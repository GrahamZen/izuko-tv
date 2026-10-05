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
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.platform.ChoiceSwitch
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.app.platform.NumberSwitch
import me.him188.ani.app.platform.ToggleSwitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Web 控制台「设置 → 调试」里的调试开关 (见 [RemoteDebugSettings]): 网页卡片要的字段, 以及网页改值时的检查.
 */
class RemoteDebugSettingsTest {
    @Test
    fun `switches are described with their editor`() {
        val toggle = RemoteDebugSettings.describe(ToggleSwitch("blur", "模糊", default = true))
        assertEquals("dev.blur", toggle.string("key"))
        assertEquals("toggle", toggle.string("editor"))
        assertTrue(toggle["value"]!!.jsonPrimitive.boolean)

        val number = RemoteDebugSettings.describe(NumberSwitch("edge", "边距", default = 48f, min = 0f, max = 160f))
        assertEquals("number", number.string("editor"))
        assertEquals(48f, number["value"]!!.jsonPrimitive.float)
        assertEquals(160f, number["max"]!!.jsonPrimitive.float)
        assertEquals("any", number.string("step"), "网页数字框要收小数")

        val choice = RemoteDebugSettings.describe(ChoiceSwitch("mode", "模式", listOf("a", "b")))
        assertEquals("choice", choice.string("editor"))
        assertEquals("a", choice.string("value"))
        assertEquals(listOf("a", "b"), choice["choices"]!!.jsonArray.map { it.jsonObject.string("value") })
    }

    @Test
    fun `setting a switch checks the value`() {
        val edge = NumberSwitch("edge", "边距", default = 48f, min = 0f, max = 160f)
        val blur = ToggleSwitch("blur", "模糊")
        val switches = listOf(edge, blur)

        assertTrue(RemoteDebugSettings.setSwitch(switches, "edge", " 12.5 ").ok)
        assertEquals(12.5f, edge.value)
        assertFalse(RemoteDebugSettings.setSwitch(switches, "edge", "999").ok)
        assertFalse(RemoteDebugSettings.setSwitch(switches, "edge", "abc").ok)
        assertEquals(12.5f, edge.value, "不合法的值不改")

        assertTrue(RemoteDebugSettings.setSwitch(switches, "blur", "1").ok)
        assertTrue(blur.value)
        assertTrue(RemoteDebugSettings.setSwitch(switches, "blur", "").ok)
        assertFalse(blur.value)

        assertFalse(RemoteDebugSettings.setSwitch(switches, "nope", "1").ok)
    }

    /** 声明写错 (重名、写在登记表初始化之前) 时 DevSwitches 初始化就会失败, 在这里暴露. */
    @Test
    fun `declared switches have unique keys`() {
        val keys = DevSwitches.all.map { it.key }
        assertEquals(keys.distinct(), keys)
    }

    private fun JsonObject.string(name: String): String = this[name]!!.jsonPrimitive.content

    private val JsonObject.ok: Boolean get() = this["ok"]!!.jsonPrimitive.boolean
}
