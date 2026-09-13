/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.persistent.database.dao.WebSearchSessionCacheDao
import me.him188.ani.app.data.persistent.database.dao.WebSearchSessionCacheEntity
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.domain.mediasource.web.WebSearchEpisodeInfo
import me.him188.ani.app.domain.mediasource.web.WebSearchSubjectInfo
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration

/**
 * Web 源搜索结果的缓存.
 *
 * 由 `SelectorMediaSource` 在真实搜索成功后写入 ([addCache]), 并在下次搜索前读取 ([getCache]):
 * 缓存的条目页面剧集列表包含请求的剧集时 (典型场景: 切集), 无需发起网络请求.
 *
 * 缓存按发起查询的条目隔离: 写入、读取与清除都以 `requesterSubjectId` 为口径.
 *
 * ## 有效期
 *
 * 每行的 TTL 在写入时确定, 取数据源配置 (`SelectorSearchConfig.searchCacheTtl`) 与
 * 用户设置 (`MediaSelectorSettings.webSearchCacheTtl`) 中的较小者. 过期行不会被读取.
 *
 * 清理时机:
 * - 进入/退出播放页: [purgeExpired] 清除所有已过期的行.
 *   未过期的行会保留, 因此短暂退出后重进播放页仍可复用;
 * - 手动重新查询: [clearByRequestedSubject] (全部数据源) 或
 *   [clearByRequestedSubjectAndSource] (单个数据源) 立即清除该条目的缓存.
 */
class SelectorMediaSourceEpisodeCacheRepository(
    private val dao: WebSearchSessionCacheDao,
    /**
     * 用户设置的缓存有效期, 通常来自 `MediaSelectorSettings.webSearchCacheTtl`.
     */
    private val userTtlFlow: Flow<Duration>,
) : Repository() {

    private suspend fun userTtl(): Duration = userTtlFlow.first()

    suspend fun addCache(
        requesterSubjectId: Int?,
        mediaSourceId: String,
        subjectName: String,
        subjectInfo: WebSearchSubjectInfo,
        episodeInfos: List<WebSearchEpisodeInfo>,
        sourceCacheTtl: Duration,
    ) = withContext(defaultDispatcher) {
        val ttl = minOf(sourceCacheTtl, userTtl())
        if (ttl <= Duration.ZERO) {
            // 缓存被禁用. 同时删除该页面可能残留的旧行 (例如用户刚把 TTL 改为 0).
            dao.deletePage(requesterSubjectId, mediaSourceId, subjectName, subjectInfo.fullUrl)
            return@withContext
        }
        val now = currentTimeMillis()
        dao.replacePage(
            requesterSubjectId, mediaSourceId, subjectName, subjectInfo.fullUrl,
            episodeInfos.map {
                it.toEntity(
                    requesterSubjectId, mediaSourceId, subjectName, subjectInfo,
                    cachedAt = now,
                    expiresAt = now + ttl.inWholeMilliseconds,
                )
            },
        )
    }

    /**
     * 清除该条目在所有数据源上的缓存.
     */
    suspend fun clearByRequestedSubject(requesterSubjectId: Int) = withContext(defaultDispatcher) {
        dao.deleteByRequestedSubject(requesterSubjectId)
    }

    /**
     * 清除该条目在单个数据源上的缓存. 用于只重启一个数据源的场景, 不影响其他数据源.
     */
    suspend fun clearByRequestedSubjectAndSource(
        requesterSubjectId: Int,
        mediaSourceId: String,
    ) = withContext(defaultDispatcher) {
        dao.deleteByRequestedSubjectAndSource(requesterSubjectId, mediaSourceId)
    }

    /**
     * 清除所有已过期的行. 在进入/退出播放页时调用.
     */
    suspend fun purgeExpired() = withContext(defaultDispatcher) {
        dao.deleteExpired(currentTimeMillis())
    }

    /**
     * 返回该条目该查询名下缓存的所有未过期的条目页面, 每个页面附带其全部剧集 (保持页面上的顺序).
     */
    suspend fun getCache(
        requesterSubjectId: Int?,
        mediaSourceId: String,
        subjectName: String,
    ): List<WebSearchCache> =
        withContext(defaultDispatcher) {
            dao.filterBySubjectName(requesterSubjectId, mediaSourceId, subjectName, currentTimeMillis())
                .groupBy { it.subjectUrl } // preserves encounter (insertion) order
                .map { (_, rows) -> rows.toWebSearchCache() }
        }

    /**
     * 同 [getCache], 但每个页面只读回可能与请求剧集相关的行 (见 [WebSearchSessionCacheDao.filterForEpisode]).
     * 用于切集: 长番的条目页有几千集, 整页读回内存再找当前集太重.
     *
     * 仅适用于请求普通剧集且按集号过滤的数据源, 此时匹配结果与 [getCache] 完全一致: 读漏的只有集号是别的数字的行,
     * 它们既匹配不上也出不了资源. 但列表变短了, 而匹配规则里有一条看「整页只有这一条」(`matchingEpisodeSortOf`),
     * 所以筛完只剩一条没有集号的行、整页却不止一条时, 那一页整页读回.
     */
    suspend fun getCacheForEpisode(
        requesterSubjectId: Int?,
        mediaSourceId: String,
        subjectName: String,
        episodeSort: EpisodeSort,
        episodeEp: EpisodeSort?,
    ): List<WebSearchCache> = withContext(defaultDispatcher) {
        val now = currentTimeMillis()
        val (rows, pageSizes) = dao.filterForEpisodeWithPageSizes(
            requesterSubjectId, mediaSourceId, subjectName,
            sort = episodeSort.toString(),
            ep = episodeEp?.toString(),
            now = now,
        )
        val pageSizeByUrl = pageSizes.associate { it.subjectUrl to it.rowCount }
        rows.groupBy { it.subjectUrl } // preserves encounter (insertion) order
            .map { (url, pageRows) ->
                val single = pageRows.singleOrNull()
                val needsWholePage = single != null && (pageSizeByUrl[url] ?: 1) > 1 &&
                        (single.episodeSortOrEp == null || single.episodeSortOrEp is EpisodeSort.Unknown)
                if (needsWholePage) {
                    dao.filterByPage(requesterSubjectId, mediaSourceId, subjectName, url, now)
                        .ifEmpty { pageRows }
                        .toWebSearchCache()
                } else {
                    pageRows.toWebSearchCache()
                }
            }
    }
}

