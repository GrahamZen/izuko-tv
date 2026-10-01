/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * 接收 mkv 字体附件. 见 [FontDeferringMkvExtractor].
 */
internal interface MkvFontSink {
    /** 当场读到的字体. */
    fun addFont(name: String, data: ByteArray)

    /** Attachments 元素的内容在文件的 [position] 处、长 [length] 字节, 没有读, 要另外取. */
    fun deferAttachments(position: Long, length: Long)
}

/**
 * libass 的 mkv 提取器 ([AssMatroskaExtractor]), 但字体附件大时不在开播前读.
 *
 * 字幕组的 mkv 常把几十 MB 的字体放在文件开头 (Attachments 在第一个 Cluster 之前), 提取器要按顺序读完
 * 才轮到画面; 网盘非会员限速时这一段要读一分钟. 这里把不小于 [DEFER_MIN_BYTES] 的 Attachments 整段
 * 跳过 (返回 [Extractor.RESULT_SEEK] 让播放器从它后面重新打开), 位置交给 [MkvFontSink.deferAttachments]
 * 另外取; 更小的当场读完, 与原来一样在开播前就有字体.
 *
 * 跳过的做法: Attachments 报成未知元素, media3 的 EBML 读取器会对其内容调用 [ExtractorInput.skipFully];
 * [AttachmentSkippingInput] 接下这次跳过, 只把位置记到元素末尾. 读取器随后去读下一个元素的 ID 时
 * 抛出 [SkipTo], [read] 接住后返回 [Extractor.RESULT_SEEK]. 此时读取器已回到「读 ID」状态,
 * 从新位置接着解析即可, 与它自己为读 Cues 而跳转相同.
 */
@AndroidxOptIn(UnstableApi::class)
internal class FontDeferringMkvExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler,
    private val sink: MkvFontSink,
) : Extractor {
    private var input: AttachmentSkippingInput? = null

    private val mkv = object : AssMatroskaExtractor(subtitleParserFactory, assHandler) {
        override fun getElementType(id: Int): Int {
            if (id != MkvAttachments.ID_ATTACHMENTS) return super.getElementType(id)
            input?.attachmentsNext = true
            return EbmlProcessor.ELEMENT_TYPE_UNKNOWN
        }
    }

    override fun sniff(input: ExtractorInput): Boolean = mkv.sniff(input)

    override fun init(output: ExtractorOutput) = mkv.init(output)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val wrapped = this.input?.takeIf { it.input === input } ?: AttachmentSkippingInput(input, ::onAttachments)
        this.input = wrapped
        return try {
            mkv.read(wrapped, seekPosition)
        } catch (skip: SkipTo) {
            seekPosition.position = skip.position
            Extractor.RESULT_SEEK
        }
    }

    /** @return 是否跳过 (没读) */
    private fun onAttachments(input: ExtractorInput, length: Int): Boolean {
        val position = input.position
        val fileLength = input.length
        val fits = fileLength == C.LENGTH_UNSET.toLong() || position + length <= fileLength
        if (length >= DEFER_MIN_BYTES && fits) {
            sink.deferAttachments(position, length.toLong())
            return true
        }
        val bytes = ByteArray(length)
        input.readFully(bytes, 0, length)
        MkvAttachments.readFonts(ByteArrayInputStream(bytes), length.toLong(), sink::addFont)
        return false
    }

    override fun seek(position: Long, timeUs: Long) {
        input = null
        mkv.seek(position, timeUs)
    }

    override fun release() = mkv.release()

    override fun getUnderlyingImplementation(): Extractor = mkv

    internal companion object {
        /** Attachments 不小于这些才推迟: 更小的话, 跳过多出的一次重新打开连接比直接读完还慢. */
        const val DEFER_MIN_BYTES = 4 * 1024 * 1024
    }
}

/** 要求从 [position] 重新打开输入. */
private class SkipTo(val position: Long) : IOException("Skip to $position")

/**
 * 见 [FontDeferringMkvExtractor]. 跳过 Attachments 之后, 位置停在它末尾, 任何读取都抛 [SkipTo].
 */
@AndroidxOptIn(UnstableApi::class)
private class AttachmentSkippingInput(
    val input: ExtractorInput,
    /** 见 [FontDeferringMkvExtractor.onAttachments] */
    private val onAttachments: (input: ExtractorInput, length: Int) -> Boolean,
) : ExtractorInput {
    /** 下一次 [skipFully] 跳的是 Attachments 的内容. */
    var attachmentsNext = false

    private var skippedTo = C.INDEX_UNSET.toLong()
    private val skipped get() = skippedTo != C.INDEX_UNSET.toLong()

    private fun checkNotSkipped() {
        if (skipped) throw SkipTo(skippedTo)
    }

    override fun skipFully(length: Int) {
        checkNotSkipped()
        if (!attachmentsNext) return input.skipFully(length)
        attachmentsNext = false
        val end = input.position + length
        if (onAttachments(input, length)) skippedTo = end
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkNotSkipped()
        return input.read(buffer, offset, length)
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        checkNotSkipped()
        return input.readFully(target, offset, length, allowEndOfInput)
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        checkNotSkipped()
        input.readFully(target, offset, length)
    }

    override fun skip(length: Int): Int {
        checkNotSkipped()
        return input.skip(length)
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        checkNotSkipped()
        return input.skipFully(length, allowEndOfInput)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        checkNotSkipped()
        return input.peek(target, offset, length)
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        checkNotSkipped()
        return input.peekFully(target, offset, length, allowEndOfInput)
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        checkNotSkipped()
        input.peekFully(target, offset, length)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        checkNotSkipped()
        return input.advancePeekPosition(length, allowEndOfInput)
    }

    override fun advancePeekPosition(length: Int) {
        checkNotSkipped()
        input.advancePeekPosition(length)
    }

    override fun resetPeekPosition() {
        if (!skipped) input.resetPeekPosition()
    }

    override fun getPeekPosition(): Long = if (skipped) skippedTo else input.peekPosition

    override fun getPosition(): Long = if (skipped) skippedTo else input.position

    override fun getLength(): Long = input.length

    override fun <E : Throwable> setRetryPosition(position: Long, e: E) = input.setRetryPosition(position, e)
}
