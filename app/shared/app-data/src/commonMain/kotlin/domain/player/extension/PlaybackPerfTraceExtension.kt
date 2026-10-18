/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.platform.perf.PerfTimeline
import me.him188.ani.datasources.api.source.MediaSourceKind
import org.koin.core.Koin

/**
 * 给性能诊断记下开播的各步 (见 [PerfTimeline]): 进入本集、拿到查询会话、各数据源开始 / 结束查询、选中资源、解析与加载、播放器状态.
 * 和播放器那头的出帧 / 丢帧、线程 CPU 与 GC 排在一条时间线上, 看开播慢在哪一步、开播还在搜索时画面卡不卡.
 * 只收状态变化, 没有接收者时 (手机版) 什么都不收.
 */
class PlaybackPerfTraceExtension(
    private val context: PlayerExtensionContext,
) : PlayerExtension("PlaybackPerfTrace") {
    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("PlaybackPerfTrace") {
            PerfTimeline.active.collectLatest { active ->
                if (active) trace(episodeSession)
            }
        }
    }

    private suspend fun trace(episodeSession: EpisodeSession) = coroutineScope {
        PerfTimeline.mark(START, "本集") { "条目 ${context.subjectId} · 剧集 ${episodeSession.episodeId}" }
        launch {
            context.videoLoadingStateFlow.distinctUntilChanged().collect { state ->
                PerfTimeline.mark(START, "加载") { loadingText(state) }
            }
        }
        launch {
            context.player.state.collect { state ->
                PerfTimeline.mark(START, "播放器") {
                    "${state.mediaStatus} · " + (if (state.playWhenReady) "要播" else "暂停") + (if (state.isBuffering) " · 缓冲中" else "")
                }
            }
        }
        episodeSession.fetchSelectFlow.filterNotNull().collectLatest { bundle ->
            val session = bundle.mediaFetchSession
            val sources = session.mediaSourceResults.filter { it.kind != MediaSourceKind.LocalCache }
            PerfTimeline.mark(START, "查询会话") { "${sources.size} 个数据源" }
            coroutineScope {
                for (source in sources) launch {
                    source.state.collect { state ->
                        if (state != MediaSourceFetchState.Disabled) {
                            PerfTimeline.mark(SOURCE, source.sourceInfo.displayName) { stateText(state) }
                        }
                    }
                }
                launch {
                    bundle.mediaSelector.selected.filterNotNull().distinctUntilChanged().collect { media ->
                        PerfTimeline.mark(START, "选中") { "${media.mediaSourceId} · ${media.kind} · ${media.properties.resolution}" }
                    }
                }
            }
        }
    }

    private fun loadingText(state: VideoLoadingState): String = when (state) {
        VideoLoadingState.Initial -> "初始"
        VideoLoadingState.ResolvingSource -> "解析资源"
        is VideoLoadingState.DecodingData -> "解码数据"
        is VideoLoadingState.Succeed -> "成功"
        is VideoLoadingState.Failed -> "失败 (" + (state::class.simpleName ?: "?") + ")"
    }

    private fun stateText(state: MediaSourceFetchState): String = when (state) {
        MediaSourceFetchState.Idle -> "未开始"
        MediaSourceFetchState.Disabled -> "停用"
        MediaSourceFetchState.Working -> SEARCHING
        is MediaSourceFetchState.Succeed -> "完成"
        is MediaSourceFetchState.Failed -> "失败"
        is MediaSourceFetchState.CaptchaRequired -> "要验证码"
        is MediaSourceFetchState.RateLimited -> "被限流"
        is MediaSourceFetchState.Paused -> "暂停"
        is MediaSourceFetchState.Abandoned -> "中止"
    }

    companion object : EpisodePlayerExtensionFactory<PlaybackPerfTraceExtension> {
        /** 时间线的大类与「正在查询」的状态名 (性能诊断按这些名字读). */
        const val START = "开播"
        const val SOURCE = "数据源"
        const val SEARCHING = "查询中"

        override fun create(context: PlayerExtensionContext, koin: Koin): PlaybackPerfTraceExtension = PlaybackPerfTraceExtension(context)
    }
}
