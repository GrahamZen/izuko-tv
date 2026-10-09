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
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceTier
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 同时在查的数据源个数的上限 (全进程共用): 数据源一多, 全部同时开查会在几秒内发出几百个请求、解析一堆网页,
 * 内存冲高到被系统杀掉 (尤其是正在播 4K 的时候). 只限「查」这一段; 查之前先发出的空结果不受限, 别的源照常汇总.
 *
 * 排队的先后: 层级 ([MediaSourceTier]) 低的在前, 同层在线源在 BT 前, 再按排队的先后. 本地缓存与 [SelfLimitedMediaSource] 不受限.
 * 一个源占着名额超过 [holdLimit] 还没查完, 就把名额让给下一个 (它照常查完): BT 翻很多页这类慢的源, 不至于把后面的源一直挡着.
 */
class MediaSourceSearchLimiter(
    private val permits: Int,
    private val holdLimit: Duration,
    private val timerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private class Waiter(val priority: Long, val seq: Long) {
        val granted = CompletableDeferred<Unit>()
    }

    private val lock = SynchronizedObject()
    private var available = permits
    private var nextSeq = 0L
    private val waiting = ArrayList<Waiter>()

    /** 占着的一个名额. [release] 多次调用只算一次. */
    inner class Permit internal constructor(private val name: String) {
        private val released = atomic(false)
        private val timer = timerScope.launch {
            delay(holdLimit)
            if (release()) logger.info { "$name is still searching after $holdLimit, letting the next source start" }
        }

        /** 交还名额, 交给排在最前的那个. 真的交还了返回 true. */
        fun release(): Boolean {
            if (!released.compareAndSet(expect = false, update = true)) return false
            timer.cancel()
            handOver()
            return true
        }
    }

    /** 等到名额. [priority] 越小越先. 等的时候被取消不占名额. */
    suspend fun acquire(priority: Long, name: String): Permit {
        val waiter = synchronized(lock) {
            if (available > 0 && waiting.isEmpty()) {
                available--
                null
            } else {
                Waiter(priority, nextSeq++).also { waiting += it }
            }
        }
        if (waiter != null) {
            try {
                waiter.granted.await()
            } catch (e: CancellationException) {
                // 已经不在队里 = 名额刚交给了它, 转给下一个
                val wasGranted = synchronized(lock) { !waiting.remove(waiter) }
                if (wasGranted) handOver()
                throw e
            }
        }
        return Permit(name)
    }

    private fun handOver() {
        val next = synchronized(lock) {
            val first = waiting.minWithOrNull(compareBy<Waiter>({ it.priority }, { it.seq }))
            if (first == null) {
                available++
            } else {
                waiting.remove(first)
            }
            first
        }
        next?.granted?.complete(Unit)
    }

    companion object {
        private val logger = logger<MediaSourceSearchLimiter>()

        /** 播放与下载的搜索共用这一个. */
        val Default = MediaSourceSearchLimiter(permits = 6, holdLimit = 10.seconds)

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
): Flow<T> {
    if (kind == MediaSourceKind.LocalCache) return this
    val upstream = this
    return flow {
        val permit = limiter.acquire(MediaSourceSearchLimiter.priorityOf(kind, tier), name)
        try {
            emitAll(upstream)
        } finally {
            permit.release()
        }
    }
}
