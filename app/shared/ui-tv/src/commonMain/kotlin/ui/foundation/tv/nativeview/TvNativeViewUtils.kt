/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.compose.runtime.snapshots.Snapshot

/**
 * 定尺寸的原生块 (hero 文字块、按钮块): 尺寸由父布局给死, 自己的内容变化 (换字、换图) 只在自己里面重排, 不往上冒泡 ——
 * 原生树挂在 Compose 的 AndroidView 里, 冒泡上去会让 Compose 重测整个 AndroidView (连带整棵原生树走一遍测量).
 *
 * 子视图请求重排时记一笔, 在下一个动画帧按当前尺寸自己测量、布局一次 (同帧的多次请求合并). 还没布局过、或尺寸本来就要变时照常冒泡.
 */
abstract class TvNativeBoundaryLayout(context: Context) : ViewGroup(context) {
    private var inPass = false
    private var relayoutPosted = false
    private val relayout = Runnable {
        relayoutPosted = false
        if (!isAttachedToWindow || measuredWidth <= 0) return@Runnable
        inPass = true
        try {
            measure(
                MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
            )
            layout(left, top, right, bottom)
        } finally {
            inPass = false
        }
        invalidate()
    }

    final override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val nested = inPass
        inPass = true
        try {
            measureContent(widthMeasureSpec, heightMeasureSpec)
        } finally {
            inPass = nested
        }
    }

    final override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val nested = inPass
        inPass = true
        try {
            layoutContent(r - l, b - t)
        } finally {
            inPass = nested
        }
    }

    protected abstract fun measureContent(widthMeasureSpec: Int, heightMeasureSpec: Int)
    protected abstract fun layoutContent(width: Int, height: Int)

    override fun requestLayout() {
        if (inPass) {
            // 自己正在测量 / 布局 (如测量时按剩余高度改简介的行数): 本次过程会把它排进去
            return
        }
        if (isLaidOut && parent != null && measuredWidth > 0) {
            forceLayout()
            if (!relayoutPosted) {
                relayoutPosted = true
                postOnAnimation(relayout)
            }
            return
        }
        super.requestLayout()
    }
}

/** 字号 (px) 设给 TextView. */
internal fun TextView.setTextSizePx(px: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, px)

/** Compose 的 FastOutSlowInEasing. */
/** [view] 是 [ancestor] 本身或在它底下. */
internal fun tvNativeIsInside(view: View?, ancestor: View): Boolean {
    var v: Any? = view
    while (v is View) {
        if (v === ancestor) return true
        v = v.parent
    }
    return false
}

internal val TV_NATIVE_FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)

/** Compose 的 LinearOutSlowInEasing (新字滑入的位移曲线). */
internal val TV_NATIVE_LINEAR_OUT_SLOW_IN = PathInterpolator(0f, 0f, 0.2f, 1f)

/** [color] 换成 [alpha] (0..1) 的不透明度. */
internal fun tvNativeWithAlpha(color: Int, alpha: Float): Int =
    AndroidColor.argb(
        (AndroidColor.alpha(color) * alpha).toInt().coerceIn(0, 255),
        AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color),
    )

/**
 * 两个颜色插值, 按预乘 alpha 算 (同两层交叉淡化的样子): 一头是全透明 (`0`, 即透明的黑) 时只淡出, 颜色不往黑里走 ——
 * 逐通道直接插的话, 白底淡出途中是半透明的灰, 浅色页面上焦点一走旧位置闪一下灰. 两头原样返回.
 */
internal fun tvNativeLerpColor(a: Int, b: Int, t: Float): Int {
    if (t <= 0f) return a
    if (t >= 1f) return b
    val alphaA = AndroidColor.alpha(a) / 255f
    val alphaB = AndroidColor.alpha(b) / 255f
    val alpha = alphaA + (alphaB - alphaA) * t
    if (alpha <= 0f) return 0
    fun ch(x: Int, y: Int) = ((x * alphaA + (y * alphaB - x * alphaA) * t) / alpha).toInt().coerceIn(0, 255)
    return AndroidColor.argb(
        (alpha * 255f + 0.5f).toInt().coerceIn(0, 255),
        ch(AndroidColor.red(a), AndroidColor.red(b)),
        ch(AndroidColor.green(a), AndroidColor.green(b)),
        ch(AndroidColor.blue(a), AndroidColor.blue(b)),
    )
}

/**
 * 原生视图把值写进 Compose 快照状态后当场派发 (不等 Compose 下一次派发): 写在动画 / 滚动回调里时, Compose 那边读这个值的
 * 绘制层在同一帧失效、同一帧重画 (主壳整屏底色的分界线要与原生背景图同帧挪).
 */
internal inline fun tvNativeWriteSnapshot(write: () -> Unit) {
    write()
    Snapshot.sendApplyNotifications()
}
