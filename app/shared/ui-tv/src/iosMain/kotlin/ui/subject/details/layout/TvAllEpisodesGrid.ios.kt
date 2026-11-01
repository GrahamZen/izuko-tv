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
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem

/** iOS 上画 Compose 版 (见 TvAllEpisodesComposeGrid). */
@Composable
internal actual fun TvAllEpisodesGrid(
    episodes: List<EpisodeListItem>,
    numbers: Boolean,
    selectedIndex: Int,
    currentEpisodeId: Int?,
    stills: Map<Int, String>,
    names: Map<Int, String>,
    playProgress: Map<Int, Float>,
    touch: Boolean,
    onFocused: (index: Int) -> Unit,
    onClick: (index: Int) -> Unit,
    onLongPress: (index: Int) -> Unit,
    modifier: Modifier,
) {
    TvAllEpisodesComposeGrid(
        episodes, numbers, selectedIndex, currentEpisodeId, stills, names, playProgress, touch, onFocused, onClick, onLongPress, modifier,
    )
}
