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
import android.text.SpannableStringBuilder
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.CueEncoder
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * mp4 的提取器 ([delegate]), 另外把 mp4 内嵌文字字幕 (tx3g / mov_text) 里同一时刻开始的几段合成一段显示.
 *
 * 这种字幕同一时刻只能放一段: 从 ASS 转来时 (网盘转码、ffmpeg), 同时出现的正文与注释 (或双语的两行) 转成前后紧挨着的几块,
 * 前面的只有几微秒长, 每块又替换上一块 ⇒ 屏幕上只剩最后一块. 这里在字幕块解析之后、交给播放器之前, 把开始时间与上一块相差不到
 * [SAME_TIME_US] 的那块换成两块的内容合在一起: 先出现的留在原来的位置 (最底下一行), 后出现的叠在它上面.
 * 样式与位置在转换时已经丢了, 只能这样叠着显示.
 */
@AndroidxOptIn(UnstableApi::class)
internal class Mp4TextCueMergingExtractor(private val delegate: Extractor) : Extractor {
    private var output: MergingExtractorOutput? = null

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        delegate.init(MergingExtractorOutput(output).also { this.output = it })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        output?.reset()
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    internal companion object {
        /** 两块的开始时间相差不到这么多就当成同时出现 (转换出来的是 1 微秒; 正常字幕不会只显示这么短) */
        const val SAME_TIME_US = 10_000L
    }
}

@AndroidxOptIn(UnstableApi::class)
private class MergingExtractorOutput(private val delegate: ExtractorOutput) : ExtractorOutput {
    private val textTracks = mutableListOf<MergingTrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        if (type != C.TRACK_TYPE_TEXT) return track
        return MergingTrackOutput(track).also { textTracks += it }
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)

    fun reset() = textTracks.forEach { it.reset() }
}

/**
 * 一条字幕轨: 解析好的字幕块 (media3 的字幕块格式, 原来是 tx3g 的) 先攒下来, 每块与上一块比开始时间, 同时出现的合并后再交出去; 别的格式原样交出去.
 */
@AndroidxOptIn(UnstableApi::class)
private class MergingTrackOutput(private val delegate: TrackOutput) : TrackOutput {
    private var merging = false
    private val pending = ByteArrayOutputStream()
    private val decoder = CueDecoder()
    private val encoder = CueEncoder()

    /** 上一块的开始时间与内容; 上一块是空的 (清屏) 或还没有时为 null */
    private var last: Pair<Long, List<Cue>>? = null

    override fun format(format: Format) {
        merging = format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES && format.codecs == MimeTypes.APPLICATION_TX3G
        delegate.format(format)
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!merging) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        val buffer = ByteArray(length)
        val read = input.read(buffer, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return read
            throw EOFException()
        }
        pending.write(buffer, 0, read)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!merging) return delegate.sampleData(data, length, sampleDataPart)
        val buffer = ByteArray(length)
        data.readBytes(buffer, 0, length)
        pending.write(buffer)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!merging) return delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        val all = pending.toByteArray()
        val start = all.size - offset - size
        val rest = all.copyOfRange(all.size - offset, all.size)
        pending.reset()
        pending.write(rest)
        val sample = decoder.decode(timeUs, all, start, size)
        val previous = last
        val cues = if (previous != null && sample.cues.isNotEmpty() && timeUs - previous.first in 0 until Mp4TextCueMergingExtractor.SAME_TIME_US) {
            Mp4TextCues.stack(earlier = previous.second, later = sample.cues)
        } else {
            sample.cues
        }
        last = if (cues.isEmpty()) null else timeUs to cues
        val bytes = if (cues === sample.cues) all.copyOfRange(start, start + size) else encoder.encode(cues, sample.durationUs)
        delegate.sampleData(ParsableByteArray(bytes), bytes.size)
        delegate.sampleMetadata(timeUs, flags, bytes.size, 0, cryptoData)
    }

    fun reset() {
        pending.reset()
        last = null
    }
}

@AndroidxOptIn(UnstableApi::class)
internal object Mp4TextCues {
    /**
     * 同时出现的两块合成一段: [later] 的字在上, [earlier] 的字在最底下一行 (各段原来的字号、粗体等照留).
     * 不是纯文字的 (图片字幕) 原样并排放着.
     */
    fun stack(earlier: List<Cue>, later: List<Cue>): List<Cue> {
        val texts = (later + earlier).mapNotNull { it.text }
        if (texts.size != later.size + earlier.size) return later + earlier
        val text = SpannableStringBuilder()
        texts.forEachIndexed { i, t ->
            if (i > 0) text.append('\n')
            text.append(t)
        }
        return listOf(earlier.first().buildUpon().setText(text).build())
    }
}
