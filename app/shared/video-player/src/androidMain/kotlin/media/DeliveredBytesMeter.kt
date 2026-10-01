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
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.annotation.OptIn as AndroidxOptIn

/**
 * 播放器的测速器, 另外记下播放器实际从网络读到的字节 ([delivered]).
 *
 * 播放器自带的测速 ([DefaultBandwidthMeter]) 只在一次读取结束时报一个采样; 网盘直链一次打开一直读到文件尾 (见 [ParallelRangeDataSource]),
 * 播放中几分钟都不结束, 这期间从采样里看不出收了多少. 所有数据源读到的字节都会报给测速器的 [TransferListener], 这里顺带按秒累计.
 * 测速本身全部交给系统默认的测速器 (播放器不设测速器时用的就是它).
 */
@AndroidxOptIn(UnstableApi::class)
internal class DeliveredBytesMeter(context: Context) : BandwidthMeter {
    private val delegate = DefaultBandwidthMeter.getSingletonInstance(context)
    private val delegateListener: TransferListener? = delegate.transferListener

    /** 播放器从网络读到的字节 (本机缓存读出的不算). */
    val delivered = RecentBytes()

    private val listener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            delegateListener?.onTransferInitializing(source, dataSpec, isNetwork)
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            delegateListener?.onTransferStart(source, dataSpec, isNetwork)
        }

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            delegateListener?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
            if (isNetwork) delivered.add(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            delegateListener?.onTransferEnd(source, dataSpec, isNetwork)
        }
    }

    override fun getBitrateEstimate(): Long = delegate.bitrateEstimate

    override fun getTimeToFirstByteEstimateUs(): Long = delegate.timeToFirstByteEstimateUs

    override fun getTransferListener(): TransferListener = listener

    override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) {
        delegate.addEventListener(eventHandler, eventListener)
    }

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
        delegate.removeEventListener(eventListener)
    }
}

/**
 * 按秒累计最近 [BUCKETS] 秒收到的字节, 算某段时间内收了多少 ([bytesInLast]) 与一共收了多少 ([total]).
 * 读数据的线程 (每次读一次, 一秒几百次) 写, 播放器的应用线程读; 按秒分桶, 加一次只是一次加法.
 */
internal class RecentBytes(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val bytes = LongArray(BUCKETS)
    private val seconds = LongArray(BUCKETS) { -1 }
    private var total = 0L

    @Synchronized
    fun add(count: Long) {
        val second = clock() / 1000
        val index = (second % BUCKETS).toInt()
        if (seconds[index] != second) {
            seconds[index] = second
            bytes[index] = 0
        }
        bytes[index] += count
        total += count
    }

    /** 最近 [millis] 毫秒收到的字节, 按整秒算: 当前这一秒算, 起点落在的那一秒不算; 最多算 [BUCKETS] 秒. */
    @Synchronized
    fun bytesInLast(millis: Long): Long {
        val now = clock()
        val current = now / 1000
        val first = (now - millis) / 1000 + 1
        var sum = 0L
        for (i in 0 until BUCKETS) {
            if (seconds[i] in first..current) sum += bytes[i]
        }
        return sum
    }

    /** 一共收到的字节, 用来算两个时刻之间收了多少. */
    @Synchronized
    fun total(): Long = total

    companion object {
        const val BUCKETS = 64
    }
}
