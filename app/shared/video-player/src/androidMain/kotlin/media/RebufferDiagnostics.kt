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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.io.IOException
import java.util.Locale

/**
 * 正常播放中卡住缓冲时留下线索, 事后从 app.log 搜 `[Rebuffer]` 就能分清是网络没喂上、片源码率太高, 还是设备解码出了问题.
 *
 * 平时只在内存里记最近 [LoadWindow.WINDOW_MILLIS] 的取数据记录与测速采样 (每条几十字节, 不写文件、不查系统);
 * 实收的字节按播放器实际从网络读到的算 ([delivered], 见 [DeliveredBytesMeter]), 没有时按测速采样.
 * 开播、拖动、换媒体之后的第一次缓冲不算. 播放中卡住满 [MIN_REPORT_MILLIS] 时写一行 (卡死不恢复的也留得下), 恢复 (或停止、换媒体) 时再写一行;
 * 不到 1 秒就恢复的抖动只计数, 每攒 [SHORT_STALL_REPORT_COUNT] 次或换媒体、停止时汇总写一行.
 * 查 Wi-Fi 与整机下载量 (一次系统调用、读 /proc) 与写日志都放在 [ioDispatcher] 上, 不占播放与界面的线程.
 *
 * 判读: 卡住时缓冲余量接近 0 = 数据没喂上 (看最近实收与片源码率、最慢请求、出错); 余量还有好几秒却卡住 = 解码 / 渲染的问题
 * (例如 Shield 的解码服务卡死). 卡住期间整机收的比播放器多很多 = 有别的东西在抢带宽.
 *
 * 回调都在播放器的应用线程 (主线程) 上, 下面的状态只在那里读写.
 */
