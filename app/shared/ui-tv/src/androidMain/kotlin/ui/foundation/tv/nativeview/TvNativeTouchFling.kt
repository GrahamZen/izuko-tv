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
import android.widget.OverScroller

/**
 * 手指松手时以 [velocity] (px/s, 正 = 滚动量变大, 同 RecyclerView.fling 的参数) 甩出去, 照系统的惯性会再滚多远 (px).
 * 原生列表在触屏上松手后不照惯性停在半路, 而是拿这个落点挑最近的停位滚过去 (就近停下, 见 TvNativeExploreView / TvNativeGridView).
 */
internal fun tvNativeFlingDistance(context: Context, velocity: Int): Int {
    if (velocity == 0) return 0
    val scroller = OverScroller(context)
    scroller.fling(0, 0, 0, velocity, 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
    return scroller.finalY
}
