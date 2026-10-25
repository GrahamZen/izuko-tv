/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import me.him188.ani.app.data.models.feedback.FeedbackPhoto
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.bufferedSource
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.isRegularFile
import me.him188.ani.utils.io.list
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeBytes
import kotlin.random.Random

/**
 * 反馈附的照片: 控制台在手机上压缩好传上来、或应用里从相册选的, 存在应用私有目录 `feedback-photos/` 下
 * (目录见 [FeedbackPlatform.filesDir]), 工单服务收下或用户去掉后删 (没人用的由 [sweep] 清).
 * 只收 JPEG / PNG / WebP (按开头几个字节认), 每张不超过 [MAX_BYTES] (同工单服务).
 */
internal object FeedbackPhotoStore {
    const val MAX_BYTES = 5 * 1024 * 1024
    private val ID = Regex("[0-9a-f]{16}")

    private fun dir(): SystemPath? = FeedbackPlatform.filesDir()?.resolve("feedback-photos")?.also { runCatching { it.createDirectories() } }

    /** 图片类型 (`jpg` / `png` / `webp`); 认不出为 `null`. */
    fun typeOf(bytes: ByteArray): String? {
        fun at(i: Int, c: Char) = bytes[i] == c.code.toByte()
        return when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
            bytes.size >= 4 && bytes[0] == 0x89.toByte() && at(1, 'P') && at(2, 'N') && at(3, 'G') -> "png"
            bytes.size >= 12 && at(0, 'R') && at(1, 'I') && at(2, 'F') && at(3, 'F') && at(8, 'W') && at(9, 'E') && at(10, 'B') && at(11, 'P') -> "webp"
            else -> null
        }
    }

    /** 存着的照片的类型 (按开头几个字节认, 同 [typeOf]); 读不了为 `null`. */
    fun typeOf(file: SystemPath): String? = runCatching {
        file.bufferedSource().use { source ->
            val head = ByteArray(12)
            var n = 0
            while (n < head.size && !source.exhausted()) head[n++] = source.readByte()
            typeOf(head.copyOf(n))
        }
    }.getOrNull()

    /** 存一张; 不是认得的图片或存不了为 `null`. */
    fun save(bytes: ByteArray): FeedbackPhoto? {
        val type = typeOf(bytes) ?: return null
        val dir = dir() ?: return null
        val id = (1..16).joinToString("") { Random.nextInt(16).toString(16) }
        return runCatching { dir.resolve(id).writeBytes(bytes) }.map { FeedbackPhoto(id, bytes.size.toLong(), type) }.getOrNull()
    }

    fun file(id: String): SystemPath? =
        if (ID.matches(id)) dir()?.resolve(id)?.takeIf { runCatching { it.isRegularFile() }.getOrDefault(false) } else null

    fun read(id: String): ByteArray? = file(id)?.let { runCatching { it.readBytes() }.getOrNull() }

    fun delete(id: String) {
        file(id)?.let { runCatching { it.delete() } }
    }

    /** 删掉 [keep] 以外的 (草稿与上传队列里都不用了的). */
    fun sweep(keep: Set<String>) {
        val dir = dir() ?: return
        runCatching { dir.list() }.getOrDefault(emptyList()).map { it.inSystem }.filter { it.name !in keep }.forEach { runCatching { it.delete() } }
    }
}
