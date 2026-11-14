/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import me.him188.ani.app.data.models.preference.TogetherRecentRoom
import me.him188.ani.app.data.models.preference.TogetherRoomRef
import me.him188.ani.app.data.models.preference.TogetherSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TogetherRecentRoomsTest {
    private fun room(number: String, password: String = "123456") = TogetherRoomId(number, password)

    @Test
    fun `a joined room goes to the front with the password used this time`() {
        val before = listOf(
            TogetherRecentRoom("11111111", "111111", hostName = "Mia", hostMemberId = "m1", joinedAt = 1),
            TogetherRecentRoom("22222222", "222222", joinedAt = 2),
        )
        val after = before.withJoined(room("22222222", "333333"), nowMillis = 9)
        assertEquals(listOf("22222222", "11111111"), after.map { it.number })
        assertEquals(TogetherRecentRoom("22222222", "333333", joinedAt = 9), after.first())

        // 再进记着名字的房间: 名字留着
        val again = after.withJoined(room("11111111", "111111"), nowMillis = 10).first()
        assertEquals(TogetherRecentRoom("11111111", "111111", hostName = "Mia", hostMemberId = "m1", joinedAt = 10), again)
    }

    @Test
    fun `only the most recent rooms are kept`() {
        var list = emptyList<TogetherRecentRoom>()
        for (i in 1..TOGETHER_RECENT_ROOMS_MAX + 2) list = list.withJoined(room("1000000$i"), nowMillis = i.toLong())
        assertEquals(TOGETHER_RECENT_ROOMS_MAX, list.size)
        assertEquals("1000000${TOGETHER_RECENT_ROOMS_MAX + 2}", list.first().number)
        assertEquals("10000003", list.last().number)
    }

    @Test
    fun `the host seen in a room is recorded without reordering`() {
        val list = listOf(TogetherRecentRoom("11111111", "111111"), TogetherRecentRoom("22222222", "222222"))
        val host = TogetherMember("h2", "Kai", isHost = true, following = true, state = TogetherMemberState.IDLE, lastSeenAt = 0)
        val after = list.withHost("22222222", host)
        assertEquals(listOf("11111111", "22222222"), after.map { it.number })
        assertEquals(TogetherRecentRoom("22222222", "222222", hostName = "Kai", hostMemberId = "h2"), after[1])
        assertEquals(listOf("11111111"), after.without("22222222").map { it.number })
    }

    @Test
    fun `the host of a room is not oneself`() {
        val members = listOf(
            TogetherMember("me", "Me", isHost = true, following = true, state = TogetherMemberState.IDLE, lastSeenAt = 0),
            TogetherMember("other", "Other", isHost = false, following = true, state = TogetherMemberState.IDLE, lastSeenAt = 0),
        )
        val snapshot = TogetherRoomSnapshot(version = 1, status = TogetherRoomStatus.OPEN, hostId = "me", roomTime = 0, members = members)
        // 自己被请去当房主: 名单上的房主是自己, 不是房间的主人
        assertNull(snapshot.recentHost(selfId = "me"))
        assertEquals("me", snapshot.recentHost(selfId = "other")?.memberId)
    }

    @Test
    fun `the single room older versions remembered counts as the recent one`() {
        val legacy = TogetherSettings(lastJoined = TogetherRoomRef("11111111", "111111"))
        assertEquals(listOf(TogetherRecentRoom("11111111", "111111")), legacy.recentRoomList)
        // 记过新的之后只看新的
        val current = legacy.copy(recentRooms = listOf(TogetherRecentRoom("22222222", "222222")))
        assertEquals(listOf("22222222"), current.recentRoomList.map { it.number })
        assertEquals(emptyList(), TogetherSettings.Default.recentRoomList)
    }
}
