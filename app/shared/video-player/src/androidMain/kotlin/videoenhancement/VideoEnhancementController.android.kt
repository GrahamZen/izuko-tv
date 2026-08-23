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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.preference.PlayerKernelConfig
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.MediampPlayer
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

private val logger = logger("VideoEnhancementController")

/** 增强开着时每隔这么久记一次帧率/丢帧, 用来判断卡顿卡在哪一级。 */
private val statsInterval = 2.seconds

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

    /**
     * 增强开着期间的帧率/丢帧采样 (只记日志, 不改行为)。加这个是为了判断"能播但卡"卡在哪:
     * 解码器实付帧数涨得慢 = GL 那几级 shader 吃不消; 丢帧多而实付正常 = 提交慢/显示端跟不上。
     */
    private var statsJob: Job? = null

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

        val effects = buildList {
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
        }
        logger.info {
            "Applying video effects: mode=$mode, " +
                "video=${videoSize?.width}x${videoSize?.height}, " +
                "viewport=${viewportSize?.width}x${viewportSize?.height}, " +
                "effects=${effects.map { it::class.java.simpleName }}"
        }
        exoPlayer.setVideoEffects(effects)
        startStatsSampler(mode)
        appliedMode = mode
        scalerApplied = shouldApplyScaler
        appliedWidth = if (shouldApplyScaler) viewportSize.width else 0
        appliedHeight = if (shouldApplyScaler) viewportSize.height else 0
    }

    /**
     * 每 [statsInterval] 记一次解码器的实付/丢帧计数, 折算成 fps。只记日志。
     */
    private fun startStatsSampler(mode: VideoEnhancementMode) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var lastRendered = 0
            var lastDropped = 0
            while (isActive) {
                delay(statsInterval)
                val counters = exoPlayer.videoDecoderCounters ?: continue
                val rendered = counters.renderedOutputBufferCount
                val dropped = counters.droppedBufferCount
                val renderedDelta = rendered - lastRendered
                val droppedDelta = dropped - lastDropped
                lastRendered = rendered
                lastDropped = dropped
                val fps = renderedDelta * 1000.0 / statsInterval.inWholeMilliseconds
                // 计数器只能在播放线程读 (上面几行), 但写日志不能留在这条线程上: [scope] 绑的是
                // player.mainDispatcher, 而 logback 是同步写文件的 —— 每 2 秒在播放线程做一次
                // 文件 I/O, 那是在给"排查卡顿"的工具本身制造卡顿。取完数就换线程再打。
                val state = exoPlayer.playbackState
                val playWhenReady = exoPlayer.playWhenReady
                withContext(Dispatchers.IO) {
                    logger.info {
                        "Video enhancement stats (mode=$mode): ${String.format("%.1f", fps)} fps " +
                            "(+$renderedDelta rendered, +$droppedDelta dropped; " +
                            "total rendered=$rendered dropped=$dropped), " +
                            "state=$state playWhenReady=$playWhenReady"
                    }
                }
            }
        }
    }

    override fun restore() {
        if (appliedMode == VideoEnhancementMode.OFF) return
        logger.info { "Clearing video effects (was mode=$appliedMode)" }
        statsJob?.cancel()
        statsJob = null
        exoPlayer.setVideoEffects(emptyList())
        appliedMode = VideoEnhancementMode.OFF
        scalerApplied = false
        appliedWidth = 0
        appliedHeight = 0
    }
}

internal const val exoEffectShaderDirectory = "exo-effects"