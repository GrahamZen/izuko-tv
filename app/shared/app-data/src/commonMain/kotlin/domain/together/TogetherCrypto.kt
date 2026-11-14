/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.readAndDigest

/**
 * 由房间号与密码 ([TogetherRoomId]) 算出来的两样东西: 公共代理上的话题名, 和房间里消息的加密密钥。
 *
 * 公共代理谁都能订阅、谁都能发, 所以:
 * - 话题名只由房间号经慢哈希算出, 从话题名反推不出房间号, 也不带应用的名字。密码不对的人也落在这个话题上,
 *   房主才收得到他的招呼、告诉他密码不对;
 * - 密钥由房间号与密码一起经慢哈希算出, 消息做带校验的加密 (AES-256-GCM), 没有密码的人看不到内容, 也伪造不了消息。
 *
 * 房间号与密码都短 (方便用遥控器输入), 慢哈希 (PBKDF2, [KDF_ITERATIONS] 轮) 让逐个试的代价变高; 它挡的是顺手旁观的人
 * 与换密码之前来过的人, 不是肯花算力的人 —— 房间里只有「在看哪一集、看到哪」。
 */
class TogetherRoomKey internal constructor(
    internal val encryptionKey: ByteArray,
    /** 公共代理上的话题名, 32 位十六进制 */
    val topic: String,
) {
    companion object {
        /**
         * 慢哈希的轮数。慢的电视 (32 位的盒子) 上算一次约一秒, 进房间的人每次都要等它。
         * 定下后不能改: 房间里各台设备要算出同一个话题与密钥, 改了就与之前的版本进不了同一个房间。
         */
        internal const val KDF_ITERATIONS = 20_000
        private const val TOPIC_SALT = "izuko-together/v2/topic"
        private const val KEY_SALT = "izuko-together/v2/key"

        /** 话题名与密钥各算一次慢哈希; 不在主线程算 (见 [KDF_ITERATIONS]) */
        suspend fun derive(room: TogetherRoomId): TogetherRoomKey =
            TogetherRoomKey(encryptionKey = deriveEncryptionKey(room), topic = deriveTopic(room.number))

        /** 房间号 → 话题名 */
        internal suspend fun deriveTopic(number: String): String = withContext(Dispatchers.Default) {
            val master = platformPbkdf2HmacSha256(number, TOPIC_SALT.encodeToByteArray(), KDF_ITERATIONS, lengthBytes = 32)
            sha256("topic".encodeToByteArray() + master).toHex().take(32)
        }

        /** 房间号 + 密码 → 密钥 */
        internal suspend fun deriveEncryptionKey(room: TogetherRoomId): ByteArray = withContext(Dispatchers.Default) {
            val master = platformPbkdf2HmacSha256(
                room.number + "/" + room.password, KEY_SALT.encodeToByteArray(), KDF_ITERATIONS, lengthBytes = 32,
            )
            sha256("key".encodeToByteArray() + master)
        }

        private fun sha256(data: ByteArray): ByteArray = Buffer().apply { write(data) }.readAndDigest(DigestAlgorithm.SHA256)

        private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/**
 * 房间里一条消息在线上的样子: 版本 (1 字节) + 随机数 (12 字节) + AES-GCM 密文与校验。
 * 随机数每条消息现取, 同时当这条消息的标识 (同一条消息经几家代理各到一次, 按它去重)。
 *
 * 另有一种不加密的回执 [deny]: 「标识为某某的那条消息, 这边用房间的密钥解不开」—— 房主收到密码不对的人的招呼时回它,
 * 发招呼的人据此知道是密码不对, 而不是房间里没人。回执谁都能伪造, 最多让人误以为密码不对, 不影响房间里的消息。
 */
internal object TogetherEnvelope {
    private const val VERSION: Byte = 1
    private const val DENY: Byte = 2
    const val NONCE_BYTES = 12
    private const val HEADER_BYTES = 1 + NONCE_BYTES
    private const val GCM_TAG_BYTES = 16

    class Opened(val nonce: ByteArray, val plaintext: ByteArray)

    fun seal(key: TogetherRoomKey, plaintext: ByteArray, nonce: ByteArray = platformSecureRandomBytes(NONCE_BYTES)): ByteArray {
        require(nonce.size == NONCE_BYTES) { "随机数要 $NONCE_BYTES 字节" }
        val sealed = platformAesGcmSeal(key.encryptionKey, nonce, plaintext, associatedData = byteArrayOf(VERSION))
        return byteArrayOf(VERSION) + nonce + sealed
    }

    /** 不是这个房间的、被改过的、版本认不出的, 都是 `null` */
    fun open(key: TogetherRoomKey, envelope: ByteArray): Opened? {
        if (envelope.size < HEADER_BYTES + GCM_TAG_BYTES || envelope[0] != VERSION) return null
        val nonce = envelope.copyOfRange(1, HEADER_BYTES)
        val plaintext = platformAesGcmOpen(
            key.encryptionKey, nonce, envelope.copyOfRange(HEADER_BYTES, envelope.size),
            associatedData = byteArrayOf(VERSION),
        ) ?: return null
        return Opened(nonce, plaintext)
    }

    /** 不解密, 只取消息标识; 太短或版本不对时为 `null` */
    fun nonceOf(envelope: ByteArray): ByteArray? =
        if (envelope.size < HEADER_BYTES + GCM_TAG_BYTES || envelope[0] != VERSION) null else envelope.copyOfRange(1, HEADER_BYTES)

    /** 回执: 标识为 [nonce] 的消息解不开 */
    fun deny(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES) { "随机数要 $NONCE_BYTES 字节" }
        return byteArrayOf(DENY) + nonce
    }

    /** 是回执时取出它说的那条消息的标识, 否则为 `null` */
    fun deniedNonceOf(frame: ByteArray): ByteArray? =
        if (frame.size == HEADER_BYTES && frame[0] == DENY) frame.copyOfRange(1, HEADER_BYTES) else null
}

/** PBKDF2-HMAC-SHA256, [password] 按 UTF-8 取字节 */
internal expect fun platformPbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray

/** AES-GCM 加密, 返回密文接 16 字节校验 */
internal expect fun platformAesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray

/** AES-GCM 解密; 校验不过时为 `null` */
internal expect fun platformAesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray?

/** 密码学安全的随机字节 */
internal expect fun platformSecureRandomBytes(size: Int): ByteArray
