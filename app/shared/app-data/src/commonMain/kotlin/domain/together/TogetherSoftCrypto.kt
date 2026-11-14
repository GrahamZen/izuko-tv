/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

/**
 * 在系统给的基本运算上拼出 AES-GCM 与 PBKDF2-HMAC-SHA256, 给系统没有现成接口的端用:
 * iOS 公开的 CommonCrypto 只有 AES 分组加密与 HMAC, 没有 AES-GCM (见 TogetherCrypto.ios.kt).
 * 这里只做组合 (计数器、GHASH 校验、PBKDF2 的迭代), 密码运算本身仍是系统的; 结果与 JVM 的 `AES/GCM/NoPadding`、
 * `PBKDF2WithHmacSHA256` 逐字节相同 (见 TogetherSoftCryptoTest).
 */
internal object TogetherSoftCrypto {
    private const val BLOCK_BYTES = 16
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = 16

    /**
     * AES-GCM 加密 (NIST SP 800-38D), 返回密文接 16 字节校验, 同 [platformAesGcmSeal].
     *
     * @param encryptBlocks 用密钥把若干个 16 字节分组逐个 AES 加密 (ECB), 输入长度是 16 的倍数
     */
    fun aesGcmSeal(
        encryptBlocks: (ByteArray) -> ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(nonce.size == GCM_NONCE_BYTES) { "随机数要 $GCM_NONCE_BYTES 字节" }
        val keystream = Keystream(encryptBlocks, nonce, plaintext.size)
        val ciphertext = keystream.xor(plaintext)
        return ciphertext + keystream.tag(associatedData, ciphertext)
    }

    /** AES-GCM 解密, 同 [platformAesGcmOpen]: 随机数不是 12 字节、太短或校验不过时为 `null`. */
    fun aesGcmOpen(
        encryptBlocks: (ByteArray) -> ByteArray,
        nonce: ByteArray,
        sealed: ByteArray,
        associatedData: ByteArray,
    ): ByteArray? {
        if (nonce.size != GCM_NONCE_BYTES || sealed.size < GCM_TAG_BYTES) return null
        val ciphertext = sealed.copyOfRange(0, sealed.size - GCM_TAG_BYTES)
        val keystream = Keystream(encryptBlocks, nonce, ciphertext.size)
        val expected = keystream.tag(associatedData, ciphertext)
        if (!constantTimeEquals(expected, sealed, sealed.size - GCM_TAG_BYTES)) return null
        return keystream.xor(ciphertext)
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018), 同 [platformPbkdf2HmacSha256]: [password] 按 UTF-8 取字节当 HMAC 的密钥.
     *
     * @param hmacSha256 `(密钥, 数据) -> 32 字节摘要`
     */
    fun pbkdf2HmacSha256(
        hmacSha256: (key: ByteArray, data: ByteArray) -> ByteArray,
        password: String,
        salt: ByteArray,
        iterations: Int,
        lengthBytes: Int,
    ): ByteArray {
        require(iterations > 0) { "迭代次数要大于 0" }
        val key = password.encodeToByteArray()
        val output = ByteArray(lengthBytes)
        var written = 0
        var blockIndex = 1
        while (written < lengthBytes) {
            var round = hmacSha256(key, salt + blockIndex.toBigEndianBytes())
            val block = round.copyOf()
            repeat(iterations - 1) {
                round = hmacSha256(key, round)
                for (i in block.indices) block[i] = (block[i].toInt() xor round[i].toInt()).toByte()
            }
            val take = minOf(block.size, lengthBytes - written)
            block.copyInto(output, written, 0, take)
            written += take
            blockIndex++
        }
        return output
    }

    /**
     * 一条消息要用到的 AES 输出, 一次算完: `H = E(0)`、`E(J0)` (校验用) 与各分组的计数器 `E(J0 + i)`.
     * 12 字节随机数时 `J0 = 随机数 || 00000001`, 计数器只加最后 4 字节.
     */
    private class Keystream(encryptBlocks: (ByteArray) -> ByteArray, nonce: ByteArray, dataBytes: Int) {
        private val blocks: ByteArray

