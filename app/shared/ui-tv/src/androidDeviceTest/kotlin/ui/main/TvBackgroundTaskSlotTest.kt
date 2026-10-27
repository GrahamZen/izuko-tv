/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.ui.foundation.LONG_PRESS_MIN_HOLD
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_background_task_next
import org.jetbrains.compose.resources.getString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 动作面板顶上的后台任务那一格 ([TvBackgroundTaskSlot]): 任务本身 | ✕ 两块, 左右在块之间走, 哪一块按下都回卡片;
 * 任务本身按确定换下一个、到头回第一个, 长按打开; ✕ 停下任务, 聚焦着的那块没了时另一块接住, 都不能聚焦就交回卡片.
 */
class TvBackgroundTaskSlotTest {
    private val host = TvNativeTestHost()
    private val card = FocusRequester()
    private lateinit var compose: ComposeView
    private val tasks = mutableStateOf(listOf<TvBackgroundTask>())
    private var label: String? = null
    private var labelDanger = false
    private var cardFocused = false
    private var opened = 0
    private var stopped = 0

    private val move = TvBackgroundTask(
        "move", "搬缓存", "1/3", 0.3f,
        open = { opened++ }, hint = "长按去设置看",
        stop = { stopped++ }, stopLabel = "停止搬缓存",
    )
    private val import = TvBackgroundTask("import", "导入收藏", "5/20", 0.25f)
    private val migration = TvBackgroundTask("migration", "换电视", null, null, stop = { stopped++ }, stopLabel = "停止换电视")

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                Column {
                    TvBackgroundTaskSlot(
                        tasks.value,
                        onFocused = { text, danger ->
                            label = text
                            labelDanger = danger
                        },
                        onFocusedRowGone = { card.requestFocus() },
                        partModifier = Modifier.focusProperties { down = card },
                    )
                    // 同面板里的播放卡片: 与格子同宽, 在它正下方
                    Box(Modifier.fillMaxWidth().height(80.dp).focusRequester(card).onFocusChanged { cardFocused = it.isFocused }.focusable())
                }
            }
        }
        host.waitUntil("焦点落到卡片上") { runCatching { card.requestFocus() }.isSuccess && cardFocused }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `confirm on the task shows the next task and wraps around`() {
        setTasks(move, import, migration)
        focusPart(TV_BACKGROUND_TASK_TAG_BODY)
        assertShown("搬缓存")
        host.waitUntil("标签写按确定看下一个与长按去哪") { label == "${nextLabel(1, 3)} · 长按去设置看" }

        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertShown("导入收藏")
        assertFocused(TV_BACKGROUND_TASK_TAG_BODY)
        host.waitUntil("打不开的任务, 标签只写看下一个") { label == nextLabel(2, 3) }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertShown("换电视")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertShown("搬缓存")
        assertFocused(TV_BACKGROUND_TASK_TAG_BODY)
        assertEquals(0, host.onMain { opened }, "短按不打开")
    }

    @Test
    fun `holding confirm on the task opens it and keeps it shown`() {
        setTasks(move, import)
        focusPart(TV_BACKGROUND_TASK_TAG_BODY)
        longPressConfirm()
        host.waitUntil("长按打开任务") { opened == 1 }
        assertShown("搬缓存")
    }

    @Test
    fun `a short press on a single task does nothing`() {
        setTasks(move)
        focusPart(TV_BACKGROUND_TASK_TAG_BODY)
        host.waitUntil("只有一个时标签只写长按去哪") { label == "长按去设置看" }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.instrumentation.waitForIdleSync()
        assertShown("搬缓存")
        assertEquals(0, host.onMain { opened })
    }

    @Test
    fun `left and right move between the task and the stop button`() {
        setTasks(move, import)
        focusPart(TV_BACKGROUND_TASK_TAG_BODY)

        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused(TV_BACKGROUND_TASK_TAG_STOP)
        host.waitUntil("✕ 聚焦时标签写停止, 用错误色") { label == "停止搬缓存" && labelDanger }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused(TV_BACKGROUND_TASK_TAG_STOP)

        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertFocused(TV_BACKGROUND_TASK_TAG_BODY)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertFocused(TV_BACKGROUND_TASK_TAG_BODY)
    }

    @Test
    fun `down goes to the card from every part`() {
        setTasks(move, import)
        for (tag in listOf(TV_BACKGROUND_TASK_TAG_BODY, TV_BACKGROUND_TASK_TAG_STOP)) {
            focusPart(tag)
            host.press(KeyEvent.KEYCODE_DPAD_DOWN)
            host.waitUntil("从 $tag 按下回卡片") { cardFocused }
        }
    }

    @Test
    fun `the stop button stops the task and focus moves on when it disappears`() {
        setTasks(move, migration, import)
        focusPart(TV_BACKGROUND_TASK_TAG_STOP)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按 ✕ 停下任务") { stopped == 1 }

        // 搬缓存停下了: 换到原位置上的换电视, 它也能停, 焦点留在 ✕ 上
        setTasks(migration, import)
        assertShown("换电视")
        assertFocused(TV_BACKGROUND_TASK_TAG_STOP)

        // 换电视进了写入阶段 (停不了): ✕ 没了, 任务本身接住 (不止一个任务, 能按确定看下一个)
        setTasks(TvBackgroundTask("migration", "换电视", null, null), import)
        assertFocused(TV_BACKGROUND_TASK_TAG_BODY)

        // 只剩导入收藏 (打不开也停不了): 格子里没有能聚焦的, 交回卡片
        setTasks(import)
        host.waitUntil("交回卡片") { cardFocused }
    }

    @Test
    fun `focus moves to the task when the stop button goes away`() {
        setTasks(move)
        focusPart(TV_BACKGROUND_TASK_TAG_STOP)
        setTasks(TvBackgroundTask("move", "搬缓存", "2/3", 0.6f, open = { opened++ }, hint = "长按去设置看"))
        assertFocused(TV_BACKGROUND_TASK_TAG_BODY)
    }

    @Test
    fun `a single task that can neither open nor stop takes no focus`() {
        setTasks(import)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.instrumentation.waitForIdleSync()
        assertEquals(true, host.onMain { cardFocused })
    }

    /** 换任务列表, 等格子按新列表画好 (重组在下一帧, 主线程空闲时不一定画好了; 没画好就按键, 上面还没有能聚焦的东西). */
    private fun setTasks(vararg list: TvBackgroundTask) {
        host.onMain { tasks.value = list.toList() }
        val titles = list.map { it.title }
        host.waitUntil("格子按新的任务列表画好") {
            val shown = shownTitles()
            shown.size == (if (list.isEmpty()) 0 else 1) && shown.all { it in titles }
        }
    }

    /** 从卡片按上进格子, 先走到最左边, 再往右走到 [tag] 那一块. */
    private fun focusPart(tag: String) {
        if (!host.onMain { cardFocused }) {
            host.onMain { card.requestFocus() }
            host.waitUntil("先回到卡片") { cardFocused }
        }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("在卡片上按上, 焦点进格子") { focusedTag() != null }
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        if (host.onMain { focusedTag() } != tag) host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused(tag)
    }

    /** 按住确认键过长按阈值 (第二发且距按下 [LONG_PRESS_MIN_HOLD] 以上), 再连发两下, 抬起. */
    private fun longPressConfirm() {
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(LONG_PRESS_MIN_HOLD.inWholeMilliseconds + 100)
        for (repeat in 1..3) {
            host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = repeat)
            SystemClock.sleep(50)
        }
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun assertFocused(tag: String) {
        host.waitUntil("焦点在 $tag 上") { focusedTag() == tag }
    }

    private fun assertShown(title: String) {
        host.waitUntil("格子里显示 $title") { shownTitles() == listOf(title) }
    }

    private fun nextLabel(index: Int, total: Int): String = runBlocking { getString(Lang.tv_background_task_next, index, total) }

    /** 格子里此刻画出来的任务名 (同时只该有一个). */
    private fun shownTitles(): List<String> {
        val titles = setOf(move.title, import.title, migration.title)
        return nodes().flatMap { it.texts() }.filter { it in titles }
    }

    /** 持焦的那一块的 testTag; 焦点不在格子里时为 null. */
    private fun focusedTag(): String? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.config?.getOrNull(SemanticsProperties.TestTag)

    /** 整棵语义树 (未合并), 在主线程上调. */
    private fun nodes(): List<SemanticsNode> {
        val root = (compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
        return listOf(root) + root.descendants()
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
