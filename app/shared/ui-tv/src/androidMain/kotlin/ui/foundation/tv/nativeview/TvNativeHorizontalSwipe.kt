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
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import me.him188.ani.app.ui.foundation.tv.TvWallSwipeClaim
import me.him188.ani.app.ui.foundation.tv.tvWallSwipeClaim
import me.him188.ani.app.ui.foundation.tv.tvWallSwipeStep

/**
 * 触屏设备 (平板装了 TV 包) 上原生页的左右滑: 左滑 = 下一项、右滑 = 上一项 (同上游手机版轮播 / 分页的方向), 一下翻一项.
 * 探索页热门轮播 (同遥控器在 hero 按钮上按右 / 左, 见 [TvNativeExploreView.touchInput]) 与网格页换标签 / 换天
 * (见 [TvNativeGridPageView.onTouchSwipe]) 共用. 只在触摸开关打开时建, 电视上不存在.
 *
 * 只认横向: 按下落在 [inArea] 里, 先横着越过 touch slop 且横向占优才接管 —— 子视图收到取消 (按钮不点、列表不滚);
 * 先竖着越过的整个手势放给列表. 松手时横向位移过 [TV_WALL_SWIPE_DISTANCE_DP] 或横向速度过
 * [TV_WALL_SWIPE_VELOCITY_DP] (方向与位移一致) 就翻一项, 否则不翻.
 */
internal class TvNativeHorizontalSwipe(
    context: Context,
    private val host: View,
    /** 按下点 (宿主坐标) 在不在认滑动的区域. */
    private val inArea: (x: Float, y: Float) -> Boolean,
    /** 翻一项: +1 下一项, -1 上一项. */
    private val onSwipe: (delta: Int) -> Unit,
) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false
    private var velocity: VelocityTracker? = null

    /** 宿主的 onInterceptTouchEvent: true = 接管这个手势 (之后的事件进 [onTouch]). */
    fun onIntercept(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(event)
            MotionEvent.ACTION_MOVE -> if (tracking) {
                velocity?.addMovement(event)
                if (!dragging) decide(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return dragging
    }

    /** 宿主的 onTouchEvent: 接管之后的事件, 或按下时没有子视图要的手势. true = 消费. */
    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start(event)
                return tracking
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                velocity?.addMovement(event)
                if (!dragging) decide(event)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                velocity?.addMovement(event)
                if (dragging) finish(event)
                reset()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                val was = tracking
                reset()
                return was
            }
        }
        return tracking
    }

    private fun start(event: MotionEvent) {
        reset()
        downX = event.x
        downY = event.y
        tracking = inArea(event.x, event.y)
        if (tracking) velocity = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun decide(event: MotionEvent) {
        when (tvWallSwipeClaim(event.x - downX, event.y - downY, slop.toFloat())) {
            TvWallSwipeClaim.Claim -> {
                dragging = true
                host.parent?.requestDisallowInterceptTouchEvent(true)
            }

            TvWallSwipeClaim.Release -> reset()
            TvWallSwipeClaim.Undecided -> {}
        }
    }

    private fun finish(event: MotionEvent) {
        val vx = velocity?.let {
            it.computeCurrentVelocity(1000)
            it.xVelocity
        } ?: 0f
        val step = tvWallSwipeStep(event.x - downX, vx, density)
        if (step != 0) onSwipe(step)
    }

    private fun reset() {
        tracking = false
        dragging = false
        velocity?.recycle()
        velocity = null
    }
}
