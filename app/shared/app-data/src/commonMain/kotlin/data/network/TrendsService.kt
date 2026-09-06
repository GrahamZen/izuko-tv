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
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.trending.TrendingSubjectInfo
import me.him188.ani.app.data.models.trending.TrendsInfo
import me.him188.ani.app.data.network.mapper.orBangumiPlaceholder
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.runWrappingExceptionAsLoadResult
import me.him188.ani.app.tools.paging.SinglePagePagingSource
import me.him188.ani.datasources.bangumi.next.apis.TrendingBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextGetTrendingSubjects200Response
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectType
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.error
import kotlin.coroutines.CoroutineContext

class TrendsRepository(
    private val trendsApi: ApiInvoker<TrendingBangumiNextApi>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_
) : Repository() {
    /**
     * 热度榜的一页.
     *
     * @param offset 从第几名起. 探索页顶上的 hero 轮播放的就是 `offset = 0` 那一页的**全部**
     *   [TRENDING_LIMIT] 条, 所以推荐区那一行必须从 [TRENDING_LIMIT] 名往后取 —— 否则整行都是
     *   用户刚在轮播里转过一遍的 (2026-09-07 用户反馈"重复太多"). 榜一共 1000 条 (实测), 往后
     *   翻几十名依然是"大家最近在看".
     */
    suspend fun getTrendsInfo(limit: Int = TRENDING_LIMIT, offset: Int = 0): TrendsInfo {
        return withContext(ioDispatcher) {
            trendsApi {
                getTrendingSubjects(BangumiNextSubjectType.Anime, limit = limit, offset = offset)
                    .body().toTrendsInfo()
            }
        }
    }

    // bangumi 的每日热度榜
    fun trendsInfoPager(): Flow<PagingData<TrendsInfo>> {
        return Pager(defaultPagingConfig) {
            SinglePagePagingSource<Unit, TrendsInfo> {
                runWrappingExceptionAsLoadResult<Unit, TrendsInfo> {
                    val trendsInfo = withContext(ioDispatcher) {
                        trendsApi {
                            getTrendingSubjects(BangumiNextSubjectType.Anime, limit = TRENDING_LIMIT).body()
                                .toTrendsInfo()
                        }
                    }
                    PagingSource.LoadResult.Page(
                        listOf(trendsInfo),
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

    companion object {
        /**
         * 热度榜一页给多少条.
         *
         * **同时也是"用户已经在屏幕上看过的那一批"的边界**: 探索页的 hero 轮播就是拿
         * [trendsInfoPager] 这一页渲染的, 一条不落 (轮播圆点上限恰好也是 20). 推荐区想避开
         * 重复就得知道这个数.
         */
        const val TRENDING_LIMIT = 20
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
