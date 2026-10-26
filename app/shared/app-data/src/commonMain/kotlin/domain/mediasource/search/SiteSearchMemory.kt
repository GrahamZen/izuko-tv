/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 网页类数据源 (网页、规则、JSON API) 站点回应的内存记忆, **跨条目共用**: 搜索缓存 (`SelectorMediaSourceEpisodeCacheRepository`)
 * 按发起查询的条目分开记, 而同系列各季多拿同一个系列名搜 ([SubjectSearchPlan.keywords]), 换一季时站点的搜索结果与条目页都是同一份.
 *
 * 按数据源与请求记, 有效期不超过搜索缓存 ([effectiveTtl]: 数据源配置与用户设置中较短的), 为 0 时不记. 失败 (返回 `null`) 不记.
 * - 搜索结果 ([searchResults]) 最多记 [SEARCH_RESULTS_TTL]: 站点新开了一季的页面, 记久了就一直看不到;
 * - 条目页 ([page]) 记满有效期, 取出时还要新 (调用方给的 `isFresh`, 含已上映的最新一集才算), 否则重新打开.
 * 手动重新查询时清空 ([clear]).
 */
class SiteSearchMemory(
    private val effectiveTtl: suspend (sourceTtl: Duration) -> Duration,
) {
    private class Entry(val value: Any, val expiresAt: Long)

    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, Entry>()

    /** 数据源 [sourceId] 上搜索请求 [request] (地址或关键词) 的结果; 没记过或过期时调 [fetch] 并记下. */
    suspend fun <T : Any> searchResults(sourceId: String, request: String, sourceTtl: Duration, fetch: suspend () -> T?): T? =
        remember("$sourceId search $request", minOf(effectiveTtl(sourceTtl), SEARCH_RESULTS_TTL), { true }, fetch)

    /** 数据源 [sourceId] 上条目页 [url] 的内容; 没记过、过期或 [isFresh] 不认时调 [fetch] 并记下. */
    suspend fun <T : Any> page(
        sourceId: String,
        url: String,
        sourceTtl: Duration,
        isFresh: (T) -> Boolean,
        fetch: suspend () -> T?,
    ): T? = remember("$sourceId page $url", effectiveTtl(sourceTtl), isFresh, fetch)

    private suspend fun <T : Any> remember(key: String, ttl: Duration, accept: (T) -> Boolean, fetch: suspend () -> T?): T? {
        if (ttl <= Duration.ZERO) return fetch()
        val now = currentTimeMillis()
        synchronized(lock) {
            val entry = entries[key]
            if (entry != null && entry.expiresAt > now) {
                // 同一个 key 只记一种结果 (key 里写明了请求的种类)
                @Suppress("UNCHECKED_CAST")
                val value = entry.value as T
                if (accept(value)) return value
            }
        }
        val value = fetch() ?: return null
        synchronized(lock) {
            entries.remove(key)
            entries[key] = Entry(value, now + ttl.inWholeMilliseconds)
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        }
        return value
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    companion object {
        /** 搜索结果最多记这么久: 够换季时共用, 又不至于漏掉站点刚开的新页面 */
        val SEARCH_RESULTS_TTL: Duration = 10.minutes

        /** 最多记这么多份 (先记的先丢): 条目页可能有几千集 */
        private const val MAX_ENTRIES = 300
    }
}
