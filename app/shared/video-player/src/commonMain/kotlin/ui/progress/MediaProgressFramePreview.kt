/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.progress

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.PreviewFrame
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.math.exp
import kotlin.time.TimeSource

/**
 * 进度条预览帧的状态: 悬浮 (桌面) 或拖动 (触摸) 进度条时, 加载并展示目标位置的视频帧.
 *
 * 为了降低延迟:
 * - 请求位置对齐到 [positionGridMillis] 网格, 拖动时只在跨格时才真正解码;
 * - 最近解码的帧按格子做 LRU 缓存, 回扫时立即命中;
 * - [prewarm] 可在播放开始时后台预热解码器, 避免首次悬浮时等待秒级的解码器启动.
 *
 * @see MediaProgressSlider
 */
@Stable
class MediaProgressFramePreviewState(
    /**
     * 加载 [positionMillis] 处的预览帧. 返回 `null` 表示暂不可用 (不会清空已显示的帧).
     */
    private val fetchFrame: suspend (positionMillis: Long) -> ImageBitmap?,
    private val debounceMillis: Long = 50,
    /**
     * 预览位置的对齐粒度. 视频关键帧间隔通常为数秒, 更细的粒度并不会带来更准确的画面.
     */
    private val positionGridMillis: Long = 2_000,
    /**
     * 帧缓存容量. 每帧约 80 KB (192x108 ARGB), 默认 8 帧约 650 KB;
     * 命中场景主要是"刚扫过又扫回来", 缓存最近一小段轨迹即可.
     */
    cacheSize: Int = 8,
    /**
     * 当前媒体的取帧是否自己去取数据, 因而播放器还没缓冲到的位置也能预览.
     *
     * 为 false 时 [MediaProgressSlider] 只对已缓存完成的位置请求帧: BT 源的取帧读的是本地已下载的 piece,
     * 去取没下完的位置会抢占播放位置的下载优先级. 在线源 (播放器自己下载的 HTTP / HLS) 的取帧器独立请求数据,
     * 进度条上的「已缓存」只是播放器的缓冲范围, 用它拦截的话往前拖基本拿不到缩略图.
     */
    val fetchesUncachedPositions: () -> Boolean = { false },
    /**
     * 为 true 时维护 [loadStatus], 浮窗据此区分「正在取」「这次没取到」「这里还没下载」; 取帧失败只记在
     * [loadStatus] 上, 不把 [framesAvailable] 置 false (那会把整个画面位收掉, 看起来和没开画面预览一样).
     *
     * 为 false 时失败即退化成只显示时间. TV 用 true: 一帧要取好几秒, 不给状态的话只看得见一块灰底.
     */
    private val reportsLoadStatus: Boolean = false,
    /**
     * 这台设备 / 当前媒体会不会去取帧 (取帧源自己知道, 比如这台机器上关掉了第二路解码器).
     * 为 false 时浮窗只显示时间, 不显示「加载失败」: 那不是某一次出了错. 只在 [reportsLoadStatus] 为 true 时使用.
     */
    private val isSupported: () -> Boolean = { true },
) {
    /**
     * 当前要展示的预览帧. `null` 表示无帧可展示 (浮窗显示占位背景).
     */
    var frame: ImageBitmap? by mutableStateOf(null)
        private set

    /**
     * 当前预览位置的取帧进展. 只在 [reportsLoadStatus] 为 true 时维护, 否则恒为 [FramePreviewLoadStatus.Idle].
     */
    var loadStatus: FramePreviewLoadStatus by mutableStateOf(FramePreviewLoadStatus.Idle)
        private set

    /** 本次进入 [FramePreviewLoadStatus.Loading] 的时刻, 供浮窗估算进度. 每换一个位置重新计. */
    var loadingSince: TimeSource.Monotonic.ValueTimeMark? by mutableStateOf(null)
        private set

    /**
     * 估算进度的基准: 最近几次取帧 (从进入 Loading 到出帧, 含防抖与排队) 耗时的指数平均.
     *
     * 取帧器报不出真实进度 (media3 的取帧器与 MediaMetadataRetriever 都只给结果), 只能按这个视频
     * 最近的实际耗时估个大概.
     */
    private var expectedLoadMillis: Long = DEFAULT_EXPECTED_LOAD_MILLIS

    /**
     * 本次取帧已等了 [elapsedMillis] 时的估算进度: 基准耗时之前匀速走到 80%, 之后越走越慢, 最多到 99%.
     * 停在某个百分比不动看起来像卡死, 走满了又像已经好了 (会话里第一帧最长要等 30 秒).
     */
    fun estimatedLoadProgress(elapsedMillis: Long): Float {
        val x = elapsedMillis.toDouble() / expectedLoadMillis
        val progress = if (x <= 1) 0.8 * x else 0.8 + 0.19 * (1 - exp(-(x - 1)))
        return progress.toFloat()
    }

    /**
     * 本媒体能否取到帧. 取帧失败置 false, 成功置 true, 换媒体时复位.
     *
     * 取帧能力是**整个媒体**的属性而不是某个位置的: 平台取帧器要么能解析这个容器, 要么完全不能
     * (最典型的是 HLS/m3u8 —— Android 的 MediaMetadataRetriever 走平台 extractor, 没有 HLS
     * 解复用器, 而在线源基本都是 m3u8). 唯一按位置失败的情况是 BT 源未下载区域, 那种情况
     * 调用方根本不会发起请求 (见 [MediaProgressSlider] 里的 `isPositionCached` 判断).
     *
     * 消费方据此决定**要不要给帧留位置**: 不看这个标志的话, 取不到帧的媒体上浮窗里会一直是
     * 一块 160x90 的占位黑底 (那正是"缩略图永远是黑的"的由来), 而正确的降级是只显示时间.
     *
     * 起播时的 [prewarm] 顺带就是一次能力探测: 用户第一次唤出进度条之前这个值就已经定下来了,
     * 所以不会出现"先给一块黑底, 过一会儿才发现取不到"的闪动.
     *
     * [reportsLoadStatus] 为 true 时只由 [isSupported] 决定: 失败记在 [loadStatus] 上, 画面位留着显示失败.
     */
    var framesAvailable: Boolean by mutableStateOf(true)
        private set

    private var frameGridKey = Long.MIN_VALUE
    private val cache = androidx.collection.LruCache<Long, ImageBitmap>(cacheSize)

    private fun gridKeyOf(positionMillis: Long): Long =
        if (positionGridMillis > 0) positionMillis / positionGridMillis else positionMillis

    /**
     * 请求加载 [positionMillis] 处的帧. 预期在 `collectLatest` 中调用: 拖动到新位置时旧请求会被取消.
     * 缓存命中立即显示; 加载成功前保留上一帧, 避免闪烁.
     */
    internal suspend fun requestFrame(positionMillis: Long) {
        if (reportsLoadStatus && !checkSupported()) return
        val key = gridKeyOf(positionMillis)
        if (key == frameGridKey && frame != null) {
            loadStatus = FramePreviewLoadStatus.Idle
            return
        }
        cache[key]?.let {
            frame = it
            frameGridKey = key
            loadStatus = FramePreviewLoadStatus.Idle
            return
        }
        val startedAt = TimeSource.Monotonic.markNow()
        if (reportsLoadStatus) {
            // 此刻画面位上若还留着帧, 那是上一个位置的 (浮窗会把它压暗, 见 Loading 的说明)
            loadingSince = startedAt
            loadStatus = FramePreviewLoadStatus.Loading
        }
        delay(debounceMillis) // debounce: 快速滑动时, 更新的位置会取消本次请求
        val newFrame = fetchFrame(alignToGrid(key, positionMillis))
        if (newFrame == null) {
            // 底层实现把所有异常都吞了 (见 mediamp 的 ExoFramePreview), 这里至少留一行,
            // 否则"取不到帧"在日志里完全没有痕迹
            logger.warn { "Frame preview unavailable at $positionMillis ms (decoder returned null)" }
            if (reportsLoadStatus) {
                // 画面位留着显示失败; 旧帧是上一个位置的, 不能让它冒充这里的画面
                frame = null
                frameGridKey = Long.MIN_VALUE
                loadStatus = FramePreviewLoadStatus.Failed
            } else {
                framesAvailable = false
            }
            return
        }
        if (reportsLoadStatus) recordLoadDuration(startedAt.elapsedNow().inWholeMilliseconds)
        cache.put(key, newFrame)
        frame = newFrame
        frameGridKey = key
        framesAvailable = true
        loadStatus = FramePreviewLoadStatus.Idle
    }

    /**
     * 当前预览位置还没下载, 调用方因此不发起请求 (BT 源, 见 [fetchesUncachedPositions]). 不标出来的话,
     * 画面位里留着的是上一个位置的旧帧, 或者一块一直不变的灰底.
     */
    internal fun onPositionNotDownloaded() {
        if (!reportsLoadStatus || !checkSupported()) return
        frame = null
        frameGridKey = Long.MIN_VALUE
        loadStatus = FramePreviewLoadStatus.NotDownloaded
    }

    /** [isSupported] 为 false 时收起画面位 (浮窗只显示时间) 并返回 false. */
    private fun checkSupported(): Boolean {
        if (isSupported()) {
            framesAvailable = true
            return true
        }
        framesAvailable = false
        frame = null
        frameGridKey = Long.MIN_VALUE
        loadStatus = FramePreviewLoadStatus.Idle
        return false
    }

    private fun recordLoadDuration(millis: Long) {
        expectedLoadMillis = (expectedLoadMillis * (1 - LOAD_DURATION_WEIGHT) + millis * LOAD_DURATION_WEIGHT)
            .toLong()
            .coerceAtLeast(MIN_EXPECTED_LOAD_MILLIS)
    }

    /**
     * 后台预热: 解码 [positionMillis] 附近的一帧存入缓存, 不改变当前显示.
     * 用于播放开始时提前启动预览解码器.
     */
    suspend fun prewarm(positionMillis: Long) {
        val key = gridKeyOf(positionMillis)
        if (cache[key] != null) return
        val newFrame = fetchFrame(alignToGrid(key, positionMillis))
        if (newFrame == null) {
            if (reportsLoadStatus) {
                // 画面位不收: 拖动时照常去取, 取不到再显示失败 —— 预热失败也可能只是那一下网络不好
                logger.warn { "Frame preview prewarm failed at $positionMillis ms" }
                return
            }
            // 预热位置一定是当前播放点 (数据必然可用), 这里失败就是取帧器打不开这个媒体
            logger.warn { "Frame preview prewarm failed at $positionMillis ms; frame preview disabled for this media" }
            framesAvailable = false
            return
        }
        logger.info { "Frame preview prewarmed at $positionMillis ms" }
        cache.put(key, newFrame)
        framesAvailable = true
    }

    private fun alignToGrid(key: Long, positionMillis: Long): Long =
        if (positionGridMillis > 0) key * positionGridMillis else positionMillis

    /**
     * 预览结束 (浮窗隐藏) 时清空当前帧, 避免下次悬浮时先显示过期位置的帧. 缓存保留.
     */
    fun onPreviewFinished() {
        frame = null
        frameGridKey = Long.MIN_VALUE
        loadStatus = FramePreviewLoadStatus.Idle
        loadingSince = null
    }

    /**
     * 媒体切换时清空缓存, 避免展示上一个视频的帧.
     */
    fun onMediaChanged() {
        cache.evictAll()
        frame = null
        frameGridKey = Long.MIN_VALUE
        framesAvailable = true // 新媒体重新探测, 上一个不能取帧不代表这个也不能
        loadStatus = FramePreviewLoadStatus.Idle
        loadingSince = null
        expectedLoadMillis = DEFAULT_EXPECTED_LOAD_MILLIS
    }
}

