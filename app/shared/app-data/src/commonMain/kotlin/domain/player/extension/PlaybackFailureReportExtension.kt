/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.player.PlaybackFailureLog
import me.him188.ani.app.domain.player.PlaybackFailureLog.Reason
import me.him188.ani.app.domain.player.PlaybackFailureLog.Stage
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.player.httpErrorStatus
import me.him188.ani.app.domain.player.isNetworkFailure
import org.koin.core.Koin
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackEvent

/**
 * 把这一集的每次播放失败记进 [PlaybackFailureLog] (Web 控制台的「失败报告」): 解析、打开失败看加载状态,
 * 播起来之后的失败看播放器状态. 只记不处理, 换源由 [SwitchMediaOnPlayerErrorExtension] 管.
 */
class PlaybackFailureReportExtension(
    private val context: PlayerExtensionContext,
) : PlayerExtension("PlaybackFailureReport") {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStart(
        episodeSession: EpisodeSession,
        backgroundTaskScope: ExtensionBackgroundTaskScope
    ) {
        backgroundTaskScope.launch("PlaybackFailureReport") {
            coroutineScope {
                var episodeId = 0
                launch {
                    context.sessionFlow.collect {
                        episodeId = it.episodeId
                        PlaybackFailureLog.start(context.subjectId, it.episodeId)
                    }
                }
                val bundle = context.sessionFlow.flatMapLatest { it.fetchSelectFlow }.stateIn(this, SharingStarted.Eagerly, null)
                val loadingState = MutableStateFlow<VideoLoadingState>(VideoLoadingState.Initial)
                launch {
                    var previous: VideoLoadingState = VideoLoadingState.Initial
                    context.videoLoadingStateFlow.collect { state ->
                        loadingState.value = state
                        if (state is VideoLoadingState.Failed && state != VideoLoadingState.Cancelled) {
                            bundle.value?.mediaSelector?.selected?.value?.let { media ->
                                val noted = PlaybackFailureLog.takeResolveFailure(media.mediaId)
                                val stage = when {
                                    previous is VideoLoadingState.DecodingData -> Stage.OPEN
                                    previous == VideoLoadingState.ResolvingSource || noted != null -> Stage.RESOLVE
                                    else -> Stage.OPEN
                                }
                                val unknownCause = (state as? VideoLoadingState.UnknownError)?.cause
                                PlaybackFailureLog.record(context.subjectId, episodeId, media, stage, reasonOf(state), noted ?: unknownCause)
                            }
                        }
                        previous = state
                    }
                }
                launch {
                    context.player.state.map { (it.mediaStatus as? MediaStatus.Error)?.error }
                        .distinctUntilChanged()
                        .filterNotNull()
                        .collect { error ->
                            // 打开时的失败由加载状态记 (见上)
                            if (loadingState.value !is VideoLoadingState.Succeed) return@collect
                            val media = bundle.value?.mediaSelector?.selected?.value ?: return@collect
                            val reason = errorReason(error) ?: Reason.PLAYER_ERROR
                            PlaybackFailureLog.record(context.subjectId, episodeId, media, Stage.PLAYBACK, reason, error)
                        }
                }
                launch {
                    // 太短的视频播完 (公告、广告片), 见 isTooShortForEpisode
                    context.player.events.filterIsInstance<PlaybackEvent.MediaEnded>().filter { it.isTooShortForEpisode() }.collect { event ->
                        val media = bundle.value?.mediaSelector?.selected?.value ?: return@collect
                        PlaybackFailureLog.record(
                            context.subjectId, episodeId, media, Stage.PLAYBACK, Reason.TOO_SHORT, null,
                            mediaDurationMillis = event.durationMillis,
                        )
                    }
                }
            }
        }
    }

    companion object : EpisodePlayerExtensionFactory<PlaybackFailureReportExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): PlaybackFailureReportExtension {
            return PlaybackFailureReportExtension(context)
        }

        internal fun reasonOf(state: VideoLoadingState.Failed): Reason = when (state) {
            VideoLoadingState.ResolutionTimedOut -> Reason.RESOLUTION_TIMED_OUT
            VideoLoadingState.NetworkError -> Reason.NETWORK
            VideoLoadingState.NoMatchingFile -> Reason.NO_MATCHING_FILE
            VideoLoadingState.UnsupportedMedia -> Reason.UNSUPPORTED
            VideoLoadingState.Cancelled -> Reason.UNKNOWN
            is VideoLoadingState.UnknownError -> errorReason(state.cause) ?: Reason.UNKNOWN
        }

        /** 从异常认得出的原因: 连不上网, 或服务器回了 HTTP 错误; 都不是为 null. */
        private fun errorReason(error: Throwable): Reason? = when {
            error.isNetworkFailure() -> Reason.NETWORK
            error.httpErrorStatus() != null -> Reason.HTTP_ERROR
            else -> null
        }
    }
}
