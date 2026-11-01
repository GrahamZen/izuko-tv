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
import android.net.TrafficStats
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import me.him188.ani.app.platform.perf.PerfTimeline
import me.him188.ani.app.ui.remote.tr
import me.him188.ani.app.ui.subject.episode.tv.source.TvSearchStutter
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.File

/**
 * 播放哨兵: 电视上常驻的 [PerfTimeline] 接收者 (界面装上时由 [TvPerfDiagnostics.attach] 装), 把各处报的事记进 [timeline], 只留最近 [RETAIN_NS].
 * 「性能诊断 → 录制」与画面异常卡顿时的自动报告都从这条时间线取.
 *
 * 在播时 (播放器报「开始播放」起, 到「停止播放」为止) 自己的线程上:
 * - 每秒记整机可用内存与交换区 (`/proc/meminfo`)、本应用的主缺页次数、Java 堆、GC 次数与耗时 (ART 统计);
 *   没在录制时再记全进程 CPU 与收到的字节 (录制时录制器按线程组记);
 * - 每 [PING_MS] 往主线程投一个空任务, 等了 [STALL_MARK_MS] 还没执行就记下主线程停在哪, 执行时记一条「主线程卡住 N 毫秒」;
 * - 每秒拿最近十来秒问 [PerfPlaybackAnalysis.detect] 画面是不是异常卡顿, 是 (或主线程卡住超过 [STALL_TRIGGER_MS]) 就让 [TvPerfDiagnostics] 自动录一份.
 *
 * 不触发时不能影响播放: 这些都在自己的线程上做, 每秒读两个小文件、几次哈希表操作; 主线程上只有空任务. 暂停或没在播时线程闲着.
 */
internal object TvPlaybackSentinel : PerfTimeline.Sink {
    private val logger = logger<TvPlaybackSentinel>()

    /** 留最近这么久: 自动报告要触发前一分钟, 加上触发后录的那段. */
    private const val RETAIN_NS = 90_000_000_000L
    private const val TICK_MS = 1000L
    private const val PING_MS = 250L
    private const val STALL_MARK_MS = 300L
    private const val STALL_TRIGGER_MS = 1000L
    private const val DETECT_WINDOW_NS = 12_000_000_000L
    private const val MB = 1024L * 1024

    // 报事件的地方用的名字 (播放器 PlaybackPerfProbe、开播 PlaybackPerfTraceExtension)
    private const val PLAYBACK = "播放"
    private const val START = "开播"
    private const val PROCESS_CPU = "全进程"

    val timeline = PerfTimelineCollector(System.nanoTime(), System::nanoTime, RETAIN_NS)

    @Volatile
    private var installed = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var playing = false

    /** 当前这一集 (开播报的「条目 … · 剧集 …」), 自动报告按集限频. */
    @Volatile
    private var episode: String? = null

    /** 录制器在跑: 它按线程组记 CPU 与流量, 哨兵不重复记. */
    @Volatile
    private var recording = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val handler: Handler by lazy {
        val thread = HandlerThread("ani-perf-sentinel")
        thread.start()
        Handler(thread.looper)
    }

