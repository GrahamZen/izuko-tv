/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem

/**
 * 全部选集弹窗 ([TvAllEpisodesDialog]) 的选集网格: 剧照卡, 或一格一个集号 ([numbers]). 安卓上是原生网格 (TvNativeEpisodeGrid, 长番按住连翻也顺),
 * iOS 上画 Compose 版 ([TvAllEpisodesComposeGrid]).
 *
 * @param selectedIndex 焦点在网格外时选中、滚进来的那一格; 焦点送进网格时落在它上面.
 * @param touch 触屏: 格子不可聚焦, 点即播放, 长按开详情.
 * @param modifier 挂在网格外层 (弹窗里 Column 的权重、送焦的锚点、返回键).
 */
@Composable
internal expect fun TvAllEpisodesGrid(
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
)

/**
 * [TvAllEpisodesGrid] 的 Compose 版 (iOS 用): 剧照卡同平板详情页的选集行 ([TvPadEpisodeCard]), 集号格同播放页选集面板 ([TvPadEpisodeNumberCell]);
 * 列数按宽排, 最小格宽与间距同原生版 (TvNativeEpisodeGrid, 两边改一处要同步). 换了样子或分段就滚到 [selectedIndex] 那一格 (它上面露一行),
 * 滚不完的那边渐隐.
 */
@Composable
internal fun TvAllEpisodesComposeGrid(
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
    // 换了样子 / 分段就是另一份网格: 滚动位置重来
    val gridState = remember(numbers, episodes) { LazyGridState() }
    LaunchedEffect(gridState) {
        if (selectedIndex <= 0) return@LaunchedEffect
        val span = snapshotFlow { gridState.layoutInfo.maxSpan }.first { it > 0 }
        gridState.scrollToItem(((selectedIndex / span) - 1).coerceAtLeast(0) * span)
    }
    val gap = if (numbers) TV_ALL_EPISODES_COMPOSE_NUMBER_GAP else TV_ALL_EPISODES_COMPOSE_STILL_GAP
    LazyVerticalGrid(
        GridCells.Adaptive(if (numbers) TV_PAD_EPISODE_NUMBER_MIN_WIDTH else TV_ALL_EPISODES_COMPOSE_STILL_MIN_WIDTH),
        modifier.aniVerticalScrollFade(gridState),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        itemsIndexed(episodes, key = { _, item -> item.episodeId }) { index, item ->
            // 触屏上格子不可聚焦 (只有点); 遥控器上报聚焦, 外层按下时回到这一格
            val cell = Modifier.focusProperties { canFocus = !touch }
                .onFocusChanged { if (it.isFocused) onFocused(index) }
            val isCurrent = item.episodeId == currentEpisodeId
            if (numbers) {
                TvPadEpisodeNumberCell(
                    item,
                    isCurrent = isCurrent,
                    progress = playProgress[item.episodeId],
                    onClick = { onClick(index) },
                    onLongClick = { onLongPress(index) },
                    modifier = cell.fillMaxWidth(),
                )
            } else {
                TvPadEpisodeCard(
                    item,
                    stillUrl = stills[item.episodeId],
                    isCurrent = isCurrent,
                    progress = playProgress[item.episodeId],
                    names = names,
                    onClick = { onClick(index) },
                    onLongClick = { onLongPress(index) },
                    modifier = cell.fillMaxWidth().aspectRatio(TV_ALL_EPISODES_COMPOSE_STILL_RATIO),
                )
            }
        }
    }
}

/** 剧照卡的宽高比 (TMDB 剧照 16:9). */
private const val TV_ALL_EPISODES_COMPOSE_STILL_RATIO = 16f / 9f

/** 剧照卡最窄多宽、卡距 (同原生版). */
private val TV_ALL_EPISODES_COMPOSE_STILL_MIN_WIDTH = 180.dp
private val TV_ALL_EPISODES_COMPOSE_STILL_GAP = 16.dp

/** 集号格的间距 (同原生版). */
private val TV_ALL_EPISODES_COMPOSE_NUMBER_GAP = 10.dp
