/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.video.loading

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.resolver.MediaResolveDeadline
import me.him188.ani.app.domain.media.resolver.TorrentOpenProgress
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.player.downloadSpeedFlow
import me.him188.ani.app.domain.player.extension.MediaAutoSwitchStatus
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.foundation.TextWithBorder
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_episode_video_loading_auto_selecting
import me.him188.ani.app.ui.lang.subject_episode_video_loading_auto_switch
import me.him188.ani.app.ui.lang.subject_episode_video_loading_bt_service_starting
import me.him188.ani.app.ui.lang.subject_episode_video_loading_buffered_ahead
import me.him188.ani.app.ui.lang.subject_episode_video_loading_buffering
import me.him188.ani.app.ui.lang.subject_episode_video_loading_buffering_bt_no_speed_try_switch
import me.him188.ani.app.ui.lang.subject_episode_video_loading_buffering_bt_too_long
import me.him188.ani.app.ui.lang.subject_episode_video_loading_buffering_too_long
import me.him188.ani.app.ui.lang.subject_episode_video_loading_cause_cancelled
import me.him188.ani.app.ui.lang.subject_episode_video_loading_cause_network_error
import me.him188.ani.app.ui.lang.subject_episode_video_loading_cause_no_matching_file
import me.him188.ani.app.ui.lang.subject_episode_video_loading_cause_player_failed
import me.him188.ani.app.ui.lang.subject_episode_video_loading_cause_resolution_timed_out
import me.him188.ani.app.ui.lang.subject_episode_video_loading_cause_unknown_error
import me.him188.ani.app.ui.lang.subject_episode_video_loading_cause_unsupported_media
import me.him188.ani.app.ui.lang.subject_episode_video_loading_decoding_bt
import me.him188.ani.app.ui.lang.subject_episode_video_loading_decoding_cloud
import me.him188.ani.app.ui.lang.subject_episode_video_loading_decoding_data
import me.him188.ani.app.ui.lang.subject_episode_video_loading_failed_prefix
import me.him188.ani.app.ui.lang.subject_episode_video_loading_needs_manual_selection
import me.him188.ani.app.ui.lang.subject_episode_video_loading_no_media
import me.him188.ani.app.ui.lang.subject_episode_video_loading_player_error
import me.him188.ani.app.ui.lang.subject_episode_video_loading_resolve_deadline
import me.him188.ani.app.ui.lang.subject_episode_video_loading_resolve_deadline_auto_switch
import me.him188.ani.app.ui.lang.subject_episode_video_loading_resolving_source
import me.him188.ani.app.ui.lang.subject_episode_video_loading_resolving_source_short
import me.him188.ani.app.ui.lang.subject_episode_video_loading_searching_sources
import me.him188.ani.app.ui.lang.subject_episode_video_loading_torrent_metadata
import me.him188.ani.app.ui.lang.subject_episode_video_loading_torrent_no_peers
import me.him188.ani.app.ui.lang.subject_episode_video_loading_torrent_peers
import me.him188.ani.app.ui.lang.subject_episode_video_loading_waiting_for_sources
import me.him188.ani.app.ui.lang.subject_episode_video_loading_waiting_for_sources_more
import me.him188.ani.app.ui.subject.episode.SourceSearchProgress
import me.him188.ani.app.ui.subject.episode.SourceSearchStuck
import me.him188.ani.app.videoplayer.ui.VideoLoadingIndicator
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.Unspecified
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.stringResource
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.features.Buffering
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 加载提示里"进行到哪了"的细节, 都可以没有 (没有就照旧只写阶段): 选源时查了几个源、解析这一轮最多再等几秒、
 * 自动换源试到第几个.
 */
