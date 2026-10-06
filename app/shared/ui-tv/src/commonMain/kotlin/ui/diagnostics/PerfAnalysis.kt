/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.ui.remote.tr
import java.util.Locale

/*
 * 「性能诊断」录制的分析: 录到的帧、GC、内存、主线程卡顿 → 结论. 不碰 Android, 输入由 [TvPerfRecorder] 采集.
 * 时间一律是 System.nanoTime 那一个时钟 (CLOCK_MONOTONIC): FrameMetrics 的 vsync、`logcat -v monotonic` 的时间戳都是它.
 */

/** 一帧 (FrameMetrics); 拿不到的项为 -1 (GPU 与截止时刻要 Android 12+). */
internal class PerfFrame(
    val vsyncNs: Long,
    val totalNs: Long,
    val delayNs: Long,
    val inputNs: Long,
    val animNs: Long,
    val layoutNs: Long,
    val drawNs: Long,
    val syncNs: Long,
    val cmdNs: Long,
    val swapNs: Long,
    val gpuNs: Long = -1,
    val deadlineNs: Long = -1,
) {
    /** 主线程上的部分: 输入 + 动画 + 布局 + 录制. */
    val uiNs: Long get() = inputNs + animNs + layoutNs + drawNs
    val endNs: Long get() = vsyncNs + totalNs
}

/**
 * ART 打的一条 GC 日志. [wait] = `WaitForGcToComplete blocked …`: 线程 [tid] 停下来等别的 GC 做完;
 * 否则是一次 GC 本身 (由 [tid] 这个线程执行: 后台 GC 是 HeapTaskDaemon, Alloc / Explicit GC 是触发它的线程).
 */
internal class PerfGcEvent(
    val endNs: Long,
    val durationMs: Double,
    val pausedMs: Double,
    val cause: String,
    val tid: Int,
    val wait: Boolean,
) {
    val startNs: Long get() = endNs - (durationMs * 1e6).toLong()
}

/** 采样器每 ~100ms 一个点; [memAvailKb] 只有每秒那一拍有, 其余为 -1. */
internal class PerfCounters(
    val tNs: Long,
    val gcCount: Long,
    val gcTimeMs: Long,
    val blockingGcCount: Long,
    val blockingGcTimeMs: Long,
    val bytesAllocated: Long,
    val mainMajflt: Long,
    val processMajflt: Long,
    val javaUsedKb: Long,
    val javaMaxKb: Long,
    val nativeKb: Long,
    val memAvailKb: Long = -1,
)

/** 进程内存 (PSS, KB) 按类拆开, 每 ~5 秒一次. [graphicsKb] 在部分电视上恒为 0 (显存不按进程记账), GPU 驱动记的那份在 [otherKb] 里. */
internal class PerfMemSnapshot(
    val tNs: Long,
    val pssKb: Long,
    val javaKb: Long,
    val nativeKb: Long,
    val graphicsKb: Long,
    val codeKb: Long,
    val otherKb: Long,
)

/** 系统发来的 onTrimMemory (前台时只会是 RUNNING_MODERATE / LOW / CRITICAL = 5 / 10 / 15). */
internal class PerfTrim(val tNs: Long, val level: Int)

/** 主线程卡住时采到的一次堆栈. [context] = 在处理哪类消息, [appTop] = 栈顶往下第一个应用自己的帧. */
internal class PerfStall(
    val tNs: Long,
    val blockedMs: Long,
    val stack: List<String>,
    val context: String,
    val appTop: String?,
)

