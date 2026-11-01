/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.videoplayer.ui.captureAndroidSubtitles
import me.him188.ani.app.videoplayer.ui.captureAndroidVideoFrame
import me.him188.ani.app.videoplayer.ui.isTransparent
import me.him188.ani.danmaku.ui.DanmakuLayerSnapshots
import org.openani.mediamp.MediampPlayer
import kotlin.math.roundToInt

/**
 * 手机控制台「截图」在电视这边取到的东西 (见 `RemoteScreenshot`): 三层位图同尺寸、都裁到视频区域, 叠不叠、加什么水印由手机网页决定.
 * 播放器界面不取.
 */
internal class TvPlayerShot(
    /** 视频画面 (高为视频原分辨率, 见 [captureAndroidVideoFrame]). */
    val frame: Bitmap,
    /** 字幕 (透明底); 这一刻没有字幕时为 null. */
    val subtitles: Bitmap?,
    /** 弹幕 (透明底); 弹幕关着或屏上没有时为 null. */
    val danmaku: Bitmap?,
    /** 截图时的播放位置 (毫秒). */
    val positionMillis: Long,
)

/**
 * 播放页登记的截图来源: 播放器 ([RegisterTvPlayerScreenshot]) 与弹幕层 ([rememberTvScreenshotDanmakuLayer]). 只在主线程读写.
 */
internal object TvPlayerScreenshots {
    private var player: MediampPlayer? = null
    private var danmaku: DanmakuSlot? = null

    /**
     * 截此刻屏幕上的一帧. 播放页不在, 或画面不在屏幕上 (电视休眠、切到了别的应用) 时为 null.
     *
     * 只读屏幕上已有的东西 (视频 Surface 上解好的那一帧、字幕视图、弹幕层此刻的内容), 不另起解码器: 后台会话正握着硬解实例,
     * 第二路解码器在只有一路硬解的盒子上会把它挤掉 (issue #10).
     */
    suspend fun capture(): TvPlayerShot? = withContext(Dispatchers.Main.immediate) {
        val player = player ?: return@withContext null
        val frame = player.captureAndroidVideoFrame() ?: return@withContext null
        val width = frame.bitmap.width
        val height = frame.bitmap.height
        TvPlayerShot(
            frame = frame.bitmap,
            subtitles = runCatching { player.captureAndroidSubtitles(frame.rectInWindow, width, height) }.getOrNull(),
            danmaku = danmaku?.let { runCatching { it.capture(frame.rectInWindow, width, height) }.getOrNull() },
            positionMillis = player.currentPositionMillis.value,
        )
    }

    fun register(player: MediampPlayer) {
        this.player = player
    }

    fun unregister(player: MediampPlayer) {
        if (this.player === player) this.player = null
    }

    fun register(slot: DanmakuSlot) {
        danmaku = slot
    }

    fun unregister(slot: DanmakuSlot) {
        if (danmaku === slot) danmaku = null
    }

    /** 弹幕层: 它画在自己的表面上, 截图时按它此刻的内容另画一张 ([DanmakuLayerSnapshots]). */
    class DanmakuSlot(private val snapshots: DanmakuLayerSnapshots) {
        var boundsInWindow: Rect? = null

        /** 取弹幕层, 裁到视频区域 [videoRect] 并缩放成 [width]×[height]; 屏上没有弹幕时为 null. */
        fun capture(videoRect: Rect, width: Int, height: Int): Bitmap? {
            val bounds = boundsInWindow ?: return null
            val image = snapshots.capture()?.asAndroidBitmap() ?: return null
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                scale(width.toFloat() / videoRect.width(), height.toFloat() / videoRect.height())
                translate(-videoRect.left.toFloat(), -videoRect.top.toFloat())
                drawBitmap(image, null, RectF(bounds), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            // 层的大小 (4K 界面上一张 33MB), 用完马上放掉, 不等 GC
            image.recycle()
            if (out.isTransparent()) {
                out.recycle()
                return null
            }
            return out
        }
    }
}

/** 播放页在组合里时登记 [player] 为截图来源. */
@Composable
internal fun RegisterTvPlayerScreenshot(player: MediampPlayer) {
    DisposableEffect(player) {
        TvPlayerScreenshots.register(player)
        onDispose { TvPlayerScreenshots.unregister(player) }
    }
}

/**
 * 挂在弹幕层上, 登记 [snapshots] 与层在窗口里的位置. 弹幕关掉 (层从组合里摘掉) 时随之注销.
 */
@Composable
internal fun rememberTvScreenshotDanmakuLayer(snapshots: DanmakuLayerSnapshots): Modifier {
    val slot = remember(snapshots) { TvPlayerScreenshots.DanmakuSlot(snapshots) }
    DisposableEffect(slot) {
        TvPlayerScreenshots.register(slot)
        onDispose { TvPlayerScreenshots.unregister(slot) }
    }
    return Modifier.onGloballyPositioned { coordinates ->
        val b = coordinates.boundsInWindow()
        slot.boundsInWindow = Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
    }
}
