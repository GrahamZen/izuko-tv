/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.container.NalUnitUtil
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.util.IdentityHashMap

/*
 * 把交给解码器的视频 [Format] 的色彩信息 (standard/range/transfer 三项) 补齐。
 *
 * ## 为什么需要
 *
 * Shield (NVIDIA ROM) 上, 只要 MediaCodec 拿到的 MediaFormat 色彩三项**缺任何一项**, 视频层的
 * dataspace 就不会被设置, 留着一段没初始化的内存 (实测是 `0x49dc1850` 这类像堆指针的值)。大多数
 * 垃圾值显示通路当没信息处理, 画面正常 —— 但 transfer 位撞上 7 (ST2084/PQ) 时, SDR 就被按 HDR
 * 曲线还原: 暗部压死、高光爆掉、对比度炸开, 重播一次又好。
 *
 * 三项从哪来、怎么会缺 (两次实锤的入口):
 *
 * - **MKV + HEVC** (2026-08-15/16): media3 的 `MatroskaExtractor` 只认容器的 Colour 元素
 *   (`MatroskaExtractor.java:2536`), mkvmerge 压制的番剧普遍没写 → colorInfo 整个为 null。
 *   SPS 里其实写全了, 从 SPS 恢复即可。
 * - **MP4 + H.264 web 源** (2026-08-19): `BoxParser` 会经 `AvcConfig` 读 SPS
 *   (`BoxParser.java:1494`), 但 `colorSpace` 是从 **primaries** 映射的
 *   (`isoColorPrimariesToColorSpace`), 这条流 SPS 只写了 matrix=BT709/range=limited,
 *   primaries/transfer 都是 unspecified → colorInfo 只剩 range 一项。**SPS 本身没写全,
 *   光靠"从 SPS 恢复"救不了**, 缺的必须按默认规则补。
 *
 * 按 AOSP (android-11.0.0_r48) 的 `ACodec`/`ColorUtils`, 缺项本该在框架层被
 * `setDefaultCodecColorAspectsIfNeeded` 补上默认值、dataspace 从不缺席 —— Shield ROM 在这段
 * 有私改 (机制黑盒), 所以只能在 app 层把三项补齐后再交给 MediaCodec。经验规律 (48+ 次采样):
 * **三项齐 ⇒ 从不假 HDR; 缺任何一项 ⇒ dataspace 垃圾抽奖**。
 *
 * ## 补全策略 (优先级从高到低, 逐字段独立取值)
 *
 * 1. Format 上已有的值 (容器或 extractor 解出来的) —— 绝不覆盖;
 * 2. 从 H.264/HEVC 的 SPS VUI 里解 (用 media3 自己的 [NalUnitUtil], 不是猜);
 * 3. 平台默认值 —— 规则抄 AOSP `ColorUtils.setDefaultCodecColorAspectsIfNeeded`:
 *    分辨率定 colorSpace (4K+→BT2020, PAL/NTSC→BT601, 其余→BT709)、range→limited、
 *    transfer→SDR。上一版 (HevcColorInfoRepair) 拒绝这一步 ("SPS 也没写就别编"),
 *    2026-08-19 被证伪: "维持现状"不是中性行为, 是垃圾 dataspace 抽奖。
 *
 * ## 挂在哪 (为什么是 MediaSource 而不是 ExtractorsFactory)
 *
 * 上一版包在 libass 的 `ExtractorsFactory` 上, 只覆盖 ProgressiveMediaSource (BT/本地/直链);
 * HLS (web 源 m3u8、Jellyfin 转码) 走 `DefaultHlsExtractorFactory`, DASH、以及 interceptor
 * 兜底返回的 mediamp 默认源全都绕过它。所以改包 [MediaSource]: 所有协议的 [Format] 最终都经
 * [SampleStream.readData] 交给渲染器, 在那一层补, 一个口子覆盖全部入口。
 *
 * 注意补的是 readData 交出的 Format (MediaCodecRenderer 用它 configure 解码器), TrackGroup 里
 * 的 Format 保持原样 —— 轨道选择不看 SDR 色彩信息, 不需要动。
 *
 * ## 边界
 *
 * - 只补已验证的 H.264/HEVC 视频轨; 其他编码 (AV1/VP9/Dolby Vision 等) 原样放行 —— 缺容器
 *   Colour 的真 HDR (如 Dolby Vision) 按 SDR 补会显式标错。已有的字段一律不覆盖
 *   (容器写了以容器为准)。
 * - 三项齐的 Format 原样返回 (同一实例), 不产生任何重配置。
 * - transfer 缺且真实内容是 HDR (PQ/HLG 没写进流) 时会被按 SDR 补 —— 和 AOSP 默认行为一致;
 *   规范要求 HDR 必须显式签名, 这种流本来就是坏的, 且旧行为 (垃圾 dataspace) 只会更错。
 */

