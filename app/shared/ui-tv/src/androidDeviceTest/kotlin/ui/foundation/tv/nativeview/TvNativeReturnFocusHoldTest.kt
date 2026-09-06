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
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 返回本页 (页面重建, 如从播放器 / 整页回来) 时焦点要回的那张卡 / 那一格: 从排出来的第一帧起就画成聚焦态, 焦点送到时画面不变, 不从未聚焦
 * 再放大一遍. 三种容器: 探索页 ([TvNativeExploreView.restore] 带着上次停的行)、网格页 ([TvNativeGridPageView.holdLandingLook], 网格建出来之前
 * 就给)、圆头像行 ([TvNativeMonogramStrip] 的 restoreFocus, 页面重建时由 SaveableStateHolder 摘下 / 装回, 同 Nav3 保存页面状态的方式).
 * 聚焦动画要有时长 (共用样式是 0, 状态动画当场到位, 测不出这一跳), 模拟器要开着系统动画.
 */
class TvNativeReturnFocusHoldTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    private fun assertAnimatorsOn() =
        assertTrue(ValueAnimator.areAnimatorsEnabled(), "要开着系统动画跑: 关着时状态动画当场到位, 测不出先小后大")

    // ------------------------------------------------------------------
    // 探索页
    // ------------------------------------------------------------------

    /** 同页面重建: 建出来先按页面记下的焦点簿记恢复 (factory 里), 列表项之后才给 (update 里). */
    private fun buildExplore(focusedRowKey: String?): TvNativeExploreView {
        host.launch()
        val view = host.onMain {
            // 页面以外持着焦点 (真实页面里是 Compose 的焦点), 见网格那一例
            val outside = View(host.activity).apply { isFocusable = true }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
            outside.requestFocus()
            val style = testWallStyle().copy(focusMillis = 300L)
            TvNativeExploreView(
                host.activity, host.sketch, scope, style, testExploreMetrics(style),
                testHeroTextStyle(), testHeroButtonStyle(), testTextStyle(32f, 44),
            ).also { view ->
                view.transitions = false
                view.animatedScroll = false
                view.restore(
                    scrollPx = 0,
                    rowLeftIndex = mapOf(ROW to 0),
                    rowFocusedIndex = mapOf(ROW to 2),
                    focusedRowKey = focusedRowKey,
                    focusedCardIndex = 0,
                    heroActive = false,
                )
                view.setButtons("立即观看", null, "新番时间表", null)
                view.setCarousel(3, 0, Color.WHITE)
                val empty = TvNativeHeroSource(backdrop = null, dimming = false, rawSubjectId = null, text = null)
                view.setSources(empty, empty)
                view.setItems(
                    listOf(
                        TvNativeExploreItem.Spacer("spacer"),
                        TvNativeExploreItem.Header("header", "继续观看"),
                        TvNativeExploreItem.Row(ROW, testCards(12, "在看")),
                    ),
                )
                host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        host.waitUntil("第 2 张排出来") { exploreCard(view, 2) != null }
        return view
    }

    private fun exploreCard(view: TvNativeExploreView, index: Int): TvNativeCardView? =
        findRow(view, ROW)?.findViewHolderForAdapterPosition(index)?.itemView as? TvNativeCardView

    @Test
    fun `a rebuilt explore wall draws the remembered card focused before the focus comes back`() {
        assertAnimatorsOn()
        val view = buildExplore(focusedRowKey = ROW)
        host.onMain {
            val card = exploreCard(view, 2)!!
            assertFalse(card.isFocused)
            assertTrue(card.focusLookHeld, "重建出来的第一帧按未聚焦画了 (焦点回来时会再放大一遍)")
            assertFalse(exploreCard(view, 1)!!.focusLookHeld, "只按住上次停的那张")
        }
        // 页面的进页落点 (cardIndex = -1: 这一行上次停的那张)
        host.onMain { view.focusCard(ROW, -1) }
        host.waitUntil("焦点回到第 2 张") { exploreCard(view, 2)?.isFocused == true }
        val (progress, held) = host.onMain { exploreCard(view, 2)!!.let { it.focusProgress to it.focusLookHeld } }
        assertEquals(1f, progress, "焦点回来时聚焦动画从头走了一遍")
        assertFalse(held)
    }

    @Test
    fun `a rebuilt explore wall holds no card when the focus goes back to the hero buttons`() {
        // 离开前焦点在轮播按钮上 (页面的 focusedRowKey 是 null): 落点是按钮, 卡片一张都不能放大着
        val view = buildExplore(focusedRowKey = null)
        host.onMain { assertFalse(exploreCard(view, 2)!!.focusLookHeld) }
    }

    // ------------------------------------------------------------------
    // 网格页 (追番 / 搜索 / 时间表)
    // ------------------------------------------------------------------

    @Test
    fun `a rebuilt grid page draws the landing card focused before the focus comes back`() {
        assertAnimatorsOn()
        host.launch()
        val page = host.onMain {
            // 页面以外持着焦点 (真实页面里是 Compose 的焦点): 窗口里没有焦点时系统会把焦点塞给第一张排出来的卡, 按住随之放开
            val outside = View(host.activity).apply { isFocusable = true }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
            outside.requestFocus()
            TvNativeGridPageView(host.activity, host.sketch, scope, testWallStyle().copy(focusMillis = 300L), testGridPageMetrics(), testHeroTextStyle())
                .also { page ->
                    page.transitions = false
                    page.animatedScroll = false
                    // 同宿主建视图: 网格还没建出来就给进页落点
                    page.holdLandingLook(3)
                    host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                    page.showGrid(0, direction = 0, animated = false)
                    page.setCards(0, testCards(12))
                }
        }
        fun card(index: Int) = page.grid?.findViewHolderForAdapterPosition(index)?.itemView as? TvNativeCardView
        host.waitUntil("第 3 张排出来") { card(3) != null }
        host.onMain {
            assertFalse(card(3)!!.isFocused)
            assertTrue(card(3)!!.focusLookHeld, "重建出来的第一帧按未聚焦画了")
            assertFalse(card(2)!!.focusLookHeld, "只按住落点那张")
        }
        // 页面的网格送焦 (NativeSendFocusEffect)
        host.onMain { page.focusItem(3) }
        host.waitUntil("焦点到第 3 张") { card(3)?.isFocused == true }
        val (progress, held) = host.onMain { card(3)!!.let { it.focusProgress to it.focusLookHeld } }
        assertEquals(1f, progress, "焦点回来时聚焦动画从头走了一遍")
        assertFalse(held)
    }

    // ------------------------------------------------------------------
    // 圆头像行 (详情页角色 / 制作人员)
    // ------------------------------------------------------------------

    private val outside = FocusRequester()
    private val rowAnchor = FocusRequester()
    private var shown by mutableStateOf(true)
    private var restoring by mutableStateOf(false)
    private val monogramStyle by lazy { testMonogramStyle().copy(focusMillis = 300L) }

    private fun launchMonogramRow() {
        host.launch()
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(
                    LocalSketch provides host.sketch,
                    LocalThemeSettings provides ThemeSettings.Default,
                ) {
                    val pages = rememberSaveableStateHolder()
                    Column {
                        // 页面以外的落点 (代替整页 / 播放器)
                        Box(Modifier.size(48.dp).focusRequester(outside).focusable())
                        if (shown) {
                            pages.SaveableStateProvider("details") {
                                // 同详情页: 区块落点挂在这一排上, 焦点经它送进行里
                                Box(Modifier.focusRequester(rowAnchor).focusGroup()) {
                                    TvNativeMonogramStrip(
                                        items = testPeople(8),
                                        style = monogramStyle,
                                        onClick = {},
                                        onLongPress = null,
                                        repeatMillis = 100L,
                                        startPadding = 48.dp,
                                        endPadding = 48.dp,
                                        restoreFocus = restoring,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        host.waitUntil("行排出来") { monogramRow()?.childCount?.let { it > 0 } == true }
    }

    private fun monogramRow(): TvNativeMonogramRowView? = findMonogramRow(host.root)

    private fun cell(index: Int): TvNativeMonogramCardView? =
        monogramRow()?.findViewHolderForAdapterPosition(index)?.itemView as? TvNativeMonogramCardView

    /** 进行、走到第 2 格, 焦点交给页面以外, 页面离开组合. */
    private fun focusThirdCellAndLeave() {
        host.onMain { rowAnchor.requestFocus() }
        host.waitUntil("焦点进了行首那格") { cell(0)?.isFocused == true }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("第 2 格聚焦到位") { cell(2)?.isFocused == true && cell(2)?.focusProgress == 1f }
        host.onMain {
            outside.requestFocus()
            shown = false
        }
        host.waitUntil("页面离开组合") { monogramRow() == null }
    }

    @Test
    fun `a rebuilt monogram row draws the remembered cell focused when the page restores focus to it`() {
        assertAnimatorsOn()
        launchMonogramRow()
        focusThirdCellAndLeave()
        // 返回: 页面重建, 进页落点要回这一排
        host.onMain {
            restoring = true
            shown = true
        }
        host.waitUntil("行重建, 第 2 格排出来") { cell(2) != null }
        host.onMain {
            assertFalse(cell(2)!!.isFocused)
            assertTrue(cell(2)!!.focusLookHeld, "重建出来的第一帧按未聚焦画了")
            assertFalse(cell(1)!!.focusLookHeld, "只按住上次聚焦那格")
        }
        host.onMain { rowAnchor.requestFocus() }
        host.waitUntil("焦点回到第 2 格") { cell(2)?.isFocused == true }
        val (progress, held) = host.onMain { cell(2)!!.let { it.focusProgress to it.focusLookHeld } }
        assertEquals(1f, progress, "焦点回来时聚焦动画从头走了一遍")
        assertFalse(held)
    }

    @Test
    fun `a rebuilt monogram row that the page does not restore to holds no cell`() {
        launchMonogramRow()
        focusThirdCellAndLeave()
        // 返回, 但进页落点在别的区块
        host.onMain { shown = true }
        host.waitUntil("行重建, 第 2 格排出来") { cell(2) != null }
        host.onMain { assertFalse(cell(2)!!.focusLookHeld) }
    }

    private companion object {
        const val ROW = "followed"
    }
}

/** 同 TvNativeGridPageNavigationTest 的页面尺寸 (1080p, 6 列). */
private fun testGridPageMetrics(): TvNativeGridPageMetrics = TvNativeGridPageMetrics(
    pageWidthPx = 1920,
    pageHeightPx = 1080,
    gridTopPx = 120,
    grid = TvNativeGridMetrics(
        columns = 6,
        startPx = 128,
        endPx = 48,
        topBleedPx = 0,
        bottomBleedPx = 0,
        endMarginPx = 60,
        heroLinePx = 400,
        fadeDistancePx = 128f,
    ),
    backdropWidthPx = 1344,
    backdropHeightPx = 756,
    heroLeftPx = 128,
    heroTopPx = 120,
    heroWidthPx = 900,
    heroHeightPx = 300,
    titleWidthPx = 900,
    summaryWidthPx = 800,
)

private fun findRow(v: View, key: String): TvNativeRowView? = when {
    v is TvNativeRowView && v.tag == key -> v
    v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findRow(v.getChildAt(it), key) }
    else -> null
}

private fun findMonogramRow(v: View): TvNativeMonogramRowView? = when (v) {
    is TvNativeMonogramRowView -> v
    is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findMonogramRow(v.getChildAt(it)) }
    else -> null
}
