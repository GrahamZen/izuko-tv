/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.view.FrameMetrics
import android.view.Window
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.File
import java.lang.Process as JavaProcess
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 一次「录制」的采集 (见 [TvPerfDiagnostics]). 只在录制期间工作, 结束后线程全部停掉, 平时零开销.
 *
 * - 帧: [Window.OnFrameMetricsAvailableListener], 只在真实出帧时回调, 不自己拉帧.
 * - 计数器 (每 ~100ms): ART 的 GC 次数 / 耗时 / 阻塞式 GC / 累计分配字节, 主线程与整个进程的主缺页, Java 堆与 native 堆;
 *   每秒一拍顺带读系统可用内存.
 * - 内存拆分 (每 ~5 秒, `Debug.getMemoryInfo` 要扫 smaps, 不能更密): PSS 按 Java / Native / Graphics / 代码 / 其它.
 * - GC 的确切时刻: 读本进程的 logcat (`-v monotonic`, 与 FrameMetrics 同一时钟), ART 对较长的 GC 与「线程等 GC」各打一行.
 * - 主线程看门狗: 每 [WATCHDOG_TICK_MS] 往主线程投一个空任务, 超过 [STALL_THRESHOLD_MS] 没执行就采样主线程堆栈.
 * - 系统的内存不足通知 (onTrimMemory).
 *
 * 所有采集都包在 try 里, 读不到的节点跳过, 诊断出错不影响应用.
 */
