/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.os.SystemClock
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.ui.foundation.tv.tvWallTapPassesScroll
import kotlin.math.hypot

/**
 * 触屏上列表还在滚 (手指甩出去的惯性, 或进 hero 态那一段程序滚动) 时按下去: RecyclerView 默认把这一下当成「接住」—— 当场进拖动态
 * (SCROLL_STATE_DRAGGING)、整个手势归列表, 点在卡片上的这一下到不了卡片, 页面还当成手指拖了 (按触屏规矩停放焦点, hero 态原地退出).
 *
 * 列表的 onInterceptTouchEvent 里先调它: 按下时还在滚就先停住. 程序滚动与滑得慢的惯性 ([tvWallTapPassesScroll]), 这一下照常交给卡片
 * (轻点就是点卡片, 卡片停在手指按下时看到的位置); 甩得快的惯性只停住、这一下归列表 (返回 true, onInterceptTouchEvent 照它拦下),
 * 不算点卡片, 也不进拖动态. 手指真的动起来 (过了 touch slop) 列表照常把它接成拖动.
 *
 * @param speed 这个列表挂着的滚动速度 (见 [TvNativeScrollSpeed])
 * @return true = 这一下由列表接住
 */
internal fun RecyclerView.stopScrollOnTouchDown(ev: MotionEvent, speed: TvNativeScrollSpeed): Boolean {
    if (ev.actionMasked != MotionEvent.ACTION_DOWN || scrollState != RecyclerView.SCROLL_STATE_SETTLING) return false
    val catches = speed.fromTouch && !tvWallTapPassesScroll(speed.pxPerSecond / resources.displayMetrics.density)
    stopScroll()
    return catches
}

/**
 * 列表此刻滑得多快 (px/秒, 每帧按这一帧的位移与时间算; 停下为 0), 以及这一段是不是手指拖出来的 ([fromTouch]). 挂成列表的滚动监听.
 */
internal class TvNativeScrollSpeed : RecyclerView.OnScrollListener() {
    /** 这一段滚动是手指拖出来的 (进过 SCROLL_STATE_DRAGGING, 之后的惯性也算); 进 hero 态那段程序滚动不算. */
    var fromTouch = false
        private set

    var pxPerSecond = 0f
        private set

    private var lastMillis = 0L

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        when (newState) {
            RecyclerView.SCROLL_STATE_DRAGGING -> fromTouch = true
            RecyclerView.SCROLL_STATE_IDLE -> {
                fromTouch = false
                pxPerSecond = 0f
                lastMillis = 0L
            }
        }
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        // 布局换了内容也会报一下 (0, 0), 不算
        if (dx == 0 && dy == 0) return
        val now = SystemClock.uptimeMillis()
        val dt = now - lastMillis
        if (lastMillis != 0L && dt > 0) pxPerSecond = hypot(dx.toFloat(), dy.toFloat()) * 1000f / dt
        lastMillis = now
    }
}
