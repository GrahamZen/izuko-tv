/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * 下载一个安装包. 原地址与各个镜像同时各取开头一段测速, 从收到第一个字节起计时: 量的是带宽, 不是建连接、跳转的快慢
 * (离得近、握手快的限速站按建连接算会排第一). 顺带确认回的确实是安装包 (加速站出错时常回 200 的网页).
 *
 * 从最快的下; 下了一阵实测速度远不如别的线路, 就换过去按 Range 接着下. 断了换下一条线路接着下 (不认 Range 的从头来).
 * 下完核对大小与 SHA-256 (有的话), 对不上删掉换线路重下. 界面可以手动指定线路 ([choose]) 或重新测速 ([retest]).
 *
 * 经第三方镜像下的包即使没有摘要可核, 也装不进来别人的东西: 系统只接受与助手同签名的覆盖安装.
 */
class Downloader(private val log: (String) -> Unit, private val tuning: Tuning = Tuning()) {
    class Progress(val done: Long, val total: Long?, val bytesPerSecond: Long, val host: String)

    class Failure(val reasons: List<String>) : IOException(reasons.joinToString("; "))

    /**
     * 一条线路此刻的情况, 给界面列出来.
     * @property probeBytesPerSecond 测速结果; `null` = 还没测完或数据太少算不出
     * @property measuredBytesPerSecond 从它下载时的实测速度; 没从它下过为 `null`
     * @property error 测速或下载失败的原因; 有它时自动选线路不再用这条
     */
    class LineStatus(
        val url: String,
        val probeBytesPerSecond: Long?,
        val measuredBytesPerSecond: Long?,
        val probing: Boolean,
        val error: String?,
        val current: Boolean,
    ) {
        val host get() = hostOf(url)
    }

    /**
     * @property probeBytes 测速每条线路取多少字节
     * @property minProbeSample 第一个字节之后至少收到这么多才算得出测速结果
     * @property probeGraceMs 第一条线路测完后再等别的线路多久就开始下载 (没测完的排在后面, 测完了照样参与换线路)
     * @property switchAfterMs 在一条线路上下了这么久, 才拿实测速度和别的线路比
     * @property switchFactor 别的线路的速度是当前实测的这么多倍才换
     * @property minSwitchGainBytesPerSecond 而且至少快这么多 (几十 KB/s 之间换来换去没意义)
     * @property minSwitchRemainingBytes 剩下的不到这么多就不换了
     * @property stallMs 有别的线路时的读超时: 这么久一个字节都没收到就换线路 (不算这条线路出错); 没有别的线路时等 20 秒
     */
    class Tuning(
        val probeBytes: Int = 256 * 1024,
        val minProbeSample: Int = 32 * 1024,
        val probeTimeoutMs: Long = 8_000,
        val probeGraceMs: Long = 2_000,
        val switchAfterMs: Long = 8_000,
        val switchFactor: Double = 2.5,
        val minSwitchGainBytesPerSecond: Long = 100 * 1024,
        val minSwitchRemainingBytes: Long = 4L * 1024 * 1024,
        val rateWindowMs: Long = 5_000,
        val maxAutoSwitches: Int = 6,
        val stallMs: Long = 8_000,
    )

    private class Line(val url: String) {
        @Volatile
        var probeBytesPerSecond: Long? = null

        @Volatile
        var measuredBytesPerSecond: Long? = null

        @Volatile
        var probing = false

        /** 测速时按 Range 回了 206; 自动换线路只换到认 Range 的线路, 不然已下的部分作废. */
        @Volatile
        var rangeSupported = false

        @Volatile
        var error: String? = null

        /** 速度的估计: 下过的按实测, 没下过的按测速. */
        val estimate get() = measuredBytesPerSecond ?: probeBytesPerSecond
    }

    /** 下载中停下当前连接的原因 (换线路、重新测速); 不是线路的错. */
    private class Interruption(message: String) : Exception(message)

    @Volatile
    private var lines: List<Line> = emptyList()

    @Volatile
    private var current: Line? = null