internal class TvPerfRecorder(
    private val context: Context,
    private val window: Window,
    private val refreshHz: Float,
    private val windowSize: String,
) {
    private val pid = Process.myPid()
    private val cores = Runtime.getRuntime().availableProcessors()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diagThread = HandlerThread("ani-perf-diag", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var handler: Handler

    // 只在诊断线程上写; stop() 里 join 之后再读
    private val frames = ArrayList<PerfFrame>()
    private val counters = ArrayList<PerfCounters>()
    private val memory = ArrayList<PerfMemSnapshot>()
    private var tick = 0

    private val trims: MutableList<PerfTrim> = Collections.synchronizedList(ArrayList())
    private val stalls: MutableList<PerfStall> = Collections.synchronizedList(ArrayList())
    private val stallDurations: MutableList<Long> = Collections.synchronizedList(ArrayList())
    private val gcEvents: MutableList<PerfGcEvent> = Collections.synchronizedList(ArrayList())

    @Volatile
    private var running = false
    private var startNs = 0L
    private var cpuStart: Map<Int, Pair<String, Long>> = emptyMap()
    private var logcat: JavaProcess? = null
    private var watchdog: Thread? = null

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> onFrame(metrics) }

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (running) trims += PerfTrim(System.nanoTime(), level)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            if (running) trims += PerfTrim(System.nanoTime(), ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }
    }

    private val sampleTick = object : Runnable {
        override fun run() {
            if (!running) return
            safe("sample") { sample() }
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    private val memoryTick = object : Runnable {
        override fun run() {
            if (!running) return
            safe("memory") { memorySnapshot() }
            handler.postDelayed(this, MEMORY_INTERVAL_MS)
        }
    }

    /** 在主线程调 (要往窗口上挂监听). */
    fun start() {
        running = true
        diagThread.start()
        handler = Handler(diagThread.looper)
        startNs = System.nanoTime()
        cpuStart = readThreadCpu()
        safe("frames") { window.addOnFrameMetricsAvailableListener(frameListener, handler) }
        safe("trim") { context.registerComponentCallbacks(trimCallbacks) }
        handler.post(sampleTick)
        handler.post(memoryTick)
        startWatchdog()
        startLogcat()
    }

    /** 在后台线程调 (要等几拍收尾): 摘掉监听 (切到主线程摘)、停线程, 返回录到的全部数据. */
    fun stop(): PerfRecording {
        running = false
        val detached = CountDownLatch(1)
        mainHandler.post {
            safe("frames") { window.removeOnFrameMetricsAvailableListener(frameListener) }
            detached.countDown()
        }
        detached.await(2, TimeUnit.SECONDS)
        safe("trim") { context.unregisterComponentCallbacks(trimCallbacks) }
        watchdog?.interrupt()
        // 收尾的一拍计数器与内存 (分析按首尾两拍算增量)
        val done = CountDownLatch(1)
        handler.post {
            safe("sample") { sample(withMemAvail = true) }
            safe("memory") { memorySnapshot() }
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
        val endNs = System.nanoTime()
        val cpuEnd = readThreadCpu()
        // logcat 里 GC 日志晚到一点: 收尾之后再给它半秒
        Thread.sleep(500)
        val gcLogAvailable = logcat?.let { it.isAlive || gcEvents.isNotEmpty() } ?: false
        logcat?.destroy()
        diagThread.quitSafely()
        diagThread.join(2_000)
        return PerfRecording(
            startNs = startNs,
            endNs = endNs,
            refreshHz = refreshHz,
            cores = cores,
            mainTid = pid,
            frames = frames.toList(),
            gcEvents = synchronized(gcEvents) { gcEvents.sortedBy { it.endNs } },
            gcLogAvailable = gcLogAvailable,
            counters = counters.toList(),
            memory = memory.toList(),
            trims = synchronized(trims) { trims.toList() },
            stalls = synchronized(stalls) { stalls.toList() },
            stallDurations = synchronized(stallDurations) { stallDurations.toList() },
            threadCpuMs = cpuDelta(cpuStart, cpuEnd),
            totalMemKb = memInfo()?.totalMem?.div(1024) ?: 0,
            apiLevel = Build.VERSION.SDK_INT,
            thermalStatus = thermalStatus(),
            windowSize = windowSize,
        )
    }

    // ------------------------------------------------------------------ 帧

    private fun onFrame(m: FrameMetrics) {
        if (!running) return
        try {
            if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
            frames += PerfFrame(
                vsyncNs = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                totalNs = m.getMetric(FrameMetrics.TOTAL_DURATION),
                delayNs = m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
                inputNs = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                animNs = m.getMetric(FrameMetrics.ANIMATION_DURATION),
                layoutNs = m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                drawNs = m.getMetric(FrameMetrics.DRAW_DURATION),
                syncNs = m.getMetric(FrameMetrics.SYNC_DURATION),
                cmdNs = m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                swapNs = m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                gpuNs = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else -1,
                deadlineNs = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else -1,
            )
            if (frames.size > MAX_FRAMES) frames.removeAt(0)
        } catch (e: Throwable) {
            logger.warn(e) { "onFrame failed" }
        }
    }

    // ------------------------------------------------------------------ 计数器 / 内存

    private fun sample(withMemAvail: Boolean = (tick % 10 == 0)) {
        tick++
        val runtime = Runtime.getRuntime()
        counters += PerfCounters(
            tNs = System.nanoTime(),
            gcCount = runtimeStat("art.gc.gc-count"),
            gcTimeMs = runtimeStat("art.gc.gc-time"),
            blockingGcCount = runtimeStat("art.gc.blocking-gc-count"),
            blockingGcTimeMs = runtimeStat("art.gc.blocking-gc-time"),
            bytesAllocated = runtimeStat("art.gc.bytes-allocated"),
            mainMajflt = majorFaults("/proc/self/task/$pid/stat"),
            processMajflt = majorFaults("/proc/self/stat"),
            javaUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024,
            javaMaxKb = runtime.maxMemory() / 1024,
            nativeKb = Debug.getNativeHeapAllocatedSize() / 1024,
            memAvailKb = if (withMemAvail) memInfo()?.availMem?.div(1024) ?: -1 else -1,
        )
    }

    private fun memorySnapshot() {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        fun stat(name: String) = info.getMemoryStat(name)?.toLongOrNull() ?: 0L
        memory += PerfMemSnapshot(
            tNs = System.nanoTime(),
            pssKb = stat("summary.total-pss"),
            javaKb = stat("summary.java-heap"),
            nativeKb = stat("summary.native-heap"),
            graphicsKb = stat("summary.graphics"),
            codeKb = stat("summary.code"),
            otherKb = stat("summary.private-other"),
        )
    }

    private fun memInfo(): ActivityManager.MemoryInfo? = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }.getOrNull()

    private fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0 else 0

    // ------------------------------------------------------------------ GC 日志

    private fun startLogcat() {
        try {
            // 只看本进程, -T 1 = 从现在开始 (只带出缓冲里最后一行), -e 只留 GC 那两类
            val p = ProcessBuilder("logcat", "-v", "monotonic", "--pid=$pid", "-T", "1", "-e", PerfGcLog.FILTER)
                .redirectErrorStream(true)
                .start()
            logcat = p
            thread(name = "ani-perf-logcat", isDaemon = true) {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        val e = PerfGcLog.parse(line) ?: return@forEachLine
                        if (e.endNs >= startNs) gcEvents += e
                    }
                } catch (_: Exception) {
                    // destroy() 时读断, 正常结束
                }
            }
        } catch (e: Throwable) {
            logger.warn(e) { "Failed to read GC log" }
        }
    }

    // ------------------------------------------------------------------ 主线程看门狗

    private fun startWatchdog() {
        val mainThread = Looper.getMainLooper().thread
        val pongAt = AtomicLong(0)
        val pong = Runnable { pongAt.set(System.nanoTime()) }
        watchdog = thread(name = "ani-perf-watchdog", isDaemon = true) {
            var pending = false
            var sentAt = 0L
            var samples = 0
            var lastSampleMs = 0L
            while (running) {
                try {
                    val now = System.nanoTime()
                    if (pending) {
                        val answeredAt = pongAt.get()
                        if (answeredAt >= sentAt) {
                            val blockedMs = (answeredAt - sentAt) / 1_000_000
                            if (blockedMs >= STALL_THRESHOLD_MS) stallDurations += blockedMs
                            pending = false
                        } else {
                            val blockedMs = (now - sentAt) / 1_000_000
                            // 卡住的前 ~200ms 每拍一采, 更久的每 250ms 补一采, 一次卡顿最多 20 采
                            if (blockedMs >= STALL_THRESHOLD_MS &&
                                (samples < 8 || (samples < 20 && blockedMs - lastSampleMs >= 250))
                            ) {
                                val stack = mainThread.stackTrace
                                samples++
                                lastSampleMs = blockedMs
                                if (stalls.size < MAX_STALL_SAMPLES) stalls += stallOf(now, blockedMs, stack)
                            }
                        }
                    }
                    if (!pending) {
                        pending = true
                        samples = 0
                        lastSampleMs = 0
                        sentAt = System.nanoTime()
                        mainHandler.post(pong)
                    }
                    Thread.sleep(WATCHDOG_TICK_MS)
                } catch (_: InterruptedException) {
                    return@thread
                } catch (e: Throwable) {
                    logger.warn(e) { "watchdog error" }
                    return@thread
                }
            }
        }
    }

    private fun stallOf(t: Long, blockedMs: Long, stack: Array<StackTraceElement>): PerfStall {
        val context = when {
            stack.any { it.methodName == "dispatchKeyEvent" || it.className.endsWith("InputEventReceiver") } -> "按键分发"
            stack.any { it.className == "android.view.Choreographer" && it.methodName == "doFrame" } -> "帧回调"
            stack.any { it.className.startsWith("kotlinx.coroutines") } -> "协程"
            else -> "其它消息"
        }
        // me.him188.ani.r8.* 是 R8 合并过的合成 lambda, 不代表应用代码
        val appTop = stack.firstOrNull {
            it.className.startsWith("me.him188.ani") && !it.className.startsWith("me.him188.ani.r8.") &&
                !it.className.startsWith("me.him188.ani.app.ui.diagnostics")
        }?.let { "${it.className.substringAfterLast('.')}.${it.methodName.take(60)}" }
        return PerfStall(t, blockedMs, stack.take(40).map { it.toString() }, context, appTop)
    }

    // ------------------------------------------------------------------ /proc

    /** tid → (线程名, utime + stime 滴答). */
    private fun readThreadCpu(): Map<Int, Pair<String, Long>> {
        val result = HashMap<Int, Pair<String, Long>>()
        val tasks = File("/proc/self/task").list() ?: return result
        for (tidText in tasks) {
            val tid = tidText.toIntOrNull() ?: continue
            val text = runCatching { File("/proc/self/task/$tidText/stat").readText() }.getOrNull() ?: continue
            val open = text.indexOf('(')
            val close = text.lastIndexOf(')')
            if (open < 0 || close < 0 || close + 2 > text.length) continue
            // ")" 之后从第 3 个字段 (state) 开始, utime / stime 是第 14 / 15 个字段
            val fields = text.substring(close + 2).split(' ')
            val ticks = (fields.getOrNull(11)?.toLongOrNull() ?: 0L) + (fields.getOrNull(12)?.toLongOrNull() ?: 0L)
            result[tid] = text.substring(open + 1, close) to ticks
        }
        return result
    }

    private fun cpuDelta(start: Map<Int, Pair<String, Long>>, end: Map<Int, Pair<String, Long>>): Map<String, Long> {
        val ticksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
        val byName = HashMap<String, Long>()
        for ((tid, pair) in end) {
            val d = (pair.second - (start[tid]?.second ?: 0L)).coerceAtLeast(0)
            if (d == 0L) continue
            // 同类线程 (DefaultDispatch-1 / -2 …) 合在一起
            val name = if (tid == pid) "main" else pair.first.trimEnd { it.isDigit() || it == '-' || it == '#' }.ifEmpty { pair.first }
            byName[name] = (byName[name] ?: 0L) + d * 1000 / ticksPerSecond
        }
        return byName
    }

    private fun majorFaults(path: String): Long {
        val text = runCatching { File(path).readText() }.getOrNull() ?: return -1
        // ")" 之后下标 0 是第 3 个字段, majflt 是第 12 个
        return text.substringAfterLast(')').trim().split(' ').getOrNull(12 - 3)?.toLongOrNull() ?: -1
    }

    private fun runtimeStat(name: String): Long = Debug.getRuntimeStat(name)?.toLongOrNull() ?: -1

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logger.warn(e) { "perf diagnostics: $what failed" }
        }
    }

    private companion object {
        private val logger = logger<TvPerfRecorder>()
        const val SAMPLE_INTERVAL_MS = 100L
        const val MEMORY_INTERVAL_MS = 5_000L
        const val WATCHDOG_TICK_MS = 16L

        /** 主线程超过这么久没执行看门狗的空任务就算卡住, 60Hz 下约三帧. */
        const val STALL_THRESHOLD_MS = 48L
        const val MAX_FRAMES = 20_000
        const val MAX_STALL_SAMPLES = 300
    }
}
