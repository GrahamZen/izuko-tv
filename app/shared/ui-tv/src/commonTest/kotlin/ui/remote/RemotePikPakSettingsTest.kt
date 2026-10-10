/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.models.preference.PikPakConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Web 控制台的 PikPak 账号: 改账号密码时会话怎么处理 (同设置页 PikPak 那一组), 密码不回给网页. */
class RemotePikPakSettingsTest {
    private val signedIn = PikPakConfig(
        enabled = true,
        username = "a@example.com",
        password = "old",
        refreshToken = "token",
        legacyNoticeAnswered = true,
    )

    @Test
    fun `new username clears the session and takes the password typed with it`() {
        assertEquals(
            signedIn.copy(username = "b@example.com", password = "new", refreshToken = "", legacyNoticeAnswered = false),
            RemotePikPakSettings.applyCredentials(signedIn, " b@example.com ", "new"),
        )
        // 没填密码: 旧账号的密码不留给新账号
        assertEquals(
            signedIn.copy(username = "b@example.com", password = "", refreshToken = "", legacyNoticeAnswered = false),
            RemotePikPakSettings.applyCredentials(signedIn, "b@example.com", ""),
        )
    }

    @Test
    fun `new password clears the session`() {
        assertEquals(
            signedIn.copy(password = "new", refreshToken = ""),
            RemotePikPakSettings.applyCredentials(signedIn, "a@example.com", "new"),
        )
    }

    @Test
    fun `blank password keeps everything`() {
        assertNull(RemotePikPakSettings.applyCredentials(signedIn, "a@example.com", ""))
    }

    @Test
    fun `clearing the username removes the account`() {
        val cleared = RemotePikPakSettings.applyCredentials(signedIn, "", "typed")!!
        assertEquals("", cleared.username)
        assertEquals("", cleared.password)
        assertEquals("", cleared.refreshToken)
        assertTrue(cleared.enabled) // 开关留在电视上, 不跟着改
    }

    @Test
    fun `password is never sent to the page`() {
        val state = RemotePikPakSettings.describe(signedIn)
        assertFalse(state.toString().contains("old"))
        assertFalse(state.toString().contains("token\""))
        assertTrue(state["hasPassword"]!!.jsonPrimitive.boolean)
        assertTrue(state["signedIn"]!!.jsonPrimitive.boolean)
        assertEquals("a@example.com", state["username"]!!.jsonPrimitive.content)
    }
}
