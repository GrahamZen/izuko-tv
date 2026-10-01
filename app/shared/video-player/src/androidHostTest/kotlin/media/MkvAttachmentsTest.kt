/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import me.him188.ani.app.videoplayer.media.MkvTestFiles.attachedFile
import java.io.ByteArrayInputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MkvAttachmentsTest {
    private fun readFonts(content: ByteArray, length: Long = content.size.toLong()): List<Pair<String, ByteArray>> {
        val fonts = mutableListOf<Pair<String, ByteArray>>()
        MkvAttachments.readFonts(ByteArrayInputStream(content), length) { name, data -> fonts += name to data }
        return fonts
    }

    @Test
    fun `picks fonts by mime type or extension`() {
        val a = ByteArray(300) { it.toByte() }
        val b = ByteArray(70_000) { (it * 7).toByte() }
        val content = attachedFile("A.TTF", "application/x-truetype-font", a) +
            attachedFile("cover.jpg", "image/jpeg", ByteArray(1000)) +
            attachedFile("b.otf", "application/octet-stream", b)

        val fonts = readFonts(content)

        assertEquals(listOf("A.TTF", "b.otf"), fonts.map { it.first })
        assertContentEquals(a, fonts[0].second)
        assertContentEquals(b, fonts[1].second)
    }

    @Test
    fun `mime type after the data`() {
        val data = ByteArray(10) { 1 }
        val fonts = readFonts(attachedFile("font", "font/ttf", data, mimeTypeFirst = false))
        assertEquals(listOf("font"), fonts.map { it.first })
    }

    @Test
    fun `truncated attachments fail`() {
        val content = attachedFile("a.ttf", "font/ttf", ByteArray(100))
        assertFailsWith<EOFException> {
            readFonts(content.copyOf(content.size - 10), content.size.toLong())
        }
    }
}
