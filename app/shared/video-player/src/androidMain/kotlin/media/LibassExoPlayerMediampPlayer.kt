/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException as Media3PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoTimeoutException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.ExperimentalMediampApi
import me.him188.ani.app.domain.player.tracks.PlayerTrackChooser
import me.him188.ani.app.domain.player.tracks.TrackChooserHost
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.app.videoplayer.player.VideoSurfaceFrameSignal
import me.him188.ani.app.videoplayer.ui.findAndroidVideoSurface
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.MediampPlayerFactory
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.exoplayer.ExoPlayerAudioTimeStretch
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import org.openani.mediamp.features.audioTracks
import org.openani.mediamp.features.subtitleTracks
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

private val logger = logger("LibassExoPlayerMediampPlayer")

/**
 * Adds libass parsing and rendering to MediaMP's ExoPlayer backend.
 *
 * The v2 backend exposes a media source interceptor hook (`docs/playback-state-v2.md` §11)
 * invoked on the main dispatcher during each open, after the default media source is built and
 * before ExoPlayer prepares it. [LibassMediaSourcePipeline] is installed as that interceptor and
 * replaces the default source with one using libass's Matroska extractor and subtitle parser, so
 * MediaMP remains the sole owner of playback state and no source is ever swapped behind its back.
 *
 * For [SeekableInputMediaData], the backend opens the session's [SeekableInput] eagerly during
 * the open, before the interceptor runs, and the `createInput` contract allows only one open
 * input at a time. [setMediaData] therefore wraps the data in [TrackingSeekableInputMediaData]
 * so the interceptor can route playback reads through that already-open input.
 *
 * Video output detach timeouts: when the video output (e.g. a SurfaceView's surface) is destroyed,
 * ExoPlayer blocks the main thread until the playback thread releases it, and stops playback with
 * [ExoTimeoutException.TIMEOUT_OPERATION_DETACH_SURFACE] if that takes longer than
 * [DETACH_SURFACE_TIMEOUT_MILLIS]. The media itself is fine and the picture resumes once a new
 * output is attached, so a [UriMediaData] is reopened at the position where it failed: [openMedia]
 * retries timeouts during an open, and [videoOutputTimeoutListener] reopens after timeouts during
 * playback. A [SeekableInputMediaData] (BT, local files) is closed by MediaMP when its session ends
 * and cannot be reopened as is, so its timeouts are reported as ordinary playback errors.
 */
