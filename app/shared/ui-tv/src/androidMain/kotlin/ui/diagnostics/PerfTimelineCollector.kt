/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import me.him188.ani.app.platform.perf.PerfTimeline
import kotlin.math.roundToLong

/** 时间线上的一件事 (见 [PerfTimeline.mark]). */
internal class PerfTimelineEvent(val tNs: Long, val category: String, val name: String, val detail: String)

/** 一个 (大类, 名字) 在某一秒里的累计: 次数与总耗时 (计时), 或增量之和 (计数). */
internal class PerfTally(var count: Long = 0, var sum: Long = 0)

/**
 * 某一秒 (从时间线开头数, 第 [index] 秒) 的累计: 计时 / 计数 / 读数按「大类/名字」, 各线程组用的 CPU 毫秒, 本应用这一秒收到的字节.
 * 读数取这一秒里的最小值.
 */
internal class PerfSecond(val index: Int) {
    val times = HashMap<String, PerfTally>()
    val counts = HashMap<String, Long>()
    val gauges = HashMap<String, Long>()
    val threadCpuMs = HashMap<String, Double>()

    /** 这一秒收到的字节; 读不到流量时为 -1. */
    var rxBytes: Double = -1.0

    fun count(key: String): Long = counts[key] ?: 0L

    /** 换个序号的副本 (取快照时从 0 重数). */
    fun shifted(newIndex: Int): PerfSecond = PerfSecond(newIndex).also {
        it.times.putAll(times.mapValues { (_, v) -> PerfTally(v.count, v.sum) })
        it.counts.putAll(counts)
        it.gauges.putAll(gauges)
        it.threadCpuMs.putAll(threadCpuMs)
        it.rxBytes = rxBytes
    }
}

/**
 * 一段时间线: [seconds] 的序号从 [startNs] 那一秒数起. [events] 里可能有早于 [startNs] 的 (见 [PerfTimelineCollector.snapshot]),
 * 是那之前各「大类/名字」最后一条, 用来接上当时的状态 (各数据源在不在查、解码器与格式).
 */
internal class PerfTimelineData(val startNs: Long, val events: List<PerfTimelineEvent>, val seconds: List<PerfSecond>) {
    val isEmpty: Boolean get() = events.isEmpty() && seconds.all { it.counts.isEmpty() && it.times.isEmpty() }

    companion object {
        val EMPTY = PerfTimelineData(0, emptyList(), emptyList())

        fun key(category: String, name: String) = "$category/$name"
    }
}

/**
 * 接 [PerfTimeline] 报进来的事: 事件逐条记, 计时 / 计数 / 读数按秒累计. 各处可能在任意线程报, 都在一把锁里改, 锁里只做几次哈希表操作.
 *
 * 电视上常驻一个 (见 [TvPlaybackSentinel]), 只留最近 [retainNs]: 更早的事件按「大类/名字」各留最后一条, 取快照时接在前面;
 * 录制进行中用 [hold] 让录制开始以后的都留着. 事件最多 [MAX_EVENTS] 条, 超了先挪走最早的.
 */
