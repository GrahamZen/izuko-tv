/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.media.fetch.BatchedSizedSource
import me.him188.ani.app.domain.media.fetch.batchedSizedSource
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.logging.Logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * 一条关键词链里, 搜索单个关键词时可用的上下文.
 */
interface KeywordSearchScope {
    /**
     * 向站点发起搜索前调用. 本条链里已经向站点发起过搜索时, 先等待请求间隔; 命中缓存的搜索不调用它, 也就不等待.
     */
    suspend fun beforeOnlineSearch()

    /**
     * 这个词还没搜完时先交出已经拿到的一部分资源 (一个条目页的), 之后这个词的搜索结果里同一个资源不再发.
     * [keywordSearchChain] 的 `emitPagesEarly` 关着时不做事, 资源照旧等这个词搜完一起发.
     */
    suspend fun emitEarly(media: List<DefaultMedia>) {}
}

/**
 * 自动匹配的关键词链. 在线站点类数据源 (Selector, JSON API) 的 `fetch` 共用.
 *
 * [primary] 每一个都搜, 结果全部返回; 它们都没搜到名字能对上的条目时, 再依次尝试 [fallback], 搜到为止.
 *
 * [remembered] 是其中上次搜到过条目页的 ([rememberedKeywords]), 先于其余关键词按同样的规则搜; 它们搜到了就结束, 其余的不再搜.
 * 「更多」这类搜不到的关键词没有缓存行, 切集时不会再为它发请求.
 *
 * 每个关键词的结果一搜到就发出, 不等整条链结束; 按批发 ([BatchedSizedSource]): 提前交出的一页一批, 一个词剩下的一批.
 * 两次向站点发起的搜索之间等待 [requestInterval]
 * (由 [search] 通过 [KeywordSearchScope.beforeOnlineSearch] 触发); 命中搜索缓存的不发请求也不等待.
 *
 * @param matches 一次搜索的结果里是否有名字能对上请求条目的.
 * @param emitPagesEarly 为 true 时 [search] 可以经 [KeywordSearchScope.emitEarly] 先发出一个词的部分资源 (网页类数据源每开完一个条目页交一次, 开播时不用等这个词的页全部开完)
 * @param search 搜索一个关键词, 返回该关键词搜到的全部资源. 先查缓存, 未命中时调用 [KeywordSearchScope.beforeOnlineSearch] 再向站点请求.
 */
fun keywordSearchChain(
    primary: List<String>,
    fallback: List<String>,
    remembered: List<String>,
    requestInterval: Duration,
    matches: (List<DefaultMedia>) -> Boolean,
    emitPagesEarly: Boolean = false,
    search: suspend KeywordSearchScope.(keyword: String) -> List<DefaultMedia>,
): BatchedSizedSource<DefaultMedia> {
    val finishedState = MutableStateFlow(false)
    val totalSizeState = MutableStateFlow<Int?>(null)
    suspend fun searchAll(send: suspend (List<DefaultMedia>) -> Unit) {
        var total = 0
        var matched = false
        // 这个词已经提前发出的资源 (见 KeywordSearchScope.emitEarly); 几个条目页同时取时会同时来交, 要锁着
        val sentEarly = HashSet<String>()
        val sentEarlyLock = Mutex()
        val scope = object : KeywordSearchScope {
            private var searchedOnline = false
            override suspend fun beforeOnlineSearch() {
                if (searchedOnline) delay(requestInterval)
                searchedOnline = true
            }

            override suspend fun emitEarly(media: List<DefaultMedia>) {
                if (!emitPagesEarly) return
                sentEarlyLock.withLock {
                    val fresh = media.filter { sentEarly.add(it.mediaId) }
                    if (fresh.isNotEmpty()) send(fresh)
                }
            }
        }
        suspend fun searchAndEmit(name: String) {
            sentEarly.clear()
            val media = scope.search(name)
            if (!matched && matches(media)) matched = true
            total += media.size
            val rest = media.filter { it.mediaId !in sentEarly }
            if (rest.isNotEmpty()) send(rest)
        }

        val primarySet = primary.toHashSet()
        val rememberedSet = remembered.toHashSet()
        suspend fun searchInOrder(names: List<String>) {
            for (name in names) {
                // 主关键词全搜, 回退关键词搜到为止
                if (matched && name !in primarySet) break
                searchAndEmit(name)
            }
        }
        searchInOrder(remembered)
        if (!matched) searchInOrder((primary + fallback).filter { it !in rememberedSet })
        totalSizeState.value = total
        finishedState.value = true
    }

    // 提前发出的资源从站点源开页的线程里来, 要用 channelFlow
    val batches: Flow<List<DefaultMedia>> =
        if (emitPagesEarly) channelFlow { searchAll { send(it) } } else flow { searchAll { emit(it) } }
    return batchedSizedSource(batches, finishedState, totalSizeState)
}

/**
 * [keywords] 中上次搜到过条目页的, 即搜索缓存里有它的未过期行的, 保持原顺序.
 * 有行只说明搜到过条目页, 不说明名字对上了, 所以只用来决定搜索顺序. 读缓存失败按没有记忆处理.
 */
suspend fun SelectorMediaSourceEpisodeCacheRepository.rememberedKeywords(
    subjectId: Int?,
    mediaSourceId: String,
    keywords: List<String>,
    logger: Logger,
): List<String> {
    val cached = try {
        getCachedSubjectNames(subjectId, mediaSourceId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "MediaSource '$mediaSourceId': failed to read cached search keywords" }
        return emptyList()
    }
    if (cached.isEmpty()) return emptyList()
    val cachedSet = cached.toHashSet()
    return keywords.filter { it in cachedSet }
}

/**
 * 请求中已上映的最新一集正片. 剧集上映日期全部未知时为 `null`.
 *
 * 用作搜索缓存的新鲜度探针: 缓存的条目页面包含这一集才算没过时.
 */
fun MediaFetchRequest.latestAiredEpisode(): MediaFetchRequest.Episode? {
    val today = PackedDate.now()
    return episodes.asSequence()
        .filter { it.sort is EpisodeSort.Normal && it.airDate.isValid && it.airDate <= today }
        .maxByOrNull { it.sort }
}
