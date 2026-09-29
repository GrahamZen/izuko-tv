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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
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
 * 人物 / 角色预览弹窗与整页 (中栏) 的原生横滑行 (见 [PeoplePreviewRows]): 圆头像行同详情页的角色行 ([TvNativeMonogramStrip]), 海报行同
 * 关联条目 ([TvNativePosterStrip]), 格子按弹窗的宽度缩小 (见 [TV_PEOPLE_PREVIEW_CELL]). 行往两侧出血 contentPadding (弹窗: 到弹窗边上;
 * 整页: 一个栏距), 往左不超过格间距 (见 [startBleed]); 滑过行首的格从出血的边上出屏, 不在内容留白线上被硬裁, 也不画进整页的侧栏
 * (见 [horizontalBleed]). 左右两头吞掉, 上下键交给弹窗 / 整页. 没有放大看图, 按住确定键只算点击.
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
            modifier = modifier.horizontalBleed(start = startBleed(contentPadding), end = contentPadding),
            startPadding = startBleed(contentPadding),
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
            modifier = modifier.horizontalBleed(start = startBleed(contentPadding), end = contentPadding),
            startPadding = startBleed(contentPadding),
            endPadding = contentPadding,
            cardWidth = TV_PEOPLE_PREVIEW_CELL,
            onBind = onBind,
        )
    }
}

/**
 * 行往左出血多少: 调用方给的留白, 但不超过格间距. 停稳时行首左边那格的右缘离行首停靠线一个格间距, 正好落在出血的边上, 整格裁掉;
 * 出血比格间距宽的话它在边上露一窄条 (整页的栏距 24dp 比格间距宽 4dp, 那一条贴在左栏的图边上).
 */
private fun startBleed(contentPadding: Dp): Dp = contentPadding.coerceAtMost(TV_PEOPLE_PREVIEW_CELL_SPACING)

/**
 * 往左右两侧各多占 [start] / [end] (父布局给的宽度加上这两份), 布局上仍只占父布局给的宽度; 横向按多占之后的宽度裁. 原生行两头排着
 * 伸出行外的格 (滑过行首的、行尾露一截的、行外多排的那格), 装原生行的那层不裁子视图, 不裁的话整页里它们会一直画进侧栏. 竖向不裁:
 * 行上下各多出一截画聚焦放大与投影, 不占布局.
 */
private fun Modifier.horizontalBleed(start: Dp, end: Dp): Modifier = layout { measurable, constraints ->
    val startPx = start.roundToPx()
    val endPx = end.roundToPx()
    if (startPx + endPx == 0 || !constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth + startPx + endPx
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-startPx, 0) }
}.drawWithContent {
    clipRect(top = -size.height, bottom = size.height * 2) { this@drawWithContent.drawContent() }
}

/**
 * 预览弹窗里一格的宽 (圆的直径 / 海报卡宽) 与格间距. 弹窗 560dp 宽, 行从 16dp 的留白线排起: 16 + 4 × (106 + 20) = 520,
 * 一屏完整放得下 4 格, 第 5 格露出 40dp (后面还有). 圆头像行与海报行同一格宽同一间距 (海报行的间距是海报墙的),
 * 上下两行的列对得齐. 详情页的圆是 130dp, 弹窗窄, 放 130 只装得下三格半.
 */
internal val TV_PEOPLE_PREVIEW_CELL = 106.dp
internal val TV_PEOPLE_PREVIEW_CELL_SPACING = TV_POSTER_WALL_COLUMN_SPACING
