/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文字字幕 (SRT / ASS / SSA / WebVTT) 交给 [delegate] 前先转成 UTF-8: media3 的解析器与 libass 都按 UTF-8 读,
 * 字幕组的外挂字幕却常是 GBK、Big5 或 UTF-16 (libass 完全读不了 UTF-16). 编码怎么认见 [SubtitleCharsetGuesser].
 * 图形字幕原样交给 [delegate].
 *
 * 管的是外挂字幕文件 (网盘里视频旁边的字幕、HLS 里的 WebVTT 等, 整个文件一次解析). mkv 内封的字幕在提取器里就理顺了
 * (见 [SubtitleFixingMkvExtractor]), 到这里已经是 UTF-8, 原样通过; 而且 libass 画的内封 ASS 根本不经过解析器.
 */
@AndroidxOptIn(UnstableApi::class)
internal class CharsetFixingSubtitleParserFactory(private val delegate: SubtitleParser.Factory) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        return if (format.sampleMimeType in TEXT_MIME_TYPES) CharsetFixingSubtitleParser(parser) else parser
    }

    private companion object {
        val TEXT_MIME_TYPES = setOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT)
    }
}

@AndroidxOptIn(UnstableApi::class)
private class CharsetFixingSubtitleParser(private val delegate: SubtitleParser) : SubtitleParser {
    /** 一个解析器对应一条字幕轨 (或一个外挂文件): 编码按整条轨累计判断. */
    private val guesser = SubtitleCharsetGuesser()

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val utf8 = guesser.toUtf8OrNull(data, offset, length)
        if (utf8 == null) {
            delegate.parse(data, offset, length, outputOptions, output)
        } else {
            delegate.parse(utf8, 0, utf8.size, outputOptions, output)
        }
    }

    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior

    override fun reset() = delegate.reset()
}

/** 一整个字幕文件的文字 (编码认法同 [SubtitleCharsetGuesser]); 给要把字幕交出去的地方 (Web 控制台发给手机) 用. */
fun decodeSubtitleText(data: ByteArray): String {
    val utf8 = SubtitleCharsetGuesser().toUtf8OrNull(data, 0, data.size) ?: data
    return String(utf8, Charsets.UTF_8).removePrefix("﻿")
}

/**
 * 认一条字幕轨的文本编码, 不是 UTF-8 的转成 UTF-8.
 *
 * 合法 UTF-8 原样不动; 带字节序标记的 UTF-16 按标记解. 其余当中文的双字节编码, 在 GBK (按 GB18030 解, 它是 GBK 的超集) 与 Big5 之间选:
 * Big5 常用字的低位字节约四成落在 0x40~0x7E, GBK 常用字 (GB2312 区) 的低位都在 0xA1 以上, 只有生僻的扩展区会落进去.
 * 一条字幕往往只有几个字, 所以按整条轨累计: 低位落在 0x40~0x7E 的双字节不少于五分之一、且整段能按 Big5 解开时认 Big5.
 */
internal class SubtitleCharsetGuesser {
    private var pairs = 0
    private var lowTrailPairs = 0

    /** [data] 中 [offset] 起 [length] 个字节转成的 UTF-8; 本来就是 UTF-8 时为 null. */
    fun toUtf8OrNull(data: ByteArray, offset: Int, length: Int): ByteArray? {
        if (hasUtf16Bom(data, offset, length)) return String(data, offset, length, Charsets.UTF_16).toByteArray(Charsets.UTF_8)
        if (decodesAs(Charsets.UTF_8, data, offset, length)) return null
        countPairs(data, offset, length)
        val charset = if (lowTrailPairs * 5 >= pairs && decodesAs(BIG5, data, offset, length)) BIG5 else GB18030
        return String(data, offset, length, charset).toByteArray(Charsets.UTF_8)
    }

    private fun countPairs(data: ByteArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val lead = data[i].toInt() and 0xFF
            if (lead in 0x81..0xFE && i + 1 < end) {
                pairs++
                if ((data[i + 1].toInt() and 0xFF) in 0x40..0x7E) lowTrailPairs++
                i += 2
            } else {
                i++
            }
        }
    }

    private companion object {
        val GB18030: Charset = Charset.forName("GB18030")
        val BIG5: Charset = Charset.forName("Big5")

        fun hasUtf16Bom(data: ByteArray, offset: Int, length: Int): Boolean {
            if (length < 2) return false
            val first = data[offset].toInt() and 0xFF
            val second = data[offset + 1].toInt() and 0xFF
            return (first == 0xFE && second == 0xFF) || (first == 0xFF && second == 0xFE)
        }

        fun decodesAs(charset: Charset, data: ByteArray, offset: Int, length: Int): Boolean =
            try {
                charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length))
                true
            } catch (_: CharacterCodingException) {
                false
            }
    }
}
