/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 详情弹窗底下那一排按钮 ([TvDetailsBody]; 选源面板条目的详情等用它): 打开时焦点在最右那颗, 上下键换一条时焦点留在同一格 (同一个节点, 只换字),
 * 那一格没了回最右那颗; 一格轮流写几种字时照最长的留宽, 换一条整排不挪; 换到信息少的一条时按钮不跟着上移.
 *
 * 每一条的按钮 ([ITEMS]): 0 = 播放 · 更换资源 · 下载, 1 = 播放 · 更换资源 · 删除下载, 2 = 只有取消下载, 3 = 没有按钮, 4 = 同 0.
 * 只有第 0 条有几行信息 ([TALL_FIELDS]).
 */
class TvDetailsBodyFocusTest {
    private val host = TvNativeTestHost()
    private lateinit var compose: ComposeView
    private var item by mutableIntStateOf(0)

    /** 正文此刻多高 (像素) */
    private var bodyHeight = 0

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                // 同应用里: 按钮外面有一圈 48dp 的最小可点区域, 没按钮的一条要照它留高
                MaterialTheme {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 48.dp) {
                        TvDetailsBody(
                            title = "item $item",
                            position = "${item + 1} / ${ITEMS.size}",
                            subtitle = "",
                            badges = emptyList(),
                            fields = if (item == 0) TALL_FIELDS else emptyList(),
                            note = "",
                            buttons = buttonsOf(item),
                            onNavigate = { item = (item + it).coerceIn(0, ITEMS.lastIndex) },
                            modifier = Modifier.onSizeChanged { bodyHeight = it.height },
                        )
                    }
                }
            }
        }
        host.waitUntil("焦点落到最右那颗") { focused() != null }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `opens with the focus on the rightmost button`() {
        assertEquals(DOWNLOAD, settledLabel())
    }

    @Test
    fun `switching items keeps the focus on the same slot`() {
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        val replace = settledFocus()
        assertEquals(REPLACE, labelOf(replace))
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("换到下一条") { item == 1 }
        assertEquals(replace.id, settledFocus().id, "换一条时焦点换到了新建的按钮上")

        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        val toggle = settledFocus()
        assertEquals(DELETE, labelOf(toggle))
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("换回上一条") { item == 0 }
        val back = settledFocus()
        assertEquals(toggle.id, back.id, "同一格换了字以后焦点换了节点")
        assertEquals(DOWNLOAD, labelOf(back))
    }

    @Test
    fun `a missing slot falls back to the rightmost button`() {
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(PLAY, settledLabel())
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("换到第二条") { item == 1 }
        assertEquals(PLAY, settledLabel())
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("换到只有一颗的那条") { item == 2 }
        assertEquals(CANCEL, settledLabel())
    }

    @Test
    fun `up and down still work on an item without buttons`() {
        repeat(3) { host.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        host.waitUntil("换到没有按钮的那条") { item == 3 }
        assertEquals("", settledLabel(), "没有按钮时焦点应停在隐形落点上")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("换到下一条") { item == 4 }
        assertEquals(DOWNLOAD, settledLabel())
    }

    @Test
    fun `a slot keeps its width when its label changes`() {
        val download = boundsOf(DOWNLOAD)
        val play = boundsOf(PLAY)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("换到下一条") { item == 1 }
        settledFocus()
        val delete = boundsOf(DELETE)
        assertEquals(download.width, delete.width, "下载那一格换字后变了宽")
        assertEquals(play.left, boundsOf(PLAY).left, "换一条后左边的按钮挪了")
    }

    @Test
    fun `the buttons stay put when the next item has less to show`() {
        val before = boundsOf(DOWNLOAD)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("换到下一条") { item == 1 }
        settledFocus()
        assertEquals(before.top, boundsOf(DELETE).top, "换到信息少的一条后按钮上移了")
    }

    @Test
    fun `the body keeps its height on an item without buttons`() {
        val before = host.onMain { bodyHeight }
        repeat(3) { host.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        host.waitUntil("换到没有按钮的那条") { item == 3 }
        settledFocus()
        assertEquals(before, host.onMain { bodyHeight }, "没有按钮的一条弹窗变矮了")
    }

    /** 等换完的那几帧画完, 返回聚焦的节点 */
    private fun settledFocus(): SemanticsNode {
        Thread.sleep(300)
        host.instrumentation.waitForIdleSync()
        return assertNotNull(host.onMain { focused() }, "没有东西聚焦")
    }

    private fun settledLabel(): String = labelOf(settledFocus())

    private fun nodes(): List<SemanticsNode> {
        val root = compose.getChildAt(0) as? RootForTest ?: return emptyList()
        val all = mutableListOf<SemanticsNode>()
        val pending = ArrayDeque(listOf(root.semanticsOwner.unmergedRootSemanticsNode))
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            all += node
            pending.addAll(node.children)
        }
        return all
    }

    /** 聚焦的节点; 没有聚焦的为 null */
    private fun focused(): SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }

    /** 节点连同里面的字 */
    private fun labelOf(node: SemanticsNode): String = host.onMain {
        val texts = mutableListOf<String>()
        val pending = ArrayDeque(listOf(node))
        while (pending.isNotEmpty()) {
            val n = pending.removeFirst()
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            pending.addAll(n.children)
        }
        texts.joinToString("")
    }

    /** 写着 [label] 的那颗按钮 (能聚焦的那一层) 在窗口里的位置 */
    private fun boundsOf(label: String): Rect = host.onMain {
        val button = nodes().first { it.config.contains(SemanticsProperties.Focused) && labelOfUnsafe(it) == label }
        button.boundsInRoot
    }

    private fun labelOfUnsafe(node: SemanticsNode): String =
        node.children.joinToString("") { child -> (child.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "") + labelOfUnsafe(child) }

    private fun buttonsOf(item: Int): List<TvDetailsButton> = ITEMS[item].map { (key, label) ->
        TvDetailsButton(label, key = key, danger = label == DELETE, widthLabels = if (key == TOGGLE) TOGGLE_LABELS else emptyList()) {}
    }

    private companion object {
        const val PLAY = "播放"
        const val REPLACE = "更换资源…"
        const val DOWNLOAD = "下载"
        const val CANCEL = "取消下载"
        const val DELETE = "删除下载"
        const val TOGGLE = "toggle"
        val TOGGLE_LABELS = listOf(DOWNLOAD, CANCEL, DELETE)

        /** 第 0 条的信息: 几行长短不定的, 比别的条目高 */
        val TALL_FIELDS = List(3) { TvSourceDetailField("label $it", "value $it") }

        /** 每一条的按钮: (那一格, 字), 从左到右 */
        val ITEMS = listOf(
            listOf("play" to PLAY, "replace" to REPLACE, TOGGLE to DOWNLOAD),
            listOf("play" to PLAY, "replace" to REPLACE, TOGGLE to DELETE),
            listOf(TOGGLE to CANCEL),
            emptyList(),
            listOf("play" to PLAY, "replace" to REPLACE, TOGGLE to DOWNLOAD),
        )
    }
}
