/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SealedNoticesTest {
    private val plain = listOf(
        "*=交流群已更换：在设置的关于里查看",
        "1.0.9=只给正式版",
        "1.0.9*=给 1.0.9 和它的测试版",
        "1.0.8=别的版本",
        "1.0.9=",
        "*=交流群已更换：在设置的关于里查看",
    ).joinToString("\n")

    @Test
    fun `release gets the wildcard its exact and its prefix lines in order`() {
        assertEquals(
            listOf("交流群已更换：在设置的关于里查看", "只给正式版", "给 1.0.9 和它的测试版"),
            SealedNotices.select(plain, "1.0.9"),
        )
    }

    @Test
    fun `alpha gets the wildcard and the prefix but not the exact release line`() {
        assertEquals(
            listOf("交流群已更换：在设置的关于里查看", "给 1.0.9 和它的测试版"),
            SealedNotices.select(plain, "1.0.9-alpha05"),
        )
    }

    @Test
    fun `other versions get only the wildcard`() {
        assertEquals(listOf("交流群已更换：在设置的关于里查看"), SealedNotices.select(plain, "1.1.0"))
    }

    @Test
    fun `notices and app config sealed with the same key cannot stand in for each other`() {
        // scripts/sealed_text.py 用固定钥匙与 nonce 出的一份通知密文 (明文: 1.0.0-dev=测试通知：只给本地包)
        val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        assertEquals("1.0.0-dev=测试通知：只给本地包\n", SealedText.open(NOTICES_TOKEN, key, SealedText.Purpose.NOTICES))
        assertNull(SealedText.open(NOTICES_TOKEN, key, SealedText.Purpose.APP_CONFIG))
    }

    private companion object {
        const val NOTICES_TOKEN = "v1.ZGVmZ2hpamtsbW5v.eTXuSEnEMvtIX7ldUY3FaKtCnO0UyRzOPTQj4hwYPK4IRaVc_PcXgfd030qAZ4aXaLn_QOMUnxyo"
    }
}
