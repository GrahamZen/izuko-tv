/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.os.SystemClock
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * 连接断了从断处接着读的 HTTP 数据源: 重新请求已经读到的位置之后的部分, 播放器只看到数据接着来, 不报错.
 *
 * 两种时候在读的那一下重新请求:
 * - **连接空着超过 [IDLE_RECONNECT_MILLIS]** (暂停时缓冲攒满了、播放会话在后台): 服务器常在客户端一分钟不收数据时把连接断掉
 *   (nginx 默认的 `send_timeout` 就是 60 秒). 断之前已经发出的数据还在本机的缓冲里, 接着读会先读出它们、过一会儿才撞上断开,
 *   那时播放器的缓冲已经用掉一截; 所以不等断开, 空久了就直接重开.
 * - **读到一半断了**: 读出错 (连接被重置、HTTP/1.1 下长度不够), 或者没到响应说的长度就读完了 —— HTTP/2 的服务器断流时把这条流正常收尾,
 *   OkHttp 不核对长度, 上层只看到「读完了」, 提取器在文件中间读不到数据报 `EOFException`.
 *
 * 只接这次打开读到过数据的: 一上来就失败的照旧交给播放器 (坏链接早换源, 见 [OpeningLoadErrorPolicy]).
 * 重新请求走 [upstream], 地址被拒时由它下面的 [RefreshingHttpDataSource] 换地址. 连着 [MAX_RESUMES] 次重新请求都一个字节没读到,
 * 抛错交出去, 由播放器的重试与出错处理接手: 不能说「读完了」, 外面「边下边播」的 `CacheDataSource` 会把那个位置当成文件总长记下,
 * 之后重试、重新加载都一读到那儿就完 (见 [PlaybackDiskCache.forgetContentLength]).
 * 重新请求前先清掉连接池里闲着的连接 ([onReconnect]): 被断过的 HTTP/2 连接在池里看着还好, 新请求复用它会一直读不到数据.
 *
 * 网盘直链的分块下载 ([ParallelRangeReader]) 每块是一个请求, 自己会重试, 不用这一层.
 */
@AndroidxOptIn(UnstableApi::class)
internal class ResumingHttpDataSource(
    private val upstream: HttpDataSource,
    private val onReconnect: () -> Unit = {},
    private val enabled: () -> Boolean = { DevSwitches.resumeCutStreams.value },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : HttpDataSource by upstream {
    private var dataSpec: DataSpec? = null

    /** 下一个要读的字节在文件里的位置 */
    private var position = 0L

    /** 读到这里为止 (文件里的位置, 不含); 不知道时为 [C.LENGTH_UNSET] */
    private var end = C.LENGTH_UNSET.toLong()

    /** 这次打开读到过数据: 连接用得上, 断了才值得接着读 */
    private var readAny = false
    private var resumesWithoutProgress = 0
    private var lastActivityAt = 0L

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        position = dataSpec.position
        readAny = false
        resumesWithoutProgress = 0
        val length = upstream.open(dataSpec)
        end = endOf(dataSpec, length)
        lastActivityAt = clock()
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val spec = dataSpec
        if (spec == null || !enabled()) return upstream.read(buffer, offset, length)
        val idleMillis = clock() - lastActivityAt
        if (readAny && idleMillis >= IDLE_RECONNECT_MILLIS && !reachedEnd()) {
            logger.info { "Connection idle for ${idleMillis / 1000}s, requesting again from $position${describeEnd()} (${hostOf(spec)})" }
            reopen(spec)
        }
        while (true) {
            val read = try {
                upstream.read(buffer, offset, length)
            } catch (e: IOException) {
                if (!readAny || e.isInterruption()) throw e
                resumeOrThrow(spec, e)
                continue
            }
            if (read == C.RESULT_END_OF_INPUT) {
                if (!readAny || end == C.LENGTH_UNSET.toLong() || reachedEnd()) return read
                resumeOrThrow(spec, EOFException("Stream ended early at $position of $end"))
                continue
            }
            position += read
            readAny = true
            resumesWithoutProgress = 0
            lastActivityAt = clock()
            return read
        }
    }

    override fun close() {
        dataSpec = null
        upstream.close()
    }

    private fun reachedEnd() = end != C.LENGTH_UNSET.toLong() && position >= end

    private fun describeEnd() = if (end == C.LENGTH_UNSET.toLong()) "" else " of $end"

    /** 从 [position] 重新请求; 连着 [MAX_RESUMES] 次都没读到数据时抛 [cause]. */
    private fun resumeOrThrow(spec: DataSpec, cause: IOException) {
        if (resumesWithoutProgress >= MAX_RESUMES) throw cause
        resumesWithoutProgress++
        logger.info {
            "Stream cut at $position${describeEnd()} (${cause::class.java.simpleName}: ${cause.message}), " +
                "requesting again ($resumesWithoutProgress/$MAX_RESUMES, ${hostOf(spec)})"
        }
        try {
            reopen(spec)
        } catch (e: IOException) {
            e.addSuppressed(cause)
            throw e
        }
    }

    private fun reopen(spec: DataSpec) {
        runCatching { upstream.close() }
        onReconnect()
        val rest = spec.subrange(position - spec.position)
        val length = upstream.open(rest)
        end = endOf(rest, length)
        lastActivityAt = clock()
    }

    /** [spec] 打开后读到哪为止: 打开时给的长度, 请求本身限的长度, 或响应 `Content-Range` 写的文件总长. */
    private fun endOf(spec: DataSpec, openedLength: Long): Long = when {
        openedLength != C.LENGTH_UNSET.toLong() -> spec.position + openedLength
        spec.length != C.LENGTH_UNSET.toLong() -> spec.position + spec.length
        else -> RefreshingHttpDataSource.totalLengthOf(upstream.responseHeaders) ?: C.LENGTH_UNSET.toLong()
    }

    /** 加载被取消 (换集、跳转、离开播放页) 时线程被打断: 不是断流, 不接着读 */
    private fun IOException.isInterruption() = this is InterruptedIOException || cause is InterruptedIOException

    private fun hostOf(spec: DataSpec) = spec.uri.host ?: spec.uri.scheme

    class Factory(
        private val upstream: HttpDataSource.Factory,
        private val onReconnect: () -> Unit,
    ) : HttpDataSource.Factory {
        override fun createDataSource(): HttpDataSource = ResumingHttpDataSource(upstream.createDataSource(), onReconnect)

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            upstream.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }
    }

    companion object {
        /** 连接空着超过这么久就不再用它, 接着读时重新请求: 比常见的服务器断开时限 (60 秒) 短, 播放中读的间隔远到不了这么长 */
        const val IDLE_RECONNECT_MILLIS = 30_000L

        /** 连着这么多次重新请求一个字节都没读到, 就把错交给播放器 */
        const val MAX_RESUMES = 3

        private val logger = logger<ResumingHttpDataSource>()
    }
}
