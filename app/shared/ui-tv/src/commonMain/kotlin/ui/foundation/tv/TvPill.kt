/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * **播放器胶囊按钮的外壳** (Prime Video 样式): 圆头、未聚焦白 14%、聚焦反色成白底黑字.
 *
 * 播放器控制层那一行按钮 (选集/一起看/弹幕/评论/相关推荐)、OP/ED 跳过按钮、弹幕输入胶囊
 * 原本各自写了一遍同一个 `Surface` + `Row` —— 三份形状、两档底色、内边距、图文间距全都逐字重复.
 * 改一次配色要改三处, 而它们必须看着是同一行里的同类按钮.
 *
 * 内容 (图标 + 文字, 或输入框) 由调用方给, 外壳只管形状、两档配色与内边距.
 *
 * @param highlighted 是否反色. 一般就是"聚焦时" —— 但弹幕输入胶囊展开成输入框后即使聚焦
 *   也要保持深色 (白底上没法放输入光标), 所以这里收的是布尔而不是自己去读焦点.
 * @param interactionSource 必须与调用方读聚焦态用的是同一个, 否则 [highlighted] 与实际焦点对不上.
 */
@Composable
internal fun TvPillShell(
    highlighted: Boolean,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (highlighted) Color.White else Color.White.copy(alpha = TV_PILL_IDLE_ALPHA),
        contentColor = if (highlighted) Color.Black else Color.White,
        interactionSource = interactionSource,
    ) {
        Row(
            Modifier.padding(horizontal = TV_PILL_PADDING_H, vertical = TV_PILL_PADDING_V),
            horizontalArrangement = Arrangement.spacedBy(TV_PILL_CONTENT_SPACING),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** 未聚焦时的底色不透明度 (白): 压得住画面又不抢戏. */
private const val TV_PILL_IDLE_ALPHA = 0.14f

/** 胶囊里图标的尺寸. */
internal val TV_PILL_ICON_SIZE = 14.dp

/** 胶囊内边距 (横/纵). */
internal val TV_PILL_PADDING_H = 14.dp
internal val TV_PILL_PADDING_V = 8.dp

/** 图标与文字之间的间距. */
private val TV_PILL_CONTENT_SPACING = 6.dp