        init {
            val counters = (dataBytes + BLOCK_BYTES - 1) / BLOCK_BYTES
            val input = ByteArray((2 + counters) * BLOCK_BYTES)
            // 第 0 块全零 (算 H); 第 1 块起是 J0, J0 + 1, …
            for (i in 0..counters) {
                val offset = (1 + i) * BLOCK_BYTES
                nonce.copyInto(input, offset)
                (1 + i).toBigEndianBytes().copyInto(input, offset + GCM_NONCE_BYTES)
            }
            blocks = encryptBlocks(input)
            check(blocks.size == input.size) { "AES 输出长度不对: ${blocks.size}, 应为 ${input.size}" }
        }

        fun xor(data: ByteArray): ByteArray = ByteArray(data.size) { i ->
            (data[i].toInt() xor blocks[2 * BLOCK_BYTES + i].toInt()).toByte()
        }

        fun tag(associatedData: ByteArray, ciphertext: ByteArray): ByteArray {
            val ghash = GHash(blocks.copyOfRange(0, BLOCK_BYTES))
            ghash.update(associatedData)
            ghash.update(ciphertext)
            ghash.updateLengths(associatedData.size, ciphertext.size)
            val s = ghash.result()
            return ByteArray(GCM_TAG_BYTES) { i -> (s[i].toInt() xor blocks[BLOCK_BYTES + i].toInt()).toByte() }
        }
    }

    /** GCM 的校验函数: 每个 16 字节分组 (不足补零) 异或进累加值再乘 H, 在 GF(2^128) 里按 GCM 的位序算 (SP 800-38D 算法 1). */
    private class GHash(h: ByteArray) {
        private val hHi = h.readLong(0)
        private val hLo = h.readLong(8)
        private var xHi = 0L
        private var xLo = 0L

        fun update(data: ByteArray) {
            var offset = 0
            while (offset < data.size) {
                val block = ByteArray(BLOCK_BYTES)
                data.copyInto(block, 0, offset, minOf(offset + BLOCK_BYTES, data.size))
                xHi = xHi xor block.readLong(0)
                xLo = xLo xor block.readLong(8)
                multiplyByH()
                offset += BLOCK_BYTES
            }
        }

        /** 最后一块: 附加数据与密文的长度 (位数, 各 64 位大端) */
        fun updateLengths(associatedBytes: Int, ciphertextBytes: Int) {
            xHi = xHi xor (associatedBytes.toLong() * 8)
            xLo = xLo xor (ciphertextBytes.toLong() * 8)
            multiplyByH()
        }

        fun result(): ByteArray = xHi.toBigEndianBytes() + xLo.toBigEndianBytes()

        private fun multiplyByH() {
            var zHi = 0L
            var zLo = 0L
            var vHi = hHi
            var vLo = hLo
            for (i in 0 until 128) {
                val bit = if (i < 64) (xHi ushr (63 - i)) and 1L else (xLo ushr (127 - i)) and 1L
                if (bit == 1L) {
                    zHi = zHi xor vHi
                    zLo = zLo xor vLo
                }
                val carry = vLo and 1L
                vLo = (vLo ushr 1) or (vHi shl 63)
                vHi = vHi ushr 1
                if (carry == 1L) vHi = vHi xor REDUCTION
            }
            xHi = zHi
            xLo = zLo
        }
    }

    /** GCM 的约化多项式 `11100001 || 0^120` 的高 64 位 */
    private val REDUCTION = 0xE1L shl 56

    /** [expected] 与 [actual] 从 [offset] 起的同样长度比较, 用时不随第一个不同的字节变 */
    private fun constantTimeEquals(expected: ByteArray, actual: ByteArray, offset: Int): Boolean {
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor actual[offset + i].toInt())
        return diff == 0
    }

    private fun Int.toBigEndianBytes(): ByteArray =
        byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), toByte())

    private fun Long.toBigEndianBytes(): ByteArray = ByteArray(8) { i -> (this ushr (56 - 8 * i)).toByte() }

    private fun ByteArray.readLong(offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (this[offset + i].toLong() and 0xffL)
        return value
    }
}
