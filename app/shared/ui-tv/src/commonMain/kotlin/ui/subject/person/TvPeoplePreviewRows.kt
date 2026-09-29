/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.person

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogram
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogramStrip
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativePosterStrip
import me.him188.ani.app.ui.subject.details.layout.rememberTvMonogramStyle
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_MOVE_RATE
import me.him188.ani.app.ui.subject.details.sections.monogramInitials

/**
 * 人物 / 角色预览弹窗的原生横滑行 (见 [PeoplePreviewRows]): 圆头像行同详情页的角色行 ([TvNativeMonogramStrip]), 海报行同关联条目
 * ([TvNativePosterStrip]), 格子按弹窗的宽度缩小 (见 [TV_PEOPLE_PREVIEW_CELL]). 行往两侧出血到弹窗边上 (滑过行首的格从弹窗边出屏,
 * 不在内容留白线上被硬裁), 左右两头吞掉, 上下键交给弹窗. 弹窗里没有放大看图, 按住确定键只算点击.
 */
object TvPeoplePreviewRows : PeoplePreviewRows {
    @Composable
    override fun PeopleRow(
        items: List<PeopleRowItem?>,
        onClick: (index: Int) -> Unit,
        onBind: (index: Int) -> Unit,
        contentPadding: Dp,
        modifier: Modifier,
    ) {
        val monograms = remember(items) {
            items.map { item ->
                item?.let { TvNativeMonogram.Person(it.imageUrl, it.name, it.subtitle, monogramInitials(it.name)) }
            }
        }
        TvNativeMonogramStrip(
            items = monograms,
            style = rememberTvMonogramStyle(TV_PEOPLE_PREVIEW_CELL, TV_PEOPLE_PREVIEW_CELL_SPACING, viewAllLabel = ""),
            onClick = onClick,
            onLongPress = null,
            repeatMillis = 1000L / TV_MONOGRAM_MOVE_RATE,
            modifier = modifier.horizontalBleed(contentPadding),
            startPadding = contentPadding,
            endPadding = contentPadding,
            onBind = onBind,
        )
    }

    @Composable
    override fun PosterRow(
        items: List<PosterRowItem?>,
        onClick: (index: Int) -> Unit,
        onBind: (index: Int) -> Unit,
        contentPadding: Dp,
        modifier: Modifier,
    ) {
        val cards = remember(items) {
            items.map { item -> item?.let { TvNativeCard(imageUrl = it.imageUrl, title = it.title, subtitle = it.subtitle) } }
        }
        TvNativePosterStrip(
            cards = cards,
            onClick = onClick,
            modifier = modifier.horizontalBleed(contentPadding),
            startPadding = contentPadding,
            endPadding = contentPadding,
            cardWidth = TV_PEOPLE_PREVIEW_CELL,
            onBind = onBind,
        )
    }
}

/** 往左右两侧各多占 [bleed] (父布局给的宽度加两份), 布局上仍只占父布局给的宽度. */
private fun Modifier.horizontalBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val px = bleed.roundToPx()
    if (px == 0 || !constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth + px * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}

/**
 * 预览弹窗里一格的宽 (圆的直径 / 海报卡宽) 与格间距. 弹窗 560dp 宽, 行从 16dp 的留白线排起: 16 + 4 × (106 + 20) = 520,
 * 一屏完整放得下 4 格, 第 5 格露出 40dp (后面还有). 圆头像行与海报行同一格宽同一间距 (海报行的间距是海报墙的),
 * 上下两行的列对得齐. 详情页的圆是 130dp, 弹窗窄, 放 130 只装得下三格半.
 */
internal val TV_PEOPLE_PREVIEW_CELL = 106.dp
internal val TV_PEOPLE_PREVIEW_CELL_SPACING = TV_POSTER_WALL_COLUMN_SPACING
