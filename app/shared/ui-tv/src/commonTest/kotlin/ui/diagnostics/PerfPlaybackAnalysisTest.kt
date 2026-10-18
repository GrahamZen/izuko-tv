/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerfPlaybackAnalysisTest {
    private val second = 1_000_000_000L
    private var now = 0L
    private val timeline = PerfTimelineCollector(0, { now })

    private fun at(sec: Double) {
        now = (sec * second).toLong()
    }

    private fun mark(sec: Double, category: String, name: String, detail: String = "") {
        at(sec)
        timeline.event(category, name, detail)
    }

    /** 开播: 进入本集 0.5 秒, 两个源开查, A 在 1.5 秒出结果, 选中、解析, 2.4 秒首帧; B 查到 [searchEndSec]. */
    private fun startup(searchEndSec: Double) {
        mark(0.5, "开播", "本集")
        mark(0.6, "开播", "查询会话", "2 个数据源")
        mark(0.7, "数据源", "A", "查询中")
        mark(0.7, "数据源", "B", "查询中")
        mark(1.5, "数据源", "A", "完成")
        mark(1.6, "开播", "选中")
        mark(2.0, "开播", "加载", "成功")
        mark(2.2, "播放", "视频格式", "avc1 · 1920x1080 · 24.000 fps · 5.0 Mbps")
        mark(2.3, "播放", "视频解码器", "c2.amlogic.avc.decoder")
        mark(2.4, "播放", "就绪")
        mark(2.4, "播放", "首帧")
        mark(2.4, "播放", "开始播放")
        mark(searchEndSec, "数据源", "B", "完成")
    }

    /** 第 [index] 秒: 出帧 / 丢帧 / 跳帧, 选源器耗时, 各线程组 CPU. */
    private fun playSecond(
        index: Int,
        rendered: Long,
        dropped: Long = 0,
        skipped: Long = 0,
        selectorMs: Long = 0,
        cpu: Map<String, Long> = mapOf("main" to 300L),
        displayHz: Double? = null,
        memAvailMb: Long? = null,
    ) {
        at(index + 0.5)
        timeline.count("画面", "采样", 2)
        timeline.count("画面", "出帧", rendered)
        timeline.count("画面", "丢帧", dropped)
        timeline.count("画面", "跳帧", skipped)
        timeline.gauge("画面", "缓冲余量毫秒", 20_000)
        if (selectorMs > 0) timeline.time("选源器", "按集筛选", selectorMs * 1_000_000)
        displayHz?.let { timeline.gauge("显示", "刷新率毫赫", (it * 1000).toLong()) }
        memAvailMb?.let { timeline.gauge("系统", "可用内存MB", it) }
        timeline.recordInterval(index * second, (index + 1) * second, cpu, 1_000_000)
    }

    private fun recording() = PerfRecording(
        startNs = 0, endNs = 10 * second, refreshHz = 60f, cores = 4, mainTid = 1,
        frames = emptyList(), gcEvents = emptyList(), gcLogAvailable = true, counters = emptyList(), memory = emptyList(),
        trims = emptyList(), stalls = emptyList(), stallDurations = emptyList(), threadCpuMs = mapOf("main" to 500L),
        totalMemKb = 3_000_000, apiLevel = 30, thermalStatus = 0, windowSize = "1920x1080", timeline = timeline.snapshot(),
    )

    @Test
    fun `smooth playback reports startup steps and no stutter`() {
        startup(searchEndSec = 3.2)
        for (i in 3..9) playSecond(i, rendered = 24)

        val result = PerfPlaybackAnalysis.analyze(recording())
        assertTrue(result.hasPlayback)
        val texts = result.findings.joinToString("\n") { it.text }
        assertTrue(result.findings.any { it.text.startsWith("开播用了 1.9 秒") }, texts)
        assertTrue(result.findings.any { it.level == PerfFinding.Level.OK && it.text.startsWith("视频画面流畅") }, texts)
        assertTrue(result.findings.any { it.text.contains("硬解") }, texts)
        assertEquals(0, assertNotNull(result.json)["stutterSeconds"]!!.jsonArray.size)
        // 录制时屏幕 60 Hz
        assertTrue(result.findings.any { it.text.startsWith("片源 24.0 fps、屏幕 60.0 Hz，不是整数倍：有的帧显示 2 次、有的显示 3 次") }, texts)
    }

    @Test
    fun `screen switched to a whole multiple of the frame rate gives no cadence warning`() {
        startup(searchEndSec = 3.2)
        for (i in 3..9) playSecond(i, rendered = 24, displayHz = 24.0)

        val result = PerfPlaybackAnalysis.analyze(recording())
        val texts = result.findings.joinToString("\n") { it.text }
        assertTrue(result.findings.none { it.text.contains("不是整数倍") }, texts)
        assertEquals("24.0", assertNotNull(result.json)["displayHz"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stutter while sources search with busy CPU blames the search`() {
        startup(searchEndSec = 6.2)
        playSecond(3, rendered = 24)
        playSecond(4, rendered = 15, dropped = 3, selectorMs = 150, cpu = mapOf("main" to 400L, "DefaultDispatch" to 3200L))
        playSecond(5, rendered = 14, dropped = 4, selectorMs = 180, cpu = mapOf("main" to 400L, "DefaultDispatch" to 3300L))
        for (i in 6..9) playSecond(i, rendered = 24)

        val result = PerfPlaybackAnalysis.analyze(recording())
        val texts = result.findings.joinToString("\n") { it.text }
        val stutter = assertNotNull(result.json)["stutterSeconds"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(listOf(4, 5), stutter)
        assertTrue(result.findings.any { it.text.startsWith("视频画面卡了 2 秒") }, texts)
        assertTrue(result.findings.any { it.text.startsWith("卡的那 2 秒里：2 秒数据源还在查询，2 秒本应用 CPU 接近吃满") }, texts)
        assertTrue(result.findings.any { it.text.startsWith("多半是开播时还在搜索的数据源和选源器抢了 CPU") }, texts)
        assertTrue(result.findings.any { it.text.startsWith("首帧后搜索又持续了 3.8 秒") }, texts)
    }

    @Test
    fun `frames skipped right after playback starts are not stutter`() {
        startup(searchEndSec = 3.2)
        playSecond(3, rendered = 12, skipped = 200)
        for (i in 4..9) playSecond(i, rendered = 24)

        val result = PerfPlaybackAnalysis.analyze(recording())
        val texts = result.findings.joinToString("\n") { it.text }
        val json = assertNotNull(result.json)
        assertEquals(0, json["stutterSeconds"]!!.jsonArray.size)
        assertEquals(200, json["prerollSkipped"]!!.jsonPrimitive.int)
        assertTrue(result.findings.any { it.text.startsWith("视频画面流畅") }, texts)
    }

    @Test
    fun `CPU of a sampling interval is split across the seconds it covers`() {
        timeline.recordInterval(second / 2, second * 3 / 2, mapOf("main" to 1000L), 2000)
        val seconds = timeline.snapshot().seconds
        assertEquals(listOf(0, 1), seconds.map { it.index })
        assertEquals(500.0, seconds[0].threadCpuMs["main"]!!, 0.001)
        assertEquals(500.0, seconds[1].threadCpuMs["main"]!!, 0.001)
        assertEquals(1000.0, seconds[1].rxBytes, 0.001)
    }

    @Test
    fun `seconds spent buffering are not counted as stutter`() {
        startup(searchEndSec = 3.2)
        for (i in 3..5) playSecond(i, rendered = 24)
        mark(6.1, "播放", "缓冲")
        playSecond(6, rendered = 5)
        mark(6.9, "播放", "就绪")
        for (i in 7..9) playSecond(i, rendered = 24)

        val result = PerfPlaybackAnalysis.analyze(recording())
        val texts = result.findings.joinToString("\n") { it.text }
        assertEquals(0, assertNotNull(result.json)["stutterSeconds"]!!.jsonArray.size)
        assertTrue(result.findings.any { it.text.startsWith("播放中缓冲 1 次，共 0.8 秒") }, texts)
    }

    @Test
    fun `report on a playback recording explains the idle UI and includes video findings`() {
        startup(searchEndSec = 3.2)
        for (i in 3..9) playSecond(i, rendered = 24)

        val result = PerfAnalysis.analyze(recording())
        val texts = result.findings.joinToString("\n") { it.text }
        assertTrue(result.findings.any { it.text.startsWith("界面几乎没有重画") }, texts)
        assertTrue(result.findings.any { it.text.startsWith("视频画面流畅") }, texts)
        assertNotNull(result.json["playback"])
    }

    @Test
    fun `detect stays quiet while playback is smooth`() {
        startup(searchEndSec = 3.2)
        for (i in 3..9) playSecond(i, rendered = 24)
        assertNull(PerfPlaybackAnalysis.detect(timeline.snapshot(), 10 * second))
    }

    @Test
    fun `detect reports a frozen picture while plenty is buffered`() {
        startup(searchEndSec = 3.2)
        for (i in 3..6) playSecond(i, rendered = 24)
        playSecond(7, rendered = 0)
        playSecond(8, rendered = 24)
        val reason = assertNotNull(PerfPlaybackAnalysis.detect(timeline.snapshot(), 9 * second))
        assertTrue(reason.contains("画面却停了"), reason)
    }

    @Test
    fun `detect reports too few frames over five seconds`() {
        startup(searchEndSec = 3.2)
        for (i in 3..9) playSecond(i, rendered = 12)
        assertEquals("5 秒只出了 60 帧（片源 24.0 fps）", PerfPlaybackAnalysis.detect(timeline.snapshot(), 10 * second))
    }

    @Test
    fun `detect ignores playback at another speed`() {
        startup(searchEndSec = 3.2)
        mark(2.5, "播放", "倍速", "0.50")
        for (i in 3..9) playSecond(i, rendered = 12)
        assertNull(PerfPlaybackAnalysis.detect(timeline.snapshot(), 10 * second))
    }

    @Test
    fun `detect ignores buffering seconds`() {
        startup(searchEndSec = 3.2)
        for (i in 3..5) playSecond(i, rendered = 24)
        mark(6.1, "播放", "缓冲")
        for (i in 6..9) playSecond(i, rendered = 0)
        assertNull(PerfPlaybackAnalysis.detect(timeline.snapshot(), 10 * second))
    }

    @Test
    fun `retained timeline keeps the last state of pruned events`() {
        val ring = PerfTimelineCollector(0, { now }, retainNs = 5 * second)
        at(1.0)
        ring.event("数据源", "A", "查询中")
        ring.event("播放", "视频格式", "24.000 fps")
        at(2.0)
        ring.event("数据源", "A", "完成")
        ring.count("画面", "出帧", 24)
        at(20.0)
        ring.count("画面", "出帧", 24)
        ring.prune(20 * second)
        val data = ring.snapshot(18 * second)
        assertEquals(18 * second, data.startNs)
        assertEquals(listOf("视频格式", "A"), data.events.map { it.name })
        assertEquals("完成", data.events.last().detail)
        assertEquals(listOf(2), data.seconds.map { it.index })
    }

    @Test
    fun `automatic report puts the trigger first and notes tight memory`() {
        startup(searchEndSec = 3.2)
        for (i in 3..9) playSecond(i, rendered = if (i in 6..7) 10 else 24, memAvailMb = 300)
        mark(8.2, "哨兵", "触发", "5 秒只出了 92 帧（片源 24.0 fps）")

        val result = PerfAnalysis.analyze(recording())
        val texts = result.findings.joinToString("\n") { it.text }
        assertTrue(result.findings.first().text.startsWith("画面异常卡顿，自动录下了这份报告：5 秒只出了 92 帧"), texts)
        assertTrue(result.findings.any { it.text.startsWith("卡的那几秒里：2 秒整机内存紧（可用内存最低 300 MB") }, texts)
        assertTrue(result.findings.any { it.text.startsWith("多半是整机内存太紧") }, texts)
        assertTrue(result.findings.any { it.text.startsWith("播放时有 7 秒整机内存紧") }, texts)
    }

    @Test
    fun `a count over a sampling interval is split across the seconds it covers`() {
        at(1.25)
        timeline.count("画面", "出帧", 13, spanNs = second / 2)
        val seconds = timeline.snapshot().seconds
        assertEquals(listOf(7L, 6L), seconds.map { it.count("画面/出帧") })
    }

    @Test
    fun `frame rate comes from rendered frames when the format does not say`() {
        mark(0.5, "开播", "本集")
        mark(2.2, "播放", "视频格式", "hvc1 3840x2160")
        mark(2.4, "播放", "开始播放")
        for (i in 3..9) playSecond(i, rendered = if (i == 6) 12 else 24)
        val json = assertNotNull(PerfPlaybackAnalysis.analyze(recording()).json)
        assertEquals("24.0", json["fps"]!!.jsonPrimitive.content)
        assertEquals(listOf(6), json["stutterSeconds"]!!.jsonArray.map { it.jsonPrimitive.int })
    }
}
