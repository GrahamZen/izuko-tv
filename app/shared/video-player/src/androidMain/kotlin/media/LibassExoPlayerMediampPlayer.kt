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
import android.view.SurfaceView
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openani.mediamp.ExperimentalMediampApi
import me.him188.ani.app.videoplayer.ui.findAndroidVideoSurface
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.MediampPlayerFactory
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.exoplayer.ExoPlayerAudioTimeStretch
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import java.lang.ref.WeakReference
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
 */
@OptIn(InternalForInheritanceMediampApi::class)
@AndroidxOptIn(UnstableApi::class)
class LibassExoPlayerMediampPlayer private constructor(
    parentCoroutineContext: CoroutineContext,
    private val pipeline: LibassMediaSourcePipeline,
    internal val exoMediampPlayer: ExoPlayerMediampPlayer,
) : MediampPlayer by exoMediampPlayer {
    constructor(
        context: Context,
        parentCoroutineContext: CoroutineContext,
        audioTimeStretch: ExoPlayerAudioTimeStretch = ExoPlayerAudioTimeStretch.HighQualityWsola,
    ) : this(context, parentCoroutineContext, audioTimeStretch, LibassMediaSourcePipeline(context))

    private constructor(
        context: Context,
        parentCoroutineContext: CoroutineContext,
        audioTimeStretch: ExoPlayerAudioTimeStretch,
        pipeline: LibassMediaSourcePipeline,
    ) : this(
        parentCoroutineContext,
        pipeline,
        ExoPlayerMediampPlayer(
            context,
            parentCoroutineContext,
            audioTimeStretch,
            mediaSourceInterceptor = pipeline::intercept,
        ),
    )

    internal val assHandler: AssHandler get() = pipeline.assHandler

    private val exoPlayer: ExoPlayer get() = exoMediampPlayer.impl
    private val backgroundScope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job.Key]),
    )
    @Volatile
    private var closed = false

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

    init {
        assHandler.init(exoPlayer)
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
                assHandler.videoTime = exoPlayer.currentPosition * 1_000
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
    }

    private fun releaseDataSpaceOwnership() {
        dataSpaceWrittenByUs = 0
        dataSpaceWrittenTo = null
        lastLoggedApply = Long.MIN_VALUE
    }

    override suspend fun setMediaData(data: MediaData, playWhenReady: Boolean, startPositionMillis: Long) {
        // Wrap so the interceptor can reuse the SeekableInput the backend opens for the
        // session; see TrackingSeekableInputMediaData.
        val playerData = if (data is SeekableInputMediaData) {
            TrackingSeekableInputMediaData(data)
        } else {
            data
        }
        exoMediampPlayer.setMediaData(playerData, playWhenReady, startPositionMillis)
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
        // 先掐掉后续写入 (applyVideoDataSpace 的 closed 门控之外再兜一层), 再交还已写的
        nvidiaVideoDecoderActive = false
        videoDataSpace = 0
        clearVideoDataSpaceIfOwned() // Surface 归 UI 所有, 可能比播放器活得久, 交还再走
        scheduleDataSpaceClearRetry(attemptsLeft = 3)
        backgroundScope.cancel()
        exoPlayer.removeListener(assHandler)
        assHandler.release()
        exoMediampPlayer.close()
    }
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
) {
    val assHandler = AssHandler(
        renderType = AssRenderType.OVERLAY_OPEN_GL,
        config = AssHandlerConfig(maxRenderPixels = 1920 * 1080),
    )
    private val subtitleParserFactory = AssSubtitleParserFactory(assHandler)
    private val extractorsFactory = DefaultExtractorsFactory()
        .withAssMkvSupport(subtitleParserFactory, assHandler)

    // 播放器构造后由 LibassExoPlayerMediampPlayer 设置; 每个视频轨的最终 Format 经这里回调
    // 给它, 用来决定要不要把 dataspace 直接写到 Surface 上 (NVIDIA h264 硬解不理 MediaFormat).
    var onVideoFormat: ((androidx.media3.common.Format) -> Unit)? = null

    // withColorInfoRepair: 色彩三项不全时 Shield 不设视频层 dataspace, 留着的垃圾撞上 ST2084 位
    // 就变假 HDR. 包在 MediaSource 出口是为了覆盖所有入口 (progressive/HLS/兜底默认源),
    // 详见 ColorInfoRepair.kt
    fun intercept(defaultSource: MediaSource, data: MediaData): MediaSource =
        (createLibassMediaSource(data) ?: defaultSource)
            .withColorInfoRepair(onVideoFormat = { onVideoFormat?.invoke(it) })

    private fun createLibassMediaSource(data: MediaData): MediaSource? {
        val dataSourceFactory = when (data) {
            is UriMediaData -> DefaultHttpDataSource.Factory()
                .setUserAgent(data.headers["User-Agent"] ?: DEFAULT_USER_AGENT)
                .setDefaultRequestProperties(data.headers)
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MILLIS)

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
                        mediaUri = data.uri,
                        mediaDataSourceFactory = DataSource.Factory {
                            VideoDataDataSource(tracking.source, primaryInput)
                        },
                        fallbackDataSourceFactory = DefaultDataSource.Factory(context),
                    )
                }
            }
        }

        val mediaItem = MediaItem.Builder()
            .setUri(data.playbackUri)
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

        return DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            .setSubtitleParserFactory(subtitleParserFactory)
            .createMediaSource(mediaItem)
    }

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

@AndroidxOptIn(UnstableApi::class)
private class RoutingDataSourceFactory(
    private val mediaUri: String,
    private val mediaDataSourceFactory: DataSource.Factory,
    private val fallbackDataSourceFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(
        mediaUri,
        mediaDataSourceFactory,
        fallbackDataSourceFactory,
    )
}

@AndroidxOptIn(UnstableApi::class)
private class RoutingDataSource(
    private val mediaUri: String,
    private val mediaDataSourceFactory: DataSource.Factory,
    private val fallbackDataSourceFactory: DataSource.Factory,
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var activeDataSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        check(activeDataSource == null) { "Data source is already open" }
        val dataSource = if (dataSpec.uri.toString() == mediaUri) {
            mediaDataSourceFactory.createDataSource()
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
        activeDataSource?.close()
        activeDataSource = null
    }
}

class LibassExoPlayerMediampPlayerFactory(
    private val enableHighQualityAudioTimeStretch: () -> Boolean = { true },
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
        return LibassExoPlayerMediampPlayer(context, parentCoroutineContext, audioTimeStretch)
    }
}
