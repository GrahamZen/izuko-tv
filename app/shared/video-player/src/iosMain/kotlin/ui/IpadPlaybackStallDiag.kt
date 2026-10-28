/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)

package me.him188.ani.app.videoplayer.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionReasonKey
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionInterruptionWasSuspendedKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereLostNotification
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionSilenceSecondaryAudioHintNotification
import platform.AVFAudio.currentRoute
import platform.AVFAudio.otherAudioPlaying
import platform.AVFAudio.outputLatency
import platform.AVFAudio.sampleRate
import platform.AVFAudio.secondaryAudioShouldBeSilencedHint
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemTimeJumpedNotification
import platform.AVFoundation.AVPlayerItemTrack
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatus
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.isMuted
import platform.AVFoundation.loadedTimeRanges
import platform.AVFoundation.mediaType
import platform.AVFoundation.playbackBufferEmpty
import platform.AVFoundation.playbackBufferFull
import platform.AVFoundation.playbackLikelyToKeepUp
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.rate
import platform.AVFoundation.reasonForWaitingToPlay
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.timebase
import platform.AVFoundation.tracks
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimebaseGetRate
import platform.CoreMedia.CMTimebaseGetTime
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSValue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

/*
 * iPad 测试包的诊断 (只在 CI 测试分支): 小窗开播后 AVPlayer 报在播、速率 1, 当前时间却不走.
 * 记 item 时钟 (timebase) 的实际速率、已载入区间、缓冲三态、轨道、静音音量、这个播放器挂过的每块图层, 以及音频会话;
 * 另外全局记音频会话打断 / 线路变化 / 服务重置、AVPlayer 卡顿与时间跳变、app 前后台. 日志里搜 `卡住排查` 与 `[iPadDiag]`.
 */
internal object IpadPlaybackStallDiag {
    private val seenLayers = ArrayList<WeakReference<AVPlayerLayer>>()
    private var watched: WeakReference<AVPlayer>? = null
    private var installed = false

    fun track(avPlayer: AVPlayer, layer: AVPlayerLayer) {
        watched = WeakReference(avPlayer)
        seenLayers.removeAll { it.get() == null }
        if (seenLayers.none { it.get() == layer }) seenLayers += WeakReference(layer)
        while (seenLayers.size > 6) seenLayers.removeAt(0)
        if (installed) return
        installed = true
        on(AVAudioSessionInterruptionNotification) { n ->
            "音频会话打断: 类型 ${n.info(AVAudioSessionInterruptionTypeKey)} (1 开始 0 结束), 选项 ${n.info(AVAudioSessionInterruptionOptionKey)}, " +
                "原因 ${n.info(AVAudioSessionInterruptionReasonKey)}, 挂起 ${n.info(AVAudioSessionInterruptionWasSuspendedKey)}"
        }
        on(AVAudioSessionRouteChangeNotification) { n -> "音频线路变了: 原因 ${n.info(AVAudioSessionRouteChangeReasonKey)}, 现在 ${outputs()}" }
        on(AVAudioSessionMediaServicesWereLostNotification) { "音频服务丢了" }
        on(AVAudioSessionMediaServicesWereResetNotification) { "音频服务重置了" }
        on(AVAudioSessionSilenceSecondaryAudioHintNotification) { "次要音频静音提示" }
        on(AVPlayerItemPlaybackStalledNotification) { "AVPlayer 报卡顿 (PlaybackStalled)" }
        on(AVPlayerItemTimeJumpedNotification) { "AVPlayer 时间跳变 (TimeJumped)" }
        on(UIApplicationWillResignActiveNotification) { "app 将不活跃" }
        on(UIApplicationDidBecomeActiveNotification) { "app 活跃了" }
        on(UIApplicationDidEnterBackgroundNotification) { "app 进后台" }
        on(UIApplicationWillEnterForegroundNotification) { "app 回前台" }
    }

    /** item 时钟的实际速率 (AVPlayer.rate 只是想要的速率). */
    fun clock(item: AVPlayerItem?): String {
        val timebase = item?.timebase ?: return "-"
        return fmt(CMTimebaseGetRate(timebase))
    }

