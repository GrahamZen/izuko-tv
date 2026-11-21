/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import me.him188.ani.app.platform.RemoteAppConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SealedAppConfigTest {
    // scripts/app-config.py 的加密 (Python cryptography 的 AESGCM) 用固定钥匙与 nonce 出的一份, 守着两边格式一致
    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val token = "v1.ZGVmZ2hpamtsbW5v.K3O_EleYJ7BXBmLZ6lVazEihbmv_QoIDib3FJpCezTzgmTP6Yz3qadOCCRGeLafganT9n0EnMUSf-8i3geXE5Hjgunf4Kkt1pQaJfnTyUIsUFHoZzqGHsrKVVOoaYyd_-h75GwVw2UPR1L3P0Is0T0dntoKyqKV-uXZI_A"

    @AfterTest
    fun reset() {
        RemoteAppConfig.remote.value = emptyMap()
    }

    @Test
    fun `opens what the script sealed`() {
        assertEquals(
            mapOf(
                "chat.qq.id" to "10001",
                "chat.qq.link" to "https://qm.qq.com/q/example",
                "dandanplay.app.id" to "example-id",
                "tmdb.api.token" to "a=b.c",
            ),
            SealedAppConfig.open(token, key),
        )
    }

    @Test
    fun `trailing newline from the file is fine`() {
        assertEquals("10001", SealedAppConfig.open("$token\n", key)?.get("chat.qq.id"))
    }

    @Test
    fun `wrong key gives nothing`() {
        assertNull(SealedAppConfig.open(token, "AQECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))
    }

    @Test
    fun `tampered or malformed tokens give nothing`() {
        val parts = token.split('.')
        val flipped = parts[2].replaceRange(0, 1, if (parts[2][0] == 'A') "B" else "A")
        assertNull(SealedAppConfig.open("v1.${parts[1]}.$flipped", key))
        assertNull(SealedAppConfig.open("v2.${parts[1]}.${parts[2]}", key))
        assertNull(SealedAppConfig.open("v1.${parts[1]}", key))
        assertNull(SealedAppConfig.open("not a token", key))
    }

    @Test
    fun `remote values win and missing or blank ones fall back to bundled`() {
        RemoteAppConfig.remote.value = mapOf("chat.qq.id" to "20002", "tmdb.api.token" to " ")
        assertEquals("20002", RemoteAppConfig.value("chat.qq.id", "10001"))
        assertEquals("bundled", RemoteAppConfig.value("tmdb.api.token", "bundled"))
        assertEquals("bundled", RemoteAppConfig.value("dandanplay.app.id", "bundled"))
    }
}
