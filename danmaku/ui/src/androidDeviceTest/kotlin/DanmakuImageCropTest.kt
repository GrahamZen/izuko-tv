/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanmakuImageCropTest {
    /** 把图按偏移画到 [width]×[height] 的透明画布上 (同弹幕层与截图的画法). */
    private fun place(image: Bitmap, offset: Offset, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(image, 100 + offset.x, 100 + offset.y, null)
        }

    @Test
    fun `cropping keeps every stroke in place and leaves a transparent border of two pixels`() {
        val original = Bitmap.createBitmap(300, 120, Bitmap.Config.ARGB_8888)
        for (x in 40..200) original.setPixel(x, 50, Color.WHITE)
        original.setPixel(120, 3, Color.argb(10, 0, 0, 0)) // 伸到很上面的一点淡的 (叠起来的变音符号之类)
        val offset = Offset(-30f, -30f)
        val reference = place(original.copy(Bitmap.Config.ARGB_8888, false), offset, 600, 400)

        val cropped = cropDanmakuImage(original, offset)
        val bitmap = cropped.bitmap.asAndroidBitmap()
        assertEquals(161 + 4, bitmap.width)
        assertEquals(48 + 4, bitmap.height, "从最上面那一点到横线, 上下各多留 2 像素")
        assertEquals(Offset(-30f + 38, -30f + 1), cropped.offset, "偏移跟着挪到裁剪后的左上角")
        assertTrue(place(bitmap, cropped.offset, 600, 400).sameAs(reference), "画出来与裁剪前一模一样")
    }

    @Test
    fun `a real danmaku image loses most of its transparent margin`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(context), LayoutDirection.Ltr)
        val danmaku = dummyDanmaku(measurer, TextStyle(fontSize = 36.sp), DanmakuStyle.Default.copy(fontSize = 36.sp), "名场面！Ǻ̈̈ 666")
        val image = danmaku.cachedImage()
        val bitmap = image.bitmap.asAndroidBitmap()
        // 原先四周各留半个字高: 图高是字高的两倍
        assertTrue(bitmap.height < danmaku.danmakuHeight * 3 / 2, "图高 ${bitmap.height}, 字高 ${danmaku.danmakuHeight}")
        assertTrue(bitmap.width < danmaku.danmakuWidth + danmaku.danmakuHeight / 2, "图宽 ${bitmap.width}, 字宽 ${danmaku.danmakuWidth}")
    }
}
