/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.NsfwPolicy

/** 网格的行距, 同电视海报墙. */
val PAD_POSTER_WALL_ROW_SPACING: Dp = 20.dp

/** 滚到底时末行离视口底边的留白 (另加导航栏). */
private val PAD_POSTER_GRID_BOTTOM_SPACE = 32.dp

/**
 * 条目海报卡: NSFW 设为模糊时封面打码; 长按弹收藏菜单 (锚在海报上). 宽度由 [modifier] 定 (行里按列宽, 网格里铺满格子).
 */
@Composable
fun PadSubjectCard(
    subjectId: Int,
    title: String,
    coverUrl: String,
    nsfw: NsfwPolicy,
    menu: PadCollectionMenu,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    PadPosterCard(
        title = title,
        coverUrl = coverUrl,
        onClick = onClick,
        modifier = modifier,
        subtitle = subtitle,
        coverDownsample = nsfw.coverDownsample(subjectId),
        progress = progress,
        onLongClick = { menuOpen = true },
        overlay = {
            if (menuOpen) {
                menu.Dropdown(subjectId, expanded = true, onDismissRequest = { menuOpen = false })
            }
        },
    )
}

/**
 * 海报墙网格 (追番 / 搜索 / 时间表): 列数与卡宽同 [PadPosterWallLayout], 列距行距同电视海报墙. [topPadding] 给浮在上面的顶栏
 * (玻璃标签栏、搜索框) 让位, 卡片滚上去从顶栏底下透过.
 */
@Composable
fun PadPosterGrid(
    count: Int,
    layout: PadPosterWallLayout,
    topPadding: Dp,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    item: @Composable (index: Int) -> Unit,
) {
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyVerticalGrid(
        columns = GridCells.Fixed(layout.columns),
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(
            start = layout.margin,
            end = layout.margin,
            top = topPadding,
            bottom = PAD_POSTER_GRID_BOTTOM_SPACE + navigationBar,
        ),
        horizontalArrangement = Arrangement.spacedBy(PAD_POSTER_WALL_COLUMN_SPACING),
        verticalArrangement = Arrangement.spacedBy(PAD_POSTER_WALL_ROW_SPACING),
    ) {
        items(count) { index ->
            Box(Modifier.fillMaxWidth()) { item(index) }
        }
    }
}

/** 列表空着时页面中间的一句提示. */
@Composable
fun PadEmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = PAD_POSTER_WALL_PAGE_MARGIN), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
