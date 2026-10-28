/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.MediampPlayer
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatus
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.playbackLikelyToKeepUp
import platform.AVFoundation.presentationSize
import platform.AVFoundation.rate
import platform.AVFoundation.reasonForWaitingToPlay
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.timebase
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimebaseGetRate
import platform.CoreMedia.kCMTimeZero
import kotlin.time.TimeSource

/**
 * 盯着播放器此刻登记的视频图层 (见 [IosVideoLayerRegistry.register]), 播放卡住了就拨一下.
 *
 * 两种卡法. 一是 AVPlayer 报在播、速率不为 0、缓冲也够, item 的时钟 (`timebase`) 却一直不启动: 当前时间不走, 画面停在一帧上或黑着.
 * 偶发在开播的立即播放之后, 不挑源, 跟音频会话与图层无关; 正常开播时钟在 1 秒内启动. 二是在播而图层一直没出过一帧 (`isReadyForDisplay` 为 false).
 *
 * 时钟 [STALL_MILLIS] 不启动, 或者图层 [NO_FRAME_MILLIS] 没出帧, 就暂停、原地精确 seek、再播放 (暂停与播放经 [MediampPlayer], 状态跟着走),
 * 每条视频 (换源、换集换了 item) 最多拨 [MAX_NUDGES] 次. 盯到这块图层被换掉为止. 这几样状态一变就记一行日志 (`[PiP] 图层 …`).
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosVideoLayerWake {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun watch(player: MediampPlayer, avPlayer: AVPlayer, layer: AVPlayerLayer, isCurrent: () -> Boolean) {
        job?.cancel()
        IpadPlaybackStallDiag.track(avPlayer, layer)
        job = scope.launch {
            val tag = "图层 ${layer.hashCode()}"
            var item: AVPlayerItem? = null
            var nudges = 0
            var noFrameMillis = 0L
            var stalledMillis = 0L
            var lastState = ""
            var elapsed = 0L
            var startDumped = false
            var dumpAt = -1L
            var dumpLabel = ""
            while (isCurrent()) {
                val current = avPlayer.currentItem
                if (current != item) {
                    item = current
                    nudges = 0
                    noFrameMillis = 0L
                    stalledMillis = 0L
                    startDumped = false
                    dumpAt = -1L
                }
                val ready = layer.isReadyForDisplay()
                val itemReady = current?.status == AVPlayerItemStatusReadyToPlay
                val hasVideo = current != null && current.presentationSize.useContents { width > 0.0 }
                val status = avPlayer.timeControlStatus
                val clockRate = current?.timebase?.let { CMTimebaseGetRate(it) } ?: 0.0
                val keepUp = current?.playbackLikelyToKeepUp ?: false
                val playing = avPlayer.rate > 0f && itemReady && status == AVPlayerTimeControlStatusPlaying

                val state = buildString {
                    append(if (ready) "出过帧" else "没出帧")
                    append(", ").append(statusText(status, avPlayer.reasonForWaitingToPlay))
                    append(", 速率 ").append(avPlayer.rate)
                    append(", 时钟 ").append(clockRate)
                    append(", item ").append(if (current == null) "无" else if (itemReady) "就绪" else "未就绪")
                    append(", 视频 ").append(current?.presentationSize?.useContents { "${width.toInt()}x${height.toInt()}" } ?: "-")
                    append(", 缓冲够 ").append(keepUp)
                    append(", 图层 ").append(layer.bounds.useContents { "${size.width.toInt()}x${size.height.toInt()}" })
                    append(if (layer.superlayer == null) " 未挂上" else "")
                }
                if (state != lastState) {
                    lastState = state
                    val time = CMTimeGetSeconds(avPlayer.currentTime())
                    logger.info { "[PiP] $tag: $state (t=${(time * 10).toLong() / 10.0}s)" }
                }
                if (!startDumped && playing) {
                    startDumped = true
                    logger.info { "[PiP] $tag: 卡住排查 开播时: ${IpadPlaybackStallDiag.dump(avPlayer, layer)}" }
                    dumpAt = elapsed + 1000
                    dumpLabel = "开播 1 秒"
                }
                if (dumpAt in 0..elapsed) {
                    val label = dumpLabel
                    dumpAt = -1L
                    logger.info { "[PiP] $tag: 卡住排查 $label: ${IpadPlaybackStallDiag.dump(avPlayer, layer)}" }
                }

                noFrameMillis = if (playing && hasVideo && !ready) noFrameMillis + POLL_MILLIS else 0L
                stalledMillis = if (playing && keepUp && clockRate == 0.0) stalledMillis + POLL_MILLIS else 0L
                if ((noFrameMillis >= NO_FRAME_MILLIS || stalledMillis >= STALL_MILLIS) && nudges < MAX_NUDGES) {
                    nudges++
                    val why = if (noFrameMillis >= NO_FRAME_MILLIS) "没出帧 ${noFrameMillis}ms" else "缓冲够了时钟 ${stalledMillis}ms 没启动"
                    noFrameMillis = 0L
                    stalledMillis = 0L
                    logger.info { "[PiP] $tag: 卡住排查 拨之前 ($why): ${IpadPlaybackStallDiag.dump(avPlayer, layer)}" }
                    logger.info { "[PiP] $tag: $why, 暂停、原地 seek 再播放 (第 $nudges 次)" }
                    player.pause()
                    val seekStart = TimeSource.Monotonic.markNow()
                    avPlayer.seekToTime(
                        avPlayer.currentTime(),
                        toleranceBefore = kCMTimeZero.readValue(),
                        toleranceAfter = kCMTimeZero.readValue(),
                    ) { finished ->
                        logger.info { "[PiP] $tag: 卡住排查 原地 seek 回调 finished=$finished, ${seekStart.elapsedNow().inWholeMilliseconds}ms" }
                    }
                    player.resume()
                    dumpAt = elapsed + 500
                    dumpLabel = "拨后 0.5 秒"
                }
                elapsed += POLL_MILLIS
                delay(POLL_MILLIS)
            }
        }
    }

    private fun statusText(status: AVPlayerTimeControlStatus, waitReason: String?): String = when (status) {
        AVPlayerTimeControlStatusPlaying -> "在播"
        AVPlayerTimeControlStatusPaused -> "暂停"
        else -> "等着 (${waitReason ?: "?"})"
    }

    private val logger = logger<IosVideoLayerWake>()

    private const val POLL_MILLIS = 250L
    private const val NO_FRAME_MILLIS = 750L
    private const val STALL_MILLIS = 1500L
    private const val MAX_NUDGES = 2
}