@Immutable
data class EpisodeLoadingDetails(
    val sourceSearch: SourceSearchProgress? = null,
    val resolveDeadline: MediaResolveDeadline? = null,
    /** 超时 / 失败后会不会自动换源: 决定倒数时要不要说「超时自动换源」. */
    val autoSwitchesOnFailure: Boolean = false,
    val autoSwitch: MediaAutoSwitchStatus? = null,
    /** 在加载的是 BT 资源. */
    val loadingBt: Boolean = false,
    /** BT 服务已经起来了. 没起来时解析 BT 资源其实是在等它启动. */
    val btServiceConnected: Boolean = true,
    /** BT 资源在等种子信息时连上了几个节点. */
    val torrentOpen: TorrentOpenProgress? = null,
    /** 缓冲时已经缓冲到当前位置之后多少毫秒, 不知道为 `null`. */
    val bufferedAheadMillis: Long? = null,
) {
    companion object {
        val None = EpisodeLoadingDetails()
    }
}

@Composable // see preview
fun EpisodeVideoLoadingIndicator(
    playerState: MediampPlayer,
    videoLoadingState: VideoLoadingState,
    optimizeForFullscreen: Boolean,
    modifier: Modifier = Modifier,
    details: EpisodeLoadingDetails = EpisodeLoadingDetails.None,
) {
    val state by playerState.state.collectAsStateWithLifecycle()

    val speed by remember(playerState) {
        playerState.downloadSpeedFlow()
    }.collectAsStateWithLifecycle(FileSize.Unspecified)

    if (shouldShowVideoLoadingIndicator(videoLoadingState, state.isBuffering, state.mediaStatus is MediaStatus.Error)) {
        // 已缓冲了多少只在缓冲 (地址交给播放器之后) 时显示, 也只在这时订阅
        val bufferedAheadMillis = if (videoLoadingState is VideoLoadingState.Succeed) {
            val bufferedAheadFlow = remember(playerState) { playerState.bufferedAheadMillisFlow() }
            bufferedAheadFlow.collectAsStateWithLifecycle(null).value
        } else {
            null
        }
        EpisodeVideoLoadingIndicator(
            videoLoadingState,
            speedProvider = { speed },
            optimizeForFullscreen = optimizeForFullscreen,
            playerError = state.mediaStatus is MediaStatus.Error,
            modifier = modifier,
            details = details.copy(bufferedAheadMillis = bufferedAheadMillis),
        )
    }
}

fun shouldShowVideoLoadingIndicator(state: VideoLoadingState, buffering: Boolean, playerError: Boolean): Boolean =
    buffering || playerError || state !is VideoLoadingState.Succeed

/**
 * 已经缓冲到当前位置之后多少毫秒 (播放器报的已缓冲位置 - 当前位置), 按 [BUFFERED_AHEAD_STEP_MILLIS] 取整后去重:
 * 播放器上报得很勤, 不取整的话缓冲提示每报一次都要重组. 播放器不支持 [Buffering] 或报不出 (负数) 时为 `null`.
 */
@OptIn(ExperimentalMediampApi::class)
private fun MediampPlayer.bufferedAheadMillisFlow(): Flow<Long?> {
    val buffering = features[Buffering] ?: return flowOf(null)
    return combine(buffering.bufferedPositionMillis, currentPositionMillis) { buffered, current ->
        if (buffered < 0) {
            null
        } else {
            (buffered - current).coerceAtLeast(0L) / BUFFERED_AHEAD_STEP_MILLIS * BUFFERED_AHEAD_STEP_MILLIS
        }
    }.distinctUntilChanged()
}

