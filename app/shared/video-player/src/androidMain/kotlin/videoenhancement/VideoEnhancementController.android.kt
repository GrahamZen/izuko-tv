/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package me.him188.ani.app.videoplayer.videoenhancement

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.PlayerKernelConfig
import org.openani.mediamp.MediampPlayer
import kotlin.coroutines.CoroutineContext

actual fun createVideoEnhancementController(
    player: MediampPlayer,
    playerKernelConfig: Flow<PlayerKernelConfig>,
    parentCoroutineContext: CoroutineContext,
): VideoEnhancementController? {
    val exoPlayer = player.impl as? ExoPlayer ?: return null
    return ExoPlayerVideoEnhancementController(
        player,
        exoPlayer,
        playerKernelConfig.map { it.exoPlayerInitEffectGraphInAdvance },
        parentCoroutineContext,
    )
}

private class ExoPlayerVideoEnhancementController(
    player: MediampPlayer,
    private val exoPlayer: ExoPlayer,
    preinitVideoEffects: Flow<Boolean>,
    parentCoroutineContext: CoroutineContext,
) : BaseVideoEnhancementController(player, parentCoroutineContext) {
    private var appliedMode = VideoEnhancementMode.OFF
    private var scalerApplied = false
    private var appliedWidth = 0
    private var appliedHeight = 0

    init {
        // 上游把这次 pre-init 做成了开关 (exoPlayerInitEffectGraphInAdvance), 理由是 media3 要求
        // 效果图在首次 prepare 之前就存在, 才能在播放中切换效果. **本 fork 把它的默认值改成了 false**,
        // 见 PlayerKernelConfig 那边的注释 —— 即便传空列表, 这一调用也会把 ExoPlayer 从
        // "解码器直连 SurfaceView" 切到 GL 合成管线 (DefaultVideoFrameProcessor), 而 Shield
        // (Tegra X1 / API 30) 上 10bit HEVC 走这条路只出声音不出画面: 解码器正常起来
        // (OMX.Nvidia.h265.decode)、surface 也连上, 就是黑屏 (2026-08-15 实测, 缓存好的 WebRip 全部如此).
        //
        // 默认关着的功能不该改变管线. 关掉之后图在**首次真正启用增强时**才建 (见 apply); 代价是
        // 播放中途打开增强, 个别设备可能要 seek 一下才生效 —— 远好过默认就没画面.
        scope.launch {
            if (preinitVideoEffects.first()) {
                exoPlayer.setVideoEffects(emptyList())
            }
        }
        startObserving()
    }

    override suspend fun apply(
        mode: VideoEnhancementMode,
        videoSize: VideoDimensions?,
        viewportSize: VideoDimensions?,
    ) {
        if (mode == VideoEnhancementMode.OFF) {
            restore()
            return
        }

        val shouldApplyScaler = videoSize != null && viewportSize != null
        if (
            appliedMode == mode && scalerApplied == shouldApplyScaler &&
            (!shouldApplyScaler || appliedWidth == viewportSize.width && appliedHeight == viewportSize.height)
        ) return

        exoPlayer.setVideoEffects(
            buildList {
                when (mode) {
                    VideoEnhancementMode.OFF -> Unit
                    VideoEnhancementMode.PERFORMANCE -> add(Anime4kRestoreEffect)
                    VideoEnhancementMode.QUALITY -> {
                        add(Anime4kRestoreQualityEffect)
                        add(Anime4kUpscaleQualityEffect)
                    }
                }
                if (shouldApplyScaler) {
                    add(DesktopStyleLanczosSharpEffect(viewportSize.width, viewportSize.height))
                }
            },
        )
        appliedMode = mode
        scalerApplied = shouldApplyScaler
        appliedWidth = if (shouldApplyScaler) viewportSize.width else 0
        appliedHeight = if (shouldApplyScaler) viewportSize.height else 0
    }

    override fun restore() {
        if (appliedMode == VideoEnhancementMode.OFF) return
        exoPlayer.setVideoEffects(emptyList())
        appliedMode = VideoEnhancementMode.OFF
        scalerApplied = false
        appliedWidth = 0
        appliedHeight = 0
    }
}

internal const val exoEffectShaderDirectory = "exo-effects"