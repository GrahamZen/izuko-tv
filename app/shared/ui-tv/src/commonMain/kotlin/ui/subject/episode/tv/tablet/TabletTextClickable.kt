/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 文字行的点击 (视频下面「第 N 集 … 详情 ›」、「选集 … 全 12 话 ›」, 详情面板里点开看全文的简介):
 * 按下时不铺底色, 整行字变淡, 松手恢复 (同文字链接); 可点的范围不变.
 * 按钮与卡片 (收藏胶囊、带字的图标、评论卡片、集卡片、推荐) 照旧用涟漪: 它们本身有形状, 按下去一块变色是对的.
 */
internal fun Modifier.tabletTextClickable(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Modifier
        .graphicsLayer { alpha = if (pressed) TABLET_TEXT_PRESSED_ALPHA else 1f }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** 文字行按下时的不透明度. */
private const val TABLET_TEXT_PRESSED_ALPHA = 0.5f