private val logger = logger("ColorInfoRepair")

/**
 * 包一层, 把视频轨缺失的色彩信息在交给渲染器之前补齐。见本文件顶部的说明。
 *
 * [onVideoFormat] 在每个视频 [Format] 交给渲染器时携带其最终 (补齐后) 的样子回调, 供
 * 播放器决定要不要把 dataspace 直接写到 Surface 上 (见 SurfaceDataSpace.kt); 在播放线程上
 * 调用。HDR/未知 transfer 的轨也会回调 —— 播放器要靠它知道该退出 SDR 覆盖状态。
 */
internal fun MediaSource.withColorInfoRepair(
    onVideoFormat: ((Format) -> Unit)? = null,
): MediaSource = ColorInfoRepairingMediaSource(this, onVideoFormat)

private class ColorInfoRepairingMediaSource(
    mediaSource: MediaSource,
    private val onVideoFormat: ((Format) -> Unit)?,
) : WrappingMediaSource(mediaSource) {
    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = ColorInfoRepairingMediaPeriod(
        mediaSource.createPeriod(id, allocator, startPositionUs),
        onVideoFormat,
    )

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod((mediaPeriod as ColorInfoRepairingMediaPeriod).child)
    }
}

private class ColorInfoRepairingMediaPeriod(
    val child: MediaPeriod,
    private val onVideoFormat: ((Format) -> Unit)?,
) : MediaPeriod {
    // selectTracks 的保留语义按实例判等, 同一条底层流必须一直对应同一个包装实例
    private val streamWrappers = IdentityHashMap<SampleStream, ColorInfoRepairingSampleStream>()

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        child.prepare(
            object : MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: MediaPeriod) {
                    callback.onPrepared(this@ColorInfoRepairingMediaPeriod)
                }

                override fun onContinueLoadingRequested(source: MediaPeriod) {
                    callback.onContinueLoadingRequested(this@ColorInfoRepairingMediaPeriod)
                }
            },
            positionUs,
        )
    }

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        // 底层 period 按实例识别自己的流, 先解包再转发
        for (i in streams.indices) {
            (streams[i] as? ColorInfoRepairingSampleStream)?.let { streams[i] = it.child }
        }
        val result = child.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs)
        for (i in streams.indices) {
            val childStream = streams[i] ?: continue
            streams[i] = streamWrappers.getOrPut(childStream) {
                ColorInfoRepairingSampleStream(childStream, onVideoFormat)
            }
        }
        return result
    }

    override fun maybeThrowPrepareError() = child.maybeThrowPrepareError()
    override fun getTrackGroups(): TrackGroupArray = child.trackGroups
    override fun getStreamKeys(trackSelections: MutableList<ExoTrackSelection>): List<StreamKey> =
        child.getStreamKeys(trackSelections)

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = child.discardBuffer(positionUs, toKeyframe)
    override fun readDiscontinuity(): Long = child.readDiscontinuity()
    override fun seekToUs(positionUs: Long): Long = child.seekToUs(positionUs)
    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        child.getAdjustedSeekPositionUs(positionUs, seekParameters)

    override fun getBufferedPositionUs(): Long = child.bufferedPositionUs
    override fun getNextLoadPositionUs(): Long = child.nextLoadPositionUs
    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = child.continueLoading(loadingInfo)
    override fun isLoading(): Boolean = child.isLoading
    override fun reevaluateBuffer(positionUs: Long) = child.reevaluateBuffer(positionUs)
}

