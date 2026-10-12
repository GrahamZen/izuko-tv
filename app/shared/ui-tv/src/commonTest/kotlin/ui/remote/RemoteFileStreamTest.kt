/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.him188.ani.app.ui.foundation.lan.LanHttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteFileStreamTest {
    private val file = File.createTempFile("izuko-stream", ".mp4").apply {
        deleteOnExit()
        writeBytes(ByteArray(300_000) { (it % 251).toByte() })
    }
    private val bytes = file.readBytes()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = LanHttpServer(
        handler = { RemoteFileStream.serve(file, it) },
        token = "streamtest",
        isStreaming = { it.startsWith("video/") },
    ).also { s -> scope.launch { s.serve() } }

    @AfterTest
    fun tearDown() {
        server.close()
        scope.cancel()
        file.delete()
    }

    private class Got(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun get(range: String? = null, method: String = "GET"): Got {
        val c = URL("http://127.0.0.1:${server.port}/streamtest/video/a.mp4").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
        c.disconnect()
        return Got(code, headers, body)
    }

    @Test
    fun `whole file without a range`() {
        val r = get()
        assertEquals(200, r.code)
        assertEquals("bytes", r.headers["Accept-Ranges"])
        assertEquals("video/mp4", r.headers["Content-Type"])
        assertContentEquals(bytes, r.body)
    }

    @Test
    fun `range requests get exactly that span`() {
        val r = get("bytes=1000-1999")
        assertEquals(206, r.code)
        assertEquals("bytes 1000-1999/300000", r.headers["Content-Range"])
        assertContentEquals(bytes.copyOfRange(1000, 2000), r.body)

        val open = get("bytes=299000-")
        assertEquals(206, open.code)
        assertContentEquals(bytes.copyOfRange(299000, 300000), open.body)

        val suffix = get("bytes=-500")
        assertEquals("bytes 299500-299999/300000", suffix.headers["Content-Range"])
        assertContentEquals(bytes.copyOfRange(299500, 300000), suffix.body)
    }

    @Test
    fun `out of range is 416 and head sends no body`() {
        val r = get("bytes=400000-")
        assertEquals(416, r.code)
        assertEquals("bytes */300000", r.headers["Content-Range"])
        val head = get("bytes=0-99", method = "HEAD")
        assertEquals(206, head.code)
        assertEquals("100", head.headers["Content-Length"])
        assertEquals(0, head.body.size)
    }

    @Test
    fun `range parsing`() {
        assertEquals(0L..9L, RemoteFileStream.parseRange("bytes=0-9", 100))
        assertEquals(90L..99L, RemoteFileStream.parseRange("bytes=90-500", 100))
        assertEquals(50L..99L, RemoteFileStream.parseRange("bytes=-50", 100))
        assertEquals(0L..99L, RemoteFileStream.parseRange("bytes=-500", 100))
        // 只认第一段
        assertEquals(0L..9L, RemoteFileStream.parseRange("bytes=0-9, 20-29", 100))
        assertNull(RemoteFileStream.parseRange("bytes=100-", 100))
        assertNull(RemoteFileStream.parseRange("bytes=9-0", 100))
        assertNull(RemoteFileStream.parseRange("items=0-9", 100))
        assertEquals("video/x-matroska", RemoteFileStream.contentTypeOf("a.MKV"))
        assertEquals("video/mp2t", RemoteFileStream.contentTypeOf("a.ts"))
    }
}
