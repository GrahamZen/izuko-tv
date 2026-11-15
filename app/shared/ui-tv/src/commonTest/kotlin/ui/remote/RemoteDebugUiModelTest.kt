/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 调试接口 `api/debug/ui` 在读到的界面上算的东西: 同一排、按文字找、停没停稳、回答的样子 (见 RemoteDebugUiModel.kt). */
class RemoteDebugUiModelTest {
    private fun element(
        text: String,
        left: Int,
        top: Int,
        width: Int = 100,
        height: Int = 40,
        interactive: Boolean = true,
        focused: Boolean = false,
        selected: Boolean = false,
        desc: String? = null,
        group: Any? = null,
    ) = DebugUiElement(
        text = text,
        bounds = DebugUiBounds(left, top, left + width, top + height),
        source = "compose",
        desc = desc,
        interactive = interactive,
        focused = focused,
        selected = selected,
        group = group,
    )

    private fun window(vararg elements: DebugUiElement, kind: String = "activity", focused: Boolean = true) =
        DebugUiWindow(kind, title = null, focused = focused, bounds = DebugUiBounds(0, 0, 1920, 1080), elements = elements.toList())

    /** 一个订阅确认那样的弹窗: 标题、一排三档 (第二档聚焦、第一档选中)、底下两颗按钮. */
    private fun dialog(focused: Boolean = true): DebugUiWindow = window(
        element("订阅「番名」", 600, 300, width = 400, interactive = false),
        element("不需要", 600, 400, selected = true),
        element("新剧集", 720, 404, focused = true),
        element("全部剧集", 840, 400),
        element("每集播出后自动下载", 600, 460, width = 400, interactive = false),
        element("取消", 600, 560),
        element("订阅", 720, 560),
        kind = "dialog",
        focused = focused,
    )

    @Test
    fun `the row of the focused element is what overlaps it vertically - left to right`() {
        val dialog = dialog()
        val focus = dialog.elements.first { it.focused }
        assertEquals(listOf("不需要", "新剧集", "全部剧集"), debugUiRow(dialog.elements, focus).map { it.text })
    }

    @Test
    fun `text that cannot take focus is not part of a row`() {
        val focus = element("开始下载", 600, 500, focused = true)
        val elements = listOf(element("左边一句说明", 300, 500, interactive = false), focus)
        assertEquals(listOf("开始下载"), debugUiRow(elements, focus).map { it.text })
    }

    @Test
    fun `a row stays inside the container of the focused element when it shares it with others`() {
        val focus = element("立即观看", 128, 448, group = "hero", focused = true)
        val elements = listOf(
            element("", 32, 428, width = 64, height = 64, group = "rail"),
            focus,
            element("新番时间表", 128, 520, group = "hero"),
            element("下一张", 400, 448, group = "hero"),
        )
        assertEquals(listOf("立即观看", "下一张"), debugUiRow(elements, focus).map { it.text }, "侧边栏的图标一样高, 不在这个容器里")
    }

    @Test
    fun `a row is found across the window when each element sits alone in its container`() {
        val focus = element("新剧集", 720, 400, group = "wrap2", focused = true)
        val elements = listOf(element("不需要", 600, 400, group = "wrap1"), focus, element("全部剧集", 840, 400, group = "wrap3"))
        assertEquals(listOf("不需要", "新剧集", "全部剧集"), debugUiRow(elements, focus).map { it.text })
    }

    @Test
    fun `a lone element does not pick up unnamed things or the other kind of ui at its height`() {
        val card = DebugUiElement("朱色假面", DebugUiBounds(128, 160, 377, 590), source = "view", interactive = true, focused = true, group = "grid")
        val elements = listOf(
            element("", 32, 268, width = 64, height = 64, desc = "Avatar", group = "rail"),
            element("", 32, 348, width = 64, height = 64, group = "rail"),
            card,
        )
        assertEquals(listOf("朱色假面"), debugUiRow(elements, card).map { it.text })
    }

    @Test
    fun `the head of a window in the brief answer cuts long text short`() {
        val long = "字".repeat(100)
        val json = DebugUiSnapshot(listOf(window(element(long, 0, 0, interactive = false)))).toJson(full = false, toasts = emptyList())
        val head = json["windows"]!!.jsonArray.single().jsonObject["head"]!!.jsonArray.single().jsonPrimitive.content
        assertEquals(61, head.length)
        assertTrue(head.endsWith("…"))
    }

