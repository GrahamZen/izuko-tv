/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.migration

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** 设置所在的 DataStore 整份交给新包: 每种值类型都要原样回来. */
class PreferenceEntriesTest {
    @Test
    fun `各种类型经 JSON 往返后原样还原`() {
        val bytes = byteArrayOf(0, 1, -2, 127)
        val original = mutablePreferencesOf().apply {
            set(stringPreferencesKey("settings"), """{"a":1,"中文":"值"}""")
            set(booleanPreferencesKey("flag"), true)
            set(intPreferencesKey("int"), -3)
            set(longPreferencesKey("long"), Long.MAX_VALUE)
            set(floatPreferencesKey("float"), 1.5f)
            set(doublePreferencesKey("double"), 0.1)
            set(stringSetPreferencesKey("set"), setOf("x", "y"))
            set(byteArrayPreferencesKey("bytes"), bytes)
        }

        val serializer = ListSerializer(PreferenceEntry.serializer())
        val json = Json.encodeToString(serializer, PreferenceEntries.of(original))
        val restored = PreferenceEntries.toPreferences(Json.decodeFromString(serializer, json))

        val withoutBytes = { p: Preferences ->
            p.asMap().filterKeys { it.name != "bytes" }
        }
        assertEquals(withoutBytes(original), withoutBytes(restored))
        assertContentEquals(bytes, restored[byteArrayPreferencesKey("bytes")])
    }
}
