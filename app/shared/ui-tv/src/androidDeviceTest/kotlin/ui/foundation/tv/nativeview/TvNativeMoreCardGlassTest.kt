/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.widget.FrameLayout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 行尾「更多」卡的玻璃外观 ([TvNativeMoreGlassStyle]): 底下铺一张竖版封面的模糊小图, 盖玻璃色; 中间的圆钮平时是玻璃, 卡片聚焦时换成浅色实底.
 * 深色 / 浅色两排, 每排四张: 原海报 (对照) / 没聚焦的「更多」/ 聚焦的「更多」/ 没有底图的「更多」(对照). 截本窗口看几处像素;
 * 截图另存到测试包的缓存目录 (`adb shell run-as me.him188.ani.app.tv.test cat cache/more_glass_dark.png > more_glass_dark.png` 拉出来看).
 */
class TvNativeMoreCardGlassTest {
    private val host = TvNativeTestHost()
    private val rows = mutableListOf<List<TvNativeCardView>>()
    private val styles = mutableListOf<TvNativeWallStyle>()

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            // 测试窗口的标题栏会压住第一排上缘 (截图里看不全)
            host.activity.actionBar?.hide()
            val poster = writePoster()
            val base = testWallStyle()
            styles += base.copy(moreGlass = TV_NATIVE_MORE_GLASS_DARK)
            styles += base.copy(
                moreGlass = TV_NATIVE_MORE_GLASS_LIGHT,
                placeholderColor = LIGHT_PLACEHOLDER,
                title = base.title.copy(color = Color.BLACK),
            )
            for ((row, style) in styles.withIndex()) {
                // 这一排的页面底色; 同海报行不裁子视图: 聚焦的卡放大、投影都伸出卡框
                val band = FrameLayout(host.activity).apply {
                    setBackgroundColor(if (row == 0) DARK_PAGE else LIGHT_PAGE)
                    clipChildren = false
                }
                host.root.addView(band, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, BAND_HEIGHT).apply { topMargin = row * BAND_HEIGHT })
                val cards = listOf(
                    TvNativeCard(imageUrl = poster, title = "最后一张"),
                    TvNativeCard(imageUrl = poster, title = "更多相似的", more = TvNativeMore.Idle),
                    TvNativeCard(imageUrl = poster, title = "更多相似的", more = TvNativeMore.Idle),
                    TvNativeCard(imageUrl = null, title = "更多相似的", more = TvNativeMore.Idle),
                ).mapIndexed { i, data ->
                    TvNativeCardView(host.activity, style).also { card ->
                        // 窗口一开系统会把焦点给第一个可聚焦的视图: 只有要测聚焦的那张可聚焦
                        card.isFocusable = false
                        card.bind(data, host.sketch)
                        band.addView(card, FrameLayout.LayoutParams(style.cardWidthPx, style.cardBlockHeightPx).apply { leftMargin = 120 + i * 360; topMargin = 40 })
                    }
                }
                rows += cards
            }
        }
        host.waitUntil("卡片排出来") { rows.flatten().all { it.width > 0 } }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `the more card is a glass pane over the blurred last poster`() {
        for ((row, cards) in rows.withIndex()) {
            val (poster, idle, focused, bare) = cards
            host.onMain {
                rows.flatten().forEach { it.isFocusable = it === focused }
                focused.requestFocus()
            }
            host.waitUntil("第 $row 排的「更多」卡拿到焦点") { focused.isFocused && focused.focusProgress == 1f }
            // 模糊小图与原海报都解出来了: 与没有底图的那张同一处不再一样 (截图只能在测试线程上截, 所以自己轮询)
            val deadline = SystemClock.uptimeMillis() + 5000
            var shot = host.windowShot()
            while (
                pixelAt(shot, idle, 0.2f, 0.8f) == pixelAt(shot, bare, 0.2f, 0.8f) ||
                pixelAt(shot, poster, 0.2f, 0.8f) == styles[row].placeholderColor
            ) {
                if (SystemClock.uptimeMillis() > deadline) fail("等不到: 第 $row 排的模糊底图解出来")
                SystemClock.sleep(50)
                shot = host.windowShot()
            }
            saveShot(shot, if (row == 0) "more_glass_dark.png" else "more_glass_light.png")
            // 玻璃底: 与原海报同一处不一样 (盖了玻璃色、模糊过)
            assertTrue(pixelAt(shot, idle, 0.2f, 0.8f) != pixelAt(shot, poster, 0.2f, 0.8f), "第 $row 排「更多」卡底下是原图, 没有盖玻璃")
            // 圆钮: 没聚焦是玻璃 (透出底下), 聚焦换成浅色实底 —— 取圆钮里、加号笔画之外的一点 (圆钮半径约为封面宽的 0.16)
            val idleButton = pixelAt(shot, idle, BUTTON_X, BUTTON_Y)
            val focusedButton = pixelAt(shot, focused, BUTTON_X, BUTTON_Y)
            assertTrue(luminance(focusedButton) > 0.75f, "第 $row 排聚焦时圆钮不是浅色实底: ${Integer.toHexString(focusedButton)}")
            assertTrue(
                luminance(focusedButton) > luminance(idleButton) + 0.1f,
                "第 $row 排聚焦前后圆钮一样: ${Integer.toHexString(idleButton)} / ${Integer.toHexString(focusedButton)}",
            )
        }
    }

    /** 画一张有颜色起伏的「海报」存成文件, 返回 file:// 地址 (模糊之后看得出不是纯色). */
    private fun writePoster(): String {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, 600f,
            intArrayOf(0xFFE86A92.toInt(), 0xFFF7B267.toInt(), 0xFF2F4B7C.toInt()), null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 400f, 600f, paint)
        paint.shader = null
        paint.color = 0xFF6FD6C4.toInt()
        canvas.drawCircle(280f, 220f, 120f, paint)
        paint.color = 0xFF3A2E5C.toInt()
        canvas.drawRect(40f, 380f, 220f, 560f, paint)
        val file = File(host.activity.cacheDir, "more_glass_poster.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    private fun saveShot(shot: Bitmap, name: String) {
        File(host.activity.cacheDir, name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** [card] 封面框 (卡片外框内缩聚焦框空隙, 没放大时的位置) 里 ([fx], [fy]) 比例处的像素. */
    private fun pixelAt(shot: Bitmap, card: TvNativeCardView, fx: Float, fy: Float): Int = host.onMain {
        val xy = IntArray(2)
        card.getLocationInWindow(xy)
        val style = styles.first()
        shot.getPixel(xy[0] + style.gapPx + (style.coverWidthPx * fx).toInt(), xy[1] + style.gapPx + (style.coverHeightPx * fy).toInt())
    }

    private fun luminance(c: Int): Float = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    private companion object {
        const val BAND_HEIGHT = 540

        /** 圆钮里、加号笔画之外的一点: 离中心右下各约 0.08 个封面宽. */
        const val BUTTON_X = 0.58f
        const val BUTTON_Y = 0.555f
        val DARK_PAGE = Color.rgb(20, 20, 22)
        val LIGHT_PAGE = Color.rgb(236, 236, 238)
        val LIGHT_PLACEHOLDER = Color.rgb(216, 216, 220)
    }
}
