/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 左上角那块遮罩的蒙版图 ([tvBackdropPatchImage], 详情页浅色主题托住大标题): 从左上角长出来 —— 左缘与上缘满, 往右 / 往下变浅,
 * 标题右端 / 副标题下沿 (中点) 约八成, 再往外一个羽化宽度淡到 0; 横竖相乘, 右下角比同样远的边上更淡. 采样间距 = 羽化宽 / 32.
 */
class TvBackdropPatchImageTest {
    private val right = 400
    private val bottom = 200
    private val feather = 144
    private val step = feather / 32f
    private val bitmap = tvBackdropPatchImage(right, bottom, feather).asAndroidBitmap()

    /** 本层像素坐标处的不透明度 0..255. */
    private fun alphaAt(x: Int, y: Int): Int =
        Color.alpha(bitmap.getPixel((x / step).toInt().coerceIn(0, bitmap.width - 1), (y / step).toInt().coerceIn(0, bitmap.height - 1)))

    @Test
    fun `the mask reaches one feather past the middle and its resolution follows the feather`() {
        assertEquals(ceil((right + feather) / step).toInt(), bitmap.width)
        assertEquals(ceil((bottom + feather) / step).toInt(), bitmap.height)
        // 同样的形状换成 4K 界面 (像素都翻倍): 蒙版图一样大
        val big = tvBackdropPatchImage(right * 2, bottom * 2, feather * 2).asAndroidBitmap()
        assertEquals(bitmap.width, big.width)
        assertEquals(bitmap.height, big.height)
    }

    @Test
    fun `full along the left and top edges`() {
        assertEquals(255, alphaAt(0, 0))
        assertEquals(255, alphaAt(right - feather - 8, 0))
        assertEquals(255, alphaAt(0, bottom - feather - 8))
        assertEquals(255, alphaAt(right - feather - 8, bottom - feather - 8))
    }

    @Test
    fun `lighter towards the right and bottom and gone one feather past the middle`() {
        var last = 255
        var x = 0
        while (x < right + feather) {
            val a = alphaAt(x, 0)
            assertTrue(a <= last, "往右应越来越淡: x=$x 处 $a > $last")
            last = a
            x += 4
        }
        assertEquals(0, alphaAt(right + feather - 2, 0))
        assertEquals(0, alphaAt(0, bottom + feather - 2))
        // 中点 (标题右端) 约八成: 下缘那条曲线的一半处
        assertTrue(abs(alphaAt(right, 0) - 209) <= 12, "中点应约八成浓, 实际 ${alphaAt(right, 0)}")
    }

    @Test
    fun `the corner is lighter than the edges because the two directions multiply`() {
        val onEdge = alphaAt(right, 0)
        val corner = alphaAt(right, bottom)
        assertTrue(corner < onEdge, "右下角 ($corner) 应比边上 ($onEdge) 淡")
        assertTrue(abs(corner - onEdge * onEdge / 255) <= 12, "右下角应约为两边相乘, 实际 $corner")
    }
}
