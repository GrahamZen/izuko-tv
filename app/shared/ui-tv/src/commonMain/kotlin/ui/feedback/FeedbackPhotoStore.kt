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
import me.him188.ani.app.ui.remote.TvRemoteControl
import java.io.File
import kotlin.random.Random

/**
 * 反馈附的照片: 手机上压缩好传上来, 存在电视的应用私有目录 `feedback-photos/` 下, 工单服务收下或用户去掉后删 (没人用的由 [sweep] 清).
 * 只收 JPEG / PNG / WebP (按开头几个字节认), 每张不超过 [MAX_BYTES] (同工单服务).
 */
internal object FeedbackPhotoStore {
    const val MAX_BYTES = 5 * 1024 * 1024
    private val ID = Regex("[0-9a-f]{16}")

    private fun dir(): File? = TvRemoteControl.filesDir()?.let { File(it, "feedback-photos").apply { mkdirs() } }

    /** 图片类型 (`jpg` / `png` / `webp`); 认不出为 `null`. */
    fun typeOf(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        else -> null
    }

    /** 存一张; 不是认得的图片或存不了为 `null`. */
    fun save(bytes: ByteArray): FeedbackPhoto? {
        val type = typeOf(bytes) ?: return null
        val dir = dir() ?: return null
        val id = (1..16).joinToString("") { Random.nextInt(16).toString(16) }
        return runCatching { File(dir, id).writeBytes(bytes) }.map { FeedbackPhoto(id, bytes.size.toLong(), type) }.getOrNull()
    }

    fun file(id: String): File? = if (ID.matches(id)) dir()?.let { File(it, id) }?.takeIf { it.isFile } else null

    fun read(id: String): ByteArray? = file(id)?.let { runCatching { it.readBytes() }.getOrNull() }

    fun delete(id: String) {
        file(id)?.delete()
    }

    /** 删掉 [keep] 以外的 (草稿与上传队列里都不用了的). */
    fun sweep(keep: Set<String>) {
        dir()?.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }
}
