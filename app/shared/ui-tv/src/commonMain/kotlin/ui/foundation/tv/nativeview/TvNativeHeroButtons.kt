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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.runtime.Immutable

/**
 * hero 操作按钮的外观 (原生版 TvHeroButton, 取值同 Compose 版: 按 TV_HERO_BUTTON_SCALE 缩过的内边距 / 图标 / 字号, 圆角 8dp).
 * 未聚焦: 主按钮 [filledColor] / 次按钮 [unfilledColor] 底板 + 一圈 [outlineWidthPx] 细描边, 字与图标 [contentColor];
 * 聚焦: 底板 [focusedColor] (叠一层 [focusedContentColor] 10% 的聚焦态层, 同 M3 Surface 的聚焦指示), 字与图标 [focusedContentColor], 不描边.
 */
@Immutable
data class TvNativeHeroButtonStyle(
    val text: TvNativeTextStyle,
    val iconSizePx: Int,
    val iconGapPx: Int,
    val paddingHorizontalPx: Int,
    val paddingVerticalPx: Int,
    val cornerPx: Float,
    val outlineWidthPx: Float,
    val outlineColor: Int,
    val filledColor: Int,
    val unfilledColor: Int,
    val focusedColor: Int,
    val contentColor: Int,
    val focusedContentColor: Int,
)

/**
 * 一颗 hero 操作按钮: 图标 + 单行字, 宽度按内容. 确定键短按 = [View.performClick] (系统默认的按键处理).
 * [onFocused] 在拿到焦点时回调.
 */
@SuppressLint("ViewConstructor")
class TvNativeHeroButton(
    context: Context,
    style: TvNativeHeroButtonStyle,
    private val filled: Boolean,
) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val label = TvNativeTextView(context)
    private val plate = GradientDrawable()

    var onFocused: (() -> Unit)? = null

    var style: TvNativeHeroButtonStyle = style
        set(value) {
            if (field == value) return
            field = value
            applyStyle()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 触摸模式下也可聚焦 (同卡片, 见 TvNativeCardView)
        isFocusableInTouchMode = true
        isClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        label.setSingleLine(true)
        addView(icon)
        addView(label)
        background = plate
        applyStyle()
    }

    fun bind(text: String, iconBitmap: Bitmap?) {
        label.text = text
        contentDescription = text
        icon.setImageBitmap(iconBitmap)
    }

    private fun applyStyle() {
        val s = style
        s.text.applyTo(label)
        setPadding(s.paddingHorizontalPx, s.paddingVerticalPx, s.paddingHorizontalPx, s.paddingVerticalPx)
        icon.layoutParams = LayoutParams(s.iconSizePx, s.iconSizePx).apply { marginEnd = s.iconGapPx }
        plate.cornerRadius = s.cornerPx
        applyFocusColors()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        applyFocusColors()
        if (gainFocus) onFocused?.invoke()
    }

    private fun applyFocusColors() {
        val s = style
        val focused = isFocused
        val container = when {
            focused -> tvNativeLerpColor(s.focusedColor, s.focusedContentColor, TV_NATIVE_FOCUS_STATE_LAYER_ALPHA)
            filled -> s.filledColor
            else -> s.unfilledColor
        }
        plate.setColor(container)
        if (focused) {
            plate.setStroke(0, 0)
        } else {
            plate.setStroke(s.outlineWidthPx.toInt().coerceAtLeast(1), s.outlineColor)
        }
        val content = if (focused) s.focusedContentColor else s.contentColor
        label.setTextColor(content)
        icon.colorFilter = PorterDuffColorFilter(content, PorterDuff.Mode.SRC_IN)
    }
}

/** M3 聚焦态层的不透明度 (StateTokens.FocusStateLayerOpacity). */
private const val TV_NATIVE_FOCUS_STATE_LAYER_ALPHA = 0.1f

/**
 * 轮播指示器 (原生版 TvCarouselIndicator): 横排小圆点, 当前项为 [selectedWidthPx] 宽的胶囊, 其余 [dotPx] 见方的圆点, 间距 [gapPx];
 * 当前项 [color], 其余 [color] 40%. 在自身宽度内水平居中, 竖直居中. 不可聚焦, 少于 2 项不画.
 */
@SuppressLint("ViewConstructor")
class TvNativeCarouselDotsView(
    context: Context,
    private val dotPx: Float,
    private val selectedWidthPx: Float,
    private val gapPx: Float,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var color: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var count: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var selectedIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        isFocusable = false
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val n = count
        if (n <= 1) return
        val total = selectedWidthPx + (n - 1) * dotPx + (n - 1) * gapPx
        var x = (width - total) / 2f
        val top = (height - dotPx) / 2f
        val radius = dotPx / 2f
        for (i in 0 until n) {
            val active = i == selectedIndex
            val w = if (active) selectedWidthPx else dotPx
            paint.color = if (active) color else tvNativeWithAlpha(color, TV_NATIVE_DOT_INACTIVE_ALPHA)
            rect.set(x, top, x + w, top + dotPx)
            canvas.drawRoundRect(rect, radius, radius, paint)
            x += w + gapPx
        }
    }
}

private const val TV_NATIVE_DOT_INACTIVE_ALPHA = 0.4f
