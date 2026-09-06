/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
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
import androidx.paging.PagingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.recommend.RecommendedItemInfo
import me.him188.ani.app.data.models.recommend.RecommendedSubjectInfo
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionDao
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.runWrappingExceptionAsLoadResult
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import kotlin.coroutines.CoroutineContext

/**
 * 探索页「猜你喜欢」.
 *
 * bangumi 没有"全站推荐流"这种东西 (Ani 的 `/v2/home/recommendations` 是它自己做的, 而且实测已经
 * 开始混广告进来: 负数 subjectId、指向 IP 地址的链接). 这里改成**以自己最近看的条目为种子**,
 * 各取一份 bangumi 的"看过这部的人也看过" (`/p1/subjects/{id}/recs`), 合并去重, 并去掉自己已经
 * 收藏过的 —— 语义上比原来那个更贴「猜你喜欢」, 而且不会有广告.
 */
class RecommendationRepository(
    private val bangumiSubjectApi: ApiInvoker<SubjectBangumiNextApi>,
    private val subjectCollectionDao: SubjectCollectionDao,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_
) : Repository() {
    fun recommendedSubjectsPager(): Flow<PagingData<RecommendedItemInfo>> {
        return Pager(defaultPagingConfig, initialKey = 0) {
            RecommendationPagingSource()
        }.flow
    }

    private inner class RecommendationPagingSource : PagingSource<Int, RecommendedItemInfo>() {
        /**
         * 已经发出去过的条目, **跨页**去重.
         *
         * 种子是"最近更新的收藏", 它的顺序会随着看番实时变 —— 用偏移分页翻它, 同一个种子会在
         * 相邻两页里各出现一次 (真机日志里 526789/541547 就这样), 它的推荐自然也重.
         */
        private val emitted = mutableSetOf<Int>()

        override fun getRefreshKey(state: PagingState<Int, RecommendedItemInfo>): Int? = state.anchorPosition

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, RecommendedItemInfo> {
            val startOffset = params.key ?: 0
            return runWrappingExceptionAsLoadResult {
                withContext(ioDispatcher) { loadPage(startOffset, params.loadSize) }
            }.also {
                if (it is LoadResult.Error) {
                    logger.error(it.throwable) { "Failed to load recommendations." }
                }
            }
        }

        /**
         * 从第 [startOffset] 个种子开始, **一批一批往后要, 直到凑够一页**.
         *
         * 一批固定几个种子是不够的: 推荐里已经收藏过的要全去掉, 而收藏越多去掉得越狠 (真机上
         * 402 部收藏时, 4 个种子的 36 条推荐只剩十来条), 于是探索页推荐区就只有孤零零两行.
         * 更糟的是有的条目一条推荐都没有 —— 整批为空时旧写法直接判到底, 后面的种子再也不看了.
         */
        private suspend fun loadPage(startOffset: Int, loadSize: Int): LoadResult.Page<Int, RecommendedItemInfo> {
            val collected = subjectCollectionDao.mostRecentUpdated(limit = COLLECTED_LOOKBACK, offset = 0)
                .first()
                .mapTo(mutableSetOf()) { it.subjectId }

            val target = loadSize.coerceAtMost(MAX_ITEMS_PER_PAGE)
            val result = mutableListOf<RecommendedItemInfo>()
            var offset = startOffset
            var seedsExhausted = false
            var batches = 0

            while (result.size < target && batches < MAX_SEED_BATCHES_PER_PAGE) {
                val seeds = subjectCollectionDao
                    .mostRecentUpdated(limit = SEEDS_PER_PAGE, offset = offset)
                    .first()
                if (seeds.isEmpty()) {
                    seedsExhausted = true
                    break
                }
                offset += seeds.size
                batches++

                val recommendations = bangumiSubjectApi {
                    coroutineScope {
                        seeds.map { seed -> async { getSubjectRecs(seed.subjectId, limit = RECS_PER_SEED).body().data } }
                            .flatMap { it.await() }
                    }
                }
                val before = result.size
                recommendations.asSequence()
                    .map { it.subject }
                    // 「看过这部的人也看过」不分条目类型, 漫画/游戏/三次元都会混进来 (实测 276792 的
                    // 10 条推荐里有 3 条不是动画). 探索页那一行只放动画.
                    .filter { it.type == BangumiNextSubjectType.Anime }
                    .filter { it.id !in collected && emitted.add(it.id) }
                    .take(target - result.size)
                    .mapTo(result) { subject ->
                        RecommendedSubjectInfo(
                            bangumiId = subject.id,
                            nameCn = subject.nameCN.ifEmpty { subject.name },
                            imageLarge = subject.images?.large ?: "",
                        )
                    }
                logger.info {
                    "bgm-direct: recommendations seeds=${seeds.map { it.subjectId }} -> " +
                            "${recommendations.size} raw, 收下 ${result.size - before} 条 (已有 ${result.size}/$target), " +
                            "collected=${collected.size}"
                }
                if (seeds.size < SEEDS_PER_PAGE) {
                    seedsExhausted = true
                    break
                }
            }

            return LoadResult.Page(
                result,
                prevKey = null, // 只往后翻
                // 只有**种子取完**才是真到底. 某一批一条都没剩下不算 —— 后面的种子还有货
                nextKey = if (seedsExhausted) null else offset,
            )
        }
    }

    private companion object {
        /** 一批用几个种子条目. 每个种子一个请求 (同批并发), 别调大. */
        const val SEEDS_PER_PAGE = 4

        /** 一页最多要几批种子. 凑够就停, 这个上限只防"用户收藏太全, 怎么翻都剩不下几条". */
        const val MAX_SEED_BATCHES_PER_PAGE = 4

        /**
         * 一页最多给几条. Paging 首屏会按 `pageSize * 3` 要 (90 条), 全凑齐得几十个请求;
         * 48 条 = 电视上四行, 已经翻不完了.
         */
        const val MAX_ITEMS_PER_PAGE = 48

        /** `/recs` 的 limit 上限是 10. */
        const val RECS_PER_SEED = 10

        /** 判"已收藏"时往回看多少条. 全表拉出来只为做个集合不值当. */
        const val COLLECTED_LOOKBACK = 500
    }
}