private class ColorInfoRepairingSampleStream(
    val child: SampleStream,
    private val onVideoFormat: ((Format) -> Unit)?,
) : SampleStream {
    // Format 实例在流内会被重复交付 (seek/重新 enable), 缓存住避免重复解析与日志刷屏
    private var lastInput: Format? = null
    private var lastOutput: Format? = null

    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        val result = child.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_FORMAT_READ) {
            val format = formatHolder.format
            if (format != null) {
                if (format !== lastInput) {
                    lastInput = format
                    val output = format.withCompletedColorInfo()
                    lastOutput = output
                    // 每个视频轨都回调, 包括没动过的和 HDR 的 —— 容器写全了的 h264 一样
                    // 需要在 Surface 上补 dataspace, HDR 则要让播放器退出 SDR 覆盖状态
                    val mime = output.sampleMimeType
                    if (mime != null && MimeTypes.isVideo(mime)) {
                        onVideoFormat?.invoke(output)
                    }
                }
                formatHolder.format = lastOutput
            }
        }
        return result
    }

    override fun isReady(): Boolean = child.isReady
    override fun maybeThrowError() = child.maybeThrowError()
    override fun skipData(positionUs: Long): Int = child.skipData(positionUs)
}

private fun Format.withCompletedColorInfo(): Format {
    val mime = sampleMimeType
    // 只补已验证的 AVC/HEVC。其他编码 (AV1/VP9/Dolby Vision 等) 不碰: 比如缺容器 Colour 的
    // Dolby Vision, colorInfo 为 null 但内容是真 HDR, 按 SDR 补会把它显式标错; 等有对应
    // 码流解析和实测再逐个加入。
    if (mime != MimeTypes.VIDEO_H264 && mime != MimeTypes.VIDEO_H265) return this
    val existing = colorInfo
    if (existing != null &&
        existing.colorSpace != Format.NO_VALUE &&
        existing.colorRange != Format.NO_VALUE &&
        existing.colorTransfer != Format.NO_VALUE
    ) {
        return this // 三项齐, 不碰
    }

    val fromSps = when (mime) {
        MimeTypes.VIDEO_H265 -> parseSpsColorInfo(initializationData, isH265 = true)
        MimeTypes.VIDEO_H264 -> parseSpsColorInfo(initializationData, isH265 = false)
        else -> null
    }
    val colorSpace = firstSpecified(existing?.colorSpace, fromSps?.colorSpace)
        ?: defaultColorSpace(width, height)
    val colorRange = firstSpecified(existing?.colorRange, fromSps?.colorRange)
        ?: C.COLOR_RANGE_LIMITED
    val colorTransfer = firstSpecified(existing?.colorTransfer, fromSps?.colorTransfer)
        ?: C.COLOR_TRANSFER_SDR
    logger.info {
        "Incomplete colour info on $mime ${width}x$height " +
                "(had space=${existing?.colorSpace} range=${existing?.colorRange} " +
                "transfer=${existing?.colorTransfer}, " +
                "sps space=${fromSps?.colorSpace} range=${fromSps?.colorRange} " +
                "transfer=${fromSps?.colorTransfer}); " +
                "completed to space=$colorSpace range=$colorRange transfer=$colorTransfer"
    }
    val repaired = (existing?.buildUpon() ?: ColorInfo.Builder())
        .setColorSpace(colorSpace)
        .setColorRange(colorRange)
        .setColorTransfer(colorTransfer)
        .build()
    return buildUpon().setColorInfo(repaired).build()
}

private fun firstSpecified(vararg values: Int?): Int? =
    values.firstOrNull { it != null && it != Format.NO_VALUE }

/**
 * 色彩空间的默认值按分辨率定, 规则与 AOSP `ColorUtils.setDefaultCodecColorAspectsIfNeeded` 一致:
 * 4K 及以上 BT2020, PAL/NTSC 尺寸 BT601, 其余 (含分辨率未知) BT709。
 *
 * AOSP 会进一步区分 BT601 的 525(NTSC)/625(PAL), media3 的 [C.COLOR_SPACE_BT601] 表达不了
 * (对应 MediaFormat 的 BT601_PAL); 两者矩阵系数相同只差原色坐标, MediaFormat 层不区分,
 * Surface 直写层按分辨率拆开 (见 SurfaceDataSpace.kt 的 toSdrDataSpaceOrNull)。
 */
