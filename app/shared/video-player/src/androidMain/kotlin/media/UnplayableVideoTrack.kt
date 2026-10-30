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
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities

/**
 * 画面格式设备没有解码器时 (老芯片上的 AV1、杜比视界 profile 5、DivX 3, 或解析时就认不出、记成 `video/x-unknown` 的), ExoPlayer 不选这条画面轨,
 * 照常放声音和字幕: 画面一片黑, 不报错, 也不换源. 视频渲染器把这种格式报成「超出能力」(没有更好的画面轨时照样选上),
 * 开解码器时找不到解码器而报错, 错误里带着这个格式 (app-data 的 `UnsupportedFormat` 认出格式名),
 * 之后与其他设备放不了的格式一样提示并自动换源. 由 [UndecodableProfileGuardingVideoRenderer] 调.
 */
@AndroidxOptIn(UnstableApi::class)
internal object UnplayableVideoTrack {
    /** [format] 判断出来的能力 [capabilities] 是「没有解码器」的画面时改成「超出能力」, 否则原样 */
    fun selectAnyway(format: Format, capabilities: Int): Int =
        if (MimeTypes.isVideo(format.sampleMimeType) && RendererCapabilities.getFormatSupport(capabilities) == C.FORMAT_UNSUPPORTED_SUBTYPE) {
            RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES)
        } else {
            capabilities
        }
}
