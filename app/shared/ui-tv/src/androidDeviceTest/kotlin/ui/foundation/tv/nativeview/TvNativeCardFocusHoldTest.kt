/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.animation.ValueAnimator
import android.view.View
import android.widget.FrameLayout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 卡片按住的聚焦态 ([TvNativeCardView.setFocusLookHeld], 进出详情页期间) 在焦点回来时放开: 放开发生在拿到焦点的回调里 (适配器的焦点监听),
 * 卡要一直画成聚焦态, 不先缩回再放大一遍. 聚焦动画要有时长 (其余用例的样式是 0, 状态动画当场到位, 测不出这一跳), 模拟器要开着系统动画.
 */
class TvNativeCardFocusHoldTest {
    private val host = TvNativeTestHost()
    private lateinit var grid: TvNativeGridView
    private lateinit var outside: View

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val metrics = TvNativeGridMetrics(
                columns = 6,
                startPx = 128,
                endPx = 48,
                topBleedPx = 0,
                bottomBleedPx = 0,
                endMarginPx = 60,
                heroLinePx = 400,
                fadeDistancePx = 128f,
            )
            grid = TvNativeGridView(host.activity, testWallStyle().copy(focusMillis = 300L), host.sketch, metrics)
            grid.animatedScroll = false
            host.root.addView(grid, FrameLayout.LayoutParams(1920, 1000))
            // 页面外的一个落点 (代替详情页)
            outside = View(host.activity).apply { isFocusable = true }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
            grid.cards.submit(testCards(12)) { it.toLong() }
        }
        host.waitUntil("卡片排出来") { grid.childCount > 0 }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun card(index: Int): TvNativeCardView? = grid.findViewHolderForAdapterPosition(index)?.itemView as? TvNativeCardView

    @Test
    fun `a held card stays drawn focused when the focus comes back`() {
        assertTrue(ValueAnimator.areAnimatorsEnabled(), "要开着系统动画跑: 关着时状态动画当场到位, 测不出缩回再放大那一跳")
        host.onMain { grid.focusItem(3) }
        host.waitUntil("第 3 张聚焦到位") { card(3)?.isFocused == true && card(3)?.focusProgress == 1f }
        // 进详情页: 按住聚焦态, 焦点交出去
        host.onMain {
            grid.cards.setFocusLookHeld(grid, 3)
            outside.requestFocus()
        }
        host.waitUntil("焦点离开网格") { outside.isFocused }
        // 返回: 焦点交还的那一刻 (还没到下一帧) 就是聚焦态, 按住随之放开
        val (progress, held) = host.onMain {
            grid.focusItem(3)
            card(3)?.focusProgress to card(3)?.focusLookHeld
        }
        assertEquals(1f, progress, "焦点回来时先缩回了")
        assertEquals(false, held)
    }
}
