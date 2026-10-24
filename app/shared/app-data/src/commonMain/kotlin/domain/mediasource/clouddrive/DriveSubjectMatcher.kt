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
import me.him188.ani.app.data.network.TmdbEpisodeMap
import me.him188.ani.app.data.network.TmdbSubjectMapRepository
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
 * 1. 用条目的每个名字 (中文名、原名、别名, 去掉季标记与副标题) 搜索. 英文别名很重要: 剧集发布的文件夹常常只有英文名.
 * 2. 服务端的匹配比较宽 (英文按词), 所以再在本地核对: 归一化后的文件或文件夹名要包含关键词.
 * 3. 搜到的文件夹往下展开几层 (网盘搜索常只返回命中的文件夹本身, 里面的文件名不含关键词时不会单独返回).
 * 4. 从文件名解析集号, 从文件名 (没写时取最近一层写了季的文件夹) 解析季, 与条目的季 ([DriveSubjectSeason]) 对不上的去掉.
 *    只有一集的条目 (剧场版) 文件名里多半没有集号, 认不出集号的正片就当作那一集.
 *    认出了季与集 (`S02E05`, 或「Season 1」文件夹里的 `12.mkv`) 而条目在对应表里有逐集对位 ([TmdbEpisodeNumbering]) 时,
 *    按表换算成条目的集, 不看下面的季规则; 表里没有这个季集时, 季正是条目的季 (TMDB 把整部算一季接着排, 文件按本季写成 `S04E01`)
 *    就照下面的季规则认, 否则不是这个条目. 文件名自己写的季要与条目的季相同; 文件夹给的季在条目推不出季时当第一季比.
 * 5. 展开文件夹时, 按季分好的一层里不是这个条目的季的文件夹 (见 [otherSeasonFolders]) 不往下列:
 *    里面没写季的视频认的都是那一季, 最后也会按季去掉.
 * 6. 文件夹或文件名写明是剧场版、外传、OVA ([DriveNameParser.workKindsOf]) 而条目不是这一类的不要: 整个系列的合集里
 *    它们的视频也从 01 编起. 写着「TV+剧场版」「1-4季」这类合集名的文件夹不算.
 * 7. 网盘把系列里的一段当成单独一部、从这一段起编季 (「死神 千年血战篇 -祸进谭-」分享里的 `S01E41`, TMDB 记成 S2E41):
 *    路径点名了这一部 ([DriveSharePartSearch.namesOwnPart])、集号又正是这一部的系列连续集号 (bangumi 的 sort 与 ep 不同) 时,
 *    按季对不上的也照集号认.
 *
 * 季的规则 (条目的季见 [DriveSubjectSeason]):
 * - 推不出季 (第一季或只有一季): 文件写明是第 2 季及以后的去掉, 其余保留.
 * - 条目名写明是第 N 季 (N ≥ 2): 文件或文件夹写明是第 N 季的保留, 写明别的季的去掉;
 *   都没写的, 只有集号等于条目某一集的系列绝对集号时保留 —— 否则第一季文件夹里的 `03.mp4` 会被当成第 N 季第 3 集.
 * - 推出来是第 N 季 (条目名没写): 写明别的季的去掉, 写明第 N 季与都没写的保留 (「进击的巨人 最终季 Part.2 / 01.mp4」).
 */
