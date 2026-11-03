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
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Mp4TextCueMergingExtractor] 交给播放器的字幕块. media3 的 mp4 提取器要用 Android 的类, 所以是设备测试.
 *
 * 片子: 3 秒的小 mp4 (2351 字节), mov_text 字幕由 ASS 转来, 1 秒处正文与注释同时出现 (转出来正文只有 1 微秒长).
 * 生成: `ffmpeg -f lavfi -i color=black:s=64x36:r=5 -i tiny.ass -t 3 -map 0:v -map 1:0 -c:v libx264 -preset ultrafast -crf 51
 * -pix_fmt yuv420p -c:s mov_text tiny.mp4`, tiny.ass 三条: 0.20-0.80 `first line`; 1.00-2.50 `main line`; 1.00-2.50 `{\fs22\pos(320,300)}annotation`.
 */
class Mp4TextCueMergingTest {
    private val media: ByteArray by lazy { Base64.getDecoder().decode(BASE64.joinToString("")) }

    /** 字幕轨交出去的每一块: 开始时间 (毫秒) 与文字 (几段用 `|` 连起来) */
    private fun subtitleSamples(merge: Boolean): List<Pair<Long, String>> {
        val samples = mutableListOf<Pair<Long, String>>()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                private val pending = ByteArrayOutputStream()

                override fun format(format: Format) = Unit

                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    val buffer = ByteArray(length)
                    val read = input.read(buffer, 0, length)
                    if (read > 0) pending.write(buffer, 0, read)
                    return read
                }

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    val buffer = ByteArray(length)
                    data.readBytes(buffer, 0, length)
                    pending.write(buffer)
                }

                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    val bytes = pending.toByteArray()
                    pending.reset()
                    if (type != C.TRACK_TYPE_TEXT) return
                    val cues = CueDecoder().decode(timeUs, bytes, bytes.size - offset - size, size).cues
                    samples += timeUs / 1000 to cues.joinToString("|") { it.text.toString() }
                }
            }

            override fun endTracks() = Unit

            override fun seekMap(seekMap: SeekMap) = Unit
        }
        val mp4 = Mp4Extractor(DefaultSubtitleParserFactory())
        val extractor: Extractor = if (merge) Mp4TextCueMergingExtractor(mp4) else mp4
        extractor.init(output)
        // 照 ProgressiveMediaPeriod 的读法: 提取器要求跳转 (读完 moov 去 mdat) 时从新位置重新打开输入
        var position = 0L
        while (true) {
            var pos = position.toInt()
            val reader = DataReader { buffer, offset, length ->
                if (pos >= media.size) {
                    C.RESULT_END_OF_INPUT
                } else {
                    val n = min(length, media.size - pos)
                    media.copyInto(buffer, offset, pos, pos + n)
                    pos += n
                    n
                }
            }
            val input = DefaultExtractorInput(reader, position, media.size.toLong())
            val positionHolder = PositionHolder()
            var result = Extractor.RESULT_CONTINUE
            while (result == Extractor.RESULT_CONTINUE) result = extractor.read(input, positionHolder)
            if (result == Extractor.RESULT_END_OF_INPUT) return samples
            position = positionHolder.position
        }
    }

    @Test
    fun `without merging the later of two simultaneous lines replaces the earlier one`() {
        // 1.000000 秒的正文只有 1 微秒, 马上被 1.000001 秒的注释替换
        assertEquals(listOf("main line", "annotation"), subtitleSamples(merge = false).filter { it.first == 1000L }.map { it.second })
    }

    @Test
    fun `lines that start at the same time are shown together with the earlier one at the bottom`() {
        val samples = subtitleSamples(merge = true)
        // 1.000001 秒那块: 注释在上, 正文在最底下一行
        assertEquals(listOf("main line", "annotation\nmain line"), samples.filter { it.first == 1000L }.map { it.second })
        // 别的块原样
        assertEquals("first line", samples.first { it.first == 200L }.second)
        assertEquals("", samples.first { it.first == 800L }.second)
    }

    private companion object {
        val BASE64 = listOf(
        "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAAA0BtZGF0AAACUwYF//9P3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NSBy",
        "MzIyMyAwNDgwY2IwIC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyNSAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQu",
        "aHRtbCAtIG9wdGlvbnM6IGNhYmFjPTAgcmVmPTEgZGVibG9jaz0wOjA6MCBhbmFseXNlPTA6MCBtZT1kaWEgc3VibWU9MCBwc3k9MSBwc3lfcmQ9MS4wMDow",
        "LjAwIG1peGVkX3JlZj0wIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MCA4eDhkY3Q9MCBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lw",
        "PTEgY2hyb21hX3FwX29mZnNldD0wIHRocmVhZHM9MSBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGludGVy",
        "bGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBiZnJhbWVzPTAgd2VpZ2h0cD0wIGtleWludD0yNTAga2V5aW50X21pbj01IHNj",
        "ZW5lY3V0PTAgaW50cmFfcmVmcmVzaD0wIHJjPWNyZiBtYnRyZWU9MCBjcmY9NTEuMCBxY29tcD0wLjYwIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBf",
        "cmF0aW89MS40MCBhcT0wAIAAAAASZYiEOiYoABWTk5OuuuuuuuvAAAAAAAAGQZogFaGwAApmaXJzdCBsaW5lAAAABkGaQBahsAAAAAZBmmAWobAAAAAGQZqA",
        "FqGwAAAABkGaoBahsAAAAAltYWluIGxpbmUACmFubm90YXRpb24AAAAWc3R5bAABAAAACgABABb/////AAAABkGawBahsAAAAAZBmuAWobAAAAAGQZsAFqGw",
        "AAAABkGbIBahsAAAAAZBm0AWobAAAAAGQZtgFqGwAAAABkGbgBahsAAAAAZBm6AWobAAAAAGQZvAFqGwAAAAAAXHbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAA",
        "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",
        "YXZmNjIuMy4xMDA="
        )
    }
}