internal class RebufferDiagnostics(
    private val context: Context,
    private val scope: CoroutineScope,
    private val delivered: RecentBytes? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AnalyticsListener {
    private val window = LoadWindow()
    private val tracker = StallTracker()
    private var bitrateEstimate = 0L
    private var droppedFrames = 0
    private var videoFormat: Format? = null

    /** 进行中那次卡住的序号, 卡住结束时清零; 后台的延时报告据此判断还是不是同一次. 主线程写, 后台读. */
    @Volatile
    private var currentStall = 0

    /** 已经写了「卡住」那一行的序号与当时的系统快照 (后台写), 恢复时据此决定写不写恢复那行、算期间的下载量. */
    @Volatile
    private var reported: Pair<Int, SystemSnapshot?>? = null
    private var stallWindowBytes = 0L
    private var stallAheadMillis = 0L

    private var shortStalls = 0
    private var shortStallsSince = 0L
    private var shortStallAheadMillis = 0L

    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
        tracker.onPlayWhenReady(playWhenReady)
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            handle(tracker.onSeek(clock()), eventTime)
        }
    }

    override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
        handle(tracker.onMediaChanged(clock()), eventTime)
        flushShortStalls()
        window.clear(clock())
        droppedFrames = 0
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val playerState = when (state) {
            Player.STATE_READY -> StallTracker.State.READY
            Player.STATE_BUFFERING -> StallTracker.State.BUFFERING
            else -> StallTracker.State.STOPPED
        }
        handle(tracker.onState(playerState, clock()), eventTime)
        if (playerState == StallTracker.State.STOPPED) flushShortStalls()
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        record(loadEventInfo, mediaLoadData, error = null)
    }

    override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        record(loadEventInfo, mediaLoadData, error = null)
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        record(loadEventInfo, mediaLoadData, error = error.toString().take(120))
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        this.bitrateEstimate = bitrateEstimate
        window.addTransfer(clock(), totalBytesLoaded)
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        videoFormat = format
    }

    private fun record(info: LoadEventInfo, data: MediaLoadData, error: String?) {
        // 只记媒体数据与播放列表 (HLS 的 m3u8), 字幕、DRM 之类不算
        if (data.dataType != C.DATA_TYPE_MEDIA && data.dataType != C.DATA_TYPE_MANIFEST) return
        window.addLoad(
            LoadWindow.Load(
                atMillis = clock(),
                host = info.uri.host.orEmpty(),
                bytes = info.bytesLoaded,
                durationMillis = info.loadDurationMs,
                error = error,
            ),
        )
    }

    private fun handle(event: StallTracker.Event?, eventTime: AnalyticsListener.EventTime) {
        when (event) {
            null -> Unit
            is StallTracker.Event.Started -> onStallStarted(event, eventTime)
            is StallTracker.Event.Ended -> onStallEnded(event)
        }
    }

    private fun onStallStarted(event: StallTracker.Event.Started, eventTime: AnalyticsListener.EventTime) {
        val number = event.number
        currentStall = number
        stallWindowBytes = receivedTotal()
        stallAheadMillis = eventTime.totalBufferedDurationMs
        val summary = window.summary(clock(), receivedBytesIn = delivered?.let { it::bytesInLast })
        val head = buildString {
            append("[Rebuffer] #").append(number).append(" stalled at ").append(formatPosition(eventTime.currentPlaybackPositionMs))
            append(" (buffered ahead ").append(seconds(eventTime.totalBufferedDurationMs)).append(")")
            append("; ").append(summary.describe())
            append("; estimate ").append(mbps(bitrateEstimate))
            append("; video ").append(describe(videoFormat))
            append("; dropped ").append(droppedFrames).append(" frames")
            if (shortStalls > 0) append("; ").append(shortStalls).append(" short stalls under 1 s before this")
        }
        scope.launch(ioDispatcher) {
            delay(MIN_REPORT_MILLIS)
            if (currentStall != number) return@launch
            val snapshot = runCatching { SystemSnapshot.take(context) }.getOrNull()
            reported = number to snapshot
            logger.info { head + "; " + (snapshot?.network ?: "network unknown") }
        }
    }

    private fun onStallEnded(event: StallTracker.Event.Ended) {
        currentStall = 0
        val start = reported?.takeIf { it.first == event.number }
        if (start == null) {
            // 没写过「卡住」那行: 不到 1 秒的抖动, 只计数
            if (shortStalls == 0) shortStallsSince = clock() - event.durationMillis
            shortStalls++
            shortStallAheadMillis = stallAheadMillis
            if (shortStalls >= SHORT_STALL_REPORT_COUNT) flushShortStalls()
            return
        }
        reported = null
        shortStalls = 0
        val playerBytes = receivedTotal() - stallWindowBytes
        val head = buildString {
            append("[Rebuffer] #").append(event.number).append(' ').append(event.outcome.text).append(" after ").append(seconds(event.durationMillis))
            append("; player received ").append(megabytes(playerBytes)).append(" during the stall")
        }
        scope.launch(ioDispatcher) {
            val before = start.second
            val after = runCatching { SystemSnapshot.take(context) }.getOrNull()
            val traffic = if (before != null && after != null) {
                val device = after.totalRxBytes - before.totalRxBytes
                val own = after.uidRxBytes - before.uidRxBytes
                "; device received ${megabytes(device)} (this app ${megabytes(own)}, others ${megabytes((device - own).coerceAtLeast(0))})"
            } else {
                ""
            }
            logger.info { head + traffic + "; " + (after?.network ?: "network unknown") }
        }
    }

    /** 把攒下的不到 1 秒的抖动汇总写一行. 缓冲余量还有好几秒 = 解码 / 渲染跟不上, 余量接近 0 = 数据刚好喂不上. */
    private fun flushShortStalls() {
        if (shortStalls == 0) return
        val line = "[Rebuffer] $shortStalls short stalls under 1 s in the last ${seconds(clock() - shortStallsSince)} " +
            "(last buffered ahead ${seconds(shortStallAheadMillis)}, dropped $droppedFrames frames, video ${describe(videoFormat)})"
        shortStalls = 0
        scope.launch(ioDispatcher) { logger.info { line } }
    }

    /** 播放器一共从网络收到的字节, 用来算卡住期间收了多少. */
    private fun receivedTotal(): Long = delivered?.total() ?: window.transferredBytesTotal

    private fun describe(format: Format?): String {
        if (format == null) return "unknown"
        val bitrate = listOf(format.averageBitrate, format.bitrate, format.peakBitrate).firstOrNull { it != Format.NO_VALUE }
        return buildString {
            append(format.codecs ?: format.sampleMimeType ?: "?")
            if (format.width != Format.NO_VALUE) append(' ').append(format.width).append('x').append(format.height)
            append(' ').append(if (bitrate != null) mbps(bitrate.toLong()) else "bitrate unknown")
        }
    }

    /** 卡住 / 恢复时的系统状态: 整机与本 app 累计收了多少, 当前网络. */
    private class SystemSnapshot(
        val totalRxBytes: Long,
        val uidRxBytes: Long,
        val network: String,
    ) {
        companion object {
            fun take(context: Context) = SystemSnapshot(
                totalRxBytes = TrafficStats.getTotalRxBytes(),
                uidRxBytes = TrafficStats.getUidRxBytes(Process.myUid()),
                network = describeNetwork(context),
            )

            @Suppress("DEPRECATION") // connectionInfo: 新接口要注册回调才拿得到, 这里只要一个即时值
            private fun describeNetwork(context: Context): String = runCatching {
                val connectivity = context.getSystemService(ConnectivityManager::class.java)
                val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                    ?: return@runCatching "no active network"
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
                        if (info == null) {
                            "Wi-Fi"
                        } else {
                            val link = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                "rx ${info.rxLinkSpeedMbps} / tx ${info.txLinkSpeedMbps} Mbps"
                            } else {
                                "${info.linkSpeed} Mbps"
                            }
                            "Wi-Fi rssi ${info.rssi} dBm, link $link, ${info.frequency} MHz"
                        }
                    }

                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "network (downstream estimate ${capabilities.linkDownstreamBandwidthKbps} kbps)"
                } + if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) ", VPN" else ""
            }.getOrElse { "network unknown (${it.javaClass.simpleName})" }
        }
    }

    private companion object {
        private val logger = logger<RebufferDiagnostics>()

        /** 卡住满这么久才算一次要报告的卡住; 更短的只计数. */
        const val MIN_REPORT_MILLIS = 1_000L

        /** 不到 1 秒的抖动每攒这么多次汇总写一行. */
        const val SHORT_STALL_REPORT_COUNT = 20
    }
}

