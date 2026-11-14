/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TogetherInviteTest {
    private val room = TogetherRoomId("28689131", "482193")

    @Test
    fun `an invite in any language reads back as the room`() {
        assertEquals(room, TogetherInvite.parse("【Izuko TV · 一起看】房间号 2868 9131，密码 482 193。在电视上打开「一起看」输入。"))
        assertEquals(room, TogetherInvite.parse("Izuko TV Watch Together - room 2868 9131, password 482 193"))
        assertEquals(room, TogetherInvite.parse("28689131 482193"))
        assertEquals(room, TogetherInvite.parse("2868-9131 / 482-193"))
    }

    @Test
    fun `extra numbers around the invite do not confuse it`() {
        assertEquals(room, TogetherInvite.parse("第 3 集看完了，来：房间号 2868 9131，密码 482 193，晚上 8 点"))
    }

    @Test
    fun `text without a room number and password is not an invite`() {
        assertNull(TogetherInvite.parse(""))
        assertNull(TogetherInvite.parse("2868 9131"))
        assertNull(TogetherInvite.parse("晚上一起看吗"))
    }

    @Test
    fun `room numbers and passwords are shown in groups`() {
        assertEquals("2868 9131", formatTogetherRoomNumber("28689131"))
        assertEquals("482 193", formatTogetherRoomPassword("482193"))
    }
}
