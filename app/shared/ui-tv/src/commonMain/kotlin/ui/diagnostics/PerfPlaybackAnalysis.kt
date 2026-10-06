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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.ui.diagnostics.PerfAnalysis.fmt1
import me.him188.ani.app.ui.remote.tr
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 录制期间在播视频时的分析 (数据见 [PerfTimelineData]):
 * - 开播用了多久, 各步 (拿到查询会话、第一个数据源出结果、选中资源、首帧) 在第几秒;
 * - 视频画面每秒出了几帧、丢 / 跳了几帧, 哪几秒卡了 (丢帧、跳帧, 或出帧明显少于片源帧率; 缓冲中的另算;
 *   开始播放、缓冲完、拖动后的 [SETTLE_NS] 内不算: 那段里跳过关键帧到目标位置之间的帧、音画对齐都是正常的);
 * - 卡的那几秒当时: 数据源是不是还在查、本应用 CPU 是不是吃满 (哪个线程组最占)、有没有 GC、选源器筛选花了多久、缓冲余量还剩多少;
 * - 首帧后搜索还持续多久, 那段时间的 CPU;
 * - 片源帧率与屏幕刷新率不成整数倍 (如 24 fps 放在 60 Hz 上): 每帧轮流显示 2 次和 3 次, 一帧不丢也会一顿一顿.
 * - 常驻哨兵 ([TvPlaybackSentinel]) 每秒记的整机内存、交换区、Java 堆、GC 与主线程卡住, 卡的时候是不是整机内存紧;
 *   哨兵每秒拿最近几秒问 [detect] 画面是不是异常卡顿, 是就自动录一份 (报告里第一条写触发原因).
 *
 * 事件的名字与报事件的地方约定一致: 播放器 (video-player 的 PlaybackPerfProbe)、开播 (app-data 的 PlaybackPerfTraceExtension)、
 * 选源器 (MediaSelectorFilterSortAlgorithm 的计时)、哨兵.
 */
internal object PerfPlaybackAnalysis {
    /** 录制器每秒记的屏幕刷新率 (毫赫, 见 [TvPerfRecorder]). */
    const val DISPLAY = "显示"
    const val DISPLAY_MILLIHZ = "刷新率毫赫"

    /** 哨兵每秒记的系统读数 (读数取每秒最小值, GC 是增量). */
    const val SYSTEM = "系统"
    const val MEM_AVAIL_MB = "可用内存MB"
    const val SWAP_FREE_MB = "交换区剩余MB"
    const val JAVA_MB = "Java堆MB"
    const val GC_COUNT = "GC次数"
    const val GC_MS = "GC毫秒"

    /** 本应用的主缺页次数 (`/proc/self/stat`): 被换出的内存页又要从交换区 / 存储读回来, 多了 = 内存不够用 (应用读不到整机的换入统计). */
    const val MAJOR_FAULTS = "主缺页"

    /** 哨兵自己的事: 触发自动报告 (详情是原因)、主线程卡住 (详情以「N 毫秒」开头)、触发时的整机内存. */
    const val SENTINEL = "哨兵"
    const val TRIGGER = "触发"
    const val MAIN_STALL = "主线程卡住"
    const val MEMORY = "整机内存"

    /** 别处报的「这时在做的重活」(写主屏频道、建 WebView …), 卡的时候列出来. */
    const val BACKGROUND = "后台任务"

    private const val FRAMES = "画面"
    private const val PLAYBACK = "播放"
    private const val START = "开播"
    private const val SOURCE = "数据源"
    private const val SELECTOR = "选源器"
    private const val SEARCHING = "查询中"
    private val RENDERED = PerfTimelineData.key(FRAMES, "出帧")
    private val DROPPED = PerfTimelineData.key(FRAMES, "丢帧")
    private val SKIPPED = PerfTimelineData.key(FRAMES, "跳帧")
    private val OFFSET_US = PerfTimelineData.key(FRAMES, "提前量微秒")
    private val OFFSET_FRAMES = PerfTimelineData.key(FRAMES, "提前量帧数")
    private val BUFFERED_MS = PerfTimelineData.key(FRAMES, "缓冲余量毫秒")
    private val SAMPLES = PerfTimelineData.key(FRAMES, "采样")
    private val DISPLAY_HZ = PerfTimelineData.key(DISPLAY, DISPLAY_MILLIHZ)
    private val MEM_AVAIL = PerfTimelineData.key(SYSTEM, MEM_AVAIL_MB)
    private val SWAP_FREE = PerfTimelineData.key(SYSTEM, SWAP_FREE_MB)
    private val MAJOR_FAULT = PerfTimelineData.key(SYSTEM, MAJOR_FAULTS)
    private val JAVA = PerfTimelineData.key(SYSTEM, JAVA_MB)
    private val SENTINEL_GC_COUNT = PerfTimelineData.key(SYSTEM, GC_COUNT)
    private val SENTINEL_GC_MS = PerfTimelineData.key(SYSTEM, GC_MS)
    private val LEADING_MS = Regex("""^(\d+) 毫秒""")
    private val FPS = Regex("""([\d.]+) fps""")

