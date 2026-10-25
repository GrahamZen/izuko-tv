/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.effects

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 左缘与下缘羽化成透明, 露出底下的内容: 横向从 [leftFadeStart] (透明) 到 [leftFadeEnd] (不透明), 纵向从 [bottomFadeStart] (不透明)
 * 到下缘 (透明), 都是占这一块宽 / 高的比例. 遮罩画在离屏层里 (DstIn), 不离屏会把底下的内容一起擦掉.
 *
 * 遮罩比这一块四边各多盖 [maskOverhang] (渐变的起止仍按这一块的大小): 边落在小数坐标上时 (平移、滚动途中), 边上那一行内容与遮罩都只盖了一部分,
 * Skia (iOS / 桌面) 按覆盖比例混合, 那一行擦不干净, 露成一条线; 遮罩整块盖住那一行就擦干净了.
 */
fun Modifier.featherLeftAndBottom(
    leftFadeStart: Float,
    leftFadeEnd: Float,
    bottomFadeStart: Float,
    maskOverhang: Dp = 2.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val overhang = maskOverhang.toPx()
        val maskTopLeft = Offset(-overhang, -overhang)
        val maskSize = Size(size.width + overhang * 2, size.height + overhang * 2)
        drawRect(
            Brush.horizontalGradient(leftFadeStart to Color.Transparent, leftFadeEnd to Color.Black, startX = 0f, endX = size.width),
            topLeft = maskTopLeft,
            size = maskSize,
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.verticalGradient(bottomFadeStart to Color.Black, 1f to Color.Transparent, startY = 0f, endY = size.height),
            topLeft = maskTopLeft,
            size = maskSize,
            blendMode = BlendMode.DstIn,
        )
    }
