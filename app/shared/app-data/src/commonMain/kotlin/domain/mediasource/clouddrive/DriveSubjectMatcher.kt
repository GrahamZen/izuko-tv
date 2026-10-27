/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.him188.ani.app.data.models.episode.EpisodeDurations
import me.him188.ani.app.data.network.TmdbEpisodeMap
import me.him188.ani.app.data.network.TmdbSubjectMapRepository
import me.him188.ani.app.domain.mediasource.clouddrive.DrivePathAttribution.Attribution
import me.him188.ani.app.domain.mediasource.search.KeywordStyle
import me.him188.ani.app.domain.mediasource.search.SearchKeyword
import me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan
import me.him188.ani.app.domain.mediasource.search.TitleForms
import me.him188.ani.app.domain.mediasource.search.TitleIdentities
import me.him188.ani.app.domain.mediasource.search.TitleIdentity
import me.him188.ani.app.domain.mediasource.search.WorkKind
import me.him188.ani.app.domain.mediasource.search.WorkKinds
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 在网盘里找一个条目的视频文件.
 */
internal interface DriveBrowser {
    /**
     * 按名字搜索整个网盘 (第一页).
     */
    suspend fun search(keyword: String): List<DriveFile>

    /**
     * 列出文件夹的直接子项.
     */
    suspend fun listFolder(folderId: String): List<DriveFile>
}

/**
 * 条目的分集对 TMDB 季集的反查 (来自 bangumi-tmdb-map 对应表). 网盘里常是 Jellyfin / Plex 之类按 TMDB 整理的 `S02E05`,
 * 而 Bangumi 常把 TMDB 的一季当成独立条目、名字里不写季 (天降之物f 是 TMDB 的第 2 季), 只看条目名认季会认错.
 */
internal fun interface TmdbEpisodeNumbering {
    /** TMDB (季, 集) → [request] 这个条目的集; 表里没有这个条目或没有逐集对位时为 null. */
    suspend fun of(request: MediaFetchRequest): Map<Pair<Int, Int>, EpisodeSort>?

    companion object {
        val None = TmdbEpisodeNumbering { null }

        fun of(repository: TmdbSubjectMapRepository) = of { subjectId -> repository.lookup(subjectId)?.episodes }

        /** [episodesOf]: 条目 id → 对应表里的 episodes 列 (编码见 [TmdbEpisodeMap]). */
        fun of(episodesOf: suspend (subjectId: Int) -> String?) = TmdbEpisodeNumbering { request ->
            val subjectId = request.subjectId.toIntOrNull() ?: return@TmdbEpisodeNumbering null
            // 本篇按集号接续, 要全部分集才排得出第几个
            if (request.episodes.isEmpty()) return@TmdbEpisodeNumbering null
            val map = episodesOf(subjectId)?.let { TmdbEpisodeMap.parse(it) } ?: return@TmdbEpisodeNumbering null
            map.resolveSorts(request.episodes.map { it.sort })
                .entries.associate { (sort, tmdb) -> tmdb to sort }
                .ifEmpty { null }
        }
    }
}

