/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 加密的应用配置: 与 scripts/sealed_text.py、应用的 SealedText 同一个格式. */
class AppConfigTest {
    private val random = SecureRandom()

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun seal(plain: String, key: ByteArray, purpose: String = AppConfig.PURPOSE): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(purpose.toByteArray())
        return "v1.${base64Url(nonce)}.${base64Url(cipher.doFinal(plain.toByteArray()))}"
    }

    private val key = ByteArray(32).also(random::nextBytes)

    @Test
    fun `解开同一个格式的密文 按第一个等号拆成名字和值`() {
        val token = seal("chat.qq.id=123\nchat.qq.link=https://qm.qq.com/q/a=b\n", key)
        val config = AppConfig.open(token, base64Url(key))?.let(AppConfig::parse)
        assertEquals(mapOf("chat.qq.id" to "123", "chat.qq.link" to "https://qm.qq.com/q/a=b"), config)
    }

    @Test
    fun `钥匙不对 用途不对 被改过 都解不开`() {
        val token = seal("chat.qq.id=123", key)
        assertNull(AppConfig.open(token, base64Url(ByteArray(32))))
        assertNull(AppConfig.open(seal("chat.qq.id=123", key, purpose = "izuko-notices/v1"), base64Url(key)))
        // 改密文中间的一个字符 (末尾那个字符可能只带着没用上的几位)
        val middle = token.lastIndexOf('.') + 10
        val tampered = token.substring(0, middle) + (if (token[middle] == 'A') 'B' else 'A') + token.substring(middle + 1)
        assertNull(AppConfig.open(tampered, base64Url(key)))
        assertNull(AppConfig.open("v2." + token.substringAfter('.'), base64Url(key)))
    }

    @Test
    fun `base64url 补不补等号都认 别的字符不认`() {
        val bytes = ByteArray(31).also(random::nextBytes)
        assertContentEquals(bytes, AppConfig.base64UrlDecode(base64Url(bytes)))
        assertContentEquals(bytes, AppConfig.base64UrlDecode(Base64.getUrlEncoder().encodeToString(bytes)))
        assertNull(AppConfig.base64UrlDecode("ab+c"))
    }

    @Test
    fun `交流群 配置里有就用它 没有用编包时的`() {
        val chat = ChatConfig.of(mapOf("chat.qq.id" to "456", "chat.qq.link" to " "))
        assertEquals("456", chat.qqId)
        assertEquals(BuildConfig.CHAT_QQ_LINK, chat.qqLink)
        assertEquals(BuildConfig.CHAT_TELEGRAM_LINK, chat.telegramLink)
    }
}
