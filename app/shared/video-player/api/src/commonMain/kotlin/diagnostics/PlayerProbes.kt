/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.diagnostics

/**
 * 播放链路上那些只在设备上才看得出对不对的判定, 记在这里给 Web 控制台的调试区读
 * (`GET api/debug/probes`, 只在 debug 包里开; 写入侧无条件记, release 包没人读).
 *
 * 为的是把「只能看屏确认」的东西变成能用数据核对的: HLS 到底把哪些段判成了广告、当前用的是哪个解码器、
 * 色彩三项齐不齐。两者都是每次换片源就重新判一遍, 所以 HLS 保留最近若干条, 解码器只留当前这一次。
 *
 * HLS 由 app-data 的 PlatformHlsPlaybackPreparer (本地代理过滤播放列表时) 写, 解码器与色彩由 LibassExoPlayerMediampPlayer 写;
 * 放在 video-player-api 是因为这两个模块都依赖它, ui-tv 读的那一侧在 commonMain 里。
 */
object PlayerProbes {
    /** HLS 列表过一遍去广告之后的结果. */
    class HlsDecision(
        val host: String,
        val totalSegments: Int,
        val removedSegments: Int,
        val removedSeconds: Double,
        /** 没动它的原因; 动过了为 null. */
        val skippedReason: String?,
        val atMillis: Long,
    )

    /** 当前这一路视频的解码器与色彩. 换片源或换解码器时整条替换. */
    class VideoPipeline(
        val decoderName: String?,
        /** NVIDIA 解码器: 要给 Surface 写 dataspace 兜底, 见 LibassExoPlayerMediampPlayer. */
        val nvidiaWorkaround: Boolean,
        /** 色彩三项 (space / range / transfer), 任一为 null 就是「不全」—— 那正是假 HDR 的成因. */
        val colorSpace: Int?,
        val colorRange: Int?,
        val colorTransfer: Int?,
        val codecs: String?,
        val atMillis: Long,
    ) {
        /** 三项齐全才不会在 Shield 上撞出假 HDR. */
        val colorComplete: Boolean get() = colorSpace != null && colorRange != null && colorTransfer != null
    }

    private const val HLS_HISTORY = 20

    private val hlsDecisions = ArrayDeque<HlsDecision>()

    /** 最近的在前. */
    val hls: List<HlsDecision> get() = synchronized(hlsDecisions) { hlsDecisions.toList() }

    @Volatile
    var videoPipeline: VideoPipeline? = null
        private set

    fun recordHls(
        host: String,
        totalSegments: Int,
        removedSegments: Int,
        removedSeconds: Double,
        skippedReason: String?,
        atMillis: Long,
    ) {
        val decision = HlsDecision(host, totalSegments, removedSegments, removedSeconds, skippedReason, atMillis)
        synchronized(hlsDecisions) {
            hlsDecisions.addFirst(decision)
            while (hlsDecisions.size > HLS_HISTORY) hlsDecisions.removeLast()
        }
    }

    fun recordDecoder(decoderName: String, nvidiaWorkaround: Boolean, atMillis: Long) {
        val prev = videoPipeline
        videoPipeline = VideoPipeline(
            decoderName = decoderName,
            nvidiaWorkaround = nvidiaWorkaround,
            colorSpace = prev?.colorSpace,
            colorRange = prev?.colorRange,
            colorTransfer = prev?.colorTransfer,
            codecs = prev?.codecs,
            atMillis = atMillis,
        )
    }

    fun recordVideoFormat(
        colorSpace: Int?,
        colorRange: Int?,
        colorTransfer: Int?,
        codecs: String?,
        atMillis: Long,
    ) {
        val prev = videoPipeline
        videoPipeline = VideoPipeline(
            decoderName = prev?.decoderName,
            nvidiaWorkaround = prev?.nvidiaWorkaround == true,
            colorSpace = colorSpace,
            colorRange = colorRange,
            colorTransfer = colorTransfer,
            codecs = codecs,
            atMillis = atMillis,
        )
    }
}
