/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.video.MediaCodecVideoDecoderException
import androidx.media3.exoplayer.video.PlaceholderSurface
import me.him188.ani.app.domain.player.DECODER_FAILED_OFF_SCREEN
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 这台设备的视频解码器离开画面就会坏.
 *
 * 画面离开 (应用转后台、退出播放页而会话留着) 时 Media3 不释放解码器, 把它切到一块占位输出面 ([PlaceholderSurface]) 上留着,
 * 画面回来再切回去, 马上就有画面. 有的设备上解码器在占位输出面上待不住: 小米联发科电视的 `c2.mtk.hevc.decoder` 切过去约一秒后
 * 报 `CodecException 0x80000000` (工单 #59, 4K 与 1080p 的 HEVC 都这样), 播放停在出错上.
 *
 * 坏过一次就记下 (存在本机, 跨启动): 之后画面离开时不再把解码器留在占位输出面上, 当场释放, 画面回来再建
 * (同 [VideoDecoderParking] 的停放, 回来时原地 seek, 从关键帧解起). 这次的错报成 [OffScreenDecoderException],
 * 出错处理认作解码器被抢 (`isDecoderPreempted`), 原地重载、不拉黑.
 *
 * 按系统版本 ([Build.FINGERPRINT]) 记: 数据迁移到别的设备、系统升级后重新看.
 */
internal object OffScreenDecoderFault {
    /** 这台设备 (这个系统版本) 上解码器离开画面坏过. */
    @Volatile
    var known = false
        private set

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 读出记下的结论. 在主线程上调, 重复调用无效. */
    fun install(context: Context) {
        if (prefs != null) return
        val loaded = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        known = loaded.getString(KEY_FINGERPRINT, null) == Build.FINGERPRINT
        prefs = loaded
    }

    /** 解码器 [decoder] 在离开画面时坏了. 在播放线程上调. */
    fun record(decoder: String?) {
        if (known) return
        known = true
        prefs?.edit()?.putString(KEY_FINGERPRINT, Build.FINGERPRINT)?.apply()
        logger.warn { "Video decoder $decoder failed off screen, releasing video decoders whenever the video leaves the screen from now on" }
    }

    private const val PREFS_NAME = "video_output"
    private const val KEY_FINGERPRINT = "off_screen_decoder_fault"

    private val logger = logger<OffScreenDecoderFault>()
}

/** 解码器在离开画面时坏了 (见 [OffScreenDecoderFault]). 消息带 [DECODER_FAILED_OFF_SCREEN], 出错处理按它认. */
@OptIn(UnstableApi::class)
internal class OffScreenDecoderException(cause: Throwable, codecInfo: MediaCodecInfo?) :
    MediaCodecVideoDecoderException(cause, codecInfo, null) {
    override val message: String = "Decoder ${codecInfo?.name} $DECODER_FAILED_OFF_SCREEN"
}
