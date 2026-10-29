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
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.min

/**
 * 播放器读 mkv 用的提取器: libass 的 [AssMatroskaExtractor] (ASS 字幕与字体交给 libass), 另外在读 Block 时把字幕轨的字节理顺.
 * 字幕显示 (SubtitleView、libass)、截图、控件出现时上移字幕等都只读这里交出去的数据, 不用各自处理压缩与编码.
 *
 * - **zlib 压缩的轨** (ContentCompression 算法 0, mkvmerge 常给字幕轨开): media3 只认 header stripping (算法 3).
 *   封装里没写算法元素时 (默认值就是 0, mkvmerge 不写) 它当作没压缩, 压缩后的字节被当成字幕文字显示成乱码;
 *   写了 0 时整个文件打不开. 这里先解压再交给 media3.
 * - **文字字幕** (SRT / ASS / SSA / WebVTT) 不是 UTF-8 时转成 UTF-8, 编码按整条轨认 (见 [SubtitleCharsetGuesser]);
 *   ASS 的文件头 (CodecPrivate, 样式里有字体名) 一起转.
 *
 * 外挂字幕文件不经过提取器, 由 [CharsetFixingSubtitleParserFactory] 转编码.
 *
 * 另外把视频轨的 DefaultDuration (每帧时长) 换算成帧率写进 Format: media3 只拿它算时间戳, Format 里没有帧率,
 * 播放页「跟随片源」切刷新率 (ContentFrameRateEffect)、统计层、media3 的无缝刷新率提示都靠它.
 * FLAC 轨补上解码器的输入缓冲大小, 见 [MkvFlacInputSize].
 */
@AndroidxOptIn(UnstableApi::class)
internal open class SubtitleFixingMkvExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler,
    flags: Int = 0,
) : AssMatroskaExtractor(subtitleParserFactory, assHandler, flags) {
    /** 正在读的 TrackEntry. */
    private var entry: MkvTrackFix? = null

    /** 要理顺的轨, 按轨道号. */
    private val fixes = HashMap<Long, MkvTrackFix>()

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        when (id) {
            ID_TRACK_ENTRY -> entry = MkvTrackFix()
            // 算法元素的默认值是 0 (zlib), 不一定写出来
            ID_CONTENT_COMPRESSION -> entry?.compression = 0
        }
        super.startMasterElement(id, contentPosition, contentSize)
    }

    override fun integerElement(id: Int, value: Long) {
        when (id) {
            ID_TRACK_NUMBER -> entry?.number = value
            ID_CONTENT_COMPRESSION_ALGORITHM -> {
                entry?.compression = value
                // media3 只收 header stripping, 别的算法直接报错; zlib 由这里解
                if (value == 0L) return
            }
        }
        super.integerElement(id, value)
    }

    override fun stringElement(id: Int, value: String) {
        if (id == ID_CODEC_ID) entry?.codecId = value
        super.stringElement(id, value)
    }

    override fun endMasterElement(id: Int) {
        if (id == ID_TRACK_ENTRY) {
            entry?.takeIf { it.zlib || it.text != null }?.let { fixes[it.number] = it }
            entry = null
            // 读完 TrackEntry 时 media3 建好 Format, 交给输出要等整个 Tracks 读完, 中间补上帧率
            val track = getCurrentTrack(id)
            super.endMasterElement(id)
            fillFrameRate(track)
            track.format?.let(MkvFlacInputSize::fill)?.let { track.format = it }
            return
        }
        super.endMasterElement(id)
    }

    private fun fillFrameRate(track: Track) {
        val format = track.format ?: return
        if (track.type != C.TRACK_TYPE_VIDEO || format.frameRate > 0f || track.defaultSampleDurationNs <= 0) return
        track.format = format.buildUpon().setFrameRate(1_000_000_000f / track.defaultSampleDurationNs).build()
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        val fixed = when (id) {
            ID_BLOCK, ID_SIMPLE_BLOCK -> fixBlock(contentSize, input)
            ID_CODEC_PRIVATE -> entry?.text?.let { charset ->
                val header = ByteArray(contentSize).also { input.readFully(it, 0, contentSize) }
                charset.toUtf8OrNull(header, 0, header.size) ?: header
            }

            else -> null
        }
        if (fixed == null) {
            super.binaryElement(id, contentSize, input)
        } else {
            super.binaryElement(id, fixed.size, bytesInput(fixed))
        }
    }

    /** 要理顺的轨上的 Block: 读出来理顺后的内容; 别的轨为 null (没读). */
    private fun fixBlock(contentSize: Int, input: ExtractorInput): ByteArray? {
        if (fixes.isEmpty() || contentSize <= 0) return null
        val head = ByteArray(min(contentSize, 8))
        input.peekFully(head, 0, head.size)
        input.resetPeekPosition()
        val fix = MkvSubtitleBlocks.trackNumber(head)?.let(fixes::get) ?: return null
        val block = ByteArray(contentSize).also { input.readFully(it, 0, contentSize) }
        return MkvSubtitleBlocks.fix(block, fix.zlib, fix.text)
    }

    private class MkvTrackFix {
        var number = 0L

        /** ContentCompression 的算法 (0 = zlib, 3 = header stripping); 没压缩为 null. */
        var compression: Long? = null

        var codecId: String? = null
            set(value) {
                field = value
                text = if (value in TEXT_CODEC_IDS) SubtitleCharsetGuesser() else null
            }

        /** 文字字幕轨的编码, 按整条轨累计判断; 别的轨为 null. */
        var text: SubtitleCharsetGuesser? = null
            private set

        val zlib get() = compression == 0L
    }

    private companion object {
        const val ID_TRACK_ENTRY = 0xAE
        const val ID_TRACK_NUMBER = 0xD7
        const val ID_CODEC_ID = 0x86
        const val ID_CODEC_PRIVATE = 0x63A2
        const val ID_CONTENT_COMPRESSION = 0x5034
        const val ID_CONTENT_COMPRESSION_ALGORITHM = 0x4254
        const val ID_BLOCK = 0xA1
        const val ID_SIMPLE_BLOCK = 0xA3

        val TEXT_CODEC_IDS = setOf("S_TEXT/UTF8", "S_TEXT/ASS", "S_TEXT/SSA", "S_TEXT/WEBVTT")

        fun bytesInput(bytes: ByteArray): ExtractorInput {
            var position = 0
            val reader = DataReader { buffer, offset, length ->
                if (position >= bytes.size) {
                    C.RESULT_END_OF_INPUT
                } else {
                    val n = min(length, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + n)
                    position += n
                    n
                }
            }
            return DefaultExtractorInput(reader, 0, bytes.size.toLong())
        }
    }
}

