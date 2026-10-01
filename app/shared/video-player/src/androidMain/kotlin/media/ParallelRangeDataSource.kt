/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.net.Uri
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多连接分块下载的 HTTP 数据源 (见 [ParallelRangeReader]), 每个范围请求交给 [upstreamFactory] 建的数据源去发.
 *
 * 用在每个连接有速度上限的网盘直链上 (由解析器在请求头里放 [PlaybackRequestHints.PARALLEL_RANGE_HEADER] 打开).
 * 服务端不按范围回时退回成单连接.
 */
@AndroidxOptIn(UnstableApi::class)
internal class ParallelRangeDataSource(
    private val upstreamFactory: HttpDataSource.Factory,
    private val connections: Int,
    private val chunkSize: Int,
) : BaseDataSource(/* isNetwork = */ true) {
    class Factory(
        private val upstreamFactory: HttpDataSource.Factory,
        private val connections: Int,
        private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ParallelRangeDataSource(upstreamFactory, connections, chunkSize)
    }

    private var dataSpec: DataSpec? = null
    private var reader: ParallelRangeReader? = null
    private var opened = false

    @Volatile
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        val opener = RangeOpener { start, length ->
            val upstream = upstreamFactory.createDataSource()
            upstream.open(
                dataSpec.buildUpon()
                    .setPosition(start)
                    .setLength(if (length < 0) C.LENGTH_UNSET.toLong() else length)
                    .build(),
            )
            val headers = upstream.responseHeaders
            if (responseHeaders.isEmpty()) responseHeaders = headers
            OpenedRange(UpstreamConnection(upstream), totalLengthOf(headers))
        }
        val reader = ParallelRangeReader(opener, EXECUTOR, connections, chunkSize)
        this.reader = reader
        val length = reader.open(dataSpec.position, dataSpec.length)
        logger.info {
            val mode = if (reader.isParallel) "$connections connections" else "a single connection"
            "Opened ${dataSpec.uri.host} at ${dataSpec.position} with $mode, length $length"
        }
        opened = true
        transferStarted(dataSpec)
        return if (length < 0) C.LENGTH_UNSET.toLong() else length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = checkNotNull(reader) { "Data source is not open" }.read(buffer, offset, length)
        if (count < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        val current = reader
        reader = null
        dataSpec = null
        current?.close()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private class UpstreamConnection(private val upstream: DataSource) : RangeConnection {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = upstream.read(buffer, offset, length)
            return if (count == C.RESULT_END_OF_INPUT) -1 else count
        }

        override fun close() = upstream.close()
    }

    companion object {
        /** 一块多大. 太小请求太多, 太大开头那块要等得久; 4 MiB 在 1 MB/s 的连接上约 4 秒. */
        const val DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024

        private val logger = logger<ParallelRangeDataSource>()

        private val threadId = AtomicInteger()

        private val EXECUTOR: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ParallelRange-${threadId.incrementAndGet()}").apply { isDaemon = true }
        }

        /** `Content-Range: bytes 0-4194303/247580374` 里的总长; 没有或是 `*` 时为 -1. */
        internal fun totalLengthOf(headers: Map<String, List<String>>): Long {
            val value = headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull() ?: return -1
            return value.substringAfterLast('/', "").trim().toLongOrNull() ?: -1
        }
    }
}
