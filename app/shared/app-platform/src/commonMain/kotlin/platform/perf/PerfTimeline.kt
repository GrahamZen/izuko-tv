/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform.perf

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * 性能诊断的事件时间线: 开播各步、数据源查询、选源器筛选、播放器出帧 / 丢帧 / 缓冲由各处报进来,
 * 接收者按时刻和慢帧、GC、各线程组 CPU 排在一起 (电视上是常驻的播放哨兵, 留最近一段, 「性能诊断 → 录制」与画面异常卡顿时的自动报告都从它取).
 *
 * 没有接收者时 ([sink] 为 null, 如手机版): [mark] / [measure] / [count] / [gauge] 只读一次 volatile 就返回, 文字参数用 lambda, 没人收时不拼字符串.
 * 要持续采样的 (如播放器的解码计数) 收 [active], 有接收者时才起.
 * 时刻由接收者在收到时打 (同一个时钟, 见 [Sink.now]); 各方法可在任意线程调, 接收者自己保证线程安全, 也不能阻塞调用方.
 */
object PerfTimeline {
    interface Sink {
        /** 接收者的时钟 (纳秒, 单调). */
        fun now(): Long

        /** 一件事: [category] 是大类 (开播 / 数据源 / 播放 …), [name] 是事, [detail] 是补充. */
        fun event(category: String, name: String, detail: String)

        /** 一段耗时 (高频的, 按秒累计次数与总时长). */
        fun time(category: String, name: String, durationNs: Long)

        /** 一个增量 (如半秒内出了几帧), 按秒累加; [spanNs] > 0 时是到现在为止这么长一段里的增量, 按时长分摊到各秒. */
        fun count(category: String, name: String, delta: Long, spanNs: Long = 0)

        /** 一个读数 (如缓冲余量), 按秒取最小值. */
        fun gauge(category: String, name: String, value: Long)
    }

    @Volatile
    var sink: Sink? = null
        private set

    private val activeFlow = MutableStateFlow(false)

    /** 有接收者. */
    val active: StateFlow<Boolean> = activeFlow.asStateFlow()

    fun attach(sink: Sink) {
        this.sink = sink
        activeFlow.value = true
    }

    fun detach(sink: Sink) {
        if (this.sink !== sink) return
        this.sink = null
        activeFlow.value = false
    }

    inline fun mark(category: String, name: String, detail: () -> String = { "" }) {
        sink?.event(category, name, detail())
    }

    inline fun <T> measure(category: String, name: String, block: () -> T): T {
        val s = sink ?: return block()
        val start = s.now()
        try {
            return block()
        } finally {
            s.time(category, name, s.now() - start)
        }
    }

    fun count(category: String, name: String, delta: Long, spanNs: Long = 0) {
        if (delta != 0L) sink?.count(category, name, delta, spanNs)
    }

    fun gauge(category: String, name: String, value: Long) {
        sink?.gauge(category, name, value)
    }
}
