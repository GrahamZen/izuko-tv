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

import androidx.media3.common.Effect
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.MediampPlayer
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

private val logger = logger("VideoEnhancementController")

/** 增强开着时每隔这么久记一次帧率/丢帧, 用来判断卡顿卡在哪一级。 */
private val statsInterval = 2.seconds

actual fun createVideoEnhancementController(
    player: MediampPlayer,
    parentCoroutineContext: CoroutineContext,
): VideoEnhancementController? {
    val exoPlayer = player.impl as? ExoPlayer ?: return null
    return ExoPlayerVideoEnhancementController(player, exoPlayer::setVideoEffects, parentCoroutineContext, exoPlayer)
}

internal class ExoPlayerVideoEnhancementController(
    player: MediampPlayer,
    private val setVideoEffects: (List<Effect>) -> Unit,
    parentCoroutineContext: CoroutineContext,
    /** 只给帧率/丢帧采样 (见 [statsJob]) 读解码计数用; 不给就不采样. */
    private val exoPlayer: ExoPlayer? = null,
) : BaseVideoEnhancementController(player, parentCoroutineContext) {
    private var appliedMode = VideoEnhancementMode.OFF

    /**
     * The scaler of the applied effect list, or `null` while the mode is [VideoEnhancementMode.OFF].
     */
    private var appliedScaler: DesktopStyleLanczosSharpEffect? = null

    /**
     * 增强开着期间的帧率/丢帧采样。用来判断"能播但卡"卡在哪:
     * 解码器实付帧数涨得慢 = GL 那几级 shader 吃不消; 丢帧多而实付正常 = 提交慢/显示端跟不上。
     * 实付帧数持续不到片源一半时自己关掉增强 (见 [VideoEnhancementPerformanceProbe])。
     */
    private var statsJob: Job? = null

    init {
        // 效果图不预先建 (media3 要求它在首次 prepare 之前就存在, 才能在播放中切换效果): 即便传空列表,
        // setVideoEffects 也会把 ExoPlayer 从"解码器直连 SurfaceView"切到 GL 合成管线 (DefaultVideoFrameProcessor),
        // 而 Shield (Tegra X1 / API 30) 上 10bit HEVC 走这条路只出声音不出画面: 解码器正常起来
        // (OMX.Nvidia.h265.decode)、surface 也连上, 就是黑屏 (2026-08-15 实测, 缓存好的 WebRip 全部如此).
        //
        // 没开增强就不该改变管线. 图在**首次真正启用增强时**才建 (见 apply); 代价是
        // 播放中途打开增强, 个别设备可能要 seek 一下才生效 —— 远好过默认就没画面.
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

        // Only a mode change replaces the effect list. The shaders receive input dimensions in
        // configure() and the scaler follows the viewport by itself, so neither metadata
        // availability nor a viewport resize rebuilds the effect graph: compiling the quality
        // shaders can stall playback, and Media3 deadlocks the playback thread when effect lists
        // are replaced in quick succession, as the intermediate layout sizes of a fullscreen
        // switch would do.
        if (appliedMode == mode) {
            appliedScaler?.viewportSize = viewportSize
            return
        }

        val scaler = DesktopStyleLanczosSharpEffect().apply { this.viewportSize = viewportSize }
        val effects = buildList {
            when (mode) {
                VideoEnhancementMode.OFF -> Unit
                VideoEnhancementMode.PERFORMANCE -> add(Anime4kRestoreEffect)
                VideoEnhancementMode.QUALITY -> {
                    add(Anime4kRestoreQualityEffect)
                    add(Anime4kUpscaleQualityEffect)
                }
            }
            add(scaler)
        }
        logger.info {
            "Applying video effects: mode=$mode, " +
                "video=${videoSize?.width}x${videoSize?.height}, " +
                "viewport=${viewportSize?.width}x${viewportSize?.height}, " +
                "effects=${effects.map { it::class.java.simpleName }}"
        }
        setVideoEffects(effects)
        startStatsSampler(mode)
        appliedMode = mode
        appliedScaler = scaler
    }

    /**
     * 每 [statsInterval] 记一次解码器的实付/丢帧计数, 折算成 fps; 设备算不过来时关掉增强 ([fallBackForPerformance])。
     */
    private fun startStatsSampler(mode: VideoEnhancementMode) {
        statsJob?.cancel()
        val exoPlayer = exoPlayer ?: return
        statsJob = scope.launch {
            var lastRendered = 0
            var lastDropped = 0
            val performance = VideoEnhancementPerformanceProbe(exoPlayer)
            while (isActive) {
                delay(statsInterval)
                val counters = exoPlayer.videoDecoderCounters ?: continue
                val rendered = counters.renderedOutputBufferCount
                val dropped = counters.droppedBufferCount
                val renderedDelta = rendered - lastRendered
                val droppedDelta = dropped - lastDropped
                lastRendered = rendered
                lastDropped = dropped
                val tooSlow = performance.isTooSlow(renderedDelta)
                // 按实际隔了多久折算: 显卡吃满时这条线程也慢, 名义 2 秒的间隔会拖到三四秒
                val fps = renderedDelta * 1000.0 / performance.lastWindowMillis.coerceAtLeast(1)
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
                if (tooSlow) {
                    fallBackForPerformance(mode, fps.toFloat(), performance.contentFrameRate)
                    return@launch
                }
            }
        }
    }

    /**
     * 设备算不过来 (见 [VideoEnhancementPerformanceJudge]): 当场清掉效果, 报给页面写回设置并提示.
     * 管线仍是 GL 合成 (同一个播放器回不到解码器直连), 但判得出来的这类设备卡在着色器上, 直通一层不费什么;
     * 写回关闭之后下次进播放页就不建效果图了.
     */
    private suspend fun fallBackForPerformance(mode: VideoEnhancementMode, fps: Float, contentFps: Float) {
        withContext(Dispatchers.IO) {
            logger.warn { "Video enhancement too slow on this device (mode=$mode, $fps fps, content ${contentFps} fps), turning it off" }
        }
        setMode(VideoEnhancementMode.OFF)
        VideoEnhancementFallback.report(VideoEnhancementFallbackEvent(mode, fps, contentFps))
    }

    override fun restore() {
        if (appliedMode == VideoEnhancementMode.OFF) return
        logger.info { "Clearing video effects (was mode=$appliedMode)" }
        statsJob?.cancel()
        statsJob = null
        setVideoEffects(emptyList())
        appliedMode = VideoEnhancementMode.OFF
        appliedScaler = null
    }
}

internal const val exoEffectShaderDirectory = "exo-effects"
