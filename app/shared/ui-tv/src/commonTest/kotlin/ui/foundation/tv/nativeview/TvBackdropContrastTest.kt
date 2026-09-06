/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 整屏模糊背景的压暗深浅 ([tvBackdropMaskAlpha] / [tvBackdropWorstLuminance]): 暗图照旧压起步那一份, 亮图压到番名与最难看清处的对比度够
 * [TV_WALL_BACKDROP_TEXT_CONTRAST], 一小块高光不算; 浅色主题 (深色字) 反过来提亮.
 */
class TvBackdropContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val darkPage = 0xFF2C2C2E.toInt()
    private val lightPage = 0xFFE5E5EA.toInt()
    private val base = 0.46f

    @Test
    fun `a dark image keeps the base mask`() {
        val pixels = IntArray(100) { gray(40) }
        val worst = tvBackdropWorstLuminance(pixels, lightText = true)
        assertEquals(base, alphaFor(worst, darkPage, white))
    }

    @Test
    fun `a bright image is masked until white text has enough contrast`() {
        val pixels = IntArray(100) { gray(235) }
        val worst = tvBackdropWorstLuminance(pixels, lightText = true)
        val alpha = alphaFor(worst, darkPage, white)
        assertTrue(alpha > base && alpha <= TV_WALL_BACKDROP_MASK_ALPHA_MAX, "alpha=$alpha")
        val masked = blend(235, darkPage, alpha)
        assertTrue(contrast(1f, masked) >= TV_WALL_BACKDROP_TEXT_CONTRAST - 0.05f, "contrast=${contrast(1f, masked)}")
    }

    @Test
    fun `a small highlight does not darken the whole image`() {
        // 九成五是暗的, 只有一小块白: 最亮的一成里最暗的那点还是暗的
        val pixels = IntArray(100) { if (it < 5) white else gray(40) }
        assertEquals(base, alphaFor(tvBackdropWorstLuminance(pixels, lightText = true), darkPage, white))
    }

    @Test
    fun `the mask never goes past the maximum`() {
        // 压暗色本身不够暗 (中灰): 怎么压都够不到, 停在上限
        val alpha = tvBackdropMaskAlpha(1f, maskLuminance = tvRelativeLuminance(gray(128)), textLuminance = 1f, base = base)
        assertEquals(TV_WALL_BACKDROP_MASK_ALPHA_MAX, alpha)
    }

    @Test
    fun `dark text on a light page lightens a dark image`() {
        val text = gray(28)
        val pixels = IntArray(100) { gray(10) }
        val worst = tvBackdropWorstLuminance(pixels, lightText = false)
        val alpha = alphaFor(worst, lightPage, text)
        assertTrue(alpha > base, "alpha=$alpha")
        val masked = blend(10, lightPage, alpha)
        assertTrue(contrast(tvRelativeLuminance(text), masked) >= TV_WALL_BACKDROP_TEXT_CONTRAST - 0.05f)
    }

    @Test
    fun `dark text on a light page keeps the base mask over a bright image`() {
        val pixels = IntArray(100) { gray(220) }
        assertEquals(base, alphaFor(tvBackdropWorstLuminance(pixels, lightText = false), lightPage, black))
    }

    @Test
    fun `two-level dimming keeps the base mask on an ordinary image`() {
        // 中等亮度的彩色图 (天蓝): 主色感知亮度不到很亮那一档, 只压平时那一份
        val pixels = IntArray(100) { rgb(150, 200, 240) }
        val luminosity = tvBackdropLuminosity(pixels)
        assertTrue(luminosity < TV_WALL_BACKDROP_BRIGHT_LUMINOSITY, "luminosity=$luminosity")
        assertEquals(TV_HERO_BLUR_DIM_ALPHA, tvBackdropTwoLevelMaskAlpha(luminosity, TV_HERO_BLUR_DIM_ALPHA, TV_HERO_BLUR_BRIGHT_DIM_ALPHA))
    }

    @Test
    fun `two-level dimming masks a near-white image deeper`() {
        val pixels = IntArray(100) { if (it < 80) gray(245) else gray(200) }
        val luminosity = tvBackdropLuminosity(pixels)
        assertTrue(luminosity >= TV_WALL_BACKDROP_BRIGHT_LUMINOSITY, "luminosity=$luminosity")
        assertEquals(TV_HERO_BLUR_BRIGHT_DIM_ALPHA, tvBackdropTwoLevelMaskAlpha(luminosity, TV_HERO_BLUR_DIM_ALPHA, TV_HERO_BLUR_BRIGHT_DIM_ALPHA))
    }

    @Test
    fun `luminosity of the mean color follows the HSP formula`() {
        // 一半纯红一半纯黑: 平均色 (127.5, 0, 0) / 255, HSP = √0.299 × 0.5
        val pixels = IntArray(100) { if (it % 2 == 0) rgb(255, 0, 0) else black }
        assertEquals(0.2734f, tvBackdropLuminosity(pixels), 0.001f)
        assertEquals(0f, tvBackdropLuminosity(IntArray(0)))
    }

    private fun alphaFor(worst: Float, mask: Int, text: Int): Float =
        tvBackdropMaskAlpha(worst, tvRelativeLuminance(mask), tvRelativeLuminance(text), base)

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** 灰 [v] 上按 [alpha] 压 [mask] (sRGB 分量上混, 同 SRC_ATOP) 之后的相对亮度. */
    private fun blend(v: Int, mask: Int, alpha: Float): Float {
        val m = (mask shr 8) and 0xFF
        val mixed = (v * (1 - alpha) + m * alpha).roundToInt().coerceIn(0, 255)
        return tvRelativeLuminance(gray(mixed))
    }

    private fun contrast(a: Float, b: Float): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}
