/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuEpisode
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchInfo
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.DanmakuSubject
import me.him188.ani.danmaku.api.provider.MatchingDanmakuProvider
import me.him188.ani.danmaku.api.provider.videoFileForMatching
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.thisLogger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 一台弹幕服务器.
 *
 * @param baseUrl 不带结尾的 `/` 与 `/api/v2`, 例如 `https://example.com/api/v1/token`. 里面可能有访问令牌, 不能写日志
 * @param autoMatch 是否按番自动匹配
 * @param linkImport 是否按视频链接取弹幕
 */
class DanmakuServerEndpoint(
    val name: String,
    val baseUrl: String,
    val autoMatch: Boolean = true,
    val linkImport: Boolean = true,
)

/**
 * 服务器给的番名拆开: [name] 是番名本身, 后三项是服务器加的 (没有时为 `null`).
 *
 * @param type 服务器标的类型, 如 `TV动画`、`2D动漫`、`电视剧`
 * @param site 来自哪个站, 如 `bilibili`、`dandan`
 */
internal class ServerTitle(val name: String, val year: Int?, val type: String?, val site: String?) {
    /** 同名的真人剧、综艺 (凡人修仙传、星际牛仔都有同名真人剧), 不是动画 */
    val isLiveAction: Boolean get() = type != null && LIVE_ACTION_TYPE_WORDS.any { it in type }
}

private val LIVE_ACTION_TYPE_WORDS = listOf("电视剧", "真人", "综艺", "纪录片")

/**
 * 一台弹弹 play 兼容的弹幕服务器当弹幕源. 播放器里每台服务器是单独一项 ([mainServiceId] 就是服务器名), 所以结果不像
 * 弹弹 play 那样按原始站点拆开, 否则几台服务器的同站弹幕会合进同一个开关.
 *
 * 不填 [DanmakuMatchInfo.sourceEpisodeId]: 那是发弹幕要的弹弹 play 弹幕库 id, 服务器的分集 id 是它自己编的.
 *
 * 一台服务器有没有某个接口, 请求时看 HTTP 状态判断 (见 [DanmakuServerRouteMissingException]); 没有的接口在这个对象存续期间不再请求.
 */
