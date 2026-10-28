/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * 查询用的线程池 ([SearchDispatchers]): [threads] 个线程, 任务按通道的位次取, 同位次先来先做.
 *
 * [shared] 是 [SearchDispatchers.lowPriority]: 各源查询的外层 (交起步的空结果、累积与发布结果、改状态) 与没分通道时的查询都在这上面.
 * 它排在所有源的通道 ([lane]) 前面: 外层的活都很轻, 排在解析后面的话, 冷启动时队里压着十几个源的解析,
 * 选源器要等所有源都解析完才拿到第一份结果 (它要每个源都交过一次才开始判断).
 */
class PrioritySearchPool(threads: Int, threadFactory: ThreadFactory) : SearchDispatchers.Lanes {
    private val executor = ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, PriorityBlockingQueue(), threadFactory)
    private val seq = AtomicLong()
    private val lanes = HashMap<Int, Lane>()

    val shared: CoroutineDispatcher = lane(SHARED_RANK)

    override fun lane(rank: Int): CoroutineDispatcher = synchronized(lanes) { lanes.getOrPut(rank) { Lane(rank) } }

    private inner class Lane(private val rank: Int) : CoroutineDispatcher(), SearchDispatchers.Lane {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            executor.execute(Task(rank, seq.getAndIncrement(), block))
        }

        override fun toString(): String = "SearchLane($rank)"
    }

    private class Task(private val rank: Int, private val seq: Long, private val block: Runnable) : Runnable, Comparable<Task> {
        override fun run() = block.run()

        override fun compareTo(other: Task): Int =
            if (rank != other.rank) rank.compareTo(other.rank) else seq.compareTo(other.seq)
    }

    private companion object {
        const val SHARED_RANK = Int.MIN_VALUE
    }
}
