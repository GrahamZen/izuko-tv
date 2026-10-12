/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
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
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.type.AssRenderType
import me.him188.ani.app.videoplayer.media.MkvTestFiles.element
import me.him188.ani.app.videoplayer.media.MkvTestFiles.elements
import me.him188.ani.app.videoplayer.media.MkvTestFiles.string
import me.him188.ani.app.videoplayer.media.MkvTestFiles.uint
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.Deflater
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SubtitleFixingMkvExtractor] 交给 media3 之后的字幕样本. media3 的 mkv 提取器要用 Android 的 SparseArray 等, 所以是设备测试.
 * 字幕按原样输出 ([MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA]), 直接看样本字节.
 */
class SubtitleFixingMkvExtractorTest {
    private val gbk = Charset.forName("GBK")

    private class Track {
        var format: Format? = null
        val samples = mutableListOf<String>()
    }

    private val tracks = HashMap<Int, Track>()

    private val output = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = tracks.getOrPut(id) { Track() }
            return object : TrackOutput {
                private val pending = ByteArrayOutputStream()

                override fun format(format: Format) {
                    track.format = format
                }

                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    val buffer = ByteArray(length)
                    val read = input.read(buffer, 0, length)
                    if (read == C.RESULT_END_OF_INPUT) return if (allowEndOfInput) read else error("Unexpected end of input")
                    pending.write(buffer, 0, read)
                    return read
                }

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    val buffer = ByteArray(length)
                    data.readBytes(buffer, 0, length)
                    pending.write(buffer)
                }

                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    track.samples += pending.toByteArray().toString(Charsets.UTF_8)
                    pending.reset()
                }
            }
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private fun extract(file: ByteArray) {
        val extractor = SubtitleFixingMkvExtractor(
            DefaultSubtitleParserFactory(),
            AssHandler(AssRenderType.CUES, AssHandlerConfig()),
            MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        )
        extractor.init(output)
        var pos = 0
        val reader = DataReader { buffer, offset, length ->
            if (pos >= file.size) {
                C.RESULT_END_OF_INPUT
            } else {
                val n = min(length, file.size - pos)
                file.copyInto(buffer, offset, pos, pos + n)
                pos += n
                n
            }
        }
        val input = DefaultExtractorInput(reader, 0, file.size.toLong())
        val positionHolder = PositionHolder()
        var result = Extractor.RESULT_CONTINUE
        while (result == Extractor.RESULT_CONTINUE) result = extractor.read(input, positionHolder)
        assertEquals(Extractor.RESULT_END_OF_INPUT, result)
    }

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

    /** ContentEncodings: 一层压缩, [algorithm] 为 null 时不写算法元素 (默认 zlib, mkvmerge 就这样写). */
    private fun compression(algorithm: Long?, settings: ByteArray? = null): ByteArray {
        val compression = listOfNotNull(algorithm?.let { uint(0x4254, it) }, settings?.let { element(0x4255, it) })
        return elements(
            0x6D80,
            elements(0x6240, uint(0x5031, 0), uint(0x5032, 1), uint(0x5033, 0), elements(0x5034, *compression.toTypedArray())),
        )
    }

    private fun subtitleTrack(number: Long, codecId: String, vararg more: ByteArray): ByteArray =
        elements(0xAE, uint(0xD7, number), uint(0x83, 0x11), string(0x86, codecId), *more)

    /** 一个 BlockGroup: 轨道 [number] 在 0 秒、持续 1 秒的一帧 [data]. */
    private fun blockGroup(number: Int, data: ByteArray): ByteArray =
        elements(0xA0, element(0xA1, byteArrayOf((0x80 or number).toByte(), 0, 0, 0) + data), uint(0x9B, 1000))

    private fun mkv(tracks: List<ByteArray>, blocks: List<ByteArray>): ByteArray {
        val ebmlHeader = elements(0x1A45DFA3, string(0x4282, "matroska"), uint(0x4287, 4), uint(0x4285, 2))
        val info = elements(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val cluster = elements(0x1F43B675, uint(0xE7, 0), *blocks.toTypedArray())
        return ebmlHeader + elements(0x18538067, info, elements(0x1654AE6B, *tracks.toTypedArray()), cluster)
    }

    @Test
    fun `zlib compressed subtitles are inflated when the algorithm element is omitted`() {
        val file = mkv(
            listOf(subtitleTrack(1, "S_TEXT/UTF8", compression(algorithm = null))),
            listOf(blockGroup(1, zlib("牵起我的手".toByteArray(Charsets.UTF_8)))),
        )

        extract(file)

        val sample = tracks.getValue(1).samples.single()
        assertTrue(sample.endsWith("牵起我的手"), sample)
    }

    @Test
    fun `zlib compressed gbk subtitles with an explicit algorithm become utf-8`() {
        // media3 自己读到算法 0 会报错, 整个文件打不开
        val file = mkv(
            listOf(subtitleTrack(2, "S_TEXT/UTF8", compression(algorithm = 0))),
            listOf(blockGroup(2, zlib("第二句：简体中文 GBK 编码".toByteArray(gbk)))),
        )

        extract(file)

        val sample = tracks.getValue(2).samples.single()
        assertTrue(sample.endsWith("第二句：简体中文 GBK 编码"), sample)
    }

    @Test
    fun `gbk ass header and dialogue become utf-8`() {
        val header = "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n" +
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, " +
            "StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n" +
            "Style: 正文,方正准圆_GBK,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1\n\n" +
            "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
        val file = mkv(
            listOf(subtitleTrack(3, "S_TEXT/ASS", element(0x63A2, header.toByteArray(gbk)))),
            listOf(blockGroup(3, "0,0,正文,,0,0,0,,第三句：繁简字幕".toByteArray(gbk))),
        )

        extract(file)

        val track = tracks.getValue(3)
        val initializationData = track.format!!.initializationData
        assertTrue(initializationData.any { it.toString(Charsets.UTF_8).contains("Style: 正文,方正准圆_GBK,20") }, "header was not converted")
        val sample = track.samples.single()
        assertTrue(sample.endsWith("0,0,正文,,0,0,0,,第三句：繁简字幕"), sample)
    }

    @Test
    fun `header stripping is left to media3`() {
        // media3 收下算法 3 照常读; 字幕轨它不补回去掉的头 (只有音视频补), mkvmerge 也不给字幕用这种压缩
        val file = mkv(
            listOf(subtitleTrack(4, "S_TEXT/UTF8", compression(algorithm = 3, settings = "第四".toByteArray(Charsets.UTF_8)))),
            listOf(blockGroup(4, "句：去头压缩".toByteArray(Charsets.UTF_8))),
        )

        extract(file)

        val sample = tracks.getValue(4).samples.single()
        assertTrue(sample.endsWith("\n句：去头压缩"), sample)
    }
}
