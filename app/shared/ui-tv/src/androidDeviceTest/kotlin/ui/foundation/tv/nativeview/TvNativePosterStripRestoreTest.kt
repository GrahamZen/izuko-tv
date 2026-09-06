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
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 单独成行的海报行 ([TvNativePosterStrip], 详情页关联条目) 点卡导航出去再返回: 页面离开组合、返回时整行重建 (这里用 SaveableStateHolder
 * 摘下 / 装回, 同 Nav3 保存页面状态的方式), 页面再把焦点送回点过的那张. 那张排出来就要画成聚焦态, 焦点到了画面不变, 不从未聚焦再放大一遍.
 * 行按真实样式建卡 (聚焦动画有时长), 模拟器要开着系统动画.
 */
class TvNativePosterStripRestoreTest {
    private val host = TvNativeTestHost()
    private val outside = FocusRequester()
    private val rowAnchor = FocusRequester()
    private var shown by mutableStateOf(true)
    private val clicked = mutableListOf<Int>()

    private fun launch(holdFocusLookOnClick: Boolean) {
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
                        // 页面以外的落点 (代替导航去的下一页)
                        Box(Modifier.size(48.dp).focusRequester(outside).focusable())
                        if (shown) {
                            pages.SaveableStateProvider("details") {
                                // 同详情页: 区块落点挂在这一列上, 焦点经它送进行里
                                Box(Modifier.focusRequester(rowAnchor).focusGroup()) {
                                    TvNativePosterStrip(
                                        testCards(10, "作品"),
                                        onClick = { clicked += it },
                                        startPadding = 48.dp,
                                        endPadding = 48.dp,
                                        holdFocusLookOnClick = holdFocusLookOnClick,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        host.waitUntil("行排出来") { row()?.childCount?.let { it > 0 } == true }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun row(): TvNativeRowView? = findPosterRow(host.root)

    private fun card(index: Int): TvNativeCardView? = row()?.findViewHolderForAdapterPosition(index)?.itemView as? TvNativeCardView

    /** 此刻画成聚焦态 (放大): 按住着, 或聚焦动画已到终点. */
    private fun drawnFocused(card: TvNativeCardView): Boolean = card.focusLookHeld || card.focusProgress == 1f

    /** 进行、走到第 2 张、按确定 (导航出去), 焦点交给页面以外, 页面离开组合. */
    private fun clickThirdCardAndLeave() {
        assertTrue(ValueAnimator.areAnimatorsEnabled(), "要开着系统动画跑: 关着时状态动画当场到位, 测不出先小后大")
        host.onMain { rowAnchor.requestFocus() }
        host.waitUntil("焦点进了行首那张") { card(0)?.isFocused == true }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("第 2 张聚焦到位") { card(2)?.isFocused == true && card(2)?.focusProgress == 1f }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(2), clicked)
        host.onMain {
            outside.requestFocus()
            shown = false
        }
        host.waitUntil("页面离开组合") { row() == null }
    }

    /** 返回: 页面重建, 等第 2 张排出来 (焦点还没送回来). */
    private fun comeBack(): TvNativeCardView {
        host.onMain { shown = true }
        host.waitUntil("行重建, 第 2 张排出来") { card(2) != null }
        return host.onMain { card(2)!! }
    }

    @Test
    fun `the clicked card is drawn focused from its first frame after the row is rebuilt`() {
        launch(holdFocusLookOnClick = true)
        clickThirdCardAndLeave()
        val card = comeBack()
        host.onMain {
            assertFalse(card.isFocused)
            assertTrue(drawnFocused(card), "重建出来的第一帧按未聚焦画了 (焦点回来时会再放大一遍)")
            assertFalse(card(1)!!.focusLookHeld, "只按住点过的那张")
        }
        // 页面把焦点送回来: 按住放开, 画面不变 (聚焦动画不从 0 再走一遍)
        host.onMain { rowAnchor.requestFocus() }
        host.waitUntil("焦点回到第 2 张") { card(2)?.isFocused == true }
        val (progress, held) = host.onMain { card(2)!!.focusProgress to card(2)!!.focusLookHeld }
        assertEquals(1f, progress, "焦点回来时聚焦动画从头走了一遍")
        assertEquals(false, held)
    }

    @Test
    fun `a strip that does not hold on click rebuilds with every card unfocused`() {
        // 人物预览弹窗: 点作品先关掉弹窗再跳转, 返回时焦点不回这一行, 不能留一张一直放大着的卡
        launch(holdFocusLookOnClick = false)
        clickThirdCardAndLeave()
        val card = comeBack()
        host.onMain {
            assertFalse(card.focusLookHeld)
            assertEquals(0f, card.focusProgress)
        }
    }
}

private fun findPosterRow(v: View): TvNativeRowView? = when (v) {
    is TvNativeRowView -> v
    is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findPosterRow(v.getChildAt(it)) }
    else -> null
}