    /** 出帧少于片源帧率的这个比例算卡 (半秒一采, 秒的边界上会差一两帧). */
    private const val LOW_FRAME_RATIO = 0.85
    private const val CPU_BUSY_RATIO = 0.75
    private const val SELECTOR_HEAVY_MS = 100.0
    private const val BUFFER_LOW_MS = 1000L
    private const val NS_PER_SECOND = 1_000_000_000L
    private const val SETTLE_NS = 1_000_000_000L

    /** 刷新率 / 帧率离整数差多少以内算整数倍 (23.976 fps 放在 24 Hz 上只是隔几十秒多显示一次). */
    private const val CADENCE_TOLERANCE = 0.05

    /**
     * 整机内存紧: 可用内存低于 [MEM_TIGHT_MB], 或本应用一秒主缺页 [MAJOR_FAULTS_TIGHT] 次以上 (内存页被换出又读回;
     * 刚启动、第一次用到的代码也会缺页, 每秒一两百次是常事). 交换区满了不算: zram 交换区平时就是满的 (如 Shield).
     */
    private const val MEM_TIGHT_MB = 400L
    private const val MAJOR_FAULTS_TIGHT = 300L

    /** 常见的片源帧率: 由出帧数估出来的帧率离它们不到 [FPS_SNAP_RATIO] 就取它. */
    private val COMMON_FPS = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 48.0, 50.0, 59.94, 60.0)
    private const val FPS_SNAP_RATIO = 0.04

    /** [detect]: 看最近这么多个整秒; 出帧少于片源帧率的这个比例、丢帧 + 跳帧到这么多算异常; 缓冲余量还有这么多画面却一秒没动算停住. */
    private const val DETECT_SECONDS = 5
    private const val DETECT_LOW_FRAME_RATIO = 0.7
    private const val DETECT_LOST_FRAMES = 10L
    private const val FREEZE_BUFFER_MS = 3000L

    /** 卡的那一秒主线程卡住这么久以上才算; 往前看这么久里别处报的重活. */
    private const val MAIN_STALL_HEAVY_MS = 200L
    private const val BACKGROUND_LOOKBACK_NS = 3_000_000_000L

    /** [lead]: 自动报告的触发原因, 排在全部结论的最前面. */
    class Result(val findings: List<PerfFinding>, val json: JsonObject?, val hasPlayback: Boolean, val lead: PerfFinding? = null)

    /** 一秒的情况. */
    class Second(
        val index: Int,
        val rendered: Long,
        val dropped: Long,
        val skipped: Long,
        /** 平均出帧提前量 (毫秒, 负数 = 晚了); 这一秒没出帧时为 null. */
        val offsetMs: Double?,
        val bufferedMs: Long?,
        val cores: Double,
        val topThreads: List<Pair<String, Long>>,
        val selectorMs: Double,
        val gcCount: Int,
        val gcMs: Double,
        val searching: Int,
        val rxMbps: Double?,
        val buffering: Boolean,
        val playing: Boolean,
        /** 这一秒里开始播放 / 缓冲完 / 拖动过, 或这一秒开头离上一次不到 [SETTLE_NS]. */
        val settling: Boolean,
        /** 屏幕刷新率; 这一秒没记到时为 null. */
        val displayHz: Double?,
        /** 解码计数这一秒采了几次 (采样线程被拖住时会少, 少了不判停住). */
        val samples: Long,
        val speed: Double,
        /** 哨兵记的整机读数 (MB), 没记到时为 null. */
        val memAvailMb: Long?,
        val swapFreeMb: Long?,
        /** 这一秒本应用的主缺页次数; 没记到时为 null. */
        val majorFaults: Long?,
        val javaMb: Long?,
        /** 这一秒里主线程卡住的毫秒数. */
        val mainStallMs: Long,
    ) {
        val lostFrames: Long get() = dropped + skipped

        /** 算流畅度的秒: 在播, 没在缓冲, 也不在刚开始 / 刚拖动后的那一下. */
        val counted: Boolean get() = playing && !buffering && !settling

        val memTight: Boolean
            get() = (memAvailMb != null && memAvailMb < MEM_TIGHT_MB) || (majorFaults != null && majorFaults >= MAJOR_FAULTS_TIGHT)
    }

    fun analyze(rec: PerfRecording): Result {
        val tl = rec.timeline
        // 时间线的秒从它自己的开头数 (自动报告的时间线比录制早开始一段)
        val base = if (tl === PerfTimelineData.EMPTY) rec.startNs else tl.startNs
        val events = tl.events.sortedBy { it.tNs }
        val renderedTotal = tl.seconds.sumOf { it.count(RENDERED) }
        val hasPlayback = renderedTotal > 0 || events.any { it.category == PLAYBACK && it.tNs >= base }
        if (!hasPlayback && events.none { it.category == START && it.tNs >= base }) return Result(emptyList(), null, false)

        // 只要完整的秒: 录制结束那一秒没录满, 出帧数会偏少
        val lastIndex = ((rec.endNs - base) / NS_PER_SECOND).toInt() - 1
        val seconds = if (lastIndex >= 0) buildSeconds(tl, events, rec.gcEvents, base, lastIndex) else emptyList()
        val fps = videoFps(events, seconds)
        val decoder = events.lastOrNull { it.category == PLAYBACK && it.name == "视频解码器" }?.detail
        val format = events.lastOrNull { it.category == PLAYBACK && it.name == "视频格式" }?.detail
        val milestones = milestones(events.filter { it.tNs >= base })
        val stutter = stutterSeconds(seconds, fps)
        val trigger = events.lastOrNull { it.category == SENTINEL && it.name == TRIGGER && it.tNs >= base }
        val displayHz = seconds.filter { it.counted }.mapNotNull { it.displayHz }.sorted()
            .let { if (it.isEmpty()) null else it[it.size / 2] }
            ?: rec.refreshHz.toDouble().takeIf { it > 1 }
        val findings = ArrayList<PerfFinding>()

        startFinding(milestones)?.let { findings += it }
        findings += frameFindings(rec, base, seconds, stutter, fps, decoder, events)
        cadenceFinding(fps, displayHz, seconds)?.let { findings += it }
        bufferingFinding(events, base)?.let { findings += it }
        val underruns = events.count { it.category == PLAYBACK && it.name == "音频断流" && it.tNs >= base }
        if (underruns > 0) {
            findings += PerfFinding(PerfFinding.Level.WARN, tr("声音断流 {0} 次：音频没及时送进去，多半是 CPU 太忙或解码跟不上。", underruns))
        }
        searchWindowFinding(rec, base, seconds, milestones)?.let { findings += it }
        memoryFinding(seconds, events, base)?.let { findings += it }
        val lead = trigger?.let {
            PerfFinding(PerfFinding.Level.BAD, tr("画面异常卡顿，自动录下了这份报告：{0}（第 {1} 秒）。", it.detail, ((it.tNs - base) / NS_PER_SECOND).toInt()))
        }

        val json = buildJsonObject {
            put("fps", fps?.let { fmt1(it) })
            put("displayHz", displayHz?.let { fmt1(it) })
            put("decoder", decoder)
            put("format", format)
            put("rendered", renderedTotal)
            put("dropped", seconds.filter { it.counted }.sumOf { it.dropped })
            put("skipped", seconds.filter { it.counted }.sumOf { it.skipped })
            put("prerollSkipped", seconds.filter { !it.counted }.sumOf { it.skipped })
            put("audioUnderruns", underruns)
            trigger?.let {
                putJsonObject("trigger") {
                    put("atSec", fmt1((it.tNs - base) / 1e9))
                    put("reason", it.detail)
                }
            }
            putJsonArray("stutterSeconds") { stutter.forEach { add(it.index) } }
            putJsonArray("start") {
                for (m in milestones.list) addJsonObject {
                    put("atSec", fmt1((m.tNs - base) / 1e9))
                    put("sinceStartSec", milestones.startNs?.let { fmt1((m.tNs - it) / 1e9) })
                    put("what", m.what)
                }
            }
            putJsonArray("seconds") {
                for (s in seconds) {
                    if (!s.playing && s.searching == 0 && s.rendered == 0L && s.selectorMs < 1) continue
                    addJsonObject {
                        put("sec", s.index)
                        put("rendered", s.rendered)
                        put("dropped", s.dropped)
                        put("skipped", s.skipped)
                        put("offsetMs", s.offsetMs?.let { fmt1(it) })
                        put("bufferedMs", s.bufferedMs)
                        put("cores", fmt1(s.cores))
                        put("top", s.topThreads.joinToString(", ") { "${it.first} ${it.second}ms" })
                        put("selectorMs", fmt1(s.selectorMs))
                        put("gc", s.gcCount)
                        put("gcMs", fmt1(s.gcMs))
                        put("searching", s.searching)
                        put("rxMbps", s.rxMbps?.let { fmt1(it) })
                        put("memAvailMb", s.memAvailMb)
                        put("swapFreeMb", s.swapFreeMb)
                        put("majorFaults", s.majorFaults)
                        put("javaMb", s.javaMb)
                        put("mainStallMs", s.mainStallMs)
                        put("state", if (s.buffering) "buffering" else if (s.playing) "playing" else "idle")
                        put("stutter", stutter.any { it.index == s.index })
                    }
                }
            }
            putJsonArray("events") {
                for (e in events.take(500)) addJsonObject {
                    put("atSec", fmt1((e.tNs - base) / 1e9))
                    put("category", e.category)
                    put("name", e.name)
                    put("detail", e.detail)
                }
            }
        }
        return Result(findings, json, hasPlayback, lead)
    }

    /**
     * 常驻哨兵每秒调一次 ([tl] 是最近十来秒, [nowNs] 是现在): 最近 [DETECT_SECONDS] 个整秒里画面是不是异常卡顿, 是就返回原因, 否则 null.
     * 只看在播、没在缓冲、不在刚开始 / 拖动 / 缓冲完那一下的秒 ([Second.counted]); 余量见底的卡住是网络, 不在这里判.
     * 倍速播放时不判 (出帧数跟着倍速变).
     */
    fun detect(tl: PerfTimelineData, nowNs: Long): String? {
        val events = tl.events.sortedBy { it.tNs }
        val current = ((nowNs - tl.startNs) / NS_PER_SECOND).toInt()
        if (current < DETECT_SECONDS) return null
        val seconds = buildSeconds(tl, events, emptyList(), tl.startNs, current - 1)
        val window = seconds.takeLast(DETECT_SECONDS)
        if (window.any { abs(it.speed - 1.0) > 0.01 }) return null
        window.firstOrNull { it.counted && it.samples >= 1 && it.rendered == 0L && (it.bufferedMs ?: 0L) >= FREEZE_BUFFER_MS }?.let {
            return tr("缓冲还有 {0} 秒，画面却停了 1 秒以上", fmt1((it.bufferedMs ?: 0L) / 1000.0))
        }
        if (!window.all { it.counted } || window.sumOf { it.samples } < DETECT_SECONDS) return null
        val dropped = window.sumOf { it.dropped }
        val skipped = window.sumOf { it.skipped }
        if (dropped + skipped >= DETECT_LOST_FRAMES) return tr("{0} 秒里丢帧 {1}、跳帧 {2}", DETECT_SECONDS, dropped, skipped)
        val fps = videoFps(events, seconds) ?: return null
        val rendered = window.sumOf { it.rendered }
        if (rendered < fps * DETECT_SECONDS * DETECT_LOW_FRAME_RATIO) {
            return tr("{0} 秒只出了 {1} 帧（片源 {2} fps）", DETECT_SECONDS, rendered, fmt1(fps))
        }
        return null
    }

    // ------------------------------------------------------------------ 逐秒

    private fun buildSeconds(
        tl: PerfTimelineData,
        events: List<PerfTimelineEvent>,
        gcEvents: List<PerfGcEvent>,
        base: Long,
        lastIndex: Int,
    ): List<Second> {
        val byIndex = tl.seconds.associateBy { it.index }
        val stalls = events.filter { it.category == SENTINEL && it.name == MAIN_STALL }.mapNotNull { e ->
            LEADING_MS.find(e.detail)?.groupValues?.get(1)?.toLongOrNull()?.let { ms -> (e.tNs - ms * 1_000_000) to e.tNs }
        }
        val sourceState = HashMap<String, String>()
        var buffering = false
        var playing = false
        var speed = 1.0
        var lastTransitionNs = Long.MIN_VALUE
        var ei = 0
        val result = ArrayList<Second>(lastIndex + 1)
        for (index in 0..lastIndex) {
            val endNs = base + (index + 1) * NS_PER_SECOND
            var searchingMax = sourceState.values.count { it == SEARCHING }
            var bufferingSeen = buffering
            var playingSeen = playing
            while (ei < events.size && events[ei].tNs < endNs) {
                val e = events[ei++]
                when (e.category) {
                    SOURCE -> {
                        sourceState[e.name] = e.detail
                        searchingMax = maxOf(searchingMax, sourceState.values.count { it == SEARCHING })
                    }

                    PLAYBACK -> when (e.name) {
                        "缓冲" -> {
                            buffering = true
                            bufferingSeen = true
                        }

                        "就绪" -> {
                            buffering = false
                            lastTransitionNs = e.tNs
                        }

                        "空闲", "播完" -> buffering = false
                        "开始播放" -> {
                            playing = true
                            playingSeen = true
                            lastTransitionNs = e.tNs
                        }

                        "拖动", "首帧" -> lastTransitionNs = e.tNs

                        "停止播放" -> playing = false
                        "倍速" -> speed = e.detail.toDoubleOrNull() ?: speed
                    }
                }
            }
            val s = byIndex[index]
            val cpu = s?.threadCpuMs.orEmpty().filterKeys { !it.startsWith("ani-perf") }
            val offsetFrames = s?.count(OFFSET_FRAMES) ?: 0L
            val gcs = gcEvents.filter { it.endNs >= endNs - NS_PER_SECOND && it.endNs < endNs }
            val stallMs = stalls.sumOf { (from, to) -> (minOf(to, endNs) - maxOf(from, endNs - NS_PER_SECOND)).coerceAtLeast(0) } / 1_000_000
            result += Second(
                index = index,
                rendered = s?.count(RENDERED) ?: 0,
                dropped = s?.count(DROPPED) ?: 0,
                skipped = s?.count(SKIPPED) ?: 0,
                offsetMs = if (offsetFrames > 0) (s?.count(OFFSET_US) ?: 0) / offsetFrames / 1000.0 else null,
                bufferedMs = s?.gauges?.get(BUFFERED_MS),
                cores = cpu.values.sum() / 1000.0,
                topThreads = cpu.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value.roundToLong() },
                selectorMs = s?.times?.filterKeys { it.startsWith("$SELECTOR/") }?.values?.sumOf { it.sum }?.div(1e6) ?: 0.0,
                gcCount = maxOf(gcs.count { !it.wait }, (s?.count(SENTINEL_GC_COUNT) ?: 0).toInt()),
                gcMs = maxOf(gcs.filter { !it.wait }.sumOf { it.durationMs }, (s?.count(SENTINEL_GC_MS) ?: 0).toDouble()),
                searching = searchingMax,
                rxMbps = s?.rxBytes?.takeIf { it >= 0 }?.let { it * 8 / 1e6 },
                buffering = bufferingSeen,
                playing = playingSeen || (s?.count(RENDERED) ?: 0) > 0,
                settling = lastTransitionNs >= endNs - NS_PER_SECOND - SETTLE_NS,
                displayHz = s?.gauges?.get(DISPLAY_HZ)?.let { it / 1000.0 },
                samples = s?.count(SAMPLES) ?: 0,
                speed = speed,
                memAvailMb = s?.gauges?.get(MEM_AVAIL),
                swapFreeMb = s?.gauges?.get(SWAP_FREE),
                majorFaults = s?.counts?.get(MAJOR_FAULT),
                javaMb = s?.gauges?.get(JAVA),
                mainStallMs = stallMs,
            )
        }
        return result
    }

    /**
     * 片源帧率: 视频格式里写了就用 (mkv 常常没写), 否则取在播那几秒出帧数 (倍速折回 1 倍) 的第 80 百分位 (少数几秒卡了不影响),
     * 离常见帧率不远就取常见的.
     */
    private fun videoFps(events: List<PerfTimelineEvent>, seconds: List<Second>): Double? {
        events.lastOrNull { it.category == PLAYBACK && it.name == "视频格式" }
            ?.let { FPS.find(it.detail)?.groupValues?.get(1)?.toDoubleOrNull() }
            ?.takeIf { it > 1 }
            ?.let { return it }
        val playing = seconds.filter { it.counted && it.rendered > 0 && it.lostFrames == 0L }
        if (playing.size < 3) return null
        val sorted = playing.map { it.rendered / it.speed }.sorted()
        val estimate = sorted[(sorted.size * 4 / 5).coerceAtMost(sorted.size - 1)]
        return COMMON_FPS.minBy { abs(it - estimate) }.takeIf { abs(it - estimate) <= it * FPS_SNAP_RATIO } ?: estimate
    }

    /** 卡了的秒: 算流畅度的秒里 (见 [Second.counted]) 有丢帧 / 跳帧或出帧明显少于帧率 (按倍速算). */
    private fun stutterSeconds(seconds: List<Second>, fps: Double?): List<Second> =
        seconds.filter { s ->
            s.counted && (s.lostFrames > 0 || (fps != null && s.rendered > 0 && s.rendered < fps * s.speed * LOW_FRAME_RATIO))
        }

    // ------------------------------------------------------------------ 开播

    class Milestone(val tNs: Long, val what: String)

    class Milestones(val startNs: Long?, val list: List<Milestone>) {
        fun at(what: String): Long? = list.firstOrNull { it.what == what }?.tNs
    }

    /** [events] 只含时间线开头以后的 (开头以前的是接状态用的, 不算这一段里发生的). */
    private fun milestones(events: List<PerfTimelineEvent>): Milestones {
        val list = ArrayList<Milestone>()
        fun first(what: String, predicate: (PerfTimelineEvent) -> Boolean) {
            events.firstOrNull(predicate)?.let { list += Milestone(it.tNs, what) }
        }
        first("进入本集") { it.category == START && it.name == "本集" }
        first("查询会话") { it.category == START && it.name == "查询会话" }
        first("第一个数据源出结果") { it.category == SOURCE && it.detail == "完成" }
        first("选中资源") { it.category == START && it.name == "选中" }
        first("解析完成") { it.category == START && it.name == "加载" && it.detail == "成功" }
        first("首帧") { it.category == PLAYBACK && it.name == "首帧" }
        first("开始播放") { it.category == PLAYBACK && it.name == "开始播放" }
        // 搜索结束: 最后一次「在查的源从有到无」
        val state = HashMap<String, String>()
        var endNs: Long? = null
        for (e in events) {
            if (e.category != SOURCE) continue
            val before = state.values.count { it == SEARCHING }
            state[e.name] = e.detail
            if (before > 0 && state.values.none { it == SEARCHING }) endNs = e.tNs
        }
        endNs?.let { list += Milestone(it, "搜索结束") }
        // 这一段开头时已经在播的, 没有「进入本集」, 不算开播
        return Milestones(list.firstOrNull { it.what == "进入本集" }?.tNs, list.sortedBy { it.tNs })
    }

    private fun startFinding(m: Milestones): PerfFinding? {
        val start = m.startNs ?: return null
        val firstFrame = m.at("首帧") ?: return null
        fun since(what: String) = m.at(what)?.let { fmt1((it - start) / 1e9) } ?: "—"
        val total = (firstFrame - start) / 1e9
        return PerfFinding(
            if (total > 10) PerfFinding.Level.WARN else PerfFinding.Level.INFO,
            tr(
                "开播用了 {0} 秒（从进入本集到首帧）：查询会话 {1} 秒 · 第一个数据源出结果 {2} 秒 · 选中资源 {3} 秒 · 解析完成 {4} 秒 · 首帧 {5} 秒。",
                fmt1(total), since("查询会话"), since("第一个数据源出结果"), since("选中资源"), since("解析完成"), since("首帧"),
            ),
        )
    }

    // ------------------------------------------------------------------ 画面

    private fun frameFindings(
        rec: PerfRecording,
        base: Long,
        seconds: List<Second>,
        stutter: List<Second>,
        fps: Double?,
        decoder: String?,
        events: List<PerfTimelineEvent>,
    ): List<PerfFinding> {
        val findings = ArrayList<PerfFinding>()
        val playing = seconds.filter { it.counted && it.rendered > 0 }
        if (playing.isEmpty()) {
            findings += PerfFinding(PerfFinding.Level.INFO, tr("录制期间视频没有出画面，没有画面流畅度的数据。要查播放卡顿，开始录制后在电视上播一集。"))
            return findings
        }
        val rendered = seconds.sumOf { it.rendered }
        val dropped = playing.sumOf { it.dropped }
        val skipped = playing.sumOf { it.skipped }
        val fpsText = fps?.let { fmt1(it) } ?: "?"
        val software = decoder != null && SOFTWARE_DECODERS.any { decoder.contains(it, ignoreCase = true) }
        decoder?.let {
            findings += PerfFinding(PerfFinding.Level.INFO, tr("视频解码器：{0}（{1}）。", it, if (software) tr("软解，吃 CPU") else tr("硬解")))
        }
        if (stutter.isEmpty() && dropped + skipped == 0L) {
            findings += PerfFinding(
                PerfFinding.Level.OK,
                tr("视频画面流畅：播放了 {0} 秒，出帧 {1}（片源 {2} fps），没有丢帧。", playing.size, rendered, fpsText),
            )
            return findings
        }
        val level = if (stutter.size >= 3 || (dropped + skipped) * 100 > rendered) PerfFinding.Level.BAD else PerfFinding.Level.WARN
        findings += PerfFinding(
            level,
            tr(
                "视频画面卡了 {0} 秒：丢帧 {1}、跳帧 {2}（共出帧 {3}，片源 {4} fps），卡在第 {5} 秒。",
                stutter.size, dropped, skipped, rendered, fpsText, secondsText(stutter),
            ),
        )
        if (stutter.isEmpty()) return findings

        val n = stutter.size
        val searching = stutter.count { it.searching > 0 }
        val cpuBusy = stutter.count { rec.cores > 0 && it.cores >= rec.cores * CPU_BUSY_RATIO }
        val gc = stutter.count { it.gcCount > 0 }
        val bufferLow = stutter.count { it.bufferedMs != null && it.bufferedMs < BUFFER_LOW_MS }
        val selector = stutter.count { it.selectorMs >= SELECTOR_HEAVY_MS }
        val memTight = stutter.count { it.memTight }
        val mainStall = stutter.count { it.mainStallMs >= MAIN_STALL_HEAVY_MS }
        val topThreads = stutter.flatMap { it.topThreads }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }.entries.sortedByDescending { it.value }.take(3)
            .joinToString("、") { "${it.key} ${fmt1(it.value / 1000.0 / n)}" }
        findings += PerfFinding(
            level,
            tr(
                "卡的那 {0} 秒里：{1} 秒数据源还在查询，{2} 秒本应用 CPU 接近吃满，{3} 秒在做 GC，{4} 秒缓冲余量不到 1 秒，{5} 秒选源器筛选超过 100 毫秒；平均每秒最占 CPU 的是 {6}（核）。",
                n, searching, cpuBusy, gc, bufferLow, selector, topThreads.ifEmpty { "—" },
            ),
        )
        if (memTight > 0 || mainStall > 0) {
            findings += PerfFinding(
                level,
                tr(
                    "卡的那几秒里：{0} 秒整机内存紧（可用内存最低 {1} MB，本应用一秒最多缺页 {2} 次），{3} 秒主线程卡住超过 200 毫秒。",
                    memTight, stutter.mapNotNull { it.memAvailMb }.minOrNull() ?: "—",
                    stutter.mapNotNull { it.majorFaults }.maxOrNull() ?: "—", mainStall,
                ),
            )
        }
        // 卡的前后别处报的重活 (写主屏频道、建 WebView …)
        val fromNs = base + stutter.first().index * NS_PER_SECOND - BACKGROUND_LOOKBACK_NS
        val toNs = base + (stutter.last().index + 1) * NS_PER_SECOND
        val background = events.filter { it.category == BACKGROUND && it.tNs in fromNs..toNs }
        if (background.isNotEmpty()) {
            val text = background.groupBy { it.name }.entries.joinToString("、") { (name, list) -> if (list.size > 1) "$name ×${list.size}" else name }
            findings += PerfFinding(PerfFinding.Level.INFO, tr("卡的前后还在做：{0}。", text))
        }
        val half = n / 2.0
        findings += PerfFinding(
            level,
            when {
                searching >= half && (cpuBusy >= half || selector >= n * 0.3) ->
                    tr("多半是开播时还在搜索的数据源和选源器抢了 CPU：画面卡的时候搜索还没结束，CPU 也接近吃满。")

                bufferLow >= half -> tr("多半是数据没喂上：卡的时候缓冲快见底了，看网络和片源码率。")
                memTight >= half -> tr("多半是整机内存太紧：系统忙着在交换区换进换出、杀后台，应用的线程跟着被拖慢。")
                mainStall >= half -> tr("卡的时候主线程卡住了：看报告里主线程卡住时停在哪。")
                gc >= half -> tr("卡的时候常在 GC：内存分配太多，GC 抢了 CPU。")
                cpuBusy >= half -> tr("卡的时候本应用 CPU 接近吃满，但不全是搜索：看最占 CPU 的线程组。")
                software -> tr("卡的时候 CPU、GC、缓冲都不紧张，用的又是软解：多半是软解本身跟不上这个片源。")
                else -> tr("卡的时候 CPU、GC、缓冲都不紧张：可能是解码器或系统合成跟不上，看解码器和片源格式。")
            },
        )
        return findings
    }

    /**
     * 片源帧率与屏幕刷新率不成整数倍时, 每帧在屏幕上停留的刷新次数不一样 (24 fps 放在 60 Hz 上是 2、3、2、3…),
     * 匀速平移的镜头看着一顿一顿; 解码器一帧不丢, 丢帧计数里看不出来.
     */
    private fun cadenceFinding(fps: Double?, displayHz: Double?, seconds: List<Second>): PerfFinding? {
        if (fps == null || fps < 1 || displayHz == null || seconds.none { it.counted && it.rendered > 0 }) return null
        val ratio = displayHz / fps
        val low = floor(ratio).toInt()
        if (low < 1 || abs(ratio - ratio.roundToInt()) < CADENCE_TOLERANCE) return null
        return PerfFinding(
            PerfFinding.Level.WARN,
            tr(
                "片源 {0} fps、屏幕 {1} Hz，不是整数倍：有的帧显示 {2} 次、有的显示 {3} 次，平移镜头会一顿一顿（一帧没丢也会这样）。把设置里的「播放时屏幕刷新率」选成 {4} Hz 就没有了。",
                fmt1(fps), fmt1(displayHz), low, low + 1, fps.roundToInt(),
            ),
        )
    }

    private fun bufferingFinding(events: List<PerfTimelineEvent>, base: Long): PerfFinding? {
        val firstReady = events.firstOrNull { it.category == PLAYBACK && it.name == "就绪" }?.tNs ?: return null
        val stalls = events.filter { it.category == PLAYBACK && it.name == "缓冲" && it.tNs > firstReady && it.tNs >= base }
        if (stalls.isEmpty()) return null
        var totalNs = 0L
        for (stall in stalls) {
            val resumed = events.firstOrNull { it.category == PLAYBACK && it.name == "就绪" && it.tNs > stall.tNs } ?: continue
            totalNs += resumed.tNs - stall.tNs
        }
        return PerfFinding(
            PerfFinding.Level.WARN,
            tr("播放中缓冲 {0} 次，共 {1} 秒：数据没喂上，看网络和片源码率（app.log 里搜 [Rebuffer] 有每次的详情）。", stalls.size, fmt1(totalNs / 1e9)),
        )
    }

    /** 首帧后搜索还在继续的那段: 这段时间与之后的 CPU 对比. */
    private fun searchWindowFinding(rec: PerfRecording, base: Long, seconds: List<Second>, m: Milestones): PerfFinding? {
        val firstFrame = m.at("首帧") ?: return null
        val searchEnd = m.at("搜索结束") ?: return null
        if (searchEnd <= firstFrame) return null
        val from = ((firstFrame - base) / NS_PER_SECOND).toInt()
        val to = ((searchEnd - base) / NS_PER_SECOND).toInt()
        val during = seconds.filter { it.index in from..to }
        val after = seconds.filter { it.index > to + 1 && it.playing }
        if (during.isEmpty()) return null
        val duringCores = during.map { it.cores }.average()
        val afterText = if (after.size >= 3) fmt1(after.map { it.cores }.average()) else "—"
        val selectorPerSec = during.sumOf { it.selectorMs } / during.size
        return PerfFinding(
            if (rec.cores > 0 && duringCores >= rec.cores * 0.6) PerfFinding.Level.WARN else PerfFinding.Level.INFO,
            tr(
                "首帧后搜索又持续了 {0} 秒：这段时间本应用平均 {1} 核（搜索结束后 {2} 核，共 {3} 核），选源器筛选每秒 {4} 毫秒。",
                fmt1((searchEnd - firstFrame) / 1e9), fmt1(duringCores), afterText, rec.cores, fmt1(selectorPerSec),
            ),
        )
    }

    /** 播放时整机内存紧 (哨兵每秒记的), 以及自动报告触发那一刻的整机内存. */
    private fun memoryFinding(seconds: List<Second>, events: List<PerfTimelineEvent>, base: Long): PerfFinding? {
        val atTrigger = events.lastOrNull { it.category == SENTINEL && it.name == MEMORY && it.tNs >= base }?.detail
        val tight = seconds.filter { it.playing && it.memTight }
        if (tight.isEmpty()) return atTrigger?.let { PerfFinding(PerfFinding.Level.INFO, tr("触发时整机内存：{0}。", it)) }
        return PerfFinding(
            PerfFinding.Level.WARN,
            tr(
                "播放时有 {0} 秒整机内存紧：可用内存最低 {1} MB，本应用一秒最多缺页 {2} 次（被换出的内存页又要读回来）。内存紧时系统忙着换进换出、杀后台，播放和界面都会被拖慢。",
                tight.size, tight.mapNotNull { it.memAvailMb }.minOrNull() ?: "—", tight.mapNotNull { it.majorFaults }.maxOrNull() ?: "—",
            ) + (atTrigger?.let { " " + tr("触发时整机内存：{0}。", it) } ?: ""),
        )
    }

    private fun secondsText(list: List<Second>): String {
        val shown = list.take(8).joinToString("、") { it.index.toString() }
        return if (list.size > 8) "$shown…" else shown
    }

    /** 软解的解码器名 (MediaCodec 的软件实现与 FFmpeg 扩展). */
    private val SOFTWARE_DECODERS = listOf("c2.android.", "OMX.google.", "ffmpeg")
}
