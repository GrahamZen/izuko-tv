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
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.presentationSize
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSNotificationCenter

/**
 * 只有声音、画面解不了的视频: 报成播放失败, 与安卓上没有解码器的画面一样提示并自动换源, 不只放声音.
 *
 * AVPlayer 遇到解不了的视频轨不报错, 照样把声音放下去, 画面一直黑着 —— 比如 HLS 的 TS 分片里装的是 HEVC
 * (苹果只认装在 fMP4 分片里的 HEVC), 一些网页源就是这样. 这时 item 就绪、在播、时钟在走, 视频尺寸 (`presentationSize`) 却一直是 0x0.
 *
 * 判断由 [IosVideoLayerWake] 盯着做 ([Watch]): 播放时间实打实走过 [AUDIO_ONLY_SECONDS] 秒还没有画面, 就给这个 item 发一次
 * AVPlayer 自己的「播放失败」通知, 错误是找不到解码器. mediamp 的 AVKit 播放器收到后把状态置为出错 (不支持的格式),
 * 之后与其他播放失败一样走换源 (SwitchMediaOnPlayerErrorExtension).
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosUndecodableVideo {
    /** 画面不在、声音在放, 播放时间走过这么多秒才算解不了: 正常的视频开播 1 秒内就有尺寸 */
    private const val AUDIO_ONLY_SECONDS = 3.0

    /** 一个 item 的观察: 每次轮询调 [check], 判定解不了时返回 `true` (每个 item 只判一次) */
    class Watch {
        private var item: AVPlayerItem? = null
        private var since: Double? = null
        private var reported = false

        /**
         * @param playing 在播且时钟在走
         * @param shown 图层出过帧
         * @param time 当前播放时间 (秒)
         */
        fun check(current: AVPlayerItem?, playing: Boolean, shown: Boolean, time: Double): Boolean {
            if (current != item) {
                item = current
                since = null
                reported = false
            }
            if (current == null || reported) return false
            val noVideo = current.presentationSize.useContents { width <= 0.0 || height <= 0.0 }
            if (!playing || shown || !noVideo) {
                since = null
                return false
            }
            val start = since ?: time.also { since = it }
            if (time - start < AUDIO_ONLY_SECONDS) return false
            reported = true
            return true
        }
    }

    /** 让 [item] 以「找不到解码器」失败 (见本类说明) */
    fun fail(item: AVPlayerItem) {
        val error = NSError.errorWithDomain(
            AVFoundationErrorDomain,
            AVErrorDecoderNotFound,
            mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "视频画面解不了, 只有声音"),
        )
        NSNotificationCenter.defaultCenter.postNotificationName(
            AVPlayerItemFailedToPlayToEndTimeNotification,
            item,
            mapOf<Any?, Any?>(AVPlayerItemFailedToPlayToEndTimeErrorKey to error),
        )
    }
}