private fun defaultColorSpace(width: Int, height: Int): Int {
    if (width == Format.NO_VALUE || height == Format.NO_VALUE) return C.COLOR_SPACE_BT709
    return when {
        width >= 3840 || height >= 3840 || width.toLong() * height >= 3840L * 1634 -> C.COLOR_SPACE_BT2020
        (width <= 720 && height <= 576) || (height <= 720 && width <= 576) -> C.COLOR_SPACE_BT601
        else -> C.COLOR_SPACE_BT709
    }
}

/**
 * 从 codec-specific data (start code 分隔的 VPS/SPS/PPS) 里找到 SPS 并解出色彩信息.
 *
 * 用的是 media3 自己的解析器 ([NalUnitUtil.parseH265SpsNalUnit] / [NalUnitUtil.parseSpsNalUnit]),
 * 与 `HevcConfig.parse` / `AvcConfig.parse` 走的是同一套, 只是那边解出来的不一定进 `Format`。
 */
private fun parseSpsColorInfo(initializationData: List<ByteArray>, isH265: Boolean): ColorInfo? {
    for (csd in initializationData) {
        forEachNalUnit(csd) { start, end ->
            val colorInfo = if (isH265) {
                // nal_unit_header: forbidden_zero(1) nal_unit_type(6) ...
                val nalUnitType = (csd[start].toInt() shr 1) and 0x3F
                if (nalUnitType != NalUnitUtil.H265_NAL_UNIT_TYPE_SPS) return@forEachNalUnit
                runCatching {
                    val sps = NalUnitUtil.parseH265SpsNalUnit(csd, start, end, null)
                    ColorInfo.Builder()
                        .setColorSpace(sps.colorSpace)
                        .setColorRange(sps.colorRange)
                        .setColorTransfer(sps.colorTransfer)
                        .build()
                }
            } else {
                // nal_unit_header: forbidden_zero(1) nal_ref_idc(2) nal_unit_type(5)
                val nalUnitType = csd[start].toInt() and 0x1F
                if (nalUnitType != NalUnitUtil.H264_NAL_UNIT_TYPE_SPS) return@forEachNalUnit
                runCatching {
                    val sps = NalUnitUtil.parseSpsNalUnit(csd, start, end)
                    ColorInfo.Builder()
                        .setColorSpace(sps.colorSpace)
                        .setColorRange(sps.colorRange)
                        .setColorTransfer(sps.colorTransfer)
                        .build()
                }
            }.getOrElse { e ->
                logger.info { "Failed to parse SPS for colour info: $e" }
                return@forEachNalUnit
            }
            if (colorInfo.colorSpace == Format.NO_VALUE &&
                colorInfo.colorRange == Format.NO_VALUE &&
                colorInfo.colorTransfer == Format.NO_VALUE
            ) {
                return@forEachNalUnit // SPS 里也没写
            }
            return colorInfo
        }
    }
    return null
}

/**
 * 遍历 start code (`00 00 01` 或 `00 00 00 01`) 分隔的 NAL, 回调收到的是**去掉 start code 之后**
 * 的 `[起, 止)` —— 与 [NalUnitUtil] 的解析函数期望的一致.
 */
private inline fun forEachNalUnit(data: ByteArray, block: (start: Int, end: Int) -> Unit) {
    val starts = ArrayList<Int>(4)
    var i = 0
    while (i + 2 < data.size) {
        if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
            starts.add(i + 3)
            i += 3
        } else {
            i++
        }
    }
    for (index in starts.indices) {
        val start = starts[index]
        // 下一个 NAL 的 start code 最多占 4 字节 (00 00 00 01), 从它的负载位置往回退到 start code 之前
        val end = if (index + 1 < starts.size) {
            val nextStartCode = starts[index + 1] - 3
            if (nextStartCode > start && data[nextStartCode - 1].toInt() == 0) nextStartCode - 1 else nextStartCode
        } else {
            data.size
        }
        if (end > start) block(start, end)
    }
}
