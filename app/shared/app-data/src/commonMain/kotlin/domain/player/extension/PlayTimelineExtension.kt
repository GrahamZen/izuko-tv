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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.player.diagnostics.PlayTimeline
import me.him188.ani.datasources.api.source.MediaSourceKind
import org.koin.core.Koin

/**
 * 开播时间线 ([PlayTimeline]) 里与播放器、选源器状态有关的几步: 剧集信息到了、查询会话建好、各数据源开查与查完、选中资源、
 * 解析与加载、第一次开始播放 (对应上游 `play_flow` 的 info_loaded / search_started / auto_select_done / video_load_done / playback_started).
 */
class PlayTimelineExtension(
    private val context: PlayerExtensionContext,
) : PlayerExtension("PlayTimeline") {
    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("PlayTimeline") {
            PlayTimeline.mark("session_tasks_started")
            launch {
                episodeSession.infoBundleFlow.filterNotNull().first()
                PlayTimeline.mark("info_loaded")
            }
            launch {
                context.videoLoadingStateFlow.distinctUntilChanged().collect { state ->
                    if (state != VideoLoadingState.Initial) PlayTimeline.mark("video_load", state::class.simpleName.orEmpty())
                }
            }
            launch {
                context.player.state.map { it.isPlaying }.distinctUntilChanged().filter { it }.first()
                PlayTimeline.mark("playback_started")
            }
            episodeSession.fetchSelectFlow.filterNotNull().collectLatest { bundle ->
                val sources = bundle.mediaFetchSession.mediaSourceResults
                    .filter { it.kind != MediaSourceKind.LocalCache && it.state.value != MediaSourceFetchState.Disabled }
                PlayTimeline.mark("search_started", "${sources.size} 个数据源")
                coroutineScope {
                    for (source in sources) launch {
                        source.state.collect { state ->
                            when (state) {
                                MediaSourceFetchState.Working -> PlayTimeline.mark("source_working", source.sourceInfo.displayName)
                                is MediaSourceFetchState.Completed ->
                                    PlayTimeline.mark("source_done", "${source.sourceInfo.displayName} ${state::class.simpleName} ${source.results.first().size} 条")

                                else -> {}
                            }
                        }
                    }
                    launch {
                        bundle.mediaSelector.selected.filterNotNull().distinctUntilChanged().collect { media ->
                            PlayTimeline.mark("selected", "${media.mediaSourceId} ${media.properties.subjectName} ${media.properties.episodeName}")
                        }
                    }
                }
            }
        }
    }

    companion object : EpisodePlayerExtensionFactory<PlayTimelineExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): PlayTimelineExtension = PlayTimelineExtension(context)
    }
}
