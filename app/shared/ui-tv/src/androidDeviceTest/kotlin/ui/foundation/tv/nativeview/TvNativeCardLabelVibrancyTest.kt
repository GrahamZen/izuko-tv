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
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 卡片番名块两行字的亮度: 番名与下面那行小字没聚焦时都淡一档、聚焦时全亮 ([TvNativeWallStyle.labelIdleAlpha]); 开了 vibrancy
 * ([TvNativeWallStyle.labelVibrancy]) 的卡把字色加在底下的背景上, 中灰的底上没聚焦的小字比半透明盖上去的亮得多.
 * 中灰底上并排两张卡 (一张不开 vibrancy、一张开), 截本窗口量两行字各自最亮的一点 (字是白的).
 */
class TvNativeCardLabelVibrancyTest {
    private val host = TvNativeTestHost()
    private lateinit var normal: TvNativeCardView
    private lateinit var vibrant: TvNativeCardView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            host.root.setBackgroundColor(Color.rgb(BACKGROUND, BACKGROUND, BACKGROUND))
            val style = testWallStyle()
            normal = TvNativeCardView(host.activity, style)
            vibrant = TvNativeCardView(host.activity, style.copy(labelVibrancy = true))
            for ((i, card) in listOf(normal, vibrant).withIndex()) {
                // 窗口一开系统会把焦点给第一个可聚焦的视图: 两张都不许聚焦, 保持没聚焦的样子
                card.isFocusable = false
                card.bind(TvNativeCard(imageUrl = null, title = TITLE, subtitle = SUBTITLE, subtitleColor = Color.WHITE), host.sketch)
                host.root.addView(
                    card,
                    FrameLayout.LayoutParams(style.cardWidthPx, style.cardBlockHeightPx).apply { leftMargin = 100 + i * 600; topMargin = 100 },
                )
            }
        }
        host.waitUntil("两张卡排出来") { normal.width > 0 && vibrant.width > 0 }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `an idle card dims its title and subtitle alike`() {
        val shot = shot()
        val title = host.onMain { brightest(shot, normal, TITLE) }
        val subtitle = host.onMain { brightest(shot, normal, SUBTITLE) }
        // 半透明白字盖在中灰上: 128 + (255 − 128) / 2 ≈ 191
        assertTrue(title in 170..215, "没聚焦的番名淡一档, 实际最亮处 $title")
        assertTrue(subtitle in 170..215, "没聚焦的小字淡一档, 实际最亮处 $subtitle")
    }

    @Test
    fun `a focused card shows its title and subtitle at full brightness`() {
        host.onMain {
            normal.isFocusable = true
            normal.requestFocus()
        }
        host.waitUntil("卡拿到焦点") { normal.isFocused }
        val shot = shot()
        val title = host.onMain { brightest(shot, normal, TITLE) }
        val subtitle = host.onMain { brightest(shot, normal, SUBTITLE) }
        assertTrue(title >= 250, "聚焦的番名全亮, 实际最亮处 $title")
        assertTrue(subtitle >= 250, "聚焦的小字全亮, 实际最亮处 $subtitle")
    }

    @Test
    fun `an idle vibrant subtitle is brighter than a plain translucent one`() {
        val shot = shot()
        val plain = host.onMain { brightest(shot, normal, SUBTITLE) }
        val added = host.onMain { brightest(shot, vibrant, SUBTITLE) }
        // 半透明白字: 128 + (255 − 128) / 2 ≈ 191; 加上去: 128 + 255 / 2 ≈ 255
        assertTrue(plain in 170..215, "普通小字最亮处 $plain")
        assertTrue(added >= plain + 40, "vibrancy 小字最亮处 $added 应明显亮过普通的 $plain")
    }

    private fun shot(): Bitmap {
        SystemClock.sleep(200)
        host.instrumentation.waitForIdleSync()
        return host.windowShot()
    }

    /** [card] 里写着 [text] 的那行字的框里最亮的一点 (红色分量; 字是白的, 底是灰的). */
    private fun brightest(shot: Bitmap, card: TvNativeCardView, text: String): Int {
        val view = findText(card, text) ?: error("卡里没有「$text」这行字")
        val xy = IntArray(2)
        view.getLocationInWindow(xy)
        var max = 0
        for (y in xy[1] until xy[1] + view.height) {
            for (x in xy[0] until xy[0] + view.width) max = maxOf(max, Color.red(shot.getPixel(x, y)))
        }
        return max
    }

    private fun findText(view: View, text: String): TvNativeTextView? {
        if (view is TvNativeTextView && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findText(view.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private companion object {
        const val BACKGROUND = 128
        const val TITLE = "番名番名番名"
        const val SUBTITLE = "小字小字小字"
    }
}