/**
 * 与 libass 的 `withAssMkvSupport` 相同 (mkv 换成能把 ASS 字幕与字体交给 libass 的提取器), 但用 [SubtitleFixingMkvExtractor],
 * 字幕解析器工厂也可以是任意的 (那个只收 libass 自己的工厂, 套不上 [CharsetFixingSubtitleParserFactory]).
 */
@AndroidxOptIn(UnstableApi::class)
internal fun ExtractorsFactory.withAssMkv(subtitleParserFactory: SubtitleParser.Factory, assHandler: AssHandler): ExtractorsFactory =
    ExtractorsFactory {
        createExtractors().map { extractor ->
            if (extractor is MatroskaExtractor) SubtitleFixingMkvExtractor(subtitleParserFactory, assHandler) else extractor
        }.toTypedArray()
    }

/**
 * mkv 的 Block 内容: 轨道号 (EBML 变长整数)、2 字节时间、1 字节标志, 之后是数据. 见 [SubtitleFixingMkvExtractor].
 */
internal object MkvSubtitleBlocks {
    /** Block 内容开头的轨道号; 不是合法的变长整数时为 null. */
    fun trackNumber(head: ByteArray): Long? {
        val length = varintLength(head) ?: return null
        var value = (head[0].toInt() and (0xFF ushr length)).toLong()
        for (i in 1 until length) value = (value shl 8) or (head[i].toLong() and 0xFF)
        return value
    }

    /**
     * 把 Block 的数据解压 ([zlib]) 并转成 UTF-8 ([charset] 不为 null 时), 头原样保留.
     * 带 lacing 的 (一个 Block 装几帧, 字幕轨不这样封) 与解压失败的原样返回.
     */
    fun fix(block: ByteArray, zlib: Boolean, charset: SubtitleCharsetGuesser?): ByteArray {
        val headerLength = (varintLength(block) ?: return block) + 3
        if (block.size < headerLength) return block
        val lacing = block[headerLength - 1].toInt() and 0x06
        if (lacing != 0) return block
        var data = block.copyOfRange(headerLength, block.size)
        if (zlib) data = inflate(data) ?: return block
        if (charset != null) data = charset.toUtf8OrNull(data, 0, data.size) ?: data
        return block.copyOf(headerLength) + data
    }

    private fun varintLength(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        val first = bytes[0].toInt() and 0xFF
        if (first == 0) return null
        val length = Integer.numberOfLeadingZeros(first) - 23
        return length.takeIf { it <= bytes.size }
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }
}
