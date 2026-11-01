/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import me.him188.ani.app.ui.foundation.focus.TvGridFocusState
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.wall.TvWallGridPage

/** iOS 上画 Compose 版 (见 TvWallGridPage). */
@Composable
internal actual fun <T : Any> TvNativeGridPageHostContent(
    state: TvNativeGridPageState,
    metrics: TvNativeGridPageMetrics,
    cardWidth: Dp,
    gridKey: Int,
    slideDirection: (from: Int, to: Int) -> Int,
    cards: List<TvNativeCard?>,
    itemAt: (Int) -> T?,
    itemCount: () -> Int,
    onBind: (Int) -> Unit,
    heroEnabled: Boolean,
    badge: TvNativeCardBadgeStyle?,
    source: TvNativeHeroSource?,
    fadeColor: Color,
    treatment: TvBackdropTreatment,
    gridFocus: TvGridFocusState,
    farJump: () -> Boolean,
    onFarJumpConsumed: () -> Unit,
    callbacks: TvNativeGridPageCallbacks<T>,
    menuFor: (T) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
    wallBackdrop: TvNativeWallBackdropSpec?,
    modifier: Modifier,
    landingIndex: Int,
    topBarScrollAwayPx: Int,
    columnSpacing: Dp,
    cardHeight: Dp?,
    labelVibrancy: Boolean,
    // Compose 版没有一组一行的内容 (追番页「追更」, 这一端不显示)
    rows: TvNativeGridPageRows?,
    emptyContent: @Composable BoxScope.() -> Unit,
) {
    TvWallGridPage(
        state, metrics, cardWidth, gridKey, slideDirection, cards, itemAt, itemCount, onBind, heroEnabled, badge, source, fadeColor,
        treatment, gridFocus, farJump, onFarJumpConsumed, callbacks, menuFor, wallBackdrop, modifier, landingIndex, topBarScrollAwayPx,
        columnSpacing, cardHeight, emptyContent,
    )
}
