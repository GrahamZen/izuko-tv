/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TogetherRoomKeyCacheTest {
    @Test
    fun `the topic and key of a room are derived once and a new password reuses the topic`() = runTest {
        val topics = mutableListOf<String>()
        val keys = mutableListOf<TogetherRoomId>()
        val cache = TogetherRoomKeyCache(
            deriveTopic = { number -> topics += number; TogetherRoomKey.deriveTopic(number) },
            deriveKey = { room -> keys += room; TogetherRoomKey.deriveEncryptionKey(room) },
        )
        val room = TogetherRoomId("48271593", "123456")
        val first = cache.get(room)
        val again = cache.get(room)
        assertEquals(first.topic, again.topic)
        assertContentEquals(first.encryptionKey, again.encryptionKey)
        assertEquals(listOf("48271593"), topics)
        assertEquals(listOf(room), keys)

        // 换了密码: 只算密钥
        cache.get(TogetherRoomId("48271593", "654321"))
        assertEquals(1, topics.size)
        assertEquals(2, keys.size)

        // 换了房间: 都算
        cache.get(TogetherRoomId("11112222", "654321"))
        assertEquals(2, topics.size)
        assertEquals(3, keys.size)
    }
}
