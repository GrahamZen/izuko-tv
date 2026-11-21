/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import me.him188.ani.app.ui.foundation.tv.TvFocusRing
import kotlin.math.ceil

/**
 * 固定聚焦框的样子 (钉死锚点版式, 见 [TvNativeAnchorRingView]): 线宽 [strokePx], 外圆角 [cornerPx], 从左上 [startColor] 渐变到右下 [endColor] (ARGB).
 */
@Immutable
data class TvNativeAnchorRingStyle(
    val strokePx: Float,
    val cornerPx: Float,
    val startColor: Int,
    val endColor: Int,
)

/**
 * 海报卡 ([style]) 的固定聚焦框样子, 同详情页选集行的锚位框 (TvNativeEpisodeRingView): 线宽 [TvFocusRing.Width] (取整像素), 圆角 = 封面圆角 +
 * 聚焦框空隙 (框画在卡格的边上, 卡格比封面四周各大一份空隙), 主题主色渐变到次色.
 */
@Composable
fun rememberTvNativeAnchorRingStyle(style: TvNativeWallStyle): TvNativeAnchorRingStyle {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    return remember(density, colors, style.cornerPx, style.gapPx) {
        with(density) {
            TvNativeAnchorRingStyle(
                strokePx = ceil(TvFocusRing.Width.toPx()),
                cornerPx = style.cornerPx + style.gapPx,
                startColor = colors.primary.toArgb(),
                endColor = colors.secondary.toArgb(),
            )
        }
    }
}

/**
 * 钉死锚点版式的固定聚焦框 (探索页, 见 TvNativeRowWallView.fixedAnchor): 停在 hero 线上聚焦行的行首停靠线处, 不随卡片动, 卡片从它下面滑过.
 * 由宿主摆成卡格大小; 描边画在框内侧 (同 TvFocusRing 的 tvFocusRingBorder). 显隐由宿主改透明度.
 */
@SuppressLint("ViewConstructor")
internal class TvNativeAnchorRingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE }
    private val rect = RectF()
    private var builtWidth = -1
    private var builtHeight = -1

    var ringStyle: TvNativeAnchorRingStyle? = null
        set(value) {
            if (field == value) return
            field = value
            builtWidth = -1
            invalidate()
        }

    init {
        isFocusable = false
        isClickable = false
    }

    private fun build(s: TvNativeAnchorRingStyle, w: Int, h: Int) {
        builtWidth = w
        builtHeight = h
        paint.strokeWidth = s.strokePx
        paint.color = s.startColor
        paint.shader = if (s.startColor == s.endColor) {
            null
        } else {
            LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), s.startColor, s.endColor, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = ringStyle ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (w != builtWidth || h != builtHeight) build(s, w, h)
        val half = s.strokePx / 2f
        rect.set(half, half, w - half, h - half)
        val r = (s.cornerPx - half).coerceAtLeast(0f)
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun hasOverlappingRendering(): Boolean = false
}
