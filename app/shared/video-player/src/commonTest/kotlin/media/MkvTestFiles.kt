/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

/** 拼 Matroska (EBML) 字节的小工具. 长度一律写成 8 字节, ID 按值取 1~4 字节. */
internal object MkvTestFiles {
    fun element(id: Int, content: ByteArray): ByteArray = idBytes(id) + sizeBytes(content.size.toLong()) + content

    fun elements(id: Int, vararg children: ByteArray): ByteArray =
        element(id, children.fold(ByteArray(0)) { acc, child -> acc + child })

    fun uint(id: Int, value: Long): ByteArray = element(id, ByteArray(8) { (value ushr (8 * (7 - it))).toByte() })

    fun string(id: Int, value: String): ByteArray = element(id, value.encodeToByteArray())

    fun attachedFile(name: String, mimeType: String, data: ByteArray, mimeTypeFirst: Boolean = true): ByteArray {
        val nameElement = string(0x466E, name)
        val mimeElement = string(0x4660, mimeType)
        val dataElement = element(0x465C, data)
        return if (mimeTypeFirst) {
            elements(0x61A7, nameElement, mimeElement, dataElement, uint(0x46AE, 1))
        } else {
            elements(0x61A7, nameElement, dataElement, mimeElement, uint(0x46AE, 1))
        }
    }

    /** 元素头的长度 (ID + 8 字节长度). */
    fun headerLength(id: Int): Int = idBytes(id).size + 8

    private fun idBytes(id: Int): ByteArray {
        val length = when {
            id ushr 24 != 0 -> 4
            id ushr 16 != 0 -> 3
            id ushr 8 != 0 -> 2
            else -> 1
        }
        return ByteArray(length) { (id ushr (8 * (length - 1 - it))).toByte() }
    }

    private fun sizeBytes(size: Long): ByteArray =
        byteArrayOf(0x01) + ByteArray(7) { (size ushr (8 * (6 - it))).toByte() }
}
