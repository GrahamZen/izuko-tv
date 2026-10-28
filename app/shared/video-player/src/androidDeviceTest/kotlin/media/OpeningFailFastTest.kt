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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.platform.DevSwitches
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.source.UriMediaData
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * 在线视频的链接坏了 (服务器回错误码) 时, 打开多快报失败 (见 [OpeningLoadErrorPolicy]): 真的 ExoPlayer 连本机一个一律回 404 的服务.
 */
class OpeningFailFastTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `a bad link fails the open after one retry`() = runBlocking(Dispatchers.Main) {
        val (result, requests) = openBadLink()
        assertIs<PlaybackException>(result.exceptionOrNull(), "$result")
        // 打开一次、马上重试一次, 第二次失败就报错
        assertEquals(2, requests)
    }

    @Test
    fun `with fail fast off a bad link is retried three times as by default`() = runBlocking(Dispatchers.Main) {
        DevSwitches.openFailFast.set("false")
        try {
            val (result, requests) = openBadLink()
            assertIs<PlaybackException>(result.exceptionOrNull(), "$result")
            assertEquals(4, requests)
        } finally {
            DevSwitches.openFailFast.set("true")
        }
    }

    /** 打开一个回 404 的地址; 返回打开的结果与服务收到几次请求. 10 秒还没结果算失败 (一直在重试). */
    private suspend fun openBadLink(): Pair<Result<Unit>, Int> = NotFoundOrigin().use { origin ->
        val job = SupervisorJob()
        val player = LibassExoPlayerMediampPlayer(context, Dispatchers.Main + job)
        try {
            val result = withTimeoutOrNull(OPEN_TIMEOUT_MILLIS) {
                runCatching { player.setMediaData(UriMediaData(origin.url), playWhenReady = false, startPositionMillis = 0) }
            }
            assertNotNull(result, "open still running after $OPEN_TIMEOUT_MILLIS ms, ${origin.requests} requests so far")
            result to origin.requests
        } finally {
            player.close()
            job.cancel()
        }
    }

    /** 一律回 404 的极简 HTTP 服务, 数收到几次请求. */
    private class NotFoundOrigin : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val count = AtomicInteger()

        val requests: Int get() = count.get()
        val url: String = "http://127.0.0.1:${serverSocket.localPort}/video.mp4"

        private val acceptThread = thread(name = "NotFoundOrigin", isDaemon = true) {
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
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        count.incrementAndGet()
                        s.getOutputStream().apply {
                            write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) serverSocket.close()
        }

        @Suppress("unused")
        private fun keepThreadReachable(): Thread = acceptThread
    }

    private companion object {
        const val OPEN_TIMEOUT_MILLIS = 10_000L
    }
}
