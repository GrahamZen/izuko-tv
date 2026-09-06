/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusRail
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusRailKeys
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.TV_NAV_SETTLE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvRailWallSelection
import me.him188.ani.app.ui.foundation.tv.rememberTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.rememberTvRailWallSelection
import me.him188.ani.app.ui.foundation.tv.tvNavKeyInterceptor
import me.him188.ani.app.ui.subject.collection.TvCollectionGlassRailTab
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 胶囊行 + 海报墙的选中规则 ([TvRailWallSelection], 追番页的分类标签、新番时间表的日期): 胶囊跟着每一发连发走, 海报墙等方向键松开才换,
 * 按住走过好几项也只换一次; 单按松手就换; 按下键进墙时胶囊上的选择当场上墙; 点按当场换. 海报墙用一个数代替, 记下它换过的每一次.
 */
class TvRailWallSelectionTest {
    private val host = TvNativeTestHost()
    private lateinit var focusScope: TvFocusScope
    private lateinit var selection: TvRailWallSelection<Int>
    private var focusedItem by mutableIntStateOf(-1)
    private var wall by mutableIntStateOf(0)

    /** 海报墙换过的每一次 (只在主线程读写). */
    private val wallChanges = mutableListOf<Int>()
    private var committedOnDown: Boolean? = null

    private data class ItemKey(val index: Int) : TvFocusKey

    @BeforeTest
    fun setUp() = host.launch()

    @AfterTest
    fun tearDown() = host.close()

    /** 摆出一行 [ITEMS] 枚胶囊, 下键进墙 = commit; 焦点先落在第 0 项. 方向键跟踪器挂在最外层, 同 TV 壳根部. */
    private fun show() {
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                val keys = rememberTvNavKeyTracker()
                CompositionLocalProvider(
                    LocalTvNavKeyTracker provides keys,
                    LocalThemeSettings provides ThemeSettings.Default,
                ) {
                    val scope = rememberTvFocusScope()
                    val s = rememberTvRailWallSelection(
                        wall = { wall },
                        showOnWall = { item, _ ->
                            wallChanges += item
                            wall = item
                        },
                    )
                    val rail = rememberTvFocusRail(
                        scope = scope,
                        keyAt = { ItemKey(it) },
                        onMove = { runCatching { scope.requesterOf(ItemKey(it)).requestFocus() } },
                    )
                    focusScope = scope
                    selection = s
                    Row(
                        Modifier.tvNavKeyInterceptor(keys).tvFocusRailKeys(
                            state = rail,
                            itemCount = { ITEMS },
                            onNavigateDown = {
                                committedOnDown = s.commit()
                                true
                            },
                        ),
                    ) {
                        repeat(ITEMS) { i ->
                            TvCollectionGlassRailTab(
                                rail = rail,
                                index = i,
                                item = i,
                                selection = s,
                                label = "第 $i 项",
                                onFocusChanged = { if (it) focusedItem = i },
                            )
                        }
                    }
                }
            }
        }
        host.waitUntil("行排出来") { this::focusScope.isInitialized }
        host.onMain { focusScope.requesterOf(ItemKey(0)).requestFocus() }
        host.waitUntil("焦点在第 0 项") { focusedItem == 0 }
    }

    @Test
    fun `holding right moves the capsules at every repeat and the wall follows once after release`() {
        show()
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("到第 1 项") { focusedItem == 1 }
        for (repeat in 1..2) {
            host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = repeat)
            host.waitUntil("到第 ${repeat + 1} 项") { focusedItem == repeat + 1 }
        }
        assertEquals(3, host.onMain { selection.selected })
        // 走到最后一项还按着 (连发照来): 过了连发的静默期也不换
        for (repeat in 3..6) {
            Thread.sleep(100)
            host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = repeat)
        }
        assertEquals(0, host.onMain { wall })
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("松手后海报墙换到第 3 项") { wall == 3 }
        assertEquals(listOf(3), host.onMain { wallChanges.toList() })
    }

    @Test
    fun `a single press moves the wall on release`() {
        show()
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("到第 1 项") { focusedItem == 1 }
        Thread.sleep(150)
        assertEquals(0, host.onMain { wall }, "按着还没松, 海报墙不该换")
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("松手后海报墙换到第 1 项") { wall == 1 }
        assertEquals(listOf(1), host.onMain { wallChanges.toList() })
    }

    @Test
    fun `down while the wall still waits puts the selection on the wall at once`() {
        show()
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("到第 1 项") { focusedItem == 1 }
        // 右键还按着 (海报墙在等抬起) 就按下键: 进墙之前当场换上
        host.keyDown(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(true, host.onMain { committedOnDown })
        assertEquals(1, host.onMain { wall })
        host.keyUp(KeyEvent.KEYCODE_DPAD_DOWN)
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        Thread.sleep(TV_NAV_SETTLE_MILLIS + 100)
        assertEquals(listOf(1), host.onMain { wallChanges.toList() }, "松手后不该再换一次")
    }

    @Test
    fun `confirming a capsule switches the wall at once`() {
        show()
        // 焦点不经本行左右键送过去 (不算聚焦即选中), 再按确定
        host.onMain { focusScope.requesterOf(ItemKey(2)).requestFocus() }
        host.waitUntil("焦点在第 2 项") { focusedItem == 2 }
        assertEquals(0, host.onMain { selection.selected })
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(2, host.onMain { wall })
        assertEquals(2, host.onMain { selection.selected })
    }

    private companion object {
        const val ITEMS = 4
    }
}
