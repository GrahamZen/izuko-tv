/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **按 key 去重的"过期就重取"**: 同一个 key 的并发调用只有一个真的去取, 其余合流等它;
 * 而取数本身跑在 [scope] 上, **调用者走开不会把它掐掉**.
 *
 * ## 为什么取数必须脱离调用方协程 (2026-09-06)
 *
 * 原先每个调用者在自己的协程里取数, 理由是"取消语义与各自直接取数完全一致"。代价在
 * TV 上被实测抓到: hero 流水线在 `collectLatest` 里解析聚焦卡片, **焦点一挪就取消上游**,
 * 而 `subjectCollectionFlow` 的 fetch 正挂在那条协程上 —— 于是
 *
 * ```
 * 01:21:09  GET /v0/users/-/collections/225462/episodes  CANCELLED
 * 01:21:10  GET /p1/subjects/225462                      200
 * 01:21:11  GET /v0/users/-/collections/225462/episodes  CANCELLED
 * 01:21:36  GET /p1/subjects/225462                      200
 * ```
 *
 * 条目取到了、分集在途, 落库 (upsert) 在两者之后 —— 每次都在写库前作废。表现是
 * **在关联条目行里划过去的条目, 进详情页连一张选集卡骨架都没有**, 而且每次进页都从零重取
 * (「详情页加载非常慢」同一个因)。停留时间越短, 越永远落不了库。
 *
 * 现在换成 in-flight 合流表: 任务归 [scope], 调用者只 `await`。当年放弃合流表的顾虑是
 * "首个调用者被取消时任务归属说不清" —— 那个模糊点正是这里要的答案: **任务归仓库**,
 * 调用者来去自由, 数据照样落库, 下一个消费者进来就发现已经新鲜。
 *
 * [scope] 必须带 `SupervisorJob`: 一个 key 取数失败不能连坐其它 key。没人 `await` 的失败
 * 由 [Deferred] 自己吃掉 (不会变成未捕获异常)。
 *
 * 锁表**只增不删**: [Mutex] 很轻, 而按 key 删要处理"删的瞬间正好有人来拿"的竞态, 不值当。
 */
internal class StaleKeyedFetcher<K>(
    private val scope: CoroutineScope,
) {
    private val locks = mutableMapOf<K, Mutex>()
    private val locksGuard = Mutex()

    /**
     * key -> 在途任务. 读写一律在 [locksGuard] 里且不含挂起工作;
     * 用它而不是 per-key 锁保护, 是因为任务的 `finally` 要能在任意协程上摘除自己。
     */
    private val inFlight = mutableMapOf<K, Deferred<Unit>>()

    /**
     * @param isFresh 在**临界区内**重查一次: 前一个持锁者可能刚把数据取回来写好了.
     *   为 true 就跳过 [fetch].
     * @param fetch 真正的取数与落库, 跑在 [scope] 上. 抛出的异常原样传给**本次**调用者
     *   (合流上来的调用者也会收到同一个异常), 不影响后续调用重试.
     */
    suspend fun fetchIfStale(key: K, isFresh: suspend () -> Boolean, fetch: suspend () -> Unit) {
        val lock = locksGuard.withLock { locks.getOrPut(key) { Mutex() } }
        val task = lock.withLock {
            locksGuard.withLock { inFlight[key] }
                ?: run {
                    if (isFresh()) return
                    // LAZY + 先登记再 start: DEFAULT 起的话 fetch 有可能在登记前就跑完,
                    // finally 里的 remove 扑空, 这个 key 就永远挂着一个已完成的任务
                    val task = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            fetch()
                        } finally {
                            locksGuard.withLock { inFlight.remove(key) }
                        }
                    }
                    locksGuard.withLock { inFlight[key] = task }
                    task.start()
                    task
                }
        }
        // 锁在这之前就放了: 后来的调用者进来会看到 inFlight 里的任务并合流, 不会再发一遍
        task.await()
    }
}
