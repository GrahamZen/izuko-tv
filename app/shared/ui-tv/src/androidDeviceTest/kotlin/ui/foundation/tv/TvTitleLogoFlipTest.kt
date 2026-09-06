/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 标题 logo 翻色 ([flipTvTitleLogo]): 只翻黑白灰的部分 (浅色字时黑翻成白、深灰翻成浅灰, 本来就浅的不动; 深色字反过来), 彩色原样, 透明度不变;
 * 要不要翻看 logo 的色调 ([TvTitleLogoTone.needsFlip]).
 */
class TvTitleLogoFlipTest {
    private fun row(vararg colors: Int): Bitmap = Bitmap.createBitmap(colors, colors.size, 1, Bitmap.Config.ARGB_8888)

    private fun Bitmap.pixels(): List<Int> = (0 until width).map { getPixel(it, 0) }

    @Test
    fun `on light text only the black and gray parts turn light`() {
        val flipped = flipTvTitleLogo(
            row(Color.BLACK, Color.rgb(60, 60, 60), Color.RED, Color.rgb(220, 220, 220), Color.argb(128, 0, 0, 0), Color.rgb(140, 20, 20)),
            lightText = true,
        ).pixels()
        assertEquals(Color.WHITE, flipped[0], "黑翻成白")
        assertEquals(Color.rgb(195, 195, 195), flipped[1], "深灰翻成浅灰")
        assertEquals(Color.RED, flipped[2], "彩色原样")
        assertEquals(Color.rgb(220, 220, 220), flipped[3], "本来就浅的不动")
        assertEquals(Color.argb(128, 255, 255, 255), flipped[4], "透明度不变")
        assertEquals(Color.rgb(140, 20, 20), flipped[5], "深红也是彩色, 原样")
    }

    @Test
    fun `on dark text only the white and light gray parts turn dark`() {
        val flipped = flipTvTitleLogo(row(Color.WHITE, Color.rgb(200, 200, 200), Color.BLUE, Color.rgb(30, 30, 30)), lightText = false).pixels()
        assertEquals(Color.BLACK, flipped[0], "白翻成黑")
        assertEquals(Color.rgb(55, 55, 55), flipped[1], "浅灰翻成深灰")
        assertEquals(Color.BLUE, flipped[2], "彩色原样")
        assertEquals(Color.rgb(30, 30, 30), flipped[3], "本来就深的不动")
    }

    @Test
    fun `only logos that are mostly the same tone as the background get flipped`() {
        val blackText = row(*IntArray(9) { Color.BLACK }, Color.RED)
        assertTrue(tvTitleLogoTone(blackText).needsFlip(lightText = true), "主体纯黑, 压在深色底上要翻")
        assertFalse(tvTitleLogoTone(blackText).needsFlip(lightText = false), "压在浅色底上本来就看得清")
        val blackWithWhiteOutline = row(*IntArray(6) { Color.BLACK }, *IntArray(4) { Color.WHITE })
        assertFalse(tvTitleLogoTone(blackWithWhiteOutline).needsFlip(lightText = true), "自己带白色描边的不翻")
    }
}
