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
import android.widget.FrameLayout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 番名的 vibrancy ([TvNativeWallStyle.labelVibrancy]): 没聚焦的卡番名是半透明的, 照 tvOS 把字色加在底下的背景上, 中灰的底上比半透明白字
 * 亮得多. 中灰底上并排两张没聚焦的卡 (一张开、一张不开), 截本窗口比两块番名最亮处.
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
                card.bind(TvNativeCard(imageUrl = null, title = "测试测试测试"), host.sketch)
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
    fun `an idle vibrant label is brighter than a plain translucent one`() {
        SystemClock.sleep(200)
        host.instrumentation.waitForIdleSync()
        val shot = host.windowShot()
        val plain = host.onMain { brightestInLabel(shot, normal) }
        val added = host.onMain { brightestInLabel(shot, vibrant) }
        // 半透明白字: 128 + (255 − 128) / 2 ≈ 191; 加上去: 128 + 255 / 2 ≈ 255
        assertTrue(plain in 170..215, "普通番名最亮处 $plain")
        assertTrue(added >= plain + 40, "vibrancy 番名最亮处 $added 应明显亮过普通的 $plain")
    }

    /** [card] 番名块里最亮的一点 (红色分量; 字是白的, 底是灰的). */
    private fun brightestInLabel(shot: Bitmap, card: TvNativeCardView): Int {
        val xy = IntArray(2)
        card.getLocationInWindow(xy)
        val style = testWallStyle()
        val top = xy[1] + style.cardHeightPx
        var max = 0
        for (y in top until top + style.labelHeightPx) {
            for (x in xy[0] until xy[0] + style.cardWidthPx) max = maxOf(max, Color.red(shot.getPixel(x, y)))
        }
        return max
    }

    private companion object {
        const val BACKGROUND = 128
    }
}