@OptIn(InternalForInheritanceMediampApi::class)
@AndroidxOptIn(UnstableApi::class)
class LibassExoPlayerMediampPlayer private constructor(
    parentCoroutineContext: CoroutineContext,
    private val pipeline: LibassMediaSourcePipeline,
    internal val exoMediampPlayer: ExoPlayerMediampPlayer,
) : MediampPlayer by exoMediampPlayer, VideoSurfaceFrameSignal, TrackChooserHost {
    /**
     * @param configurePlayerBuilder 在 [ExoPlayer.Builder] 构建前调用, 用于自定义原生播放器 (如缓冲策略).
     *   见 [ExoPlayerMediampPlayer] 的同名参数.
     * @param diskCacheEnabled 在线播放时要不要把下过的数据存在本机 (见 [PlaybackDiskCache]), 每打开一个媒体时问一次 (在主线程上)
     */
    constructor(
        context: Context,
        parentCoroutineContext: CoroutineContext,
        audioTimeStretch: ExoPlayerAudioTimeStretch = ExoPlayerAudioTimeStretch.HighQualityWsola,
        configurePlayerBuilder: ((ExoPlayer.Builder) -> Unit)? = null,
        proxyConfig: () -> PlaybackProxyConfig? = { null },
        diskCacheEnabled: () -> Boolean = { false },
    ) : this(
        context,
        parentCoroutineContext,
        audioTimeStretch,
        configurePlayerBuilder,
        LibassMediaSourcePipeline(context, parentCoroutineContext, proxyConfig, diskCacheEnabled),
    )

    private constructor(
        context: Context,
        parentCoroutineContext: CoroutineContext,
        audioTimeStretch: ExoPlayerAudioTimeStretch,
        configurePlayerBuilder: ((ExoPlayer.Builder) -> Unit)?,
        pipeline: LibassMediaSourcePipeline,
    ) : this(
        parentCoroutineContext,
        pipeline,
        ExoPlayerMediampPlayer(
            context,
            parentCoroutineContext,
            audioTimeStretch,
            mediaSourceInterceptor = pipeline::intercept,
            configurePlayerBuilder = { builder ->
                builder.setDetachSurfaceTimeoutMs(DETACH_SURFACE_TIMEOUT_MILLIS)
                configurePlayerBuilder?.invoke(builder)
            },
            { builder -> builder.setLoadControl(pipeline.loadControl) },
        ),
    )

    internal val assHandler: AssHandler get() = pipeline.assHandler

    /** 见 [TrackChooserHost]; 由播放页的 PreferredTracksExtension 设置, 只在主线程读写. */
    override var trackChooser: PlayerTrackChooser? = null

    private val exoPlayer: ExoPlayer get() = exoMediampPlayer.impl

    /** 进行中的拖动预览 (见 [startSeekPreview]); 只在主线程读写. */
    private var seekPreview: SeekPreview? = null

    private val previewOriginMillis = MutableStateFlow<Long?>(null)

    /** 拖动预览进行中时为开始时的位置 (见 [SeekPreview]), 否则为 null. 只在主线程变. */
    val seekPreviewOrigin: StateFlow<Long?> get() = previewOriginMillis

    /**
     * 开始拖动预览: 之后由主播放器解出预览位置的画面, 画进 [SeekPreview.attachFrameSurface] 给的小画面里 (见 [SeekPreview]);
     * 全屏停在开始时那一帧, 对外报告的播放位置 ([currentPositionMillis]) 与字幕也停在那里. 播放器已经关了时返回 null.
     * 换媒体时进行中的预览自动结束. 在主线程上调用.
     *
     * @param isAvailable 本机有没有某个位置的数据, 没有的话停一会儿才去下 (BT: 那一段下完了没有). 在线源不用给, 由播放器自己在联网前挡住.
     */
    fun startSeekPreview(isAvailable: ((Long) -> Boolean)? = null): SeekPreview? {
        seekPreview?.end()
        if (closed) return null
        return SeekPreview(
            exoPlayer, pipeline.networkGate, pipeline.loadControl, isAvailable,
            // 不经 seekTo: 那里会把字幕时钟拨到预览位置, 预览期间字幕要停在开始时
            seek = { exoMediampPlayer.seekTo(it) },
            seekPlayback = { seekTo(it) },
            setOutputSurface = ::setPreviewOutputSurface,
            restoreOutput = ::restoreVideoOutput,
        ) { ended ->
            if (seekPreview === ended) {
                seekPreview = null
                previewOriginMillis.value = null
            }
        }.also {
            seekPreview = it
            previewOriginMillis.value = it.originMillis
        }
    }

    /** 拖动预览时视频输出接到小画面上 ([surface] 为 null 时哪儿也不画), 全屏那层留着最后一帧. */
    private fun setPreviewOutputSurface(surface: Surface?) {
        exoPlayer.setVideoSurface(surface)
        // 小画面同样要补上色彩信息 (见 applyVideoDataSpace), 否则 NVIDIA h264 解出的帧颜色不对
        val dataSpace = videoDataSpace
        if (surface != null && nvidiaVideoDecoderActive && dataSpace != 0 && surface.isValid) {
            SurfaceDataSpace.set(surface, dataSpace)
        }
    }

    /** 视频输出接回全屏的视频画面. */
    private fun restoreVideoOutput() {
        val surfaceView = findAndroidVideoSurface()
        if (surfaceView != null) exoPlayer.setVideoSurfaceView(surfaceView) else exoPlayer.clearVideoSurface()
    }

    /**
     * 播放位置; 拖动预览期间报开始时的位置 (见 [startSeekPreview]): 全屏停在那一刻, 弹幕、进度条、记忆进度也跟着停在那里.
     */
    override val currentPositionMillis: StateFlow<Long> = object : StateFlow<Long> {
        override val value: Long get() = previewOriginMillis.value ?: exoMediampPlayer.currentPositionMillis.value
        override val replayCache: List<Long> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<Long>): Nothing {
            combine(exoMediampPlayer.currentPositionMillis, previewOriginMillis) { live, origin -> origin ?: live }
                .distinctUntilChanged()
                .collect(collector)
            awaitCancellation()
        }
    }
    private val backgroundScope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job.Key]),
    )
    @Volatile
    private var closed = false

    /**
     * The media of the caller's latest [setMediaData], or `null` after [stopPlayback]. Reopened after
     * a video output detach timeout during playback.
     */
    @Volatile
    private var currentMediaData: MediaData? = null

    /**
     * ExoPlayer registers its analytics collector at construction, before MediaMP's Player.Listener,
     * so this listener sees the error first. MediaMP handles the error by stopping ExoPlayer and
     * clearing the media, so the failed position is taken from [AnalyticsListener.EventTime].
     */
    private val videoOutputTimeoutListener = object : AnalyticsListener {
        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: Media3PlaybackException) {
            if (closed || !error.isVideoOutputDetachTimeout()) return
            // A timeout during an open is thrown from setMediaData and retried by openMedia.
            if (exoMediampPlayer.state.value.mediaStatus == MediaStatus.Opening) return
            val data = currentMediaData as? UriMediaData ?: return
            val positionMillis = eventTime.currentPlaybackPositionMs
            val playWhenReady = exoPlayer.playWhenReady
            logger.warn(error) {
                "Video output detach timed out during playback, reopening at ${positionMillis}ms, playWhenReady=$playWhenReady"
            }
            // Reopen outside ExoPlayer's listener dispatch.
            backgroundScope.launch(Dispatchers.Main) {
                // The caller may have loaded other media or stopped playback in the meantime.
                if (currentMediaData !== data) return@launch
                try {
                    openMedia(data, playWhenReady, positionMillis)
                } catch (e: PlaybackException) {
                    logger.warn(e) { "Failed to reopen media after video output detach timeout" }
                }
            }
        }
    }

    /** [registerAndroidVideoSurface] 侧残留回调用来自我注销, 见那边注释。 */
    internal val isClosed: Boolean get() = closed

    /**
     * 最近一次从视频轨解出的 SDR dataspace (见 [toSdrDataSpaceOrNull]); 0 表示当前轨不该覆盖。
     * Shield 的 NVIDIA h264 硬解管线不理 MediaFormat 里的色彩信息, 必须由 app 直接写到
     * Surface 上, 否则视频层 dataspace 是垃圾, 撞上 ST2084 位就变假 HDR。详见 SurfaceDataSpace.kt。
     */
    @Volatile
    private var videoDataSpace: Int = 0

    /**
     * 我们写过 dataspace 且还没交还的 SurfaceView 与值; 退出覆盖状态时只清自己写过的,
     * 不碰平台设置的。记账绑定 SurfaceView 实例 (Surface 对象会被 SurfaceView 跨销毁/重建
     * 复用, 当不了代次标识): 该 view 的 surfaceDestroyed 即该代次 disconnect, sticky 随之
     * 消失, 记账直接释放。清理失败时保留记账, 由周期循环重试。
     */
    private var dataSpaceWrittenByUs: Int = 0
    private var dataSpaceWrittenTo: WeakReference<SurfaceView>? = null

    /**
     * 只对实锤有问题的 NVIDIA 解码器启用 Surface 直写 (正常设备让 MediaCodec 自己管
     * dataspace, 不去抢)。按解码器名判定而不是机型清单: 行为跟着解码器走。
     */
    @Volatile
    private var nvidiaVideoDecoderActive: Boolean = false

    private val _hasFrameOnCurrentSurface = MutableStateFlow(false)

    /** 见 [VideoSurfaceFrameSignal]: 换过输出面之后, 这一代上出过画面了没有。 */
    override val hasFrameOnCurrentSurface: StateFlow<Boolean> get() = _hasFrameOnCurrentSurface

    /**
     * 视频输出面换了一代 (新 SurfaceView 接上): 新 Surface 上还没有任何画面 ——
     * 解码器要把输出重定向过去, 就地改不了的芯片还得释放重建、从关键帧重解。
     *
     * 由 [registerAndroidVideoSurface] 在**确认换了 view** 时调用: 那个函数会随组合重跑,
     * 每次都清的话标记会在播放中被清成 false 再也回不去 ([onRenderedFirstFrame] 只在换面/
     * 重置时才发), 于是每次恢复都白等一个超时。
     */
    internal fun onVideoSurfaceReplaced() {
        _hasFrameOnCurrentSurface.value = false
    }

    init {
        assHandler.init(exoPlayer)
        exoPlayer.addAnalyticsListener(videoOutputTimeoutListener)
        // 字幕与音轨按界面语言和这部番记下的选择选, 音轨的选择由它下发给 ExoPlayer (见 PreferredTrackSelector)
        val preferredTracks = PreferredTrackSelector(exoPlayer, exoMediampPlayer.subtitleTracks, exoMediampPlayer.audioTracks) { trackChooser }
        preferredTracks.start(backgroundScope)
        pipeline.onNewMedia = {
            seekPreview?.end()
            preferredTracks.onNewMedia()
        }
        exoPlayer.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    val isNvidia = decoderName.contains("nvidia", ignoreCase = true)
                    if (isNvidia != nvidiaVideoDecoderActive) {
                        nvidiaVideoDecoderActive = isNvidia
                        logger.info { "Video decoder: $decoderName, surface dataspace workaround=${isNvidia}" }
                        if (!isNvidia) {
                            // 换成了正常解码器 (如硬解失败转软解), 把写过的值交还,
                            // 别让它接手一个带旧 sticky 标签的 Surface
                            clearVideoDataSpaceIfOwned()
                        }
                    }
                    if (isNvidia) applyVideoDataSpace()
                }

                override fun onRenderedFirstFrame(
                    eventTime: AnalyticsListener.EventTime,
                    output: Any,
                    renderTimeMs: Long,
                ) {
                    // media3 的语义正是我们要的: "自设置输出面 / 渲染器重置 / 换流以来的第一帧"
                    _hasFrameOnCurrentSurface.value = true
                    pipeline.onFirstFrameRendered()
                }

                override fun onVideoDecoderReleased(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                ) {
                    // 解码器走了就交还 Surface, 让接任者从干净状态自己设 (它可能在 configure
                    // 阶段就写好正确值, 晚清会擦掉它)。接任的还是 NVIDIA 的话 initialized 会
                    // 立即重写; dataspace 只影响之后入队的 buffer, 已显示的画面不受影响。
                    if (nvidiaVideoDecoderActive) {
                        nvidiaVideoDecoderActive = false
                        clearVideoDataSpaceIfOwned()
                    }
                }
            },
        )
        pipeline.onVideoFormat = { format ->
            // 只对实锤的 NVIDIA h264 生成目标值: HEVC 靠 MediaFormat 补齐已验证足够 (48+ 次
            // 采样), 其他编码没有实测。非 h264 / 非 SDR (HDR/未知 transfer) 都给 0 → 退出
            // 覆盖并把 Surface 清回 UNKNOWN; dataspace 是 sticky 的, 只停手不清会把旧 SDR
            // 标签留给后续 buffer
            val dataSpace = if (format.sampleMimeType == MimeTypes.VIDEO_H264) {
                format.toSdrDataSpaceOrNull() ?: 0
            } else {
                0
            }
            if (dataSpace != videoDataSpace) {
                videoDataSpace = dataSpace
                backgroundScope.launch(Dispatchers.Main.immediate) {
                    if (dataSpace == 0) clearVideoDataSpaceIfOwned() else applyVideoDataSpace()
                }
            }
        }
        backgroundScope.launch(Dispatchers.Main.immediate) {
            // Surface 可能重建 (回后台再回来), ACodec 也可能在重连时重置; 回到可播状态就补一手
            playbackState.collect { state ->
                if (state == PlaybackState.READY || state == PlaybackState.PLAYING) {
                    applyVideoDataSpace()
                }
            }
        }
        backgroundScope.launch(Dispatchers.Main.immediate) {
            // NVIDIA ROM 会在我们设置之后再盖写 (实测: 只在格式解出/READY 时写一次压不住,
            // 层上仍是垃圾; 加周期守护后稳定为 V0_BT709)。apply 每周期无条件写 —— 曾经
            // 试过"读回等于目标就跳过", 实测读回值和帧上生效值脱节, 守护被短路后层上
            // 永远是垃圾。只对 NVIDIA 解码器会话启用。
            while (isActive) {
                val shouldOwn = nvidiaVideoDecoderActive && videoDataSpace != 0
                if (!shouldOwn) {
                    if (dataSpaceWrittenByUs != 0) {
                        clearVideoDataSpaceIfOwned() // 上次清理失败 (Surface 暂不可用等) 的重试
                    }
                } else if (playbackState.value == PlaybackState.PLAYING) {
                    applyVideoDataSpace()
                }
                delay(2_000.milliseconds)
            }
        }
        backgroundScope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                // AssRenderer normally supplies this timestamp. MediaMP owns the ExoPlayer
                // builder, so drive the overlay from the same playback clock here instead.
                assHandler.videoTime = (previewOriginMillis.value ?: exoPlayer.currentPosition) * 1_000
                delay(16.milliseconds)
            }
        }
    }

    /**
     * 把当前视频轨的 SDR dataspace 写到视频 Surface 的 producer 端。主线程调用。
     * 只在确认了 NVIDIA 解码器的会话上生效。Surface 重建后 (如 SurfaceView 重新 attach)
     * 需要重放, 由 [registerAndroidVideoSurface] 侧的 surfaceCreated 回调、READY/PLAYING
     * 状态转换和周期重设触发。
     */
    private var lastLoggedApply: Long = Long.MIN_VALUE
    private var lastOverwriteSeen: Int = 0
    private var overwriteCount: Long = 0

    fun applyVideoDataSpace() {
        // closed 也要挡: SurfaceView 上的回调比播放器活得久, close 后的 surfaceCreated
        // 不能再拿旧值写 Surface (会污染接手同一个 view 的新播放器)
        if (closed || !nvidiaVideoDecoderActive) return
        val dataSpace = videoDataSpace
        if (dataSpace == 0) return
        val surfaceView = findAndroidVideoSurface() ?: return
        val surface = surfaceView.holder.surface ?: return
        if (!surface.isValid) return
        // 不做"先读后写就地返回"的短路: 实测 (2026-08-19 13:2x) 读回值和帧上实际携带的值
        // 会脱节 —— 写成功 (result=0) 后层上仍是 ROM 盖写的 0x43d, 周期守护被短路后再也
        // 压不回去。写本身是进程内改字段无 IPC, 每 2 秒无条件写一次没有成本。
        // 读回只用于观测盖写行为 (进 app.log 取证)。
        val current = SurfaceDataSpace.get(surface)
        if (current >= 0 && current != dataSpace) {
            overwriteCount++
            if (current != lastOverwriteSeen || overwriteCount % 100 == 0L) {
                lastOverwriteSeen = current
                logger.info {
                    "Video surface dataspace overwritten to 0x${current.toString(16)} (x$overwriteCount), rewriting"
                }
            }
        }
        val result = SurfaceDataSpace.set(surface, dataSpace)
        if (result == 0) {
            dataSpaceWrittenByUs = dataSpace
            dataSpaceWrittenTo = WeakReference(surfaceView)
        }
        // 周期性重设会反复走到这里, 只在 (值, 结果) 变化时打日志
        val signature = (dataSpace.toLong() shl 32) or (result.toLong() and 0xFFFFFFFFL)
        if (signature != lastLoggedApply) {
            lastLoggedApply = signature
            logger.info {
                "Set video surface dataspace to 0x${dataSpace.toString(16)}: result=$result"
            }
        }
    }

    /**
     * 退出 SDR 覆盖状态: 把写过的那个 SurfaceView 当前 Surface 的 sticky dataspace 清回
     * UNKNOWN(0)。只清自己写过的 —— 当前值已被平台改走 (比如设成了 PQ/HLG) 就不碰;
     * 写过的 Surface 已不可用则 sticky 随之消失, 直接放掉记账; 清理失败保留记账等重试。
     */
    internal fun clearVideoDataSpaceIfOwned() {
        val written = dataSpaceWrittenByUs
        if (written == 0) return
        val surface = dataSpaceWrittenTo?.get()?.holder?.surface
        if (surface == null || !surface.isValid) {
            releaseDataSpaceOwnership() // 写过的那代 Surface 没了, sticky 跟着没了
            return
        }
        val current = SurfaceDataSpace.get(surface)
        if (current >= 0 && current != written) {
            releaseDataSpaceOwnership() // 平台已接管, 不碰
            return
        }
        val result = SurfaceDataSpace.set(surface, 0)
        if (result == 0) {
            logger.info { "Cleared video surface dataspace (was ours 0x${written.toString(16)})" }
            releaseDataSpaceOwnership()
        }
        // 失败: 保留记账, 由周期循环 (会话内) 或 close 后的有界重试接手
    }

    /**
     * close 路径的清理失败无法靠周期循环重试 (backgroundScope 已取消), 用主线程 Handler
     * 做有界重试。每次仍走 [clearVideoDataSpaceIfOwned] 的完整安全检查 (Surface 没了 /
     * 值被新播放器改走都只放账不写), 记账清零即停。
     */
    private fun scheduleDataSpaceClearRetry(attemptsLeft: Int) {
        if (attemptsLeft <= 0 || dataSpaceWrittenByUs == 0) return
        Handler(Looper.getMainLooper()).postDelayed(
            {
                clearVideoDataSpaceIfOwned()
                scheduleDataSpaceClearRetry(attemptsLeft - 1)
            },
            500,
        )
    }

    /**
     * [registerAndroidVideoSurface] 侧的 surfaceDestroyed 回调。只释放属于该 view 的记账
     * (销毁即该代次 disconnect, sticky 随之消失, 不必写 0); 别的 view 销毁不动当前记账 ——
     * 新 Surface 已接任时旧 view 的销毁不能清掉新账。
     */
    internal fun onVideoSurfaceDestroyed(surfaceView: SurfaceView) {
        if (dataSpaceWrittenTo?.get() === surfaceView) {
            releaseDataSpaceOwnership()
        }
        // 当前那一块没了 = 这一代输出面上再没有画面。**只认当前那一块**: 页面切换时新 view
        // 先注册、旧 view 的 surfaceDestroyed 后到, 不加这个判断会把新一代刚出的帧标记抹掉
        if (findAndroidVideoSurface() === surfaceView) {
            _hasFrameOnCurrentSurface.value = false
        }
    }

    private fun releaseDataSpaceOwnership() {
        dataSpaceWrittenByUs = 0
        dataSpaceWrittenTo = null
        lastLoggedApply = Long.MIN_VALUE
    }

    override suspend fun setMediaData(data: MediaData, playWhenReady: Boolean, startPositionMillis: Long) {
        currentMediaData = data
        openMedia(data, playWhenReady, startPositionMillis)
    }

    /**
     * Opens [data]. A [UriMediaData] whose open fails with a video output detach timeout is retried at
     * the same position, up to [MAX_OPEN_ATTEMPTS] attempts in total.
     */
    private suspend fun openMedia(data: MediaData, playWhenReady: Boolean, startPositionMillis: Long) {
        var attempt = 1
        while (true) {
            // Wrap so the interceptor can reuse the SeekableInput the backend opens for the
            // session; see TrackingSeekableInputMediaData.
            val playerData = if (data is SeekableInputMediaData) {
                TrackingSeekableInputMediaData(data)
            } else {
                data
            }
            try {
                exoMediampPlayer.setMediaData(playerData, playWhenReady, startPositionMillis)
                return
            } catch (e: PlaybackException) {
                if (data !is UriMediaData || !e.isVideoOutputDetachTimeout() || attempt >= MAX_OPEN_ATTEMPTS) throw e
                currentCoroutineContext().ensureActive()
                logger.warn(e) { "Video output detach timed out while opening, retrying (attempt $attempt/$MAX_OPEN_ATTEMPTS)" }
                attempt++
            }
        }
    }

    override fun stopPlayback() {
        currentMediaData = null
        exoMediampPlayer.stopPlayback()
    }

    /**
     * Unwraps [TrackingSeekableInputMediaData] so consumers observe the exact [MediaData]
     * instance they loaded (e.g. `is TorrentMediaData` checks in `CacheProgressProvider`).
     */
    override val mediaData: StateFlow<MediaData?> = object : StateFlow<MediaData?> {
        override val value: MediaData? get() = exoMediampPlayer.mediaData.value.unwrapTracking()
        override val replayCache: List<MediaData?> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<MediaData?>): Nothing =
            exoMediampPlayer.mediaData.collect(
                FlowCollector { value -> collector.emit(value.unwrapTracking()) },
            )
    }

    override fun seekTo(positionMillis: Long) {
        exoMediampPlayer.seekTo(positionMillis)
        // ExoPlayer applies a seek asynchronously. Update libass immediately as well so the
        // paused overlay does not retain the subtitle from the previous playback position.
        val positionUs = positionMillis * 1_000
        assHandler.videoTime = positionUs
        // AssHandler throttles clock callbacks while video is playing. A paused seek only
        // produces one distinct timestamp, so request that frame explicitly as well.
        assHandler.videoTimeCallback?.invoke(positionUs)
    }

    override fun skip(deltaMillis: Long) {
        // The interface default would delegate to the backend's seekTo (bypassing the override
        // above via class delegation), skipping the libass clock refresh; route it explicitly.
        seekTo(currentPositionMillis.value + deltaMillis)
    }

    override fun close() {
        if (closed) return
        closed = true
        currentMediaData = null
        // 先掐掉后续写入 (applyVideoDataSpace 的 closed 门控之外再兜一层), 再交还已写的
        nvidiaVideoDecoderActive = false
        videoDataSpace = 0
        clearVideoDataSpaceIfOwned() // Surface 归 UI 所有, 可能比播放器活得久, 交还再走
        scheduleDataSpaceClearRetry(attemptsLeft = 3)
        backgroundScope.cancel()
        exoPlayer.removeAnalyticsListener(videoOutputTimeoutListener)
        pipeline.close() // 在 assHandler.release 之前: 后台补的字体不再往里加
        exoPlayer.removeListener(assHandler)
        assHandler.release()
        exoMediampPlayer.close()
    }

    private companion object {
        private val logger = logger<LibassExoPlayerMediampPlayer>()

        /**
         * How long the main thread waits for the playback thread to release a video output. Longer
         * than Media3's 2 s default to leave room for the video effects (GL) pipeline; the main
         * thread is blocked meanwhile, so it must stay well below the 5 s input ANR threshold.
         */
        const val DETACH_SURFACE_TIMEOUT_MILLIS = 3_000L

        const val MAX_OPEN_ATTEMPTS = 3
    }
}

