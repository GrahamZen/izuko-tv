/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidVersionsTest {
    @Test
    fun `version comes from the API level, not from the reported release`() {
        // 中国移动 IPTV 盒子 (海思 Hi3798MV300): 系统自报 9.0, 实际是 API 19
        assertEquals("4.4", AndroidVersions.name(19, "9.0"))
        assertEquals("7.1", AndroidVersions.name(25, "7.1.2"))
        assertEquals("12", AndroidVersions.name(31, "12"))
    }

    @Test
    fun `newer API levels fall back to the reported release`() {
        assertEquals("17", AndroidVersions.name(37, "17"))
    }

    @Test
    fun `a faked release does not match`() {
        assertFalse(AndroidVersions.releaseMatches(19, "9.0"))
        assertFalse(AndroidVersions.releaseMatches(30, "12"))
        assertFalse(AndroidVersions.releaseMatches(25, ""))
    }

    @Test
    fun `extra or missing trailing zero parts still match`() {
        assertTrue(AndroidVersions.releaseMatches(19, "4.4.2"))
        assertTrue(AndroidVersions.releaseMatches(28, "9"))
        assertTrue(AndroidVersions.releaseMatches(28, "9.0"))
        assertTrue(AndroidVersions.releaseMatches(21, "5"))
        assertTrue(AndroidVersions.releaseMatches(32, "12"))
        assertTrue(AndroidVersions.releaseMatches(20, "4.4W.2"))
        assertTrue(AndroidVersions.releaseMatches(99, "anything"))
    }
}
