/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 一排按钮: 从左往右排, 下一个放不下就整个换到下一行. 横排的 LinearLayout 放不下时会把最后一个按钮压窄,
 * 字竖着排 (右边有交流群一栏时三个按钮就放不下).
 */
internal class ButtonFlow(context: Context, private val horizontalGap: Int, private val verticalGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(available.coerceAtLeast(0), if (widthMode == MeasureSpec.UNSPECIFIED) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            if (x > 0 && x + child.measuredWidth > available) {
                x = 0
                y += rowHeight + verticalGap
                rowHeight = 0
            }
            x += child.measuredWidth
            widest = maxOf(widest, x)
            x += horizontalGap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        val width = if (widthMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else widest + paddingLeft + paddingRight
        setMeasuredDimension(width, y + rowHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (x > 0 && x + child.measuredWidth > available) {
                x = 0
                y += rowHeight + verticalGap
                rowHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + horizontalGap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}
