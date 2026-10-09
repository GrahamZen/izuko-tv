/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.app.videoplayer.player.PlayerBufferPolicy
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import androidx.annotation.OptIn as AndroidxOptIn

/**
 * 正在播被限速的网盘直链时 ([throttled], 解析器给了并发提示, 见 `PlaybackRequestHints.PARALLEL_RANGE_HEADER`) 换一套缓冲策略, 其余源照 ExoPlayer 默认.
 *
 * 这种源的下载速度常常跟不上局部码率 (片头这类画面复杂的段落码率是平均的好几倍), 4K 片子的平均码率也常常只比下载速度低一点.
 * 默认攒一两秒就播, 播几秒又卡, 看起来就是一直在缓冲; 这里:
 * - 开播 / 跳转后攒够 [START_US] 再播, 播放中卡住后攒够 [REBUFFER_RESUME_US]; 同一个媒体每多卡一次, 卡住后要攒的翻一倍,
 *   最多 [MAX_REBUFFER_RESUME_US] (见 [requiredBufferUs]): 再卡说明这段时间下载一直跟不上, 每次多攒些, 总的等待差不多, 卡的次数少得多.
 *   网速够快时多攒的几秒一两秒就到. 缓冲占的内存先到了字节上限 (码率极高) 时照样开播, 免得一直卡着.
 *   在按这些门槛等的时候 [bufferWait] 报要攒到多少, 页面据此说明为什么要等;
 * - 缓冲上限放到 [THROTTLED_MAX_BUFFER_US]: 码率低的段落多攒些, 留给后面码率高的段落. 占用的内存仍受 [DefaultLoadControl] 按轨道算的字节上限约束.
 * - 缓冲低于 [BOOST_ON_US] 或卡住时多开连接 ([boosted], 见 `ParallelRangeDataSource.boostedConnectionsFor`), 回到 [BOOST_OFF_US] 再收回:
 *   每个连接被限速, 片子码率高时 (1080p 一集上 GB) 平时的路数怎么攒都跟不上. 开播 / 跳转后缓冲从零攒起, 一开始就是多开的:
 *   头一轮的小块由多开的所有连接一起下 (见 `ParallelRangeReader`), 跳到没下过的位置出画面快. 读文件头、索引这类短范围
 *   拆成几块按平时的路数算, 多开不影响它们.
 * - 暂停时 (离开播放页时保留的会话也是暂停着的) 只攒到 [PAUSED_MAX_BUFFER_US]: 播放中的缓冲本来就在多开连接的两个阈值之间来回,
 *   恢复播放时有这么多就够; 再往上攒只是占内存, 4K 片子会一直攒到字节上限 (一百多 MB).
 * 拖动预览由主播放器跳到预览位置时 ([previewing], 见 [SeekPreview]), 任何源都只读到 [PREVIEW_MAX_BUFFER_US], 出画面就算就绪:
 * 每挪一步都要重新缓冲, 照常攒的话一步要从盘上读几十秒的数据进内存. 在一个位置上停下来看清楚之后 ([previewParked]) 照暂停时的规则往后攒:
 * 确认时多半就从这里播, 闲着的这段时间先缓冲好, 开播后不至于马上卡; 再挪一步时播放器跳转会掐掉这次加载, 不耽误下一个位置.
 *
 * 只转发 media3 1.9 实际调用的那组方法 (带 [PlayerId] / [LoadControl.Parameters] 的).
 */
@AndroidxOptIn(UnstableApi::class)
internal class ThrottledSourceLoadControl : LoadControl {
    /** 当前媒体是不是被限速的网盘直链; 在准备媒体时 (主线程) 设, 播放线程读. 每设一次算换了一个媒体, 卡住的次数从头算. */
    @Volatile
    var throttled = false
        set(value) {
            field = value
            mediaChanged = true
            _bufferWait.value = null
        }

    /** [throttled] 设过之后还没在播放线程上清掉这个媒体的卡顿计数. */
    @Volatile
    private var mediaChanged = false

    /** 这个媒体播放中卡住了几次 (拖动、跳转造成的缓冲不算); 只在播放线程读写. */
    private var stalls = 0

    /** 正在等一次卡住后的缓冲 (已经计过数); 只在播放线程读写. */
    private var waitingAfterStall = false

    private val _bufferWait = MutableStateFlow<ThrottledBufferWait?>(null)

    /** 限速源正在按加长的门槛攒缓冲时要攒到多少, 不在等时为 null. 播放线程写. */
    val bufferWait: StateFlow<ThrottledBufferWait?> = _bufferWait.asStateFlow()

    /** 限速源此刻要不要多开连接; 播放线程写, 加载线程读. */
    @Volatile
    var boosted = false
        private set

    /** 拖动预览中 (见 [SeekPreview]); 主线程写, 播放线程读. */
    @Volatile
    var previewing = false

    /** 拖动预览停在一个位置上、那一帧已经出来了 (见 [SeekPreview]): 不再按 [PREVIEW_MAX_BUFFER_US] 截住, 照暂停时的规则缓冲. 主线程写, 播放线程读. */
    @Volatile
    var previewParked = false

