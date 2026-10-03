/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import me.him188.ani.app.data.network.TmdbTitleLogo
import me.him188.ani.app.ui.foundation.tv.tvTitleLogoBox
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * hero 文字块的标题 logo 怎么上: 还不知道有没有 logo 时进场先等 (查完马上进场, 等到点照文字标题进场); 知道有 logo 时一进场就按 logo 排版
 * (标题位是 logo 框的高), 不先放文字标题; logo 的图出了问题 (这里的地址不存在) 才换成文字标题.
 */
class TvNativeHeroTextLogoTest {
    private val host = TvNativeTestHost()
    private lateinit var heroText: TvNativeHeroTextView
    private val box = assertNotNull(tvTitleLogoBox(titleLineHeightPx = 72, titleWidthPx = 0))

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            heroText = TvNativeHeroTextView(host.activity, testHeroTextStyle().copy(animated = true, logoBox = box)).apply {
                sketch = host.sketch
                titleWidthPx = 1000
                summaryWidthPx = 1000
            }
            host.root.addView(heroText, FrameLayout.LayoutParams(1200, 600).apply { leftMargin = 100; topMargin = 200 })
        }
        host.waitUntil("文字块挂上窗口") { heroText.isAttachedToWindow }
    }

    @AfterTest
    fun tearDown() = host.close()

    private val titleSlot: ViewGroup get() = heroText.getChildAt(0) as ViewGroup
    private val title: TextView get() = titleSlot.getChildAt(0) as TextView

    @Test
    fun `the entry waits for the logo lookup and starts as soon as it is done`() {
        host.onMain {
            heroText.setText(text(1, pending = true), TvNativeTextTransition.Key)
            assertNull(heroText.shownSubjectId, "还不知道有没有 logo: 先不进场")
            heroText.setText(text(1, pending = false), TvNativeTextTransition.Key)
            assertEquals(1, heroText.shownSubjectId, "查完了 (没有 logo): 马上进场")
        }
    }

    @Test
    fun `the entry gives up waiting for the logo lookup after a while`() {
        val start = host.onMain {
            heroText.setText(text(2, pending = true), TvNativeTextTransition.Key)
            SystemClock.uptimeMillis()
        }
        host.waitUntil("等到点照文字标题进场") { heroText.shownSubjectId == 2 }
        val waited = SystemClock.uptimeMillis() - start
        assertTrue(waited >= 250, "进场应等一会儿再放弃, 实际 ${waited}ms")
        host.onMain { assertEquals(View.VISIBLE, title.visibility, "照文字标题进场") }
    }

    @Test
    fun `another subject while waiting enters right away`() {
        host.onMain {
            heroText.setText(text(3, pending = true), TvNativeTextTransition.Key)
            heroText.setText(text(4, pending = false), TvNativeTextTransition.Key)
            assertEquals(4, heroText.shownSubjectId, "换成已查完的另一部: 马上进场那一部")
        }
    }

    @Test
    fun `a known logo takes the logo slot before its image arrives`() {
        val logo = TmdbTitleLogo("/izuko-device-test-missing-logo.png", aspectRatio = 3f)
        host.onMain {
            heroText.setText(text(5, logo = logo), TvNativeTextTransition.Reset)
            // 当场量一遍 (不等下一帧: 图片请求的结果可能先回来)
            heroText.measure(exactly(1200), exactly(600))
            heroText.layout(heroText.left, heroText.top, heroText.right, heroText.bottom)
            assertEquals(View.GONE, title.visibility, "知道有 logo: 不先放文字标题")
            assertEquals(box.maxHeightPx, titleSlot.measuredHeight, "标题位是 logo 框的高")
        }
        // 地址不存在: 加载失败 (或等满) 后换成文字标题
        host.waitUntil("图出了问题换成文字标题", timeoutMillis = 10_000) { title.visibility == View.VISIBLE }
    }

    private fun text(subjectId: Int, pending: Boolean = false, logo: TmdbTitleLogo? = null) = TvNativeHeroText(
        subjectId = subjectId,
        title = "标题 $subjectId",
        infoReady = true,
        summary = "简介",
        logo = logo,
        logoPending = pending,
    )

    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
}
