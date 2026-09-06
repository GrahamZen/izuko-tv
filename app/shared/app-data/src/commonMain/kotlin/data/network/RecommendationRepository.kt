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
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.recommend.RecommendedItemInfo
import me.him188.ani.app.data.models.recommend.RecommendedSubjectInfo
import me.him188.ani.app.data.network.mapper.orBangumiPlaceholder
import me.him188.ani.app.data.persistent.database.dao.RecommendationFeedDao
import me.him188.ani.app.data.persistent.database.dao.RecommendationFeedEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionDao
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.domain.search.SearchSort
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 探索页「猜你喜欢」.
 *
 * bangumi 没有"全站推荐流"这种东西 (Ani 的 `/v2/home/recommendations` 是它自己做的, 而且实测已经
 * 开始混广告进来: 负数 subjectId、指向 IP 地址的链接). 这里以**自己最近看的条目为种子**, 各取一份
 * bangumi 的"看过这部的人也看过" (`/p1/subjects/{id}/recs`), 合并去重, 去掉自己已经收藏过的.
 *
 * **结果缓存在 Room 里, 进页只读缓存, 一个请求都不发** ([recommendedSubjectsPager]).
 * 改之前是"翻页时现算": 每翻一页拿几个种子、每个种子一个请求, 一次会话实测 946 个请求, 而且全都
 * 发生在用户正盯着这一页的时候. 现在一次重算封顶 [MAX_SEED_REQUESTS] + 1 个请求, 且发生在首帧
 * 之后 (见 [requestRefresh]).
 *
 * 算不出几条时 (未登录 / 新装没有收藏, 或收藏太全把推荐过滤光了) 用**热门**兜底, 那条路不需要
 * 登录, 所以探索页不会是空的.
 */