    // 上下限都设成限速源要的上限; 普通源在 shouldContinueLoading 里按默认的 50 秒截住, 效果同默认 (默认上下限也都是 50 秒).
    // 往后保留 [PlayerBufferPolicy.backward] (保留到上一个关键帧): media3 默认播过就释放, 在线源往回退一点也要重新下载
    private val delegate = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            (THROTTLED_MAX_BUFFER_US / 1000).toInt(),
            (THROTTLED_MAX_BUFFER_US / 1000).toInt(),
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setBackBuffer(PlayerBufferPolicy.backward.inWholeMilliseconds.toInt(), /* retainBackBufferFromKeyframe = */ true)
        .build()

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        updateBoost(parameters)
        if (previewing && !previewParked && parameters.bufferedDurationUs >= PREVIEW_MAX_BUFFER_US) return false
        if (!throttled && parameters.bufferedDurationUs >= DEFAULT_MAX_BUFFER_US) return false
        if (throttled && !parameters.playWhenReady && parameters.bufferedDurationUs >= PAUSED_MAX_BUFFER_US) return false
        return delegate.shouldContinueLoading(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        updateBoost(parameters)
        if (previewing) return true // 预览时播放器停着, 这里只决定画面出来后算不算就绪
        if (!throttled) return delegate.shouldStartPlayback(parameters)
        if (mediaChanged) {
            mediaChanged = false
            resetStalls()
        }
        if (parameters.rebuffering && !waitingAfterStall) {
            waitingAfterStall = true
            stalls++
        } else if (!parameters.rebuffering) {
            waitingAfterStall = false // 卡住时拖走了: 这次卡住就此结束
        }
        val needed = (requiredBufferUs(if (parameters.rebuffering) stalls else 0) / parameters.playbackSpeed.coerceAtLeast(0.1f)).toLong()
        // 字节先到了上限 (码率极高) 就攒不到那么多秒, 这时照样开播, 免得一直卡着
        val start = parameters.bufferedDurationUs >= needed || isBufferFull(parameters.playerId)
        if (start) waitingAfterStall = false
        _bufferWait.value = if (start) null else ThrottledBufferWait(needed / 1000, if (parameters.rebuffering) stalls else 0)
        return start
    }

    private fun resetStalls() {
        stalls = 0
        waitingAfterStall = false
        _bufferWait.value = null
    }

    private fun updateBoost(parameters: LoadControl.Parameters) {
        val buffered = parameters.bufferedDurationUs
        val next = when {
            !throttled -> false
            parameters.rebuffering || buffered < BOOST_ON_US -> true
            buffered >= BOOST_OFF_US -> false
            else -> boosted
        }
        if (next != boosted) {
            boosted = next
            logger.info {
                "${if (next) "More" else "Normal"} connections for the throttled source " +
                        "(buffered ${buffered / 1_000_000}s, rebuffering=${parameters.rebuffering})"
            }
        }
    }

    private fun resetBoost() {
        boosted = false
    }

    /** 缓冲占的内存已经到了视频轨的默认字节上限 (再加上音轨的就停止加载了), 再等也攒不下更多. */
    private fun isBufferFull(playerId: PlayerId): Boolean =
        delegate.getAllocator(playerId).totalBytesAllocated >= DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE

    override fun onPrepared(playerId: PlayerId) {
        resetBoost()
        resetStalls()
        delegate.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) {
        resetBoost()
        resetStalls()
        delegate.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        resetBoost()
        resetStalls()
        delegate.onReleased(playerId)
    }

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    companion object {
        /** 普通源的缓冲上限, 同 ExoPlayer 默认. */
        private const val DEFAULT_MAX_BUFFER_US = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * 1000L

        /** 限速源的缓冲上限. */
        const val THROTTLED_MAX_BUFFER_US = 120_000_000L

        /** 限速源开播 / 跳转后要攒多少才播. */
        const val START_US = 5_000_000L

        /** 限速源第一次卡住之后要攒多少才接着播; 之后每多卡一次翻一倍. */
        const val REBUFFER_RESUME_US = 10_000_000L

        /** 限速源卡住之后最多攒这么多才接着播. */
        const val MAX_REBUFFER_RESUME_US = 60_000_000L

        /**
         * 限速源要攒够多少 (微秒) 才播, 不算播放速度. [stall]: 这是这个媒体第几次播放中卡住, 0 = 开播 / 跳转后.
         */
        fun requiredBufferUs(stall: Int): Long = when {
            stall <= 0 -> START_US
            else -> (REBUFFER_RESUME_US shl (stall - 1).coerceAtMost(3)).coerceAtMost(MAX_REBUFFER_RESUME_US)
        }

        /** 播放中缓冲低于这些就多开连接. */
        const val BOOST_ON_US = 30_000_000L

        /** 多开连接后缓冲回到这些就收回. */
        const val BOOST_OFF_US = 60_000_000L

        /** 限速源暂停时的缓冲上限: 与收回多开连接的阈值相同. */
        const val PAUSED_MAX_BUFFER_US = BOOST_OFF_US

        /** 拖动预览时的缓冲上限: 读到圆点之后一点就够出画面. */
        const val PREVIEW_MAX_BUFFER_US = 1_000_000L

        private val logger = logger<ThrottledSourceLoadControl>()
    }
}

/**
 * 限速源正在攒缓冲, 要攒到 [targetMillis] 才播 (见 [ThrottledSourceLoadControl]).
 * [stall]: 这是这个媒体第几次播放中卡住; 0 = 开播或跳转之后, 还没卡过.
 */
data class ThrottledBufferWait(val targetMillis: Long, val stall: Int)
