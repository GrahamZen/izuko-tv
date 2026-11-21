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
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import kotlin.math.min

// 自己量、自己摆子视图的原生小视图 (弹窗内容、设置页的行与说明栏) 共用的量法与字视图工具.

/** 不限尺寸的量法 (按内容量). */
internal val TV_NATIVE_UNSPECIFIED: Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

/** 最多 [px] 的量法; 负数按 0. */
internal fun tvNativeAtMost(px: Int): Int = View.MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), View.MeasureSpec.AT_MOST)

/** 正好 [px] 的量法; 负数按 0. */
internal fun tvNativeExactly(px: Int): Int = View.MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), View.MeasureSpec.EXACTLY)

/** 按量好的尺寸摆在 ([x], [y]). */
internal fun View.tvNativeLayoutAt(x: Int, y: Int) = layout(x, y, x + measuredWidth, y + measuredHeight)

/** 新建一个字视图: 按内容定高 (换字时重新测量, 不按上一段字的高), 至多 [maxLines] 行、放不下截尾, 套上 [style]. */
internal fun tvNativeText(context: Context, style: TvNativeTextStyle, maxLines: Int = 1): TvNativeTextView =
    TvNativeTextView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        style.applyTo(this)
    }

/**
 * 按内容宽量, 最宽 [room]: 先不限宽量出要多宽, 放得下就多给 [TV_NATIVE_TEXT_SLACK_PX] 按定宽排 —— 按 AT_MOST 量出的宽度有时比排版要的
 * 少零点几像素 (回落字体的字, 如行尾的 ›), 最后一个字被挤到下一行; 放不下的按 [room] 折行或截断.
 */
internal fun TvNativeTextView.measureWithin(room: Int) {
    measure(TV_NATIVE_UNSPECIFIED, TV_NATIVE_UNSPECIFIED)
    if (measuredWidth <= room) {
        measure(tvNativeExactly(min(measuredWidth + TV_NATIVE_TEXT_SLACK_PX, room)), TV_NATIVE_UNSPECIFIED)
    } else {
        measure(tvNativeAtMost(room), TV_NATIVE_UNSPECIFIED)
    }
}

/** [measureWithin] 多给的宽 (px). */
private const val TV_NATIVE_TEXT_SLACK_PX = 2

/** 字变了才换 (换字会让它重新排版). 带样式的字 (粗体) 每次都换. */
internal fun TvNativeTextView.setTextIfChanged(value: CharSequence) {
    if (value !is Spanned && text.toString() == value.toString()) return
    text = value
}

/** Compose 的带样式字换成原生的: 粗体 (字重 ≥ 600) 与颜色, 其余照纯文字. */
internal fun AnnotatedString.toTvNativeText(): CharSequence {
    if (spanStyles.isEmpty()) return text
    val spannable = SpannableString(text)
    for (range in spanStyles) {
        val weight = range.item.fontWeight?.weight
        if (weight != null && weight >= 600) {
            spannable.setSpan(StyleSpan(Typeface.BOLD), range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val color = range.item.color
        if (color.isSpecified) {
            spannable.setSpan(ForegroundColorSpan(color.toArgb()), range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return spannable
}
