/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 设置「隧道播放」: 视频解码器把画面直接送硬件视频层 (sideband), 不经系统合成.
 *
 * 有的电视的硬件合成只接隧道送来的视频 (工单 #44: TCL / 联发科 T50, HWC 只有 SIDEBAND_STREAM 能力): 普通播放时视频层由
 * SurfaceFlinger 用 GPU 合成进 1080p 的界面图形层, 合成时把 BT.709 (SMPTE 170M) 换算到 sRGB, 暗部抬亮发灰、黑色里露出压缩色块,
 * 4K 片也被压到 1080p. 开隧道播放就绕开了这一步. 应用里看不出这台电视属不属于这一类, 所以做成设置, 默认关.
 *
 * 代价与处理:
 * - 画面不经系统合成, 截图读不到画面; 拖动预览换不了输出面, 只能画在全屏上 (见 app-data 的 `seekPreviewOnFullScreen`).
 * - 隧道里的音频不跟倍速 (索尼实测设 1.5 倍照样按 1 倍走), 所以倍速不是 1 时暂时不走隧道, 回到 1 倍再走;
 *   每次切换都重新选轨、重建解码器, 画面停一下.
 * - 解码器或音频渲染器不支持隧道时 (比如要用 FFmpeg 解码的音轨) media3 照常不走隧道.
 */
@OptIn(UnstableApi::class)
internal object TunneledPlayback {
    private val logger = logger<TunneledPlayback>()

    /**
     * 跟着设置 [enabled] 与倍速 [requestedSpeed] 改播放器的选轨参数, 改了当场重新选轨. 在主线程上改.
     *
     * 倍速要用应用要求的那个 (mediamp 的 PlaybackSpeed), 不能用播放器报出的: 隧道里音频只按 1 倍走, 播放器就报 1 倍,
     * 拿它判断会在「1.5 倍 → 关隧道 → 报 1 倍 → 开隧道 → 应用补设 1.5 倍」之间来回切 (索尼实测).
     */
    fun follow(exoPlayer: ExoPlayer, scope: CoroutineScope, enabled: Flow<Boolean>, requestedSpeed: Flow<Float>) {
        scope.launch(Dispatchers.Main) {
            combine(enabled, requestedSpeed, ::Pair).collect { (on, rate) ->
                val tunnel = shouldTunnel(on, rate)
                val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@collect
                if (selector.parameters.tunnelingEnabled == tunnel) return@collect
                logger.info { "Tunneled playback ${if (tunnel) "on" else "off"} (setting=$on, speed=$rate)" }
                selector.setParameters(selector.buildUponParameters().setTunnelingEnabled(tunnel))
            }
        }
    }

    /** 设置开着且按 1 倍速播放时走隧道. */
    fun shouldTunnel(enabled: Boolean, speed: Float): Boolean = enabled && speed == 1f
}