internal class PerfRecording(
    val startNs: Long,
    val endNs: Long,
    val refreshHz: Float,
    val cores: Int,
    val mainTid: Int,
    val frames: List<PerfFrame>,
    val gcEvents: List<PerfGcEvent>,
    val gcLogAvailable: Boolean,
    val counters: List<PerfCounters>,
    val memory: List<PerfMemSnapshot>,
    val trims: List<PerfTrim>,
    val stalls: List<PerfStall>,
    /** 主线程卡住的次数与时长 (看门狗量的, 每次卡顿一条, 毫秒). */
    val stallDurations: List<Long>,
    /** 线程名 → 录制期间用的 CPU 毫秒. 主线程叫 "main". */
    val threadCpuMs: Map<String, Long>,
    val totalMemKb: Long,
    val apiLevel: Int,
    val thermalStatus: Int,
    /** 窗口实际渲染尺寸, 如 "1920x1080". */
    val windowSize: String,
)

/** 解析 `logcat -v monotonic` 里 ART 的 GC 日志. */
internal object PerfGcLog {
    // "   156.234  1234  1240 I tag     : message" (monotonic 时间 = 开机秒数, 与 System.nanoTime 同一时钟)
    private val LINE = Regex("""^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+.*?:\s(.*)$""")
    private val GC = Regex("""^(.+?) GC freed .*? paused (\S+) total ([\d.]+)(ns|us|ms|s)\b""")
    private val WAIT = Regex("""^WaitForGcToComplete blocked (.+?) for ([\d.]+)(ns|us|ms|s)\b""")
    private val DURATION = Regex("""([\d.]+)(ns|us|ms|s)""")

    /** 喂给 `logcat -e` 的过滤, 只留这两类. */
    const val FILTER = "GC freed|WaitForGcToComplete"

    fun parse(line: String): PerfGcEvent? {
        val m = LINE.find(line) ?: return null
        val seconds = m.groupValues[1].toDoubleOrNull() ?: return null
        val tid = m.groupValues[3].toIntOrNull() ?: return null
        val message = m.groupValues[4]
        val endNs = (seconds * 1e9).toLong()
        GC.find(message)?.let { g ->
            val paused = DURATION.findAll(g.groupValues[2]).sumOf { toMs(it.groupValues[1], it.groupValues[2]) }
            return PerfGcEvent(endNs, toMs(g.groupValues[3], g.groupValues[4]), paused, g.groupValues[1].trim(), tid, wait = false)
        }
        WAIT.find(message)?.let { w ->
            val ms = toMs(w.groupValues[2], w.groupValues[3])
            return PerfGcEvent(endNs, ms, ms, w.groupValues[1].trim(), tid, wait = true)
        }
        return null
    }

    private fun toMs(value: String, unit: String): Double {
        val v = value.toDoubleOrNull() ?: return 0.0
        return when (unit) {
            "ns" -> v / 1e6
            "us" -> v / 1e3
            "ms" -> v
            else -> v * 1e3
        }
    }
}

internal class StageStat(val p50Ns: Long, val p90Ns: Long, val maxNs: Long) {
    companion object {
        fun of(values: List<Long>): StageStat? {
            val v = values.filter { it >= 0 }.sorted()
            if (v.isEmpty()) return null
            return StageStat(v[(v.size - 1) / 2], v[((v.size - 1) * 9) / 10], v.last())
        }
    }
}

internal class FrameSummary(
    val count: Int,
    val durationMs: Long,
    val fps: Double,
    val periodNs: Long,
    val janky: Int,
    val missedVsync: Long,
    val total: StageStat?,
    val ui: StageStat?,
    val delay: StageStat?,
    val sync: StageStat?,
    val cmd: StageStat?,
    val swap: StageStat?,
    val gpu: StageStat?,
) {
    val jankyPercent: Double get() = if (count == 0) 0.0 else janky * 100.0 / count
}

/** 慢帧按原因计数 (一帧可以同时算进几类). */
internal class JankCauses(
    val janky: Int,
    /** 主线程在做 GC (Alloc / Explicit GC 由它执行) 或停下来等 GC. */
    val gcMain: Int,
    /** 同一时刻后台在 GC (不停主线程, 但抢 CPU). */
    val gcBackground: Int,
    /** 这一帧期间主线程发生了主缺页 (要从存储读回内存页). */
    val pageFault: Int,
    /** 这一帧期间看门狗采到主线程卡住. */
    val mainBusy: Int,
    /** 前后 1 秒内系统发来 TRIM_MEMORY_RUNNING_LOW / CRITICAL. */
    val lowMemory: Int,
)

