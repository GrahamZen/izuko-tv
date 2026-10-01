/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 在网盘里找一个条目的视频文件.
 */
internal interface QuarkDriveBrowser {
    /**
     * 按名字搜索整个网盘 (第一页).
     */
    suspend fun search(keyword: String): List<QuarkFile>

    /**
     * 列出文件夹的直接子项.
     */
    suspend fun listFolder(folderId: String): List<QuarkFile>
}

/**
 * 把条目对到网盘里的视频文件.
 *
 * 1. 用条目的每个名字 (中文名、原名、别名, 去掉季标记与副标题) 搜索. 英文别名很重要: 剧集发布的文件夹常常只有英文名.
 * 2. 服务端的匹配比较宽 (英文按词), 所以再在本地核对: 归一化后的文件或文件夹名要包含关键词.
 * 3. 搜到的文件夹往下展开几层 (夸克搜索只返回命中的文件夹本身, 里面的文件名不含关键词时不会单独返回).
 * 4. 从文件名解析集号, 从文件名或所在文件夹解析季, 与条目的季对不上的去掉.
 *    只有一集的条目 (剧场版) 文件名里多半没有集号, 认不出集号的正片就当作那一集.
 *
 * 季的规则 (条目的季来自条目名里的 `第二季` / `Season 2` 之类的写法):
 * - 条目没写季 (第一季或只有一季): 文件写明是第 2 季及以后的去掉, 其余保留.
 * - 条目是第 N 季 (N ≥ 2): 文件或文件夹写明是第 N 季的保留, 写明别的季的去掉;
 *   都没写的, 只有集号等于条目某一集的系列绝对集号 (bangumi 的 sort 与 ep 不同) 时保留 ——
 *   否则第一季文件夹里的 `03.mp4` 会被当成第 N 季第 3 集.
 */
