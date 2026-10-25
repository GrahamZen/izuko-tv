/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class EchConfigListTest {
    private val cloudflare = Base64.decode(BangumiEchConfig.BUNDLED)

    @Test
    fun `cloudflare config is usable`() {
        assertTrue(EchConfigList.hasUsableConfig(cloudflare))
    }

    @Test
    fun `empty or truncated lists are not usable`() {
        assertFalse(EchConfigList.hasUsableConfig(ByteArray(0)))
        assertFalse(EchConfigList.hasUsableConfig(cloudflare.copyOf(cloudflare.size - 1)))
        assertFalse(EchConfigList.hasUsableConfig(cloudflare + 0))
    }

    @Test
    fun `other kem is skipped`() {
        // 头 2 字节是整份的长度, 再 2 字节版本、2 字节这份的长度, 然后 config_id (1) 与 KEM (2)
        val config = cloudflare.copyOf()
        config[7] = 0x00
        config[8] = 0x10
        assertFalse(EchConfigList.hasUsableConfig(config))
    }

    @Test
    fun `other version is skipped`() {
        val config = cloudflare.copyOf()
        config[3] = 0x0c
        assertFalse(EchConfigList.hasUsableConfig(config))
    }

    @Test
    fun `a usable config after an unknown version is found`() {
        // 前面加一份版本不认识的 (内容随便), 整份长度相应加上
        val unknown = byteArrayOf(0xfe.toByte(), 0x0c, 0x00, 0x02, 0x01, 0x02)
        val body = unknown + cloudflare.copyOfRange(2, cloudflare.size)
        val list = byteArrayOf((body.size shr 8).toByte(), body.size.toByte()) + body
        assertTrue(EchConfigList.hasUsableConfig(list))
    }

    @Test
    fun `mandatory extension makes the config unusable`() {
        // 包里那份的扩展是空的 (最后两字节 0x0000): 换成一条类型 0xfa00 (最高位是 1 = 必须理解)、长度 0 的扩展
        val body = cloudflare.copyOfRange(2, cloudflare.size - 2) + byteArrayOf(0x00, 0x04, 0xfa.toByte(), 0x00, 0x00, 0x00)
        val configLength = ((body[2].toInt() and 0xff) shl 8 or (body[3].toInt() and 0xff)) + 4
        body[2] = (configLength shr 8).toByte()
        body[3] = configLength.toByte()
        val list = byteArrayOf((body.size shr 8).toByte(), body.size.toByte()) + body
        assertFalse(EchConfigList.hasUsableConfig(list))
    }
}