/**
 * Whether [this] or its cause chain is ExoPlayer's video output detach timeout
 * ([ExoTimeoutException.TIMEOUT_OPERATION_DETACH_SURFACE]).
 */
@AndroidxOptIn(UnstableApi::class)
internal fun Throwable.isVideoOutputDetachTimeout(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is ExoTimeoutException && it.timeoutOperation == ExoTimeoutException.TIMEOUT_OPERATION_DETACH_SURFACE
    }

/**
 * Builds libass-enabled media sources. Installed as the backend's media source interceptor
 * (`docs/playback-state-v2.md` §11): invoked on the main dispatcher during each open, after
 * [ExoPlayerMediampPlayer] built the default source (and, for non-`file://`
 * [SeekableInputMediaData], eagerly opened the session's [SeekableInput]), and before ExoPlayer
 * prepares it.
 */
@AndroidxOptIn(UnstableApi::class)
private class LibassMediaSourcePipeline(
    private val context: Context,
    parentCoroutineContext: CoroutineContext,
    private val proxyConfig: () -> PlaybackProxyConfig?,
    private val diskCacheEnabled: () -> Boolean,
) {
    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job.Key]))

    init {
        // 在第一次建 libass 对象之前: 渲染器初始化时系统字体扫一遍就缓存下来
        LibassFontconfig.ensure(context)
    }

    val assHandler = AssHandler(
        // 画在视频上面单独一层 GL 视图里 (LiftableAssSubtitleView), 控制层出现时下半部分能挪开
        renderType = AssRenderType.OVERLAY_OPEN_GL,
        config = AssHandlerConfig(maxRenderPixels = 1920 * 1080),
    )
    private val subtitleParserFactory = AssSubtitleParserFactory(assHandler)
    private val extractorsFactory = DefaultExtractorsFactory()
        .withAssMkvSupport(subtitleParserFactory, assHandler)

    /** 被限速的网盘直链换一套缓冲策略 (见 [ThrottledSourceLoadControl]); 每准备一个媒体按它的提示头切换. */
    val loadControl = ThrottledSourceLoadControl()

    /** 拖动预览期间挡住存本机的媒体的联网读取 (见 [SeekPreview]). */
    val networkGate = PlaybackNetworkGate()

    /** 每准备一个新媒体时先调 (主线程): 播放器借此结束进行中的拖动预览. */
    var onNewMedia: (() -> Unit)? = null

    // 播放器构造后由 LibassExoPlayerMediampPlayer 设置; 每个视频轨的最终 Format 经这里回调
    // 给它, 用来决定要不要把 dataspace 直接写到 Surface 上 (NVIDIA h264 硬解不理 MediaFormat).
    var onVideoFormat: ((androidx.media3.common.Format) -> Unit)? = null

    /** 当前媒体推迟读的字体附件 (见 [FontDeferringMkvExtractor]); 只在主线程读写. */
    private var deferredFonts: DeferredMkvFonts? = null

    // withColorInfoRepair: 色彩三项不全时 Shield 不设视频层 dataspace, 留着的垃圾撞上 ST2084 位
    // 就变假 HDR. 包在 MediaSource 出口是为了覆盖所有入口 (progressive/HLS/兜底默认源),
    // 详见 ColorInfoRepair.kt
    fun intercept(defaultSource: MediaSource, data: MediaData): MediaSource {
        onNewMedia?.invoke()
        deferredFonts?.cancel()
        deferredFonts = null
        loadControl.throttled = data is UriMediaData && parallelConnectionsOf(data) != null
        return (createLibassMediaSource(data) ?: defaultSource)
            .withColorInfoRepair(onVideoFormat = { onVideoFormat?.invoke(it) })
    }

    /** 当前媒体出了第一帧: 推迟的字体附件可以开始读了. */
    fun onFirstFrameRendered() {
        deferredFonts?.onFirstFrameRendered()
    }

    fun close() {
        deferredFonts?.cancel()
        deferredFonts = null
        networkGate.open()
        scope.cancel()
    }

    /** 解析器要求并发几路 (见 [PlaybackRequestHints.PARALLEL_RANGE_HEADER]); 没要求时为 null. */
    private fun parallelConnectionsOf(data: UriMediaData): Int? =
        data.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER]?.toIntOrNull()?.takeIf { it > 1 }

    private fun createLibassMediaSource(data: MediaData): MediaSource? {
        // 网盘直链时另给推迟的字体附件用 (见 DeferredMkvFonts)
        var fontsDataSourceFactory: DataSource.Factory? = null
        // 网盘直链下过的数据按这个内容标识存在本机 (见 PlaybackDiskCache); 其余在线源按地址存, 不用它
        var cacheKey: String? = null
        val dataSourceFactory = when (data) {
            is UriMediaData -> {
                // 解析器给的提示头只给播放器看, 不发给服务器
                val parallel = parallelConnectionsOf(data)
                val userAgent = data.headers["User-Agent"] ?: DEFAULT_USER_AGENT
                val headers = PlaybackRequestHints.strip(data.headers)
                if (parallel != null) {
                    // 网盘直链: 分块并发下载, 连接走会换节点的客户端 (下载域名偶尔整组节点连不上, 见 RangeHttpClients);
                    // 播放跟不上时多开连接 (见 ThrottledSourceLoadControl.boosted)
                    val upstream = RangeHttpClients.factory(proxyConfig(), userAgent, headers)
                    // 设置里开了「边下边播」时, 下过的数据存在本机: 往回拖、拖动预览、再看同一个文件时从盘上读
                    val key = PlaybackDiskCache.keyOf(data)?.takeIf { diskCacheEnabled() }
                    val cache = key?.let { PlaybackDiskCache.get(context) }
                    // 文件总长播放与补字体共用, 本机缓存里记着的话直接拿来: 打开时不必先单独发一个请求问长度
                    val resourceLength = AtomicLong(-1)
                    val lengthHint: () -> Long = if (key != null && cache != null) {
                        { PlaybackDiskCache.contentLength(cache, key) }
                    } else {
                        { -1 }
                    }
                    // 字体在出画面后读, 正赶上缓冲最少、播放多开着连接的时候: 少开一半, 也不跟着多开
                    val fonts = ParallelRangeDataSource.Factory(
                        upstream, (parallel / 2).coerceAtLeast(2),
                        knownLength = resourceLength, lengthHint = lengthHint,
                    )
                    val playback = ParallelRangeDataSource.Factory(
                        upstream, parallel,
                        boosted = { loadControl.boosted },
                        knownLength = resourceLength, lengthHint = lengthHint,
                    )
                    if (key == null || cache == null) {
                        fontsDataSourceFactory = fonts
                        // 拖动预览时 (见 SeekPreview) 联网之前先过这道门
                        networkGate.wrap(playback)
                    } else {
                        cacheKey = key
                        logger.info { "Caching $key on disk while playing" }
                        fontsDataSourceFactory = PlaybackDiskCache.cacheDataSourceFactory(cache, fonts)
                        // 播放器按 MediaItem 的 customCacheKey 给媒体请求带上内容标识; 外挂字幕的请求不带, 直接下
                        RoutingDataSourceFactory(
                            routesToPrimary = { it.key != null },
                            // 拖动预览时 (见 SeekPreview) 本机有的直接读, 没存的部分要联网时先过这道门
                            primaryDataSourceFactory = PlaybackDiskCache.cacheDataSourceFactory(cache, networkGate.wrap(playback)),
                            fallbackDataSourceFactory = playback,
                        )
                    }
                } else {
                    // 拖动预览时 (见 SeekPreview) 联网之前先过这道门
                    val http = networkGate.wrap(
                        createPlaybackHttpDataSourceFactory(
                            proxyConfig = proxyConfig(),
                            userAgent = userAgent,
                            headers = headers,
                            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                        ),
                    )
                    // 设置里开了「边下边播」时下过的数据存在本机, 按地址认 (这类直链不像网盘那样另给内容标识).
                    // m3u8 播放列表不存: 列表随时可能更新, 去广告也要拿原样的列表
                    val cache = if (diskCacheEnabled()) PlaybackDiskCache.get(context) else null
                    if (cache == null) {
                        http
                    } else {
                        RoutingDataSourceFactory(
                            routesToPrimary = { Util.inferContentTypeForUriAndMimeType(it.uri, null) != C.CONTENT_TYPE_HLS },
                            primaryDataSourceFactory = PlaybackDiskCache.cacheDataSourceFactory(cache, http),
                            fallbackDataSourceFactory = http,
                        )
                    }
                }
            }

            is SeekableInputMediaData -> {
                if (data.uri.startsWith("file://")) {
                    DefaultDataSource.Factory(context)
                } else {
                    // ExoPlayerMediampPlayer.openImpl opened the session's SeekableInput before
                    // invoking this interceptor and registered it as a session resource (the
                    // state machine closes it when the session ends). The createInput contract
                    // allows only one open input at a time, so reuse that input rather than
                    // opening another. If the wrapper or its input is missing (unexpected),
                    // fall back to the backend's default source.
                    val tracking = data as? TrackingSeekableInputMediaData ?: return null
                    val primaryInput = tracking.primaryInput ?: return null
                    RoutingDataSourceFactory(
                        routesToPrimary = { it.uri.toString() == data.uri },
                        primaryDataSourceFactory = DataSource.Factory {
                            VideoDataDataSource(tracking.source, primaryInput)
                        },
                        fallbackDataSourceFactory = DefaultDataSource.Factory(context),
                    )
                }
            }
        }

        val mediaItem = MediaItem.Builder()
            .setUri(data.playbackUri)
            .setCustomCacheKey(cacheKey)
            .setSubtitleConfigurations(
                data.extraFiles.subtitles.mapIndexed { index, subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.uri)).apply {
                        setId("animeko-external-subtitle-$index")
                        subtitle.label?.let(::setLabel)
                        subtitle.mimeType?.let(::setMimeType)
                        subtitle.language?.let(::setLanguage)
                    }.build()
                },
            )
            .build()

        if (data.isHls && data.extraFiles.subtitles.isEmpty()) {
            // 资源站的 m3u8 常在正片中间插广告段, 解析列表时去掉 (见 HlsAdFilter)
            return HlsMediaSource.Factory(dataSourceFactory)
                .setPlaylistParserFactory(AdFilteringHlsPlaylistParserFactory())
                .setSubtitleParserFactory(subtitleParserFactory)
                .createMediaSource(mediaItem)
        }
        // 网盘直链限速时, mkv 开头的大段字体附件推迟到出画面后再读
        val extractors = fontsDataSourceFactory?.let { factory ->
            val fonts = DeferredMkvFonts(Uri.parse(data.playbackUri), cacheKey, factory, assHandler, scope)
            deferredFonts = fonts
            fontDeferringExtractorsFactory(fonts)
        } ?: extractorsFactory
        return DefaultMediaSourceFactory(dataSourceFactory, extractors)
            .setSubtitleParserFactory(subtitleParserFactory)
            .createMediaSource(mediaItem)
    }

    /** 与 [extractorsFactory] 相同, 但 mkv 用 [FontDeferringMkvExtractor]. */
    private fun fontDeferringExtractorsFactory(fonts: DeferredMkvFonts) = ExtractorsFactory {
        extractorsFactory.createExtractors().map { extractor ->
            if (extractor is MatroskaExtractor) FontDeferringMkvExtractor(subtitleParserFactory, assHandler, fonts) else extractor
        }.toTypedArray()
    }

    /** 与 [DefaultMediaSourceFactory] 判断 HLS 的方式相同 (按地址后缀). */
    private val MediaData.isHls: Boolean
        get() = Util.inferContentTypeForUriAndMimeType(Uri.parse(playbackUri), null) == C.CONTENT_TYPE_HLS

    private val MediaData.playbackUri: String
        get() = when (this) {
            is UriMediaData -> uri
            is SeekableInputMediaData -> uri
        }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"
    }
}

