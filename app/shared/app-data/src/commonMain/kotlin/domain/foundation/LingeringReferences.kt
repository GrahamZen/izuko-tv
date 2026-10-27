/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * [DefaultHttpClientProvider] 归还的 HttpClient 多留多久 (见 [LingeringReferences]): 盖住一次搜索与随后打开的条目页、解析播放地址.
 */
internal val HTTP_CLIENT_LINGER: Duration = 1.minutes

/**
 * 归还的实例在 [pool] 里多留 [linger] 再放 ([ReuseObjectPool] 在最后一个借用者归还时就放掉实例).
 *
 * 给 [DefaultHttpClientProvider] 用: 数据源一次搜索先搜、再一页页打开条目页, 每个请求都单独借还 HttpClient; 归还就关的话,
 * 下一个请求拿到的是新实例, 连接池是空的, 每个请求都重新建连 (TCP + TLS, 电视上每次一两百毫秒), 同时开的两页还各建一条.
 * 留着的实例按 [ReuseObjectPool] 的规矩多借一份引用, 期间再归还就重新计时, 满 [linger] 没再用才还掉这份引用.
 */
internal class LingeringReferences<K : Any, V : Any>(
    private val pool: ReuseObjectPool<K, V>,
    private val scope: CoroutineScope,
    private val linger: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Held<V>(val value: V, var lastUsed: TimeMark)

    private val lock = SynchronizedObject()
    private val held = HashMap<K, Held<V>>()

    /**
     * [value] 是按 [key] 从 [pool] 借出、还没归还的实例, 在归还前调用: 第一次见到时多借一份引用, 之后重新计时.
     */
    fun retain(key: K, value: V) {
        synchronized(lock) {
            val current = held[key]
            if (current != null && current.value === value) {
                current.lastUsed = timeSource.markNow()
                return
            }
            // 调用方还没归还, 池里这个 key 对应的就是 value, 这里借到的是同一个
            val extra = pool.borrow(key)
            if (extra !== value) {
                pool.release(key, extra)
                return
            }
            val entry = Held(value, timeSource.markNow())
            held[key] = entry
            scope.launch { expire(key, entry) }
        }
    }

    private suspend fun expire(key: K, entry: Held<V>) {
        while (true) {
            val remaining = synchronized(lock) {
                if (held[key] !== entry) return
                val left = linger - entry.lastUsed.elapsedNow()
                if (left <= Duration.ZERO) held.remove(key)
                left
            }
            if (remaining <= Duration.ZERO) {
                pool.release(key, entry.value)
                return
            }
            delay(remaining)
        }
    }

    /** 不再留任何实例, 也不归还 (池被强制清空时用). */
    fun forget() {
        synchronized(lock) { held.clear() }
    }
}