    /** 用户指定的线路; `null` = 自动. */
    @Volatile
    private var manual: Line? = null

    /** 自动换线路时选中的下一条. */
    @Volatile
    private var switchTarget: Line? = null

    @Volatile
    private var retestRequested = false

    @Volatile
    private var aborted = false

    @Volatile
    private var connection: HttpURLConnection? = null

    fun statuses(): List<LineStatus> {
        val now = current
        return lines.map { LineStatus(it.url, it.probeBytesPerSecond, it.measuredBytesPerSecond, it.probing, it.error, it === now) }
    }

    /** 当前是不是用户指定的线路. */
    val isManual get() = manual != null

    /** 指定从 [url] 下 (失败时退回自动); `null` = 自动选、按速度换. */
    fun choose(url: String?) {
        if (url == null) {
            manual = null
            log("Line: automatic")
            return
        }
        val line = lines.firstOrNull { it.url == url } ?: return
        line.error = null
        manual = line
        log("Line: manual ${hostOf(url)}")
        if (current !== line) interruptTransfer()
    }

    /** 停下当前的传输, 所有线路重新测速 (之前失败的也再测一次), 再接着下. */
    fun retest() {
        retestRequested = true
        interruptTransfer()
    }

    /** 放弃这次下载 (例如手机传来了安装包); [download] 尽快抛 [InterruptedException]. */
    fun abort() {
        aborted = true
        interruptTransfer()
    }

    /**
     * 把 [file] 下到 [dest]. [sources] 是 [file] 的全部来源 (原地址在前, 然后各镜像).
     * @param onProbing 测速开始 (`true`) 与结束 (`false`)
     */
    fun download(
        file: RemoteFile,
        sources: List<String>,
        dest: File,
        onProgress: (Progress) -> Unit,
        onProbing: (Boolean) -> Unit = {},
    ) {
        val reasons = mutableListOf<String>()
        lines = sources.distinct().map { Line(it) }
        rank(reasons, onProbing)
        var autoSwitches = 0
        while (true) {
            checkAborted()
            if (retestRequested) {
                retestRequested = false
                lines.forEach {
                    it.error = null
                    it.probeBytesPerSecond = null
                    it.measuredBytesPerSecond = null
                }
                rank(reasons, onProbing)
                continue
            }
            val line = next() ?: throw Failure(reasons)
            current = line
            try {
                fetch(line, file, dest, onProgress, canAutoSwitch = autoSwitches < tuning.maxAutoSwitches)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Interruption) {
                if (switchTarget != null) autoSwitches++
                log("Leaving ${hostOf(line.url)}: ${e.message}")
                continue
            } catch (e: Exception) {
                checkAborted()
                // 换线路 / 重新测速时从界面线程断开连接, 读的那一侧抛的 IOException 不算线路的错
                val chosen = manual
                if (retestRequested || (chosen != null && chosen !== line)) continue
                if (e is SocketTimeoutException && hasAlternative(line)) {
                    // 卡住不算出错: 排到最后, 别的线路都不行时还能回来接着下
                    line.measuredBytesPerSecond = 0
                    if (chosen === line) manual = null
                    log("Leaving ${hostOf(line.url)}: no data for ${tuning.stallMs} ms")
                    continue
                }
                line.error = e.describe()
                if (chosen === line) manual = null
                reasons += "${hostOf(line.url)}: ${e.describe()}"
                log("Download from ${line.url} failed: ${e.describe()}")
                continue
            }
            val problem = verify(file, dest)
            if (problem == null) {
                log("Downloaded ${file.name} from ${line.url} (${dest.length()} bytes)")
                return
            }
            line.error = problem
            if (manual === line) manual = null
            reasons += "${hostOf(line.url)}: $problem"
            log("Downloaded ${file.name} from ${line.url} but $problem, deleting")
            dest.delete()
        }
    }

    private fun hasAlternative(line: Line) = lines.any { it !== line && it.error == null }

