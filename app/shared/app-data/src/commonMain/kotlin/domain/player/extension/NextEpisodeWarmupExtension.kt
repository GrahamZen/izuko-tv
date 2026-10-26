/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.matcher.WebVideo
import me.him188.ani.datasources.api.matcher.WebVideoDirectResolver
import me.him188.ani.datasources.api.matcher.WebVideoMatcherContext
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.ktor.rawCookieHeader
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import kotlin.time.TimeSource

/**
 * 下一集预热: 在线源的这一集播过一半时, 替下一集向站点的 CDN 要一次文件最后 1 个字节.
 *
 * 有的站点的视频在 Cloudflare 后面, 一集第一次有人看时 CDN 还没缓存, 它先从源站把整个文件 (几百 MB) 拉完, 期间除了从头开始的请求都在等;
 * MP4 的 moov 在末尾时播放器开播前必须读到尾巴, 这一集打开就要等十几二十秒 (缓存热了之后一两秒). 提前要一次尾巴, CDN 在后台把下一集拉进缓存,
 * 切过去时已经是热的. 这个文件反正要被拉一次, 只是提前了.
 *
 * 下一集从这一轮的候选里找 (属于别的集的结果也在里面, 只是被排除了): 同一个源、同一条线路、集数加一. 只用不开网页的那一步解析
 * ([WebVideoDirectResolver], 规则源与选择器源), 拿不到直链就不预热 —— 不为预热开 WebView; 网盘类不走这个解析, 自然跳过.
 * 只预热 http(s) 的整文件 (HLS 的分片另算, 不管). 日志 `[Warmup]`, 记下 CDN 回的缓存状态.
 */
class NextEpisodeWarmupExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
) : PlayerExtension("NextEpisodeWarmup") {
    private val mediaSourceManager: MediaSourceManager by koin.inject()
    private val httpClientProvider: HttpClientProvider by koin.inject()

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("NextEpisodeWarmup") {
            context.sessionFlow.flatMapLatest { it.fetchSelectFlow }.collectLatest { bundle ->
                if (bundle == null) return@collectLatest
                bundle.mediaSelector.selected.collectLatest { media ->
                    if (media == null || media.kind != MediaSourceKind.WEB) return@collectLatest
                    val nextSort = nextEpisodeSortOf(media) ?: return@collectLatest
                    awaitHalfway()
                    val next = nextEpisodeMediaOf(media, nextSort, bundle.mediaSelector.filteredCandidates.first())
                    if (next == null) {
                        logger.info { "[Warmup] 这一轮的结果里没有 ${media.mediaSourceId} 同一线路的第 $nextSort 集, 不预热" }
                        return@collectLatest
                    }
                    warm(next)
                }
            }
        }
    }

    /** 播到一半 (时长知道了才算). */
    private suspend fun awaitHalfway() {
        combine(context.player.currentPositionMillis, context.player.mediaProperties) { position, properties ->
            val duration = properties?.durationMillis ?: 0L
            duration > 0 && position >= duration / 2
        }.first { it }
    }

    private suspend fun warm(next: Media) {
        val video = directVideoOf(next)
        if (video == null) {
            logger.info { "[Warmup] ${next.mediaSourceId} 下一集要开网页才拿得到地址, 不预热" }
            return
        }
        val url = video.m3u8Url
        if (!isWholeFileUrl(url)) {
            logger.info { "[Warmup] ${next.mediaSourceId} 下一集不是整文件 (${Url(url).host}), 不预热" }
            return
        }
        val started = TimeSource.Monotonic.markNow()
        try {
            httpClientProvider.get(ScopedHttpClientUserAgent.NONE).use {
                prepareGet(url) {
                    // 只发解析器给的请求头; Cookie 原样发 (客户端的 Cookie 插件会把它重新编码)
                    video.headers.forEach { (name, value) ->
                        if (name.equals(HttpHeaders.Cookie, ignoreCase = true)) rawCookieHeader(value) else header(name, value)
                    }
                    header(HttpHeaders.Range, "bytes=-1")
                    expectSuccess = false
                    // CDN 没缓存时要等它从源站拉完整个文件才回
                    timeout { requestTimeoutMillis = WARMUP_TIMEOUT_MILLIS }
                }.execute { response ->
                    response.readRawBytes()
                    logger.info {
                        "[Warmup] ${next.mediaSourceId} 下一集 ${Url(url).host}: ${response.status.value}, " +
                            "CDN 缓存 ${response.headers["cf-cache-status"] ?: "?"}, ${started.elapsedNow().inWholeMilliseconds}ms"
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info { "[Warmup] ${next.mediaSourceId} 下一集 ${Url(url).host} 没要到 (${started.elapsedNow().inWholeMilliseconds}ms): ${e::class.simpleName}" }
        }
    }

    /** 不开网页能拿到的直链 (见 [WebVideoDirectResolver]); 拿不到返回 null. */
    private suspend fun directVideoOf(media: Media): WebVideo? {
        val matcherContext = WebVideoMatcherContext(media)
        for (matcher in mediaSourceManager.webVideoMatcherLoader.loadMatchers(media.mediaSourceId)) {
            if (matcher !is WebVideoDirectResolver) continue
            matcher.resolveDirectly(media.download.uri, matcherContext)?.let { return it }
        }
        return null
    }

    companion object : EpisodePlayerExtensionFactory<NextEpisodeWarmupExtension> {
        private val logger = logger<NextEpisodeWarmupExtension>()
        private const val WARMUP_TIMEOUT_MILLIS = 120_000L

        override fun create(context: PlayerExtensionContext, koin: Koin): NextEpisodeWarmupExtension =
            NextEpisodeWarmupExtension(context, koin)
    }
}

/** [media] 是单集时的下一集集数; 合集、特别篇等看不出下一集的返回 null. */
internal fun nextEpisodeSortOf(media: Media): EpisodeSort? {
    val sort = media.episodeRange?.knownSorts?.singleOrNull() as? EpisodeSort.Normal ?: return null
    if (sort.number % 1f != 0f) return null
    return EpisodeSort(sort.number.toInt() + 1)
}

/** 这一轮的候选里, 和 [current] 同一个源、同一条线路的第 [nextSort] 集 (排序靠前的那个). */
@OptIn(UnsafeOriginalMediaAccess::class)
internal fun nextEpisodeMediaOf(current: Media, nextSort: EpisodeSort, candidates: List<MaybeExcludedMedia>): Media? =
    candidates.firstOrNull { candidate ->
        val media = candidate.original
        media.mediaSourceId == current.mediaSourceId &&
            media.properties.alliance == current.properties.alliance &&
            media.episodeRange?.knownSorts?.singleOrNull() == nextSort &&
            // 下一集在这一轮里是「集数不对」被排除的; 别的原因被排除的 (放不了、名字不对) 不算
            (candidate.exclusionReason == null || candidate.exclusionReason is MediaExclusionReason.EpisodeMismatch)
    }?.original

/** http(s) 的整文件 (不是 HLS 的播放列表). */
internal fun isWholeFileUrl(url: String): Boolean {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return false
    val path = url.substringBefore('#').substringBefore('?').lowercase()
    return !path.endsWith(".m3u8") && !path.endsWith(".m3u")
}
