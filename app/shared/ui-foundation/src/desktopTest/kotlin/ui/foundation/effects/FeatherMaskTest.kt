/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertTrue

class FeatherMaskTest {
    /** 一行 (右半边) 的绿色通道平均值, 0..255. */
    private fun PixelMap.green(y: Int): Float {
        var sum = 0f
        for (x in WIDTH / 2 until WIDTH) sum += this[x, y].green * 255f
        return sum / (WIDTH - WIDTH / 2)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `bottom edge at fractional offsets leaves no line of content`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(2f),
    ) {
        var offset by mutableFloatStateOf(0f)
        setContent {
            Box(Modifier.fillMaxSize().background(BACKGROUND)) {
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, BLOCK_HEIGHT))
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                        }
                        .graphicsLayer { translationY = offset }
                        // 羽化的是里面画的内容 (背景要在羽化之内)
                        .featherLeftAndBottom(leftFadeStart = 0.02f, leftFadeEnd = 0.35f, bottomFadeStart = 0.6f)
                        .background(CONTENT),
                )
            }
        }
        val background = BACKGROUND.green * 255f
        for (step in 1..9) {
            runOnIdle { offset = -SHIFT - step / 10f }
            waitForIdle()
            val pixels = captureToImage().toPixelMap()
            val bottom = BLOCK_HEIGHT + offset
            // 下缘所在的那一行只盖了一部分; 羽化越往下越透明, 这一行应当比上面一行更接近底色
            val edgeRow = floor(bottom).toInt()
            val edge = abs(pixels.green(edgeRow) - background)
            val above = abs(pixels.green(edgeRow - 1) - background)
            assertTrue(edge <= above + 1f, "offset=$offset: 下缘那一行离底色 $edge, 上面一行 $above")
        }
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
        const val BLOCK_HEIGHT = 200
        const val SHIFT = 37f
        val BACKGROUND = Color(0xFF808080)
        val CONTENT = Color(0xFF00FF00)
    }
}
