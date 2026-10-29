/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MkvFlacInputSizeTest {
    @Test
    fun `six channel flac takes the max frame size from streaminfo`() {
        // 实测没声音的那条轨: 6 声道 16 位, 最大一帧 50970 字节, 解码器默认只开 32768
        assertEquals(50970, MkvFlacInputSize.of(codecPrivate(channels = 6, maxFrameSize = 50970)))
    }

    @Test
    fun `without a max frame size the uncompressed frame size is used`() {
        // 4608 个样本 × 6 声道 × 3 字节 (24 位), 再加帧头
        val size = MkvFlacInputSize.of(codecPrivate(channels = 6, bitsPerSample = 24, maxFrameSize = 0))!!
        assertEquals(4608 * 6 * 3 + 1024, size)
    }

    @Test
    fun `small frames keep at least the decoder default`() {
        assertEquals(MkvFlacInputSize.DEFAULT_INPUT_SIZE, MkvFlacInputSize.of(codecPrivate(channels = 2, maxFrameSize = 9000)))
    }

    @Test
    fun `codec private that is not flac stream info is ignored`() {
        val valid = codecPrivate(channels = 6, maxFrameSize = 50970)
        assertNull(MkvFlacInputSize.of(valid.copyOf(20)))
        assertNull(MkvFlacInputSize.of(valid.copyOf().also { it[0] = 'X'.code.toByte() }))
        // 第一块不是 STREAMINFO
        assertNull(MkvFlacInputSize.of(valid.copyOf().also { it[4] = 4 }))
    }

    @Test
    fun `only flac formats without an input size are filled`() {
        val flac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setInitializationData(listOf(codecPrivate(channels = 6, maxFrameSize = 50970))).build()
        assertEquals(50970, MkvFlacInputSize.fill(flac)?.maxInputSize)
        assertNull(MkvFlacInputSize.fill(flac.buildUpon().setMaxInputSize(65536).build()))
        assertNull(MkvFlacInputSize.fill(flac.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AAC).build()))
        assertNull(MkvFlacInputSize.fill(flac.buildUpon().setInitializationData(emptyList()).build()))
    }

    /** mkv FLAC 轨的 CodecPrivate: 「fLaC」+ STREAMINFO 块 (最后一块) */
    private fun codecPrivate(channels: Int, maxFrameSize: Int, bitsPerSample: Int = 16, blockSize: Int = 4608): ByteArray {
        val bits = BitWriter()
        bits.write(blockSize.toLong(), 16) // 最小块
        bits.write(blockSize.toLong(), 16) // 最大块
        bits.write(0, 24) // 最小帧
        bits.write(maxFrameSize.toLong(), 24)
        bits.write(48000, 20)
        bits.write((channels - 1).toLong(), 3)
        bits.write((bitsPerSample - 1).toLong(), 5)
        bits.write(48000L * 60, 36)
        repeat(16) { bits.write(0, 8) } // MD5
        val info = bits.toByteArray()
        return "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x80.toByte(), 0, 0, info.size.toByte()) + info
    }

    private class BitWriter {
        private val bytes = ArrayList<Byte>()
        private var current = 0
        private var count = 0

        fun write(value: Long, width: Int) {
            for (i in width - 1 downTo 0) {
                current = (current shl 1) or ((value ushr i) and 1).toInt()
                if (++count == 8) {
                    bytes += current.toByte()
                    current = 0
                    count = 0
                }
            }
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}
