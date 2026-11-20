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
import me.him188.ani.app.videoplayer.link.PlaybackLinks
import me.him188.ani.app.videoplayer.link.RefreshablePlaybackLink
import me.him188.ani.utils.coroutines.REJECTED_LINK_STATUSES
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
 * 重新请求走 [upstream], 地址被拒时由它下面的 [RefreshingHttpDataSource] 换地址; 连接超时用短的 ([ReconnectTimeout]).
 * 至少 [MAX_RESUMES] 次、前后至少 [UNREACHABLE_AFTER_MILLIS] 重新请求都一个字节没读到, 媒体本身的请求 ([originalUri]) 换一次地址
 * ([link], 换不换同被拒时的规矩) 再试一次; 还是读不到 (或换不了地址) 就放弃, 抛错交出去, 由播放器的重试与出错处理接手.
 * 放弃时服务器一次都没回过话 (只是连不上、读不到) 才算这条资源接不上了 ([RefreshablePlaybackLink.markUnreachable],
 * 加载方据此趁缓冲还在播就换源); 说地址不对 (过期被拒、回的不是视频) 的记成被拒 ([RefreshablePlaybackLink.markRejected],
 * 加载方据此趁缓冲还在播重新解析这条资源).
 * 不能说「读完了」: 外面「边下边播」的 `CacheDataSource` 会把那个位置当成文件总长记下, 之后重试、重新加载都一读到那儿就完
 * (见 [PlaybackDiskCache.forgetContentLength]). 之后又读到数据时撤销「接不上」.
 * 重新请求前先清掉连接池里闲着的连接 ([onReconnect]): 被断过的 HTTP/2 连接在池里看着还好, 新请求复用它会一直读不到数据.
 *
 * 网盘直链的分块下载 ([ParallelRangeReader]) 每块是一个请求, 自己会重试, 不用这一层.
 */
