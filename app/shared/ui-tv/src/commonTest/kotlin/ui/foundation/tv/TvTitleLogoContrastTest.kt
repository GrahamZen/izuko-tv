/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模糊背景上的标题 logo 什么时候要处理 ([tvTitleLogoBackdropLook]): 只有字与背后都暗 (或都亮且颜色差不多)、APCA 对比度又近 0 时才动;
 * 黑白灰的 logo 翻色, 有颜色的加柔光. 整体看得清、单独一块看不清的只翻色.
 */
class TvTitleLogoContrastTest {
    /** 一张 40 × 10 格的 logo: 中间 6 行、左右各空 4 列是字 (颜色 [text]), 其余透明. */
    private fun logo(text: Int, tone: TvTitleLogoTone = TvTitleLogoTone(0f, 0f)): TvTitleLogoGrid {
        val cols = 40
        val rows = 10
        val coverage = FloatArray(cols * rows) { i ->
            val x = i % cols
            val y = i / cols
            if (x in 4 until cols - 4 && y in 2 until rows - 2) 1f else 0f
        }
        return TvTitleLogoGrid(cols, rows, coverage, IntArray(cols * rows) { text }, tone)
    }

    private fun background(logo: TvTitleLogoGrid, color: Int) = IntArray(logo.cols * logo.rows) { color }

    private val black = TvTitleLogoTone(nearBlack = 1f, nearWhite = 0f)
    private val white = TvTitleLogoTone(nearBlack = 0f, nearWhite = 1f)

    @Test
    fun `white logo on dark backdrop stays original`() {
        val l = logo(argb(240, 240, 240), white)
        assertEquals(TvTitleLogoBackdropLook.Original, tvTitleLogoBackdropLook(l, background(l, argb(24, 24, 24))))
    }

    @Test
    fun `black logo on bright backdrop stays original`() {
        val l = logo(argb(16, 16, 16), black)
        assertEquals(TvTitleLogoBackdropLook.Original, tvTitleLogoBackdropLook(l, background(l, argb(176, 176, 176))))
    }

    @Test
    fun `black logo on dark backdrop flips to light`() {
        val l = logo(argb(16, 16, 16), black)
        val look = tvTitleLogoBackdropLook(l, background(l, argb(24, 24, 24)))
        assertEquals(true, look.flipLightText)
        assertNull(look.glow)
    }

    @Test
    fun `white logo on light backdrop flips to dark`() {
        val l = logo(argb(232, 232, 232), white)
        val look = tvTitleLogoBackdropLook(l, background(l, argb(224, 224, 224)))
        assertEquals(false, look.flipLightText)
        assertNull(look.glow)
    }

    @Test
    fun `dark blue logo on near black backdrop gets a light glow`() {
        val l = logo(argb(26, 42, 90))
        val bg = background(l, argb(20, 24, 34))
        val look = tvTitleLogoBackdropLook(l, bg)
        assertNull(look.flipLightText)
        val glow = assertNotNull(look.glow)
        assertTrue(glow.lighten)
        val peak = glow.alpha.max()
        assertTrue(peak > 0f && peak <= 0.6f, "peak $peak")
        // 最浓处压上去就看得清了
        assertTrue(apcaContrast(argb(26, 42, 90), mixArgb(bg[0], argb(255, 255, 255), peak)) >= TV_TITLE_LOGO_LC_TARGET)
        // 最外圈淡到 0, 位图边上没有硬边
        assertEquals(0f, glow.alpha[0])
        assertEquals(0f, glow.alpha[glow.alpha.size - 1])
    }

    @Test
    fun `colored dark logo with some contrast stays original`() {
        // 深蓝字压在更暗的底上, |Lc| ≈ 12: 看得清
        val l = logo(argb(60, 60, 200))
        assertEquals(TvTitleLogoBackdropLook.Original, tvTitleLogoBackdropLook(l, background(l, argb(40, 40, 60))))
    }

    @Test
    fun `bright logo on equally bright but different color stays original`() {
        // 亮度一样 (|Lc| = 0), 颜色差得远: 亮的区间里颜色认得出
        val l = logo(argb(255, 143, 176))
        assertEquals(TvTitleLogoBackdropLook.Original, tvTitleLogoBackdropLook(l, background(l, argb(120, 200, 200))))
    }

    @Test
    fun `bright logo on same bright color gets a glow`() {
        val l = logo(argb(250, 236, 120))
        val look = tvTitleLogoBackdropLook(l, background(l, argb(240, 226, 140)))
        assertNull(look.flipLightText)
        assertNotNull(look.glow)
    }

    @Test
    fun `a colored logo with black lettering on a dark backdrop flips the black parts instead of glowing`() {
        // 左半黑字、右半深红字 (色调不够黑, 整体不算黑白 logo): 黑的部分翻白就认得出, 不加柔光
        val base = logo(argb(16, 16, 16))
        val colors = IntArray(base.cols * base.rows) { if (it % base.cols < base.cols / 2) argb(16, 16, 16) else argb(90, 10, 10) }
        val l = TvTitleLogoGrid(base.cols, base.rows, base.coverage, colors, TvTitleLogoTone(nearBlack = 0.5f, nearWhite = 0f))
        val look = tvTitleLogoBackdropLook(l, background(l, argb(24, 24, 24)))
        assertEquals(true, look.flipLightText)
        assertNull(look.glow)
    }