internal class PerfFinding(val level: Level, val text: String) {
    enum class Level { BAD, WARN, INFO, OK }
}

internal class PerfResult(
    val frames: FrameSummary,
    val causes: JankCauses,
    val findings: List<PerfFinding>,
    val json: JsonObject,
)

internal object PerfAnalysis {
    private const val NS = 1_000_000L
    private const val KB_PER_MB = 1024L

    /** 少于这么多帧就不下帧相关的结论 (录制时没人操作电视). */
    const val MIN_FRAMES = 30

    /** 一类原因占慢帧这么多就算主因. */
    private const val CAUSE_SHARE = 0.25
    private const val TRIM_RUNNING_LOW = 10
    private const val JIT_THREAD = "Jit thread pool"

    /** 不算「后台任务」的线程: 界面本身、JIT 与 GC (这两样另有结论). */
    private val FOREGROUND_THREADS = setOf("main", "RenderThread", JIT_THREAD, "HeapTaskDaemon")

    fun periodNs(rec: PerfRecording): Long =
        if (rec.refreshHz > 1f) (1e9 / rec.refreshHz).toLong() else inferPeriod(rec.frames)

    private fun inferPeriod(frames: List<PerfFrame>): Long {
        var best = Long.MAX_VALUE
        for (i in 1 until frames.size) {
            val gap = frames[i].vsyncNs - frames[i - 1].vsyncNs
            if (gap in 5 * NS until best) best = gap
        }
        return if (best == Long.MAX_VALUE) 16_666_667L else best
    }

    private fun isJanky(f: PerfFrame, periodNs: Long): Boolean {
        val limit = if (f.deadlineNs > 0) f.deadlineNs else periodNs
        return limit > 0 && f.totalNs > limit
    }

    fun summarizeFrames(frames: List<PerfFrame>, periodNs: Long): FrameSummary {
        if (frames.isEmpty()) return FrameSummary(0, 0, 0.0, periodNs, 0, 0, null, null, null, null, null, null, null)
        val first = frames.first().vsyncNs
        val end = frames.maxOf { it.endNs }
        val durationMs = ((end - first) / NS).coerceAtLeast(1)
        // 画面在动的时间: 相邻两帧不超过 10 个周期的那些间隔加起来 (与下面掉 vsync 同一口径); 更久的算静止, 不算进去,
        // 否则按键之间的停顿会把平均帧率拉低
        var activeNs = periodNs.coerceAtLeast(1)
        for (i in 1 until frames.size) {
            val gap = frames[i].vsyncNs - frames[i - 1].vsyncNs
            if (gap > 0 && (gap + periodNs / 2) / periodNs.coerceAtLeast(1) <= 10) activeNs += gap
        }
        // 掉的 vsync: 相邻两帧的预定 vsync 隔了 N 个周期就是掉了 N-1 个; 隔 10 个周期以上算页面空闲 (没有动画), 不计
        var missed = 0L
        for (i in 1 until frames.size) {
            val gap = frames[i].vsyncNs - frames[i - 1].vsyncNs
            if (periodNs > 0 && gap > 0) {
                val periods = (gap + periodNs / 2) / periodNs
                if (periods in 2..10) missed += periods - 1
            }
        }
        return FrameSummary(
            count = frames.size,
            durationMs = durationMs,
            fps = frames.size * 1e9 / activeNs,
            periodNs = periodNs,
            janky = frames.count { isJanky(it, periodNs) },
            missedVsync = missed,
            total = StageStat.of(frames.map { it.totalNs }),
            ui = StageStat.of(frames.map { it.uiNs }),
            delay = StageStat.of(frames.map { it.delayNs }),
            sync = StageStat.of(frames.map { it.syncNs }),
            cmd = StageStat.of(frames.map { it.cmdNs }),
            swap = StageStat.of(frames.map { it.swapNs }),
            gpu = StageStat.of(frames.map { it.gpuNs }),
        )
    }