internal class PerfTimelineCollector(
    private val startNs: Long,
    private val clock: () -> Long,
    private val retainNs: Long = Long.MAX_VALUE,
) : PerfTimeline.Sink {
    private val lock = Any()
    private val events = ArrayDeque<PerfTimelineEvent>()
    private val earlier = HashMap<String, PerfTimelineEvent>()
    private val seconds = HashMap<Int, PerfSecond>()
    private var holdFromNs = Long.MAX_VALUE
    private var sincePrune = 0

    override fun now(): Long = clock()

    override fun event(category: String, name: String, detail: String) {
        val t = clock()
        synchronized(lock) {
            events.addLast(PerfTimelineEvent(t, category, name, detail))
            if (events.size > MAX_EVENTS) moveEarlier(events.removeFirst())
            if (++sincePrune >= PRUNE_EVERY) prune(t)
        }
    }

    override fun time(category: String, name: String, durationNs: Long) {
        val t = clock()
        synchronized(lock) {
            val tally = second(t).times.getOrPut(PerfTimelineData.key(category, name)) { PerfTally() }
            tally.count++
            tally.sum += durationNs
        }
    }

    override fun count(category: String, name: String, delta: Long, spanNs: Long) {
        val t = clock()
        val key = PerfTimelineData.key(category, name)
        synchronized(lock) {
            if (spanNs <= 0) {
                add(second(t), key, delta)
                return
            }
            // 按与每一秒重叠的时长分摊, 累计取整, 总数不变
            val from = t - spanNs
            var cur = from
            var assigned = 0L
            while (cur < t) {
                val s = second(cur)
                val end = minOf(t, (startNs + (s.index + 1) * SECOND_NS).coerceAtLeast(cur + 1))
                val upTo = if (end >= t) delta else (delta.toDouble() * (end - from) / spanNs).roundToLong()
                add(s, key, upTo - assigned)
                assigned = upTo
                cur = end
            }
        }
    }

    private fun add(second: PerfSecond, key: String, delta: Long) {
        if (delta != 0L) second.counts[key] = (second.counts[key] ?: 0L) + delta
    }

    override fun gauge(category: String, name: String, value: Long) {
        val t = clock()
        synchronized(lock) {
            val gauges = second(t).gauges
            val key = PerfTimelineData.key(category, name)
            gauges[key] = minOf(gauges[key] ?: Long.MAX_VALUE, value)
        }
    }

    /**
     * 录制器大约每秒报一次 [fromNs, toNs] 这段里各线程组用的 CPU 毫秒与本应用收到的字节 ([rxBytes] 读不到时为 -1),
     * 按与每一秒重叠的长度分摊 (采样间隔不是正好一秒, 整段记到一秒里会一秒两份、下一秒空着).
     */
    fun recordInterval(fromNs: Long, toNs: Long, threadCpuMs: Map<String, Long>, rxBytes: Long) {
        if (toNs <= fromNs) return
        synchronized(lock) {
            var t = fromNs
            while (t < toNs) {
                val s = second(t)
                val secondEnd = startNs + (s.index + 1) * SECOND_NS
                val end = minOf(toNs, secondEnd.coerceAtLeast(t + 1))
                val share = (end - t).toDouble() / (toNs - fromNs)
                for ((name, ms) in threadCpuMs) s.threadCpuMs[name] = (s.threadCpuMs[name] ?: 0.0) + ms * share
                if (rxBytes >= 0) s.rxBytes = s.rxBytes.coerceAtLeast(0.0) + rxBytes * share
                t = end
            }
        }
    }

    /** 不早于 [fromNs] 的都留着 (录制进行中); null = 不再留. */
    fun hold(fromNs: Long?) {
        synchronized(lock) { holdFromNs = fromNs ?: Long.MAX_VALUE }
    }

    /** 挪走 [retainNs] 以前的 (调用方定期调; 报事件时也会顺带调). */
    fun prune(nowNs: Long = clock()) {
        synchronized(lock) {
            sincePrune = 0
            if (retainNs == Long.MAX_VALUE) return
            val cutoff = minOf(nowNs - retainNs, holdFromNs)
            while (events.isNotEmpty() && events.first().tNs < cutoff) moveEarlier(events.removeFirst())
            val firstKept = index(cutoff)
            seconds.keys.removeAll { it < firstKept }
        }
    }

    /**
     * [fromNs] 所在那一秒起 (到 [toNs] 为止) 的一段, 秒的序号从 0 重数; 那之前各「大类/名字」最后一条事件放在最前面.
     */
    fun snapshot(fromNs: Long = startNs, toNs: Long = Long.MAX_VALUE): PerfTimelineData = synchronized(lock) {
        val firstIndex = index(fromNs)
        val base = startNs + firstIndex * SECOND_NS
        val before = HashMap(earlier)
        val within = ArrayList<PerfTimelineEvent>()
        for (e in events) {
            when {
                e.tNs < base -> before[PerfTimelineData.key(e.category, e.name)] = e
                e.tNs < toNs -> within += e
            }
        }
        val lastIndex = if (toNs == Long.MAX_VALUE) Int.MAX_VALUE else index(toNs)
        PerfTimelineData(
            startNs = base,
            events = before.values.sortedBy { it.tNs } + within,
            seconds = seconds.values.filter { it.index in firstIndex..lastIndex }.sortedBy { it.index }
                .map { it.shifted(it.index - firstIndex) },
        )
    }

    private fun moveEarlier(e: PerfTimelineEvent) {
        earlier[PerfTimelineData.key(e.category, e.name)] = e
    }

    private fun index(tNs: Long): Int = ((tNs - startNs) / SECOND_NS).toInt().coerceAtLeast(0)

    private fun second(tNs: Long): PerfSecond = index(tNs).let { i -> seconds.getOrPut(i) { PerfSecond(i) } }

    private companion object {
        const val SECOND_NS = 1_000_000_000L
        const val MAX_EVENTS = 4000
        const val PRUNE_EVERY = 64
    }
}