@Composable
fun EpisodeVideoLoadingIndicator(
    state: VideoLoadingState,
    speedProvider: () -> FileSize,
    optimizeForFullscreen: Boolean,
    playerError: Boolean = false,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    details: EpisodeLoadingDetails = EpisodeLoadingDetails.None,
) {
    val playerErrorText = stringResource(Lang.subject_episode_video_loading_player_error)
    val autoSelectingText = stringResource(Lang.subject_episode_video_loading_auto_selecting)
    val resolvingSourceText = stringResource(Lang.subject_episode_video_loading_resolving_source)
    val decodingDataText = stringResource(Lang.subject_episode_video_loading_decoding_data)
    val decodingBtText = stringResource(Lang.subject_episode_video_loading_decoding_bt)
    val decodingCloudText = stringResource(Lang.subject_episode_video_loading_decoding_cloud)
    val bufferingText = stringResource(Lang.subject_episode_video_loading_buffering)
    val bufferingBtTooLongText = stringResource(Lang.subject_episode_video_loading_buffering_bt_too_long)
    val bufferingNoSpeedTrySwitchText =
        stringResource(Lang.subject_episode_video_loading_buffering_bt_no_speed_try_switch)
    val bufferingTooLongText = stringResource(Lang.subject_episode_video_loading_buffering_too_long)
    val failedPrefix = stringResource(Lang.subject_episode_video_loading_failed_prefix)
    val causeLabels = videoLoadingCauseLabels()
    val sourceSearch = details.sourceSearch.takeIf { state == VideoLoadingState.Initial }
    // 选源时画定量的环 (查完几个源); 查完却没选中时不画 —— 那时已经不是在等了
    val searching = sourceSearch?.takeIf { it.stuck == null && it.total > 0 }
    VideoLoadingIndicator(
        showProgress = state is VideoLoadingState.Progressing || searching != null,
        progress = searching?.let { search -> { search.finished.toFloat() / search.total } },
        text = {
            if (playerError) {
                TextWithBorder(playerErrorText, color = MaterialTheme.colorScheme.error)
                return@VideoLoadingIndicator
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (state) {
                    VideoLoadingState.Initial -> {
                        if (sourceSearch != null) {
                            SourceSearchText(sourceSearch)
                        } else {
                            TextWithBorder(autoSelectingText)
                        }
                    }

                    VideoLoadingState.ResolvingSource -> {
                        val deadline = details.resolveDeadline
                        when {
                            // 解析 BT 资源先要等 BT 服务起来 (第一次十几秒), 这时说「正在解析资源链接」是假话
                            details.loadingBt && !details.btServiceConnected -> TextWithBorder(
                                stringResource(Lang.subject_episode_video_loading_bt_service_starting),
                                textAlign = TextAlign.Center,
                            )

                            deadline != null -> ResolveDeadlineText(deadline, details.autoSwitchesOnFailure)

                            else -> TextWithBorder(
                                resolvingSourceText,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    is VideoLoadingState.DecodingData -> {
                        val engineKey = state.engineKey
                        val torrentOpen = details.torrentOpen
                        // 云盘按需取流, 没有等种子信息、找节点这一步
                        if (engineKey != null && !engineKey.isCloud && torrentOpen != null) {
                            TorrentMetadataText(torrentOpen)
                        } else {
                            TextWithBorder(
                                when {
                                    engineKey == null -> decodingDataText
                                    engineKey.isCloud -> decodingCloudText
                                    else -> decodingBtText
                                },
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    is VideoLoadingState.Succeed -> {
                        var tooLong by rememberSaveable {
                            mutableStateOf(false)
                        }
                        val speed by remember { derivedStateOf(speedProvider) }
                        val speedIsZero by remember { derivedStateOf { speed == FileSize.Zero } }
                        if (speedIsZero) {
                            LaunchedEffect(true) {
                                delay(15.seconds)
                                tooLong = true
                            }
                        }
                        val bufferedAheadText = details.bufferedAheadMillis?.let {
                            stringResource(Lang.subject_episode_video_loading_buffered_ahead, formatTenthsOfSecond(it))
                        }
                        val bufferedAhead by rememberUpdatedState(bufferedAheadText)
                        val text by remember {
                            derivedStateOf {
                                buildString {
                                    append(bufferingText)
                                    // 第二行: 已缓冲多少 · 下载速度 (各自可能不知道)
                                    val speedText = if (speed != FileSize.Unspecified) "$speed/s" else null
                                    val progressLine = listOfNotNull(bufferedAhead, speedText).joinToString(" · ")
                                    if (progressLine.isNotEmpty()) {
                                        appendLine()
                                        append(progressLine)
                                    }

                                    if (tooLong) {
                                        appendLine()
                                        // 云盘和 WEB 一样是按需取流, 没有找节点这一步要解释, 用通用文案.
                                        val engineKey = state.engineKey
                                        if (engineKey != null && !engineKey.isCloud) {
                                            append(bufferingBtTooLongText)
                                            appendLine()
                                            append(bufferingNoSpeedTrySwitchText)
                                        } else {
                                            append(bufferingTooLongText)
                                        }
                                    }
                                }
                            }
                        }

                        TextWithBorder(text, textAlign = TextAlign.Center)
                    }

                    is VideoLoadingState.Failed -> {
                        TextWithBorder(
                            "$failedPrefix${renderCause(state, causeLabels)}",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                // 自动换源正在试下一个: 加一行试到第几个. 失败态本身已经在说原因, 不再叠一行
                val autoSwitch = details.autoSwitch
                if (autoSwitch != null && state !is VideoLoadingState.Failed) {
                    AutoSwitchText(autoSwitch, causeLabels)
                }
            }
        },
        modifier = modifier,
        textStyle = textStyle,
    )
}

/**
 * 选源时那行字: 查了几个源、找到几条; 查得慢时点名还在等的源; 全部查完却没选中时说清是没结果还是要手选.
 */
@Composable
private fun SourceSearchText(search: SourceSearchProgress) {
    // 查得慢才点名: 一般两三秒就全回来了, 一开始就列名字只是晃眼 (被墙的源要等满 60 秒连接超时)
    var showPending by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SOURCE_SEARCH_NAME_PENDING_AFTER)
        showPending = true
    }
    // 查完没选中要稳一会儿才说: 全部查完到自动选源拿主意之间有一瞬, 那时说「请手动选择」是误报
    var stuck by remember { mutableStateOf<SourceSearchStuck?>(null) }
    LaunchedEffect(search.stuck) {
        stuck = null
        val next = search.stuck ?: return@LaunchedEffect
        delay(SOURCE_SEARCH_STUCK_SETTLE)
        stuck = next
    }
    val text = when (stuck) {
        SourceSearchStuck.NoMedia -> stringResource(Lang.subject_episode_video_loading_no_media, search.total)
        SourceSearchStuck.NeedsManualSelection ->
            stringResource(Lang.subject_episode_video_loading_needs_manual_selection, search.found)

        null -> {
            val progressLine = stringResource(
                Lang.subject_episode_video_loading_searching_sources,
                search.finished,
                search.total,
                search.found,
            )
            val names = search.pendingNames.joinToString(" / ")
            val more = search.pendingCount - search.pendingNames.size
            val pendingLine = when {
                !showPending || search.pendingNames.isEmpty() -> null
                more > 0 -> stringResource(Lang.subject_episode_video_loading_waiting_for_sources_more, names, more)
                else -> stringResource(Lang.subject_episode_video_loading_waiting_for_sources, names)
            }
            if (pendingLine == null) progressLine else "$progressLine\n$pendingLine"
        }
    }
    TextWithBorder(
        text,
        color = if (stuck == SourceSearchStuck.NoMedia) MaterialTheme.colorScheme.error else Color.Unspecified,
        textAlign = TextAlign.Center,
    )
}

/** 解析时那两行: 正在解析 + 这一轮最多再等几秒 (会自动换源的话一并说). */
@Composable
private fun ResolveDeadlineText(deadline: MediaResolveDeadline, autoSwitchesOnFailure: Boolean) {
    var remainingSeconds by remember(deadline) { mutableIntStateOf(deadline.remainingWholeSeconds()) }
    LaunchedEffect(deadline) {
        while (true) {
            remainingSeconds = deadline.remainingWholeSeconds()
            delay(RESOLVE_COUNTDOWN_TICK)
        }
    }
    val title = stringResource(Lang.subject_episode_video_loading_resolving_source_short)
    val countdown = if (autoSwitchesOnFailure) {
        stringResource(Lang.subject_episode_video_loading_resolve_deadline_auto_switch, remainingSeconds)
    } else {
        stringResource(Lang.subject_episode_video_loading_resolve_deadline, remainingSeconds)
    }
    TextWithBorder("$title\n$countdown", textAlign = TextAlign.Center)
}

/** 向上取整到秒, 至少 1: 到点那一刻状态就会变成失败, 不写「再等 0 秒」. */
private fun MediaResolveDeadline.remainingWholeSeconds(): Int =
    ((remainingMillis() + 999) / 1000).toInt().coerceAtLeast(1)

/** 自动换源时加的那行: 上一个源为什么失败、正在试第几个、还剩几个. */
@Composable
private fun AutoSwitchText(status: MediaAutoSwitchStatus, causeLabels: VideoLoadingCauseLabels) {
    val reason = status.previousFailure?.let { renderCause(it, causeLabels) }
        ?: stringResource(Lang.subject_episode_video_loading_cause_player_failed)
    TextWithBorder(
        stringResource(Lang.subject_episode_video_loading_auto_switch, reason, status.attempt, status.remaining),
        modifier = Modifier.padding(top = 4.dp),
        textAlign = TextAlign.Center,
    )
}

/**
 * BT 资源等种子信息时那几行: 正在获取种子信息 + 连上几个节点、等了多久; 一直没有节点时建议换源 ——
 * 等元数据没有超时, 没有节点的话可能永远等不来.
 */
@Composable
private fun TorrentMetadataText(progress: TorrentOpenProgress) {
    // 按开始时刻计时: 节点数每秒报一次会换一个新对象, 按整个对象当键的话计时会被反复重置
    var elapsedSeconds by remember(progress.startedAt) { mutableIntStateOf(0) }
    LaunchedEffect(progress.startedAt) {
        while (true) {
            elapsedSeconds = progress.startedAt.elapsedNow().inWholeSeconds.toInt()
            delay(1.seconds)
        }
    }
    val lines = listOfNotNull(
        stringResource(Lang.subject_episode_video_loading_torrent_metadata),
        stringResource(Lang.subject_episode_video_loading_torrent_peers, progress.peers, elapsedSeconds),
        if (progress.peers == 0 && elapsedSeconds >= TORRENT_NO_PEERS_HINT_AFTER.inWholeSeconds) {
            stringResource(Lang.subject_episode_video_loading_torrent_no_peers)
        } else {
            null
        },
    )
    TextWithBorder(lines.joinToString("\n"), textAlign = TextAlign.Center)
}

/** 毫秒写成带一位小数的秒, 如 1500 → "1.5" (公共代码里没有 String.format). */
private fun formatTenthsOfSecond(millis: Long): String {
    val tenths = millis / 100
    return "${tenths / 10}.${tenths % 10}"
}

/** 已缓冲时长的取整粒度. */
private const val BUFFERED_AHEAD_STEP_MILLIS = 500L

/** 等种子信息满这么久还一个节点都没有, 才建议换源. */
private val TORRENT_NO_PEERS_HINT_AFTER = 30.seconds

/** 查了这么久还没查完, 才点名还在等的源. */
private val SOURCE_SEARCH_NAME_PENDING_AFTER = 8.seconds

/** 全部查完却没选中, 要稳住这么久才说「没结果 / 请手选」. */
private val SOURCE_SEARCH_STUCK_SETTLE = 2.seconds

private val RESOLVE_COUNTDOWN_TICK = 250.milliseconds

/**
 * 每种失败原因的一句话说明.
 *
 * 抽出来是因为不止画面上要说: 后台会话的提示 (`RetainedPlaybackNoticeTexts`) 说的是同一批原因,
 * 两处各写一遍必然写岔.
 */
internal data class VideoLoadingCauseLabels(
    val resolutionTimedOut: String,
    val unknownError: String,
    val unsupportedMedia: String,
    val noMatchingFile: String,
    val cancelled: String,
    val networkError: String,
) {
    /** 把每句原因再包一层 (例如套进"后台播放遇到问题: ……"的模板). */
    inline fun map(transform: (String) -> String) = VideoLoadingCauseLabels(
        resolutionTimedOut = transform(resolutionTimedOut),
        unknownError = transform(unknownError),
        unsupportedMedia = transform(unsupportedMedia),
        noMatchingFile = transform(noMatchingFile),
        cancelled = transform(cancelled),
        networkError = transform(networkError),
    )
}

@Composable
internal fun videoLoadingCauseLabels(): VideoLoadingCauseLabels = VideoLoadingCauseLabels(
    resolutionTimedOut = stringResource(Lang.subject_episode_video_loading_cause_resolution_timed_out),
    unknownError = stringResource(Lang.subject_episode_video_loading_cause_unknown_error),
    unsupportedMedia = stringResource(Lang.subject_episode_video_loading_cause_unsupported_media),
    noMatchingFile = stringResource(Lang.subject_episode_video_loading_cause_no_matching_file),
    cancelled = stringResource(Lang.subject_episode_video_loading_cause_cancelled),
    networkError = stringResource(Lang.subject_episode_video_loading_cause_network_error),
)

internal fun renderCause(cause: VideoLoadingState.Failed, labels: VideoLoadingCauseLabels): String = when (cause) {
    is VideoLoadingState.ResolutionTimedOut -> labels.resolutionTimedOut
    is VideoLoadingState.UnknownError -> labels.unknownError
    is VideoLoadingState.UnsupportedMedia -> labels.unsupportedMedia
    VideoLoadingState.NoMatchingFile -> labels.noMatchingFile
    VideoLoadingState.Cancelled -> labels.cancelled
    VideoLoadingState.NetworkError -> labels.networkError
}

@Preview(name = "Selecting Media")
@Composable
private fun PreviewEpisodeVideoLoadingIndicator() {
    ProvideCompositionLocalsForPreview {
        EpisodeVideoLoadingIndicator(
            VideoLoadingState.Initial,
            speedProvider = { 0.3.bytes },
            optimizeForFullscreen = false,
        )
    }
}

@Preview(name = "Selecting Media")
@Composable
private fun PreviewEpisodeVideoLoadingIndicatorFullscreen() {
    ProvideCompositionLocalsForPreview {
        EpisodeVideoLoadingIndicator(
            VideoLoadingState.Initial,
            speedProvider = { 0.3.bytes },
            optimizeForFullscreen = true,
        )
    }
}

@Preview(name = "ResolvingSource")
@Composable
private fun PreviewEpisodeVideoLoadingIndicator2() {
    ProvideCompositionLocalsForPreview {
        EpisodeVideoLoadingIndicator(
            VideoLoadingState.ResolvingSource,
            speedProvider = { 0.3.bytes },
            optimizeForFullscreen = false,
        )
    }
}

@Preview(name = "ResolvingSource")
@Composable
private fun PreviewEpisodeVideoLoadingIndicator5() {
    ProvideCompositionLocalsForPreview {
        EpisodeVideoLoadingIndicator(
            VideoLoadingState.DecodingData(MediaCacheEngineKey.Anitorrent),
            speedProvider = { 0.3.bytes },
            optimizeForFullscreen = false,
        )
    }
}

private fun successState() = VideoLoadingState.Succeed(MediaCacheEngineKey.Anitorrent)

@Preview(name = "Buffering")
@Composable
private fun PreviewEpisodeVideoLoadingIndicator3() {
    ProvideCompositionLocalsForPreview {
        EpisodeVideoLoadingIndicator(
            successState(),
            speedProvider = { 0.3.bytes },
            optimizeForFullscreen = false,
        )
    }
}

@Preview(name = "Failed")
@Composable
private fun PreviewEpisodeVideoLoadingIndicator7() {
    ProvideCompositionLocalsForPreview {
        EpisodeVideoLoadingIndicator(
            VideoLoadingState.ResolutionTimedOut,
            speedProvider = { Unspecified },
            optimizeForFullscreen = false,
        )
    }
}

@Preview(name = "Buffering - No Speed")
@Composable
private fun PreviewEpisodeVideoLoadingIndicator4() {
    ProvideCompositionLocalsForPreview {
        EpisodeVideoLoadingIndicator(
            successState(),
            speedProvider = { Unspecified },
            optimizeForFullscreen = false,
        )
    }
}