    /** 每个慢帧 (连同它前面一个周期, 帧开始前的事也算) 和各类事件对时间轴. */
    fun attribute(rec: PerfRecording, periodNs: Long): JankCauses {
        val janky = rec.frames.filter { isJanky(it, periodNs) }
        var gcMain = 0
        var gcBackground = 0
        var pageFault = 0
        var mainBusy = 0
        var lowMemory = 0
        val lowTrims = rec.trims.filter { it.level >= TRIM_RUNNING_LOW }
        for (f in janky) {
            val from = f.vsyncNs - periodNs
            val to = f.endNs
            fun overlaps(start: Long, end: Long) = start <= to && end >= from
            if (rec.gcEvents.any { it.tid == rec.mainTid && overlaps(it.startNs, it.endNs) }) gcMain++
            if (rec.gcEvents.any { it.tid != rec.mainTid && !it.wait && overlaps(it.startNs, it.endNs) }) gcBackground++
            if (mainFaultsBetween(rec.counters, from, to) > 0) pageFault++
            if (rec.stalls.any { it.tNs in from..to }) mainBusy++
            if (lowTrims.any { it.tNs in (from - 1_000 * NS)..(to + 1_000 * NS) }) lowMemory++
        }
        return JankCauses(janky.size, gcMain, gcBackground, pageFault, mainBusy, lowMemory)
    }

    /** 采样区间与 [from, to] 相交的那几段里主线程主缺页的增量. */
    private fun mainFaultsBetween(counters: List<PerfCounters>, from: Long, to: Long): Long {
        var sum = 0L
        for (i in 1 until counters.size) {
            val a = counters[i - 1]
            val b = counters[i]
            if (a.tNs <= to && b.tNs >= from && a.mainMajflt >= 0 && b.mainMajflt >= 0) sum += b.mainMajflt - a.mainMajflt
        }
        return sum
    }

