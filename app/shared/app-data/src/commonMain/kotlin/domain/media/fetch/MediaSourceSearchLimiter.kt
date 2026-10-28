/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.player.diagnostics.PlayTimeline
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceTier
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 同时在查的数据源个数的上限 (全进程共用): 数据源一多, 全部同时开查会在几秒内发出几百个请求、解析一堆网页,
 * 内存冲高到被系统杀掉 (尤其是正在播 4K 的时候). 只限「查」这一段; 查之前先发出的空结果不受限, 别的源照常汇总.
 *
 * 上限 ([limit]) 每次发名额时现取: [Default] 用 [SearchConcurrency] (默认不限, 吃不消时按阶梯调小). 不限时来一个发一个;
 * 调小时正在查的不受影响, 在查的少于上限才发下一个.
 *
 * 排队的先后: 层级 ([MediaSourceTier]) 低的在前, 同层在线源在 BT 前, 再按排队的先后. 本地缓存与 [SelfLimitedMediaSource] 不受限.
 * 一个源占着名额超过 [holdLimit] 还没查完, 就把名额让给下一个 (它照常查完): BT 翻很多页这类慢的源, 不至于把后面的源一直挡着.
 * 让出去的名额只管排队: 真正在查的 (拿到名额、还没查完的, 让过名额的也算) 不超过上限的 [MAX_RUNNING_PER_PERMIT] 倍,
 * 调小了上限 (内存吃紧) 时慢源也不能一批批地把同时在查的个数顶上去.
 */
