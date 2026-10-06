/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerfAnalysisTest {
    private val period = 16_666_667L
    private val ms = 1_000_000L
    private val mainTid = 1234

    private fun frame(i: Int, totalMs: Long = 10, vsyncNs: Long = i * period) =
        PerfFrame(
            vsyncNs = vsyncNs, totalNs = totalMs * ms, delayNs = 0, inputNs = 0, animNs = ms, layoutNs = ms, drawNs = 2 * ms,
            syncNs = ms, cmdNs = 2 * ms, swapNs = ms,
        )

    private fun counters(tNs: Long, mainMajflt: Long = 0, memAvailKb: Long = 2_000_000, gcCount: Long = 0) =
        PerfCounters(
            tNs = tNs, gcCount = gcCount, gcTimeMs = gcCount * 20, blockingGcCount = 0, blockingGcTimeMs = 0,
            bytesAllocated = 0, mainMajflt = mainMajflt, processMajflt = mainMajflt, javaUsedKb = 50_000, javaMaxKb = 512_000,
            nativeKb = 100_000, memAvailKb = memAvailKb,
        )

    private fun recording(
        frames: List<PerfFrame>,
        gcEvents: List<PerfGcEvent> = emptyList(),
        counters: List<PerfCounters> = listOf(counters(0), counters(frames.lastOrNull()?.endNs ?: 0)),
        trims: List<PerfTrim> = emptyList(),
    ) = PerfRecording(
        startNs = 0, endNs = frames.lastOrNull()?.endNs ?: 1_000 * ms, refreshHz = 60f, cores = 4, mainTid = mainTid,
        frames = frames, gcEvents = gcEvents, gcLogAvailable = true, counters = counters, memory = emptyList(),
        trims = trims, stalls = emptyList(), stallDurations = emptyList(), threadCpuMs = mapOf("main" to 500L),
        totalMemKb = 3_000_000, apiLevel = 30, thermalStatus = 0, windowSize = "1920x1080",
    )

    @Test
    fun `parses a concurrent copying GC line`() {
        val e = assertNotNull(
            PerfGcLog.parse(
                "   156.234  1234  1240 I zygote64: Background concurrent copying GC freed 123(4MB) AllocSpace objects, " +
                    "0(0B) LOS objects, 49% free, 6MB/12MB, paused 120us,40us total 120.506ms",
            ),
        )
        assertEquals(156_234_000_000L, e.endNs)
        assertEquals(1240, e.tid)
        assertEquals("Background concurrent copying", e.cause)
        assertEquals(120.506, e.durationMs, 0.001)
        assertEquals(0.16, e.pausedMs, 0.001)
        assertEquals(false, e.wait)
    }

    @Test
    fun `parses a thread waiting for GC`() {
        val e = assertNotNull(PerfGcLog.parse("    10.500  1234  1234 I io.github.x.tv: WaitForGcToComplete blocked Alloc on Background for 12.345ms"))
        assertEquals(true, e.wait)
        assertEquals(1234, e.tid)
        assertEquals("Alloc on Background", e.cause)
        assertEquals(12.345, e.durationMs, 0.001)
    }

    @Test
    fun `ignores lines that are not GC logs`() {
        assertNull(PerfGcLog.parse("    10.500  1234  1234 I HttpClientProvider: GET https://example.com 200 in 12ms"))
        assertNull(PerfGcLog.parse("--------- beginning of main"))
    }

    @Test
    fun `counts slow frames and missed vsyncs`() {
        val frames = (0 until 60).map { i ->
            // 第 31 帧起整体晚一个周期 = 掉了一个 vsync
            val vsync = if (i > 30) (i + 1) * period else i * period
            frame(i, totalMs = if (i == 10 || i == 20) 40 else 10, vsyncNs = vsync)
        }
        val s = PerfAnalysis.summarizeFrames(frames, period)
        assertEquals(60, s.count)
        assertEquals(2, s.janky)
        assertEquals(1, s.missedVsync)
    }

    @Test
    fun `average fps ignores idle gaps between bursts`() {
        val frames = (0 until 30).map { frame(it) } +
            (0 until 30).map { i -> frame(i, vsyncNs = 30 * period + 500 * ms + i * period) }
        val s = PerfAnalysis.summarizeFrames(frames, period)
        assertTrue(s.fps in 55.0..62.0, "fps=${s.fps}")
    }

    @Test
    fun `slow frames during main thread GC count as GC`() {
        val frames = (0 until 60).map { i -> frame(i, totalMs = if (i == 10) 40 else 10) }
        val slow = frames[10]
        val gc = listOf(
            // 主线程在等 GC, 与第 10 帧重叠
            PerfGcEvent(endNs = slow.vsyncNs + 20 * ms, durationMs = 15.0, pausedMs = 15.0, cause = "Alloc on Background", tid = mainTid, wait = true),
            // 后台 GC, 离得很远
            PerfGcEvent(endNs = frames[50].vsyncNs, durationMs = 5.0, pausedMs = 0.1, cause = "Background concurrent copying", tid = 99, wait = false),
        )
        val c = PerfAnalysis.attribute(recording(frames, gcEvents = gc), period)
        assertEquals(1, c.janky)
        assertEquals(1, c.gcMain)
        assertEquals(0, c.gcBackground)
    }

    @Test
    fun `slow frames with main thread page faults count as page faults`() {
        val frames = (0 until 60).map { i -> frame(i, totalMs = if (i == 10) 40 else 10) }
        val slow = frames[10]
        val samples = listOf(
            counters(0),
            counters(slow.vsyncNs - ms, mainMajflt = 0),
            counters(slow.endNs + ms, mainMajflt = 3),
            counters(frames.last().endNs, mainMajflt = 3),
        )
        val c = PerfAnalysis.attribute(recording(frames, counters = samples), period)
        assertEquals(1, c.pageFault)
    }

    @Test
    fun `blames GC when most slow frames hit main thread GC`() {
        val slowIndices = (5 until 60 step 5).toList()
        val frames = (0 until 60).map { i -> frame(i, totalMs = if (i in slowIndices) 45 else 10) }
        val gc = slowIndices.map { i ->
            PerfGcEvent(endNs = frames[i].vsyncNs + 30 * ms, durationMs = 25.0, pausedMs = 25.0, cause = "Alloc concurrent copying", tid = mainTid, wait = false)
        }
        val result = PerfAnalysis.analyze(
            recording(frames, gcEvents = gc, counters = listOf(counters(0), counters(frames.last().endNs, gcCount = gc.size.toLong()))),
        )
        val gcFinding = result.findings.firstOrNull { it.text.contains("卡顿主要来自 GC") }
        assertNotNull(gcFinding)
        assertEquals(PerfFinding.Level.BAD, gcFinding.level)
    }

    @Test
    fun `warns when slow frames keep overlapping background GC`() {
        val slowIndices = (5 until 60 step 5).toList()
        val frames = (0 until 60).map { i -> frame(i, totalMs = if (i in slowIndices) 45 else 10) }
        // 次数不多 (3 次), 但每次跑得久, 盖住了大部分慢帧
        val gc = listOf(10, 30, 50).map { i ->
            PerfGcEvent(endNs = frames[i].vsyncNs + 200 * ms, durationMs = 400.0, pausedMs = 0.2, cause = "Background concurrent copying", tid = 99, wait = false)
        }
        val result = PerfAnalysis.analyze(
            recording(frames, gcEvents = gc, counters = listOf(counters(0), counters(frames.last().endNs, gcCount = 3))),
        )
        val f = result.findings.firstOrNull { it.text.startsWith("GC 偏多") }
        assertNotNull(f, result.findings.joinToString { it.text })
        assertEquals(PerfFinding.Level.WARN, f.level)
    }

    @Test
    fun `points out heavy background threads and JIT`() {
        val frames = (0 until 120).map { i -> frame(i) }
        val seconds = frames.last().endNs / 1e9
        val rec = recording(frames).let {
            PerfRecording(
                startNs = it.startNs, endNs = it.endNs, refreshHz = it.refreshHz, cores = 4, mainTid = mainTid, frames = it.frames,
                gcEvents = emptyList(), gcLogAvailable = true, counters = it.counters, memory = emptyList(), trims = emptyList(),
                stalls = emptyList(), stallDurations = emptyList(),
                threadCpuMs = mapOf("main" to 500L, "DefaultDispatch" to (seconds * 1300).toLong(), "Jit thread pool" to (seconds * 300).toLong()),
                totalMemKb = it.totalMemKb, apiLevel = 30, thermalStatus = 0, windowSize = "1920x1080",
            )
        }
        val texts = PerfAnalysis.analyze(rec).findings.map { it.text }
        assertTrue(texts.any { it.startsWith("后台任务很重：DefaultDispatch") }, texts.joinToString())
        assertTrue(texts.any { it.startsWith("JIT 编译用了") }, texts.joinToString())
    }

    @Test
    fun `reports memory pressure`() {
        val frames = (0 until 60).map { i -> frame(i) }
        val result = PerfAnalysis.analyze(
            recording(
                frames,
                counters = listOf(counters(0, memAvailKb = 200_000), counters(frames.last().endNs, memAvailKb = 180_000)),
                trims = listOf(PerfTrim(frames[30].vsyncNs, 10)),
            ),
        )
        assertTrue(result.findings.any { it.text.contains("系统内存紧张") }, result.findings.joinToString { it.text })
    }

    @Test
    fun `too few frames gives a hint instead of frame conclusions`() {
        val frames = (0 until 10).map { i -> frame(i) }
        val result = PerfAnalysis.analyze(recording(frames))
        assertTrue(result.findings.any { it.text.contains("录制期间几乎没有画面更新") })
        assertTrue(result.findings.none { it.text.contains("慢帧") && it.text.contains("fps") })
    }

    @Test
    fun `quiet smooth recording finds no bottleneck`() {
        val frames = (0 until 120).map { i -> frame(i) }
        val result = PerfAnalysis.analyze(recording(frames))
        assertTrue(result.findings.none { it.level == PerfFinding.Level.BAD || it.level == PerfFinding.Level.WARN }, result.findings.joinToString { it.text })
    }
}
