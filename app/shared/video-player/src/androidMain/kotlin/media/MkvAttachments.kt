/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * 读 Matroska 的 Attachments 元素 (内嵌字体等附件) 的内容, 挑出字体交给 libass.
 *
 * 只认 Attachments 下的 AttachedFile 及其 FileName / FileMimeType / FileData, 其余元素按长度跳过.
 */
internal object MkvAttachments {
    const val ID_ATTACHMENTS = 0x1941A469
    private const val ID_ATTACHED_FILE = 0x61A7
    private const val ID_FILE_NAME = 0x466E
    private const val ID_FILE_MIME_TYPE = 0x4660
    private const val ID_FILE_DATA = 0x465C

    /** 与 ass-media 的 AssMatroskaExtractor 认的 MIME 相同, 另加字体集合. */
    private val FONT_MIME_TYPES = setOf(
        "font/ttf", "font/otf", "font/sfnt", "font/woff", "font/woff2", "font/collection",
        "application/font-sfnt", "application/font-woff", "application/x-truetype-font",
        "application/vnd.ms-opentype", "application/x-font-ttf", "application/x-font-otf",
    )
    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    /**
     * 从 [input] 读 [length] 字节的 Attachments 内容, 每读完一个字体附件调用一次 [onFont].
     */
    fun readFonts(input: InputStream, length: Long, onFont: (name: String, data: ByteArray) -> Unit) {
        val reader = EbmlStream(input)
        while (reader.position < length) {
            val id = reader.readId()
            val size = reader.readSize()
            if (id == ID_ATTACHED_FILE) readAttachedFile(reader, size, onFont) else reader.skip(size)
        }
    }

    private fun readAttachedFile(reader: EbmlStream, size: Long, onFont: (String, ByteArray) -> Unit) {
        val end = reader.position + size
        var name: String? = null
        var mimeType: String? = null
        var data: ByteArray? = null
        while (reader.position < end) {
            val id = reader.readId()
            val childSize = reader.readSize()
            when (id) {
                ID_FILE_NAME -> name = reader.readString(childSize)
                ID_FILE_MIME_TYPE -> mimeType = reader.readString(childSize)
                // MIME 一般写在数据前面; 还不知道是不是字体时先读下来
                ID_FILE_DATA -> if (mimeType == null || isFont(name, mimeType)) {
                    data = reader.readBytes(childSize)
                } else {
                    reader.skip(childSize)
                }

                else -> reader.skip(childSize)
            }
        }
        if (name != null && data != null && isFont(name, mimeType)) onFont(name, data)
    }

    private fun isFont(name: String?, mimeType: String?): Boolean =
        mimeType?.lowercase() in FONT_MIME_TYPES ||
            name?.substringAfterLast('.', "")?.lowercase() in FONT_EXTENSIONS

    /** 顺序读 EBML 元素头与内容, 记着读过的字节数. */
    private class EbmlStream(private val input: InputStream) {
        var position = 0L
            private set

        private fun readByte(): Int {
            val b = input.read()
            if (b < 0) throw EOFException("Attachments ended early at $position")
            position++
            return b
        }

        /** 元素 ID, 保留长度标记位 (与 Matroska 规范里写的 ID 相同). */
        fun readId(): Int {
            val first = readByte()
            val length = varintLength(first, maxLength = 4)
            var value = first
            repeat(length - 1) { value = (value shl 8) or readByte() }
            return value
        }

        /** 元素内容长度, 去掉长度标记位. */
        fun readSize(): Long {
            val first = readByte()
            val length = varintLength(first, maxLength = 8)
            var value = (first and (0xFF ushr length)).toLong()
            var allOnes = value == (0xFF ushr length).toLong()
            repeat(length - 1) {
                val b = readByte()
                if (b != 0xFF) allOnes = false
                value = (value shl 8) or b.toLong()
            }
            if (allOnes) throw IOException("Unknown-size element inside attachments at $position")
            return value
        }

        private fun varintLength(first: Int, maxLength: Int): Int {
            for (length in 1..maxLength) {
                if (first and (0x80 ushr (length - 1)) != 0) return length
            }
            throw IOException("Invalid EBML varint 0x${first.toString(16)} at ${position - 1}")
        }

        fun readBytes(size: Long): ByteArray {
            if (size > Int.MAX_VALUE) throw IOException("Attachment too large: $size bytes")
            val bytes = ByteArray(size.toInt())
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) throw EOFException("Attachments ended early at ${position + read}")
                read += n
            }
            position += size
            return bytes
        }

        fun readString(size: Long): String = readBytes(size).decodeToString().trimEnd('\u0000')

        fun skip(size: Long) {
            var remaining = size
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    if (input.read() < 0) throw EOFException("Attachments ended early at ${position + size - remaining}")
                    remaining--
                }
            }
            position += size
        }
    }
}
