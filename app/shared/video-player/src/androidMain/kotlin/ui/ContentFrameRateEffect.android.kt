/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.MediampPlayer

private val logger = logger("ContentFrameRate")

@OptIn(UnstableApi::class)
@Composable
actual fun ContentFrameRateEffect(player: MediampPlayer, enabled: Boolean) {
    if (!enabled) return
    val activity = LocalContext.current.findActivity() ?: return
    val exoPlayer = player.impl as? ExoPlayer ?: return
    DisposableEffect(activity, exoPlayer) {
        val window = activity.window
        val original = window.attributes.preferredDisplayModeId
        val handler = Handler(Looper.getMainLooper())
        var switched = false
        // 设备不接受应用切显示模式 (见过 Shield: 显示驱动回 BadConfig, 系统每一帧重试): 撤回请求, 这次播放不再试
        var refused = false

        fun setPreferredMode(modeId: Int) {
            val params = window.attributes
            params.preferredDisplayModeId = modeId
            window.attributes = params
        }

        fun matchFrameRate(fps: Float) {
            if (refused) return
            val display = window.decorView.display ?: return
            val current = display.mode.toOption()
            val target = pickDisplayModeForFrameRate(display.supportedModes.map { it.toOption() }, current, fps) ?: return
            if (window.attributes.preferredDisplayModeId == target) return
            logger.info { "[FrameRate] $fps fps content: display mode ${current.width}x${current.height} ${current.refreshRate} Hz -> mode $target" }
            setPreferredMode(target)
            switched = true
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(
                {
                    val now = window.decorView.display?.mode?.modeId
                    if (now != target && window.attributes.preferredDisplayModeId == target) {
                        logger.warn { "[FrameRate] Display stayed on mode $now instead of $target, the device does not let apps switch modes; withdrawing the request" }
                        refused = true
                        setPreferredMode(original)
                        switched = false
                    }
                },
                SWITCH_TIMEOUT_MILLIS,
            )
        }

        // 视频格式在解码器拿到时回调 (播放器的应用线程, 即主线程); 帧率不知道 (有的 mkv 没写) 时不换
        val listener = object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                if (format.frameRate > 0f) matchFrameRate(format.frameRate)
            }
        }
        exoPlayer.addAnalyticsListener(listener)
        exoPlayer.videoFormat?.frameRate?.takeIf { it > 0f }?.let(::matchFrameRate)
        onDispose {
            handler.removeCallbacksAndMessages(null)
            exoPlayer.removeAnalyticsListener(listener)
            if (switched) setPreferredMode(original)
        }
    }
}

/** 电视换显示模式要重新同步信号, 一般一两秒; 等这么久还没换成就当设备不让切. */
private const val SWITCH_TIMEOUT_MILLIS = 5_000L

private fun Display.Mode.toOption() = DisplayModeOption(modeId, physicalWidth, physicalHeight, refreshRate)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
