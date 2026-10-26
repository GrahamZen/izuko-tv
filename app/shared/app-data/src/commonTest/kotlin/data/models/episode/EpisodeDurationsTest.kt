/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeDurationsTest {
    @Test
    fun `wiki durations are read as seconds`() {
        assertEquals(1450, EpisodeDurations.parse("00:24:10"))
        assertEquals(4920, EpisodeDurations.parse("01:22:00"))
        assertEquals(1450, EpisodeDurations.parse("24:10"))
        assertEquals(1440, EpisodeDurations.parse("24分钟"))
        assertEquals(1440, EpisodeDurations.parse("24m"))
        assertNull(EpisodeDurations.parse(""))
        assertNull(EpisodeDurations.parse("00:00:00"))
        assertNull(EpisodeDurations.parse("约半小时"))
    }

    @Test
    fun `only far shorter or far longer media is another work`() {
        // 短剧 2 分钟、真人电影 112 分钟之于 24 分钟的一集
        assertTrue(EpisodeDurations.isMismatch(130, 1440))
        assertTrue(EpisodeDurations.isMismatch(112 * 60, 1500))
        // 剪掉片头片尾、两集合在一个文件里都算这一集
        assertFalse(EpisodeDurations.isMismatch(20 * 60, 1440))
        assertFalse(EpisodeDurations.isMismatch(48 * 60, 1440))
        // 哪一边不知道都不算
        assertFalse(EpisodeDurations.isMismatch(0, 1440))
        assertFalse(EpisodeDurations.isMismatch(130, null))
    }
}
