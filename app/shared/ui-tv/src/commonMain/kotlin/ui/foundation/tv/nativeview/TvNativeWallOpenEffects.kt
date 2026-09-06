/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff

/** 能点开整屏背景的原生页面视图 (网格页 / 探索页, 见 TvNativeWallFocus). */
interface TvNativeWallOpenable {
    /** 恢复点开的状态 (返回时页面重建): 卡片淡没、背景清晰, 等 [endWallOpen]. */
    fun restoreWallOpen()

    /** 从点开进去的详情页回来了: 倒放回模糊与卡片. */
    fun endWallOpen()

    /** 点开途中按了返回: 不进了, 倒放. */
    fun cancelWallOpen()
}

/**
 * 整屏背景点开 (见 TvNativeWallFocus) 在 Compose 这一侧的收尾, 新番时间表与探索 / 追番 / 搜索 hero 态的模糊背景共用: 点开进了详情页, 回到本页
 * (栈顶) 且缩回层撤掉之后倒放 (返回时页面重建先恢复成点开的样子, 缩回落地时列表页与缩回层是同一张清晰图); 点开途中按返回取消.
 * 点开的记录由页面跨重建保存.
 *
 * @param opened 点开进了详情页 (快照状态), 倒放时由 [clear] 清掉
 * @param opening 点开途中 (对焦还没到位, 快照状态)
 * @param left 点开之后本页离开过前台 (真进了详情页; 恢复出来的状态算离开过), 由 [markLeft] 记下
 */
@Composable
internal fun TvNativeWallOpenEffects(
    view: TvNativeWallOpenable?,
    opened: () -> Boolean,
    opening: () -> Boolean,
    left: () -> Boolean,
    markLeft: () -> Unit,
    clear: () -> Unit,
) {
    val pageForeground = LocalPageIsForeground.current
    val currentOpened by rememberUpdatedState(opened)
    val currentLeft by rememberUpdatedState(left)
    val currentMarkLeft by rememberUpdatedState(markLeft)
    val currentClear by rememberUpdatedState(clear)
    LaunchedEffect(view) {
        if (view == null) return@LaunchedEffect
        if (currentOpened()) view.restoreWallOpen()
        snapshotFlow { TvNativeWallOpenSignal(currentOpened(), pageForeground.value, TvHeroZoomHandoff.shrinking) }
            .collectLatest { s ->
                if (!s.opened) return@collectLatest
                if (!s.foreground) {
                    currentMarkLeft()
                    return@collectLatest
                }
                if (s.shrinking) return@collectLatest
                // 点开之后一直没离开前台: 导航没发出去 (前进导航的转场闸门挡了), 过一会儿还在就当没进
                if (!currentLeft()) delay(TV_WALL_OPEN_LEAVE_TIMEOUT_MILLIS)
                currentClear()
                view.endWallOpen()
            }
    }
    BackHandler(enabled = opening()) { view?.cancelWallOpen() }
}

private data class TvNativeWallOpenSignal(val opened: Boolean, val foreground: Boolean, val shrinking: Boolean)

/** 整屏背景点开之后多久还没离开前台就当导航没发出去 (倒放回卡片). */
private const val TV_WALL_OPEN_LEAVE_TIMEOUT_MILLIS = 1_000L
