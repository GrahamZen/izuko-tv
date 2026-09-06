/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * hero 文字块里探索页「更多」卡那一行与简介 (见 [TvNativeHeroStatus.wrap] / [TvNativeHeroStatus.nameSeparator] / [TvNativeHeroText.summaryMaxLines]):
 * 整句那种放不下就折行、字一个不丢; 嵌名字那种名字前面不加分隔; 简介限了行数就不超过它.
 */
class TvNativeHeroMoreLineTest {
    private val host = TvNativeTestHost()
    private lateinit var heroText: TvNativeHeroTextView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            heroText = TvNativeHeroTextView(host.activity, testHeroTextStyle()).apply {
                titleWidthPx = SUMMARY_WIDTH
                summaryWidthPx = SUMMARY_WIDTH
            }
            host.root.addView(heroText, FrameLayout.LayoutParams(1200, 700))
        }
        host.waitUntil("文字块排出来") { heroText.isLaidOut }
    }

    @AfterTest
    fun tearDown() = host.close()

    /** 行的顺序: 标题, 信息行, 下一集行, 简介. */
    private val statusRow: ViewGroup get() = heroText.getChildAt(2) as ViewGroup
    private val summary: TextView get() = heroText.getChildAt(3) as TextView

    private fun show(text: TvNativeHeroText) {
        host.onMain { heroText.setText(text, TvNativeTextTransition.Reset) }
        host.waitUntil("换上的字排好") { !heroText.isLayoutRequested && statusRow.visibility == View.VISIBLE }
    }

    @Test
    fun `a whole sentence line wraps instead of being cut`() {
        show(text(TvNativeHeroStatus("下一集 第 3 话", name = null, tail = null, color = Color.WHITE)))
        val oneLine = host.onMain { statusRow.height }
        show(text(TvNativeHeroStatus(LONG_SENTENCE, name = null, tail = null, color = Color.WHITE, wrap = true)))
        host.onMain {
            val lead = statusRow.getChildAt(0) as TextView
            assertTrue(lead.lineCount >= 2, "整句没有折行: ${lead.lineCount} 行")
            assertTrue(statusRow.height >= oneLine * 2 - 2, "折行后那一行没有长高: $oneLine -> ${statusRow.height}")
            assertTrue(lead.width <= SUMMARY_WIDTH, "整句超出了简介宽度: ${lead.width}")
            assertEquals(View.GONE, statusRow.getChildAt(1).visibility)
            assertEquals(View.GONE, statusRow.getChildAt(2).visibility)
        }
        // 再换回普通的一行: 不再折行
        show(text(TvNativeHeroStatus("下一集 第 3 话", name = "很长很长的集名", tail = null, color = Color.WHITE)))
        host.onMain {
            assertEquals(1, (statusRow.getChildAt(0) as TextView).lineCount)
            assertEquals(" · 很长很长的集名", (statusRow.getChildAt(1) as TextView).text.toString())
        }
    }

    @Test
    fun `a name inside a sentence gets no separator`() {
        show(text(TvNativeHeroStatus("按确定，推荐更多和《", name = "葬送的芙莉莲", tail = "》相似的作品", color = Color.WHITE, nameSeparator = "")))
        host.onMain { assertEquals("葬送的芙莉莲", (statusRow.getChildAt(1) as TextView).text.toString()) }
    }

    @Test
    fun `a capped summary stays within its lines`() {
        show(text(TvNativeHeroStatus("一行", name = null, tail = null, color = Color.WHITE), summary = LONG_SENTENCE.repeat(6), summaryMaxLines = 2))
        host.onMain {
            assertEquals(2, summary.maxLines)
            assertEquals(2, summary.lineCount, "简介不是正好两行 (内容远不止两行)")
        }
    }

    private fun text(status: TvNativeHeroStatus, summary: String = "简介。", summaryMaxLines: Int = 0) = TvNativeHeroText(
        subjectId = 1,
        title = "标题",
        infoReady = true,
        rating = "8.0",
        status = status,
        summary = summary,
        summaryMaxLines = summaryMaxLines,
    )

    private companion object {
        const val SUMMARY_WIDTH = 360
        const val LONG_SENTENCE = "按确定，推荐更多「符合你口味的高分动画」，下一部就是它"
    }
}