/**
 * 同一页面的行 (非空, 保持页面上的顺序) 还原成一个 [WebSearchCache].
 */
private fun List<WebSearchSessionCacheEntity>.toWebSearchCache(): WebSearchCache {
    val first = first()
    return WebSearchCache(
        webSubjectInfo = WebSearchSubjectInfo(
            internalId = first.subjectInternalId,
            name = first.subjectPageName,
            fullUrl = first.subjectUrl,
            partialUrl = first.subjectPartialUrl,
            origin = null,
        ),
        webEpisodeInfos = map { it.toWebSearchEpisodeInfo() },
    )
}

data class WebSearchCache(
    val webSubjectInfo: WebSearchSubjectInfo,
    val webEpisodeInfos: List<WebSearchEpisodeInfo>,
)

private fun WebSearchEpisodeInfo.toEntity(
    requesterSubjectId: Int?,
    mediaSourceId: String,
    subjectName: String,
    subjectInfo: WebSearchSubjectInfo,
    cachedAt: Long,
    expiresAt: Long,
): WebSearchSessionCacheEntity {
    return WebSearchSessionCacheEntity(
        requesterSubjectId = requesterSubjectId,
        mediaSourceId = mediaSourceId,
        subjectName = subjectName,
        subjectPageName = subjectInfo.name,
        subjectInternalId = subjectInfo.internalId,
        subjectUrl = subjectInfo.fullUrl,
        subjectPartialUrl = subjectInfo.partialUrl,
        channel = channel.orEmpty(),
        episodeName = name,
        episodeSortOrEp = episodeSortOrEp,
        playUrl = playUrl,
        cachedAt = cachedAt,
        expiresAt = expiresAt,
    )
}

private fun WebSearchSessionCacheEntity.toWebSearchEpisodeInfo(): WebSearchEpisodeInfo {
    return WebSearchEpisodeInfo(
        channel = channel.ifEmpty { null },
        name = episodeName,
        episodeSortOrEp = episodeSortOrEp,
        playUrl = playUrl,
    )
}