    @Test
    fun `flipping a color turns only its gray part over`() {
        assertEquals(argb(239, 239, 239), tvTitleLogoFlipColor(argb(16, 16, 16), lightText = true))
        assertEquals(argb(200, 20, 20), tvTitleLogoFlipColor(argb(200, 20, 20), lightText = true))
        // 本来就浅的不动; 深色字时白翻成黑
        assertEquals(argb(230, 230, 230), tvTitleLogoFlipColor(argb(230, 230, 230), lightText = true))
        assertEquals(argb(25, 25, 25), tvTitleLogoFlipColor(argb(230, 230, 230), lightText = false))
    }

    @Test
    fun `a dark but saturated color is not mistaken for gray`() {
        // 深蓝 (青之驱魔师的笔画): 通道差小, 饱和度高, 原样
        assertEquals(argb(0, 0, 48), tvTitleLogoFlipColor(argb(0, 0, 48), lightText = true))
        // 暗到看不出颜色的蓝黑照黑色翻
        assertEquals(argb(255, 255, 247), tvTitleLogoFlipColor(argb(0, 0, 8), lightText = true))
    }

    @Test
    fun `a dark blue logo fading to black gets a glow instead of a two-tone flip`() {
        // 上半深蓝、下半黑 (渐变的两端各占一半时黑的那半就够认了; 这里黑的只占一成, 翻了也认不出, 改加柔光)
        val base = logo(argb(0, 0, 60))
        val colors = IntArray(base.cols * base.rows) { if (it / base.cols == base.rows - 3) argb(0, 0, 6) else argb(0, 0, 60) }
        val l = TvTitleLogoGrid(base.cols, base.rows, base.coverage, colors, TvTitleLogoTone(nearBlack = 0.3f, nearWhite = 0f))
        val look = tvTitleLogoBackdropLook(l, background(l, argb(14, 16, 30)))
        assertNull(look.flipLightText)
        assertNotNull(look.glow)
    }

    /** 20 × 10 格的 logo, 每格的颜色由 [colorAt] 给 (null = 透明). */
    private fun grid(colorAt: (x: Int, y: Int) -> Int?): TvTitleLogoGrid {
        val cols = 20
        val rows = 10
        val colors = IntArray(cols * rows) { colorAt(it % cols, it / cols) ?: argb(0, 0, 0) }
        val coverage = FloatArray(cols * rows) { if (colorAt(it % cols, it / cols) != null) 1f else 0f }
        return TvTitleLogoGrid(cols, rows, coverage, colors, TvTitleLogoTone(0f, 0f))
    }

    @Test
    fun `a separate block of black lettering lost in the dark flips although most of the logo reads`() {
        // 左边一大块亮红字看得清, 隔开四列的右边一列黑字压在深蓝底上看不见 (药屋少女的呢喃): 整体中位数看得清, 黑字那块照样翻白
        val l = grid { x, _ ->
            when {
                x < 10 -> argb(255, 64, 64)
                x >= 14 -> argb(0, 0, 0)
                else -> null
            }
        }
        val look = tvTitleLogoBackdropLook(l, background(l, argb(10, 20, 40)))
        assertEquals(true, look.flipLightText)
        assertNull(look.glow)
    }

    @Test
    fun `a dark outline hugging readable letters stays original`() {
        // 亮红字外面一圈黑描边: 描边紧挨着看得清的字, 看不清也不要紧
        val l = grid { x, y -> if (x in 1..18 && y in 1..8) argb(255, 64, 64) else argb(0, 0, 0) }
        assertEquals(TvTitleLogoBackdropLook.Original, tvTitleLogoBackdropLook(l, background(l, argb(10, 20, 40))))
    }

    @Test
    fun `lettering partly lost is not flipped when that would wash out the part that reads`() {
        // 深灰字: 左边一大块压在浅灰底上看得清, 右边一块压在暗处看不见. 翻成浅灰右边是清楚了, 左边却糊掉, 不翻
        val l = grid { x, _ -> if (x < 12 || x >= 15) argb(64, 64, 64) else null }
        val bg = IntArray(l.cols * l.rows) { if (it % l.cols < 13) argb(192, 192, 192) else argb(32, 32, 32) }
        assertEquals(TvTitleLogoBackdropLook.Original, tvTitleLogoBackdropLook(l, bg))
    }

    @Test
    fun `transparent logo stays original`() {
        val l = TvTitleLogoGrid(4, 2, FloatArray(8), IntArray(8) { argb(16, 16, 16) }, black)
        assertEquals(TvTitleLogoBackdropLook.Original, tvTitleLogoBackdropLook(l, IntArray(8) { argb(24, 24, 24) }))
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
