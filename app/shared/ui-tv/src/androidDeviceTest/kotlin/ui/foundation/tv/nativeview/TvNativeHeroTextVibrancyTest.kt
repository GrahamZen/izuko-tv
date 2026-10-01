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
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * hero 文字块的 vibrancy ([TvNativeHeroText.vibrant], hero 态铺着模糊背景时): 简介与次要色的字换成次要那一档 ([tvVibrancySecondary]),
 * 以加法混合画在背景上; 标题与其余颜色的字照常. 深灰底上截本窗口量简介最亮的一点: 照常是不透明的白, vibrancy 是「底 + 白的一半」.
 */
class TvNativeHeroTextVibrancyTest {
    private val host = TvNativeTestHost()
    private lateinit var heroText: TvNativeHeroTextView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            host.root.setBackgroundColor(Color.rgb(BACKGROUND, BACKGROUND, BACKGROUND))
            val style = testHeroTextStyle().let { it.copy(meta = it.meta.copy(color = SECONDARY), status = it.status.copy(color = SECONDARY)) }
            heroText = TvNativeHeroTextView(host.activity, style).apply {
                titleWidthPx = WIDTH
                summaryWidthPx = WIDTH
            }
            host.root.addView(heroText, FrameLayout.LayoutParams(1200, 600).apply { leftMargin = 100; topMargin = 200 })
        }
        host.waitUntil("文字块排出来") { heroText.isLaidOut }
    }

    @AfterTest
    fun tearDown() = host.close()

    /** 行的顺序: 标题, 信息行 (星 / 评分 / 那一串), 下一集行, 简介. */
    private val title: TextView get() = heroText.getChildAt(0) as TextView
    private val meta: TextView get() = (heroText.getChildAt(1) as ViewGroup).getChildAt(2) as TextView
    private val summary: TextView get() = heroText.getChildAt(3) as TextView

    private fun show(vibrant: Boolean) {
        host.onMain { heroText.setText(TEXT.copy(vibrant = vibrant), TvNativeTextTransition.Reset) }
        host.waitUntil("换上的字排好") { !heroText.isLayoutRequested && summary.visibility == View.VISIBLE && summary.width > 0 }
    }

    @Test
    fun `a vibrant summary is added onto the background while the title stays white`() {
        show(vibrant = false)
        val plainShot = shot()
        val plain = host.onMain { brightest(plainShot, summary) }
        show(vibrant = true)
        val vibrantShot = shot()
        val added = host.onMain { brightest(vibrantShot, summary) }
        val titleBrightest = host.onMain { brightest(vibrantShot, title) }
        assertTrue(plain >= 250, "照常的简介是不透明的白, 实际最亮处 $plain")
        // 加上去: 60 + 255 / 2 ≈ 188 (白 50% 盖上去是 60 / 2 + 255 / 2 ≈ 158)
        assertTrue(added in 178..198, "vibrancy 的简介应是底加白的一半, 实际最亮处 $added")
        assertTrue(titleBrightest >= 250, "标题照常全亮, 实际最亮处 $titleBrightest")
    }

    @Test
    fun `vibrancy recolors only the secondary spans of the info line`() {
        show(vibrant = true)
        host.onMain {
            val colors = spanColors(meta)
            assertEquals(tvVibrancySecondary(Color.WHITE), colors[0], "次要色那一段换成次要那一档")
            assertEquals(HIGHLIGHT, colors[1], "其余颜色那一段不换")
            assertNotNull(meta.paint.xfermode, "信息行以加法混合画")
        }
        show(vibrant = false)
        host.onMain {
            assertEquals(listOf(SECONDARY, HIGHLIGHT), spanColors(meta))
            assertNull(meta.paint.xfermode, "照常画")
            assertNull(summary.paint.xfermode, "照常画")
        }
    }

    private fun spanColors(view: TextView): List<Int> {
        val text = view.text as Spanned
        return text.getSpans(0, text.length, ForegroundColorSpan::class.java)
            .sortedBy { text.getSpanStart(it) }
            .map { it.foregroundColor }
    }

    private fun shot(): Bitmap {
        SystemClock.sleep(200)
        host.instrumentation.waitForIdleSync()
        return host.windowShot()
    }

    /** [view] 的框里最亮的一点 (红色分量; 字是白的, 底是灰的). */
    private fun brightest(shot: Bitmap, view: View): Int {
        val xy = IntArray(2)
        view.getLocationInWindow(xy)
        var max = 0
        for (y in xy[1] until xy[1] + view.height) {
            for (x in xy[0] until xy[0] + view.width) max = maxOf(max, Color.red(shot.getPixel(x, y)))
        }
        return max
    }

    private companion object {
        const val BACKGROUND = 60
        const val WIDTH = 900
        val SECONDARY = Color.rgb(180, 181, 183)
        val HIGHLIGHT = Color.rgb(227, 226, 230)
        val TEXT = TvNativeHeroText(
            subjectId = 1,
            title = "标题标题标题",
            infoReady = true,
            meta = listOf(TvNativeTextSpan("连载中", SECONDARY), TvNativeTextSpan(" · 12 集", HIGHLIGHT)),
            summary = "简介简介简介简介简介简介简介简介",
        )
    }
}