class RecommendationRepository(
    private val bangumiSubjectApi: ApiInvoker<SubjectBangumiNextApi>,
    private val subjectCollectionDao: SubjectCollectionDao,
    private val feedDao: RecommendationFeedDao,
    private val trendsRepository: TrendsRepository,
    private val searchService: AniSubjectSearchService,
    /**
     * 重算跑在这个 scope 上, **不能挂调用方协程**: 页面那边是 `collectLatest` 收的, 换一次焦点
     * 就会把在途的重算连同落库一起掐掉 —— 详情页"连选集卡骨架都没有"就是这么来的.
     */
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) : Repository() {
    /** 只读缓存表, 零请求. 写入由 [requestRefresh] 在后台做, Room 会自己把新结果推过来. */
    fun recommendedSubjectsPager(): Flow<PagingData<RecommendedItemInfo>> =
        Pager(
            // 关掉占位: 原来那个网络 PagingSource 不报总数, 界面一直是"没有 null 项"的形状;
            // Room 的 PagingSource 会报, 开着占位就会凭空多出 null 项来
            PagingConfig(pageSize = 30, enablePlaceholders = false),
        ) {
            feedDao.pagingSource(RecommendationFeedEntity.GROUP_DEFAULT)
        }.flow.map { data ->
            data.map { entity ->
                RecommendedSubjectInfo(
                    bangumiId = entity.subjectId,
                    nameCn = entity.nameCn,
                    imageLarge = entity.imageLarge,
                )
            }
        }

    private val refreshMutex = Mutex()

    /**
     * 请求重算一次. 缓存还新鲜就什么都不做; 同时只会有一次在跑.
     *
     * @param force 用户主动要"换一批"时传 true: 跳过新鲜度判断与首帧宽限, 立刻重算.
     */
    fun requestRefresh(force: Boolean = false) {
        // 已经有一次在跑就直接算数: 不必排队, 那次跑完的结果是一样的
        if (!refreshMutex.tryLock()) return
        scope.launch {
            try {
                refreshOnce(force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 推荐算不出来不该牵连别的东西: 缓存里的旧结果照旧显示
                logger.warn(e) { "bgm-direct: recommendations 重算失败, 保留旧缓存" }
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    private suspend fun refreshOnce(force: Boolean) {
        if (!force) {
            // 先判新鲜度再等 —— 反过来的话, 绝大多数进页都要白白挂 3 秒才发现无事可做
            val computedAt = feedDao.computedAtOf(RecommendationFeedEntity.GROUP_DEFAULT)
            if (computedAt != null && currentTimeMillis() - computedAt < TTL_MILLIS) {
                logger.info { "bgm-direct: recommendations 缓存仍新鲜, 跳过重算" }
                return
            }
            // 首帧宽限: 进页那一两秒在布局、拉封面与 hero 背景, 重算这时候插进去只会让首屏更慢,
            // 而它的结果本来就要等**下次**进页才用得上, 一点都不急.
            // **缓存空的时候不能这么等**: 那是装完/升级完第一次进页, 推荐区正空着, 等 3 秒
            // 就是干瞪眼 3 秒 —— 那一次结果是要当场用的.
            delay(if (computedAt == null) COLD_START_GRACE else FIRST_FRAME_GRACE)
        }

        val startMillis = currentTimeMillis()
        val (items, requests) = withContext(ioDispatcher) { compute() }
        if (items.isEmpty()) {
            // 一条都没算出来时**不要清空**: 宁可让用户接着看上一批
            logger.warn { "bgm-direct: recommendations 算出 0 条, 保留旧缓存" }
            return
        }
        val computedAt = currentTimeMillis()
        feedDao.deleteGroupsExcept(listOf(RecommendationFeedEntity.GROUP_DEFAULT))
        feedDao.replace(
            RecommendationFeedEntity.GROUP_DEFAULT,
            items.mapIndexed { index, item ->
                RecommendationFeedEntity(
                    groupKey = RecommendationFeedEntity.GROUP_DEFAULT,
                    orderIndex = index,
                    subjectId = item.bangumiId,
                    nameCn = item.nameCn,
                    imageLarge = item.imageLarge,
                    computedAt = computedAt,
                )
            },
        )
        logger.info {
            "bgm-direct: recommendations 重算完成 ${items.size} 条, $requests 个请求, " +
                    "${computedAt - startMillis}ms"
        }
    }

    /** @return 算出来的条目, 与这次发了多少个请求 */
    private suspend fun compute(): Pair<List<RecommendedSubjectInfo>, Int> {
        val collected = subjectCollectionDao.mostRecentUpdated(limit = COLLECTED_LOOKBACK, offset = 0)
            .first()
            .mapTo(mutableSetOf()) { it.subjectId }

        // LinkedHashMap 兼做去重与保序: 先被推荐到的排前面
        val result = LinkedHashMap<Int, RecommendedSubjectInfo>()
        var requests = 0
        var seedOffset = 0

        while (result.size < TARGET_ITEMS && requests < MAX_SEED_REQUESTS) {
            val seeds = subjectCollectionDao
                .mostRecentUpdated(limit = SEEDS_PER_BATCH, offset = seedOffset)
                .first()
            if (seeds.isEmpty()) break
            seedOffset += seeds.size

            // 同批并发. 单个种子失败不该拖垮整批 —— 有的条目一条推荐都没有
            val recommendations = bangumiSubjectApi {
                coroutineScope {
                    seeds.map { seed ->
                        async {
                            runCatching { getSubjectRecs(seed.subjectId, limit = RECS_PER_SEED).body().data }
                                .getOrElse { emptyList() }
                        }
                    }.flatMap { it.await() }
                }
            }
            requests += seeds.size

            val before = result.size
            for (rec in recommendations) {
                if (result.size >= TARGET_ITEMS) break
                val subject = rec.subject
                // 「看过这部的人也看过」不分条目类型, 漫画/游戏/三次元都会混进来 (实测 276792 的
                // 10 条推荐里有 3 条不是动画). 探索页那一行只放动画.
                if (subject.type != BangumiNextSubjectType.Anime) continue
                if (subject.id in collected) continue
                result.getOrPut(subject.id) {
                    RecommendedSubjectInfo(
                        bangumiId = subject.id,
                        nameCn = subject.nameCN.ifEmpty { subject.name },
                        imageLarge = subject.images?.large.orBangumiPlaceholder(),
                    )
                }
            }
            logger.info {
                "bgm-direct: recommendations seeds=${seeds.map { it.subjectId }} -> " +
                        "${recommendations.size} raw, 收下 ${result.size - before} 条 " +
                        "(已有 ${result.size}/$TARGET_ITEMS), collected=${collected.size}"
            }

            if (seeds.size < SEEDS_PER_BATCH) break // 种子取完了
        }

        // 没有收藏 (未登录 / 新装), 或者收藏太全导致推荐几乎被过滤光 —— 用热门兜底.
        // 这一路不需要登录, 所以未登录用户的探索页也不是空的.
        if (result.size < MIN_ITEMS) {
            val before = result.size
            runCatching { trendsRepository.getTrendsInfo() }
                .onSuccess { trends ->
                    requests += 1
                    for (trending in trends.subjects) {
                        if (result.size >= TARGET_ITEMS) break
                        if (trending.bangumiId in collected) continue
                        result.getOrPut(trending.bangumiId) {
                            RecommendedSubjectInfo(
                                bangumiId = trending.bangumiId,
                                nameCn = trending.nameCn,
                                imageLarge = trending.imageLarge,
                            )
                        }
                    }
                    logger.info { "bgm-direct: recommendations 热门兜底补了 ${result.size - before} 条" }
                }
                .onFailure { logger.warn(it) { "bgm-direct: recommendations 热门兜底失败" } }
        }

        // 还不够就拿高分榜补齐. 前两路都可能很薄: 新番条目在 bangumi 上**根本没有**"看过这部的
        // 人也看过"数据 (真机上第二批 6 个种子一条都没有), 而热门就那么二十来条.
        // 这一路不需要登录、能分页、量管够 (total=1000).
        var searchRequests = 0
        while (result.size < TARGET_ITEMS && searchRequests < MAX_FILL_REQUESTS) {
            val before = result.size
            // 每次换个起点, 免得所有人的推荐都是同一批殿堂级老番. 结果会缓存 12 小时, 所以
            // 一天之内是稳定的 —— 真正的"稳定随机"(按日期播种 + 换一批) 是后面的事.
            val offset = Random.nextInt(FILL_OFFSET_RANGE)
            val page = runCatching {
                searchService.searchSubjects(
                    keyword = "",
                    offset = offset,
                    limit = FILL_PAGE_SIZE,
                    sort = SearchSort.RANK,
                    // bangumi 把"无排名"记作 rank 0, 不写 ">=1" 就会把一堆没排名的排最前
                    filters = SubjectSearchFilters(ranks = listOf(">=1"), nsfw = false),
                )
            }.getOrElse {
                logger.warn(it) { "bgm-direct: recommendations 高分榜兜底失败" }
                break
            }
            searchRequests++
            requests++
            if (page.isEmpty()) break
            for (details in page) {
                if (result.size >= TARGET_ITEMS) break
                val info = details.subjectInfo
                if (info.subjectId in collected) continue
                result.getOrPut(info.subjectId) {
                    RecommendedSubjectInfo(
                        bangumiId = info.subjectId,
                        nameCn = info.nameCn.ifEmpty { info.name },
                        imageLarge = info.imageLarge,
                    )
                }
            }
            logger.info {
                "bgm-direct: recommendations 高分榜兜底 offset=$offset 补了 ${result.size - before} 条 " +
                        "(已有 ${result.size}/$TARGET_ITEMS)"
            }
        }

        return result.values.toList() to requests
    }

    private companion object {
        /** 缓存多久算过期. 推荐不是时效性内容, 不必勤快. */
        val TTL_MILLIS = 12.hours.inWholeMilliseconds

        /** 缓存里已有结果时, 进页到开始重算之间等多久 —— 让首屏先画完. */
        val FIRST_FRAME_GRACE = 3.seconds

        /** 缓存是空的 (装完第一次进页) 时等多久: 只让首帧过去, 别让推荐区空着干等. */
        val COLD_START_GRACE = 500.milliseconds

        /** 一批用几个种子条目, 每个种子一个请求 (同批并发). */
        const val SEEDS_PER_BATCH = 6

        /**
         * 一次重算最多发几个种子请求 (硬预算).
         *
         * 收藏越多, 推荐里能剩下的越少 (真机上 402 部收藏时, 4 个种子的 36 条推荐只剩十来条),
         * 所以要允许多要几批; 但绝不能像改之前那样"没凑够就一直往后翻".
         */
        const val MAX_SEED_REQUESTS = 12

        /** 一次算多少条. 电视上一行几张, 60 条已经翻不完了. */
        const val TARGET_ITEMS = 60

        /** 少于这个数就用热门补齐, 免得探索页只有孤零零两行. */
        const val MIN_ITEMS = 24

        /** 高分榜最多补几次 (每次一个请求). */
        const val MAX_FILL_REQUESTS = 2

        /** 高分榜一次要几条. bangumi 的 limit 上限是 50. */
        const val FILL_PAGE_SIZE = 50

        /** 高分榜起点在 [0, 这个数) 里随机. 再往后就不算"高分"了. */
        const val FILL_OFFSET_RANGE = 300

        /** `/recs` 的 limit 上限是 10. */
        const val RECS_PER_SEED = 10

        /** 判"已收藏"时往回看多少条. 全表拉出来只为做个集合不值当. */
        const val COLLECTED_LOOKBACK = 500
    }
}
