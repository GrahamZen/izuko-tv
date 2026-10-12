/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 下载一个安装包: 原地址与各个镜像先各取开头 [PROBE_BYTES] 字节测速 (顺带确认回的确实是安装包, 加速站出错时常回 200 的网页),
 * 再从最快的下; 断了换下一条线路接着下 (按 Range 续传, 不认 Range 的从头来). 下完核对大小与 SHA-256 (有的话),
 * 对不上删掉换线路重下.
 *
 * 经第三方镜像下的包即使没有摘要可核, 也装不进来别人的东西: 系统只接受与助手同签名的覆盖安装.
 */
class Downloader(private val log: (String) -> Unit) {
    class Progress(val done: Long, val total: Long?, val bytesPerSecond: Long, val host: String)

    class Failure(val reasons: List<String>) : IOException(reasons.joinToString("; "))

    /** 线路测速结果; [bytesPerSecond] 为 `null` = 连上了但没在时限里测完, 排在测完的后面. */
    private class Probe(val url: String, val bytesPerSecond: Long?)

    /**
     * 把 [file] 下到 [dest]. [sources] 是 [file] 的全部来源 (原地址在前, 然后各镜像).
     * @param cancelled 为真时尽快放弃 (抛 [InterruptedException])
     */
    fun download(
        file: RemoteFile,
        sources: List<String>,
        dest: File,
        onProgress: (Progress) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val reasons = mutableListOf<String>()
        val ranked = rank(sources, reasons)
        if (ranked.isEmpty()) throw Failure(reasons)
        for (url in ranked) {
            if (cancelled()) throw InterruptedException()
            try {
                fetch(url, file, dest, onProgress, cancelled)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                reasons += "${hostOf(url)}: ${e.describe()}"
                log("Download from $url failed: ${e.describe()}")
                continue
            }
            val problem = verify(file, dest)
            if (problem == null) {
                log("Downloaded ${file.name} from $url (${dest.length()} bytes)")
                return
            }
            reasons += "${hostOf(url)}: $problem"
            log("Downloaded ${file.name} from $url but $problem, deleting")
            dest.delete()
        }
        throw Failure(reasons)
    }

    /** 各来源同时测速, 测出安装包的按速度排前, 没测完的排后; 回 404 / 网页的不要. */
    private fun rank(sources: List<String>, reasons: MutableList<String>): List<String> {
        val results = arrayOfNulls<Probe>(sources.size)
        val latch = CountDownLatch(sources.size)
        sources.forEachIndexed { index, url ->
            thread(name = "probe-$index", isDaemon = true) {
                try {
                    val result = probe(url)
                    synchronized(results) { results[index] = result }
                } catch (e: Exception) {
                    synchronized(reasons) { reasons += "${hostOf(url)}: ${e.describe()}" }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val probes = synchronized(results) { results.filterNotNull() }
        log("Probes: " + probes.joinToString { "${hostOf(it.url)}=${it.bytesPerSecond}" })
        return probes.filter { it.bytesPerSecond != null }.sortedByDescending { it.bytesPerSecond }.map { it.url } +
            probes.filter { it.bytesPerSecond == null }.map { it.url }
    }

    private fun probe(url: String): Probe {
        val connection = Http.open(url, connectTimeoutMs = 6_000, readTimeoutMs = 8_000)
        try {
            connection.setRequestProperty("Range", "bytes=0-${PROBE_BYTES - 1}")
            val start = SystemClock.elapsedRealtime()
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            val buffer = ByteArray(PROBE_BYTES)
            var read = 0
            connection.inputStream.use { input ->
                while (read < PROBE_BYTES) {
                    val n = input.read(buffer, read, PROBE_BYTES - read)
                    if (n < 0) break
                    read += n
                    if (SystemClock.elapsedRealtime() - start > PROBE_TIMEOUT_MS) break
                }
            }
            if (read < APK_MAGIC.size || !APK_MAGIC.indices.all { buffer[it] == APK_MAGIC[it] }) {
                throw IOException("not an APK")
            }
            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
            return Probe(url, if (read == PROBE_BYTES) read * 1000L / elapsed else null)
        } finally {
            connection.disconnect()
        }
    }

    private fun fetch(
        url: String,
        file: RemoteFile,
        dest: File,
        onProgress: (Progress) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val have = dest.takeIf { it.exists() }?.length() ?: 0L
        if (file.size != null && have == file.size) return
        val connection = Http.open(url, connectTimeoutMs = 8_000, readTimeoutMs = 20_000)
        try {
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            val code = connection.responseCode
            val append = code == 206 && have > 0
            if (code != 200 && code != 206) throw HttpStatusException(code)
            val total = file.size ?: connection.getHeaderField("Content-Length")?.toLongOrNull()?.let { if (append) it + have else it }
            var done = if (append) have else 0L
            val host = hostOf(url)
            FileOutputStream(dest, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var windowStart = SystemClock.elapsedRealtime()
                    var windowBytes = 0L
                    var speed = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw InterruptedException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        windowBytes += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - windowStart >= 1_000) {
                            speed = windowBytes * 1000 / (now - windowStart)
                            windowStart = now
                            windowBytes = 0
                        }
                        if (now - lastReport >= 250) {
                            lastReport = now
                            onProgress(Progress(done, total, speed, host))
                        }
                    }
                }
                output.fd.sync()
            }
            onProgress(Progress(done, total, 0, host))
        } finally {
            connection.disconnect()
        }
    }

    /** 下好的文件有什么不对; `null` = 没问题. */
    private fun verify(file: RemoteFile, dest: File): String? {
        if (file.size != null && dest.length() != file.size) return "size ${dest.length()} != ${file.size}"
        val magic = ByteArray(APK_MAGIC.size)
        val read = RandomAccessFile(dest, "r").use { it.read(magic) }
        if (read != magic.size || !magic.contentEquals(APK_MAGIC)) return "not an APK"
        val expected = file.sha256 ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        dest.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual == expected) null else "SHA-256 mismatch"
    }

    companion object {
        private const val PROBE_BYTES = 64 * 1024
        private const val PROBE_TIMEOUT_MS = 10_000L

        /** zip 的本地文件头, APK 都以它开头. */
        private val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
