/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs

/**
 * 触屏上 hero 态里点卡片墙的空白处 (卡片之间、行与行之间) 退回卡片墙, 同遥控器按返回.
 *
 * 只认轻点: 没拖过 touch slop、没到长按时长. 「空白处」= 落在 [page] 里所有看得见的海报卡的外包框之内, 又不在任何卡片的封面
 * (hero 态里卡片只认封面, 四周留白与番名那块不算, 见 [TvNativeCardView.touchTargetVisibleRect]) 或别的可点视图 (hero 按钮等) 上 ——
 * hero 上方的大图与文字不算. 页面在 dispatchTouchEvent 里先喂给它, 只看不拦, 卡片的点击与列表滚动照常. 只在触屏设备上装, 电视上没有这一层.
 */
internal class TvNativeHeroBlankTap(
    private val page: ViewGroup,
    /** 现在能不能退 (在 hero 态、没在点开途中). */
    private val active: () -> Boolean,
    private val onBlankTap: () -> Unit,
) {
    private val touchSlop = ViewConfiguration.get(page.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tracking = false
    private val pageXY = IntArray(2)
    private val rect = Rect()

    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downTime = ev.eventTime
                page.getLocationInWindow(pageXY)
                tracking = active() && onBlank((pageXY[0] + ev.x).toInt(), (pageXY[1] + ev.y).toInt())
            }

            MotionEvent.ACTION_MOVE ->
                if (abs(ev.rawX - downX) > touchSlop || abs(ev.rawY - downY) > touchSlop) tracking = false

            MotionEvent.ACTION_UP -> {
                if (tracking && ev.eventTime - downTime < ViewConfiguration.getLongPressTimeout() && active()) onBlankTap()
                tracking = false
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
        }
    }

    /** 窗口坐标 ([x], [y]) 落在卡片墙范围里、又不在卡片封面或别的可点视图上. */
    private fun onBlank(x: Int, y: Int): Boolean {
        val wall = Rect()
        var onClickable = false
        fun visit(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.visibility != View.VISIBLE || child.alpha == 0f) continue
                when {
                    child is TvNativeCardView -> if (child.touchTargetVisibleRect(rect)) {
                        if (rect.contains(x, y)) onClickable = true
                        wall.union(rect)
                    }

                    child.isClickable || child.isLongClickable ->
                        if (child.getGlobalVisibleRect(rect) && rect.contains(x, y)) onClickable = true

                    child is ViewGroup -> visit(child)
                }
            }
        }
        visit(page)
        return !onClickable && wall.contains(x, y)
    }
}
