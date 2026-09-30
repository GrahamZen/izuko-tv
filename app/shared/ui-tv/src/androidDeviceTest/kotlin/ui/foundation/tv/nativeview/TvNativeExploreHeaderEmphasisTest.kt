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
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 探索页海报墙的组标题 ([TvNativeExploreHeaderView]): 焦点所在那一组的标题全亮, 其余淡到 [TvNativeExploreView.headerIdleAlpha];
 * 一组切成几行时后面几行也算这一组; 焦点在轮播按钮 / 本页以外时都淡.
 *
 * 列表: [hero 占位] [继续观看标题] 继续观看 20 张 [推荐标题] 推荐 12 张 / 12 张.
 */
class TvNativeExploreHeaderEmphasisTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private lateinit var view: TvNativeExploreView
    private lateinit var outside: View

    private val listener = object : TvNativeExploreListener {
        override fun onCardFocused(rowKey: String, index: Int, column: Int) = Unit
        override fun onCardClick(rowKey: String, index: Int) = Unit
        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onHeroButtonFocused(button: Int) = Unit
        override fun onHeroButtonClick(button: Int) = Unit
        override fun onSwitchCarousel(delta: Int): Boolean = false
        override fun onExitLeft() = Unit
        override fun onHeroActiveChanged(active: Boolean) = Unit
        override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit
        override fun onToneChanged(tone: Float, splitY: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val style = testWallStyle()
            view = TvNativeExploreView(
                host.activity, host.sketch, scope, style, testExploreMetrics(style),
                testHeroTextStyle(), testHeroButtonStyle(), testTextStyle(32f, 44),
            )
            view.transitions = false
            view.animatedScroll = false
            view.headerIdleAlpha = IDLE
            view.listener = listener
            view.setButtons("立即观看", null, "新番时间表", null)
            view.setCarousel(3, 0, Color.WHITE)
            val empty = TvNativeHeroSource(backdrop = null, dimming = false, rawSubjectId = null, text = null)
            view.setSources(empty, empty)
            view.setItems(
                listOf(
                    TvNativeExploreItem.Spacer("spacer"),
                    TvNativeExploreItem.Header("followed-header", FOLLOWED_TITLE),
                    TvNativeExploreItem.Row(FOLLOWED, testCards(20, "在看")),
                    TvNativeExploreItem.Header("rec-header", REC_TITLE),
                    TvNativeExploreItem.Row(REC0, testCards(12, "推荐")),
                    TvNativeExploreItem.Row(REC1, testCards(12, "再推荐")),
                ),
            )
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            // 页面外的一个落点 (代替侧边栏)
            outside = View(host.activity).apply { isFocusable = true }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        host.onMain { view.focusHeroButton(0) }
        host.waitUntil("焦点在立即观看") { view.findFocus() != null && !view.cardAreaHasFocus }
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    /** 屏上写着 [title] 的组标题此刻的不透明度; 没排出来时 null. */
    private fun headerAlpha(title: String): Float? = host.onMain { findHeader(view, title)?.alpha }

    private fun findHeader(v: View, title: String): TvNativeExploreHeaderView? {
        if (v is TvNativeExploreHeaderView && v.text.toString() == title) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findHeader(v.getChildAt(i), title)?.let { return it }
        }
        return null
    }

    private fun waitCard(rowKey: String) =
        host.waitUntil("焦点到 $rowKey") { view.cardAreaHasFocus && view.focusedRowKey == rowKey }

    @Test
    fun `only the header of the focused group is bright`() {
        // 焦点在轮播按钮上: 都淡
        assertEquals(IDLE, headerAlpha(FOLLOWED_TITLE))

        repeat(2) { host.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitCard(FOLLOWED)
        assertEquals(1f, headerAlpha(FOLLOWED_TITLE))
        assertEquals(IDLE, headerAlpha(REC_TITLE))

        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC0)
        assertEquals(1f, headerAlpha(REC_TITLE))
        headerAlpha(FOLLOWED_TITLE)?.let { assertEquals(IDLE, it) }

        // 同一组的第二行: 还是这一组的标题
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC1)
        assertEquals(1f, headerAlpha(REC_TITLE))
    }

    @Test
    fun `headers dim when focus leaves the page`() {
        repeat(2) { host.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitCard(FOLLOWED)
        assertEquals(1f, headerAlpha(FOLLOWED_TITLE))

        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点出了卡片区") { !view.cardAreaHasFocus }
        assertEquals(IDLE, headerAlpha(FOLLOWED_TITLE))
    }

    private companion object {
        const val IDLE = 0.5f
        const val FOLLOWED = "followed-row"
        const val REC0 = "rec-row-0"
        const val REC1 = "rec-row-1"
        const val FOLLOWED_TITLE = "继续观看"
        const val REC_TITLE = "推荐"
    }
}
