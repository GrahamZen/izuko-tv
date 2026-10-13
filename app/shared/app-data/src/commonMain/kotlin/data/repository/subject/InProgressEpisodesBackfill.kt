/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 给一批条目补分集 (收藏列表接口只给条目不给分集): 同时最多 [concurrency] 个请求, 每补完 [flushEvery] 部把攒下的分集
 * 一次写进库 ([save]), 剩下的最后一起写.
 *
 * 一部一部串着补的话, 镜像慢时一部就要几十秒, 六十多部在看要十几分钟; 每部各写一次库, 「继续观看」那一行又会跟着新进度
 * 重排六十多次. 某一部取失败只跳过它 ([onFailed]), 下次刷新再补; [save] 失败照常抛出.
 */
internal suspend fun <T> backfillEpisodesConcurrently(
    subjectIds: List<Int>,
    fetch: suspend (subjectId: Int) -> List<T>,
    save: suspend (List<T>) -> Unit,
    onFetched: (subjectId: Int, count: Int) -> Unit = { _, _ -> },
    onFailed: (subjectId: Int, error: Exception) -> Unit = { _, _ -> },
    concurrency: Int = IN_PROGRESS_EPISODES_CONCURRENCY,
    flushEvery: Int = IN_PROGRESS_EPISODES_FLUSH_EVERY,
) {
    require(concurrency > 0) { "concurrency must be positive" }
    require(flushEvery > 0) { "flushEvery must be positive" }
    if (subjectIds.isEmpty()) return
    coroutineScope {
        val results = Channel<List<T>>(Channel.UNLIMITED)
        val permits = Semaphore(concurrency)
        val workers = subjectIds.map { subjectId ->
            launch {
                permits.withPermit {
                    val episodes = try {
                        fetch(subjectId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onFailed(subjectId, e)
                        return@withPermit
                    }
                    onFetched(subjectId, episodes.size)
                    results.send(episodes)
                }
            }
        }
        launch {
            workers.joinAll()
            results.close()
        }
        val pending = ArrayList<T>()
        var collected = 0
        for (episodes in results) {
            pending += episodes
            collected++
            if (collected % flushEvery == 0 && pending.isNotEmpty()) {
                save(pending.toList())
                pending.clear()
            }
        }
        if (pending.isNotEmpty()) save(pending)
    }
}

/** 补分集时同时发几个请求. */
private const val IN_PROGRESS_EPISODES_CONCURRENCY = 4

/** 补够几部写一次库. */
private const val IN_PROGRESS_EPISODES_FLUSH_EVERY = 8