    @Test
    fun `finding by text puts whole matches before partial ones`() {
        val snapshot = DebugUiSnapshot(listOf(dialog()))
        assertEquals(listOf("订阅", "订阅「番名」"), debugUiFind(snapshot, "订阅").map { it.element.text })
        assertEquals(listOf("订阅"), debugUiFind(snapshot, "订阅", exact = true).map { it.element.text })
        assertTrue(debugUiFind(snapshot, "没有这段字").isEmpty())
    }

    @Test
    fun `finding by text looks at the window taking keys first and matches descriptions`() {
        val page = window(element("订阅", 100, 100), element("", 300, 100, desc = "设置"))
        val snapshot = DebugUiSnapshot(listOf(dialog(focused = false), page))
        assertEquals(listOf(1, 0), debugUiFind(snapshot, "订阅", exact = true).map { it.window })
        assertEquals(1, debugUiFind(snapshot, "设置").single().window)
    }

    @Test
    fun `the signature changes when something moves or changes state and not otherwise`() {
        val before = debugUiSignature(DebugUiSnapshot(listOf(dialog())))
        assertEquals(before, debugUiSignature(DebugUiSnapshot(listOf(dialog()))))
        val moved = window(element("订阅", 720, 561), kind = "dialog")
        val still = window(element("订阅", 720, 560), kind = "dialog")
        assertNotEquals(debugUiSignature(DebugUiSnapshot(listOf(still))), debugUiSignature(DebugUiSnapshot(listOf(moved))))
        val refocused = window(element("订阅", 720, 560, focused = true), kind = "dialog")
        assertNotEquals(debugUiSignature(DebugUiSnapshot(listOf(still))), debugUiSignature(DebugUiSnapshot(listOf(refocused))))
    }

    @Test
    fun `the console address on screen is given without its token`() {
        assertEquals("http://192.168.1.20:41893/<token>/", maskConsoleToken("http://192.168.1.20:41893/Ab3dEf9hIj/"))
        assertEquals("用手机打开 192.168.1.20:41893/<token>", maskConsoleToken("用手机打开 192.168.1.20:41893/Ab3dEf9hIj"))
        assertEquals("第 12 集 · 23:30 更新", maskConsoleToken("第 12 集 · 23:30 更新"))
    }

    @Test
    fun `the brief answer has the focus with its row and the head of each window`() {
        val page = window(element("探索", 100, 100), focused = false)
        val json = DebugUiSnapshot(listOf(dialog(), page)).toJson(full = false, toasts = listOf(DebugUiToast("已订阅", 1200)))
        assertTrue(json.bool("front"))
        val focus = json["focus"]!!.jsonObject
        assertEquals("新剧集", focus.string("text"))
        assertEquals(0, focus["window"]!!.jsonPrimitive.int)
        assertEquals(1, focus["rowIndex"]!!.jsonPrimitive.int)
        val row = focus["row"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("不需要", "新剧集", "全部剧集"), row.map { it.string("text") })
        assertTrue(row[0].bool("selected"))
        assertTrue(row[1].bool("focused"))
        val windows = json["windows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("dialog", "activity"), windows.map { it.string("kind") })
        assertEquals("订阅「番名」", windows[0]["head"]!!.jsonArray.first().jsonPrimitive.content)
        assertNull(windows[0]["elements"], "简略的回答不列全部元素")
        assertEquals("已订阅", json["toasts"]!!.jsonArray.single().jsonObject.string("text"))
    }

    @Test
    fun `the full answer lists every element with its state and position`() {
        val json = DebugUiSnapshot(listOf(dialog())).toJson(full = true, toasts = emptyList())
        val elements = json["windows"]!!.jsonArray.single().jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(7, elements.size)
        val chip = elements.first { it.string("text") == "不需要" }
        assertTrue(chip.bool("selected"))
        assertTrue(chip.bool("interactive"))
        assertEquals(listOf(600, 400, 700, 440), chip["bounds"]!!.jsonArray.map { it.jsonPrimitive.int })
        val title = elements.first { it.string("text") == "订阅「番名」" }
        assertNull(title["interactive"], "不能聚焦的字不带这一项")
    }

    @Test
    fun `nothing is focused when the app is not in front`() {
        val json = DebugUiSnapshot(listOf(dialog(focused = false))).toJson(full = false, toasts = emptyList())
        assertFalse(json.bool("front"))
        assertNull(json["focus"])
    }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.boolean
}
