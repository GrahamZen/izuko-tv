/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad.foundation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 浮在海报墙上的玻璃标签栏 (追番页的分类、新番时间表的日期), 照电视版的 TvCollectionGlassTabBar / TvCollectionGlassTab
 * (tvOS 顶部标签栏): 整条是半透明中性色胶囊加一圈细亮边, 卡片从底下透出来; 没选中的字降到七成, 选中的垫一块半透明浅灰片.
 * 电视上还有聚焦态 (浅色实底黑字、抬起), 触屏没有焦点, 只有选中态; 标签上下留得比电视宽, 手指好点.
 */

/** 顶栏玻璃配色, 取值同电视版的 TvGlassColors. */
@Immutable
class PadGlassColors(
    val fill: Color,
    val edge: Color,
    val idleContent: Color,
    val selectedPlatter: Color,
    val selectedContent: Color,
)

private val PAD_GLASS_COLORS_DARK = PadGlassColors(
    fill = Color(0xFF1E1E1E).copy(alpha = 0.5f),
    edge = Color.White.copy(alpha = 0.1f),
    idleContent = Color.White.copy(alpha = 0.7f),
    selectedPlatter = Color(0xFFD0D1D3).copy(alpha = 0.5f),
    selectedContent = Color.White,
)

private val PAD_GLASS_COLORS_LIGHT = PadGlassColors(
    fill = Color(0xFF828282).copy(alpha = 0.5f),
    edge = Color.White.copy(alpha = 0.2f),
    idleContent = Color.Black.copy(alpha = 0.7f),
    selectedPlatter = Color.Black.copy(alpha = 0.37f),
    selectedContent = Color.White,
)

private val PAD_GLASS_EDGE_WIDTH = 1.dp
private val PAD_GLASS_TAB_BAR_PADDING = 4.dp
private val PAD_GLASS_TAB_GAP = 2.dp
private val PAD_GLASS_TAB_PADDING_HORIZONTAL = 16.dp
private val PAD_GLASS_TAB_PADDING_VERTICAL = 9.dp
private const val PAD_GLASS_SELECT_MILLIS = 150

@Composable
fun padGlassColors(): PadGlassColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) PAD_GLASS_COLORS_DARK else PAD_GLASS_COLORS_LIGHT

/** 一条玻璃胶囊, 里面横排标签; 放不下时在胶囊里横滑. */
@Composable
fun PadGlassTabBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = padGlassColors()
    Row(
        modifier
            .background(colors.fill, CircleShape)
            .border(PAD_GLASS_EDGE_WIDTH, colors.edge, CircleShape)
            .padding(PAD_GLASS_TAB_BAR_PADDING)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PAD_GLASS_TAB_GAP),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 玻璃栏里的一个标签. [detail] (条数、日期) 以小号淡色跟在后面. 点一下就选中. */
@Composable
fun PadGlassTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val colors = padGlassColors()
    val selectLayer by animateFloatAsState(if (selected) 1f else 0f, tween(PAD_GLASS_SELECT_MILLIS), label = "padTabPlatter")
    val labelColor by animateColorAsState(
        if (selected) colors.selectedContent else colors.idleContent,
        tween(PAD_GLASS_SELECT_MILLIS),
        label = "padTabLabel",
    )
    Row(
        modifier
            .drawBehind {
                // 选中片只动透明度: 按颜色插值的话「没有片」得写成 Color.Transparent (alpha 0 的黑), 浅色主题下淡入途中会先黑一下
                if (selectLayer > 0f) {
                    drawOutline(CircleShape.createOutline(size, layoutDirection, this), colors.selectedPlatter, alpha = selectLayer)
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = PAD_GLASS_TAB_PADDING_HORIZONTAL, vertical = PAD_GLASS_TAB_PADDING_VERTICAL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = labelColor,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            softWrap = false,
        )
        if (detail != null) {
            Text(
                detail,
                color = labelColor.copy(alpha = labelColor.alpha * 0.7f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}
