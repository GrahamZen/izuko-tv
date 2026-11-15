/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.foundation.widgets.ToastHistory
import java.net.URLEncoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 控制台的通用界面接口 ([RemoteDebugUi]): 一页里上半是 Compose (标题、一排三颗胶囊、带令牌的控制台地址), 下半是原生视图 (一行字、两张卡).
 * 读得到两种内容与焦点所在; 送的按键同遥控器一样挪焦点, 按住时连发; 按文字点、等文字出现; Compose 的弹窗是压在上面的另一个窗口.
 */
class RemoteDebugUiTest {
    private val host = TvNativeTestHost()
    private val firstChip = FocusRequester()
    private val dialogButton = FocusRequester()
    private var showDialog by mutableStateOf(false)
    private var chipClicks = 0
    private var cardClicks = 0
    private var cardLongClicks = 0
    private lateinit var keyLog: KeyLogView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val column = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
            host.root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val compose = ComposeView(host.activity)
            column.addView(compose, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            compose.setContent {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(40.dp)) {
                        BasicText("自动下载")
                        Row {
                            Chip("不需要", Modifier.focusRequester(firstChip).semantics { selected = true })
                            Chip("新剧集")
                            Chip("全部剧集")
                        }
                        BasicText("http://192.168.1.20:41893/$TOKEN/")
                    }
                    if (showDialog) {
                        Dialog(onDismissRequest = { showDialog = false }) {
                            Box(Modifier.size(200.dp, 80.dp).focusRequester(dialogButton).clickable { showDialog = false }) { BasicText("知道了") }
                            LaunchedEffect(Unit) { dialogButton.requestFocus() }
                        }
                    }
                }
            }
            column.addView(TextView(host.activity).apply { text = "继续观看" })
            val cards = LinearLayout(host.activity)
            column.addView(cards, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 200))
            cards.addView(card("孤独摇滚", "第 3 集"), LinearLayout.LayoutParams(300, 160))
            keyLog = KeyLogView(host.activity)
            // 靠右放: 从胶囊往下走时正下方只有那张卡
            cards.addView(keyLog, LinearLayout.LayoutParams(300, 160).apply { leftMargin = 1200 })
        }
        host.waitUntil("焦点落到第一颗胶囊上") { runCatching { firstChip.requestFocus() }.isSuccess && focusText() == "不需要" }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Composable
    private fun Chip(label: String, modifier: Modifier = Modifier) {
        Box(modifier.size(160.dp, 60.dp).clickable { chipClicks++ }) { BasicText(label) }
    }

    private fun card(title: String, subtitle: String): View = LinearLayout(host.activity).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        contentDescription = title
        setOnClickListener { cardClicks++ }
        setOnLongClickListener {
            cardLongClicks++
            showDialog = true
            true
        }
        addView(TextView(context).apply { text = title })
        addView(TextView(context).apply { text = subtitle })
    }

    @Test
    fun `the screen is read as elements - compose and native - with the focused one marked`() {
        val json = get("api/debug/ui")
        assertTrue(json.bool("front"))
        val window = json["windows"]!!.jsonArray.single().jsonObject
        assertEquals("activity", window.string("kind"))
        val elements = window["elements"]!!.jsonArray.map { it.jsonObject }
        // 宿主页面自己的标题栏也读得到, 排在最前, 这里只看摆进去的内容
        val placed = listOf("自动下载", "不需要", "新剧集", "全部剧集", "继续观看", "孤独摇滚 · 第 3 集", "记按键")
        assertEquals(placed, elements.map { it.string("text") }.filter { it in placed }, "从上到下、从左到右")
        val chip = elements.first { it.string("text") == "不需要" }
        assertTrue(chip.bool("interactive") && chip.bool("focused") && chip.bool("selected"))
        assertEquals("compose", chip.string("source"))
        assertNull(elements.first { it.string("text") == "自动下载" }["interactive"], "标题不能聚焦")
        val card = elements.first { it.string("text") == "孤独摇滚 · 第 3 集" }
        assertEquals("view", card.string("source"))
        assertTrue(card.bool("interactive"), "卡里的两行字并成一个元素")
        assertNull(card["desc"], "描述与卡里的字重复时不另给")
        assertTrue(elements.any { it.string("text") == "继续观看" && it["interactive"] == null })
        val focus = json["focus"]!!.jsonObject
        assertEquals("不需要", focus.string("text"))
        assertEquals(listOf("不需要", "新剧集", "全部剧集"), focus["row"]!!.jsonArray.map { it.jsonObject.string("text") })
        assertEquals(0, focus["rowIndex"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the console address on screen never leaves with its token`() {
        val text = get("api/debug/ui").toString()
        assertFalse(TOKEN in text)
        assertTrue("http://192.168.1.20:41893/<token>/" in text)
    }

    @Test
    fun `keys through the system input channel move focus the way the remote does`() = keysMoveFocus("system")

    @Test
    fun `keys dispatched inside the app move focus the way the remote does`() = keysMoveFocus("app")

    /** 在 Compose 的一排里左右走, 往下进原生的那一行, 确定键点卡. */
    private fun keysMoveFocus(path: String) {
        val right = post("api/debug/ui/key", "keys" to "RIGHT", "path" to path)
        assertEquals(1, right["sent"]!!.jsonPrimitive.int)
        assertEquals(path, right.string("keyPath"))
        assertTrue(right.bool("settled"))
        assertEquals("新剧集", right["focus"]!!.jsonObject.string("text"))
        assertEquals(1, right["focus"]!!.jsonObject["rowIndex"]!!.jsonPrimitive.int)
        assertNull(right["windows"]!!.jsonArray.first().jsonObject["elements"], "按键的回答是简略的")

        val down = post("api/debug/ui/key", "keys" to "LEFT,DOWN", "path" to path)
        assertEquals(2, down["sent"]!!.jsonPrimitive.int)
        assertEquals("孤独摇滚 · 第 3 集", down["focus"]!!.jsonObject.string("text"), "往下进了原生的那一行")

        post("api/debug/ui/key", "keys" to "OK", "path" to path)
        host.waitUntil("确定键点了卡") { cardClicks == 1 }

        val up = post("api/debug/ui/key", "keys" to "UP", "path" to path)
        assertEquals("不需要", up["focus"]!!.jsonObject.string("text"), "往上回到 Compose 的那一排")
    }

    @Test
    fun `a key held through the system input channel repeats and its first repeat is the long press`() = holdRepeats("system")

    @Test
    fun `a key held inside the app repeats and its first repeat is the long press`() = holdRepeats("app")

    private fun holdRepeats(path: String) {
        host.onMain { keyLog.requestFocus() }
        post("api/debug/ui/key", "keys" to "OK@900", "path" to path)
        val events = host.onMain { keyLog.events.toList() }
        val downs = events.filter { it.action == KeyEvent.ACTION_DOWN }
        val seen = events.joinToString { "${if (it.action == KeyEvent.ACTION_DOWN) "down" else "up"}#${it.repeatCount}@${it.eventTime - it.downTime}" }
        assertEquals(KeyEvent.ACTION_UP, events.last().action, seen)
        assertEquals(1, events.count { it.action == KeyEvent.ACTION_UP }, seen)
        assertTrue(downs.size >= 4, "按住 0.9 秒: 按下之后还有连发, 实际: $seen")
        assertEquals(downs.indices.toList(), downs.map { it.repeatCount }, "连发的序号从 0 起逐个加一, 实际: $seen")
        assertEquals(listOf(1), downs.filter { it.isLongPress }.map { it.repeatCount }, "第一发连发带长按标记")
        assertTrue(events.all { it.downTime == events.first().downTime }, "同一次按住")
    }

    @Test
    fun `without a path the keys take the channel this android version allows`() {
        val json = post("api/debug/ui/key", "keys" to "RIGHT")
        val expected = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) "system" else "app"
        assertEquals(expected, json.string("keyPath"))
        assertEquals("新剧集", json["focus"]!!.jsonObject.string("text"))
        assertFalse(post("api/debug/ui/key", "keys" to "RIGHT", "path" to "adb").bool("ok"), "不认识的通道拒收")
    }

    @Test
    fun `clicking by text sends focus there and presses confirm`() {
        val json = post("api/debug/ui/click", "text" to "全部剧集")
        assertEquals("focus", json.string("via"))
        assertEquals("全部剧集", json["focus"]!!.jsonObject.string("text"))
        host.waitUntil("按了那颗胶囊") { chipClicks == 1 }

        val moved = post("api/debug/ui/click", "text" to "孤独摇滚", "press" to "0")
        assertEquals("孤独摇滚 · 第 3 集", moved["focus"]!!.jsonObject.string("text"), "包含这段字的元素")
        assertEquals(0, host.onMain { cardClicks }, "press=0 只送焦点")

        val missing = post("api/debug/ui/click", "text" to "没有这段字")
        assertFalse(missing.bool("ok"))
        val notFocusable = post("api/debug/ui/click", "text" to "继续观看")
        assertFalse(notFocusable.bool("ok"), "不能聚焦的字点不了")
    }

    @Test
    fun `a compose dialog is another window on top and takes the keys`() {
        for (path in listOf("system", "app")) {
            host.onMain { showDialog = true }
            assertTrue(post("api/debug/ui/wait", "text" to "知道了").bool("found"))
            host.waitUntil("焦点在弹窗的按钮上") { focusText() == "知道了" }
            val windows = get("api/debug/ui")["windows"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("dialog", "activity"), windows.map { it.string("kind") })
            assertTrue(windows[0].bool("focused"), "按键送到弹窗")
            assertFalse(windows[1].bool("focused"))

            post("api/debug/ui/key", "keys" to "OK", "path" to path)
            val gone = post("api/debug/ui/wait", "gone" to "知道了")
            assertTrue(gone.bool("found"), "确定键关了弹窗 ($path)")
            assertEquals(1, gone["windows"]!!.jsonArray.size)
        }
    }

    @Test
    fun `a hold that opens a dialog is one long press and not an error`() {
        for ((index, path) in listOf("system", "app").withIndex()) {
            assertTrue(post("api/debug/ui/click", "text" to "孤独摇滚", "press" to "0").bool("ok"))
            val held = post("api/debug/ui/key", "keys" to "OK@900", "expect" to "孤独摇滚", "path" to path)
            // 弹窗一出来就拿走了按键: 这一下的后半截同真遥控器 —— 卡片收到取消, 抬起落在弹窗上 (这里的弹窗没有吞残余按键的那一层,
            // 它的按钮理不理这个抬起由 Compose 定, 不在这条测试里断言)
            assertTrue(held.bool("ok"), "按住的中途弹窗拿走了按键, 这一下不算失败 ($path): ${held["message"]}")
            assertEquals(index + 1, cardLongClicks, "卡片收到一次长按 ($path)")
            assertEquals(0, cardClicks, "长按不算点了一下 ($path)")
            host.onMain { showDialog = false }
            assertTrue(post("api/debug/ui/wait", "gone" to "知道了").bool("found"))
        }
    }

    @Test
    fun `the back key closes a compose dialog on either channel`() {
        for (path in listOf("system", "app")) {
            host.onMain { showDialog = true }
            host.waitUntil("焦点在弹窗的按钮上") { focusText() == "知道了" }
            post("api/debug/ui/key", "keys" to "BACK", "path" to path)
            assertTrue(post("api/debug/ui/wait", "gone" to "知道了").bool("found"), "返回键关了弹窗 ($path)")
        }
    }

    @Test
    fun `waiting for text also sees a toast and gives up after the timeout`() {
        val absent = post("api/debug/ui/wait", "text" to "已订阅", "timeout" to "300")
        assertFalse(absent.bool("found"))
        ToastHistory.record("已订阅，正在查找版本")
        val shown = post("api/debug/ui/wait", "text" to "已订阅", "timeout" to "300")
        assertTrue(shown.bool("found"))
        assertEquals("已订阅，正在查找版本", shown["toasts"]!!.jsonArray.last().jsonObject.string("text"))
    }

    private fun focusText(): String? = RemoteDebugUiReader.read().focus()?.second?.text

    private fun get(path: String): JsonObject =
        assertNotNull(RemoteDebugUi.handle(LanHttpRequest("GET", path, query = "", body = ByteArray(0))))

    private fun post(path: String, vararg fields: Pair<String, String>): JsonObject {
        val body = fields.joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }
        return assertNotNull(RemoteDebugUi.handle(LanHttpRequest("POST", path, query = "", body = body.toByteArray())))
    }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.boolean == true

    /** 记下收到的每个按键事件的卡. */
    private class KeyLogView(context: Context) : FrameLayout(context) {
        val events = ArrayList<KeyEvent>()

        init {
            isFocusable = true
            addView(TextView(context).apply { text = "记按键" })
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            events += KeyEvent(event)
            return true
        }
    }

    private companion object {
        const val TOKEN = "Ab3dEf9hIj"
    }
}
