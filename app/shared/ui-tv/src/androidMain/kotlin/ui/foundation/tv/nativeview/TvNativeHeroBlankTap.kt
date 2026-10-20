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
 * 触屏上 hero 态里点卡片以外的地方: 点卡片墙的空白处 (卡片之间、行与行之间) 退回卡片墙, 同遥控器按返回; 点 hero 区 (上方的背景图与文字,
 * [heroAreaBottom] 以上) 打开聚焦的那张 ([onHeroTap], 同点它).
 *
 * 只认轻点: 没拖过 touch slop、没到长按时长. 「空白处」= 在 [page] 里所有看得见的海报卡的外包框顶边以下 (卡片之间与卡片下面), 又不在任何卡片的封面
 * (hero 态里卡片只认封面, 四周留白与番名那块不算, 见 [TvNativeCardView.touchTargetVisibleRect]) 或别的可点视图 (hero 按钮等) 上.
 * 页面在 dispatchTouchEvent 里先喂给它, 只看不拦, 卡片的点击与列表滚动照常; 按下时没有子视图接 (hero 区底下没有列表) 就由页面在
 * onTouchEvent 里按 [tracking] 接住, 抬起才派发得到. 只在触屏设备上装, 电视上没有这一层.
 */
internal class TvNativeHeroBlankTap(
    private val page: ViewGroup,
    /** 现在能不能退 (在 hero 态、没在点开途中). */
    private val active: () -> Boolean,
    /** hero 区下缘在窗口里的纵坐标. */
    private val heroAreaBottom: () -> Int,
    private val onHeroTap: () -> Unit,
    private val onBlankTap: () -> Unit,
) {
    private val touchSlop = ViewConfiguration.get(page.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    /** 这一下按在了哪 (null = 不管). */
    private var target: Target? = null

    /** 这一下按在 hero 区或空白处, 还没拖开. */
    val tracking: Boolean get() = target != null
    private val pageXY = IntArray(2)
    private val rect = Rect()

    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downTime = ev.eventTime
                page.getLocationInWindow(pageXY)
                target = if (active()) targetAt((pageXY[0] + ev.x).toInt(), (pageXY[1] + ev.y).toInt()) else null
            }

            MotionEvent.ACTION_MOVE ->
                if (abs(ev.rawX - downX) > touchSlop || abs(ev.rawY - downY) > touchSlop) target = null

            MotionEvent.ACTION_UP -> {
                val tapped = target
                target = null
                if (tapped != null && ev.eventTime - downTime < ViewConfiguration.getLongPressTimeout() && active()) {
                    when (tapped) {
                        Target.Hero -> onHeroTap()
                        Target.Blank -> onBlankTap()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> target = null
        }
    }

    private enum class Target { Hero, Blank }

    /** 窗口坐标 ([x], [y]) 不在卡片封面或别的可点视图上时: 在 hero 区里, 或落在卡片墙范围里 (空白处); 都不是时 null. */
    private fun targetAt(x: Int, y: Int): Target? {
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
        return when {
            onClickable -> null
            y < heroAreaBottom() -> Target.Hero
            // 卡片下面也算空白: 网格页的 hero 态只剩一排卡, 只认外包框以内的话那一排下面怎么点都退不出来
            !wall.isEmpty && y >= wall.top -> Target.Blank
            else -> null
        }
    }
}