class MediaSourceSearchLimiter(
    private val limit: () -> Int,
    private val holdLimit: Duration,
    private val timerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * 名额空着时来查的源先排队, 等这么久收齐同时来的一批再按优先级发名额; 为 0 时来一个发一个.
     * 开播时各源几乎同时来查, 来一个发一个的话头几个名额归列表里排在前面的源 (常是 BT 源), 层级低的在线源要等它们让出名额.
     */
    private val startWindow: () -> Duration = { Duration.ZERO },
    /** 真正在查的个数 (让过名额、还没查完的也算) 变了 (在锁里调, 要快). */
    private val onInFlightChanged: (Int) -> Unit = {},
    /** 没有在查的时候来了要查的, 发名额之前调 (不在锁里). */
    private val beforeBatch: () -> Unit = {},
) {
    /** 固定 [permits] 个名额. */
    constructor(
        permits: Int,
        holdLimit: Duration,
        timerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        startWindow: () -> Duration = { Duration.ZERO },
    ) : this({ permits }, holdLimit, timerScope, startWindow)

    private class Waiter(var priority: Long, val seq: Long, val key: String?) {
        val granted = CompletableDeferred<Unit>()
    }

    private val lock = SynchronizedObject()

    /** 占着名额的个数 */
    private var inFlight = 0

    /** 真正在查的个数: 拿到名额、还没查完 (让过名额的也算) */
    private var running = 0
    private var nextSeq = 0L
    private val waiting = ArrayList<Waiter>()

    /** 正在收一批 (见 [startWindow]) */
    private var gathering = false

    /** [prioritize] 过、还没来排队的源 (见 [acquire]) */
    private val urgent = HashMap<String, TimeMark>()

    /**
     * 让 [key] (数据源 id) 排到最前: 正在排队的立即挪到队首, 还没来排队的在 [URGENT_WINDOW] 内来排时直接排队首.
     * 自动选源的记忆源阶段只等记住的那个源, 它排在一串源后面时开播就跟着等.
     */
    fun prioritize(key: String) {
        synchronized(lock) {
            val queued = waiting.filter { it.key == key }
            queued.forEach { it.priority = URGENT_PRIORITY }
            if (queued.isEmpty()) urgent[key] = TimeSource.Monotonic.markNow()
        }
    }

    /** 占着的一个名额, 查完 (或被取消) 时调 [release]. 占满 [holdLimit] 时先把名额让给下一个, 仍算在查. */
    inner class Permit internal constructor(private val name: String) {
        private val yielded = atomic(false)
        private val finished = atomic(false)
        private val timer = timerScope.launch {
            // 不限时没有在等的, 不必让; 中途调小了才让
            do {
                delay(holdLimit)
            } while (limit() == SearchConcurrency.UNLIMITED)
            if (yieldSlot()) logger.info { "$name is still searching after $holdLimit, letting the next source start" }
        }

        /** 把名额交给排在最前的那个, 自己接着查. 真的交了返回 true. */
        private fun yieldSlot(): Boolean {
            if (!yielded.compareAndSet(expect = false, update = true)) return false
            timer.cancel()
            handOver()
            return true
        }

        /** 查完了 (或被取消): 不再算在查, 还占着名额就交还. 多次调用只算一次. */
        fun release() {
            if (!finished.compareAndSet(expect = false, update = true)) return
            synchronized(lock) { onInFlightChanged(--running) }
            // 名额早让出去了时少了一个在查的, 也可能因此能放排着的进来
            if (!yieldSlot()) grant(endsGathering = false)
        }
    }

    /** 等到名额. [priority] 越小越先, [key] 是数据源 id (见 [prioritize]). 等的时候被取消不占名额. */
    suspend fun acquire(priority: Long, name: String, key: String? = null): Permit {
        if (running == 0) beforeBatch()
        val window = startWindow()
        val limit = limit()
        var gather = false
        val waiter = synchronized(lock) {
            val boosted = key != null && urgent.remove(key)?.let { it.elapsedNow() < URGENT_WINDOW } == true
            // 不限时来一个发一个; 排在最前的 (插队的、层级最高的在线源) 不必等着跟同一批的比, 有空名额就给
            val first = boosted || priority <= TOP_PRIORITY || limit == SearchConcurrency.UNLIMITED
            if (canStart(limit) && (first || waiting.isEmpty() && window <= Duration.ZERO)) {
                inFlight++
                onInFlightChanged(++running)
                null
            } else {
                // 名额空着而没人在收这一批: 由这个源开一个窗口
                if (canStart(limit) && !gathering) {
                    gathering = true
                    gather = true
                }
                Waiter(if (boosted) URGENT_PRIORITY else priority, nextSeq++, key).also { waiting += it }
            }
        }
        if (gather) {
            timerScope.launch {
                delay(window)
                grant(endsGathering = true)
            }
        }
        if (waiter != null) {
            val queuedAt = TimeSource.Monotonic.markNow()
            try {
                waiter.granted.await()
            } catch (e: CancellationException) {
                // 已经不在队里 = 名额刚交给了它, 转给下一个
                val wasGranted = synchronized(lock) { !waiting.remove(waiter) }
                if (wasGranted) {
                    synchronized(lock) { onInFlightChanged(--running) }
                    handOver()
                }
                throw e
            }
            logger.info { "$name waited ${queuedAt.elapsedNow().inWholeMilliseconds} ms for a search slot" }
        }
        return Permit(name)
    }

    /** 交还一个名额, 在查的少于上限时按优先级发给排队的 (上限调大过时可能一次发几个) */
    private fun handOver() {
        synchronized(lock) { inFlight-- }
        grant(endsGathering = false)
    }

    /** 还能再开查一个: 有空名额, 真正在查的也没到上限的 [MAX_RUNNING_PER_PERMIT] 倍 (在锁里调) */
    private fun canStart(limit: Int): Boolean =
        inFlight < limit && (limit == SearchConcurrency.UNLIMITED || running < limit * MAX_RUNNING_PER_PERMIT)

    private fun grant(endsGathering: Boolean) {
        val granted = synchronized(lock) {
            if (endsGathering) gathering = false
            val limit = limit()
            buildList {
                while (canStart(limit)) {
                    val first = waiting.minWithOrNull(compareBy<Waiter>({ it.priority }, { it.seq })) ?: break
                    waiting.remove(first)
                    inFlight++
                    onInFlightChanged(++running)
                    add(first)
                }
            }
        }
        granted.forEach { it.granted.complete(Unit) }
    }

    companion object {
        private val logger = logger<MediaSourceSearchLimiter>()

        /** 播放与下载的搜索共用这一个. */
        val Default = MediaSourceSearchLimiter(
            limit = SearchConcurrency.Default::current,
            holdLimit = 10.seconds,
            startWindow = { DevSwitches.searchStartWindowMs.value.toDouble().milliseconds },
            onInFlightChanged = SearchConcurrency.Default::onInFlightChanged,
            beforeBatch = SearchConcurrency.Default::beforeBatch,
        )

        /** 真正在查的个数最多是上限的几倍 (让过名额的慢源也算, 见类说明) */
        private const val MAX_RUNNING_PER_PERMIT = 2

        /** [prioritize] 的源的优先级: 比任何层级都靠前 */
        private const val URGENT_PRIORITY = -1L

        /** 最高层级 (0) 的在线源的优先级 (见 [priorityOf]) */
        private const val TOP_PRIORITY = 0L

        /** [prioritize] 之后多久内来排队仍算插队 */
        private val URGENT_WINDOW = 60.seconds

        /** 排队的先后, 见 [MediaSourceSearchLimiter]. */
        fun priorityOf(kind: MediaSourceKind, tier: MediaSourceTier?): Long =
            (tier ?: MediaSourceTier.Fallback).value.toLong() * 2 + if (kind == MediaSourceKind.BitTorrent) 1 else 0
    }
}

/**
 * 不占 [MediaSourceSearchLimiter] 名额、一开始就查的数据源: 只发少量 JSON 请求、自己限着同时发的请求数 (网盘源),
 * 不会像解析网页的源那样把内存冲高. 它们按层级排队的话会排在一串网页源后面, 开播时往往还没轮到就被暂停.
 */
interface SelfLimitedMediaSource

/**
 * 拿到 [limiter] 的名额才开始收集 (见 [MediaSourceSearchLimiter]), 收集完、出错或被取消时交还. 本地缓存不受限.
 */
internal fun <T> Flow<T>.withSearchPermit(
    limiter: MediaSourceSearchLimiter,
    kind: MediaSourceKind,
    tier: MediaSourceTier?,
    name: String,
    key: String? = null,
): Flow<T> {
    if (kind == MediaSourceKind.LocalCache) return this
    val upstream = this
    return flow {
        PlayTimeline.mark("t_acquire", name)
        val permit = limiter.acquire(MediaSourceSearchLimiter.priorityOf(kind, tier), name, key)
        PlayTimeline.mark("t_permit", name)
        try {
            emitAll(upstream)
        } finally {
            permit.release()
        }
    }
}
