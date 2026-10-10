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
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 把 SRT 字幕交给 [delegate] 前先转成 UTF-8: mkv 里的 SRT 按规范是 UTF-8 (`S_TEXT/UTF8`), 但不少字幕组封进去的是 GBK 或 Big5,
 * 外挂的 .srt 更常见; media3 一律按 UTF-8 读, 字幕就全是乱码. 编码怎么认见 [SubtitleCharsetGuesser]. 别的字幕格式原样交给 [delegate].
 */
@AndroidxOptIn(UnstableApi::class)
internal class CharsetFixingSubtitleParserFactory(private val delegate: SubtitleParser.Factory) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        return if (format.sampleMimeType == MimeTypes.APPLICATION_SUBRIP) CharsetFixingSubtitleParser(parser) else parser
    }
}

/**
 * 与 libass 的 `withAssMkvSupport` 相同 (mkv 换成能把 ASS 字幕与字体交给 libass 的 [AssMatroskaExtractor]),
 * 但字幕解析器工厂可以是任意的 (那个只收 libass 自己的工厂, 套不上 [CharsetFixingSubtitleParserFactory]).
 */
@AndroidxOptIn(UnstableApi::class)
internal fun ExtractorsFactory.withAssMkv(subtitleParserFactory: SubtitleParser.Factory, assHandler: AssHandler): ExtractorsFactory =
    ExtractorsFactory {
        createExtractors().map { extractor ->
            if (extractor is MatroskaExtractor) AssMatroskaExtractor(subtitleParserFactory, assHandler) else extractor
        }.toTypedArray()
    }

@AndroidxOptIn(UnstableApi::class)
private class CharsetFixingSubtitleParser(private val delegate: SubtitleParser) : SubtitleParser {
    /** 一个解析器对应一条字幕轨: 编码按整条轨累计判断. */
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

/**
 * 认一条字幕轨的文本编码, 不是 UTF-8 的转成 UTF-8.
 *
 * 合法 UTF-8、带 UTF-16 字节序标记的原样不动 (后者 media3 自己认). 其余当中文的双字节编码, 在 GBK (按 GB18030 解, 它是 GBK 的超集) 与 Big5 之间选:
 * Big5 常用字的低位字节约四成落在 0x40~0x7E, GBK 常用字 (GB2312 区) 的低位都在 0xA1 以上, 只有生僻的扩展区会落进去.
 * 一条字幕往往只有几个字, 所以按整条轨累计: 低位落在 0x40~0x7E 的双字节不少于五分之一、且整段能按 Big5 解开时认 Big5.
 */
internal class SubtitleCharsetGuesser {
    private var pairs = 0
    private var lowTrailPairs = 0

    /** [data] 中 [offset] 起 [length] 个字节转成的 UTF-8; 本来就是 UTF-8 (或 UTF-16) 时为 null. */
    fun toUtf8OrNull(data: ByteArray, offset: Int, length: Int): ByteArray? {
        if (hasUtf16Bom(data, offset, length) || decodesAs(Charsets.UTF_8, data, offset, length)) return null
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
