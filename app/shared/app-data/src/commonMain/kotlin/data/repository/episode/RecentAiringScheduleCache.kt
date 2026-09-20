/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import me.him188.ani.app.domain.episode.AiringScheduleForDate
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * 最近一次拉到的新番时间表, 进程里只存一份 (按"哪天 + 时区"认). 再进时间表页先出这一份, 不必每次等一次网络 ——
 * 2026-09-13 两台电视实测, 进页到焦点落位 0.73~1.08s, 其中 0.6~0.8s 在等这个请求与解析.
 *
 * - 只认 [maxAge] 之内的: 与页面开着时的刷新周期同一个数, 看到的不会比一直开着页面更旧. 过期就当没有, 照常等网络 ——
 *   不做"先出旧的、后台再刷": 进页之后数据再变一次, 条目挪位, 焦点那一格就对不上左栏.
 * - 同一天同一时区的请求在途时, 后来者合流 (预取还没回来就进了页, 接着等同一个请求). 请求挂在 [scope] 上,
 *   调用方取消了也会跑完、落进缓存.
 * - 失败不缓存.
 */
internal class RecentAiringScheduleCache(
    private val scope: CoroutineScope,
    private val maxAge: Duration,
    private val clock: Clock,
    private val fetch: suspend (today: LocalDate, timeZone: TimeZone) -> List<AiringScheduleForDate>,
) {
    private class Entry(
        val today: LocalDate,
        val timeZoneId: String,
        val value: List<AiringScheduleForDate>,
        val fetchedAt: Instant,
    )

    private class InFlight(val today: LocalDate, val timeZoneId: String, val job: Deferred<Entry>)

    private val latest = MutableStateFlow<Entry?>(null)
    private val mutex = Mutex()

    /** 在途的那一个请求. 只在 [mutex] 里读写. */
    private var inFlight: InFlight? = null

    /** 还新鲜的那份; 没有或已过期 = `null`. */
    fun peek(today: LocalDate, timeZone: TimeZone): List<AiringScheduleForDate>? = freshEntry(today, timeZone)?.value

    /** 丢掉缓存: 用户要求刷新时, 下一次必定走网络. */
    fun invalidate() {
        latest.value = null
    }

    /** 先出新鲜的缓存 (有的话), 之后每 [maxAge] 拉一次; 内容没变不重复发. */
    fun flow(today: LocalDate, timeZone: TimeZone): Flow<List<AiringScheduleForDate>> = flow {
        var last = freshEntry(today, timeZone)
        if (last != null) emit(last.value)
        while (true) {
            // 缓存那份到期了再拉; 没有缓存就立即拉
            last?.let { delay(maxAge - (clock.now() - it.fetchedAt)) }
            val fresh = fetchShared(today, timeZone)
            if (fresh.value != last?.value) emit(fresh.value)
            last = fresh
        }
    }

    private fun freshEntry(today: LocalDate, timeZone: TimeZone): Entry? =
        latest.value?.takeIf { it.today == today && it.timeZoneId == timeZone.id && clock.now() - it.fetchedAt < maxAge }

    private suspend fun fetchShared(today: LocalDate, timeZone: TimeZone): Entry {
        val job = mutex.withLock {
            inFlight?.takeIf { it.today == today && it.timeZoneId == timeZone.id && it.job.isActive }?.job
                ?: scope.async {
                    Entry(today, timeZone.id, fetch(today, timeZone), clock.now()).also { latest.value = it }
                }.also { inFlight = InFlight(today, timeZone.id, it) }
        }
        return job.await()
    }
}
