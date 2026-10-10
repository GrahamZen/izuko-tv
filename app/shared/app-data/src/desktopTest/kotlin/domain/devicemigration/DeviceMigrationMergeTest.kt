/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.devicemigration

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.builtins.ListSerializer
import me.him188.ani.app.domain.torrent.peer.PeerFilterSubscription
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 换电视时整机数据怎么并 (DeviceMigrationMerge) 与设置的原样搬运 (PreferenceEntries). 订阅、数据源、弹幕屏蔽词见 DeviceMigrationTest.
 */
class DeviceMigrationMergeTest {
    @Test
    fun `设置各种类型原样往返`() {
        val preferences = mutablePreferencesOf().apply {
            set(stringPreferencesKey("s"), "文字")
            set(booleanPreferencesKey("b"), true)
            set(intPreferencesKey("i"), 3)
            set(longPreferencesKey("l"), 1L shl 40)
            set(floatPreferencesKey("f"), 1.5f)
            set(stringSetPreferencesKey("set"), setOf("a", "b"))
            set(byteArrayPreferencesKey("bytes"), byteArrayOf(1, 2, 3))
        }
        val entries = DeviceMigrationJson.decodeFromString(
            ListSerializer(PreferenceEntry.serializer()),
            DeviceMigrationJson.encodeToString(ListSerializer(PreferenceEntry.serializer()), PreferenceEntries.of(preferences)),
        )
        val restored = PreferenceEntries.toPreferences(entries)
        assertEquals("文字", restored[stringPreferencesKey("s")])
        assertEquals(true, restored[booleanPreferencesKey("b")])
        assertEquals(3, restored[intPreferencesKey("i")])
        assertEquals(1L shl 40, restored[longPreferencesKey("l")])
        assertEquals(1.5f, restored[floatPreferencesKey("f")])
        assertEquals(setOf("a", "b"), restored[stringSetPreferencesKey("set")])
        assertContentEquals(byteArrayOf(1, 2, 3), restored[byteArrayPreferencesKey("bytes")])
    }

    private fun entry(key: String, value: String) = PreferenceEntry(key, PreferenceEntry.Type.STRING, value)

    @Test
    fun `每部番的偏好按键合并 两边都有的取旧电视的`() {
        val merged = DeviceMigrationMerge.keyed(
            incoming = listOf(entry("search_keywords:1", "旧"), entry("track_choice:2", "旧")),
            existing = listOf(entry("search_keywords:1", "新"), entry("search_keywords:3", "新")),
        )
        assertEquals(
            mapOf("search_keywords:1" to "旧", "track_choice:2" to "旧", "search_keywords:3" to "新"),
            merged.associate { it.key to it.value },
        )
    }

    @Test
    fun `Peer 规则订阅按地址认`() {
        fun sub(id: String, url: String) = PeerFilterSubscription(subscriptionId = id, url = url, enabled = true, lastLoaded = null)
        val merged = DeviceMigrationMerge.peerFilterSubscriptions(
            incoming = listOf(sub("a", "https://rules/1.json")),
            existing = listOf(sub("b", "https://rules/1.json"), sub("c", "https://rules/2.json")),
        )
        assertEquals(listOf("a", "c"), merged.map { it.subscriptionId })
    }

    @Test
    fun `NSFW 登记两边取并集`() {
        assertEquals(listOf(1, 2, 3), DeviceMigrationMerge.nsfwSubjects(incoming = listOf(2, 3), existing = listOf(1, 2)))
    }
}
