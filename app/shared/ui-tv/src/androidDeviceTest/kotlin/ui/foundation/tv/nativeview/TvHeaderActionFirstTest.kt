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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.focus.tvBringIntoViewOnFocus
import me.him188.ani.app.ui.foundation.focus.tvContainDirectionalKeys
import me.him188.ani.app.ui.foundation.focus.tvHeaderActionFirst
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.subject.person.PeopleRowItem
import me.him188.ani.app.ui.subject.person.PosterRowItem
import me.him188.ani.app.ui.subject.person.TvPeoplePreviewRows
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * 标题行右边的按钮夹在两排原生行之间 ([tvHeaderActionFirst], 人物 / 角色预览弹窗与整页的「查看全部」): 按几何找焦点时按钮不在卡片的正上 /
 * 正下方, 没有它的话上下键直接在两排之间跳, 按钮够不着. 照弹窗搭: 560dp 宽的一列, 上面圆头像行, 中间「出演作品」标题行右边一颗按钮,
 * 下面海报行. 从上往下的顺序是 圆头像行 → 按钮 → 海报行, 往上反过来.
 */
class TvHeaderActionFirstTest {
    private val host = TvNativeTestHost()
    private val viewAll = FocusRequester()
    private var viewAllFocused = false
    private lateinit var compose: ComposeView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(
                    LocalSketch provides host.sketch,
                    LocalThemeSettings provides ThemeSettings.Default,
                ) {
                    // 外面再套一层: 窗口根上的那一层拿到的是整个窗口的定宽约束, 在它上面设宽度不起作用
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(560.dp).fillMaxHeight().tvContainDirectionalKeys()) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Column(Modifier.tvBringIntoViewOnFocus()) {
                                    Text("声优")
                                    TvPeoplePreviewRows.PeopleRow(
                                        List(8) { PeopleRowItem(imageUrl = null, name = "人物 $it", subtitle = "") },
                                        onClick = {},
                                        onBind = {},
                                        contentPadding = 16.dp,
                                        modifier = Modifier,
                                    )
                                }
                                Column(Modifier.tvBringIntoViewOnFocus()) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("出演作品", Modifier.weight(1f))
                                        // 同「查看全部」: 标题行右边
                                        Box(
                                            Modifier.size(96.dp, 40.dp)
                                                .focusRequester(viewAll)
                                                .onFocusChanged { viewAllFocused = it.isFocused }
                                                .focusable(),
                                        )
                                    }
                                    Box(Modifier.tvHeaderActionFirst(viewAll, { viewAllFocused })) {
                                        TvPeoplePreviewRows.PosterRow(
                                            List(10) { PosterRowItem(imageUrl = null, title = "作品 $it", subtitle = "主角") },
                                            onClick = {},
                                            onBind = {},
                                            contentPadding = 16.dp,
                                            modifier = Modifier,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        host.waitUntil("两行都排出来") { rows().size == 2 && rows().all { it.childCount > 0 } }
        // 焦点先放在圆头像行的第一格
        host.onMain { peopleRow().getChildAt(0).requestFocus() }
        host.waitUntil("焦点在圆头像行") { focusedIndex(peopleRow()) == 0 }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun rows(): List<TvNativeStripView> = findStrips(host.root)
    private fun peopleRow() = rows().first { it is TvNativeMonogramRowView }
    private fun posterRow() = rows().first { it is TvNativeRowView }
    private fun focusedIndex(row: TvNativeStripView): Int = row.focusedChild?.let { row.getChildAdapterPosition(it) } ?: -1

    @Test
    fun `down from the row above lands on the header button before the cards`() {
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("按下先落到标题行的按钮") { viewAllFocused }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("再按下进海报行") { focusedIndex(posterRow()) == 0 }
    }

    @Test
    fun `up from the cards goes back through the header button`() {
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("落到按钮") { viewAllFocused }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("进海报行") { focusedIndex(posterRow()) == 0 }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("按上先回到按钮") { viewAllFocused }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("再按上回圆头像行") { focusedIndex(peopleRow()) == 0 }
    }
}

private fun findStrips(v: View): List<TvNativeStripView> = when (v) {
    is TvNativeStripView -> listOf(v)
    is ViewGroup -> (0 until v.childCount).flatMap { findStrips(v.getChildAt(it)) }
    else -> emptyList()
}