    // 只在哨兵线程上读写
    private var ticking = false
    private var lastSampleNs = 0L
    private var lastCpuTicks = -1L
    private var lastRx = -1L
    private var lastGcCount = -1L
    private var lastGcMs = -1L
    private var lastMajorFaults = -1L
    private val clockTicks: Long by lazy { runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1) }

    // 主线程看门狗: 在等的空任务是什么时候投的 (0 = 没有在等的); 卡住时主线程停在哪
    @Volatile
    private var pingPostedNs = 0L

    @Volatile
    private var stallTop: String? = null

    @Volatile
    private var stallTriggered = false

    private val ping = Runnable {
        val posted = pingPostedNs
        pingPostedNs = 0
        if (posted == 0L) return@Runnable
        val waitedMs = (System.nanoTime() - posted) / 1_000_000
        if (waitedMs >= STALL_MARK_MS) {
            timeline.event(PerfPlaybackAnalysis.SENTINEL, PerfPlaybackAnalysis.MAIN_STALL, "$waitedMs 毫秒" + (stallTop?.let { " · $it" } ?: ""))
        }
        stallTop = null
        stallTriggered = false
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!ticking) return
            val now = System.nanoTime()
            val posted = pingPostedNs
            if (posted == 0L) {
                pingPostedNs = now
                mainHandler.post(ping)
            } else {
                val waitedMs = (now - posted) / 1_000_000
                if (waitedMs >= STALL_MARK_MS && stallTop == null) stallTop = safe("stack") { mainStackTop() }
                if (waitedMs >= STALL_TRIGGER_MS && !stallTriggered) {
                    stallTriggered = true
                    if (safe("searchBlamed") { PerfPlaybackAnalysis.searchBlamed(timeline.snapshot(now - DETECT_WINDOW_NS, now), now, mainStalled = true) } == true) {
                        TvSearchStutter.report(episode)
                    }
                    trigger(tr("主线程卡住了 1 秒以上"), now)
                }
            }
            handler.postDelayed(this, PING_MS)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            timeline.prune(now)
            if (!playing) {
                ticking = false
                return
            }
            safe("system") { sampleSystem(now) }
            safe("detect") { detect(now) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            timeline.event(PerfPlaybackAnalysis.SYSTEM, "内存不足通知", trimLevelName(level))
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            timeline.event(PerfPlaybackAnalysis.SYSTEM, "内存不足通知", "LOW_MEMORY")
        }
    }

    /** 装上 (多次调只装一次). */
    fun install(context: Context) {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val app = context.applicationContext
        appContext = app
        runCatching { app.registerComponentCallbacks(trimCallbacks) }
        PerfTimeline.attach(this)
    }

    /** 录制器开始 / 结束: 录制期间 [fromNs] 以后的都留着, CPU 与流量由录制器记. */
    fun recorderStarted(fromNs: Long) {
        recording = true
        timeline.hold(fromNs)
    }

    fun recorderStopped() {
        recording = false
        timeline.hold(null)
    }

    // ------------------------------------------------------------------ 接收

    override fun now(): Long = timeline.now()

    override fun event(category: String, name: String, detail: String) {
        timeline.event(category, name, detail)
        when {
            category == PLAYBACK && name == "开始播放" -> {
                playing = true
                handler.post {
                    if (!ticking) {
                        ticking = true
                        lastSampleNs = 0
                        // 暂停后很快又播: 上一轮还排着的那拍先撤掉, 免得跑两份
                        handler.removeCallbacks(tick)
                        handler.removeCallbacks(watchdog)
                        tick.run()
                        watchdog.run()
                    }
                }
            }

            category == PLAYBACK && name == "停止播放" -> playing = false
            category == START && name == "本集" -> episode = detail
        }
    }

    override fun time(category: String, name: String, durationNs: Long) = timeline.time(category, name, durationNs)

    override fun count(category: String, name: String, delta: Long, spanNs: Long) = timeline.count(category, name, delta, spanNs)

    override fun gauge(category: String, name: String, value: Long) = timeline.gauge(category, name, value)

    // ------------------------------------------------------------------ 每秒

    private fun sampleSystem(now: Long) {
        // 每项各自兜底: 有的节点在某些系统上读不到, 别连累别的
        val stat = runCatching { readProcessStat() }.getOrNull()
        val majorFaults = stat?.majorFaults
        if (majorFaults != null && lastMajorFaults >= 0) {
            timeline.count(PerfPlaybackAnalysis.SYSTEM, PerfPlaybackAnalysis.MAJOR_FAULTS, majorFaults - lastMajorFaults)
        }
        lastMajorFaults = majorFaults ?: -1
        val mem = runCatching { readMeminfoKb("MemAvailable:", "SwapFree:") }.getOrDefault(emptyMap())
        mem["MemAvailable:"]?.let { timeline.gauge(PerfPlaybackAnalysis.SYSTEM, PerfPlaybackAnalysis.MEM_AVAIL_MB, it / 1024) }
        mem["SwapFree:"]?.let { timeline.gauge(PerfPlaybackAnalysis.SYSTEM, PerfPlaybackAnalysis.SWAP_FREE_MB, it / 1024) }
        val runtime = Runtime.getRuntime()
        timeline.gauge(PerfPlaybackAnalysis.SYSTEM, PerfPlaybackAnalysis.JAVA_MB, (runtime.totalMemory() - runtime.freeMemory()) / MB)

        val gcCount = runCatching { Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() }.getOrNull()
        val gcMs = runCatching { Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() }.getOrNull()
        if (gcCount != null && lastGcCount >= 0) timeline.count(PerfPlaybackAnalysis.SYSTEM, PerfPlaybackAnalysis.GC_COUNT, gcCount - lastGcCount)
        if (gcMs != null && lastGcMs >= 0) timeline.count(PerfPlaybackAnalysis.SYSTEM, PerfPlaybackAnalysis.GC_MS, gcMs - lastGcMs)
        lastGcCount = gcCount ?: -1
        lastGcMs = gcMs ?: -1

        if (recording) {
            lastSampleNs = 0
            return
        }
        val cpu = stat?.cpuTicks ?: -1
        val rx = TrafficStats.getUidRxBytes(Process.myUid())
        if (lastSampleNs != 0L && cpu >= 0 && lastCpuTicks >= 0) {
            timeline.recordInterval(
                lastSampleNs, now,
                mapOf(PROCESS_CPU to (cpu - lastCpuTicks) * 1000 / clockTicks),
                if (rx >= 0 && lastRx >= 0) rx - lastRx else -1,
            )
        }
        lastSampleNs = now
        lastCpuTicks = cpu
        lastRx = rx
    }

    private fun detect(now: Long) {
        val snapshot = timeline.snapshot(now - DETECT_WINDOW_NS, now)
        val reason = PerfPlaybackAnalysis.detect(snapshot, now) ?: return
        // 搜索导致的: 让播放页改「只手动」、暂停搜索 (见 TvSearchStutter). 跟自动录制各管各的, 录过这一集照样判
        if (PerfPlaybackAnalysis.searchBlamed(snapshot, now)) TvSearchStutter.report(episode)
        if (!TvPerfDiagnostics.autoCaptureAllowed(episode)) return
        trigger(reason, now)
    }

    private fun trigger(reason: String, now: Long) {
        val episode = episode
        if (!TvPerfDiagnostics.autoCaptureAllowed(episode)) return
        timeline.event(PerfPlaybackAnalysis.SENTINEL, PerfPlaybackAnalysis.TRIGGER, reason)
        safe("memory") { memorySummary() }?.let { timeline.event(PerfPlaybackAnalysis.SENTINEL, PerfPlaybackAnalysis.MEMORY, it) }
        logger.warn { "[PerfAuto] 画面异常卡顿, 自动录制: $reason ($episode)" }
        TvPerfDiagnostics.startAutoCapture(reason, now, episode)
    }

    // ------------------------------------------------------------------ 读系统

    private fun readMeminfoKb(vararg keys: String): Map<String, Long> {
        val result = HashMap<String, Long>(keys.size)
        File("/proc/meminfo").forEachLine { line ->
            val key = keys.firstOrNull { line.startsWith(it) } ?: return@forEachLine
            line.substring(key.length).trim().substringBefore(' ').toLongOrNull()?.let { result[key] = it }
        }
        return result
    }

    private class ProcessStat(val cpuTicks: Long, val majorFaults: Long)

    /** `/proc/self/stat`: utime + stime (时钟滴答) 与主缺页次数. */
    private fun readProcessStat(): ProcessStat {
        val fields = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')
        // 括号后第一个是 state (第 3 字段); majflt 是第 12 字段, utime / stime 是第 14 / 15 字段
        return ProcessStat(cpuTicks = fields[11].toLong() + fields[12].toLong(), majorFaults = fields[9].toLong())
    }

    private fun memorySummary(): String? {
        val am = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val swap = readMeminfoKb("SwapTotal:", "SwapFree:")
        return "可用 ${info.availMem / MB} / 共 ${info.totalMem / MB} MB · 系统低内存线 ${info.threshold / MB} MB · " +
            (if (info.lowMemory) "已低于" else "未低于") +
            (swap["SwapTotal:"]?.takeIf { it > 0 }?.let { " · 交换区剩 ${(swap["SwapFree:"] ?: 0) / 1024} / ${it / 1024} MB" } ?: "")
    }

    /** 主线程现在停在哪: 跳过系统框架, 取前几帧. */
    private fun mainStackTop(): String {
        val stack = Looper.getMainLooper().thread.stackTrace
        val app = stack.firstOrNull { it.className.startsWith("me.him188") || it.className.startsWith("io.github") }
        val top = stack.take(3).joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        return if (app != null && app !in stack.take(3)) "$top … ${app.className.substringAfterLast('.')}.${app.methodName}" else top
    }

    @Suppress("DEPRECATION")
    private fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> level.toString()
    }

    private inline fun <T> safe(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        logger.warn(e) { "Playback sentinel: $what failed" }
        null
    }
}
