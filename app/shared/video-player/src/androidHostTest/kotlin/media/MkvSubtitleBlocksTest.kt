/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MkvSubtitleBlocksTest {
    /** 轨道号 3 (一字节变长整数)、时间 0x0102、不带 lacing 的标志, 然后是 [data]. */
    private fun block(data: ByteArray, flags: Int = 0x80): ByteArray = byteArrayOf(0x83.toByte(), 1, 2, flags.toByte()) + data

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    @Test
    fun `track number is read from the varint at the start`() {
        assertEquals(3L, MkvSubtitleBlocks.trackNumber(byteArrayOf(0x83.toByte(), 0, 0, 0)))
        assertEquals(0x1234L, MkvSubtitleBlocks.trackNumber(byteArrayOf(0x52, 0x34, 0, 0)))
        assertNull(MkvSubtitleBlocks.trackNumber(byteArrayOf(0, 0, 0, 0)))
        assertNull(MkvSubtitleBlocks.trackNumber(byteArrayOf(0x40)))
    }

    @Test
    fun `zlib data is inflated and the header is kept`() {
        val text = "牵起我的手".toByteArray(Charsets.UTF_8)
        val compressed = zlib(text)
        // mkvmerge 压出来的字幕以 78 DA 开头, 按 GB18030 显示就是「x赟z…」那样的乱码
        assertEquals(0x78, compressed[0].toInt() and 0xFF)

        assertContentEquals(block(text), MkvSubtitleBlocks.fix(block(compressed), zlib = true, charset = null))
    }

    @Test
    fun `text is converted to utf-8 after inflating`() {
        val gbk = "第四句：标点符号——「引号」".toByteArray(Charset.forName("GBK"))

        val fixed = MkvSubtitleBlocks.fix(block(zlib(gbk)), zlib = true, charset = SubtitleCharsetGuesser())

        assertContentEquals(block("第四句：标点符号——「引号」".toByteArray(Charsets.UTF_8)), fixed)
    }

    @Test
    fun `utf-8 text without compression is returned unchanged`() {
        val text = block("Dialogue text 字幕".toByteArray(Charsets.UTF_8))
        assertContentEquals(text, MkvSubtitleBlocks.fix(text, zlib = false, charset = SubtitleCharsetGuesser()))
    }

    @Test
    fun `laced blocks and broken zlib data are left as they are`() {
        val laced = block(zlib("a".toByteArray()), flags = 0x82)
        assertSame(laced, MkvSubtitleBlocks.fix(laced, zlib = true, charset = null))

        val broken = block(byteArrayOf(0x78, 0xDA.toByte(), 1, 2, 3))
        assertSame(broken, MkvSubtitleBlocks.fix(broken, zlib = true, charset = null))

        val truncated = block(zlib("一句很长的字幕一句很长的字幕".toByteArray()).copyOf(6))
        assertSame(truncated, MkvSubtitleBlocks.fix(truncated, zlib = true, charset = null))
    }
}