    fun dump(avPlayer: AVPlayer, layer: AVPlayerLayer): String = buildString {
        append(playerNow(avPlayer))
        val item = avPlayer.currentItem
        if (item != null) {
            item.timebase?.let { append(", 时钟时间 ").append(fmt(CMTimeGetSeconds(CMTimebaseGetTime(it)))) }
            append(", 已载入 ").append(ranges(item.loadedTimeRanges))
            append(", 缓冲 空/满/够 ").append(item.playbackBufferEmpty).append('/').append(item.playbackBufferFull)
                .append('/').append(item.playbackLikelyToKeepUp)
            append(", 轨道 ").append(
                item.tracks.joinToString(" ") { track ->
                    (track as? AVPlayerItemTrack)?.let {
                        "${it.assetTrack?.mediaType}${if (it.enabled) "" else "(关)"}@${fmt(it.currentVideoFrameRate.toDouble())}"
                    } ?: "?"
                },
            )
            append(", 前向缓冲 ").append(fmt(item.preferredForwardBufferDuration))
        }
        append(", 静音 ").append(avPlayer.isMuted()).append(" 音量 ").append(fmt(avPlayer.volume.toDouble()))
        append(", 等缓冲再播 ").append(avPlayer.automaticallyWaitsToMinimizeStalling)
        append(", 图层 [").append(layers(avPlayer, layer)).append(']')
        append(", 音频会话 ").append(session())
    }

    private fun on(name: String?, describe: (NSNotification?) -> String) {
        NSNotificationCenter.defaultCenter.addObserverForName(name, null, NSOperationQueue.mainQueue) { n ->
            val player = watched?.get()
            diagLogger.info { "[iPadDiag] ${describe(n)}; ${if (player == null) "无播放器" else playerNow(player)}" }
        }
    }

    private fun NSNotification?.info(key: String?): String = this?.userInfo?.get(key)?.toString() ?: "-"

    private fun playerNow(avPlayer: AVPlayer): String {
        val item = avPlayer.currentItem
        return "t=${fmt(CMTimeGetSeconds(avPlayer.currentTime()))}, ${statusText(avPlayer.timeControlStatus, avPlayer.reasonForWaitingToPlay)}, " +
            "速率 ${fmt(avPlayer.rate.toDouble())}, 时钟 ${clock(item)}, item ${item?.hashCode() ?: "无"}"
    }

    private fun layers(avPlayer: AVPlayer, current: AVPlayerLayer): String {
        seenLayers.removeAll { it.get() == null }
        return seenLayers.mapNotNull { it.get() }.joinToString(" | ") { layer ->
            buildString {
                append(layer.hashCode())
                if (layer == current) append('*')
                val owner = layer.player
                append(if (owner == null) " 没播放器" else if (owner == avPlayer) " 接这个播放器" else " 接别的播放器")
                append(if (layer.superlayer == null) " 未挂上" else " 挂着")
                append(if (layer.isReadyForDisplay()) " 出过帧" else " 没出帧")
                append(' ').append(layer.bounds.useContents { "${size.width.toInt()}x${size.height.toInt()}" })
            }
        }
    }

    private fun session(): String {
        val session = AVAudioSession.sharedInstance()
        return "${session.category?.removePrefix("AVAudioSessionCategory")}/${session.mode?.removePrefix("AVAudioSessionMode")}, " +
            "别的声音在放 ${session.otherAudioPlaying}, 次要静音 ${session.secondaryAudioShouldBeSilencedHint}, 输出 ${outputs()}, " +
            "采样率 ${session.sampleRate.toInt()}, 输出延迟 ${fmt(session.outputLatency * 1000)}ms"
    }

    private fun outputs(): String = AVAudioSession.sharedInstance().currentRoute.outputs
        .joinToString(",") { (it as? AVAudioSessionPortDescription)?.portType ?: "?" }

    private fun ranges(list: List<*>): String = list.mapNotNull { value ->
        (value as? NSValue)?.CMTimeRangeValue?.useContents {
            val startSeconds = if (start.timescale != 0) start.value.toDouble() / start.timescale else 0.0
            val durationSeconds = if (duration.timescale != 0) duration.value.toDouble() / duration.timescale else 0.0
            "${fmt(startSeconds)}~${fmt(startSeconds + durationSeconds)}"
        }
    }.joinToString(" ").ifEmpty { "无" }

    private fun statusText(status: AVPlayerTimeControlStatus, waitReason: String?): String = when (status) {
        AVPlayerTimeControlStatusPlaying -> "在播"
        AVPlayerTimeControlStatusPaused -> "暂停"
        else -> "等着 (${waitReason ?: "?"})"
    }

    private fun fmt(value: Double): String =
        if (value.isNaN() || value.isInfinite()) "?" else ((value * 100).toLong() / 100.0).toString()

    private val diagLogger = logger("iPadDiag")
}