/**
 * 把条目对到网盘里的视频文件.
 *
 * 1. 关键词来自搜索计划 ([SubjectSearchPlan.keywords], 按标题原样找的排法 [KeywordStyle.literal]): 条目每个名字的主标题
 *    (中文名、原名、别名, 去掉季标记与副标题) 一起搜; 英文别名很重要, 剧集发布的文件夹常常只有英文名. 都没搜到相关的文件时,
 *    依次再搜别的写法、副标题、系列名与别的叫法 (最多 [MAX_FALLBACK_SEARCHES] 次), 搜到为止.
 * 2. 服务端的匹配比较宽 (英文按词), 所以再在本地核对: 归一化后的文件或文件夹名要包含关键词.
 * 3. 搜到的文件夹往下展开几层 (网盘搜索常只返回命中的文件夹本身, 里面的文件名不含关键词时不会单独返回).
 * 4. 展开文件夹时, 按季分好的一层里不是这个条目的季的文件夹 (见 [DriveSubjectSeason.otherSeasonFolders]) 不往下列:
 *    里面没写季的视频认的都是那一季, 最后也会按季去掉.
 * 5. 每个视频 ([matchEpisodes]): 自动找到的先看路径点名的是哪一部 ([DrivePathAttribution]), 点名别的部的不要; 再看作品类别,
 *    写明是剧场版、外传、OVA ([WorkKinds.of]) 而条目不是这一类的不要 (整个系列的合集里它们的视频也从 01 编起,
 *    「TV+剧场版」「1-4季」这类合集名不算), 真人版、短剧一律不要; 最后从文件名解析集号, 按 [DriveEpisodeResolver] 认是哪一集.
 *    只有一集的条目 (剧场版) 文件名里多半没有集号, 认不出集号的正片就当作那一集.
 *    网盘给的视频时长与认出的那一集 (条目服务记的时长) 差得离谱的不要 ([EpisodeDurations]): 短剧、同名的剧场版.
 */
