/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * 触屏上列表还在滚 (手指甩出去的惯性, 或进 hero 态那一段程序滚动) 时按下去: RecyclerView 默认把这一下当成「接住」—— 当场进拖动态
 * (SCROLL_STATE_DRAGGING)、整个手势归列表, 点在卡片上的这一下到不了卡片, 页面还当成手指拖了 (按触屏规矩停放焦点, hero 态原地退出).
 *
 * 列表的 onInterceptTouchEvent 里先调它: 按下时还在滚就先停住, 这一下照常交给卡片 (轻点就是点卡片, 卡片停在手指按下时看到的位置);
 * 手指真的动起来 (过了 touch slop) 列表照常把它接成拖动.
 */
internal fun RecyclerView.stopScrollOnTouchDown(ev: MotionEvent) {
    if (ev.actionMasked == MotionEvent.ACTION_DOWN && scrollState == RecyclerView.SCROLL_STATE_SETTLING) stopScroll()
}
