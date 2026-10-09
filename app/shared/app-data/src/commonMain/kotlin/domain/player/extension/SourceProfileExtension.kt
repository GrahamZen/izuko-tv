/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import io.ktor.http.Url
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.probe.MediaStreamProbe
import me.him188.ani.app.domain.mediasource.profile.SearchObservation
import me.him188.ani.app.domain.mediasource.profile.SourceObservation
import me.him188.ani.app.domain.mediasource.profile.SourceProfiles
import me.him188.ani.app.domain.player.PlaybackFailureLog.Reason
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.videoplayer.diagnostics.PlayerProbes
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.core.Koin
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackState
import kotlin.time.Duration.Companion.seconds

/**
 * 给数据源画像 ([SourceProfiles]) 记每次播放: 选中资源后播起来了 (开播用时、规则源走没走 WebView、HLS 去广告删了几段;
 * 这个源一天内没测过视频信息时, 再过一会儿探测一次分辨率 / 编码 / 码率), 或者没播起来 / 播放中出错 (原因);
 * 以及每次查资源各源的用时与结果.
 *
 * 探测拿的是解析出的视频地址 ([SourceProfiles.resolvedOf]), 只取几十 KB, 等开播缓冲过了才做. 本地缓存不记.
 */
class SourceProfileExtension(
    private val context: PlayerExtensionContext,
    private val client: ScopedHttpClient,
) : PlayerExtension("SourceProfile") {
    private class Tracking(val media: Media, val selectedAt: Long) {
        var loaded = false
        var outcome: Boolean? = null
        var playbackFailed = false
    }

    private sealed interface Event {
        class Selected(val media: Media?) : Event
        class Loading(val state: VideoLoadingState) : Event
        class Playback(val state: PlaybackState) : Event
        data object PlayerError : Event
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStart(
        episodeSession: EpisodeSession,
        backgroundTaskScope: ExtensionBackgroundTaskScope
    ) {
        backgroundTaskScope.launch("SourceProfile") {
            coroutineScope {
                val selected = context.sessionFlow.flatMapLatest { it.fetchSelectFlow }
                    .flatMapLatest { it?.mediaSelector?.selected ?: flowOf(null) }
                    .stateIn(this, SharingStarted.Eagerly, null)
                launch {
                    // 每次查资源各源的用时与结果: 只看查询状态, 不多发请求
                    context.sessionFlow.flatMapLatest { it.fetchSelectFlow }.filterNotNull().collectLatest { bundle ->
                        coroutineScope {
                            for (result in bundle.mediaFetchSession.mediaSourceResults) {
                                if (result.kind != MediaSourceKind.LocalCache) launch { recordSearches(result) }
                            }
                        }
                    }
                }
                val errors = context.player.state.map { (it.mediaStatus as? MediaStatus.Error)?.error }.distinctUntilChanged().filterNotNull()
                var tracking: Tracking? = null
                // 一个收集者按先后处理, 不用为跟踪状态加锁
                merge(
                    selected.map { Event.Selected(it) },
                    context.videoLoadingStateFlow.map { Event.Loading(it) },
                    context.player.playbackState.map { Event.Playback(it) },
                    errors.map { Event.PlayerError },
                ).collect { event ->
                    when (event) {
                        is Event.Selected -> tracking = event.media?.takeIf { it.kind != MediaSourceKind.LocalCache }
                            ?.let { if (tracking?.media?.mediaId == it.mediaId) tracking else Tracking(it, currentTimeMillis()) }

                        is Event.Loading -> {
                            val t = tracking ?: return@collect
                            val state = event.state
                            if (state is VideoLoadingState.Succeed) t.loaded = true
                            if (state is VideoLoadingState.Failed && state != VideoLoadingState.Cancelled && t.outcome == null) {
                                t.outcome = false
                                val reason = PlaybackFailureReportExtension.reasonOf(state)
                                launch { record(t, ok = false, reason = reason) }
                            }
                        }

                        is Event.Playback -> {
                            val t = tracking ?: return@collect
                            if (event.state == PlaybackState.PLAYING && t.loaded && t.outcome == null) {
                                t.outcome = true
                                launch { recordPlaying(t, startupMillis = currentTimeMillis() - t.selectedAt) }
                            }
                        }

                        Event.PlayerError -> {
                            val t = tracking ?: return@collect
                            if (t.outcome == true && !t.playbackFailed) {
                                t.playbackFailed = true
                                launch { record(t, ok = false, reason = Reason.PLAYER_ERROR) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 一个源的查询从开始 ([MediaSourceFetchState.Working]) 到出结果记一条; 暂停、放弃 (我们自己停的) 不记. */
    private suspend fun recordSearches(result: MediaSourceFetchResult) {
        var startedAt: Long? = null
        result.state.collect { state ->
            val outcome = when (state) {
                MediaSourceFetchState.Working -> {
                    startedAt = currentTimeMillis()
                    return@collect
                }

                is MediaSourceFetchState.Succeed -> SearchObservation.Outcome.OK
                is MediaSourceFetchState.Failed -> SearchObservation.Outcome.FAILED
                is MediaSourceFetchState.CaptchaRequired -> SearchObservation.Outcome.CAPTCHA
                is MediaSourceFetchState.RateLimited -> SearchObservation.Outcome.RATE_LIMITED
                else -> return@collect
            }
            val start = startedAt ?: return@collect
            startedAt = null
            val now = currentTimeMillis()
            SourceProfiles.recordSearch(result.mediaSourceId, SearchObservation(at = now, millis = now - start, outcome = outcome))
        }
    }

    private suspend fun record(t: Tracking, ok: Boolean, reason: Reason) {
        SourceProfiles.record(
            t.media.mediaSourceId,
            SourceObservation(
                at = currentTimeMillis(),
                ok = ok,
                webView = SourceProfiles.takeWebView(t.media.mediaSourceId),
                reason = reason.name,
            ),
        )
    }

    private suspend fun recordPlaying(t: Tracking, startupMillis: Long) {
        var observation = SourceObservation(
            at = currentTimeMillis(),
            ok = true,
            startupMillis = startupMillis,
            webView = SourceProfiles.takeWebView(t.media.mediaSourceId),
        )
        val resolved = SourceProfiles.resolvedOf(t.media.mediaId)
        val host = resolved?.let { runCatching { Url(it.uri).host }.getOrNull() }
        if (t.media.kind == MediaSourceKind.WEB && resolved != null && host != null && host != "127.0.0.1" && host != "localhost") {
            // 本地代理处理这次的 HLS 列表时记下的去广告结果
            PlayerProbes.hls.lastOrNull { it.host == host && it.atMillis >= t.selectedAt }?.let {
                observation = observation.copy(adSegments = it.removedSegments)
            }
            if (SourceProfiles.shouldProbe(t.media.mediaSourceId, currentTimeMillis())) {
                // 开播头几秒让给缓冲
                delay(PROBE_DELAY)
                val result = withTimeoutOrNull(PROBE_TIMEOUT) {
                    client.use { MediaStreamProbe.probe(this, resolved.uri, resolved.headers) }
                }
                if (result != null) {
                    observation = observation.copy(width = result.width, height = result.height, codec = result.codec, kbps = result.kbps)
                }
            }
        }
        SourceProfiles.record(t.media.mediaSourceId, observation)
    }

    companion object : EpisodePlayerExtensionFactory<SourceProfileExtension> {
        private val PROBE_DELAY = 8.seconds
        private val PROBE_TIMEOUT = 30.seconds

        override fun create(context: PlayerExtensionContext, koin: Koin): SourceProfileExtension {
            return SourceProfileExtension(context, koin.get<HttpClientProvider>().get(ScopedHttpClientUserAgent.BROWSER))
        }
    }
}