    /** 从别的线程断开当前连接, 让下载线程上阻塞着的读马上出来; 断开本身放到后台, 不卡调用方 (界面线程). */
    private fun interruptTransfer() {
        val active = connection ?: return
        thread(name = "download-interrupt", isDaemon = true) { active.disconnect() }
    }

    private fun checkAborted() {
        if (aborted) throw InterruptedException()
    }

    /** 下一条要下的线路: 用户指定的, 或自动换线路选中的, 否则没出错的里估计最快的 (都没测出来时按原顺序). */
    private fun next(): Line? {
        manual?.let { if (it.error == null) return it }
        switchTarget?.let {
            switchTarget = null
            if (it.error == null) return it
        }
        return lines.filter { it.error == null }.sortedByDescending { it.estimate ?: -1 }.firstOrNull()
    }

    /**
     * 没出错的线路同时测速. 全部测完, 或第一条测完之后再等 [Tuning.probeGraceMs], 就返回; 没测完的接着在后台测,
     * 结果照样用来换线路.
     */
    private fun rank(reasons: MutableList<String>, onProbing: (Boolean) -> Unit) {
        val probing = lines.filter { it.error == null }
        if (probing.size <= 1) return
        onProbing(true)
        val start = now()
        probing.forEachIndexed { index, line ->
            line.probing = true
            thread(name = "probe-$index", isDaemon = true) {
                try {
                    probe(line)
                } catch (e: Exception) {
                    line.error = e.describe()
                    synchronized(reasons) { reasons += "${hostOf(line.url)}: ${e.describe()}" }
                    log("Probe ${line.url} failed: ${e.describe()}")
                } finally {
                    line.probing = false
                }
            }
        }
        var firstDone = 0L
        while (!aborted && !retestRequested) {
            val t = now()
            if (probing.none { it.probing }) break
            if (firstDone == 0L && probing.any { it.probeBytesPerSecond != null }) firstDone = t
            if (firstDone != 0L && t - firstDone >= tuning.probeGraceMs) break
            if (t - start >= tuning.probeTimeoutMs + 1_000) break
            Thread.sleep(50)
        }
        onProbing(false)
        log("Probes: " + lines.joinToString { "${hostOf(it.url)}=${it.probeBytesPerSecond ?: it.error ?: "…"}" })
    }

    private fun probe(line: Line) {
        val connection = Http.open(line.url, connectTimeoutMs = 6_000, readTimeoutMs = 8_000)
        try {
            connection.setRequestProperty("Range", "bytes=0-${tuning.probeBytes - 1}")
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            line.rangeSupported = code == 206
            val buffer = ByteArray(16 * 1024)
            val head = ByteArray(APK_MAGIC.size)
            var headRead = 0
            var total = 0
            var firstAt = 0L
            var afterFirst = 0L
            val start = now()
            connection.inputStream.use { input ->
                while (total < tuning.probeBytes) {
                    val n = input.read(buffer, 0, minOf(buffer.size, tuning.probeBytes - total))
                    if (n < 0) break
                    if (headRead < head.size) {
                        val k = minOf(n, head.size - headRead)
                        System.arraycopy(buffer, 0, head, headRead, k)
                        headRead += k
                    }
                    if (firstAt == 0L) firstAt = now() else afterFirst += n
                    total += n
                    if (now() - start > tuning.probeTimeoutMs) break
                }
            }
            if (headRead < head.size || !head.contentEquals(APK_MAGIC)) throw IOException("not an APK")
            val elapsed = (now() - firstAt).coerceAtLeast(1)
            line.probeBytesPerSecond = if (afterFirst >= tuning.minProbeSample) afterFirst * 1000 / elapsed else null
            log("Probe ${hostOf(line.url)}: $total bytes (${afterFirst} after the first read in $elapsed ms) -> ${line.probeBytesPerSecond}")
        } finally {
            connection.disconnect()
        }
    }