    fun analyze(rec: PerfRecording): PerfResult {
        val periodNs = periodNs(rec)
        val frames = summarizeFrames(rec.frames, periodNs)
        val causes = attribute(rec, periodNs)
        val seconds = ((rec.endNs - rec.startNs) / 1e9).coerceAtLeast(0.001)
        val first = rec.counters.firstOrNull()
        val last = rec.counters.lastOrNull()
        val gcCount = delta(first?.gcCount, last?.gcCount)
        val gcTimeMs = delta(first?.gcTimeMs, last?.gcTimeMs)
        val blockingCount = delta(first?.blockingGcCount, last?.blockingGcCount)
        val blockingMs = delta(first?.blockingGcTimeMs, last?.blockingGcTimeMs)
        val allocMbPerSec = delta(first?.bytesAllocated, last?.bytesAllocated) / (1024.0 * 1024.0) / seconds
        val mainGcMs = rec.gcEvents.filter { it.tid == rec.mainTid }.sumOf { if (it.wait) it.durationMs else it.pausedMs.coerceAtLeast(it.durationMs) }
        val memAvails = rec.counters.map { it.memAvailKb }.filter { it > 0 }
        val memAvailMinMb = memAvails.minOrNull()?.div(KB_PER_MB)
        val totalMb = rec.totalMemKb / KB_PER_MB
        val javaUsedMaxMb = rec.counters.maxOfOrNull { it.javaUsedKb }?.div(KB_PER_MB) ?: 0
        val javaMaxMb = last?.javaMaxKb?.div(KB_PER_MB) ?: 0
        val mainFaults = delta(first?.mainMajflt, last?.mainMajflt)
        val processFaults = delta(first?.processMajflt, last?.processMajflt)
        val processCpuMs = rec.threadCpuMs.values.sum()
        val coresUsed = processCpuMs / (seconds * 1000.0)

        val findings = ArrayList<PerfFinding>()
        val enoughFrames = frames.count >= MIN_FRAMES
        if (!enoughFrames) {
            findings += PerfFinding(
                PerfFinding.Level.WARN,
                tr("录制期间几乎没有画面更新（{0} 帧），帧相关的结论不准。录制时要在电视上操作想测的界面。", frames.count),
            )
        } else {
            val level = when {
                frames.jankyPercent >= 10 -> PerfFinding.Level.BAD
                frames.jankyPercent >= 3 -> PerfFinding.Level.WARN
                else -> PerfFinding.Level.OK
            }
            findings += PerfFinding(
                level,
                tr(
                    "{0} 帧，画面变化时平均 {1} fps；慢帧 {2} 个（{3}%），掉帧 {4} 次。",
                    frames.count, fmt1(frames.fps), frames.janky, fmt1(frames.jankyPercent), frames.missedVsync,
                ),
            )
        }

        // ---- GC
        val gcShare = share(causes.gcMain, causes.janky)
        findings += when {
            enoughFrames && causes.janky >= 5 && gcShare >= CAUSE_SHARE -> PerfFinding(
                PerfFinding.Level.BAD,
                tr(
                    "卡顿主要来自 GC：{0} 个慢帧里有 {1} 个落在主线程做 GC 或等 GC 的时候（主线程因 GC 停了约 {2} 毫秒）。录制期间 GC {3} 次共 {4} 毫秒，每秒分配 {5} MB。",
                    causes.janky, causes.gcMain, mainGcMs.toLong(), gcCount, gcTimeMs, fmt1(allocMbPerSec),
                ),
            )

            blockingCount > 0 -> PerfFinding(
                if (causes.gcMain > 0) PerfFinding.Level.WARN else PerfFinding.Level.INFO,
                tr(
                    "有 {0} 次阻塞式 GC（共 {1} 毫秒）：触发它的线程要停下来等 GC 做完（分配跟不上回收，或代码主动要求 GC）；{2} 个慢帧发生时主线程在做 GC 或等 GC。每秒分配 {3} MB。",
                    blockingCount, blockingMs, causes.gcMain, fmt1(allocMbPerSec),
                ),
            )

            // 次数多, GC 跑的总时长长 (慢设备上后台 GC 一次就要几百毫秒), 或者慢帧常与后台 GC 重叠
            gcCount / seconds > 0.5 || gcTimeMs > seconds * 1000 * 0.1 ||
                (enoughFrames && causes.janky >= 5 && share(causes.gcBackground, causes.janky) >= CAUSE_SHARE) -> PerfFinding(
                PerfFinding.Level.WARN,
                tr(
                    "GC 偏多：{0} 秒里 {1} 次（共 {2} 毫秒），每秒分配 {3} MB；{4} 个慢帧（占 {5}%）发生时后台在 GC。后台 GC 不停主线程，但会和界面抢 CPU。",
                    seconds.toLong(), gcCount, gcTimeMs, fmt1(allocMbPerSec), causes.gcBackground,
                    fmt1(share(causes.gcBackground, causes.janky) * 100),
                ),
            )

            else -> PerfFinding(PerfFinding.Level.OK, tr("GC 正常：{0} 次，共 {1} 毫秒；每秒分配 {2} MB。", gcCount, gcTimeMs, fmt1(allocMbPerSec)))
        }
        if (!rec.gcLogAvailable) {
            findings += PerfFinding(PerfFinding.Level.INFO, tr("这台电视上读不到 GC 日志，没法逐帧对上 GC，只能看总次数。"))
        }

        // ---- 内存
        val lowTrims = rec.trims.filter { it.level >= TRIM_RUNNING_LOW }
        val faultShare = share(causes.pageFault, causes.janky)
        val memTight = (memAvailMinMb != null && totalMb > 0 && memAvailMinMb < maxOf(300L, totalMb / 10)) || lowTrims.isNotEmpty()
        findings += when {
            memTight -> PerfFinding(
                if (enoughFrames && causes.janky >= 5 && (faultShare >= CAUSE_SHARE || share(causes.lowMemory, causes.janky) >= CAUSE_SHARE)) PerfFinding.Level.BAD else PerfFinding.Level.WARN,
                tr(
                    "系统内存紧张：可用内存最低 {0} MB（共 {1} MB），系统发来 {2} 次内存不足通知；慢帧里 {3} 个发生时主线程在从存储读回被换出的内存页。",
                    memAvailMinMb ?: "?", totalMb, lowTrims.size, causes.pageFault,
                ),
            )

            enoughFrames && causes.janky >= 5 && faultShare >= CAUSE_SHARE -> PerfFinding(
                PerfFinding.Level.WARN,
                tr("慢帧里 {0} 个发生时主线程在从存储读内存页，但系统内存并不紧张：多半是第一次用到的代码或资源（刚安装、刚更新、还没编译时常见）。", causes.pageFault),
            )

            else -> PerfFinding(
                PerfFinding.Level.OK,
                tr("系统内存正常：可用内存最低 {0} MB（共 {1} MB）。", memAvailMinMb ?: "?", totalMb),
            )
        }
        if (javaMaxMb > 0 && javaUsedMaxMb * 100 / javaMaxMb >= 85) {
            findings += PerfFinding(PerfFinding.Level.WARN, tr("Java 堆接近上限（{0}/{1} MB），GC 会越来越频繁，再涨可能内存溢出。", javaUsedMaxMb, javaMaxMb))
        }

        // ---- 帧里各段
        if (enoughFrames) {
            val period = periodNs.toDouble()
            val uiP90 = frames.ui?.p90Ns ?: 0
            val renderP90 = (frames.sync?.p90Ns ?: 0) + (frames.cmd?.p90Ns ?: 0)
            if (rec.stalls.isNotEmpty() || rec.stallDurations.isNotEmpty()) {
                val top = rec.stalls.mapNotNull { it.appTop }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                val busyShare = share(causes.mainBusy, causes.janky)
                findings += PerfFinding(
                    if (busyShare >= CAUSE_SHARE && causes.janky >= 5) PerfFinding.Level.BAD else PerfFinding.Level.WARN,
                    tr(
                        "主线程卡住 {0} 次，最长 {1} 毫秒，合计 {2} 毫秒；卡住时最常停在 {3}。",
                        rec.stallDurations.size, rec.stallDurations.maxOrNull() ?: 0, rec.stallDurations.sum(), top ?: tr("系统代码"),
                    ),
                )
            }
            if (uiP90 > period) {
                findings += PerfFinding(PerfFinding.Level.WARN, tr("主线程每帧耗时偏高：p90 {0} 毫秒（输入、动画、布局、录制），一帧只有 {1} 毫秒。", ms(uiP90), ms(periodNs)))
            }
            if (renderP90 > period * 0.8 && uiP90 < period * 0.5) {
                findings += PerfFinding(PerfFinding.Level.WARN, tr("RenderThread 偏慢：同步 + 下发 p90 {0} 毫秒（把绘制命令交给 GPU）。", ms(renderP90)))
            }
            val gpu = frames.gpu
            if (gpu != null && gpu.p90Ns > period) {
                findings += PerfFinding(
                    if (gpu.p90Ns > period * 1.3) PerfFinding.Level.BAD else PerfFinding.Level.WARN,
                    tr("GPU 画不动：每帧 GPU p90 {0} 毫秒，超过一帧的 {1} 毫秒。界面按 {2} 渲染，降到 1080p 或把视觉效果调成「流畅」会轻很多。", ms(gpu.p90Ns), ms(periodNs), rec.windowSize),
                )
            } else if (gpu == null && (frames.swap?.p90Ns ?: 0) > period * 0.5) {
                findings += PerfFinding(
                    PerfFinding.Level.WARN,
                    tr("交换缓冲区耗时偏高（p90 {0} 毫秒），多半是 GPU 跟不上；这台电视的系统版本拿不到每帧的 GPU 耗时。界面按 {1} 渲染。", ms(frames.swap?.p90Ns ?: 0), rec.windowSize),
                )
            }
            if ((frames.delay?.p90Ns ?: 0) > period) {
                findings += PerfFinding(PerfFinding.Level.WARN, tr("帧开始前主线程常被别的任务占着：等主线程 p90 {0} 毫秒。", ms(frames.delay?.p90Ns ?: 0)))
            }
        }
        if (rec.cores > 0 && coresUsed > rec.cores * 0.85) {
            findings += PerfFinding(PerfFinding.Level.WARN, tr("CPU 接近吃满：本应用平均用了 {0} 核（共 {1} 核）。", fmt1(coresUsed), rec.cores))
        }
        // 某个后台线程组 (协程线程池、图片解码…) 平均占掉大半个核
        val busiest = rec.threadCpuMs.entries
            .filter { it.key !in FOREGROUND_THREADS && !it.key.startsWith("ani-perf") }
            .maxByOrNull { it.value }
        if (busiest != null && busiest.value > seconds * 1000 * 0.75) {
            findings += PerfFinding(
                PerfFinding.Level.WARN,
                tr("后台任务很重：{0} 线程平均占 {1} 核（共 {2} 核），会和界面抢 CPU。", busiest.key, fmt1(busiest.value / (seconds * 1000)), rec.cores),
            )
        }
        // 还没编译成机器码时 JIT 一直在编译, 主线程也跑得慢: 这时的数据比平时悲观
        val jitMs = rec.threadCpuMs[JIT_THREAD] ?: 0
        if (jitMs > seconds * 1000 * 0.1) {
            findings += PerfFinding(
                PerfFinding.Level.INFO,
                tr("JIT 编译用了 {0} 秒 CPU：应用还没编译成机器码（debug 包、刚安装或刚更新时是这样），这次的卡顿会比平时重。", fmt1(jitMs / 1000.0)),
            )
        }
        if (rec.thermalStatus >= 2) {
            findings += PerfFinding(PerfFinding.Level.WARN, tr("电视正在因为发热降频（温控等级 {0}）。", rec.thermalStatus))
        }
        if (findings.none { it.level == PerfFinding.Level.BAD || it.level == PerfFinding.Level.WARN }) {
            findings += PerfFinding(PerfFinding.Level.OK, tr("没有发现明显的瓶颈。"))
        }

        val json = buildJsonObject {
            putJsonArray("findings") {
                for (f in findings.sortedBy { it.level.ordinal }) addJsonObject {
                    put("level", f.level.name.lowercase(Locale.ROOT))
                    put("text", f.text)
                }
            }
            putJsonObject("frames") {
                put("count", frames.count)
                put("seconds", fmt1(frames.durationMs / 1000.0))
                put("fps", fmt1(frames.fps))
                put("refreshHz", fmt1(rec.refreshHz.toDouble()))
                put("periodMs", ms(periodNs))
                put("janky", frames.janky)
                put("jankyPercent", fmt1(frames.jankyPercent))
                put("missedVsync", frames.missedVsync)
                put("window", rec.windowSize)
                putJsonObject("stagesMs") {
                    stage("total", frames.total)
                    stage("ui", frames.ui)
                    stage("waitMain", frames.delay)
                    stage("sync", frames.sync)
                    stage("issue", frames.cmd)
                    stage("swap", frames.swap)
                    stage("gpu", frames.gpu)
                }
            }
            putJsonObject("jankCauses") {
                put("janky", causes.janky)
                put("gcMain", causes.gcMain)
                put("gcBackground", causes.gcBackground)
                put("pageFault", causes.pageFault)
                put("mainBusy", causes.mainBusy)
                put("lowMemory", causes.lowMemory)
            }
            putJsonObject("gc") {
                put("count", gcCount)
                put("timeMs", gcTimeMs)
                put("blockingCount", blockingCount)
                put("blockingMs", blockingMs)
                put("allocMbPerSec", fmt1(allocMbPerSec))
                put("mainThreadGcMs", mainGcMs.toLong())
                put("logAvailable", rec.gcLogAvailable)
                putJsonArray("events") {
                    for (e in rec.gcEvents.take(60)) addJsonObject {
                        put("atSec", fmt1((e.endNs - rec.startNs) / 1e9))
                        put("cause", e.cause)
                        put("durationMs", fmt1(e.durationMs))
                        put("pausedMs", fmt1(e.pausedMs))
                        put("mainThread", e.tid == rec.mainTid)
                        put("wait", e.wait)
                    }
                }
            }
            putJsonObject("memory") {
                put("totalMb", totalMb)
                put("availMinMb", memAvailMinMb)
                put("javaUsedMaxMb", javaUsedMaxMb)
                put("javaMaxMb", javaMaxMb)
                put("nativeMaxMb", rec.counters.maxOfOrNull { it.nativeKb }?.div(KB_PER_MB) ?: 0)
                put("pssMaxMb", rec.memory.maxOfOrNull { it.pssKb }?.div(KB_PER_MB))
                put("mainMajorFaults", mainFaults)
                put("processMajorFaults", processFaults)
                putJsonArray("trims") {
                    for (t in rec.trims) addJsonObject {
                        put("atSec", fmt1((t.tNs - rec.startNs) / 1e9))
                        put("level", t.level)
                    }
                }
                putJsonArray("timeline") {
                    for (m in rec.memory) addJsonObject {
                        put("atSec", fmt1((m.tNs - rec.startNs) / 1e9))
                        put("pssMb", m.pssKb / KB_PER_MB)
                        put("javaMb", m.javaKb / KB_PER_MB)
                        put("nativeMb", m.nativeKb / KB_PER_MB)
                        put("graphicsMb", m.graphicsKb / KB_PER_MB)
                        put("codeMb", m.codeKb / KB_PER_MB)
                        put("otherMb", m.otherKb / KB_PER_MB)
                    }
                }
            }
            putJsonObject("cpu") {
                put("cores", rec.cores)
                put("processMs", processCpuMs)
                put("coresUsed", fmt1(coresUsed))
                putJsonArray("threads") {
                    for ((name, cpu) in rec.threadCpuMs.entries.sortedByDescending { it.value }.take(10)) addJsonObject {
                        put("name", name)
                        put("ms", cpu)
                    }
                }
            }
            putJsonObject("mainThread") {
                put("stalls", rec.stallDurations.size)
                put("longestMs", rec.stallDurations.maxOrNull() ?: 0)
                put("totalMs", rec.stallDurations.sum())
                putJsonArray("stacks") {
                    val groups = rec.stalls.groupBy { s -> s.stack.take(6).joinToString("|") }.entries.sortedByDescending { it.value.size }
                    for (group in groups.take(3)) addJsonObject {
                        val sample = group.value.first()
                        put("samples", group.value.size)
                        put("longestMs", group.value.maxOf { it.blockedMs })
                        put("context", sample.context)
                        put("appTop", sample.appTop)
                        putJsonArray("stack") { sample.stack.take(24).forEach { add(it) } }
                    }
                }
            }
        }
        return PerfResult(frames, causes, findings, json)
    }

    private fun JsonObjectBuilder.stage(name: String, s: StageStat?) {
        if (s == null) return
        putJsonArray(name) {
            add(ms(s.p50Ns))
            add(ms(s.p90Ns))
            add(ms(s.maxNs))
        }
    }

    private fun delta(a: Long?, b: Long?): Long = if (a == null || b == null || a < 0 || b < 0) 0 else (b - a).coerceAtLeast(0)
    private fun share(part: Int, whole: Int): Double = if (whole <= 0) 0.0 else part.toDouble() / whole
    fun ms(ns: Long): String = fmt1(ns / 1e6)
    fun fmt1(d: Double): String = String.format(Locale.ROOT, "%.1f", d)
}