internal class QuarkSubjectMatcher(
    private val browser: QuarkDriveBrowser,
) {
    class MatchedFile(
        val file: QuarkFile,
        /**
         * 从搜到的文件夹到文件所在文件夹的路径 (名字). 文件本身被搜到时为空.
         */
        val folders: List<String>,
        val episode: EpisodeSort,
    )

    suspend fun match(request: MediaFetchRequest): List<MatchedFile> {
        val keywords = keywordsOf(subjectNamesOf(request))
        if (keywords.isEmpty()) return emptyList()

        val hits = searchAll(keywords)
        return matchEpisodes(request, collectCandidates(hits))
    }

    private class Hit(val keyword: String, val file: QuarkFile)

    /**
     * 一个可能属于这个条目的视频文件.
     *
     * @param folders 从最外层到文件所在文件夹的名字, 用来认季 (越靠里的越优先)
     */
    class Candidate(val file: QuarkFile, val folders: List<String>)

    private suspend fun searchAll(keywords: List<String>): List<Hit> = coroutineScope {
        val semaphore = Semaphore(SEARCH_CONCURRENCY)
        val results = keywords.map { keyword ->
            async {
                semaphore.withPermit {
                    try {
                        Result.success(browser.search(keyword).map { Hit(keyword, it) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                }
            }
        }.awaitAll()
        // 登录失效等错误要报给用户, 不能当成"没搜到"; 只要有一个关键词搜成功, 就用已有的结果
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        results.flatMap { it.getOrNull().orEmpty() }
    }

    private suspend fun collectCandidates(hits: List<Hit>): List<Candidate> {
        val relevant = hits.filter { hit ->
            DriveNameParser.normalize(hit.file.fileName).contains(DriveNameParser.normalize(hit.keyword))
        }
        val candidates = LinkedHashMap<String, Candidate>()
        val listedFolders = HashSet<String>()
        var listedCount = 0

        suspend fun walk(folder: QuarkFile, path: List<String>, depth: Int) {
            if (!listedFolders.add(folder.fid) || listedCount >= MAX_LISTED_FOLDERS) return
            listedCount++
            val children = try {
                browser.listFolder(folder.fid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuarkAuthException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to list Quark folder ${folder.fid}" }
                return
            }
            for (child in children) {
                if (candidates.size >= MAX_FILES) return
                if (child.isVideo) {
                    candidates.getOrPut(child.fid) { Candidate(child, path) }
                } else if (child.dir && depth < MAX_DEPTH) {
                    walk(child, path + child.fileName, depth + 1)
                }
            }
        }

        relevant.asSequence()
            .filter { it.file.dir }
            .distinctBy { it.file.fid }
            .take(MAX_MATCHED_FOLDERS)
            .forEach { walk(it.file, listOf(it.file.fileName), depth = 0) }

        for (hit in relevant) {
            if (hit.file.isVideo && candidates.size < MAX_FILES) {
                candidates.getOrPut(hit.file.fid) { Candidate(hit.file, emptyList()) }
            }
        }
        return candidates.values.toList()
    }

    companion object {
        private val logger = logger<QuarkSubjectMatcher>()

        private const val SEARCH_CONCURRENCY = 3
        private const val MAX_KEYWORDS = 8
        private const val MAX_MATCHED_FOLDERS = 6
        private const val MAX_LISTED_FOLDERS = 40
        private const val MAX_DEPTH = 2
        private const val MAX_FILES = 600

        /**
         * 小于这个大小的视频 (样片、广告) 不要. 大小未知 (0) 的保留.
         */
        private const val MIN_VIDEO_SIZE = 20L * 1024 * 1024

        /** 条目的所有名字 (中文名、原名、别名), 去空白去重. */
        fun subjectNamesOf(request: MediaFetchRequest): List<String> =
            (request.subjectNames + listOfNotNull(request.subjectNameCN))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        /**
         * 从候选文件里挑出这个条目的剧集: 文件名认得出集号、不是花絮、不是太小的样片, 季也对得上 (规则见类说明).
         */
        fun matchEpisodes(request: MediaFetchRequest, candidates: List<Candidate>): List<MatchedFile> {
            val targetSeason = subjectNamesOf(request).firstNotNullOfOrNull { DriveNameParser.parseSubjectSeason(it) }
            val absoluteSorts = request.episodes
                .filter { episode -> episode.ep != null && episode.ep != episode.sort }
                .map { it.sort }
                .toSet()
            val onlyEpisode = request.episodes.singleOrNull()?.sort

            return candidates.mapNotNull { candidate ->
                val parsed = DriveNameParser.parseFile(candidate.file.fileName)
                if (parsed.isExtra) return@mapNotNull null
                val episode = parsed.episode ?: onlyEpisode ?: return@mapNotNull null
                if (candidate.file.size in 1 until MIN_VIDEO_SIZE) return@mapNotNull null
                val season = parsed.season
                    ?: candidate.folders.asReversed().firstNotNullOfOrNull { DriveNameParser.parseFolderSeason(it) }
                if (!seasonMatches(season, targetSeason, episode, absoluteSorts)) return@mapNotNull null
                MatchedFile(candidate.file, candidate.folders, episode)
            }
        }

        private fun seasonMatches(
            fileSeason: Int?,
            targetSeason: Int?,
            episode: EpisodeSort,
            absoluteSorts: Set<EpisodeSort>,
        ): Boolean {
            if (targetSeason == null || targetSeason == 1) return fileSeason == null || fileSeason == 1
            if (fileSeason != null) return fileSeason == targetSeason
            return episode in absoluteSorts
        }

        /**
         * 每个名字取主标题作关键词, 按归一化结果去重. 太短的关键词 (归一化后不到 2 个字) 会搜出一堆无关文件, 不用.
         */
        fun keywordsOf(names: List<String>): List<String> {
            val seen = HashSet<String>()
            return names.asSequence()
                .map { DriveNameParser.baseTitle(it) }
                .filter { keyword ->
                    val normalized = DriveNameParser.normalize(keyword)
                    normalized.length >= 2 && seen.add(normalized)
                }
                .take(MAX_KEYWORDS)
                .toList()
        }
    }
}
