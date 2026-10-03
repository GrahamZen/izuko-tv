/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.type.AssRenderType
import me.him188.ani.app.videoplayer.media.MkvTestFiles.attachedFile
import me.him188.ani.app.videoplayer.media.MkvTestFiles.element
import me.him188.ani.app.videoplayer.media.MkvTestFiles.elements
import me.him188.ani.app.videoplayer.media.MkvTestFiles.headerLength
import me.him188.ani.app.videoplayer.media.MkvTestFiles.string
import me.him188.ani.app.videoplayer.media.MkvTestFiles.uint
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * media3 的 mkv 提取器要用 Android 的 SparseArray 等, 所以是设备测试.
 *
 * 照 ProgressiveMediaPeriod 的读取循环 (遇到 RESULT_SEEK 就从新位置重新打开输入) 跑 [FontDeferringMkvExtractor].
 */
class FontDeferringMkvExtractorTest {
    private val sink = object : MkvFontSink {
        val fonts = mutableListOf<Pair<String, ByteArray>>()
        var deferred: Pair<Long, Long>? = null

        override fun addFont(name: String, data: ByteArray) {
            fonts += name to data
        }

        override fun deferAttachments(position: Long, length: Long) {
            deferred = position to length
        }
    }

    private val output = object : ExtractorOutput {
        var samples = 0

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) = Unit

            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val read = input.read(ByteArray(length), 0, length)
                return if (read == C.RESULT_END_OF_INPUT && !allowEndOfInput) error("Unexpected end of input") else read
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                samples++
            }
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private var bytesRead = 0L

    private fun extract(file: ByteArray) {
        val extractor = FontDeferringMkvExtractor(
            DefaultSubtitleParserFactory(),
            AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig()),
            sink,
        )
        extractor.init(output)
        val positionHolder = PositionHolder()
        var position = 0L
        repeat(10) {
            var pos = position.toInt()
            val reader = DataReader { buffer, offset, length ->
                if (pos >= file.size) {
                    C.RESULT_END_OF_INPUT
                } else {
                    val n = min(length, file.size - pos)
                    file.copyInto(buffer, offset, pos, pos + n)
                    pos += n
                    bytesRead += n
                    n
                }
            }
            val input = DefaultExtractorInput(reader, position, file.size.toLong())
            var result = Extractor.RESULT_CONTINUE
            while (result == Extractor.RESULT_CONTINUE) result = extractor.read(input, positionHolder)
            if (result == Extractor.RESULT_END_OF_INPUT) return
            position = positionHolder.position
        }
        error("Too many seeks")
    }

    private class TestFile(val bytes: ByteArray, val attachmentsContentPosition: Long, val attachmentsLength: Long)

    /** 字体附件在第一个 Cluster 之前 (字幕组 mkv 的常见布局), Cluster 里一个 MP3 帧. */
    private fun mkv(font: ByteArray): TestFile {
        val ebmlHeader = elements(0x1A45DFA3, string(0x4282, "matroska"), uint(0x4287, 4), uint(0x4285, 2))
        val info = elements(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val tracks = elements(0x1654AE6B, elements(0xAE, uint(0xD7, 1), uint(0x83, 2), string(0x86, "A_MPEG/L3")))
        val attachmentsContent = attachedFile("font.ttf", "font/ttf", font)
        val attachments = element(MkvAttachments.ID_ATTACHMENTS, attachmentsContent)
        val block = byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte(), 1, 2, 3, 4)
        val cluster = elements(0x1F43B675, uint(0xE7, 0), element(0xA3, block))
        val segment = elements(0x18538067, info, tracks, attachments, cluster)
        val attachmentsContentPosition = ebmlHeader.size + headerLength(0x18538067) + info.size + tracks.size +
            headerLength(MkvAttachments.ID_ATTACHMENTS)
        return TestFile(ebmlHeader + segment, attachmentsContentPosition.toLong(), attachmentsContent.size.toLong())
    }

    @Test
    fun `large attachments are skipped without reading and parsing continues after them`() {
        val file = mkv(ByteArray(FontDeferringMkvExtractor.DEFER_MIN_BYTES + 1000))

        extract(file.bytes)

        assertEquals(file.attachmentsContentPosition to file.attachmentsLength, sink.deferred)
        assertTrue(bytesRead < 64 * 1024, "read $bytesRead bytes")
        assertEquals(emptyList(), sink.fonts)
        assertEquals(1, output.samples)
    }

    @Test
    fun `small attachments are read in place`() {
        val font = ByteArray(100_000) { it.toByte() }
        val file = mkv(font)

        extract(file.bytes)

        assertNull(sink.deferred)
        assertEquals(listOf("font.ttf"), sink.fonts.map { it.first })
        assertContentEquals(font, sink.fonts.single().second)
        assertEquals(1, output.samples)
    }
}
