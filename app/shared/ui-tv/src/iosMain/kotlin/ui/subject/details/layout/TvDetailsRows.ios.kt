/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogram
import me.him188.ani.app.ui.foundation.tv.wall.TvWallPeopleStrip
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeRowSpec

/** iOS 上用选集轮播自带的 Compose 行. */
internal actual val tvEpisodeRowContent: (@Composable (FocusEpisodeRowSpec) -> Unit)? = null

/** iOS 上画 Compose 版 (见 TvWallPeopleStrip). */
@Composable
internal actual fun TvDetailsPeopleStrip(
    items: List<TvNativeMonogram?>?,
    onClick: (index: Int) -> Unit,
    onLongPress: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    horizontalPadding: Dp,
    restoreFocus: Boolean,
    modifier: Modifier,
) {
    TvWallPeopleStrip(items, onClick, onLongPress, onBind, horizontalPadding, modifier)
}
