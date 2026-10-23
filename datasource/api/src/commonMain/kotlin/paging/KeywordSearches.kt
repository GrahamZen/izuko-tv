/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.paging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

private val logger = logger<KeywordSearchFailures>()

/**
 * 同一个源用几个名字 (条目的各个名字) 各搜一次 ([searches] 是关键词与它的结果), 合成一份结果.
 * 某个关键词搜失败 (比如站点搜索不认其中的某个符号而报错) 只丢掉它那一路并记日志, 别的关键词搜到的照常给出;
 * 全部失败才算这个源失败, 抛出第一个错误.
 */
fun <T> mergeKeywordSearches(
    mediaSourceId: String,
    searches: List<Pair<String, SizedSource<T>>>,
): SizedSource<T> {
    if (searches.size <= 1) return searches.singleOrNull()?.second ?: EmptyKeywordSearch()
    val done = MutableStateFlow(false)
    return object : SizedSource<T> {
        override val results: Flow<T> = flow {
            val failures = KeywordSearchFailures(searches.size)
            emitAll(
                searches.map { (keyword, source) ->
                    source.results.catch { e ->
                        if (e is CancellationException) throw e
                        logger.warn(e) { "$mediaSourceId: search for '$keyword' failed, keeping the other keywords' results" }
                        failures.add(e)
                    }
                }.merge(),
            )
            failures.allFailed()?.let { throw it }
        }.onCompletion { done.value = true }
        override val finished: Flow<Boolean> = done
        override val totalSize: Flow<Int?> = flowOf(null)
    }
}

private class KeywordSearchFailures(private val total: Int) {
    private val lock = Mutex()
    private var count = 0
    private var first: Throwable? = null

    suspend fun add(e: Throwable) = lock.withLock {
        count++
        if (first == null) first = e
    }

    /** 每一路都失败了时返回第一个错误 */
    suspend fun allFailed(): Throwable? = lock.withLock { first.takeIf { count == total } }
}

private class EmptyKeywordSearch<T> : SizedSource<T> {
    override val results: Flow<T> = flowOf()
    override val finished: Flow<Boolean> = flowOf(true)
    override val totalSize: Flow<Int?> = flowOf(0)
}
