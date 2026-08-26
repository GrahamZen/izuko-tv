/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **按 key 去重的"过期就重取"**: 同一个 key 的并发调用只有一个真的去取, 其余等它完成后重查一次,
 * 发现数据已经新鲜就直接返回.
 *
 * 用它而不是 in-flight 合流表 (`Deferred` + 共享 scope) 的理由: 合流表要一个仓库级
 * [kotlinx.coroutines.CoroutineScope], 首个调用者被取消时任务归属就变得难说清; 这里每个调用者
 * 仍跑在自己的协程里, 取消语义与"各自直接取数"完全一致, 而"等到了发现已新鲜"同样达成去重.
 *
 * 锁**不分桶**: 撞桶会让一个 key 的前台取数排在另一个无关 key 的后台预取后面.
 * 锁表**只增不删**: [Mutex] 很轻, 而按 key 删要处理"删的瞬间正好有人来拿"的竞态, 不值当.
 */
internal class StaleKeyedFetcher<K> {
    private val locks = mutableMapOf<K, Mutex>()
    private val locksGuard = Mutex()

    /**
     * @param isFresh 在**临界区内**重查一次: 前一个持锁者可能刚把数据取回来写好了.
     *   为 true 就跳过 [fetch].
     * @param fetch 真正的取数与落库. 抛出的异常原样传给本次调用者, 不影响后续调用重试.
     */
    suspend fun fetchIfStale(key: K, isFresh: suspend () -> Boolean, fetch: suspend () -> Unit) {
        val lock = locksGuard.withLock { locks.getOrPut(key) { Mutex() } }
        lock.withLock {
            if (isFresh()) return
            fetch()
        }
    }
}
