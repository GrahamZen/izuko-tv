/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max

/** 一条进度: 圆头的轨道与进度; 不定式的是一截在轨道上来回走. */
internal class TvNativeProgressView(context: Context) : View(context) {
    private var progress: Float? = null
    private var indeterminate = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var color = 0
    private var track = 0
    private var phase = 0f
    private var animator: ValueAnimator? = null

    /** 进度 [progress] (0..1; null = 没有进度, 给了 [indeterminate] 画不定式的), 进度的颜色 [color] 与轨道的颜色 [track]. */
    fun set(progress: Float?, indeterminate: Boolean, color: Int, track: Int) {
        this.progress = progress
        this.indeterminate = indeterminate && progress == null
        this.color = color
        this.track = track
        updateAnimator()
        invalidate()
    }

    private fun updateAnimator() {
        val run = indeterminate && isAttachedToWindow && visibility == VISIBLE
        if (run && animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = INDETERMINATE_MILLIS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!run && animator != null) {
            animator?.cancel()
            animator = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimator()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2
        paint.color = track
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = color
        val p = progress
        when {
            p != null -> if (p > 0f) {
                rect.set(0f, 0f, max(h, w * p), h)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }

            indeterminate -> {
                val segment = w * INDETERMINATE_SEGMENT
                val start = (w + segment) * phase - segment
                rect.set(start.coerceAtLeast(0f), 0f, (start + segment).coerceAtMost(w), h)
                if (rect.width() > 0f) canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    private companion object {
        const val INDETERMINATE_MILLIS = 1200L
        const val INDETERMINATE_SEGMENT = 0.35f
    }
}
