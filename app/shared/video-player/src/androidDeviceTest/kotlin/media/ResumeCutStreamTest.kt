/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.source.UriMediaData
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 在线视频读到一半连接断了, 播放器照常放完 (见 [ResumingHttpDataSource]): 真的 ExoPlayer 连本机一个第一次只回一半的服务.
 */
class ResumeCutStreamTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val media = TrackLanguageTestMedia.bytes

    @Test
    fun `a connection dropped halfway plays to the end`() = runBlocking(Dispatchers.Main) {
        CutOrigin(media, cutAt = CUT, cleanEnd = false).use { origin -> assertPlaysThrough(origin) }
    }

    @Test
    fun `a stream that ends cleanly halfway plays to the end`() = runBlocking(Dispatchers.Main) {
        CutOrigin(media, cutAt = CUT, cleanEnd = true).use { origin -> assertPlaysThrough(origin) }
    }

    private suspend fun assertPlaysThrough(origin: CutOrigin) {
        val job = SupervisorJob()
        val player = LibassExoPlayerMediampPlayer(context, Dispatchers.Main + job)
        try {
            player.setMediaData(UriMediaData(origin.url), playWhenReady = true, startPositionMillis = 0)
            val status = withTimeoutOrNull(PLAY_TIMEOUT_MILLIS) {
                player.state.first { it.mediaStatus == MediaStatus.Ended || it.mediaStatus is MediaStatus.Error }.mediaStatus
            }
            assertNotNull(status, "still playing after $PLAY_TIMEOUT_MILLIS ms, requests from ${origin.rangeStarts}")
            assertEquals(MediaStatus.Ended, status, "requests from ${origin.rangeStarts}")
            // 断过, 之后有一次请求从断的地方接着要
            assertTrue(origin.cut.get(), "never cut, requests from ${origin.rangeStarts}")
            assertTrue(CUT.toLong() in origin.rangeStarts, "requests from ${origin.rangeStarts}")
        } finally {
            player.close()
            job.cancel()
        }
    }

    /**
     * 本机服务: 跨过 [cutAt] 的响应只回到 [cutAt] 就断, 直到有请求从 [cutAt] 接着要; 其余按 `Range` 照常回.
     * mkv 提取器读完头会跳去文件尾读索引再回来, 头一个响应读不到 [cutAt], 所以不是只断一次.
     * [cleanEnd] 时断的那次用分块传输正常收尾、在 `Content-Range` 里写文件总长 (HTTP/2 断流在 OkHttp 看来就是这样), 否则写了长度后直接关连接.
     */
    private class CutOrigin(private val content: ByteArray, private val cutAt: Int, private val cleanEnd: Boolean) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

        /** 每次请求从哪个字节开始要 */
        val rangeStarts = CopyOnWriteArrayList<Long>()

        /** 已经断过 */
        val cut = AtomicBoolean(false)
        private val cuts = AtomicInteger()
        val url: String = "http://127.0.0.1:${serverSocket.localPort}/tracks.mkv"

        private val acceptThread = thread(name = "CutOrigin", isDaemon = true) {
            while (!closed.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: SocketException) {
                    if (closed.get()) break else continue
                }
                runCatching {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                        reader.readLine() ?: return@use
                        var start = 0L
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                start = line.substringAfter("bytes=").substringBefore('-').trim().toLong()
                            }
                        }
                        val resumedAtCut = cutAt.toLong() in rangeStarts
                        rangeStarts += start
                        val out = s.getOutputStream()
                        if (start < cutAt && !resumedAtCut && cuts.incrementAndGet() <= MAX_CUTS) {
                            cut.set(true)
                            respondCut(out, start.toInt())
                        } else {
                            respondRange(out, start.toInt())
                        }
                    }
                }
            }
        }

        private fun respondCut(out: OutputStream, start: Int) {
            val total = content.size
            if (cleanEnd) {
                out.ascii(
                    "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${total - 1}/$total\r\n" +
                        "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n",
                )
                out.ascii((cutAt - start).toString(16) + "\r\n")
                out.write(content, start, cutAt - start)
                out.ascii("\r\n0\r\n\r\n")
            } else {
                out.ascii(
                    "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${total - 1}/$total\r\n" +
                        "Content-Length: ${total - start}\r\nConnection: close\r\n\r\n",
                )
                out.write(content, start, cutAt - start)
            }
            out.flush()
        }

        private fun respondRange(out: OutputStream, start: Int) {
            val total = content.size
            out.ascii(
                "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${total - 1}/$total\r\n" +
                    "Content-Length: ${total - start}\r\nConnection: close\r\n\r\n",
            )
            out.write(content, start, total - start)
            out.flush()
        }

        private fun OutputStream.ascii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

        override fun close() {
            closed.set(true)
            serverSocket.close()
            acceptThread.join(1000)
        }
    }

    private companion object {
        /** 切在第一个簇中间 (文件 7083 字节) */
        const val CUT = 4000

        /** 最多断这么多次, 免得没接着要时一直断下去 */
        const val MAX_CUTS = 3
        const val PLAY_TIMEOUT_MILLIS = 15_000L
    }
}
