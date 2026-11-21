/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreItem
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageRows
import me.him188.ani.app.ui.foundation.tv.tvPosterWallHeaderStyle

/*
 * 网格页 (Compose 版, TvWallGridPage) 里与各份网格换着显示的一组一行的内容 (追番页「追更」, 见 TvNativeGridPageRows): 行式海报墙
 * ([TvWallRowsSurface]) 的宾客 —— hero 态、背景图与文字、点开的对焦都是网格页的 (同原生 TvNativeRowWallView 的宾客模式), 换标签时与网格一起水平滑,
 * hero 态跨标签留着. 数据与事件由页面对 TvNativeGridPageState.rows 接 (见 TvCollectionProgressPane).
 */

/** Compose 版的一组一行的内容只要几何与卡宽 (见 [TvNativeGridPageRows]). */
@Immutable
internal class TvWallGridRowsSpec(
    override val key: Int,
    override val metrics: TvNativeExploreMetrics,
    override val cardWidth: Dp,
) : TvNativeGridPageRows

/**
 * 网格页里的行式海报墙 (宾客): 进出 hero 态交给宿主 [host] (hero 时间线是它的), 用手指还是按键跟着宿主 (宿主在整页上分辨); 前几行在 hero 态里
 * 放不到 hero 线时整片往下推 (列表顶上没有探索页那样的轮播区垫着). 焦点停放在自己的 [parkNode] 上.
 */
@Stable
internal class TvWallRowsGuest(
    private val host: TvWallGridSurface,
    scope: CoroutineScope,
    val parkNode: FocusRequester = FocusRequester(),
) : TvWallRowsSurface(host.timeline, scope, parkNode, 0, emptyMap(), emptyMap(), null, 0) {
    /**
     * 点开 (hero 态里按确定): 网格页的模糊背景此刻是 [subjectId] 那部的、能变清晰就先对焦, 到位再 [proceed] (返回 true); 否则返回 false,
     * 当场进. [index] 记给网格页 (回到本页倒放).
     */
    var openWall: (subjectId: Int, index: Int, proceed: () -> Unit) -> Boolean = { _, _, _ -> false }

    override val touchMode: Boolean get() = touchInput && host.lastInputTouch

    override val heroPushDown: Boolean get() = true

    override val logTag: String get() = "ProgressRows"

    override fun applyHeroActive(active: Boolean, animated: Boolean) {
        log { "请网格页${if (active) "进" else "退出"} hero 态" }
        host.setHeroActive(active, animated)
    }

    override fun openCard(rowKey: String, index: Int) {
        val subjectId = (items.firstOrNull { it.key == rowKey } as? TvNativeExploreItem.Row)?.cards?.getOrNull(index)?.subjectId
        val opened = subjectId != null && openWall(subjectId, index) { rowListener?.onCardClick(rowKey, index) }
        log { "进详情页 $rowKey/$index (条目 $subjectId, ${if (opened) "模糊底先对焦" else "当场进"})" }
        if (!opened) rowListener?.onCardClick(rowKey, index)
    }

    /** hero 态里 (宿主的) 恢复到聚焦行的 hero 停位, 否则回存着的停位. */
    override fun restoreStop(scrollPx: Int) {
        if (timeline.active && syncHeroStop()) return
        super.restoreStop(scrollPx)
    }
}

/**
 * [TvWallRowsGuest] 的画面, 放在网格页换着显示的那一格里 (格顶在页面的 [slotTopPx] 处, 几何都从页面顶算): 卡片列表 + 焦点停放处, 方向键在这里接.
 * [extraFade] = 点开时跟着淡没.
 */
@Composable
internal fun TvWallGridRowsContent(
    guest: TvWallRowsGuest,
    spec: TvNativeGridPageRows,
    slotTopPx: Int,
    extraFade: () -> Float,
) {
    // 没看几集画在封面右上角 (见 TvProgressEntry.badgeCount)
    val style = rememberTvWallCardStyle(spec.cardWidth, countBadge = true)
    val headerStyle = tvPosterWallHeaderStyle()
    // 换到这一格时对上宿主此刻的 hero 态 (宿主在这一格不在屏上时进出过 hero 态), 同原生 showRows
    LaunchedEffect(guest) { guest.syncHeroStop() }
    Box(
        Modifier
            .fillMaxSize()
            .onFocusChanged { guest.hasAnyFocus = it.hasFocus }
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && guest.handleKey(event.key) },
    ) {
        Box(Modifier.size(1.dp).focusRequester(guest.parkNode).focusable())
        TvWallRowsList(guest, guest.items, style, headerStyle, extraFade, pageTopPx = -slotTopPx)
    }
}