class DanmakuServerProvider(
    val endpoint: DanmakuServerEndpoint,
    client: ScopedHttpClient,
    private val defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : MatchingDanmakuProvider {
    private val logger = thisLogger()
    private val server = DanmakuServerClient(client, endpoint.name, endpoint.baseUrl)

    override val providerId: DanmakuProviderId = DanmakuProviderId("$PROVIDER_ID_PREFIX${endpoint.name}")
    override val mainServiceId: DanmakuServiceId = DanmakuServiceId(endpoint.name)
    override val fetchTimeout: Duration get() = 120.seconds

    @Volatile
    private var matchRouteMissing = false

    @Volatile
    private var searchRouteMissing = false

    @Volatile
    private var linkRouteMissing = false

    val supportsAutoMatch: Boolean get() = endpoint.autoMatch && !(matchRouteMissing && searchRouteMissing)
    val supportsLinkImport: Boolean get() = endpoint.linkImport && !linkRouteMissing

    override suspend fun fetchAutomatic(request: DanmakuFetchRequest): List<DanmakuFetchResult> =
        withContext(defaultDispatcher) {
            if (!supportsAutoMatch) return@withContext emptyList()
            val located = locate(request)
                ?: return@withContext if (supportsAutoMatch) {
                    listOf(DanmakuFetchResult.noMatch(providerId, mainServiceId))
                } else {
                    emptyList()
                }
            val contents = server.comments(located.episodeId)
            logger.info { "${endpoint.name}: fetched ${contents.size} danmaku" }
            listOf(resultOf(contents, located.method))
        }

    private class Located(val episodeId: Long, val method: DanmakuMatchMethod)

    /**
     * 先用分集搜索 (快, 标题对得上才认), 再让服务器按文件名匹配 (它可能现搜现导入, 慢, 结果由服务器判断),
     * 最后才用真实的视频文件名. 名字只试前两个: 服务器没有这部番时每个名字都可能等上一分钟.
     */
    private suspend fun locate(request: DanmakuFetchRequest): Located? {
        // 只有正片的整数集号能问: 特别篇 SP1 的 number 也是 1, 拿去问会得到正片第 1 集
        val episode = ((request.episodeEp ?: request.episodeSort) as? EpisodeSort.Normal)
            ?.takeUnless { it.isPartial }
            ?.number
            ?.toInt()
        val names = (listOf(request.subjectPrimaryName) + request.subjectNames)
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_NAMES)
        val expected = request.subjectNames.mapTo(HashSet()) { comparableName(it) }
        val year = request.subjectPublishDate.year.takeIf { it > 0 }
        var failure: Exception? = null

        suspend fun attempt(block: suspend () -> Located?): Located? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DanmakuServerRouteMissingException) {
            logger.info { "${endpoint.name}: ${e.message}" }
            null
        } catch (e: Exception) {
            logger.warn { "${endpoint.name}: ${e.message}" }
            failure = e
            null
        }

        if (episode != null) {
            for (name in names) {
                if (searchRouteMissing) break
                attempt { searchEpisode(name, episode, expected, year) }?.let { return it }
            }
            for (name in names) {
                if (matchRouteMissing) break
                attempt { matchFile(seasonEpisodeFileName(name, episode), null, null, expected, year) }?.let { return it }
            }
        }
        if (!matchRouteMissing) {
            attempt {
                request.videoFileForMatching()?.let { file ->
                    matchFile(file.filename, file.fileSize, file.videoDuration, expected, year)
                }
            }?.let { return it }
        }
        failure?.let { throw it }
        return null
    }

    private suspend fun searchEpisode(name: String, episode: Int, expected: Set<String>, year: Int?): Located? {
        val animes = try {
            server.searchEpisodes(name, episode)
        } catch (e: DanmakuServerRouteMissingException) {
            searchRouteMissing = true
            throw e
        }
        val candidates = animes.mapNotNull { anime ->
            val title = parseServerTitle(anime.animeTitle.orEmpty())
            if (!title.fits(expected, year)) return@mapNotNull null
            val picked = pickEpisode(anime.episodes, episode) ?: return@mapNotNull null
            Candidate(anime.animeTitle.orEmpty(), picked, sourcePriority(title, year))
        }
        val best = candidates.minOfOrNull { it.priority } ?: return null
        val chosen = mostComments(candidates.filter { it.priority == best }.take(MAX_COMPARED_SOURCES))
        return Located(
            chosen.episode.episodeId,
            DanmakuMatchMethod.Exact(chosen.animeTitle, chosen.episode.episodeTitle.orEmpty()),
        )
    }

    /** 分集搜索里对得上的一条: 某个站上的这一集 */
    private class Candidate(val animeTitle: String, val episode: ServerEpisode, val priority: Int)

    /**
     * 同一集在服务器上常有几个站的版本, 条数能差好几倍, 服务器给的顺序也不按条数排 (实测一集排第一的站一万条,
     * 排第二的四万条). 排在前面的几个同时只问条数, 取最多的; 一样多时取靠前的, 都问不到时取第一个.
     */
    private suspend fun mostComments(candidates: List<Candidate>): Candidate {
        if (candidates.size == 1) return candidates.single()
        val counts = coroutineScope {
            candidates.map { candidate ->
                async {
                    try {
                        server.commentCount(candidate.episode.episodeId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.info { "${endpoint.name}: comment count unavailable: ${e.message}" }
                        null
                    }
                }
            }.awaitAll()
        }
        logger.info { "${endpoint.name}: comment counts of ${candidates.size} sources: $counts" }
        val index = counts.withIndex().filter { it.value != null }.maxByOrNull { it.value ?: 0 }?.index ?: 0
        return candidates[index]
    }

    private suspend fun matchFile(
        fileName: String,
        fileSize: Long?,
        videoDuration: Duration?,
        expected: Set<String>,
        year: Int?,
    ): Located? {
        val response = try {
            server.match(fileName, fileSize, videoDuration)
        } catch (e: DanmakuServerRouteMissingException) {
            matchRouteMissing = true
            throw e
        }
        // isMatched 为假时是几个拿不准的候选, 只认标题对得上的; 服务器拿得准的也不要真人剧
        val match = if (response.isMatched) {
            response.matches.firstOrNull { !parseServerTitle(it.animeTitle.orEmpty()).isLiveAction }
        } else {
            response.matches.firstOrNull { parseServerTitle(it.animeTitle.orEmpty()).fits(expected, year) }
        } ?: return null
        val title = match.animeTitle.orEmpty()
        val episodeTitle = match.episodeTitle.orEmpty()
        return Located(
            match.episodeId,
            if (comparableTitle(title) in expected) {
                DanmakuMatchMethod.ExactSubjectFuzzyEpisode(title, episodeTitle)
            } else {
                DanmakuMatchMethod.Fuzzy(title, episodeTitle)
            },
        )
    }

    /** 搜索结果可能带着整部的分集, 也可能只有问到的那一集 */
    private fun pickEpisode(episodes: List<ServerEpisode>, episode: Int): ServerEpisode? {
        episodes.firstOrNull { it.episodeIndex == episode || it.episodeNumber?.toIntOrNull() == episode }
            ?.let { return it }
        episodes.singleOrNull()?.let { return it }
        val pattern = Regex("""(?:第|EP?\s*|#)\s*0*$episode(?:\D|$)""", RegexOption.IGNORE_CASE)
        return episodes.firstOrNull { pattern.containsMatchIn(it.episodeTitle.orEmpty()) }
    }

    override suspend fun fetchSubjectList(name: String): List<DanmakuSubject> = withContext(defaultDispatcher) {
        // 库里没有时, 御坂先回一条进度占位, 自己在后台搜; 过一会儿再问才有结果
        repeat(PLACEHOLDER_RETRIES) {
            val animes = server.searchAnime(name)
            val real = animes.filterNot { it.isPlaceholder }
            if (real.isNotEmpty() || animes.isEmpty()) return@withContext real.mapNotNull { it.toSubjectOrNull() }
            delay(PLACEHOLDER_RETRY_DELAY)
        }
        emptyList()
    }

    private val ServerAnime.isPlaceholder: Boolean
        get() = bangumiId in PLACEHOLDER_IDS || animeId?.toString() in PLACEHOLDER_IDS

    private fun ServerAnime.toSubjectOrNull(): DanmakuSubject? {
        val id = bangumiId?.takeIf { it.isNotBlank() } ?: animeId?.toString() ?: return null
        return DanmakuSubject(id, animeTitle ?: id)
    }

    override suspend fun fetchEpisodeList(subject: DanmakuSubject): List<DanmakuEpisode> =
        withContext(defaultDispatcher) {
            server.bangumiEpisodes(subject.id).map { DanmakuEpisode(it.episodeId.toString(), it.episodeTitle.orEmpty()) }
        }

    override suspend fun fetchDanmakuList(
        subject: DanmakuSubject,
        episode: DanmakuEpisode,
    ): List<DanmakuFetchResult> = withContext(defaultDispatcher) {
        val episodeId = episode.id.toLongOrNull() ?: throw IllegalArgumentException("Invalid episode id: ${episode.id}")
        val contents = server.comments(episodeId)
        listOf(resultOf(contents, DanmakuMatchMethod.Exact(subject.name, episode.name)))
    }

    /**
     * 让服务器按视频链接取弹幕.
     *
     * @throws DanmakuServerRouteMissingException 这台服务器不支持, 之后 [supportsLinkImport] 为 `false`
     * @throws DanmakuServerException
     */
    suspend fun fetchByLink(url: String): List<DanmakuContent> = withContext(defaultDispatcher) {
        try {
            server.externalComments(url)
        } catch (e: DanmakuServerRouteMissingException) {
            linkRouteMissing = true
            throw e
        }
    }

    /**
     * 测连接与令牌: 请求一次版本接口.
     *
     * @throws DanmakuServerRouteMissingException 连得上, 但这台服务器没有版本接口 (令牌错误时也可能是这样)
     * @throws DanmakuServerException 连不上或令牌不对
     */
    suspend fun probe() {
        server.version()
    }

    private fun resultOf(contents: List<DanmakuContent>, method: DanmakuMatchMethod) = DanmakuFetchResult(
        providerId = providerId,
        matchInfo = DanmakuMatchInfo(mainServiceId, contents.size, method),
        // id 加前缀: 各服务器与弹弹 play 的编号互不相干, 本地缓存又以 id 为主键
        list = contents.mapIndexed { index, content ->
            DanmakuInfo(
                id = "${providerId.id}:$index",
                serviceId = mainServiceId,
                senderId = "",
                content = content,
            )
        },
    )

    companion object {
        private const val PROVIDER_ID_PREFIX = "server:"
        private const val MAX_NAMES = 2

        /** 同一集最多比几个站的条数: 多问一个站, 服务器就可能要多去那个站抓一次 */
        private const val MAX_COMPARED_SOURCES = 3
        private const val PLACEHOLDER_RETRIES = 6
        private val PLACEHOLDER_RETRY_DELAY = 3.seconds

        /** 御坂的「搜索中」占位条目 */
        private val PLACEHOLDER_IDS = setOf("999999998", "999999999")

        private val SEASON = Regex(
            """第\s*([0-9一二三四五六七八九十]+)\s*[季期]|\bSeason\s*(\d+)\b|\b(\d+)(?:st|nd|rd|th)\s+Season\b""",
            RegexOption.IGNORE_CASE,
        )
        private const val CHINESE_DIGITS = "一二三四五六七八九"

        /**
         * 服务器在名字后面加的「(年份)【类型】from 站名」, 如 `孤独摇滚！(2022)【TV动画】from bilibili`.
         * 只认标题末尾这一段: 名字本身也可能带【】, 如 `【我推的孩子】(2023)【TV动画】from dandan`.
         */
        private val TITLE_SUFFIX = Regex("""\s*(?:[(（](\d{4}|未知)[)）])?\s*(?:【([^】]*)】)?\s*from\s+(\S+)\s*$""")

        /** 同一部番在不同站上标的年份可能差一年 (跨年播出) */
        private const val YEAR_TOLERANCE = 1

        /** 拼一个服务器都认得的文件名: `番名 S01E03`. 名字里的季号挪到 S 后面, 免得解析时当成标题的一部分. */
        internal fun seasonEpisodeFileName(name: String, episode: Int): String {
            val match = SEASON.find(name)
            val season = match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let(::parseSeasonNumber) ?: 1
            val title = (if (match == null) name else name.removeRange(match.range)).trim()
            return "${title.ifEmpty { name }} S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        }

        private fun parseSeasonNumber(text: String): Int? {
            text.toIntOrNull()?.let { return it }
            if (text == "十") return 10
            val tens = text.indexOf('十')
            if (tens < 0) return CHINESE_DIGITS.indexOf(text).takeIf { it >= 0 }?.plus(1)
            val high = if (tens == 0) 1 else CHINESE_DIGITS.indexOf(text[0]) + 1
            val low = if (tens == text.lastIndex) 0 else CHINESE_DIGITS.indexOf(text[tens + 1]) + 1
            return high * 10 + low
        }

        /** 拆出服务器加在名字后面的年份、类型与站名; 没有这一段时整个标题都是名字 */
        internal fun parseServerTitle(title: String): ServerTitle {
            val match = TITLE_SUFFIX.find(title) ?: return ServerTitle(title.trim(), null, null, null)
            return ServerTitle(
                name = title.substring(0, match.range.first).trim(),
                year = match.groupValues[1].toIntOrNull(),
                type = match.groupValues[2].ifEmpty { null },
                site = match.groupValues[3],
            )
        }

        /** 去掉服务器加的那一段与标点, 用来比较是不是同一个名字 */
        internal fun comparableTitle(title: String): String = comparableName(parseServerTitle(title).name)

        private fun comparableName(name: String): String = name.filter { it.isLetterOrDigit() }.lowercase()

        /** 名字对得上、不是真人剧、年份 (两边都知道时) 差不过一年 */
        private fun ServerTitle.fits(expected: Set<String>, year: Int?): Boolean =
            comparableName(name) in expected && !isLiveAction &&
                    (year == null || this.year == null || abs(this.year - year) <= YEAR_TOLERANCE)

        /** 同名的候选里: 年份正好对上的优先; 来自弹弹 play 的排后面, 应用本身就有那一份 */
        private fun sourcePriority(title: ServerTitle, year: Int?): Int {
            val yearPenalty = if (year != null && title.year != null && title.year != year) 2 else 0
            val dandanPenalty = if (title.site.equals("dandan", ignoreCase = true)) 1 else 0
            return yearPenalty + dandanPenalty
        }
    }
}
