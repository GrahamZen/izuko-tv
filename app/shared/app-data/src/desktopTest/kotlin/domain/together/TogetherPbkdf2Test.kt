/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 慢哈希有两条路: 系统自带的算法, 和系统没有这个算法时 (Android 7.1 的兼容包) 自己用 HMAC 算的。
 * 两台设备各走一条时必须算出同一个房间, 所以逐字节对。
 */
class TogetherPbkdf2Test {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `the hand rolled pbkdf2 matches the published test vectors`() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(pbkdf2ByMac("password", "salt".encodeToByteArray(), iterations = 1, lengthBytes = 32)),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(pbkdf2ByMac("password", "salt".encodeToByteArray(), iterations = 2, lengthBytes = 32)),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(pbkdf2ByMac("password", "salt".encodeToByteArray(), iterations = 4096, lengthBytes = 32)),
        )
    }

    @Test
    fun `both implementations agree byte for byte`() {
        val random = Random(20261113)
        repeat(20) {
            val password = buildString { repeat(random.nextInt(1, 24)) { append("0123456789ABCDEFxyz 一起看"[random.nextInt(22)]) } }
            val salt = random.nextBytes(random.nextInt(1, 40))
            val iterations = random.nextInt(1, 300)
            // 32 以内是一块, 超过的要算第二、第三块
            val length = random.nextInt(1, 80)
            assertContentEquals(
                pbkdf2ByProvider(password, salt, iterations, length),
                pbkdf2ByMac(password, salt, iterations, length),
                "password=$password iterations=$iterations length=$length",
            )
        }
    }

    @Test
    fun `both implementations agree at the real iteration count`() {
        val salt = "izuko-together/v1".encodeToByteArray()
        assertContentEquals(
            pbkdf2ByProvider("48271593", salt, TogetherRoomKey.KDF_ITERATIONS, 32),
            pbkdf2ByMac("48271593", salt, TogetherRoomKey.KDF_ITERATIONS, 32),
        )
    }
}
