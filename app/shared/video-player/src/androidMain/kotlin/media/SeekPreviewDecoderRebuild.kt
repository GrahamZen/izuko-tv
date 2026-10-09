/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * 晶晨 (Amlogic) 的硬解换输出面 (`MediaCodec.setOutputSurface`) 后出帧仍照建解码器时那个输出面来: 建在全屏的 SurfaceView 上时,
 * 换到小画面的 SurfaceTexture 后每一帧都取不出画面 (`updateTexImage` 报错, 小画面一直是空的); 来回换几次后硬解还会报错 (见 `SeekPreviewDecoderFault`).
 * 所以在这类解码器上, 拖动预览换输出 (换到小画面、换回全屏) 时不换输出面, 释放解码器、对着新的输出面重建 (media3 对名单里的晶晨机型也这么做).
 * 其他时候换输出面 (离开、回到播放页) 照旧.
 *
 * 重建的解码器要从下一个关键帧解起, 所以每次重建后要紧跟一次跳转 (见 [SeekPreview]): 换到小画面后跳到圆点, 换回全屏后跳回原处或跳到圆点.
 *
 * 当前的视频解码器由 [onVideoDecoderInitialized] 记下, 只在主线程读写.
 */
@OptIn(UnstableApi::class)
internal class SeekPreviewDecoderRebuild(
    private val exoPlayer: ExoPlayer,
) : AnalyticsListener {
    private var decoderName: String? = null

    /** 当前的视频解码器换输出面时要重建. */
    val applies: Boolean get() = decoderName?.let(::needsRebuild) == true

    /** 下一次换输出面 (换成另一个有效的输出面) 时重建解码器. 在换输出面之前调. */
    fun rebuildOnNextOutput() {
        val renderer = (0 until exoPlayer.rendererCount).map { exoPlayer.getRenderer(it) }
            .firstOrNull { it is UndecodableProfileGuardingVideoRenderer } ?: return
        exoPlayer.createMessage(renderer).setType(MSG_REBUILD_ON_NEXT_OUTPUT).setPayload(true).send()
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        this.decoderName = decoderName
    }

    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        if (this.decoderName == decoderName) this.decoderName = null
    }

    companion object {
        /** 发给 [UndecodableProfileGuardingVideoRenderer] 的消息: 下一次换输出面时释放解码器、对着新的输出面重建 (只管一次). */
        const val MSG_REBUILD_ON_NEXT_OUTPUT = Renderer.MSG_CUSTOM_BASE + 2

        /** 解码器 [decoderName] 换输出面时要重建: 晶晨的解码器 (`OMX.amlogic.…` / `c2.amlogic.…`), 或晶晨芯片上的任何解码器. */
        fun needsRebuild(decoderName: String): Boolean =
            decoderName.contains("amlogic", ignoreCase = true) || Build.HARDWARE.contains("amlogic", ignoreCase = true)
    }
}