internal class DriveSubjectMatcher(
    private val browser: DriveBrowser,
    private val numbering: TmdbEpisodeNumbering = TmdbEpisodeNumbering.None,
    /** 自动搜索 ([match]) 与播放线索里的文件夹 ([matchFolder]) 排除同系列别的作品; 用户指定的文件夹不查 */
    private val otherWorks: OtherWorks = OtherWorks.None,
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
        val keywords = keywordsOf(subjectNamesOf(request))
        if (keywords.isEmpty()) return emptyList()

        val hits = searchAll(keywords)
        val tmdbEpisodes = numbering.of(request)
        val otherWork = otherWorks.of(request)
        val subjectSeason = DriveSubjectSeason.of(request, tmdbEpisodes, otherWork?.seriesSeason)
        val candidates = collectCandidates(hits, otherSeasonFolders(subjectSeason, tmdbEpisodes))
        return matchEpisodes(request, candidates, tmdbEpisodes, otherWork = otherWork)
    }

    private class Hit(val keyword: String, val file: DriveFile)

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
        val otherWork = otherWorks.of(request)
        if (otherWork?.isOtherWork(path.joinToString("/")) == true) {
            logger.info { "Lineage drive folder ${path.joinToString("/")} is another work" }
            return null
        }
        val tmdbEpisodes = numbering.of(request)
        val subjectSeason = DriveSubjectSeason.of(request, tmdbEpisodes, otherWork?.seriesSeason)
        val candidates = try {
            listCandidates(folderId, path, otherSeasonFolders(subjectSeason, tmdbEpisodes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudDriveAuthException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to list lineage drive folder $folderId" }
            return emptyList()
        }
        return matchEpisodes(request, candidates, tmdbEpisodes, otherWork = otherWork)
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

    private suspend fun collectCandidates(hits: List<Hit>, skipFolders: (List<String>) -> Set<String>): List<Candidate> {
        val relevant = hits.filter { hit ->
            DriveNameParser.normalize(hit.file.fileName).contains(DriveNameParser.normalize(hit.keyword))
        }
        logger.info {
            hits.groupBy { it.keyword }.entries.joinToString("; ", prefix = "Cloud drive search: ") { (keyword, list) ->
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
        private const val MAX_KEYWORDS = 8
        private const val MAX_MATCHED_FOLDERS = 6
        private const val MAX_LISTED_FOLDERS = 40
        private const val MAX_DEPTH = 2
        private const val MAX_FILES = 600

        /** 日志里每类最多列几个名字. */
        private const val LOGGED_NAMES = 4

        /** 拿来认路径的条目全名归一化后至少几个字. */
        private const val MIN_OWN_NAME_LENGTH = 3

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
         *
         * @param tmdbEpisodes TMDB (季, 集) → 条目的集 (见 [TmdbEpisodeNumbering]); null = 表里没有, 只按条目名认季
         * @param trustFolders 候选来自用户指定的文件夹: 不再按条目的季认 (对应表照样用)
         * @param otherWork 路径 (文件夹与文件名) 是同系列别的作品的去掉; 自动按名字找时才给. 也带着系列关系图推出的季与本篇名 (认季用)
         */
        fun matchEpisodes(
            request: MediaFetchRequest,
            candidates: List<Candidate>,
            tmdbEpisodes: Map<Pair<Int, Int>, EpisodeSort>? = null,
            trustFolders: Boolean = false,
            otherWork: OtherWorkGuard? = null,
        ): List<MatchedFile> {
            val subjectSeason = DriveSubjectSeason.of(request, tmdbEpisodes, otherWork?.seriesSeason)
            val ownNames = subjectNamesOf(request).map(DriveNameParser::normalize).filter { it.length >= MIN_OWN_NAME_LENGTH }
            val absoluteSorts = request.episodes
                .filter { episode -> episode.ep != null && episode.ep != episode.sort }
                .map { it.sort }
                .toSet()
            val onlyEpisode = request.episodes.singleOrNull()?.sort
            // 剧场版、外传、OVA 的文件夹与文件 (合集名除外) 不是条目的正片; 条目本身是这一类、或只有一集 (剧场版条目) 时不查
            val ownKinds = subjectNamesOf(request).flatMapTo(HashSet()) { DriveNameParser.workKindsOf(it) }
            val checkKinds = !trustFolders && request.episodes.size != 1

            // 没对上的按原因记几个例子, 搜到了却一集都没有时看得出卡在哪
            val dropped = LinkedHashMap<String, MutableList<String>>()
            fun drop(reason: String, candidate: Candidate): MatchedFile? {
                dropped.getOrPut(reason) { mutableListOf() } += (candidate.folders.takeLast(1) + candidate.file.fileName).joinToString("/")
                return null
            }

            // 网盘把系列里的一段当成单独一部、从这一段起编季: 路径点名了这一部、集号又正是它的系列连续集号, 季对不上也照集号认
            fun continuesOwnPart(candidate: Candidate, episode: EpisodeSort): Boolean =
                episode in absoluteSorts && otherWork?.parts?.namesOwnPart(candidate.file.fileName, candidate.folders) == true

            val matched = candidates.mapNotNull { candidate ->
                if (otherWork != null && otherWork.isOtherWork((candidate.folders + candidate.file.fileName).joinToString("/"))) {
                    return@mapNotNull drop("other work", candidate)
                }
                if (checkKinds && (otherKindsOf(candidate) - ownKinds).isNotEmpty()) return@mapNotNull drop("other kind", candidate)
                val parsed = DriveNameParser.parseFile(candidate.file.fileName)
                if (parsed.isExtra) return@mapNotNull drop("extra", candidate)
                val episode = parsed.episode ?: onlyEpisode ?: return@mapNotNull drop("no episode number", candidate)
                if (candidate.file.size in 1 until MIN_VIDEO_SIZE) return@mapNotNull drop("too small", candidate)
                val fileEpisode = (parsed.episode as? EpisodeSort.Normal)?.number?.takeIf { it % 1f == 0f }?.toInt()
                val season = parsed.season
                    ?: candidate.folders.asReversed().firstNotNullOfOrNull { DriveNameParser.parseFolderSeason(it) }
                // 认出了季与集 (按 TMDB 整理的 `S02E05`, 或「Season 1」文件夹里的 `12.mkv`): 表里写着它是条目的哪一集就用哪一集, 不再按季规则认.
                // TMDB 把条目单列成一部、系列关系图说它是系列的第 N 季时, 文件夹写的季多半是系列的季 (「堀与宫村 S1」是前传),
                // 路径点名了这一部 (「堀与宫村 -piece- S01」) 才是它自己的季
                val seasonInTmdb = parsed.season != null || subjectSeason?.tmdbOwnShow != true || namesSubject(candidate, ownNames) ||
                        otherWork?.parts?.namesOwnPart(candidate.file.fileName, candidate.folders) == true
                if (tmdbEpisodes != null && season != null && fileEpisode != null && seasonInTmdb) {
                    tmdbEpisodes[season to fileEpisode]?.let { return@mapNotNull MatchedFile(candidate.file, candidate.folders, it) }
                    // 表里查不到: 按本季写的 (`S04E01`, TMDB 却把整部算一季接着排), 季正是条目的季就照季规则认. 文件名自己写的季要与条目的季相同,
                    // 条目推不出季时就不是这个条目 (天降之物f 的 `S01E01`); 文件夹给的季在条目推不出季时当第一季比: 拆出来的后半常放在第一季的文件夹里从 01 编
                    val expected = if (parsed.season != null) subjectSeason?.number else subjectSeason?.number ?: 1
                    if (season != expected && !(trustFolders && parsed.season == null)) {
                        if (continuesOwnPart(candidate, episode)) return@mapNotNull MatchedFile(candidate.file, candidate.folders, episode)
                        return@mapNotNull drop("S${season}E$fileEpisode not in this subject", candidate)
                    }
                }
                if (!trustFolders && !seasonMatches(season, subjectSeason, episode, absoluteSorts)) {
                    if (continuesOwnPart(candidate, episode)) return@mapNotNull MatchedFile(candidate.file, candidate.folders, episode)
                    return@mapNotNull drop("season $season, wanted ${subjectSeason ?: 1}", candidate)
                }
                MatchedFile(candidate.file, candidate.folders, episode)
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
         * [candidate] 的路径点名了这个条目: 从文件往上 (文件名、各层文件夹、分享标题) 第一个写了条目全名 ([ownNames], 已归一化) 的一层,
         * 与文件之间没隔着写了季或集号范围的文件夹 (「堀与宫村 piece（附第1季）/ 堀与宫村 S1 / 01.mp4」的 S1 是另一套编号).
         */
        private fun namesSubject(candidate: Candidate, ownNames: List<String>): Boolean {
            if (ownNames.isEmpty()) return false
            val segments = listOf(candidate.file.fileName) + candidate.folders.asReversed()
            for ((index, segment) in segments.withIndex()) {
                val normalized = DriveNameParser.normalize(segment)
                if (ownNames.any { it in normalized }) return true
                if (index > 0 && (DriveNameParser.declaredSeasons(segment).isNotEmpty() || DriveSharePartSearch.EPISODE_RANGE.containsMatchIn(segment))) {
                    return false
                }
            }
            return false
        }

        /** 候选的文件夹 (合集名不算) 与文件名写明的作品类别. */
        private fun otherKindsOf(candidate: Candidate): Set<DriveNameParser.WorkKind> =
            (candidate.folders.filterNot(DriveNameParser::isCollectionName) + candidate.file.fileName)
                .flatMapTo(HashSet()) { DriveNameParser.workKindsOf(it) }

        /**
         * 这个条目的剧集可能写在哪些季里: 条目的季 ([DriveSubjectSeason], 推不出来当第 1 季), 以及对应表里这个条目用到的 TMDB 季
         * (按 TMDB 整理的网盘把它写成那一季, 如 TMDB 把整部算一季时的 `S01E78`).
         */
        fun seasonsOf(subjectSeason: DriveSubjectSeason?, tmdbEpisodes: Map<Pair<Int, Int>, EpisodeSort>?): Set<Int> =
            setOf(subjectSeason?.number ?: 1) + tmdbEpisodes?.keys?.map { it.first }.orEmpty()

        /**
         * 一层文件夹里不必往下列的那些: 这一层按季分好了 (至少两个文件夹各只写了一个季、且季不同), 其中季不是这个条目会写的季
         * ([seasonsOf]) 的. 按季分好的文件夹装的就是那一季, 里面没写季的视频最后也会按季去掉.
         * 只有一个文件夹写了季时不跳: 常是上传者的总标题 (「…第二季」), 里面可能连前几季一起放.
         * 名字拿不准的 (见 [DriveNameParser.singleDeclaredSeason]) 当没写季.
         */
        fun otherSeasonFolders(subjectSeason: DriveSubjectSeason?, tmdbEpisodes: Map<Pair<Int, Int>, EpisodeSort>?): (List<String>) -> Set<String> {
            val seasons = seasonsOf(subjectSeason, tmdbEpisodes)
            return { names ->
                val declared = names.associateWith { DriveNameParser.singleDeclaredSeason(it) }
                if (declared.values.filterNotNull().distinct().size < 2) emptySet()
                else declared.filterValues { it != null && it !in seasons }.keys
            }
        }

        private fun seasonMatches(
            fileSeason: Int?,
            subjectSeason: DriveSubjectSeason?,
            episode: EpisodeSort,
            absoluteSorts: Set<EpisodeSort>,
        ): Boolean {
            val target = subjectSeason?.number ?: 1
            if (target == 1) return fileSeason == null || fileSeason == 1
            if (fileSeason != null) return fileSeason == target
            // 不写季的: 条目名写明了季的只认系列绝对集号, 推出来的季照留
            return subjectSeason?.declared != true || episode in absoluteSorts
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
