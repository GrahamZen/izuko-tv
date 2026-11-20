/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.profile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.probe.MediaStreamProbe
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.mp.KoinPlatform
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * 数据源的「深度测试」(Web 控制台的数据源页与触屏版设置页共用): 拿一部各站都有的番 ([SAMPLE]) 搜一次、解析出第一集的视频地址、
 * 探测分辨率与码率, 结果也记进画像 ([SourceProfiles], 标为测试)。
 *
 * 在后台跑 (搜索加解析可能要一两分钟), 进度在 [jobs] 里按数据源实例 id 查, 界面关掉也会测完。
 * 要 WebView 嗅探的源离开播放页解析不了 (WebView 挂在播放页上), 记下「要 WebView」, 码率等真正播放时再记。BT 源只测搜索 (解析就会开始下载)。
 */
object SourceDeepTest {
    enum class Stage { SEARCH, RESOLVE, PROBE }

    enum class Error { MISSING, SEARCH_FAILED, SEARCH_TIMEOUT, SEARCH_EMPTY, NOT_WEB, NEEDS_WEBVIEW, RESOLVE_FAILED, PROBE_FAILED, FAILED }

    /** 一次测试到哪了、测出了什么. [done] 之前 [stage] 是正在做的那一步. */
    data class Progress(
        val done: Boolean = false,
        val stage: Stage = Stage.SEARCH,
        val searchMs: Long? = null,
        val count: Int? = null,
        val title: String? = null,
        val resolveMs: Long? = null,
        val webView: Boolean? = null,
        val probeMs: Long? = null,
        val probe: MediaStreamProbe.Result? = null,
        val error: Error? = null,
        /** 出错的具体说明 (异常消息等). */
        val detail: String? = null,
    )

    private val logger = logger<SourceDeepTest>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _jobs = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** 各数据源实例最近一次测试 (实例 id → 进度); 没测过的不在里面. */
    val jobs: StateFlow<Map<String, Progress>> = _jobs.asStateFlow()

    private val SEARCH_TIMEOUT = 60.seconds
    private val RESOLVE_TIMEOUT = 40.seconds
    private val PROBE_TIMEOUT = 30.seconds

    /** 葬送的芙莉莲 第 1 集: 各站几乎都有. */
    private val SAMPLE = MediaFetchRequest(
        subjectId = "400602",
        episodeId = "1227087",
        subjectNameCN = "葬送的芙莉莲",
        subjectNames = listOf("葬送的芙莉莲", "葬送のフリーレン", "Sousou no Frieren"),
        episodeSort = EpisodeSort(1),
        episodeName = "冒险结束",
    )

    /** 开始测 [instanceId]; 它正在测时什么都不做. */
    fun start(instanceId: String) {
        val current = _jobs.value[instanceId]
        if (current != null && !current.done) return
        set(instanceId, Progress())
        scope.launch {
            val result = try {
                run(instanceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.info { "Deep test of $instanceId failed: $e" }
                Progress(error = Error.FAILED, detail = describe(e))
            }
            set(instanceId, result.copy(done = true))
        }
    }

    private fun set(instanceId: String, progress: Progress) {
        _jobs.update { it + (instanceId to progress) }
    }

    /** 异常的说明: 消息为空时用类名 (仓库层的异常常不带消息). */
    private fun describe(e: Throwable): String? = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName

    private suspend fun run(instanceId: String): Progress {
        val koin = KoinPlatform.getKoin()
        val instance = koin.get<MediaSourceManager>().allInstances.first().find { it.instanceId == instanceId }
            ?: return Progress(error = Error.MISSING)
        val source = instance.source
        var p = Progress()

        // 1. 搜索
        var t = currentTimeMillis()
        val matches = try {
            withTimeoutOrNull(SEARCH_TIMEOUT) { source.fetch(SAMPLE).results.take(50).toList() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 站点拒绝 (如 403) 等: 只报搜索失败, 不记进画像 (源上可能只是没这部番)
            return p.copy(searchMs = currentTimeMillis() - t, error = Error.SEARCH_FAILED, detail = describe(e))
        }
        p = p.copy(searchMs = currentTimeMillis() - t)
        if (matches == null) return p.copy(error = Error.SEARCH_TIMEOUT)
        p = p.copy(count = matches.size)
        val match = matches.firstOrNull { it.kind == MatchKind.EXACT } ?: matches.firstOrNull()
            ?: return p.copy(error = Error.SEARCH_EMPTY)
        p = p.copy(title = match.media.originalTitle)
        if (match.media.kind != MediaSourceKind.WEB) return p.copy(error = Error.NOT_WEB)
        p = p.copy(stage = Stage.RESOLVE)
        set(instanceId, p)

        // 2. 解析出视频地址; 规则源走没走 WebView 由 RuleMediaSource 记下
        t = currentTimeMillis()
        val resolveError = try {
            val provider = withTimeoutOrNull(RESOLVE_TIMEOUT) {
                koin.get<MediaResolver>().resolve(match.media, EpisodeMetadata("", EpisodeSort(1), EpisodeSort(1)))
            }
            if (provider == null) "resolve_timeout" else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // WebView 挂在播放页上, 离开播放页时嗅探不了
            if (e.message?.contains("not attached") == true) NEEDS_WEBVIEW else (describe(e) ?: "resolve_failed")
        }
        // 只认规则源自己报的 (RuleMediaSource 记); 网页抓取源本来就靠 WebView, 不参与降级
        val webView = SourceProfiles.takeWebView(source.mediaSourceId)
        p = p.copy(resolveMs = currentTimeMillis() - t, webView = webView)
        val resolved = SourceProfiles.resolvedOf(match.media.mediaId)
        if (resolveError != null || resolved == null) {
            val needsWebView = resolveError == NEEDS_WEBVIEW
            // 要 WebView 不算失败 (只是这里测不了); 别的解析失败算这个源的一次失败
            SourceProfiles.record(
                source.mediaSourceId,
                SourceObservation(
                    at = currentTimeMillis(), ok = needsWebView, webView = webView,
                    reason = if (needsWebView) null else "RESOLVE_FAILED", test = true,
                ),
            )
            return p.copy(
                error = if (needsWebView) Error.NEEDS_WEBVIEW else Error.RESOLVE_FAILED,
                detail = resolveError?.takeIf { !needsWebView },
            )
        }
        p = p.copy(stage = Stage.PROBE)
        set(instanceId, p)

        // 3. 探测
        t = currentTimeMillis()
        val client = koin.get<HttpClientProvider>().get(ScopedHttpClientUserAgent.BROWSER)
        val probe = withTimeoutOrNull(PROBE_TIMEOUT) { client.use { MediaStreamProbe.probe(this, resolved.uri, resolved.headers) } }
        p = p.copy(probeMs = currentTimeMillis() - t, probe = probe, error = if (probe == null) Error.PROBE_FAILED else null)
        SourceProfiles.record(
            source.mediaSourceId,
            SourceObservation(
                at = currentTimeMillis(),
                ok = probe != null,
                webView = webView,
                width = probe?.width,
                height = probe?.height,
                codec = probe?.codec,
                kbps = probe?.kbps,
                reason = if (probe == null) "PROBE_FAILED" else null,
                test = true,
            ),
        )
        return p
    }

    private const val NEEDS_WEBVIEW = "needs_webview"
}
