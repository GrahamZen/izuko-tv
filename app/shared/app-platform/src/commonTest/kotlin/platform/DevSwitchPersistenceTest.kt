/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevSwitchPersistenceTest {
    private class MapStore : DevSwitchStore {
        val values = mutableMapOf<String, String>()
        override fun load(key: String): String? = values[key]
        override fun save(key: String, raw: String) {
            values[key] = raw
        }
    }

    private val store = MapStore()

    @AfterTest
    fun detach() {
        DevSwitches.attachStore(MapStore())
    }

    @Test
    fun `persistent switch saves what it is set to`() {
        DevSwitches.attachStore(store)
        val switch = NumberSwitch("poolForTest", "线程数", default = 0f, min = 0f, max = 16f, persistent = true)
        switch.set("8")
        assertEquals(8f, switch.value)
        assertEquals("8", store.values["poolForTest"])
    }

    @Test
    fun `invalid value is neither applied nor saved`() {
        DevSwitches.attachStore(store)
        val switch = NumberSwitch("poolForTest", "线程数", default = 0f, min = 0f, max = 16f, persistent = true)
        switch.set("99")
        assertEquals(0f, switch.value)
        assertNull(store.values["poolForTest"])
    }

    @Test
    fun `memory only switch is not saved`() {
        DevSwitches.attachStore(store)
        val switch = ToggleSwitch("blurForTest", "模糊")
        switch.set("1")
        assertEquals(true, switch.value)
        assertNull(store.values["blurForTest"])
    }

    @Test
    fun `restored value is applied without saving again`() {
        DevSwitches.attachStore(store)
        val switch = ToggleSwitch("preloadForTest", "预读", persistent = true)
        switch.restore("1")
        assertEquals(true, switch.value)
        assertNull(store.values["preloadForTest"])
    }

    @Test
    fun `attaching a store restores declared persistent switches`() {
        val persistent = DevSwitches.all.filter { it.persistent }
        for (switch in persistent) store.values[switch.key] = "1"
        DevSwitches.attachStore(store)
        for (switch in persistent) {
            val expected: Any = when (switch) {
                is ToggleSwitch -> true
                is NumberSwitch -> 1f
                is ChoiceSwitch -> switch.value // "1" 不是合法选项, 保持原样
            }
            assertEquals(expected, switch.value, switch.key)
        }
        // 复原, 免得影响别的测试
        for (switch in persistent) switch.restore(if (switch is ToggleSwitch) "" else switch.default.toString())
    }
}
