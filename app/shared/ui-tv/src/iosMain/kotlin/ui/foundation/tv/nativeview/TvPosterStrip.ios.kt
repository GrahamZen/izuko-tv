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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import me.him188.ani.app.ui.foundation.tv.wall.TvWallPosterStrip

/** iOS 上画 Compose 版 (见 TvWallPosterStrip). */
@Composable
actual fun TvNativePosterStrip(
    cards: List<TvNativeCard?>,
    onClick: (index: Int) -> Unit,
    modifier: Modifier,
    startPadding: Dp,
    endPadding: Dp,
    onFocused: (index: Int) -> Unit,
    cardWidth: Dp?,
    onBind: (index: Int) -> Unit,
    holdFocusLookOnClick: Boolean,
) {
    TvWallPosterStrip(cards, onClick, modifier, startPadding, endPadding, onFocused, cardWidth, onBind, holdFocusLookOnClick)
}
