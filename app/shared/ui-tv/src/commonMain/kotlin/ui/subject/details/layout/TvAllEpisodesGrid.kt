/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.subject.details.sections.focusEpisodeCardColors
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
 * [TvAllEpisodesGrid] 的 Compose 版 (iOS 用): 剧照卡同平板详情页的选集行 ([TvPadEpisodeCard]), 集号格见 [TvEpisodeNumberComposeCell];
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
        GridCells.Adaptive(if (numbers) TV_ALL_EPISODES_COMPOSE_NUMBER_MIN_WIDTH else TV_ALL_EPISODES_COMPOSE_STILL_MIN_WIDTH),
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
                TvEpisodeNumberComposeCell(
                    item,
                    isCurrent = isCurrent,
                    progress = playProgress[item.episodeId],
                    onClick = { onClick(index) },
                    onLongClick = { onLongPress(index) },
                    modifier = cell,
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

/**
 * 只显示集号时的一格 (Compose 版, 同原生版 TvNativeEpisodeNumberView): 集号居中 (正在播放的前面画声浪图标), 底色与字色同剧照卡
 * (正在播放 / 看过 / 没看); 进度画成格子本身从左边起铺一层深色 (看了多少铺多宽, 看完的整格铺满), 不另画进度条 —— 密排时每格一条细线连成一片, 太吵.
 * 点了播放, 长按开本集详情.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvEpisodeNumberComposeCell(
    item: EpisodeListItem,
    isCurrent: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watched = item.isDoneOrDropped
    val colors = focusEpisodeCardColors(isPlaying = isCurrent, isWatched = watched, monochrome = false, glass = true)
    val shownProgress = (if (watched) 1f else progress ?: 0f).coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth()
            .height(TV_ALL_EPISODES_COMPOSE_NUMBER_HEIGHT)
            .clip(RoundedCornerShape(TV_ALL_EPISODES_COMPOSE_NUMBER_CORNER))
            .background(colors.container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (shownProgress > 0f) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .fillMaxWidth(shownProgress)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = TV_ALL_EPISODES_COMPOSE_NUMBER_SHADE_ALPHA)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isCurrent) Icon(Icons.Rounded.GraphicEq, contentDescription = null, Modifier.size(14.dp), tint = colors.sort)
            Text(item.sort.toString(), style = MaterialTheme.typography.titleSmall, color = colors.sort, maxLines = 1)
        }
    }
}

/** 剧照卡的宽高比 (TMDB 剧照 16:9). */
private const val TV_ALL_EPISODES_COMPOSE_STILL_RATIO = 16f / 9f

/** 剧照卡最窄多宽、卡距 (同原生版). */
private val TV_ALL_EPISODES_COMPOSE_STILL_MIN_WIDTH = 180.dp
private val TV_ALL_EPISODES_COMPOSE_STILL_GAP = 16.dp

/** 集号格: 最窄宽、高、圆角、间距 (同原生版). */
private val TV_ALL_EPISODES_COMPOSE_NUMBER_MIN_WIDTH = 72.dp
private val TV_ALL_EPISODES_COMPOSE_NUMBER_HEIGHT = 52.dp
private val TV_ALL_EPISODES_COMPOSE_NUMBER_CORNER = 12.dp
private val TV_ALL_EPISODES_COMPOSE_NUMBER_GAP = 10.dp

/** 集号格的进度那层黑的不透明度. */
private const val TV_ALL_EPISODES_COMPOSE_NUMBER_SHADE_ALPHA = 0.16f
