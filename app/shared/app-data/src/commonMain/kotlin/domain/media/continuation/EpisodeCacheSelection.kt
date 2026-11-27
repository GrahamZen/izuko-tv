/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.repository.media.PlaybackLineageRepository
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.awaitCompletedResults
import me.him188.ani.app.domain.media.fetch.isFinal
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 选源时缓存那一步: 这一集有缓存时挑一条.
 *
 * - 下完的缓存先播;
 * - 只有没下完的 (BT 边下边播): 设置 ([playUnfinishedFirst]) 开着或从缓存页点进来时播它, 否则不抢开播 ([Choice.Deferred]),
 *   交给后面的线索与自动选源, 都没有才播它;
 * - 同一类里线索上的版本优先, 其余按选源器的排序. 选了哪条缓存都不改线索 (见 PlaybackLineageExtension).
 *
 * 先看本机有没有这一集的缓存记录 ([EpisodeCacheRecords], 只读元数据): 没有就不等本地缓存源; 有才等它查完 (平时毫秒级).
 * 没下完不能播的缓存 (在线源的 m3u8) 被选源器排除, 不在候选里.
 *
 * @param finishedCaches 这一集下完的缓存在选源器里的 mediaId (认这一集同 [EpisodeCacheRecords], 见 [finishedCacheMediaIds])
 * @param playUnfinishedFirst 设置「没下完的 BT 缓存先边下边播」([me.him188.ani.app.data.models.preference.MediaCacheSettings.playUnfinishedCacheFirst])
 */
class EpisodeCacheSelection(
    private val repository: PlaybackLineageRepository,
    private val continuation: EpisodeContinuation,
    private val cacheRecords: EpisodeCacheRecords,
    private val finishedCaches: suspend (subjectId: Int, episode: EpisodeInfo) -> Set<String>,
    private val playUnfinishedFirst: suspend () -> Boolean,
) {
    sealed interface Choice {
        /** 选上了一条缓存 (或别人已经选好了) */
        data object Selected : Choice

        /** 这一集没有能播的缓存 */
        data object None : Choice

        /**
         * 有缓存记录, 但缓存源或选源器在限时内没准备好, 拿不准 (没下完的 BT 缓存要等 BT 服务连上才查得出来).
         * @property cachesAllowed 后面的自动选源能不能先选缓存: 设置不让没下完的先播、又没有已知下完的缓存时不能
         */
        data class Unknown(val cachesAllowed: Boolean) : Choice

        /** 只有没下完的缓存, 这回先不播; 后面的步骤跳过它们, 都没选上才播 [unfinished] 的第一条 (线索上的版本排前面) */
        data class Deferred(val unfinished: List<Media>) : Choice
    }

    /** @param fromCachePage 从缓存页点进来的 (见 [me.him188.ani.app.domain.media.cache.CachePlayRequests]): 没下完的也播 */
    suspend fun select(subjectId: Int, episode: EpisodeInfo, session: MediaFetchSession, selector: MediaSelector, fromCachePage: Boolean): Choice {
        val caches = playableCaches(subjectId, episode, session, selector) ?: return Choice.Unknown(
            cachesAllowed = fromCachePage || playUnfinishedFirst() || finishedIds(subjectId, episode).isNotEmpty(),
        )
        if (caches.isEmpty()) return Choice.None
        val finishedIds = finishedIds(subjectId, episode)
        val (finished, unfinished) = caches.partition { it.mediaId in finishedIds }
        val lineage = repository.lineageOf(subjectId)
        val choice = preferred(finished, lineage) ?: preferred(unfinished, lineage)?.takeIf { fromCachePage || playUnfinishedFirst() } ?: run {
            logger.info { "Episode ${episode.sort} of subject $subjectId only has unfinished caches, trying other sources first" }
            return Choice.Deferred(unfinished.sortedByDescending { cache -> lineage != null && continuation.continues(lineage, cache) })
        }
        if (selector.selectAutomatically(choice, expectedSelection = null) != null) {
            logger.info { "Selected the ${if (choice in finished) "finished" else "unfinished"} cache of episode ${episode.sort} of subject $subjectId: ${choice.mediaId}" }
        }
        return Choice.Selected
    }

    private suspend fun finishedIds(subjectId: Int, episode: EpisodeInfo): Set<String> =
        withTimeoutOrNull(FINISHED_TIMEOUT) { finishedCaches(subjectId, episode) }.orEmpty()

    private fun preferred(caches: List<Media>, lineage: PlaybackLineage?): Media? =
        lineage?.let { caches.firstOrNull { cache -> continuation.continues(it, cache) } } ?: caches.firstOrNull()

    /** 这一集能播的缓存 (不等网络); 拿不准时为 null. */
    private suspend fun playableCaches(subjectId: Int, episode: EpisodeInfo, session: MediaFetchSession, selector: MediaSelector): List<Media>? {
        val cacheSources = session.mediaSourceResults.filter { it.kind == MediaSourceKind.LocalCache }
        if (cacheSources.isEmpty() || !cacheRecords.hasRecord(subjectId, episode)) return emptyList()
        withTimeoutOrNull(CACHE_TIMEOUT) { cacheSources.forEach { it.awaitCompletedResults() } } ?: run {
            val pending = cacheSources.filter { !it.state.value.isFinal }.map { it.mediaSourceId }
            logger.info { "Episode ${episode.sort} of subject $subjectId has a cache, but $pending did not finish in $CACHE_TIMEOUT" }
            return null
        }
        val candidates = candidatesOf(session, selector, SELECTOR_TIMEOUT) ?: run {
            logger.info { "Episode ${episode.sort} of subject $subjectId has a cache, but the selector was not ready in $SELECTOR_TIMEOUT" }
            return null
        }
        return candidates.filter { it.kind == MediaSourceKind.LocalCache }
    }

    private companion object {
        val logger = logger<EpisodeCacheSelection>()

        /** 有缓存记录时等本地缓存的数据源查完 (平时毫秒级) */
        val CACHE_TIMEOUT = 2.seconds

        /** 等选源器的上下文 (剧集、完结状态) 准备好 */
        val SELECTOR_TIMEOUT = 10.seconds

        /** 读各缓存的状态 (一起读, 每条最多 1 秒); 没下完的 BT 缓存要等 BT 服务, 读不到的当没下完 */
        val FINISHED_TIMEOUT = 2.seconds
    }
}

/**
 * [episode] 这一集下完的缓存在选源器里的 mediaId (拼法同 [MediaDownloadManager.unplayableCacheMediaIds]: `存储:原资源:集`).
 * 认这一集的判据同 [EpisodeCacheRecords] ([isOfEpisode]: 记录没写条目、剧集 ID 变了按序号也算), 跟候选里的缓存一致.
 * 各条一起读状态, 每条最多等 [perCache] (没下完的 BT 缓存要等 BT 服务), 读不到的不算.
 */
internal suspend fun MediaDownloadManager.finishedCacheMediaIds(subjectId: Int, episode: EpisodeInfo, perCache: Duration = 1.seconds): Set<String> = coroutineScope {
    storages.flatMap { storage ->
        storage.listFlow.first()
            .filter { it.metadata.isOfEpisode(subjectId, episode) }
            .map { cache ->
                async {
                    "${storage.mediaSourceId}:${cache.origin.mediaId}:${cache.metadata.episodeId}"
                        .takeIf { withTimeoutOrNull(perCache) { cache.state.first() } == MediaCacheState.COMPLETED }
                }
            }
    }.awaitAll().filterNotNullTo(HashSet())
}
