/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import me.him188.ani.app.ui.foundation.tv.TvPageBackdropGeometry
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreItem
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreListener
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreMetrics

/** iOS 上画 Compose 版 (见 TvExplorationComposeWall). */
@Composable
internal actual fun TvExplorationNativeWall(
    state: TvExplorationNativeState,
    metrics: TvNativeExploreMetrics,
    cardWidth: Dp,
    items: List<TvNativeExploreItem>,
    carouselCount: Int,
    carouselIndex: () -> Int,
    fadeColor: Color,
    geometry: TvPageBackdropGeometry,
    splitGate: () -> Float,
    listener: TvNativeExploreListener,
    cardFocusRequest: () -> TvCardFocusRequest?,
    heroFocusRequest: () -> TvHeroFocusRequest?,
    farJumpRow: () -> String?,
    focusedRowKey: () -> String?,
    focusedCardIndex: () -> Int,
    menuFor: (subjectId: Int) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
    modifier: Modifier,
) {
    TvExplorationComposeWall(
        state, metrics, cardWidth, items, carouselCount, carouselIndex, fadeColor, geometry, splitGate, listener,
        cardFocusRequest, heroFocusRequest, farJumpRow, focusedRowKey, focusedCardIndex, menuFor, modifier,
    )
}