internal class DriveSubjectMatcher(
    private val browser: DriveBrowser,
    private val numbering: TmdbEpisodeNumbering = TmdbEpisodeNumbering.None,
    /** 自动搜索 ([match]) 与播放线索里的文件夹 ([matchFolder]) 按路径认部 (见 [TitleIdentity]); 用户指定的文件夹不查 */
    private val identities: TitleIdentities = TitleIdentities.None,
) {
    class MatchedFile(
        val file: DriveFile,
        /**
         * 从搜到的文件夹到文件所在文件夹的路径 (名字). 文件本身被搜到时为空.
         */
        val folders: List<String>,
        val episode: EpisodeSort,
    )

    suspend fun match(request: MediaFetchRequest): List<MatchedFile> {
        val identity = identities.of(request)
        val keywords = SubjectSearchPlan.of(request).keywords(KeywordStyle.literal(NAME_KEYWORDS), subtitles = identity?.parts?.keywords.orEmpty())
        if (keywords.primary.isEmpty()) return emptyList()

        var hits = searchAll(keywords.primary)
        if (hits.none { it.isRelevant }) {
            for (keyword in keywords.fallback.take(MAX_FALLBACK_SEARCHES)) {
                hits = hits + searchAll(listOf(keyword), failOnError = false)
                if (hits.any { it.isRelevant }) break
            }
        }
        val tmdbEpisodes = numbering.of(request)
        val candidates = collectCandidates(hits, DriveSubjectSeason.of(request, tmdbEpisodes, identity).otherSeasonFolders())
        return matchEpisodes(request, candidates, tmdbEpisodes, identity = identity)
    }

    /** 搜 [keyword] 搜到的文件或文件夹. 名字包含关键词 (比较写法) 的才算相关 ([isRelevant]): 服务端的匹配比较宽. */
    private class Hit(val keyword: SearchKeyword, val file: DriveFile) {
        val isRelevant: Boolean get() = TitleForms.compact(keyword.keyword) in TitleForms.compact(file.fileName)
    }

    /**
     * 用户指定「这个条目就在这个文件夹里」时: 把文件夹 (往下 [MAX_DEPTH] 层) 里的视频按文件名认集, 不按条目名认季
     * (文件名写了季集且条目在对应表里时照样按表换算). 文件夹打不开 (被删了) 时返回空.
     */
    suspend fun matchPickedFolder(request: MediaFetchRequest, folderId: String, folderName: String): List<MatchedFile> {
        val candidates = try {
            listCandidates(folderId, listOf(folderName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveAuthException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to list picked drive folder $folderId" }
            return emptyList()
        }
        return matchEpisodes(request, candidates, numbering.of(request), trustFolders = true)
    }

    /**
     * 播放线索里的文件夹 (见 [me.him188.ani.app.domain.media.continuation.DriveFolderLineage]): 列出 (往下 [MAX_DEPTH] 层) 里面的视频,
     * 照自动搜索的规则认季认集, [path] 是它从搜到的那个文件夹起的路径. 文件夹是同系列别的作品 (自动选错过一次被记下) 时返回 null, 线索就此作废;
     * 打不开 (网络、被删了) 时返回空.
     */
    suspend fun matchFolder(request: MediaFetchRequest, folderId: String, path: List<String>): List<MatchedFile>? {
        val identity = identities.of(request)
        if (identity?.namesOtherWork(path) == true) {
            logger.info { "Lineage drive folder ${path.joinToString("/")} is another work" }
            return null
        }
        val tmdbEpisodes = numbering.of(request)
        val candidates = try {
            listCandidates(folderId, path, DriveSubjectSeason.of(request, tmdbEpisodes, identity).otherSeasonFolders())
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveAuthException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to list lineage drive folder $folderId" }
            return emptyList()
        }
        return matchEpisodes(request, candidates, tmdbEpisodes, identity = identity)
    }

    /** 文件夹 [folderId] (往下 [MAX_DEPTH] 层, 每层 [skipFolders] 挑出的不列) 里的视频, 各带从 [path] 起的所在路径. */
    private suspend fun listCandidates(
        folderId: String,
        path: List<String>,
        skipFolders: (List<String>) -> Set<String> = { emptySet() },
    ): List<Candidate> {
        val candidates = ArrayList<Candidate>()
        suspend fun walk(id: String, path: List<String>, depth: Int) {
            val children = browser.listFolder(id)
            val skipped = skipFolders(children.filter { it.dir }.map { it.fileName })
            for (child in children) {
                if (candidates.size >= MAX_FILES) return
                if (child.isVideo) candidates += Candidate(child, path)
                else if (child.dir && depth < MAX_DEPTH && child.fileName !in skipped) walk(child.fid, path + child.fileName, depth + 1)
            }
        }
        walk(folderId, path, depth = 0)
        return candidates
    }

    /**
     * 一个可能属于这个条目的视频文件.
     *
     * @param folders 从最外层到文件所在文件夹的名字, 用来认季 (越靠里的越优先)
     */
    class Candidate(val file: DriveFile, val folders: List<String>)

    /** 一起搜 [keywords]. 登录失效等错误要报给用户, 不能当成「没搜到」: [failOnError] 时一个都没搜成功就抛出第一个错误. */
    private suspend fun searchAll(keywords: List<SearchKeyword>, failOnError: Boolean = true): List<Hit> = coroutineScope {
        val semaphore = Semaphore(SEARCH_CONCURRENCY)
        val results = keywords.map { keyword ->
            async {
                semaphore.withPermit {
                    try {
                        Result.success(browser.search(keyword.keyword).map { Hit(keyword, it) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                }
            }
        }.awaitAll()
        // 只要有一个关键词搜成功, 就用已有的结果
        if (failOnError && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        results.flatMap { it.getOrNull().orEmpty() }
    }

    private suspend fun collectCandidates(hits: List<Hit>, skipFolders: (List<String>) -> Set<String>): List<Candidate> {
        val relevant = hits.filter { it.isRelevant }
        logger.info {
            hits.groupBy { it.keyword.keyword }.entries.joinToString("; ", prefix = "Cloud drive search: ") { (keyword, list) ->
                val kept = list.filter { it in relevant }
                "「$keyword」 ${list.size} hits, ${kept.size} kept " +
                        kept.take(LOGGED_NAMES).joinToString(prefix = "[", postfix = "]") { (if (it.file.dir) "dir:" else "") + it.file.fileName } +
                        (list - kept.toSet()).take(LOGGED_NAMES).joinToString(prefix = " dropped [", postfix = "]") { it.file.fileName }
            }
        }
        val candidates = LinkedHashMap<String, Candidate>()
        val listedFolders = HashSet<String>()
        var listedCount = 0
        var skippedCount = 0

        suspend fun walk(folder: DriveFile, path: List<String>, depth: Int) {
            if (!listedFolders.add(folder.fid) || listedCount >= MAX_LISTED_FOLDERS) return
            listedCount++
            val children = try {
                browser.listFolder(folder.fid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudDriveAuthException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to list drive folder ${folder.fid}" }
                return
            }
            val skipped = skipFolders(children.filter { it.dir }.map { it.fileName })
            for (child in children) {
                if (candidates.size >= MAX_FILES) return
                if (child.isVideo) {
                    candidates.getOrPut(child.fid) { Candidate(child, path) }
                } else if (child.dir && depth < MAX_DEPTH) {
                    if (child.fileName in skipped) skippedCount++ else walk(child, path + child.fileName, depth + 1)
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
        logger.info { "Cloud drive search: ${candidates.size} candidate videos, listed $listedCount folders, skipped $skippedCount of other seasons" }
        return candidates.values.toList()
    }

    companion object {
        private val logger = logger<DriveSubjectMatcher>()

        private const val SEARCH_CONCURRENCY = 3

        /** 按名字找时最多用几个名字的主标题 (自己网盘的搜索、固定合集里对文件夹名) */
        const val NAME_KEYWORDS = 8

        /** 名字都没搜到相关的文件时, 最多再搜几个回退关键词 */
        private const val MAX_FALLBACK_SEARCHES = 3
        private const val MAX_MATCHED_FOLDERS = 6
        private const val MAX_LISTED_FOLDERS = 40
        private const val MAX_DEPTH = 2
        private const val MAX_FILES = 600

        /** 日志里每类最多列几个名字. */
        private const val LOGGED_NAMES = 4

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
         * 从候选文件里挑出这个条目的剧集: 认部、作品类别、集号 (见类说明第 5 条), 不是花絮、不是太小的样片.
         *
         * @param tmdbEpisodes TMDB (季, 集) → 条目的集 (见 [TmdbEpisodeNumbering]); null = 表里没有, 只按条目名认季
         * @param trustFolders 候选来自用户指定的文件夹: 不按路径认部、不查作品类别, 也不按条目的季认 (对应表照样用)
         * @param identity 按路径认部 ([DrivePathAttribution]); 自动按名字找时才给. 也带着系列关系图推出的季 (认季用)
         */
        fun matchEpisodes(
            request: MediaFetchRequest,
            candidates: List<Candidate>,
            tmdbEpisodes: Map<Pair<Int, Int>, EpisodeSort>? = null,
            trustFolders: Boolean = false,
            identity: TitleIdentity? = null,
        ): List<MatchedFile> {
            val season = DriveSubjectSeason.of(request, tmdbEpisodes, identity)
            val attribution = identity?.let { DrivePathAttribution(it, season, request) }
            val resolver = DriveEpisodeResolver(request, season, tmdbEpisodes, identity, trustFolders)
            val kinds = KindCheck(request, trustFolders, identity?.ownCategoryKinds.orEmpty())
            val durations = request.episodes.takeUnless { trustFolders }.orEmpty().associate { it.sort to it.durationSeconds }
            val onlyEpisode = request.episodes.singleOrNull()?.sort

            // 没对上的按原因记几个例子, 搜到了却一集都没有时看得出卡在哪
            val dropped = LinkedHashMap<String, MutableList<String>>()
            fun drop(reason: String, candidate: Candidate): MatchedFile? {
                dropped.getOrPut(reason) { mutableListOf() } += (candidate.folders.takeLast(1) + candidate.file.fileName).joinToString("/")
                return null
            }

            val titles = DriveNameParser.titlePatterns(SubjectSearchPlan.of(request).ownNames)
            // 条目本身是 OVA 时文件名里的 OVA 标记不是「附带的番外」
            val ovaIsMain = kinds.isOwn(WorkKind.OVA)
            val parsedFiles = candidates.associateWith {
                DriveNameParser.parseFile(if (ovaIsMain) WorkKinds.withoutOvaMarker(it.file.fileName) else it.file.fileName, titles)
            }
            // 只有一集的条目: 文件夹里只有这一个视频时, 名字里剩下的数字不是集号 (「酷漫404」)
            val videosIn = candidates.groupingBy { it.folders }.eachCount()
            val continuousFolders = resolver.continuousFolders(parsedFiles)
            val matched = candidates.mapNotNull { candidate ->
                val named = attribution?.attribute(candidate.file.fileName, candidate.folders)
                if (named == Attribution.OTHER) return@mapNotNull drop("other work", candidate)
                if (kinds.rejects(candidate)) return@mapNotNull drop("other kind", candidate)
                val parsed = parsedFiles.getValue(candidate)
                if (parsed.isExtra) return@mapNotNull drop("extra", candidate)
                val episode = parsed.episode?.takeUnless { onlyEpisode != null && !parsed.anchored && videosIn[candidate.folders] == 1 }
                    ?: onlyEpisode ?: return@mapNotNull drop("no episode number", candidate)
                if (candidate.file.size in 1 until MIN_VIDEO_SIZE) return@mapNotNull drop("too small", candidate)
                when (val resolved = resolver.resolve(candidate, parsed, episode, named, candidate.folders in continuousFolders)) {
                    is DriveEpisodeResolver.Resolved.Episode -> {
                        val expected = durations[resolved.sort]
                        if (EpisodeDurations.isMismatch(candidate.file.durationSeconds.toLong(), expected)) {
                            drop("duration ${candidate.file.durationSeconds}s, episode ${expected}s", candidate)
                        } else {
                            MatchedFile(candidate.file, candidate.folders, resolved.sort)
                        }
                    }

                    is DriveEpisodeResolver.Resolved.Dropped -> drop(resolved.reason, candidate)
                }
            }
            if (candidates.isNotEmpty()) {
                logger.info {
                    "Matched ${matched.size} of ${candidates.size} candidates for ${request.subjectNameCN ?: request.subjectId}" +
                            dropped.entries.joinToString(prefix = "; dropped: ", separator = "; ") { (reason, names) ->
                                "$reason ${names.size} ${names.take(LOGGED_NAMES)}"
                            }.takeIf { dropped.isNotEmpty() }.orEmpty()
                }
            }
            return matched
        }

        /**
         * 作品类别: 候选的文件夹 (合集名只算 [WorkKind.inCollections] 的) 与文件名写明了条目不是的那一类.
         * 真人版、短剧一律不要; 剧场版、外传、OVA 在条目本身是这一类 (名字里写着的, 或 Bangumi 记的类型 [categoryKinds])、
         * 只有一集 (剧场版条目的名字里常不写剧场版) 或候选来自用户指定的文件夹时不查.
         */
        private class KindCheck(request: MediaFetchRequest, trustFolders: Boolean, categoryKinds: Set<WorkKind>) {
            private val ownKinds = subjectNamesOf(request).flatMapTo(HashSet()) { WorkKinds.of(it) } + categoryKinds
            private val checked = when {
                trustFolders -> emptySet()
                request.episodes.size != 1 -> WorkKind.entries.toSet()
                else -> setOf(WorkKind.LIVE_ACTION, WorkKind.SHORT_DRAMA)
            }

            fun isOwn(kind: WorkKind): Boolean = kind in ownKinds

            fun rejects(candidate: Candidate): Boolean {
                val folderKinds = candidate.folders.flatMap { WorkKinds.outsideCollectionsOf(it) }
                return (folderKinds + WorkKinds.of(candidate.file.fileName)).any { it in checked && it !in ownKinds }
            }
        }
    }
}