private fun MediaData?.unwrapTracking(): MediaData? =
    (this as? TrackingSeekableInputMediaData)?.source ?: this

/**
 * Captures the first [SeekableInput] created from [source] — the one
 * [ExoPlayerMediampPlayer.openImpl] opens for the session before the media source interceptor
 * runs — so [LibassMediaSourcePipeline] can route playback reads through it.
 *
 * Ownership: the captured input belongs to the backend session ([ExoPlayerMediampPlayer]'s
 * state machine closes it when the session ends); neither this class nor [VideoDataDataSource]
 * closes it.
 */
@OptIn(ExperimentalMediampApi::class)
private class TrackingSeekableInputMediaData(
    val source: SeekableInputMediaData,
) : SeekableInputMediaData by source {
    var primaryInput: SeekableInput? = null
        private set

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput =
        source.createInput(coroutineContext).also { input ->
            if (primaryInput == null) {
                primaryInput = input
            }
        }
}

/** 每次打开时按请求选数据源: [routesToPrimary] 为 true 的走 [primaryDataSourceFactory], 其余走 [fallbackDataSourceFactory]. */
@AndroidxOptIn(UnstableApi::class)
private class RoutingDataSourceFactory(
    private val routesToPrimary: (DataSpec) -> Boolean,
    private val primaryDataSourceFactory: DataSource.Factory,
    private val fallbackDataSourceFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(
        routesToPrimary,
        primaryDataSourceFactory,
        fallbackDataSourceFactory,
    )
}