/**
 * 最近 [windowMillis] 内的取数据记录 ([addLoad]: 每次请求取完 / 取消 / 出错) 与测速采样 ([addTransfer]: 播放器测网速时报的这段时间收到的字节).
 * 直链文件播放器不会每块都报「取完」, 测速采样也要等一次读取结束才报; 实收的字节优先用调用方给的 (播放器实际读到的),
 * 没有时以测速采样为准, 再没有用请求的字节. 不是线程安全的.
 */
internal class LoadWindow(private val windowMillis: Long = WINDOW_MILLIS) {
    data class Load(val atMillis: Long, val host: String, val bytes: Long, val durationMillis: Long, val error: String?)

    data class Summary(
        val spanMillis: Long,
        val transferredBytes: Long,
        val requests: Int,
        val errors: Int,
        val slowest: Load?,
        val sinceLastLoadMillis: Long?,
        val lastError: String?,
    ) {
        /** 这段时间平均实收速度 (bit/s); 时间太短算不出时为 null. */
        val receivedBitsPerSecond: Long?
            get() = if (spanMillis < 1000) null else transferredBytes * 8 * 1000 / spanMillis

        fun describe(): String = buildString {
            append("last ").append(seconds(spanMillis)).append(": received ").append(megabytes(transferredBytes))
            receivedBitsPerSecond?.let { append(" (").append(mbps(it)).append(')') }
            append(", ").append(requests).append(" requests")
            slowest?.let { append(", slowest ").append(it.host).append(' ').append(seconds(it.durationMillis)).append(" for ").append(megabytes(it.bytes)) }
            sinceLastLoadMillis?.let { append(", last finished ").append(seconds(it)).append(" ago") }
            append(", ").append(errors).append(" errors")
            lastError?.let { append(" (").append(it).append(')') }
        }
    }

    private val loads = ArrayDeque<Load>()
    private val transfers = ArrayDeque<Pair<Long, Long>>()
    private var firstAtMillis: Long? = null

    /** 记下来的测速采样字节累计 (不随窗口丢弃), 用来算两个时刻之间收了多少. */
    var transferredBytesTotal = 0L
        private set

    fun addLoad(load: Load) {
        if (firstAtMillis == null) firstAtMillis = load.atMillis
        loads.addLast(load)
        trim(load.atMillis)
    }

