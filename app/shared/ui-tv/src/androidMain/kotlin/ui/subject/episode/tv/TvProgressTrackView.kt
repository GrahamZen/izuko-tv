/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import me.him188.ani.app.domain.media.player.ChunkState
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo

/**
 * 播放器进度条本体 (轨道 + 缓存区块 + 已播放段 + 章节点 + 圆点), 原生自绘.
 *
 * 遥控器拖进度条时每挪一步只有圆点的位置变了: 这里只重画自己这一块, 不重新排版 (Material 的 Slider 把圆点摆在排版阶段,
 * 值一变整条滑块重新排一次). 位置、缓存进度由调用方直接推进来 (见 TvProgressTrack), 不经过组合.
 *
 * 几何照搬原来的 Slider 版: 高 [HEIGHT_DP]; 轨道高 6dp、两端各收 2dp、两头全圆; 圆点占位宽 12dp,
 * 中心在 `6dp + (宽 - 12dp) × 比例`, 半径 8dp. 不接焦点也不接触摸 (焦点在整行上, 触屏拖动由行接).
 */
internal class TvProgressTrackView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val trackHeight = 6 * density
    private val trackInset = 2 * density
    private val thumbSlotWidth = 12 * density
    private val thumbRadius = 8 * density
    private val chapterRadius = 2 * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private val trackClip = Path()

    private var trackBackgroundColor = 0
    private var trackProgressColor = 0
    private var cachedColor = 0
    private var downloadingColor = 0
    private var notAvailableColor = 0
    private var chapterColor = 0
    private var thumbColor = 0

    private var playRatio = 0f
    private var thumbRatio = 0f
    private var thumbVisible = false
    private var cache: MediaCacheProgressInfo? = null
    private var chapterRatios = FloatArray(0)

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setColors(
        trackBackground: Int,
        trackProgress: Int,
        cached: Int,
        downloading: Int,
        notAvailable: Int,
        chapter: Int,
        thumb: Int,
    ) {
        if (trackBackgroundColor == trackBackground && trackProgressColor == trackProgress && cachedColor == cached &&
            downloadingColor == downloading && notAvailableColor == notAvailable && chapterColor == chapter && thumbColor == thumb
        ) {
            return
        }
        trackBackgroundColor = trackBackground
        trackProgressColor = trackProgress
        cachedColor = cached
        downloadingColor = downloading
        notAvailableColor = notAvailable
        chapterColor = chapter
        thumbColor = thumb
        invalidate()
    }

    /** [play] = 已播放段的比例, [thumb] = 圆点的比例 (拖动预览时圆点脱离播放位置). */
    fun setPositions(play: Float, thumb: Float) {
        if (play == playRatio && thumb == thumbRatio) return
        playRatio = play
        thumbRatio = thumb
        invalidate()
    }

    fun setThumbVisible(visible: Boolean) {
        if (visible == thumbVisible) return
        thumbVisible = visible
        invalidate()
    }

    fun setCacheProgress(info: MediaCacheProgressInfo?) {
        if (info == cache) return
        cache = info
        invalidate()
    }

    /** 章节起止点在轨道上的比例. */
    fun setChapterRatios(ratios: FloatArray) {
        if (ratios.contentEquals(chapterRatios)) return
        chapterRatios = ratios
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (HEIGHT_DP * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = (h - trackHeight) / 2
        trackRect.set(trackInset, top, w - trackInset, top + trackHeight)
        val trackWidth = trackRect.width()
        if (trackWidth > 0f) {
            canvas.save()
            trackClip.reset()
            trackClip.addRoundRect(trackRect, trackHeight / 2, trackHeight / 2, Path.Direction.CW)
            canvas.clipPath(trackClip)
            paint.color = trackBackgroundColor
            canvas.drawRect(trackRect, paint)
            drawCacheProgress(canvas, trackWidth)
            paint.color = trackProgressColor
            canvas.drawRect(trackRect.left, trackRect.top, trackRect.left + trackWidth * playRatio, trackRect.bottom, paint)
            paint.color = chapterColor
            for (ratio in chapterRatios) {
                canvas.drawCircle(trackRect.left + trackWidth * ratio, trackRect.centerY(), chapterRadius, paint)
            }
            canvas.restore()
        }
        if (thumbVisible) {
            paint.color = thumbColor
            canvas.drawCircle(thumbSlotWidth / 2 + (w - thumbSlotWidth) * thumbRatio, h / 2, thumbRadius, paint)
        }
    }

    /** 连续同状态的区块连着画: 一块一块画会因为精度在区块之间留下缝. */
    private fun drawCacheProgress(canvas: Canvas, trackWidth: Float) {
        val info = cache ?: return
        if (info.isEmpty()) return
        var x = trackRect.left
        var runState = info.chunkStates[0]
        var runWeight = 0f
        for (i in 0..info.lastIndex) {
            val state = info.chunkStates[i]
            if (state != runState) {
                x = drawChunkRun(canvas, x, runState, runWeight * trackWidth)
                runState = state
                runWeight = 0f
            }
            runWeight += info.chunkWeights[i]
        }
        drawChunkRun(canvas, x, runState, runWeight * trackWidth)
    }

    private fun drawChunkRun(canvas: Canvas, x: Float, state: ChunkState, width: Float): Float {
        val color = when (state) {
            ChunkState.NONE -> null
            ChunkState.DOWNLOADING -> downloadingColor
            ChunkState.DONE -> cachedColor
            ChunkState.NOT_AVAILABLE -> notAvailableColor
        }
        if (color != null) {
            paint.color = color
            canvas.drawRect(x, trackRect.top, x + width, trackRect.bottom, paint)
        }
        return x + width
    }

    companion object {
        const val HEIGHT_DP = 24
    }
}