/** 预览浮窗里画面位的状态, 见 [MediaProgressFramePreviewState.loadStatus]. */
enum class FramePreviewLoadStatus {
    /** 没有在取: 画面位显示的就是当前位置的帧, 或者还没开始预览. */
    Idle,

    /** 正在取当前位置的帧. 此时 [MediaProgressFramePreviewState.frame] 若不为 null, 是上一个位置留下的旧帧. */
    Loading,

    /** 当前位置这次没取到 (超时 / 取帧器出错). 挪开再挪回来会重新取. */
    Failed,

    /** 当前位置还没下载, 不去取 (BT 源只预览已下载的部分, 见 [MediaProgressFramePreviewState.fetchesUncachedPositions]). */
    NotDownloaded,
}

/** 还没有实测耗时时的估算基准: TV 上稳定态取一帧 1.3~4.5 秒, 加 200ms 防抖. */
private const val DEFAULT_EXPECTED_LOAD_MILLIS = 3_000L

/** 估算基准的下限: 连着命中很快的几次 (BT 已下载区域的关键帧) 之后, 进度环也别一下就跑满. */
private const val MIN_EXPECTED_LOAD_MILLIS = 500L

/** 新一次耗时在指数平均里的权重. */
private const val LOAD_DURATION_WEIGHT = 0.4