    fun addTransfer(atMillis: Long, bytes: Long) {
        if (firstAtMillis == null) firstAtMillis = atMillis
        transfers.addLast(atMillis to bytes)
        transferredBytesTotal += bytes
        trim(atMillis)
    }

    /** 换了媒体: 之前的记录都不算, 时间从 [nowMillis] 算起. */
    fun clear(nowMillis: Long) {
        loads.clear()
        transfers.clear()
        firstAtMillis = nowMillis
    }

    /** @param receivedBytesIn 最近多少毫秒里实际收到的字节; 调用方不知道时为 null */
    fun summary(nowMillis: Long, receivedBytesIn: ((spanMillis: Long) -> Long)? = null): Summary {
        trim(nowMillis)
        val first = firstAtMillis
        val span = if (first == null) 0 else (nowMillis - first).coerceIn(0, windowMillis)
        val transferred = receivedBytesIn?.invoke(span)
            ?: transfers.sumOf { it.second }.takeIf { transfers.isNotEmpty() }
            ?: loads.sumOf { it.bytes }
        val errors = loads.filter { it.error != null }
        return Summary(
            spanMillis = span,
            transferredBytes = transferred,
            requests = loads.size,
            errors = errors.size,
            slowest = loads.filter { it.error == null }.maxByOrNull { it.durationMillis },
            sinceLastLoadMillis = loads.lastOrNull()?.let { nowMillis - it.atMillis },
            lastError = errors.lastOrNull()?.error,
        )
    }

    private fun trim(nowMillis: Long) {
        val oldest = nowMillis - windowMillis
        while (loads.isNotEmpty() && loads.first().atMillis < oldest) loads.removeFirst()
        while (transfers.isNotEmpty() && transfers.first().first < oldest) transfers.removeFirst()
    }

    companion object {
        const val WINDOW_MILLIS = 30_000L
    }
}

/**
 * 判断一次缓冲算不算「播放中卡住」: 开播、拖动、换媒体之后到第一次就绪之前的缓冲不算, 没在播 (暂停) 时进入的也不算.
 * 卡住之后就绪 = 恢复; 停止 (出错、关闭)、拖动或换媒体 = 这次卡住就此结束. 不是线程安全的.
 */
internal class StallTracker {
    enum class State { READY, BUFFERING, STOPPED }

    enum class Outcome(val text: String) {
        RESUMED("resumed"),
        STOPPED("stopped"),
        SEEKED("seeked away"),
        MEDIA_CHANGED("media changed"),
    }

    sealed interface Event {
        data class Started(val number: Int) : Event
        data class Ended(val number: Int, val durationMillis: Long, val outcome: Outcome) : Event
    }

    private var settling = true
    private var playWhenReady = false
    private var stallStartedAt: Long? = null
    private var count = 0

    fun onPlayWhenReady(playWhenReady: Boolean) {
        this.playWhenReady = playWhenReady
    }

    fun onSeek(nowMillis: Long): Event? = interrupt(nowMillis, Outcome.SEEKED)

    fun onMediaChanged(nowMillis: Long): Event? = interrupt(nowMillis, Outcome.MEDIA_CHANGED)

    fun onState(state: State, nowMillis: Long): Event? = when (state) {
        State.READY -> {
            settling = false
            end(nowMillis, Outcome.RESUMED)
        }

        State.BUFFERING -> if (!settling && playWhenReady && stallStartedAt == null) {
            stallStartedAt = nowMillis
            Event.Started(++count)
        } else {
            null
        }

        State.STOPPED -> interrupt(nowMillis, Outcome.STOPPED)
    }

    private fun interrupt(nowMillis: Long, outcome: Outcome): Event? {
        settling = true
        return end(nowMillis, outcome)
    }

    private fun end(nowMillis: Long, outcome: Outcome): Event? {
        val started = stallStartedAt ?: return null
        stallStartedAt = null
        return Event.Ended(count, nowMillis - started, outcome)
    }
}

private fun seconds(millis: Long) = String.format(Locale.ROOT, "%.1f s", millis / 1000.0)

private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)

private fun mbps(bitsPerSecond: Long) = String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000.0)

private fun formatPosition(millis: Long): String {
    val total = millis / 1000
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}
