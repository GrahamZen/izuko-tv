/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.PagingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.models.trending.TrendingSubjectInfo
import me.him188.ani.app.data.models.trending.TrendsInfo
import me.him188.ani.app.data.network.mapper.orBangumiPlaceholder
import me.him188.ani.app.data.persistent.JsonFileCache
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.runWrappingExceptionAsLoadResult
import me.him188.ani.app.tools.paging.SinglePagePagingSource
import me.him188.ani.datasources.bangumi.next.apis.TrendingBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextGetTrendingSubjects200Response
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectType
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.hours

class TrendsRepository(
    private val trendsApi: ApiInvoker<TrendingBangumiNextApi>,
    /** 热度榜头一页落盘的文件, 见 [firstPage]; `null` = 只在内存里共用. */
    cacheFile: SystemPath? = null,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
    private val clock: () -> Long = { currentTimeMillis() },
) : Repository() {
    private val diskCache = cacheFile?.let { JsonFileCache(it, SavedFirstPage.serializer(), ioDispatcher) }
    private val firstPageLock = Mutex()
    private var firstPage: SavedFirstPage? = null

    /**
     * 热度榜的一页.
     *
     * @param offset 从第几名起. 探索页顶上的 hero 轮播放的就是 `offset = 0` 那一页的**全部**
     *   [TRENDING_LIMIT] 条, 所以推荐区那一行必须从 [TRENDING_LIMIT] 名往后取 —— 否则整行都是
     *   用户刚在轮播里转过一遍的 (2026-09-07 用户反馈"重复太多"). 榜一共 1000 条 (实测), 往后
     *   翻几十名依然是"大家最近在看".
     */
    suspend fun getTrendsInfo(limit: Int = TRENDING_LIMIT, offset: Int = 0): TrendsInfo {
        if (limit == TRENDING_LIMIT && offset == 0) return firstPage()
        return fetch(limit, offset)
    }

    // bangumi 的每日热度榜
    fun trendsInfoPager(): Flow<PagingData<TrendsInfo>> {
        return Pager(defaultPagingConfig) {
            SinglePagePagingSource<Unit, TrendsInfo> {
                runWrappingExceptionAsLoadResult<Unit, TrendsInfo> {
                    PagingSource.LoadResult.Page(
                        listOf(firstPage()),
                        null,
                        null,
                    )
                }.also {
                    if (it is PagingSource.LoadResult.Error) {
                        logger.error(it.throwable) { "Failed to load ani trends info." }
                    }
                }
            }
        }.flow
    }

    /**
     * 热度榜头一页 ([TRENDING_LIMIT] 条). 探索页轮播、推荐重算 (躲开轮播在放的)、TV 主屏频道、屏保都要它:
     * [FIRST_PAGE_TTL] 内共用一份, 同时来问的只发一次请求; 并且落盘, 冷启动时轮播不用先等这个请求.
     */
    private suspend fun firstPage(): TrendsInfo = firstPageLock.withLock {
        val cached = firstPage ?: diskCache?.read()?.also { firstPage = it }
        if (cached != null && clock() - cached.fetchedAt in 0 until FIRST_PAGE_TTL.inWholeMilliseconds) {
            return@withLock TrendsInfo(cached.subjects)
        }
        val fetched = fetch(TRENDING_LIMIT, 0)
        val saved = SavedFirstPage(clock(), fetched.subjects)
        firstPage = saved
        diskCache?.write(saved)
        fetched
    }

    private suspend fun fetch(limit: Int, offset: Int): TrendsInfo = withContext(ioDispatcher) {
        trendsApi {
            getTrendingSubjects(BangumiNextSubjectType.Anime, limit = limit, offset = offset)
                .body().toTrendsInfo()
        }
    }

    @Serializable
    private class SavedFirstPage(
        val fetchedAt: Long,
        val subjects: List<TrendingSubjectInfo>,
    )

    companion object {
        /**
         * 热度榜一页给多少条.
         *
         * **同时也是"用户已经在屏幕上看过的那一批"的边界**: 探索页的 hero 轮播就是拿
         * [trendsInfoPager] 这一页渲染的, 一条不落 (轮播圆点上限恰好也是 20). 推荐区想避开
         * 重复就得知道这个数.
         */
        const val TRENDING_LIMIT = 20

        /** 热度榜头一页共用多久, 见 [firstPage]. 榜是按天滚动的, 一小时里前二十名变不了几个. */
        private val FIRST_PAGE_TTL = 1.hours
    }
}

fun BangumiNextGetTrendingSubjects200Response.toTrendsInfo(): TrendsInfo {
    logger<TrendsRepository>().info { "bgm-direct: trending -> ${data.size}" }
    return TrendsInfo(
        subjects = data.map {
            TrendingSubjectInfo(
                it.subject.id,
                it.subject.nameCN.ifEmpty { it.subject.name },
                it.subject.images?.large.orBangumiPlaceholder(),
            )
        },
    )
}