/**
 * 从 [player] 的 [FramePreview] feature 创建 [MediaProgressFramePreviewState].
 *
 * 当播放器后端不支持取帧时返回 `null`, 此时进度条浮窗只显示时间.
 */
@Composable
fun rememberMediaProgressFramePreviewState(
    player: MediampPlayer,
    maxWidth: Dp = 192.dp,
    maxHeight: Dp = 128.dp,
    /** See [createMediaProgressFramePreviewState]. */
    mediaFramePreview: (MediaData) -> FramePreview? = { null },
): MediaProgressFramePreviewState? {
    val framePreview = remember(player) { player.features[FramePreview] } ?: return null
    val density = LocalDensity.current
    val state = remember(framePreview, density, maxWidth, maxHeight, mediaFramePreview) {
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        val maxHeightPx = with(density) { maxHeight.roundToPx() }
        MediaProgressFramePreviewState(
            fetchFrame = { positionMillis ->
                val preview = player.mediaData.value?.let(mediaFramePreview) ?: framePreview
                preview.getPreviewFrame(positionMillis, maxWidthPx, maxHeightPx)?.toImageBitmap()
            },
        )
    }
    LaunchedEffect(state, player) {
        player.mediaData.collect { data ->
            state.onMediaChanged()
            if (data != null) {
                // 预热预览解码器 (第二个解码器实例启动需要秒级时间), 避免首次悬浮时长时间显示占位框.
                // 取当前播放位置附近的帧: 该区域一定已在缓冲, 不会抢占下载优先级.
                runCatching { state.prewarm(player.currentPositionMillis.value) }
            }
        }
    }
    return state
}

/** 非 Compose 状态持有者使用的播放器取帧适配器。 */
fun createMediaProgressFramePreviewState(
    player: MediampPlayer,
    maxWidth: Int,
    maxHeight: Int,
    /**
     * A preview the current media supplies itself, used instead of the player's for as long as it
     * returns one; resolved on every request, since the media changes under the same state.
     */
    mediaFramePreview: (MediaData) -> FramePreview? = { null },
): MediaProgressFramePreviewState? {
    val feature = player.features[FramePreview] ?: return null
    return MediaProgressFramePreviewState(fetchFrame = { position ->
        val preview = player.mediaData.value?.let(mediaFramePreview) ?: feature
        preview.getPreviewFrame(position, maxWidth, maxHeight)?.toImageBitmap()
    })
}

/**
 * 将 [PreviewFrame] 的 ARGB 像素转换为 [ImageBitmap].
 */
internal expect fun PreviewFrame.toImageBitmap(): ImageBitmap

private val logger = logger<MediaProgressFramePreviewState>()
