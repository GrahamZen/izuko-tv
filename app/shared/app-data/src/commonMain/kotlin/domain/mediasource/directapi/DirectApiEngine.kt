/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.directapi

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.foundation.DeviceBrowserUserAgentHolder
import me.him188.ani.app.domain.foundation.RequestUserAgentAttribute
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.direct.DirectLink
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 按 [DirectApiConfig] 执行"搜条目 -> 找剧集 -> 取地址"三步.
 *
 * 不含任何站点特有的逻辑: 站点的差异全部由配置表达.
 */
internal class DirectApiEngine(
    private val config: DirectApiConfig,
    private val client: ScopedHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val userAgent: String?
        get() = config.userAgent.takeIf { it.isNotBlank() } ?: DeviceBrowserUserAgentHolder.current

    /** bangumi 条目 id -> 站内条目 id. 站内搜索要好几次请求, 值得缓存, 包括"找不到"这个结果. */
    private val subjectIdCacheLock = Mutex()
    private val subjectIdCache = LinkedHashMap<String, String?>()

    suspend fun checkConnection(): Boolean {
        val url = buildUrl(config.subject.request.url, baseVariables() + ("subjectName" to "test"))
        return runCatching { fetchBytes(url) != null }.getOrElse { false }
    }

    /**
     * 返回该条目在本源上**全部剧集**的地址, 每条带自己的剧集号.
     *
     * 不能只返回当前集: 同一条目的各集共用一个 `MediaFetchSession`, 切集不会重新 fetch, 而选择器会把
     * 剧集号不是当前集的资源排除掉 —— 只返回当前集的话, 切到第二集时本源就一条都不剩了.
     * 按集裁剪是选择器的事, 数据源不该自己裁.
     */
    suspend fun queryLinks(request: MediaFetchRequest): List<DirectLink> {
        val bangumiSubjectId = request.subjectId.takeIf { it.isNotBlank() } ?: return emptyList()
        val resolved = resolveSubject(request, bangumiSubjectId) ?: return emptyList()

        return fetchLinesOfAllEpisodes(
            resolved.episodes,
            baseVariables(request) + ("subjectId" to resolved.subjectId),
        )
    }

    // ============================ 三步 ============================

    /** 站点上的条目, 以及它对应到本次请求的剧集. */
    private class ResolvedSubject(val subjectId: String, val episodes: List<SiteEpisode>)

    private suspend fun resolveSubject(request: MediaFetchRequest, bangumiSubjectId: String): ResolvedSubject? {
        val cachedId = subjectIdCacheLock.withLock {
            if (subjectIdCache.containsKey(bangumiSubjectId)) {
                subjectIdCache[bangumiSubjectId] ?: return null // 缓存过“这个条目在本站找不到”
            } else {
                null
            }
        }
        if (cachedId != null) {
            val variables = baseVariables(request) + ("subjectId" to cachedId)
            return ResolvedSubject(cachedId, resolveEpisodes(request, variables).episodes)
        }
        val resolved = searchSubject(request, bangumiSubjectId)
        subjectIdCacheLock.withLock {
            if (subjectIdCache.size >= SUBJECT_ID_CACHE_SIZE) {
                subjectIdCache.keys.firstOrNull()?.let { subjectIdCache.remove(it) }
            }
            subjectIdCache[bangumiSubjectId] = resolved?.subjectId
        }
        return resolved
    }

    /**
     * 在站点上找出这个 bangumi 条目.
     *
     * **通过校验的第一个候选未必就是要找的那个**: 站点可能把正片、特别篇、剧场总集篇都标上同一个
     * bangumi 条目 id (实测「孤独摇滚！」的特别篇与正片都标着 328609, 而特别篇一条线路都没有,
     * 表现就是这个数据源搜出来 0 条)。分辨的办法是看剧集: 真正对应的那个条目, 它的剧集能按 bangumi
     * **分集** id 精确命中; 特别篇只能靠集号勉强撞上一集。
     *
     * 所以精确命中过分集 id 的候选优先, 一个都命不中时退回第一个通过校验的候选 (与只看条目校验时一致)。
     * 顺利的情况 —— 第一个通过校验的候选就命中 —— 不会多发任何请求。
     */
    private suspend fun searchSubject(request: MediaFetchRequest, bangumiSubjectId: String): ResolvedSubject? {
        val subject = config.subject
        var fallback: ResolvedSubject? = null
        for (name in request.subjectNames.filter { it.isNotBlank() }.take(subject.maxNames.coerceAtLeast(1))) {
            val variables = baseVariables() + mapOf("subjectName" to name, "bangumiSubjectId" to bangumiSubjectId)
            val items = fetchItems(subject.request, variables)
            for (item in items.take(subject.maxCandidates.coerceAtLeast(1))) {
                val candidateId = item.stringByPath(subject.idPath) ?: continue
                val verify = subject.verify
                if (verify != null) {
                    val verifyRoot = fetchRoot(
                        verify,
                        variables + mapOf("candidateId" to candidateId, "subjectId" to candidateId),
                    ) ?: continue
                    if (verifyRoot.stringByPath(subject.verifyPath) != bangumiSubjectId) continue
                }
                val match = resolveEpisodes(request, baseVariables(request) + ("subjectId" to candidateId))
                val resolved = ResolvedSubject(candidateId, match.episodes)
                // 不校验就只能信搜索结果的第一条
                if (verify == null) return resolved
                if (match.exactMatches > 0) return resolved
                if (fallback == null) fallback = resolved
            }
        }
        return fallback
    }

    /**
     * 站点的剧集列表 (一次请求拿全), 对应到条目的剧集上.
     *
     * 条目的剧集已知时以它为准 (先用 bangumi 分集 id 精确对, 对不上再按集号), 这样每条资源的剧集号
     * 与条目一致; 条目剧集未知时退回站点自己的集号.
     */
    private suspend fun resolveEpisodes(
        request: MediaFetchRequest,
        variables: Map<String, String>,
    ): EpisodeMatch {
        val episode = config.episode
        val items = fetchItems(episode.request, variables)
        if (items.isEmpty()) return EpisodeMatch(emptyList(), exactMatches = 0)

        if (request.episodes.isEmpty()) {
            return EpisodeMatch(
                items.mapNotNull { item ->
                    val value = item.stringByPath(episode.valuePath) ?: return@mapNotNull null
                    val sort = item.stringByPath(episode.sortPath)?.takeIf { it.isNotBlank() }?.let { EpisodeSort(it) }
                    SiteEpisode(value, sort)
                },
                exactMatches = 0,
            )
        }

        var exactMatches = 0
        val episodes = request.episodes.mapNotNull { wanted ->
            val hit = findEpisodeItem(items, wanted) ?: return@mapNotNull null
            val value = hit.item.stringByPath(episode.valuePath) ?: return@mapNotNull null
            if (hit.exact) exactMatches++
            SiteEpisode(value, wanted.sort)
        }
        return EpisodeMatch(episodes, exactMatches)
    }

    /** [exactMatches] = 其中按 bangumi 分集 id 精确命中的集数, 用来分辨同名条目, 见 [searchSubject]. */
    private class EpisodeMatch(val episodes: List<SiteEpisode>, val exactMatches: Int)

    /** [exact] = 按 bangumi 分集 id 命中的, 而不是按集号撞上的. */
    private class EpisodeHit(val item: DataNode, val exact: Boolean)

    private fun findEpisodeItem(items: List<DataNode>, wanted: MediaFetchRequest.Episode): EpisodeHit? {
        val episode = config.episode
        // 有 bangumi 分集 id 就精确命中
        if (episode.matchPath.isNotBlank() && wanted.episodeId.isNotBlank()) {
            items.firstOrNull { item ->
                item.selectByPath(episode.matchPath).any { it.asStringOrNull() == wanted.episodeId }
            }?.let { return EpisodeHit(it, exact = true) }
        }
        // 否则按集号. 站点的集号可能写成 "1.0" 或 "01", 所以按数值比
        if (episode.sortPath.isNotBlank()) {
            val target = wanted.sort.toString().toFloatOrNull()
            if (target != null) {
                return items.firstOrNull { item ->
                    item.stringByPath(episode.sortPath)?.toFloatOrNull() == target
                }?.let { EpisodeHit(it, exact = false) }
            }
        }
        return null
    }

    /**
     * 取地址通常是一集一个请求, 所以按集并发, 并用 [MAX_PARALLEL_EPISODE_REQUESTS] 限住并发数,
     * 免得一部长番一次打出几百个请求.
     */
    private suspend fun fetchLinesOfAllEpisodes(
        episodes: List<SiteEpisode>,
        variables: Map<String, String>,
    ): List<DirectLink> {
        if (episodes.isEmpty()) return emptyList()
        val lines = config.lines
        val semaphore = Semaphore(MAX_PARALLEL_EPISODE_REQUESTS)
        return coroutineScope {
            episodes.map { siteEpisode ->
                async {
                    semaphore.withPermit {
                        buildDirectLinks(
                            fetchItems(lines.request, variables + ("episodeId" to siteEpisode.value)),
                            lines,
                            episodeRange = siteEpisode.sort?.let { EpisodeRange.single(it) },
                        )
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /** 站点上的一集: [value] 传给取地址的请求, [sort] 是它对应的剧集号 (未知为 null). */
    private class SiteEpisode(val value: String, val sort: EpisodeSort?)

    // ============================ 请求与取值 ============================

    private suspend fun fetchItems(
        request: DirectApiConfig.RequestConfig,
        variables: Map<String, String>,
    ): List<DataNode> = fetchRoot(request, variables)?.selectByPath(request.itemsPath).orEmpty()

    private suspend fun fetchRoot(
        request: DirectApiConfig.RequestConfig,
        variables: Map<String, String>,
    ): DataNode? {
        if (request.url.isBlank()) return null
        val bytes = fetchBytes(buildUrl(request.url, variables)) ?: return null
        return parseResponse(bytes, request.format, json)
    }

    private suspend fun fetchBytes(url: String): ByteArray? = try {
        client.use {
            get(url) {
                // client 自带的 UA 是写死的常量, 每台设备一样; 有本机 UA 就用本机的.
                // 走属性而不是直接写 header: client 的 UA 是 append 上去的, 直接写会变成两个值
                userAgent?.let { ua -> attributes.put(RequestUserAgentAttribute, ua) }
            }.readRawBytes()
        }
    } catch (e: Exception) {
        logger.warn(e) { "Request failed: $url" }
        null
    }

    private fun baseVariables(request: MediaFetchRequest? = null): Map<String, String> = buildMap {
        put("baseUrl", config.baseUrl.trimEnd('/'))
        if (request != null) {
            put("bangumiSubjectId", request.subjectId)
            put("bangumiEpisodeId", request.episodeId)
            put("episodeSort", request.episodeSort.toString())
            put("episodeEp", request.episodeEp?.toString().orEmpty())
            put("subjectName", request.subjectNames.firstOrNull().orEmpty())
        }
    }

    private fun buildUrl(template: String, variables: Map<String, String>): String {
        var result = template
        for ((name, value) in variables) {
            if (!result.contains("{$name}")) continue
            // baseUrl 本身带 "://" 和 "/", 不能编码
            val encoded = if (name == "baseUrl") value else value.encodeURLParameter()
            result = result.replace("{$name}", encoded)
        }
        return result
    }

    private companion object {
        private const val SUBJECT_ID_CACHE_SIZE = 64
        private const val MAX_PARALLEL_EPISODE_REQUESTS = 6
        private val logger = logger<DirectApiEngine>()
    }
}

/**
 * 把取到的条目按 [lines] 组装成 [DirectLink]. 与 HTTP 无关, 便于用录下来的响应做测试.
 */
internal fun buildDirectLinks(
    items: List<DataNode>,
    lines: DirectApiConfig.LinesConfig,
    episodeRange: EpisodeRange? = null,
): List<DirectLink> =
    items.mapNotNull { item -> toDirectLink(item, lines, episodeRange) }
        .distinctBy { it.url }
        .limitPerChannel(lines.maxPerChannel)

private fun toDirectLink(
    item: DataNode,
    lines: DirectApiConfig.LinesConfig,
    episodeRange: EpisodeRange?,
): DirectLink? {
    val rawUrl = item.stringByPath(lines.urlPath) ?: return null
    val url = applyTransforms(rawUrl, lines.urlTransforms) ?: return null
    val channel = lines.channelPath.takeIf { it.isNotBlank() }
        ?.let { item.stringByPath(it) }
        ?.let { applyTransforms(it, lines.channelTransforms) }
        ?.let { lines.channelNames[it] ?: it }
    val subjectName = lines.subjectNamePath.takeIf { it.isNotBlank() }?.let { item.stringByPath(it) }
    val title = lines.titlePath.takeIf { it.isNotBlank() }?.let { item.stringByPath(it) }
    return DirectLink(
        url = url,
        title = listOfNotNull(subjectName, title).joinToString(" ").ifBlank { url },
        channel = channel?.takeIf { it.isNotBlank() },
        episodeRange = episodeRange,
        subjectName = subjectName,
    )
}

/** 一集可能有几十条地址, 同一条线路只留前几个, 免得淹没数据源选择器. */
private fun List<DirectLink>.limitPerChannel(max: Int): List<DirectLink> {
    if (max <= 0) return this
    val counts = HashMap<String, Int>()
    return filter { link ->
        val key = link.channel.orEmpty()
        val count = counts.getOrElse(key) { 0 }
        if (count >= max) {
            false
        } else {
            counts[key] = count + 1
            true
        }
    }
}
