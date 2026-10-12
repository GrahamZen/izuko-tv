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
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.exoplayer.ExoPlayerAudioTimeStretch

/**
 * 设备解不了的音频用 FFmpeg 软解成 PCM 播放: 多数盒子上的 TrueHD / DTS 既没有解码器, HDMI 也接不出直通 (电视、显示器多半只认 AC3 / E-AC3),
 * 不软解的话播放器只放画面、没有声音.
 *
 * 软解库是 media3 的 FFmpeg 扩展: Java 类在 src/androidMain/java/androidx/media3/decoder/ffmpeg/ (media3 同版本原样),
 * libffmpegJNI.so 由 native/media3-ffmpeg/build.sh 编 (只含音频解码器, 给 TrueHD 补了声道布局). [DefaultRenderersFactory] 按扩展渲染器模式
 * 加载它; mediamp 建的渲染器工厂 (带 WSOLA 变速的音频输出) 没打开这个模式, 也不再引用它, 这里在 ExoPlayer 构建前换成一份打开了的:
 * - [DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON]: 扩展渲染器排在系统解码器后面, 系统能解或能直通的仍走系统 (省 CPU、保留直通),
 *   系统处理不了的格式才落到 FFmpeg.
 * - 变速用 WSOLA 时 (mediamp 也只在这时设自己的工厂) 用同一个 WSOLA 工厂, 否则用 ExoPlayer 默认的工厂.
 *   WSOLA 工厂在 mediamp 里是 Kotlin internal, 只能按类名反射构造 (release 的混淆规则保住了类名); 构造不了时不换, 照旧没有软解.
 */
@OptIn(UnstableApi::class)
internal object FfmpegAudioFallback {
    private val logger = logger<FfmpegAudioFallback>()

    @Volatile
    private var logged = false

    fun install(builder: ExoPlayer.Builder, context: Context, timeStretch: ExoPlayerAudioTimeStretch) {
        val factory = if (timeStretch == ExoPlayerAudioTimeStretch.HighQualityWsola) {
            wsolaFactory(context) ?: return
        } else {
            DefaultRenderersFactory(context)
        }
        factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        builder.setRenderersFactory(factory)
        logAvailability()
    }

    private fun wsolaFactory(context: Context): DefaultRenderersFactory? = runCatching {
        Class.forName(WSOLA_FACTORY).getConstructor(Context::class.java).newInstance(context) as DefaultRenderersFactory
    }.onFailure { logger.warn(it) { "Cannot create $WSOLA_FACTORY, FFmpeg audio decoding disabled" } }.getOrNull()

    private const val WSOLA_FACTORY = "org.openani.mediamp.exoplayer.internal.WsolaRenderersFactory"

    /** 第一次建播放器时记一行: FFmpeg 库加载成功没有、版本、TrueHD / DTS 能不能解 (出问题时从用户日志里看) */
    private fun logAvailability() {
        if (logged) return
        logged = true
        logger.info {
            if (!FfmpegLibrary.isAvailable()) {
                "FFmpeg audio decoder unavailable"
            } else {
                "FFmpeg audio decoder ${FfmpegLibrary.getVersion()}: " +
                    listOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3)
                        .joinToString { "$it=${FfmpegLibrary.supportsFormat(it)}" }
            }
        }
    }
}