    private fun fetch(line: Line, file: RemoteFile, dest: File, onProgress: (Progress) -> Unit, canAutoSwitch: Boolean) {
        var have = dest.takeIf { it.exists() }?.length() ?: 0L
        if (file.size != null && have == file.size) return
        if (file.size != null && have > file.size) {
            dest.delete()
            have = 0
        }
        val readTimeout = if (hasAlternative(line)) tuning.stallMs.toInt() else 20_000
        val connection = Http.open(line.url, connectTimeoutMs = 8_000, readTimeoutMs = readTimeout)
        this.connection = connection
        val lineStart = now()
        val rate = RateMeter(tuning.rateWindowMs)
        try {
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            val code = connection.responseCode
            if (code != 200 && code != 206) throw HttpStatusException(code)
            val append = code == 206 && have > 0
            if (code == 206) line.rangeSupported = true
            val total = file.size ?: connection.getHeaderField("Content-Length")?.toLongOrNull()?.let { if (append) it + have else it }
            var done = if (append) have else 0L
            val host = hostOf(line.url)
            rate.sample(lineStart, done)
            FileOutputStream(dest, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var speed = 0L
                    var lastReport = 0L
                    var lastCheck = lineStart
                    while (true) {
                        checkAborted()
                        if (retestRequested) throw Interruption("retest")
                        val chosen = manual
                        if (chosen != null && chosen !== line) throw Interruption("manual ${hostOf(chosen.url)}")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val t = now()
                        if (t - lastReport >= 250) {
                            lastReport = t
                            rate.sample(t, done)?.let { speed = it }
                            // 刚连上那几秒在爬坡, 不算实测
                            if (t - lineStart >= 3_000) line.measuredBytesPerSecond = speed
                            onProgress(Progress(done, total, speed, host))
                        }
                        if (canAutoSwitch && t - lastCheck >= 1_000) {
                            lastCheck = t
                            checkSwitch(line, t - lineStart, rate.bytesPerSecond, total?.minus(done))
                        }
                    }
                }
                output.fd.sync()
            }
            // 连接提前结束: 算这条线路的错, 已下的留着给下一条接着下
            if (total != null && done < total) throw IOException("connection closed at $done of $total bytes")
            onProgress(Progress(done, total, 0, host))
        } finally {
            this.connection = null
            connection.disconnect()
        }
    }

    /** 实测速度远不如别的线路 (测速或之前实测的) 时换过去. */
    private fun checkSwitch(line: Line, elapsedMs: Long, speed: Long?, remaining: Long?) {
        if (manual != null || elapsedMs < tuning.switchAfterMs) return
        if (speed == null || remaining == null || remaining < tuning.minSwitchRemainingBytes) return
        val best = lines.filter { it !== line && it.error == null && it.rangeSupported }.maxByOrNull { it.estimate ?: -1 } ?: return
        val expected = best.estimate ?: return
        if (expected > speed * tuning.switchFactor && expected - speed >= tuning.minSwitchGainBytesPerSecond) {
            line.measuredBytesPerSecond = speed
            switchTarget = best
            throw Interruption("${speed}B/s here, ${hostOf(best.url)} expects ${expected}B/s")
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

    /**
     * 最近 [windowMs] 的平均速度: 只看最后一秒的话, 网络一抖读数就大起大落, 换线路会误判.
     * 每次 [sample] 传入累计字节数; [bytesPerSecond] 在样本跨度不到一秒时为 `null`.
     */
    private class RateMeter(private val windowMs: Long) {
        private val samples = ArrayDeque<Pair<Long, Long>>()

        var bytesPerSecond: Long? = null
            private set

        fun sample(time: Long, bytes: Long): Long? {
            samples.addLast(time to bytes)
            while (samples.size > 2 && time - samples[1].first >= windowMs) samples.removeFirst()
            val (since, then) = samples.first()
            bytesPerSecond = if (time - since >= 1_000) (bytes - then).coerceAtLeast(0) * 1000 / (time - since) else null
            return bytesPerSecond
        }
    }

    companion object {
        /** zip 的本地文件头, APK 都以它开头. */
        private val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        private fun now() = System.nanoTime() / 1_000_000
    }
}