@AndroidxOptIn(UnstableApi::class)
internal class ResumingHttpDataSource(
    private val upstream: HttpDataSource,
    private val onReconnect: () -> Unit = {},
    /** 媒体本身的地址: 只有发给它的请求才换地址、才算这条资源接不上 (外挂字幕、m3u8 分片不算) */
    private val originalUri: String? = null,
    /** 媒体登记的换地址办法 (见 [PlaybackLinks]) */
    private val link: RefreshablePlaybackLink? = null,
    private val enabled: () -> Boolean = { DevSwitches.resumeCutStreams.value },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** 一直读不到至少这么久才放弃 (见 resumeOrThrow) */
    private val unreachableAfterMillis: Long = UNREACHABLE_AFTER_MILLIS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : HttpDataSource by upstream {
    private var dataSpec: DataSpec? = null

    /** 下一个要读的字节在文件里的位置 */
    private var position = 0L

    /** 读到这里为止 (文件里的位置, 不含); 不知道时为 [C.LENGTH_UNSET] */
    private var end = C.LENGTH_UNSET.toLong()

    /** 这次打开读到过数据: 连接用得上, 断了才值得接着读 */
    private var readAny = false
    private var resumesWithoutProgress = 0

    /** 这一轮没读到数据的重新请求用完后换过一次地址了 */
    private var triedFreshLink = false

    /** 这一轮从什么时候起一直读不到数据; 读到了为 `null` */
    private var failingSince: Long? = null

    /** 这一轮重新请求时服务器回过话 (HTTP 错误状态等) */
    private var serverAnswered = false

    /** 这一轮服务器说过地址不对 (过期被拒、回的不是视频) */
    private var linkRejected = false
    private var lastActivityAt = 0L

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        position = dataSpec.position
        readAny = false
        resumesWithoutProgress = 0
        failingSince = null
        serverAnswered = false
        linkRejected = false
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
            try {
                reopen(spec)
            } catch (e: IOException) {
                if (e.isInterruption()) throw e
                resumeOrThrow(spec, e)
            }
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
            triedFreshLink = false
            failingSince = null
            serverAnswered = false
            linkRejected = false
            if (isMedia(spec)) link?.markReachable()
            lastActivityAt = clock()
            return read
        }
    }

    override fun close() {
        dataSpec = null
        upstream.close()
    }

    private fun reachedEnd() = end != C.LENGTH_UNSET.toLong() && position >= end

    private fun isMedia(spec: DataSpec) = originalUri != null && spec.uri.toString() == originalUri

    /** 换一次地址 (阻塞); 换上了返回 `true`. 加载被取消时线程被打断, 转成 [InterruptedIOException] */
    private fun refreshLink(): Boolean {
        val link = link ?: return false
        return try {
            link.refreshAfterCutBlocking()
        } catch (e: InterruptedException) {
            throw InterruptedIOException("Interrupted while refreshing the link").apply { initCause(e) }
        }
    }

    private fun describeEnd() = if (end == C.LENGTH_UNSET.toLong()) "" else " of $end"

    /**
     * 从 [position] 重新请求, 请求本身失败 (连不上、服务器出错) 也算一次, 第二次起隔一会儿 ([retryDelayMillis]) 再试.
     * 至少 [MAX_RESUMES] 次、前后至少 [unreachableAfterMillis] 都没读到数据时换一次地址再试一次; 换不了或换了还是读不到就放弃, 抛最后一次的错.
     * 放弃时这一轮服务器一次都没回过话 (只是连不上、读不到), 才记成接不上 ([RefreshablePlaybackLink.markUnreachable]);
     * 说地址不对 (过期被拒、回的不是视频) 的记成被拒 ([RefreshablePlaybackLink.markRejected], 加载方据此趁缓冲还在播重新解析这条资源);
     * 回了别的 HTTP 错误 (服务器出错) 的只交给播放器.
     * 撑一段时间再放弃: 网络抖一下 (切换热点、路由器重拨) 几秒就好, 不能因此换源; 也不能太久, 要赶在缓冲放完之前
     * (重新请求的连接超时是短的, 见 [ReconnectTimeout]; 换来的地址只试一次: 新地址一上来就不行, 跟旧的一样接不上).
     */
    private fun resumeOrThrow(spec: DataSpec, cause: IOException) {
        var last = cause
        val since = failingSince ?: clock().also { failingSince = it }
        noteAnswer(last)
        while (true) {
            if (resumesWithoutProgress >= MAX_RESUMES && clock() - since >= unreachableAfterMillis) {
                if (!triedFreshLink && isMedia(spec) && refreshLink()) {
                    triedFreshLink = true
                    resumesWithoutProgress = MAX_RESUMES - 1
                    logger.info { "Stream still cut at $position after $MAX_RESUMES tries, requesting again with a fresh link (${hostOf(spec)})" }
                } else {
                    val mark = when {
                        !isMedia(spec) -> ""
                        !serverAnswered -> "unreachable".also { link?.markUnreachable() }
                        linkRejected -> "link rejected".also { link?.markRejected() }
                        else -> ""
                    }
                    logger.info {
                        "Stream cut at $position and requesting again did not help for ${(clock() - since) / 1000}s, giving up" +
                            (if (mark.isNotEmpty()) " ($mark)" else if (serverAnswered) " (the server answered)" else "") + " (${hostOf(spec)})"
                    }
                    throw last
                }
            } else if (resumesWithoutProgress > 0) {
                pause(retryDelayMillis(resumesWithoutProgress))
            }
            resumesWithoutProgress++
            logger.info {
                "Stream cut at $position${describeEnd()} (${last::class.java.simpleName}: ${last.message}), " +
                    "requesting again ($resumesWithoutProgress, ${hostOf(spec)})"
            }
            try {
                reopen(spec)
                return
            } catch (e: IOException) {
                if (e.isInterruption()) throw e
                last = e
                noteAnswer(e)
            }
        }
    }

    private fun pause(millis: Long) {
        try {
            sleep(millis)
        } catch (e: InterruptedException) {
            throw InterruptedIOException("Interrupted while waiting to request again").apply { initCause(e) }
        }
    }

    /** 记下服务器回没回话 ([serverAnswered]: HTTP 错误状态, 或回的不是视频), 回的是不是说地址不对 ([linkRejected]) */
    private fun noteAnswer(e: IOException) {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth++ < 8) {
            when (current) {
                is HttpDataSource.InvalidResponseCodeException -> {
                    serverAnswered = true
                    if (current.responseCode in REJECTED_LINK_STATUSES) linkRejected = true
                    return
                }

                is HttpDataSource.InvalidContentTypeException -> {
                    serverAnswered = true
                    linkRejected = true
                    return
                }
            }
            current = current.cause?.takeIf { it !== current }
        }
    }

    private fun reopen(spec: DataSpec) {
        runCatching { upstream.close() }
        onReconnect()
        // 连接超时用短的, 见 ReconnectTimeout
        val rest = spec.subrange(position - spec.position).withAdditionalHeaders(mapOf(ReconnectTimeout.HEADER to "1"))
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

    /** @param originalUri 媒体本身的地址, 见 [ResumingHttpDataSource] 的同名参数; 它登记的换地址办法在建数据源时查 */
    class Factory(
        private val upstream: HttpDataSource.Factory,
        private val onReconnect: () -> Unit,
        private val originalUri: String? = null,
    ) : HttpDataSource.Factory {
        override fun createDataSource(): HttpDataSource = ResumingHttpDataSource(
            upstream.createDataSource(), onReconnect,
            originalUri = originalUri,
            link = originalUri?.let { PlaybackLinks.of(it) },
        )

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            upstream.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }
    }

    companion object {
        /** 连接空着超过这么久就不再用它, 接着读时重新请求: 比常见的服务器断开时限 (60 秒) 短, 播放中读的间隔远到不了这么长 */
        const val IDLE_RECONNECT_MILLIS = 30_000L

        /** 至少这么多次重新请求一个字节都没读到, 才把错交给播放器 */
        const val MAX_RESUMES = 3

        /**
         * 一直读不到至少这么久才放弃: 网络抖一下 (切换热点、路由器重拨) 几秒就好. 连不上时每次几十毫秒就失败, 隔 1、2、3、3… 秒再试,
         * 约 12 秒放弃; 服务器不应答时每次等满连接超时 (5 秒), 3 次约 18 秒. 都赶得上暂停时攒的约 50 秒缓冲.
         */
        const val UNREACHABLE_AFTER_MILLIS = 10_000L

        /** 第 [attempt] 次重新请求失败后隔多久再试 */
        fun retryDelayMillis(attempt: Int): Long = minOf(attempt * 1000L, 3000L)

        private val logger = logger<ResumingHttpDataSource>()
    }
}