@AndroidxOptIn(UnstableApi::class)
private class RoutingDataSource(
    private val routesToPrimary: (DataSpec) -> Boolean,
    private val primaryDataSourceFactory: DataSource.Factory,
    private val fallbackDataSourceFactory: DataSource.Factory,
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var activeDataSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        check(activeDataSource == null) { "Data source is already open" }
        val dataSource = if (routesToPrimary(dataSpec)) {
            primaryDataSourceFactory.createDataSource()
        } else {
            fallbackDataSourceFactory.createDataSource()
        }
        transferListeners.forEach(dataSource::addTransferListener)
        activeDataSource = dataSource
        return dataSource.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(activeDataSource) { "Data source is not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = activeDataSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        activeDataSource?.responseHeaders.orEmpty()

    override fun close() {
        val source = activeDataSource ?: return
        // **必须先置空再关**: ExoPlayer 关这个源走的是
        // `DataSourceUtil.closeQuietly` —— 它吃掉 IOException 就不再管这个实例了。而底层
        // (尤其是在线源走的 HTTP DataSource) 在拖动进度条取消请求时 close() 抛 IOException
        // 是常态, 不是意外。原先写法是 `activeDataSource?.close(); activeDataSource = null`,
        // 异常一抛后一行就不执行 —— 脏状态留到下一次 open, 撞上
        // `check(activeDataSource == null)` 变成 Loader 的 UnexpectedLoaderException → Source error,
        // 界面上就是"加载失败"并自动切下一个源。
        // 2026-09-20 真机: 在线源反复拖进度条必现。
        //
        // 异常照旧往上抛 (不吞), 只是状态先复位。
        activeDataSource = null
        source.close()
    }
}

class LibassExoPlayerMediampPlayerFactory(
    private val enableHighQualityAudioTimeStretch: () -> Boolean = { true },
    private val proxyConfig: () -> PlaybackProxyConfig? = { null },
    /** 见 [LibassExoPlayerMediampPlayer] 构造参数里的同名参数. */
    private val diskCacheEnabled: () -> Boolean = { false },
) : MediampPlayerFactory<LibassExoPlayerMediampPlayer> {
    override val forClass: KClass<LibassExoPlayerMediampPlayer>
        get() = LibassExoPlayerMediampPlayer::class

    override fun create(
        context: Any,
        parentCoroutineContext: CoroutineContext,
    ): LibassExoPlayerMediampPlayer {
        require(context is Context) { "The context argument must be android.content.Context on Android" }
        val audioTimeStretch = if (enableHighQualityAudioTimeStretch()) {
            ExoPlayerAudioTimeStretch.HighQualityWsola
        } else {
            ExoPlayerAudioTimeStretch.Media3Default
        }
        return LibassExoPlayerMediampPlayer(
            context,
            parentCoroutineContext,
            audioTimeStretch,
            configurePlayerBuilder = { builder ->
                builder.setLoadControl(aniExoPlayerLoadControl())
            },
            proxyConfig = proxyConfig,
            diskCacheEnabled = diskCacheEnabled,
        )
    }
}
