/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.videoplayer.media.LibassExoPlayerMediampPlayer
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

/**
 * 截图 (手机控制台的「截图」) 用的两层: 视频画面 ([captureAndroidVideoFrame]) 与字幕 ([captureAndroidSubtitles]).
 *
 * 视频画面在 SurfaceView 的独立显示层上, 常规的 View / Compose 截图取不到, 只能 [PixelCopy] 从 Surface 读回 —— 读的是已经解好、
 * 正在屏幕上的那一帧, 不另起解码器 (见 ui-tv 的 `captureTvPlayerFrame`). 字幕有两路: SRT 这类画在播放器视图里的 [SubtitleView]
 * (普通 View, 软件画布能画), ASS 画在叠在它里面的 [LiftableAssSubtitleView] (TextureView, 只能 `getBitmap` 读回).
 */
class AndroidPlayerFrame(
    /** 画面, 按显示比例、高度取视频原分辨率 (宽不超过 [MAX_FRAME_WIDTH]). */
    val bitmap: Bitmap,
    /** 视频在窗口里的位置 (像素): 字幕、弹幕这些叠在上面的层按它裁. */
    val rectInWindow: Rect,
)

/** 截图最宽多少像素. Android 8 之前位图像素算在 Java 堆里, 4K 的三层要一百多 MB, 那里只取到 1080p. */
private val MAX_FRAME_WIDTH = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 3840 else 1920

private class SubtitleViews(val srt: WeakReference<SubtitleView>, val ass: WeakReference<LiftableAssSubtitleView>?)

private val subtitleViews = WeakHashMap<MediampPlayer, SubtitleViews>()

/** 播放器视图建好时登记它的字幕视图 (见 VideoPlayer.android.kt). */
internal fun registerAndroidSubtitleViews(player: MediampPlayer, srt: SubtitleView, ass: LiftableAssSubtitleView?) {
    synchronized(subtitleViews) { subtitleViews[player] = SubtitleViews(WeakReference(srt), ass?.let(::WeakReference)) }
}

/**
 * 读回此刻屏幕上的视频画面. 视频不在屏幕上 (休眠、切到别的应用、Surface 还没建好) 或读回失败时为 null.
 * 在主线程上调.
 */
suspend fun MediampPlayer.captureAndroidVideoFrame(): AndroidPlayerFrame? = withContext(Dispatchers.Main.immediate) {
    val surfaceView = findAndroidVideoSurface() ?: return@withContext null
    if (!surfaceView.holder.surface.isValid || surfaceView.width <= 0 || surfaceView.height <= 0) return@withContext null
    val location = IntArray(2).also(surfaceView::getLocationInWindow)
    val rect = Rect(location[0], location[1], location[0] + surfaceView.width, location[1] + surfaceView.height)
    // 高取视频原分辨率, 宽按屏幕上显示的比例 (像素宽高比、画面比例设置都已体现在 SurfaceView 的形状上)
    val videoHeight = videoSize()?.second ?: surfaceView.height
    var height = videoHeight
    var width = (height.toFloat() * surfaceView.width / surfaceView.height).roundToInt()
    if (width > MAX_FRAME_WIDTH) {
        height = (height.toFloat() * MAX_FRAME_WIDTH / width).roundToInt()
        width = MAX_FRAME_WIDTH
    }
    val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val result = runCatching {
        suspendCoroutine { continuation ->
            PixelCopy.request(surfaceView, bitmap, { continuation.resume(it) }, Handler(Looper.getMainLooper()))
        }
    }.getOrElse { PixelCopy.ERROR_UNKNOWN }
    if (result == PixelCopy.SUCCESS) {
        AndroidPlayerFrame(bitmap, rect)
    } else {
        bitmap.recycle()
        null
    }
}

/** 视频的原始宽高; 还不知道时为 null. 读 ExoPlayer 要在主线程. */
private fun MediampPlayer.videoSize(): Pair<Int, Int>? {
    val exo = (this as? LibassExoPlayerMediampPlayer)?.exoMediampPlayer ?: (this as? ExoPlayerMediampPlayer) ?: return null
    val size = exo.impl.videoSize
    return if (size.width > 0 && size.height > 0) size.width to size.height else null
}

/**
 * 此刻屏幕上的字幕, 裁到视频区域 [rectInWindow] 并缩放成 [width]×[height] (与画面对齐), 透明底. 这一刻没有字幕时为 null.
 * 在主线程上调.
 */
fun MediampPlayer.captureAndroidSubtitles(rectInWindow: Rect, width: Int, height: Int): Bitmap? {
    val views = synchronized(subtitleViews) { subtitleViews[this] } ?: return null
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.scale(width.toFloat() / rectInWindow.width(), height.toFloat() / rectInWindow.height())
    canvas.translate(-rectInWindow.left.toFloat(), -rectInWindow.top.toFloat())
    views.srt.get()?.takeIf { it.isShown && it.width > 0 }?.let { view ->
        // 软件画布上 TextureView (ASS 那层) 什么也不画, 下面单独读回
        val at = view.locationInWindow()
        canvas.save()
        canvas.translate(at[0].toFloat(), at[1].toFloat())
        view.draw(canvas)
        canvas.restore()
    }
    views.ass?.get()?.takeIf { it.isShown && it.isAvailable }?.let { view ->
        val bitmap = runCatching { view.bitmap }.getOrNull() ?: return@let
        val at = view.locationInWindow()
        // 按目标矩形画: getBitmap 的位图带的是视图 Resources 的 density (界面缩放改过), 与画布的不同,
        // 只给左上角的 drawBitmap 会按两者之比再缩放一次
        val dst = RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
        canvas.drawBitmap(bitmap, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
    }
    if (out.isTransparent()) {
        out.recycle()
        return null
    }
    return out
}

private fun View.locationInWindow(): IntArray = IntArray(2).also(::getLocationInWindow)

/** 一个像素都没画 (全透明). */
fun Bitmap.isTransparent(): Boolean {
    val row = IntArray(width)
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (pixel in row) if (pixel ushr 24 != 0) return false
    }
    return true
}
