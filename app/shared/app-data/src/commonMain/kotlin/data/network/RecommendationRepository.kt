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
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import me.him188.ani.app.data.models.recommend.RecommendedItemInfo
import me.him188.ani.app.data.models.recommend.RecommendedSubjectInfo
import me.him188.ani.app.data.models.subject.CanonicalTagKind
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.network.mapper.orBangumiPlaceholder
import me.him188.ani.app.data.persistent.database.dao.RecommendationFeedDao
import me.him188.ani.app.data.persistent.database.dao.RecommendationFeedEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionDao
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.app.data.network.mapper.toEntity
import me.him188.ani.app.data.recommendation.InterestProfile
import me.him188.ani.app.data.recommendation.RecommendationGroup
import me.him188.ani.app.data.recommendation.RecommendationGroupKind
import me.him188.ani.app.data.recommendation.computeInterestProfile
import me.him188.ani.app.data.recommendation.isInterestTag
import me.him188.ani.app.data.recommendation.sequelHint
import me.him188.ani.app.data.recommendation.seriesKeyOf
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.domain.search.SearchSort
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.canAccessAniApiNow
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 探索页推荐.
 *
 * **结果缓存在 Room 里, 进页只读缓存, 一个请求都不发** ([recommendationGroups]).
 * 改之前是"翻页时现算": 每翻一页拿几个种子、每个种子一个请求, 一次会话实测 946 个请求, 而且全都
 * 发生在用户正盯着这一页的时候. 现在一次重算封顶十来个请求, 且发生在首帧之后 (见 [requestRefresh]).
 *
 * 推荐分成几组, 各组来源不同 (见 [RecommendationGroupKind]):
 * 兴趣画像由**本地收藏**算出 (零请求, 见 [computeInterestProfile]), 各组据此去 bangumi 召回.
 * 只有"因为你喜欢《X》"这一组依赖种子的 `/p1/subjects/{id}/recs`; 其余几组走搜索与热门.
 *
 * 没登录、或者一部收藏都没有时没有口味可依, 推荐区整个是一组 [RecommendationGroupKind.FEED],
 * 照 Ani 服务端匿名首页的规则排 (见 [computeFeed]).
 */
class RecommendationRepository(
    private val bangumiSubjectApi: ApiInvoker<SubjectBangumiNextApi>,
    private val subjectCollectionDao: SubjectCollectionDao,
    private val subjectService: SubjectService,
    private val feedDao: RecommendationFeedDao,
    private val trendsRepository: TrendsRepository,
    private val searchService: AniSubjectSearchService,
    /** 续作换成最早一季时顺前传回溯用 (见 [SequelBatch]); 与 TMDB 匹配、详情页共用节点缓存. */
    private val seriesIndexService: SubjectSeriesIndexService,
    /** 没登录时本地收藏缓存不算数 (见 [refreshOnce]). */
    private val sessionStateProvider: SessionStateProvider,
    /**
     * 重算跑在这个 scope 上, **不能挂调用方协程**: 页面那边是 `collectLatest` 收的, 换一次焦点
     * 就会把在途的重算连同落库一起掐掉 —— 详情页"连选集卡骨架都没有"就是这么来的.
     */
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) : Repository() {
    /** 分好组的推荐, 只读缓存表, 零请求. */
    fun recommendationGroups(): Flow<List<RecommendationGroup>> =
        feedDao.allFlow().map { rows ->
            // 表里已经是全局有序的, 相邻同组的连在一起, 顺序扫一遍就分完
            rows.groupBy { it.groupKey }
                .mapNotNull { (key, groupRows) ->
                    val kind = RecommendationGroupKind.ofKeyOrNull(key) ?: return@mapNotNull null
                    kind to groupRows
                }
                .sortedBy { (_, groupRows) -> groupRows.minOf { it.orderIndex } }
                .map { (kind, groupRows) ->
                    RecommendationGroup(
                        kind = kind,
                        titleArg = groupRows.firstNotNullOfOrNull { it.titleArg },
                        items = groupRows.sortedBy { it.orderIndex }.map { it.toInfo() },
                    )
                }
        }

    /** 拍平成一条流的推荐, 给还没按组画的界面用 (手机端竖排网格). */
    fun recommendedSubjectsPager(): Flow<PagingData<RecommendedItemInfo>> =
        Pager(
            // 关掉占位: 原来那个网络 PagingSource 不报总数, 界面一直是"没有 null 项"的形状;
            // Room 的 PagingSource 会报, 开着占位就会凭空多出 null 项来
            PagingConfig(pageSize = 30, enablePlaceholders = false),
        ) {
            feedDao.pagingSource()
        }.flow.map { data -> data.map { it.toInfo() } }

    private fun RecommendationFeedEntity.toInfo() = RecommendedSubjectInfo(
        bangumiId = subjectId,
        nameCn = nameCn,
        imageLarge = imageLarge,
    )

    init {
        // 登录之后收藏是**异步同步下来的**: 进页那一刻画像还是空的, 判"没变"就跳过了, 而收藏
        // 落库时又没有人再问一次 —— 表现就是"登录了但推荐没变"(2026-09-06 用户实测).
        // 盯住真收藏条数这个便宜信号; 真正要不要重算仍由画像身份串决定.
        scope.launch {
            subjectCollectionDao.realCollectionCountFlow()
                .distinctUntilChanged()
                .drop(1) // 首个值是当前状态, 进页那次已经判过了
                // **等它稳定下来再算**: 收藏是分批同步的, 每落一批条数就变一次 —— 不防抖的话
                // 一次登录能连着跑五六轮完整重算, 每轮六七个请求 (2026-09-06 真机: 7→8 就跑了
                // 两轮 13 个请求). 重算结果本来也不急, 晚十几秒毫无影响.
                .debounce(COLLECTION_SETTLE_DEBOUNCE)
                .collect {
                    logger.info { "bgm-direct: recommendations 真收藏条数稳定在 $it, 重新判一次" }
                    requestRefresh()
                }
        }
        // 登录态一变也重判: 退出登录不清本地收藏缓存, 上面那条盯的条数不会动, 推荐区却该换成没登录时那一组
        scope.launch {
            sessionStateProvider.stateFlow
                .map { it is SessionState.Valid }
                .distinctUntilChanged()
                .drop(1) // 首个值是当前状态, 进页那次已经判过了
                .collect { loggedIn ->
                    logger.info { "bgm-direct: recommendations 登录态变了 (登录=$loggedIn), 重新判一次" }
                    requestRefresh()
                }
        }
    }

    private val refreshMutex = Mutex()

    private val _isRefreshing = MutableStateFlow(false)

    /**
     * 是否正在重算.
     *
     * 给 UI 用: 装完/登录后第一次进页时推荐区是空的, 而一次重算要十几秒 (二十来个请求) ——
     * 不给个交代的话, 新用户看到的就是一片空白, 不知道这儿本来该有东西.
     *
     * **`true` 不代表"马上会有结果"**: 缓存还新鲜时 [refreshOnce] 判完就返回, 这个标记也就亮
     * 一瞬间. 所以消费方要自己加"推荐区确实是空的"这个条件, 别一看它亮就弹提示.
     */
    val isRefreshing: StateFlow<Boolean> get() = _isRefreshing

    /**
     * 请求重算一次. 缓存还新鲜就什么都不做; 同时只会有一次在跑.
     *
     * @param force 用户主动要"换一批"时传 true: 跳过新鲜度判断与首帧宽限, 立刻重算.
     */
    fun requestRefresh(force: Boolean = false) {
        // 已经有一次在跑就直接算数: 不必排队, 那次跑完的结果是一样的
        if (!refreshMutex.tryLock()) return
        scope.launch {
            _isRefreshing.value = true
            try {
                refreshOnce(force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 推荐算不出来不该牵连别的东西: 缓存里的旧结果照旧显示
                logger.warn(e) { "bgm-direct: recommendations 重算失败, 保留旧缓存" }
            } finally {
                _isRefreshing.value = false
                refreshMutex.unlock()
            }
        }
    }

    private suspend fun refreshOnce(force: Boolean) {
        // 没登录时本地那份收藏不算数: 退出登录不清本地收藏缓存, 留下的是上一个账号的. 画像于是是空的,
        // 推荐区出没登录时那一组 (见 computeFeed). 会话状态在启动刷新 token 时可能迟迟不出来, 等不到就只看收藏
        val loggedIn = withTimeoutOrNull(SESSION_STATE_WAIT) { sessionStateProvider.canAccessAniApiNow() } ?: true
        // 画像先算出来: 它是**纯本地的**(零请求), 而且判"要不要重算"就得看它 —— 登录之后
        // computedAt 与 algoVersion 都没变, 只有画像变了 (0 部收藏 → 上百部).
        val collections = if (loggedIn) {
            loadCollections()
        } else {
            logger.info { "bgm-direct: recommendations 没登录, 本地收藏缓存不算数" }
            emptyList()
        }
        val profile = computeInterestProfile(collections, currentTimeMillis())

        if (!force) {
            // 先判新鲜度再等 —— 反过来的话, 绝大多数进页都要白白挂几秒才发现无事可做
            val computedAt = feedDao.computedAt()
            val staleAlgo = (feedDao.algoVersion() ?: 0) != RecommendationFeedEntity.CURRENT_ALGO_VERSION
            val staleProfile = feedDao.profileKey() != profile.key
            if (!staleAlgo && !staleProfile && computedAt != null &&
                currentTimeMillis() - computedAt < TTL_MILLIS
            ) {
                logger.info { "bgm-direct: recommendations 缓存仍新鲜, 跳过重算" }
                return
            }
            if (staleProfile && computedAt != null) {
                logger.info { "bgm-direct: recommendations 画像变了, 缓存作废重算" }
            }
            // 首帧宽限: 进页那一两秒在布局、拉封面与 hero 背景, 重算这时候插进去只会让首屏更慢,
            // 而它的结果本来就要等**下次**进页才用得上, 一点都不急.
            // **缓存空的时候不能这么等**: 那是装完/升级完第一次进页, 推荐区正空着, 等几秒
            // 就是干瞪眼几秒 —— 那一次结果是要当场用的.
            delay(if (computedAt == null) COLD_START_GRACE else FIRST_FRAME_GRACE)
        }

        // 上一批各行用的是哪部当理由 (存在 titleArg 里); 平时拿来让种子保持稳定, 「换一批」时拿来躲开
        val previousSeedNames = feedDao.seedTitleArgs()
        // 「换一批」时各行先挑最近几批都没出现过的, 不够才用回来; 平时的重算不躲 (保持稳定), 并清掉这份记录
        val previousItems = if (force) feedDao.subjectIds().toSet() else emptySet()
        if (force) {
            recentBatches.addLast(previousItems)
            while (recentBatches.size > RECENT_BATCHES_TO_AVOID) recentBatches.removeFirst()
        } else {
            recentBatches.clear()
        }
        val avoidItems = recentBatches.flatMapTo(HashSet()) { it }

        val startMillis = currentTimeMillis()
        // **确定要重算了才补数据**: 画像身份串仍然只由本地收藏算 (上面那个 profile), 否则补进来的
        // 东西会让身份串每次都不一样, 变成每次进页都重算.
        //
        // 为什么要补: 登录后本地只有**在看**那一页 (探索页的"继续观看"栏拉的 type=3), 而**看过**
        // 那份要等用户进追番页切 tab 才会拉 —— 种子恰恰要从"打过分/看过"的里面挑, 于是
        // "因为你喜欢《X》"要等用户恰好逛过那个 tab 才会出现 (2026-09-06 用户实测: 等了很久).
        // 这里补一页, **只在内存里参与算画像, 不落库** —— 落库要走收藏仓库那条路 (它在 offset=0
        // 时会反推哪些本地行已经不属于这个类型, 拿一页去碰几百条的账号有风险).
        // **无条件把收藏取全**(登录时). 三个用处:
        //  1. 挑种子: 本地只有"在看"那一页, 挑不出"打过分/看过"的;
        //  2. **排除已收藏的** —— 用户最能感知的那一项: 排除集原先只由本地收藏建, 而本地只有
        //     分页进来的那些 (真机: 近百部里只有 23 条), 于是看过的大量出现在推荐里;
        //  3. 画像不再取决于"逛过哪个 tab".
        val extra = fetchAllCollections()
        val enriched = if (extra.isEmpty()) {
            profile
        } else {
            // extra 在前: 它是刚从服务端取的, 比本地那份新 (本地可能是几天前分页存下的)
            val merged = (extra + collections).distinctBy { it.subjectId }
            computeInterestProfile(merged, currentTimeMillis())
                .also { logger.info { "bgm-direct: recommendations 取到 ${extra.size} 条收藏 -> $it" } }
        }
        // 「换一批」才换随机种子; 其余时候 (TTL 到期、画像变了) 同一天算出来的一样, 见 Sampling
        if (force) shuffleCount++
        val today = PackedDate.now()
        if (highRatedPagesDate != today) {
            highRatedPages.clear()
            highRatedPagesDate = today
        }
        val sampling = Sampling(
            seed = sampleSeed(),
            watchedRegions = lastCollections?.watchedRegions()?.takeIf { it.isNotEmpty() } ?: DEFAULT_WATCHED_REGIONS,
            collectedRegions = lastCollections?.collectedRegions(),
        )
        logger.info { "bgm-direct: recommendations 看过的地区 ${sampling.collectedRegions ?: "(无从判断, 不按地区过滤)"}" }
        // 上一批还在换季的话停掉: 表马上要被整个替换
        sequelJob?.cancel()
        val batchJob = SupervisorJob(scope.coroutineContext[Job])
        sequelJob = batchJob
        val sequels = SequelBatch(CoroutineScope(scope.coroutineContext + ioDispatcher + batchJob))
        val allCollections = (extra + collections).distinctBy { it.subjectId }
        val computed = withContext(ioDispatcher) {
            if (allCollections.isEmpty()) {
                // 没登录 / 一部收藏都没有: 没有口味可依, 各组里能个性化的一组都出不来
                computeFeed()
            } else {
                // force = 用户点了「换一批」: 这时**不能**再沿用上一个种子, 否则最关键那一行
                // (因为你喜欢/看过X的人还看了) 会原封不动地留着 —— 用户点了却看不出变化.
                compute(
                    allCollections,
                    enriched,
                    previousSeedNames,
                    sampling,
                    sequels,
                    avoidItems = avoidItems,
                    seedHistory = rotatedSeeds,
                    rotateSeed = force,
                )
            }
        }
        if (computed.groups.isEmpty()) {
            // 一组都没算出来时**不要清空**: 宁可让用户接着看上一批
            logger.warn { "bgm-direct: recommendations 算出 0 组, 保留旧缓存" }
            batchJob.cancel()
            return
        }
        // 已经回溯完的续作当场换成最早一季 (不等没查完的, 那些落库后再换)
        val groups = sequels.applyReady(computed.groups, computed.collected, computed.onCarousel)

        val computedAt = currentTimeMillis()
        var orderIndex = 0
        // 同 kind 多行时给个序号, 否则读回来会被 groupBy 并成一行
        val kindSeen = HashMap<RecommendationGroupKind, Int>()
        val groupRows = groups.map { group ->
            val ordinal = kindSeen.getOrElse(group.kind) { 0 }
            kindSeen[group.kind] = ordinal + 1
            val groupKey = if (ordinal == 0) group.kind.key else "${group.kind.key}#$ordinal"
            group.items.map { item ->
                RecommendationFeedEntity(
                    groupKey = groupKey,
                    orderIndex = orderIndex++,
                    subjectId = item.bangumiId,
                    nameCn = item.nameCn,
                    imageLarge = item.imageLarge,
                    computedAt = computedAt,
                    titleArg = group.titleArg,
                    algoVersion = RecommendationFeedEntity.CURRENT_ALGO_VERSION,
                    profileKey = profile.key,
                )
            }
        }
        val rows = groupRows.flatten()
        feedDao.replaceAll(rows)
        logger.info {
            "bgm-direct: recommendations 重算完成 ${rows.size} 条 / ${groups.size} 组 " +
                    "(${groups.joinToString { "${it.kind.key}=${it.items.size}" }}), " +
                    "${computed.requests} 个请求, ${computedAt - startMillis}ms" +
                    if (force) {
                        ", 换一批: 与上一批重复 ${rows.count { it.subjectId in previousItems }} 条 (" +
                                groups.joinToString { g -> "${g.kind.key}=${g.items.count { it.bangumiId in previousItems }}" } + ")"
                    } else {
                        ""
                    }
        }
        // 先出后改: 页面已经拿到这一批了, 剩下没查完的续作查完再原地换
        sequels.convertAfterShown(groupRows, computed.collected)
    }

    /**
     * 当前这一批的续作换季任务 (见 [SequelBatch]), 新一批开算时取消. 只在 [refreshOnce] 里读写
     * ([refreshMutex] 串着).
     */
    private var sequelJob: Job? = null

    /**
     * 读本地收藏, **只认真收藏**.
     *
     * 表里还有一堆 NOT_COLLECTED 的行 —— 那是"点开过、被本地缓存下来"的条目 (进详情页、hero
     * 预取都会写). 它们既不该进画像, 更不该被当成"已收藏"排除掉, 否则用户点开过的东西再也不会
     * 被推荐 (2026-09-06 从设备库里查出来的: 未登录那台机上 17 行全是 NOT_COLLECTED, 却全被
     * 当成已收藏排除了).
     */
    private suspend fun loadCollections(): List<SubjectCollectionEntity> {
        val cached = subjectCollectionDao
            .mostRecentUpdated(limit = PROFILE_LOOKBACK, offset = 0)
            .first()
        val collections = cached.filter { it.collectionType != UnifiedCollectionType.NOT_COLLECTED }
        logger.info {
            // 两个数都要报: 只报后者会让"没登录"看起来像"画像算错了"
            "bgm-direct: recommendations 真收藏=${collections.size} (本地缓存共 ${cached.size} 行)"
        }
        return collections
    }

    /**
     * 把用户**所有类型**的收藏取下来, **不落库**, 只拿来算画像与排除集.
     *
     * 为什么非取不可: 本地收藏表只有各页面分页进来的那些 (探索页只拉"在看", 别的类型要等用户进
     * 追番页切 tab), 真机上近百部收藏里只有二十几条在本地 —— 于是"已看过的"大量出现在推荐里.
     *
     * 不落库是刻意的: 落库要走 `SubjectCollectionRepository` 那条路, 它在 `offset = 0` 时会
     * `reconcileCollectionsLeftType` (反推哪些本地行已经不属于这个类型), 拿一页去碰几百条的账号
     * 有风险; 而这里只需要内存里的一份快照.
     *
     * 未登录时 [SubjectService.getSubjectCollectionsPage] 会抛授权异常, 直接当没有.
     */
    private suspend fun fetchAllCollections(): List<SubjectCollectionEntity> = runCatching {
        // **所有类型**, 不只「看过」: 抛弃的更不该推, 想看的也不该伪装成新发现;
        // 顺带画像也不再取决于"用户逛过哪个 tab"了 (本地只有逛过的那些).
        suspend fun page(offset: Int, limit: Int) = subjectService.getSubjectCollectionsPage(
            type = null,
            offset = offset,
            limit = limit,
        )

        // 第一页总是要取: 它同时回答两件事 —— 一共多少条, 以及跟上次比变没变.
        // 每轮都要付这一次, 而多数轮次取完它就复用快照了, 所以它单独取得很小 (见 COLLECTION_HEAD_PAGE)
        val first = page(0, COLLECTION_HEAD_PAGE)
        val fingerprint = first.items.map { it.id to (it.interest?.updatedAt ?: 0) }
        // **第一页没变 = 整份没变**: 列表按收藏更新时间倒序 (见 getSubjectCollectionsPage, 实测),
        // 任何一条被新加或改过都会被顶到第一页, 条数变了看 total —— 所以复用不会过期, 不是凭时间
        // 猜"应该还新鲜". 换了账号第一页必然不同, 也串不了.
        // 用处: 登录后收藏分批落进本地库, 每稳定一次就触发一轮重算 (本地画像身份串变了), 一次登录能
        // 连着算好几轮, 每轮都把几百条从头翻一遍 (2026-09-22 真机: 619 条 13 页, 两轮只隔 17 秒).
        lastCollections?.let { cached ->
            if (cached.total == first.total && cached.fingerprint == fingerprint) {
                logger.info { "bgm-direct: recommendations 收藏没变 (第一页与上次相同), 复用上次取的 ${cached.entities.size} 条" }
                return@runCatching cached.entities
            }
        }
        // **按服务端报的 total 翻到底**, 而不是固定几页: 原先卡在三页 150 条, 而"看过"上百部的
        // 用户 (真机上就取满了 150) 更早的那些一条都拦不住, 于是"已看过的还出现在推荐里"
        // (2026-09-07 用户实测).
        // **其余页一起发**: 页与页之间没有依赖 (偏移量按页长算好), 原先一页等一页, 619 条 13 页
        // 串了 3.9 秒, 占整次重算的一半多 (2026-09-22 真机).
        val rest = coroutineScope {
            (COLLECTION_HEAD_PAGE until minOf(first.total, COLLECTION_FETCH_MAX) step COLLECTION_FETCH_PAGE)
                .map { offset -> async { page(offset, COLLECTION_FETCH_PAGE).items } }
                .awaitAll()
                .flatten()
        }
        val fetchedAt = currentTimeMillis()
        val all = first.items + rest
        // 地区顺手数一下 (p1 返回的条目自带官方标签, 实体里不存): 给 Sampling.regionWeight 判"常看哪些地区"
        val regionsPerSubject = all.map { subject -> subject.metaTags.filter { it in CanonicalTagKind.Region.values } }
        val regionCounts = regionsPerSubject.flatten().groupingBy { it }.eachCount()
        all.map { it.toEntity(lastFetched = fetchedAt) }
            .also {
                lastCollections = CollectionsSnapshot(
                    first.total,
                    fingerprint,
                    it,
                    regionCounts = regionCounts,
                    regionTaggedCount = regionsPerSubject.count { regions -> regions.isNotEmpty() },
                )
            }
    }.getOrElse {
        // 未登录时 getSubjectCollections 第一行的本地检查就抛了, **没发出请求** —— 别把这行
        // 读成"网络失败"
        logger.info { "bgm-direct: recommendations 未登录, 不取收藏 (没发请求)" }
        lastCollections = null
        emptyList()
    }

    /**
     * 上一次取全的收藏, 见 [fetchAllCollections]. 只在 [refreshOnce] 里读写, 而那里有 [refreshMutex]
     * 串着 (解锁/加锁之间有 happens-before), 不用另加同步.
     */
    private var lastCollections: CollectionsSnapshot? = null

    private class CollectionsSnapshot(
        val total: Int,
        /** 第一页每条的 (条目 id, 收藏更新时间). */
        val fingerprint: List<Pair<Int, Int>>,
        val entities: List<SubjectCollectionEntity>,
        /** 收藏按官方标签里的地区计数 (一部可以有多个地区, 如"美国"+"欧美"). */
        private val regionCounts: Map<String, Int>,
        /** 标了地区的收藏有几部 (占比的分母; 按部数算, 一部多地区的不重复计). */
        private val regionTaggedCount: Int,
    ) {
        /** 占比够 [WATCHED_REGION_SHARE] 的地区. */
        fun watchedRegions(): Set<String> {
            if (regionTaggedCount == 0) return emptySet()
            return regionCounts.filterValues { it >= regionTaggedCount * WATCHED_REGION_SHARE }.keys
        }

        /**
         * 算"看过"的地区: 占比够 [COLLECTED_REGION_SHARE] 的. 收藏都没标地区时为 `null` (无从判断).
         *
         * 按占比不按"有没有": 收藏五六百部日本动画、外加两三部好莱坞动画电影 (蜘蛛侠、超能陆战队) 的人并不看
         * 欧美剧集, 这两三部不该让整个地区放行.
         */
        fun collectedRegions(): Set<String>? {
            if (regionTaggedCount == 0) return null
            return regionCounts.filterValues { it >= regionTaggedCount * COLLECTED_REGION_SHARE }.keys
        }
    }

    /** [compute] 的结果. */
    private class Computed(
        val groups: List<RecommendationGroup>,
        /** 这次发了多少个请求 (日志用). */
        val requests: Int,
        /** 已收藏的条目 id. */
        val collected: Set<Int>,
        /** 顶上轮播在放的条目 id (全组躲开的那一批). */
        val onCarousel: Set<Int>,
    )

    private suspend fun compute(
        collections: List<SubjectCollectionEntity>,
        profile: InterestProfile,
        previousSeedNames: List<String>,
        sampling: Sampling,
        /** 种子那几行与「换换口味」组装出来就交给它开始回溯前传. */
        sequels: SequelBatch,
        /** 「换一批」时最近几批的全部条目 (见 [recentBatches]): 各行先挑这之外的, 不够才用回来. 平时为空. */
        avoidItems: Set<Int>,
        /** 本进程里「换一批」这一轮用过的种子, 只在 [rotateSeed] 时读写 (见 [rotatedSeeds]). */
        seedHistory: MutableSet<Int>,
        rotateSeed: Boolean = false,
    ): Computed = coroutineScope {
        val collected = collections.mapTo(HashSet()) { it.subjectId }
        logger.info { "bgm-direct: recommendations 画像 $profile" }

        // 下面几段召回是并发的, 各自往上加
        val requests = atomic(0)

        // ======== 第一段: 召回 —— 所有候选池一起发出去 ========
        // 各组的**召回**互不依赖, 依赖只在**组装**上 (跨组去重看先后, 种子要看前面的成没成行).
        // 原先召回与组装搅在一起, 一段等上一段: 单个请求才 130~200ms, 二十来个串起来就是两三秒
        // (2026-09-22 真机: 收藏以外的部分串了 2.7 秒). 现在先全部发出去, 第二段再按原来的顺序
        // 组装 —— 组装的顺序与规则都没变, 挑出来的东西也就不变.

        // ---- 0. 顶上的轮播已经在放的那些 (全组通杀的排除集, 见 fetchCarouselIds) ----
        val carouselDeferred = async { fetchCarouselIds(requests) }

        // ---- 1. 因为你喜欢《X》 ----
        // 种子按权重从高到低试, 直到有一部真的有推荐数据. **新番条目在 bangumi 上根本没有
        // `/recs`** (真机上高 id 种子一条不出), 所以必须允许换一部再试, 不能只认第一个.
        // **上一批用过的种子还在候选里就接着用**: 否则收藏每进一批就换一部作品当理由,
        // 标题几分钟一变, 看着像坏了 (2026-09-07 用户实测: 50 分钟内换了三次).
        // "还在候选里"= 仍是 affinity 前几名; 真掉出去了才换 —— 这样既不冻死, 也不乱跳.
        // 「换一批」反过来: 上一批用过的**全部**挪到最后, 其余的按喜爱度加权随机排. 只挪第一个的话,
        // 按几次都在两三个种子之间来回转 (2026-09-22 真机: 三次换一批, Z高达 与 彻夜之歌 次次都在).
        // **冷门作品当不了种子**: `/recs` 是共看数据, 种子自己没多少人看过时, 与它共看的更冷 ——
        // 2026-09-07 真机上「怪怪守护神」(327 人评过分) 那一行整行是 300~700 人评分的冷门作品
        // (健康全裸游泳社、PRISM ARK 5.5 分、两部 OVERLORD 总集篇). 这不是口味问题, 是**数据源
        // 在样本不足时给不出东西**.
        val usableSeeds = profile.seeds.filter { it.audience >= MIN_RATING_COUNT }
        if (usableSeeds.size < profile.seeds.size) {
            logger.info {
                "bgm-direct: recommendations 种子里 ${profile.seeds.size - usableSeeds.size} 个太冷门, 不用 " +
                        "(${profile.seeds.filter { it.audience < MIN_RATING_COUNT }.map { "${it.name}=${it.audience}" }})"
            }
        }
        val previous = previousSeedNames.mapNotNull { name -> usableSeeds.firstOrNull { it.name == name } }
        val others = usableSeeds.filter { it !in previous }
        val orderedSeeds = if (rotateSeed) {
            // 这一轮用过的 (连上一批) 都放到后面; 没用过的不够一批就从头再轮
            seedHistory += previous.map { it.subjectId }
            var fresh = others.filter { it.subjectId !in seedHistory }
            if (fresh.size < MAX_SEED_ROWS) {
                logger.info { "bgm-direct: recommendations 种子轮完一遍 (${seedHistory.size} 个), 从头再轮" }
                seedHistory.clear()
                fresh = others
            }
            // 排在前面的喜爱度高, 按位次加权: 靠前的更常被抽中, 但每按一次都是另一组
            sampling.ranked(fresh, idOf = { it.subjectId }, weightOf = { 1.0 }) + others.filter { it !in fresh } + previous
        } else {
            previous + others
        }
        // 组装时会试的那几个种子, 连同补齐一起提前发出去 (怎么预判的见 plannedSeeds)
        val seedPools = plannedSeeds(orderedSeeds, collections)
            .associate { seed -> seed.subjectId to async { fetchSeedPool(seed, collections, requests, sampling) } }

        // ---- 2. 符合你口味的高分动画 / 高分经典 ----
        // 用户的高权重标签各搜一次再合并成一组, **不是每个标签单独一行** —— "校园""恋爱""日常"
        // 各来一行的话, 三行内容高度重复, 看着像同一行抄了三遍.
        //
        // **不拿「来源」类标签 (漫画改/原创…) 搜**: 那是制作来源不是口味 (打分那份画像早就排掉了它),
        // 而且宽到单独拿它搜就是全站经典榜 —— 排行榜里 44.6% 的条目带"漫画改", 2026-09-22 真机上这一行
        // 因此满是星际牛仔/虫师/攻壳 (总榜第 4/21/22 名), 看着就是"按排名直接给的".
        val tasteQueryTags = profile.tags
            .filter { CanonicalTagKind.matchOrNull(it.name) != CanonicalTagKind.Source }
            .take(MAX_TAG_QUERIES)
            .map { it.name }
        // 当天取过的页接着用 (见 highRatedPages); 各标签的那一份在这里先建好, 下面的并发协程各改各的
        val tagPages = tasteQueryTags.associateWith { highRatedPages.getOrPut(it) { TagPages() } }
        val tagPoolDeferred = async {
            val tagResults = coroutineScope {
                // 并发发: 串行时这几个请求是整次重算耗时的大头 (真机 8 个请求串了 4.8 秒)
                // 每个标签每次随机添 [HIGH_RATED_RANDOM_PAGES] 页没取过的: 看得多的账号扣完已收藏, 每页只剩
                // 三分之一左右能用 (实测 232 条剩 79 条), 每次现取几页的话连按几次「换一批」就见底
                tasteQueryTags.mapIndexed { index, tag ->
                    async {
                        searchSampledByTags(
                            listOf(tag), HIGH_RATED_LINE, sampling.random(SALT_HIGH_RATED + index), requests,
                            randomPages = HIGH_RATED_RANDOM_PAGES,
                            cache = tagPages.getValue(tag),
                        )
                    }
                }.awaitAll()
            }.filterNotNull()
            val tagCandidates = interleave(tagResults)
            if (tagCandidates.isNotEmpty()) {
                RecommendationGroupKind.FOR_YOU_HIGH_RATED to tagCandidates
            } else {
                // 画像里没有口味标签可搜时退成纯排行榜. 起点随机, 免得所有人都是同一批殿堂老番
                searchTopRated(sampling.random(SALT_TOP_RATED))?.let {
                    requests += RANK_POOL_PAGES
                    RecommendationGroupKind.TOP_RATED to it
                }
            }
        }

        // ---- 3. 本季 (怎么排见第二段同名那节) ----
        val seasonDeferred = async { searchThisSeason()?.also { requests += SEASON_POOL_PAGES } }

        // ---- 4. 换换口味 ----
        // 与最强兴趣**同一类**但用户没碰过的方向: 喜欢"治愈"就试"温情/纯爱", 有连接点又不重复
        val changeTasteDeferred = async {
            neighborTagOf(profile, sampling.random(SALT_NEIGHBOR_TAG))?.let { neighbor ->
                // 与高分那一组同一种取法 (头一页 + 随机一页): 宽标签 (「科幻」) 的排行榜头两页全是几十年前的
                // 经典, 看得多的用户扣完已收藏只剩十几部, 一行 12 格照单全收, 年代权重排不开
                searchSampledByTags(listOf(neighbor), CHANGE_TASTE_LINE, sampling.random(SALT_CHANGE_TASTE_PAGE), requests)
            }
        }

        // ---- 5. 大家最近在看 ----
        // 走搜索 (近半年 + 热度序) 而不是热度榜的第 21~50 名: 榜给的精简条目没有收藏数,
        // 这一组于是成了唯一一条既过滤不了也加不了权重的路, 真机上推出过 703 收藏 / 35 人评分
        // 的东西. 换源之后同一道地板与人气权重都用得上 (见 searchRecentHot).
        val recentHotDeferred = async { searchRecentHot()?.also { requests += RECENT_HOT_PAGES } }

        // ======== 第二段: 组装 —— 按原来的顺序来, 跨组去重的先后由它决定 ========

        val onCarousel = carouselDeferred.await()
        // **只按条目 id 排除, 不按系列**: 榜前那二十条大多是本季正在播的, 连系列一起压掉的话
        // 「本季」那一行会被掏空 —— 而那一行的价值恰恰在于本季.
        val excluded = HashSet(collected).also { it += onCarousel }
        logger.info { "bgm-direct: recommendations 轮播在放的 ${onCarousel.size} 条, 全组躲开" }

        // 跨组尽量不重复, 但**不能让后面的组挨饿**: 收藏多的用户 (真机 98 部) 前几组会把能用的
        // 挑光, 热门那二十来条剩不下几个, 整行就被丢掉 (2026-09-06 真机: trending 只剩 4 条).
        // 而分组之后每行各有标题, 同一部同时出现在"本季"和"大家最近在看"两行里本来就是实话.
        // 所以先只收没出现过的, 不够再放宽. (轮播那一批不在此列, 它是硬排除.)
        // 「换一批」时最近几批的条目也记在这里: 第一遍躲开, 真凑不满时放宽那一遍才用回来 —— 不躲的话
        // 各行池子小的时候 (收藏几百部的账号), 换一次一半都是原来的.
        val seen = HashSet(avoidItems)
        // 同一部作品的不同季度只留一个 (见 seriesKeyOf): 用户看到"某作 第一季/第二季/第三季"
        // 一起堆在推荐里会觉得敷衍. 收藏里已有的那些系列也一并压住 —— 已经在追的系列不必再推.
        val seenSeries = collections.mapTo(HashSet()) { seriesKeyOf(it.nameCn.ifEmpty { it.name }) }
        val groups = mutableListOf<RecommendationGroup>()

        suspend fun takeGroup(
            kind: RecommendationGroupKind,
            rawCandidates: List<Candidate>,
            titleArg: String? = null,
            /**
             * 重排用的权重, 乘在"原序位次分"上; `null` = 不重排, 照输入顺序取.
             *
             * **输入的顺序本身就是一份信息** (排行榜按 rank, 本季按热度, 种子按相关度), 所以位次
             * 照旧算分, 权重只是往上乘一层 —— 是**降权不是封杀**, 只是年代那一项降得很陡.
             *
             * 哪几组该传什么见调用点.
             */
            weightOf: ((Candidate) -> Double)? = null,
            /**
             * 少于这个数就丢掉整组; 默认 [GROUP_SIZE], 也就是**要么满一行要么不出现**.
             *
             * 半行的观感比少一行差得多: 电视上一行 [GROUP_SIZE] 格, 画 6 个卡片剩半行空着,
             * 看着像加载失败; 而每一组的来源都有补齐的路 (种子补标签相似、标签池薄补高分榜、
             * 本季口味那一路凑不满就退回热度序), 填不满说明这个来源真的没料.
             */
            minSize: Int = GROUP_SIZE,
            /**
             * 凑不满一行时的后备料: 给了就拉来垫在**队尾** (位次分低, 只填缺的那几格) 再试一次.
             *
             * **真凑不满才拉**, 不按"池子看着薄"预先垫: 池子厚不厚要扣掉已收藏、同系列、别组用过的
             * 才知道, 召回时判不准 —— 按原始条数判的话, 看得多的用户照样被丢行 (2026-09-22 真机, 619 部
             * 收藏: 「换换口味」随机挑中"悬疑", 两页 40 条过了线, 扣完只剩 9 条, 这一行时有时无);
             * 按没收藏过的条数判又太保守, 两组各自都够 12 条却各垫了一次, 串在召回末尾多等 0.2 秒.
             * 在这里判就是准的, 平时一个请求都不多发.
             */
            padding: (suspend () -> List<Candidate>?)? = null,
            /**
             * 这一行的续作要不要换成最早一季 (见 [SequelBatch]). 要换的行另外留几个候补, 给"换完撞车"的格子.
             */
            trackSequels: Boolean = false,
            /** 重排时算不算位次分, 见 [Sampling.ranked]. */
            positional: Boolean = true,
        ) {
            // 用户没看过的地区直接不要 (见 Sampling.regionAllowed); 后备料在递归那一趟同样过这一道
            val pool = rawCandidates.filter { sampling.regionAllowed(it) }
            val ranked = if (weightOf == null) pool else sampling.ranked(pool, weightOf, positional)
            val candidates = ranked.map { it.info }
            // 超长篇只进放宽那一遍: 权重再低也只管排先后, 池子小的行 (看得多的账号扣完已收藏只剩十几部)
            // 会把它们照单全收 —— 2026-09-22 真机上「奇幻」补齐的那一行同时进了两部哆啦A梦
            val longRunning = ranked.asSequence().filter { sampling.isLongRunning(it) }.mapTo(HashSet()) { it.info.bangumiId }
            val items = LinkedHashMap<Int, RecommendedSubjectInfo>()
            val takenSeries = HashSet<String>()
            // 第一遍: 只要别的组没用过、系列也没出现过的
            for (candidate in candidates) {
                if (items.size >= GROUP_SIZE) break
                if (candidate.bangumiId in excluded || candidate.bangumiId in seen) continue
                if (candidate.bangumiId in longRunning) continue
                val series = seriesKeyOf(candidate.nameCn)
                if (series.isNotEmpty() && (series in seenSeries || !takenSeries.add(series))) continue
                items[candidate.bangumiId] = candidate
            }
            // 第二遍: 还没满就放宽到"允许与别的组重合"(组内仍不重复, 同系列仍只留一个)
            if (items.size < GROUP_SIZE) {
                for (candidate in candidates) {
                    if (items.size >= GROUP_SIZE) break
                    if (candidate.bangumiId in excluded) continue
                    val series = seriesKeyOf(candidate.nameCn)
                    if (series.isNotEmpty() && !takenSeries.add(series)) continue
                    items.getOrPut(candidate.bangumiId) { candidate }
                }
            }
            if (items.size < minSize) {
                val pad = padding?.invoke()
                if (!pad.isNullOrEmpty()) {
                    logger.info {
                        "bgm-direct: recommendations 组 ${kind.key} 只有 ${items.size} 条, 拿后备料垫 ${pad.size} 条再试"
                    }
                    // **原来的在前、后备的在后, 各自排好再拼, 拼好的不再重排**: 后备料只填原来填不满的
                    // 那几格, 不跟原来的抢位置 —— 混在一起抽样的话, 7.5 分的会把能用的 8 分以上挤掉.
                    val ordered = if (weightOf == null) {
                        pool + pad
                    } else {
                        sampling.ranked(pool, weightOf, positional) + sampling.ranked(pad, weightOf, positional)
                    }
                    return takeGroup(
                        kind, ordered, titleArg,
                        weightOf = null, minSize = minSize, padding = null, trackSequels = trackSequels,
                    )
                }
                // 连最低行长都凑不出来才丢: 说明这个来源真的没料
                logger.info { "bgm-direct: recommendations 组 ${kind.key} 只有 ${items.size} 条, 丢掉" }
                return
            }
            if (trackSequels) {
                // 候补: 行里没有、别的组没用过、与行里不同系列, 彼此也不同系列. 按第一遍的规矩进得来的在前;
                // 放宽那一遍才进得来的 (用户收藏过的系列里的其他季) 在后 —— 看得多的账号前者常常一个都不剩
                val reserveSeries = HashSet<String>()
                val reserves = candidates.asSequence()
                    .filter { it.bangumiId !in excluded && it.bangumiId !in seen && it.bangumiId !in items }
                    .filter { it.bangumiId !in longRunning }
                    .filter { candidate ->
                        val series = seriesKeyOf(candidate.nameCn)
                        series.isEmpty() || (series !in takenSeries && reserveSeries.add(series))
                    }
                    .toList()
                    .sortedBy { seriesKeyOf(it.nameCn) in seenSeries }
                    .take(SEQUEL_RESERVES)
                sequels.track(groupIndex = groups.size, items = items.values.toList(), reserves = reserves)
            }
            seen += items.keys
            items.values.mapTo(seenSeries) { seriesKeyOf(it.nameCn) }
            groups += RecommendationGroup(kind, titleArg, items.values.toList())
        }

        // ---- 1. 因为你喜欢《X》 ----
        // **出 2~3 行, 而且彼此风格不要太像**: 两个种子的标签重合太多时, 两行内容会高度重复,
        // 看着像同一行抄了两遍. 能不能用的规则见 seedSkipReason.
        var seedRows = 0
        var seedRequests = 0
        val usedSeedTags = mutableListOf<Set<String>>()
        // 已经出过行的种子属于哪些系列 (同一系列不许出两行, 见 seedSkipReason)
        val usedSeedSeries = HashSet<String>()
        for (seed in orderedSeeds) {
            if (seedRows >= MAX_SEED_ROWS) break
            // **数自己的请求**, 不能看总数: 第 0 步的热度榜也在 requests 里, 拿总数比等于白丢
            // 一个种子机会 (2026-09-07 真机: 只试了 4 个种子就停了, 而 5 个是预算)
            if (seedRequests >= MAX_SEED_REQUESTS) break
            val skipReason = seedSkipReason(seed, collections, usedSeedSeries, usedSeedTags)
            if (skipReason != null) {
                logger.info { "bgm-direct: recommendations 种子《${seed.name}》$skipReason, 跳过" }
                continue
            }
            // 预判到的那几个已经在路上了; 预判没料到的 —— 前面有一行没凑满被丢掉, 规则往下走到了
            // 别的种子 —— 才现拉
            val pool = seedPools[seed.subjectId]?.await() ?: fetchSeedPool(seed, collections, requests, sampling)
            seedRequests++
            val before = groups.size
            // 措辞跟着证据走: 只有种子确实被打过高分才敢说"你喜欢"
            val kind = if (seed.explicitlyLiked) {
                RecommendationGroupKind.BECAUSE_YOU_LIKED
            } else {
                RecommendationGroupKind.ALSO_WATCHED
            }
            // **补齐**: `/recs` 一共只有 9 条 (没有更多可翻), 扣掉门槛与已收藏之后真机上只剩
            // 0~4 条 —— 一行 12 格填不满, 前几次真机上整行被丢掉. 拿"标签和《X》最像的高分作品"
            // 垫在队尾: AND 搜种子自己最强的几个兴趣标签, 实测召回 88~487 条, 绰绰有余.
            //
            // **补齐时连措辞一起换**: 「看过《X》的人还看了」宣称的是共看数据 (别人看了什么),
            // 垫进标签相似的作品就不再全是那回事了 —— 所以补过的那一行改叫「和《X》相似的作品」
            // ([RecommendationGroupKind.SIMILAR_TO]), 不补的仍叫原来那句. 「因为你喜欢《X》」
            // 只承诺"跟你喜欢的这部有关", 内容相似完全算数, 补了也不改.
            //
            // 早先只给 BECAUSE_YOU_LIKED 补、ALSO_WATCHED 填不满就丢: 那是在"要么假话要么少一行"
            // 里选了后者. 第三种措辞把这个二选一拆开了 —— 证据变了就换说法, 行还留着.
            logger.info {
                "bgm-direct: recommendations 种子《${seed.name}》recs 只有 ${pool.recs.size} 条, " +
                        "拿 ${pool.similarTags} 补 ${pool.similar.size} 条"
            }
            val filledKind = if (kind == RecommendationGroupKind.ALSO_WATCHED && pool.similar.isNotEmpty()) {
                RecommendationGroupKind.SIMILAR_TO
            } else {
                kind
            }
            // 整行一起按年代 × 人气 × 形态 × 地区重排: recs 在前、补齐在后, 这个先后就是位次分 —— 共看的
            // 仍占优, 但几十年前的老番 (老种子的共看对象多半也老) 会被年代权重压到后面
            takeGroup(
                filledKind,
                pool.recs + pool.similar,
                titleArg = seed.name,
                weightOf = sampling::rankGroupWeight,
                trackSequels = true,
            )
            if (groups.size > before) {
                seedRows++
                usedSeedTags += seedInterestTags(seed.subjectId, collections).toSet()
                seriesKeyOf(seed.name).takeIf { it.isNotEmpty() }?.let { usedSeedSeries += it }
                if (rotateSeed) seedHistory += seed.subjectId
            }
        }

        // 凑不满一行时拿全站高分垫 (见 takeGroup 的 padding): 垫进来的都是 8 分以上, "高分"的语义不破
        fun padWithTopRated(salt: Int): suspend () -> List<Candidate>? = {
            searchTopRated(sampling.random(salt))?.also { requests += RANK_POOL_PAGES }
        }

        // ---- 2. 符合你口味的高分动画 / 高分经典 ----
        tagPoolDeferred.await()?.let { (kind, pool) ->
            logger.info {
                val usable = pool.distinctBy { it.info.bangumiId }
                    .count { it.info.bangumiId !in excluded && it.info.bangumiId !in seen && sampling.regionAllowed(it) }
                "bgm-direct: recommendations ${kind.key} 池子 ${pool.size} 条, 扣掉已收藏/轮播/最近几批/别组后还剩 $usable 条"
            }
            // 不算位次分: 池子就是分数线以上的全部, 位次只会让排行榜头一页那几部每批都在
            takeGroup(kind, pool, weightOf = sampling::rankGroupWeight, positional = false)
        }

        // ---- 3. 本季你可能会喜欢 / 本季新番 ----
        // 原先这一行就是"本季按热度排前 12", 于是与顶上的轮播、与「大家最近在看」大面积重叠
        // (三者都是"当下最热"的不同切片), 而"你可能会喜欢"这句话也没有任何依据可言
        // (2026-09-07 用户指出这一行名不副实).
        //
        // 现在: 池子照旧按热度捞 (新番大多还没排名, 按排名筛会把整季筛空), 但**按"你认可过的
        // 作品"的标签重排**, 且一个标签都对不上的直接不要 —— 这一行卖的就是"贴合口味的新番",
        // 热度只当同分时的次序.
        // 没有口味依据时 (一部都没打过分) 退回热度序, 标题同时换成不替用户表态的
        // 「本季新番」.
        seasonDeferred.await()?.let { pool ->
            val before = groups.size
            if (profile.likedTags.isNotEmpty()) {
                val weights = profile.likedTags.associate { it.name to it.weight }
                // 口味分再乘弃番率: 热度骗人的那一档就是靠这个压下去的 (2026-09-07 实测本季
                // 热度第 4 的条目弃番率 9.9%, 而 Re:Zero 第四季只有 0.6%)
                val scores = pool.associateWith {
                    tasteScore(it, weights) * dropRateWeight(it.dropRate) * sampling.regionWeight(it)
                }
                // 稳定排序: 分数相同的保持原来的热度序
                val matched = pool.filter { scores.getValue(it) > 0.0 }.sortedByDescending { scores.getValue(it) }
                logger.info {
                    "bgm-direct: recommendations 本季 ${pool.size} 条里 ${matched.size} 条对得上口味 " +
                            "(口味标签 ${weights.keys})"
                }
                // 按口味分**加权抽样**而不是取前 12: 最对口味的仍最常出现, 但「换一批」能换出别的
                takeGroup(RecommendationGroupKind.THIS_SEASON, matched, weightOf = { scores.getValue(it) })
            }
            // 口味那一路凑不满一行就退回热度序: 宁可少一层个性化, 也不能少一行 (整组丢掉).
            // 池子过了地板还有四十来条, 按热度序填满 12 格没问题.
            // **但弃番率照样要算** —— 没打过分的用户拿到的就是这一行, 纯热度序会把"开播即被弃"的
            // 摆在最前面.
            if (groups.size == before) {
                takeGroup(
                    RecommendationGroupKind.THIS_SEASON_NEW,
                    pool,
                    weightOf = { dropRateWeight(it.dropRate) * sampling.regionWeight(it) },
                )
            }
        }

        // ---- 4. 换换口味 ----
        changeTasteDeferred.await()?.let {
            // 这一组只有单一来源, 挑中一个窄标签 (实测 rank>=1 且上千人评分的: 武侠 17 / 耽美 24),
            // 或者一个用户看过大半的热门标签, 扣掉已收藏与跨组去重就凑不满一行 —— 同样拿高分榜垫
            takeGroup(
                RecommendationGroupKind.CHANGE_TASTE,
                it,
                weightOf = sampling::rankGroupWeight,
                padding = padWithTopRated(SALT_TOP_RATED_PAD_CHANGE_TASTE),
                trackSequels = true,
            )
        }

        // ---- 5. 大家最近在看 ----
        // 轮播在放的那二十条仍然躲开 —— 它们在 excluded 里, 与"已收藏"同档.
        recentHotDeferred.await()?.let {
            // 不按年代重排: 池子本来就只有近半年的, 年代权重在这儿没有意义; 人气那一层照样乘
            takeGroup(
                RecommendationGroupKind.TRENDING,
                it,
                weightOf = { c -> popularityWeight(c.collectionCount) * sampling.regionWeight(c) },
            )
        }

        Computed(groups, requests.value, collected, onCarousel)
    }

    /**
     * 顶上轮播在放的条目 id.
     *
     * 探索页顶上的 hero 轮播放的就是热度榜前 [TrendsRepository.TRENDING_LIMIT] 条, 一条不落.
     * 那些用户已经在同一屏上转着看过了, 推荐区再放一遍就是重复 (2026-09-07 用户反馈"重复太多"),
     * 所以拿它们当**全组通杀的排除集** (跟"已收藏"同档, 连放宽那一遍也不让过).
     * 只取这么多就够: 「大家最近在看」走的是搜索 (见 [searchRecentHot]), 榜的后半截用不上.
     */
    private suspend fun fetchCarouselIds(requests: AtomicInt): Set<Int> =
        runCatching { trendsRepository.getTrendsInfo(limit = TrendsRepository.TRENDING_LIMIT) }
            .onFailure { logger.warn(it) { "bgm-direct: recommendations 热门失败" } }
            .getOrNull()
            ?.subjects
            ?.also { requests.incrementAndGet() }
            .orEmpty()
            .mapTo(HashSet()) { it.bangumiId }

    /**
     * 没登录、或者一部收藏都没有时的推荐区: 一组 [RecommendationGroupKind.FEED], 按 Ani 服务端匿名首页
     * (`/v2/home/recommendations`) 的规则排, 用 bangumi 搜索复现. 2026-09-24 拿两天前取的 Ani 原榜对照:
     * 老番那 80 条是同一批 (72 条连位次都一样), 新番 120 条里 118 条相同, 差的是这两天在看人数的变动.
     *
     * 位置 `i` 的来源只看 `i % 5`: 0、1 放老番, 2、3、4 放新番 (Ani 原榜 200 条零例外):
     * - 老番: 半年前开播、有排名的, 按**看过**人数降序取 [FEED_CLASSIC_COUNT] 条;
     * - 新番: 近半年开播的 (还没开播的也算, Ani 同样不设上限), 按**在看**人数降序取 [FEED_RECENT_COUNT] 条.
     *
     * 轮播在放的整批躲开 (见 [fetchCarouselIds]), 两条榜各自往下顺补. 不按系列去重、不换季: 这一组就是
     * 全站的两张榜, 榜上怎么排就怎么放.
     */
    private suspend fun computeFeed(): Computed = coroutineScope {
        val requests = atomic(0)
        val carouselDeferred = async { fetchCarouselIds(requests) }
        val cutoff = feedRecentCutoff()
        val classicsDeferred = async {
            searchTopBy(
                SubjectSearchFilters(ranks = listOf(">=1"), airDates = listOf("<$cutoff"), nsfw = false),
                "feedClassics",
                FEED_CLASSIC_COUNT,
                requests,
            ) { it.done }
        }
        val recentDeferred = async {
            searchTopBy(
                SubjectSearchFilters(airDates = listOf(">=$cutoff"), nsfw = false),
                "feedRecent",
                FEED_RECENT_COUNT,
                requests,
            ) { it.doing }
        }
        val onCarousel = carouselDeferred.await()
        val classicsPool = classicsDeferred.await()
        val recentPool = recentDeferred.await()
        val classics = classicsPool.filter { it.bangumiId !in onCarousel }.take(FEED_CLASSIC_COUNT)
        val recent = recentPool.filter { it.bangumiId !in onCarousel }.take(FEED_RECENT_COUNT)
        logger.info {
            "bgm-direct: recommendations 无收藏, 照 Ani 匿名首页排: 老番 ${classics.size} 条 + 新番 ${recent.size} 条 " +
                    "(新番从 $cutoff 起), 轮播挡掉 ${classicsPool.count { it.bangumiId in onCarousel }} + " +
                    "${recentPool.count { it.bangumiId in onCarousel }} 条"
        }
        // 哪条榜没取到就当这次没算成 (保留旧缓存, 下次进页再算): 只剩半边的结果会顶着过完整个缓存期
        if (classics.isEmpty() || recent.isEmpty()) {
            logger.warn { "bgm-direct: recommendations 老番或新番那条榜没取到, 这次不算数" }
            return@coroutineScope Computed(emptyList(), requests.value, emptySet(), onCarousel)
        }
        val items = ArrayList<RecommendedSubjectInfo>(classics.size + recent.size)
        var nextClassic = 0
        var nextRecent = 0
        while (nextClassic < classics.size || nextRecent < recent.size) {
            val classicSlot = items.size % FEED_SLOT_CYCLE < FEED_CLASSIC_SLOTS
            // 一条榜先用完就接着放另一条剩下的
            items += if (nextRecent >= recent.size || (classicSlot && nextClassic < classics.size)) {
                classics[nextClassic++]
            } else {
                recent[nextRecent++]
            }
        }
        Computed(
            listOf(RecommendationGroup(RecommendationGroupKind.FEED, titleArg = null, items = items)),
            requests.value,
            emptySet(),
            onCarousel,
        )
    }

    /**
     * 按 [key] 取前 [count] 条, 另多取 [TrendsRepository.TRENDING_LIMIT] 条给轮播挡掉的留余量.
     *
     * 服务端只会按收藏总数排 (`heat`), 看过/在看排不了, 所以按收藏总数一批批往下翻 (一批 [FEED_PAGE_BATCH] 页,
     * 并发取), 在本地按 [key] 重排. [key] 是收藏总数里的一项, 谁的 [key] 都不会超过自己的收藏总数 ——
     * 一批末条的收藏总数已经低于手上第 N 名的 [key] 时, 往后再没有能挤进前 N 的, 就此收手.
     * 2026-09-24 实测两条榜各翻 12 页左右.
     */
    private suspend fun searchTopBy(
        filters: SubjectSearchFilters,
        what: String,
        count: Int,
        requests: AtomicInt,
        key: (SubjectCollectionStats) -> Int,
    ): List<RecommendedSubjectInfo> {
        val depth = count + TrendsRepository.TRENDING_LIMIT
        val pool = ArrayList<BatchSubjectDetails>()
        var pages = 0
        var stop = "到了 $FEED_MAX_PAGES 页的上限"
        while (pages < FEED_MAX_PAGES) {
            val batch = pages until minOf(pages + FEED_PAGE_BATCH, FEED_MAX_PAGES)
            val results = coroutineScope {
                batch.map { page ->
                    async {
                        searchPage(filters, SearchSort.COLLECTION, "$what#$page", offset = page * SEARCH_PAGE_SIZE)
                    }
                }.awaitAll()
            }
            requests += results.size
            pages = batch.last + 1
            // 只接到第一个失败的那页之前: 中间断了一页的话, 后面那些页的「末条」就判不准了
            val fetched = results.takeWhile { it != null }.filterNotNull()
            fetched.forEach { pool += it.items }
            if (fetched.size < results.size) {
                stop = "有一页没取到"
                break
            }
            val last = fetched.last()
            if (last.items.size < SEARCH_PAGE_SIZE || pages * SEARCH_PAGE_SIZE >= last.total) {
                stop = "翻到头了"
                break
            }
            val threshold = pool.map { key(it.subjectInfo.collectionStats) }.sortedDescending().getOrNull(depth - 1)
            val lastTotal = last.items.last().subjectInfo.collectionStats.collect
            if (threshold != null && lastTotal < threshold) {
                stop = "末条收藏 $lastTotal 已低于第 $depth 名的 $threshold"
                break
            }
        }
        logger.info { "bgm-direct: recommendations $what 翻了 $pages 页 (${pool.size} 条), $stop" }
        return pool.distinctBy { it.subjectInfo.subjectId }
            .sortedByDescending { key(it.subjectInfo.collectionStats) }
            .take(depth)
            .map { details ->
                val info = details.subjectInfo
                RecommendedSubjectInfo(
                    bangumiId = info.subjectId,
                    nameCn = info.nameCn.ifEmpty { info.name },
                    imageLarge = info.imageLarge,
                )
            }
    }

    /** 新番那条榜从哪天起: 今天 (UTC+8, 与 bangumi 一致) 往前 [FEED_RECENT_DAYS] 天, `YYYY-MM-DD`. */
    private fun feedRecentCutoff(): String {
        val today = PackedDate.now()
        return LocalDate(today.year, today.month, today.day).minus(FEED_RECENT_DAYS, DateTimeUnit.DAY).toString()
    }

    /**
     * 一批推荐里「续作换成用户没看过的最早一季」的活儿. 只管种子那几行与「换换口味」 ——
     * 「本季」「大家最近在看」卖的就是当下在播的那一季, 不换.
     *
     * **组装出一行就开始回溯** ([track]), 不等整批算完: 回溯打的是 next.bgm.tv 的关系接口, 召回那些
     * 搜索打的是 api.bgm.tv, 两边不抢同一个 host 的并发名额.
     *
     * 分两段换, 都不让出卡多等:
     * 1. **落库前** ([applyReady]): 已经回溯完的格子当场换好 —— 多数格子这时已经查完, 关系命中缓存时是全部;
     * 2. **落库后** ([convertAfterShown]): 还没查完的等查完再原地改格子. 行结构不变, 卡片按下标组合
     *    (见 TvExplorationPage), 焦点不受影响. 这些格子排在行尾 (电视一屏露七张左右), 大多是在屏外换的.
     *
     * 回溯只顺前传走 ([SubjectSeriesIndexService.prequelChain]), 每个条目的邻居与 TMDB 匹配、详情页的
     * 系列索引共用一份缓存: 同一个条目谁先问谁发请求, 后来的直接拿.
     */
    private inner class SequelBatch(private val batchScope: CoroutineScope) {
        private inner class Row(val groupIndex: Int, val reserves: ArrayDeque<RecommendedSubjectInfo>) {
            /** 落库时还没定下来、留给 [convertAfterShown] 的格子 (条目 id). */
            val pending = HashSet<Int>()
        }

        private val rows = mutableListOf<Row>()
        private val chains = HashMap<Int, Deferred<PrequelChain?>>()
        private val permits = Semaphore(SEQUEL_WALK_PARALLELISM)
        private val fetched = atomic(0)

        /** 页面上已经有的 (连同轮播): 换上去的不能跟任何一格撞. [applyReady] 建起来, 两段共用. */
        private val shown = HashSet<Int>()
        private val changes = mutableListOf<String>()
        private var changedBeforeShown = 0

        /** 第 [groupIndex] 组要换季: 行里的条目当场开始回溯, 名字越像续作越先查. */
        fun track(groupIndex: Int, items: List<RecommendedSubjectInfo>, reserves: List<RecommendedSubjectInfo>) {
            rows += Row(groupIndex, ArrayDeque(reserves))
            items.sortedByDescending { sequelHint(it.nameCn) }.forEach { chainOf(it.bangumiId) }
        }

        /** 回溯结果; 失败时为 `null`, 那一格不换. */
        private fun chainOf(subjectId: Int): Deferred<PrequelChain?> = chains.getOrPut(subjectId) {
            batchScope.async {
                permits.withPermit {
                    try {
                        seriesIndexService.prequelChain(subjectId, MAX_PREQUEL_HOPS, fetched) { isSeasonFormat(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "bgm-direct: recommendations 回溯 $subjectId 的前传失败, 这一格不换" }
                        null
                    }
                }
            }
        }

        /**
         * 落库前调: 已经回溯完的格子当场换好, 行排成「定下来的在前, 还没定的在后」. 还没定的 (没查完, 或者
         * 最早那季撞了车、要等候补查完) 按名字里的续作迹象排, 越像越靠后, 落库后由 [convertAfterShown] 接着换.
         */
        suspend fun applyReady(
            groups: List<RecommendationGroup>,
            collected: Set<Int>,
            onCarousel: Set<Int>,
        ): List<RecommendationGroup> {
            groups.forEach { group -> group.items.mapTo(shown) { it.bangumiId } }
            shown += onCarousel
            if (rows.isEmpty()) return groups
            val result = groups.toMutableList()
            for (row in rows) {
                val group = groups[row.groupIndex]
                val settled = mutableListOf<RecommendedSubjectInfo>()
                val pending = mutableListOf<RecommendedSubjectInfo>()
                for (item in group.items) {
                    val chain = chains[item.bangumiId]
                    if (chain == null || !chain.isCompleted) {
                        pending += item
                        continue
                    }
                    // 已经完成, await 不会挂起
                    val target = earliestUnwatched(chain.await(), collected)
                    when {
                        target == null -> settled += item
                        target.id !in shown && !isLongRunningNode(target) -> settled += replace(item, target.toInfo())
                        else -> pending += item
                    }
                }
                pending.mapTo(row.pending) { it.bangumiId }
                result[row.groupIndex] = RecommendationGroup(
                    group.kind,
                    group.titleArg,
                    settled + pending.sortedBy { sequelHint(it.nameCn) },
                )
            }
            changedBeforeShown = changes.size
            return result
        }

        /**
         * 落库后调: 等还没定的格子查完, 原地换掉, 一行一个事务 (页面每行只刷新一次).
         *
         * @param groupRows 刚落库的表, 按组分好 (下标与 [track] 的 groupIndex 一致)
         */
        fun convertAfterShown(groupRows: List<List<RecommendationFeedEntity>>, collected: Set<Int>) {
            if (rows.isEmpty()) return
            batchScope.launch {
                val startMillis = currentTimeMillis()
                for (row in rows) {
                    if (row.pending.isEmpty()) continue
                    val entities = groupRows.getOrNull(row.groupIndex) ?: continue
                    val updates = mutableListOf<RecommendationFeedEntity>()
                    for (entity in entities.asReversed()) {
                        if (entity.subjectId !in row.pending) continue
                        val target = earliestUnwatched(chains[entity.subjectId]?.await(), collected) ?: continue
                        val to = if (target.id !in shown && !isLongRunningNode(target)) {
                            target.toInfo()
                        } else {
                            // 那一季已经在页面上了 (比如两部续作回到了同一个第一季), 或者它是超长篇 (犬夜叉完结篇
                            // 回到 167 集的犬夜叉 —— 续篇本身也不该推): 这一格换个候补
                            nextReserve(row.reserves, collected) ?: continue
                        }
                        replace(RecommendedSubjectInfo(entity.subjectId, entity.nameCn, entity.imageLarge), to)
                        updates += entity.copy(subjectId = to.bangumiId, nameCn = to.nameCn, imageLarge = to.imageLarge)
                    }
                    if (updates.isNotEmpty()) feedDao.replaceItems(updates)
                }
                logger.info {
                    "bgm-direct: recommendations 续作换季 ${changes.size} 格 (出卡前 $changedBeforeShown, " +
                            "出卡后 ${changes.size - changedBeforeShown}) $changes, 回溯共 ${fetched.value} 个请求, " +
                            "落库后 ${currentTimeMillis() - startMillis}ms 换完"
                }
            }
        }

        private fun replace(from: RecommendedSubjectInfo, to: RecommendedSubjectInfo): RecommendedSubjectInfo {
            shown -= from.bangumiId
            shown += to.bangumiId
            changes += "${from.nameCn} -> ${to.nameCn}"
            return to
        }

        /** 候补里下一个能用的: 不在页面上; 它自己是续作的话同样换成最早一季. */
        private suspend fun nextReserve(
            reserves: ArrayDeque<RecommendedSubjectInfo>,
            collected: Set<Int>,
        ): RecommendedSubjectInfo? {
            while (reserves.isNotEmpty()) {
                val reserve = reserves.removeFirst()
                if (reserve.bangumiId in shown) continue
                val target = earliestUnwatched(chainOf(reserve.bangumiId).await(), collected) ?: return reserve
                if (target.id !in shown && !isLongRunningNode(target)) return target.toInfo()
            }
            return null
        }
    }

    /**
     * 这一格该换成的那一季: 比它**播得早**、没收藏过的 TV/WEB 前传里**播出最早**的那个.
     * `null` = 不换 (没有前传、前面几季都收藏了, 或者回溯失败).
     *
     * 按播出日期挑, 不按前传链走到头: bangumi 的「前传」是故事时间线上的, 后来才做的前传会挂在第一季
     * 前面 (苍穹之法芙娜 2004 年 TV 版的前传是 2005 年的特别篇 RIGHT OF LEFT), 走到头就换成了它.
     * 前面几季看过、中间某季没看的, 挑出来的就是中间那季: 推荐的意思是"接下来看什么".
     * 日期缺的排在有日期的后面, 都缺时按前传链上更远的算.
     */
    private fun earliestUnwatched(chain: PrequelChain?, collected: Set<Int>): SeriesNode? {
        if (chain == null) return null
        val selfDate = chain.self?.airDate ?: PackedDate.Invalid
        return chain.prequels.withIndex()
            .filter { (_, node) ->
                node.id !in collected && !node.nsfw && isSeasonFormat(node) &&
                        !(node.airDate.isValid && selfDate.isValid && node.airDate >= selfDate)
            }
            .minWithOrNull(compareBy<IndexedValue<SeriesNode>>({ it.value.airDate }, { -it.index }))
            ?.value
    }

    /** 回溯到的那一季是不是超长篇, 判据同 [Sampling.isLongRunning]. */
    private fun isLongRunningNode(node: SeriesNode): Boolean {
        val episodes = node.episodes
        return if (episodes != null) {
            episodes >= LONG_RUNNING_EPISODES
        } else {
            node.airDate.isValid && PackedDate.now().year - node.airDate.year >= ONGOING_LONG_YEARS
        }
    }

    /**
     * 能当「一季」的: 形态是 TV / WEB, 且至少 [MIN_SEASON_EPISODES] 集. 剧场版、OVA、总集篇不算 —— 续作前面常夹着
     * 一部剧场版 (来自深渊第二季的前传是剧场版「深沉灵魂的黎明」), 换成它不是用户要的"从头看"; 一两集的特别篇、
     * 前导短篇也不算 (苍穹之法芙娜的 RIGHT OF LEFT 标的是 TV、1 话; 普罗米亚的前日谭是 WEB、2 话).
     * 官方标签里没写形态、没写集数的当不知道, 放行.
     */
    private fun isSeasonFormat(node: SeriesNode): Boolean =
        when (node.metaTags.firstOrNull { it in CanonicalTagKind.Category.values }) {
            null, "TV", "WEB" -> node.episodes.let { it == null || it >= MIN_SEASON_EPISODES }
            else -> false
        }

    private fun SeriesNode.toInfo() = RecommendedSubjectInfo(
        bangumiId = id,
        nameCn = nameCn.ifEmpty { name },
        imageLarge = imageLarge,
    )

    /** 种子一行的两份料, 见 [fetchSeedPool]. */
    private class SeedPool(
        val recs: List<Candidate>,
        /** 补齐时 AND 搜的那几个兴趣标签 (日志用). */
        val similarTags: List<String>,
        val similar: List<Candidate>,
    )

    /**
     * 拉种子一行的两份料: `/recs` (共看) 与标签补齐. 两者互不依赖, 一起发.
     *
     * 补齐**总是要发**: `/recs` 最多 [RECS_PER_SEED] 条, 永远凑不满一行 ([GROUP_SIZE]), 所以不必
     * 等 recs 回来再判断要不要补 —— 这也是两者能一起发的原因. 没有兴趣标签、补不了的种子在
     * [seedSkipReason] 就被跳过了, 走不到这里.
     */
    private suspend fun fetchSeedPool(
        seed: InterestProfile.Seed,
        collections: List<SubjectCollectionEntity>,
        requests: AtomicInt,
        sampling: Sampling,
    ): SeedPool = coroutineScope {
        val similarTags = seedInterestTags(seed.subjectId, collections).take(SEED_SIMILAR_TAGS)
        val recs = async { fetchRecs(seed.subjectId).also { requests.incrementAndGet() } }
        val similar = async {
            // 头一页 + 随机几页, 不取排行榜头几页: 种子只有一个宽标签时 (「奇幻」), 头三页全是几十年前的经典,
            // 看得多的账号扣完已收藏正好剩那十几部, 一行 12 格照单全收 (2026-09-22 真机: 风之谷 / 少女革命 /
            // 千与千寻 / 十二国记一行)
            searchSampledByTags(
                similarTags, ratings = null, sampling.random(SALT_SEED_FILL + seed.subjectId), requests,
                randomPages = SEED_FILL_PAGES - 1,
            ).orEmpty()
        }
        SeedPool(recs.await(), similarTags, similar.await())
    }

    /**
     * 这个种子为什么不能出一行; `null` = 能用. 预判 ([plannedSeeds]) 与组装共用这一套.
     *
     * @param usedSeries 已经出过行的种子的系列
     * @param usedTags 已经出过行的种子各自的兴趣标签
     */
    private fun seedSkipReason(
        seed: InterestProfile.Seed,
        collections: List<SubjectCollectionEntity>,
        usedSeries: Set<String>,
        usedTags: List<Set<String>>,
    ): String? {
        // **同一系列不许出两行** —— 2026-09-07 真机上「因为你喜欢《死神 千年血战篇》」与
        // 「因为你喜欢《死神》」同时出现在页面上. 光靠下面的标签相似度拦不住: 那两部的高票标签里
        // 工作室、年份都不一样, Jaccard 反而掉到阈值以下.
        val series = seriesKeyOf(seed.name)
        if (series.isNotEmpty() && series in usedSeries) return "与已用的是同一系列"
        val tags = seedInterestTags(seed.subjectId, collections)
        // 补齐靠 AND 搜种子自己的兴趣标签, 一个都没有就补不了; 而 `/recs` 最多 [RECS_PER_SEED] 条,
        // 自己凑不满一行 —— 试了也只是白发一个请求, 再被整组丢掉.
        if (tags.isEmpty()) return "没有兴趣标签, 凑不满一行"
        // 风格相似度按**兴趣标签**比, 不是所有高票标签: 补齐那一路搜的就是这几个标签
        // (AND), 两个种子的兴趣标签一样, 两行内容就是同一个搜索结果的相邻切片 ——
        // 而工作室、年份这些高票标签只会把相似度冲淡, 让明明一样的两行判成不像.
        if (usedTags.any { tooSimilar(it, tags.toSet()) }) return "与已用的风格太像"
        return null
    }

    /**
     * 预判种子那一段会试哪几个, 好让它们的 recs 与补齐在召回阶段就一起发出去.
     *
     * 按"前面试的都成了行"把选择规则推演一遍 ([seedSkipReason] + 行数上限, 与组装时同一套),
     * 规则只看本地数据, 不花请求. 试的都成行是常态 (补齐三页六十条, 凑不满一行很少见), 这时预判
     * 出的正是组装时真正会试的那几个, 请求一个不多. 某行没凑满被丢掉时, 组装那边照原规则往下找,
     * 预判没料到的种子现拉 —— 结果与串行时一致, 只是那次慢一点.
     *
     * 预判出的种子在组装时一定仍然能用: 组装时"已用"的是预判时"已用"的子集 (只少了没成行的),
     * 拦得住的更少, 不会更多.
     */
    private fun plannedSeeds(
        orderedSeeds: List<InterestProfile.Seed>,
        collections: List<SubjectCollectionEntity>,
    ): List<InterestProfile.Seed> {
        val planned = mutableListOf<InterestProfile.Seed>()
        val usedSeries = HashSet<String>()
        val usedTags = mutableListOf<Set<String>>()
        for (seed in orderedSeeds) {
            if (planned.size >= minOf(MAX_SEED_ROWS, MAX_SEED_REQUESTS)) break
            if (seedSkipReason(seed, collections, usedSeries, usedTags) != null) continue
            planned += seed
            seriesKeyOf(seed.name).takeIf { it.isNotEmpty() }?.let { usedSeries += it }
            usedTags += seedInterestTags(seed.subjectId, collections).toSet()
        }
        return planned
    }

    /**
     * 几个来源交错合并: `A0 B0 C0 A1 B1 C1 …`
     *
     * 原先是 `flatten().shuffled()`: 拼起来的话第一个标签的一页就把整行占满, 所以要打散.
     * 但**合并后的位次还要当排名分用** (见 [Sampling.ranked], 输入必须按 rank 有序), 打乱之后那个位次
     * 就只是噪声, 年代权重等于在跟随机数相乘.
     * 交错两头都占: 每个标签都出头, 各自的排名顺序也还在.
     */
    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>(lists.sumOf { it.size })
        var i = 0
        while (true) {
            var any = false
            for (list in lists) {
                if (i < list.size) {
                    out += list[i]
                    any = true
                }
            }
            if (!any) break
            i++
        }
        return out
    }

    /**
     * 种子自己最有代表性的几个**兴趣**标签, 票数从高到低.
     *
     * 两个用处, 都要求"只留兴趣标签": ①**当搜索词**补齐种子行 (AND 搜前几个, 顺序决定了搜出来
     * 的东西离《X》有多近); ②比两个种子的风格像不像 —— 拿所有高票标签比会被工作室、年份冲淡,
     * 明明会搜出同一批东西的两个种子反而判成不像 (2026-09-07 两个死神同时出现).
     */
    private fun seedInterestTags(subjectId: Int, collections: List<SubjectCollectionEntity>): List<String> {
        val tags = collections.firstOrNull { it.subjectId == subjectId }?.tags.orEmpty()
        val confident = confidentTagsOf(tags)
        return tags.asSequence()
            .filter { it.name in confident && isInterestTag(it.name) }
            .sortedByDescending { it.count }
            .map { it.name }
            .toList()
    }

    /**
     * 条目自己的**高票**标签.
     *
     * 低票标签是极少数人打的, 拿来判口味只会引入噪声 —— 与画像那边同一个阈值
     * ([TAG_CONFIDENCE]), 两边判同一件事就该用同一把尺子.
     */
    private fun confidentTagsOf(tags: List<Tag>): Set<String> {
        val maxCount = tags.maxOfOrNull { it.count } ?: return emptySet()
        if (maxCount <= 0) return emptySet()
        return tags.asSequence()
            .filter { it.count.toDouble() / maxCount >= TAG_CONFIDENCE }
            .map { it.name }
            .toSet()
    }

    /**
     * 候选自己的高票**兴趣**标签: 在 [confidentTagsOf] 之上再筛掉不是兴趣方向的那些.
     *
     * 必须筛: 打分要除以标签数 (见 [tasteScore]), 而条目上挂着一堆
     * "2026年7月""TV""京都动画""日本" —— 它们永远匹配不上画像, 只会把分母撑大, 于是"标签挂得多"
     * 本身成了扣分项. 与画像用同一套词汇 (`isInterestTag`) 才对得上.
     */
    private fun interestTagsOf(tags: List<Tag>): Set<String> =
        confidentTagsOf(tags).filterTo(HashSet()) { isInterestTag(it) }

    /**
     * 一个候选有多贴合"用户认可过的口味"; `0` = 一个标签都对不上, 不配进「本季你可能会喜欢」.
     *
     * 除以 `sqrt(标签数)` 是必要的: 不除的话, 挂了三十个标签的热门长番光靠"撞上得多"就能压过
     * 真正对味的小众作品 —— 那这一行又变回热度榜了.
     */
    private fun tasteScore(candidate: Candidate, weights: Map<String, Double>): Double {
        if (candidate.tags.isEmpty()) return 0.0
        val hit = candidate.tags.sumOf { weights[it] ?: 0.0 }
        if (hit <= 0.0) return 0.0
        return hit / sqrt(candidate.tags.size.toDouble())
    }

    /**
     * 两个种子的标签重合到什么程度算"风格太像".
     *
     * 用 Jaccard (交/并) 而不是"交集有几个": 标签数量差很多时后者会误判 —— 一个只有 3 个标签的
     * 短篇与一个 15 个标签的长番交 3 个, 交集看着不大, 其实前者被后者完全覆盖.
     * 任一方标签为空时判"不像" (没有依据, 宁可放过).
     */
    private fun tooSimilar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val intersection = a.count { it in b }
        if (intersection == 0) return false
        return intersection.toDouble() / (a.size + b.size - intersection) >= SEED_SIMILARITY_LIMIT
    }

    private suspend fun fetchRecs(subjectId: Int): List<Candidate> = runCatching {
        bangumiSubjectApi {
            coroutineScope { getSubjectRecs(subjectId, limit = RECS_PER_SEED).body().data }
        }
    }.getOrElse {
        logger.warn(it) { "bgm-direct: recommendations 取 $subjectId 的 recs 失败" }
        emptyList()
    }.mapNotNull { rec ->
        val subject = rec.subject
        // 「看过这部的人也看过」不分条目类型, 漫画/游戏/三次元都会混进来 (实测 276792 的
        // 10 条推荐里有 3 条不是动画). 探索页只放动画.
        if (subject.type != BangumiNextSubjectType.Anime) return@mapNotNull null
        // **这一路的门槛比搜索那几路高** ([MIN_RECS_RATING_COUNT] 而不是 [MIN_RATING_COUNT]):
        // 共看数据天生带长尾 —— 种子自己不冷, 与它共看的第 5~9 条也会冷下去. 2026-09-22 真机上
        // 种子《彻夜之歌》那一行整行中位数只有 3177 收藏 (罗马浴场 2402 / 十字架与吸血鬼 CAPU2 /
        // 吸血鬼同盟), 全都过了 1000 这道线. 填不掉的坑由下面的标签补齐来填.
        if (subject.rating.total < MIN_RECS_RATING_COUNT) return@mapNotNull null
        // bangumi 按 `sim` (共看强度) 给的顺序在种子行里当位次分用 (见组装那边). 精简条目没有播出日期与
        // 收藏数, 但 `info` 串里有首播日期、官方标签里有形态与地区 —— 年代权重与地区过滤都靠这几样.
        Candidate(
            RecommendedSubjectInfo(
                bangumiId = subject.id,
                nameCn = subject.nameCN.ifEmpty { subject.name },
                imageLarge = subject.images?.large.orBangumiPlaceholder(),
            ),
            year = INFO_YEAR.find(subject.info)?.groupValues?.get(1)?.toIntOrNull(),
            format = formatOf(subject.metaTags, emptyList()),
            regions = subject.metaTags.filterTo(HashSet()) { it in CanonicalTagKind.Region.values },
            // 集数未知时 info 串里干脆不写「N话」
            episodes = INFO_EPISODES.find(subject.info)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 },
        )
    }

    /**
     * 带 [tags] (多个是「且」) 且在分数线 [ratings] 以上的作品, **头一页 + 在全部结果里随机挑的几页**, 按排名排.
     * 给「符合你口味的高分动画」(每个口味标签一次)、「换换口味」与种子行的补齐用.
     *
     * 只取排行榜头几页的话, 池子永远是这个标签评分最高的那几十部: 恰恰是用户最可能看过的那批, 看得多的账号
     * 扣完已收藏每次剩的都是同几部; 而且宽标签 (「科幻」) 的头几页全是几十年前的经典, 年代权重也排不开.
     * 随机页让池子覆盖整条分数线以上的范围; 挑哪几页跟着 [random] 走, 同一天同一批不变, 「换一批」才换.
     * 头一页顺便报出总数, 所以随机页只能等它回来再发.
     */
    private suspend fun searchSampledByTags(
        tags: List<String>,
        /** 分数线; `null` = 不限. */
        ratings: List<String>?,
        random: Random,
        requests: AtomicInt,
        /** 头一页之外随机取几页 (不够就全取). */
        randomPages: Int = 1,
        /**
         * 之前取过的页 (见 [highRatedPages]): 给了就只随机添**没取过的**页, 返回的是连同旧页在内的全部 ——
         * 池子随着「换一批」越滚越大. 不给就每次现取.
         */
        cache: TagPages? = null,
    ): List<Candidate>? {
        val filters = SubjectSearchFilters(
            tags = tags,
            ranks = listOf(">=1"),
            ratingCounts = listOf(">=$MIN_RATING_COUNT"),
            ratings = ratings,
            nsfw = false,
        )
        val pages = cache?.pages ?: HashMap()
        val total = if (cache != null && 0 in pages) {
            cache.total
        } else {
            requests.incrementAndGet()
            val (first, count) = searchWithTotal(filters, SearchSort.RANK, "sampled$tags#0") ?: return null
            pages[0] = first
            cache?.total = count
            count
        }
        // 头一页之后还有几页; 随机挑几页没取过的一起发, 按页序拼 (位次分仍按排名走)
        val restPages = (total - 1) / SEARCH_PAGE_SIZE
        val fresh = (1..restPages).filter { it !in pages }.shuffled(random).take(randomPages)
        requests += fresh.size
        coroutineScope {
            fresh.map { page ->
                async { page to search(filters, SearchSort.RANK, "sampled$tags#$page", offset = page * SEARCH_PAGE_SIZE) }
            }.awaitAll()
        }.forEach { (page, items) -> if (items != null) pages[page] = items }
        return pages.keys.sorted().flatMap { pages.getValue(it) }
    }

    /** 一个标签已经取过的页, 见 [searchSampledByTags] 的 cache. */
    private class TagPages(
        var total: Int = 0,
        val pages: MutableMap<Int, List<Candidate>> = HashMap(),
    )

    /** 全站 [HIGH_SCORE] 的作品里随机取一段. 给「高分经典」与各组凑不满时垫料用. */
    private suspend fun searchTopRated(random: Random): List<Candidate>? =
        searchPages(
            SubjectSearchFilters(
                ranks = listOf(">=1"),
                ratingCounts = listOf(">=$MIN_RATING_COUNT"),
                ratings = HIGH_SCORE,
                nsfw = false,
            ),
            SearchSort.RANK,
            "topRated",
            pages = RANK_POOL_PAGES,
            startOffset = random.nextInt(TOP_RATED_OFFSET_RANGE),
        )

    /**
     * 本季的候选池: TV 两页 + WEB 一页, 共 [SEASON_POOL_PAGES] 个请求.
     *
     * 取好几页而不是一页: 这一路要**按口味重排**, 池子越全挑出来的越贴合 —— 一季新番里能对上
     * 某个具体口味的本来就不多, 只看热度前 20 的话常常凑不满一行.
     */
    private suspend fun searchThisSeason(): List<Candidate>? = coroutineScope {
        val since = currentSeasonStart()
        // **只要连载形态的**: 「本季新番」里混进剧场版是错的 —— 那不是"从这一季开始一周追一集"
        // 的东西 (2026-09-07 实测: 本季 317 条里 62 条是剧场版, 热度第 11 就是一部).
        // 用官方 meta_tags 在**服务端**筛, 比拿 platform/集数在客户端猜干净得多 (`eps` 还常是 0).
        // TV 与 WEB 都要 —— 网络原创番是实打实的当季新番 (本季 73 条, 赛博朋克边缘行者 2、
        // JOJO 新篇都在里面); 而 meta_tags 之间是**且**关系, "TV 或 WEB"只能分两次问.
        val tv = async {
            searchPages(seasonFilters(since, "TV"), SearchSort.COLLECTION, "seasonTV", pages = SEASON_TV_PAGES)
        }
        val web = async {
            searchPages(seasonFilters(since, "WEB"), SearchSort.COLLECTION, "seasonWeb", pages = 1)
        }
        // 交错而不是拼接: 拼接的话位次分里 WEB 那批全排在 TV 之后, 白挨一轮降权
        val pool = interleave(listOfNotNull(tv.await(), web.await()))
        // **观众规模地板**: 交错让两个池子等权, 而它们的尾部质量差一个数量级 —— 2026-09-22 实测
        // 本季 TV 两页最低也有 2203 收藏 / 847 在看 (可选 163 条, 挑得出来), 而 WEB 只够 4 条,
        // 第 5 条起断崖 (1122 → 421 → 403 → … → 119), 尾部全是几十人在看的国产网络动画.
        // 交错把 WEB 第 13、15 条插到整池第 26、30 位, 再被下游按口味重排一捧就上了行首 ——
        // 真机上这一行前三名是 Clevatess II(4084) / 镇魂街(162) / 神通安昂(209).
        //
        // 地板挡在进池之前而不是靠降权: 下游那一路 (tasteScore) 压根没有规模项, 降权拦不住.
        val filtered = pool.filter { (it.activeAudience ?: 0) >= MIN_SEASON_AUDIENCE }
        if (filtered.size < pool.size) {
            logger.info {
                "bgm-direct: recommendations 本季池 ${pool.size} 条, " +
                        "${pool.size - filtered.size} 条没过观众规模地板 ($MIN_SEASON_AUDIENCE 在看+想看)"
            }
        }
        filtered.takeIf { it.isNotEmpty() }
    }

    /**
     * 「大家最近在看」的候选池: **近半年开播的, 按热度排**.
     *
     * 为什么不直接用热度榜 (`trendsRepository`): 那个接口给的是精简条目, 连收藏数都没有,
     * 于是这一组是整个推荐里**唯一一条既过滤不了也加不了权重**的路 —— 2026-09-22 真机上它
     * 推出过 703 收藏 / 35 人评分的东西, 榜的第 21~50 名尾部已经很虚.
     *
     * 换成搜索之后返回带 `collection`, 同一道地板与人气权重都能用上, 而请求数只多一个
     * (热度榜那次仍然要发, 轮播的排除集靠它).
     *
     * 半年这个窗口与 Ani 服务端那条线是同一个意思: 再往前就不叫"最近在看"了.
     */
    private suspend fun searchRecentHot(): List<Candidate>? {
        val today = PackedDate.now()
        // PackedDate 没有"减几个月", 自己按月序号算 (与 currentSeasonStart 同一套做法)
        val months = today.year * 12 + (today.month - 1) - RECENT_HOT_MONTHS
        val since = "${months / 12}-${(months % 12 + 1).toString().padStart(2, '0')}-01"
        return searchPages(
            SubjectSearchFilters(
                airDates = listOf(">=$since"),
                nsfw = false,
            ),
            SearchSort.COLLECTION,
            "recentHot",
            pages = RECENT_HOT_PAGES,
        )?.filter { (it.activeAudience ?: 0) >= MIN_SEASON_AUDIENCE }?.takeIf { it.isNotEmpty() }
    }

    /**
     * 本季那一路的筛选条件.
     *
     * **不加评分人数下限** (与排行榜那几组相反): 新番才播几集, 谁都还没评分, 一加整季筛空.
     * 也不能按排名排 —— 新番大多还没有排名, `ranks>=1` 同样会把整季筛空, 所以按热度来.
     */
    private fun seasonFilters(since: String, metaTag: String) = SubjectSearchFilters(
        airDates = listOf(">=$since"),
        metaTags = listOf(metaTag),
        nsfw = false,
    )

    /**
     * 连着取几页再拼起来 (并发发).
     *
     * 为什么非要多取几页: 服务端**一页只有 [SEARCH_PAGE_SIZE] 条**, 而一组要 [GROUP_SIZE] 条 ——
     * 池子 20 条挑 12 个, 等于八成照单收下, 重排 (年代/人气) 根本没有腾挪的空间.
     */
    private suspend fun searchPages(
        filters: SubjectSearchFilters,
        sort: SearchSort,
        what: String,
        pages: Int,
        startOffset: Int = 0,
    ): List<Candidate>? = coroutineScope {
        (0 until pages).map { page ->
            async {
                search(filters, sort, "$what#$page", offset = startOffset + page * SEARCH_PAGE_SIZE)
            }
        }.awaitAll().filterNotNull().flatten().takeIf { it.isNotEmpty() }
    }

    private suspend fun search(
        filters: SubjectSearchFilters,
        sort: SearchSort,
        what: String,
        offset: Int = 0,
    ): List<Candidate>? = searchWithTotal(filters, sort, what, offset)?.first

    /** 同 [search], 另外带上结果总数. */
    private suspend fun searchWithTotal(
        filters: SubjectSearchFilters,
        sort: SearchSort,
        what: String,
        offset: Int = 0,
    ): Pair<List<Candidate>, Int>? =
        searchPage(filters, sort, what, offset)?.let { page -> page.items.map { it.toCandidate() } to page.total }

    /** 取一页搜索结果 (原样, 连同服务端报的总数); 失败时记一笔, 返回 `null`. */
    private suspend fun searchPage(
        filters: SubjectSearchFilters,
        sort: SearchSort,
        what: String,
        offset: Int,
    ): SubjectSearchPage? = runCatching {
        searchService.searchSubjectsPage(
            keyword = "",
            offset = offset,
            limit = SEARCH_PAGE_SIZE,
            sort = sort,
            filters = filters,
        )
    }.onFailure {
        logger.warn(it) { "bgm-direct: recommendations 搜索 $what 失败" }
    }.getOrNull()

    private fun BatchSubjectDetails.toCandidate(): Candidate {
        val info = subjectInfo
        return Candidate(
            RecommendedSubjectInfo(
                bangumiId = info.subjectId,
                nameCn = info.nameCn.ifEmpty { info.name },
                imageLarge = info.imageLarge,
            ),
            year = info.airDate.year.takeIf { it > 0 },
            tags = interestTagsOf(info.tags),
            collectionCount = info.collectionStats.collect,
            dropRate = dropRateOf(info.collectionStats),
            activeAudience = info.collectionStats.let { it.doing + it.wish },
            format = formatOf(metaTags, info.tags),
            regions = metaTags.filterTo(HashSet()) { it in CanonicalTagKind.Region.values },
            episodes = mainEpisodeCount.takeIf { it > 0 },
        )
    }

    /**
     * 连载形态: 先看官方标签 (`meta_tags`, 维基人维护、已归一化), 没有再看用户标签里的 `Category` 类
     * (实测排行榜前 500 条里 TV 64.6% / 剧场版 25.0% / OVA 9.6%). 都没有 (资料不全) 时返回 `null`.
     */
    private fun formatOf(metaTags: List<String>, tags: List<Tag>): String? =
        metaTags.firstOrNull { it in CanonicalTagKind.Category.values }
            ?: confidentTagsOf(tags).firstOrNull { it in CanonicalTagKind.Category.values }

    /**
     * 与最强兴趣同类、但用户没碰过的一个标签.
     *
     * 同类是关键: 从"情绪"里挑另一个情绪标签, 与已有兴趣有一两个连接点, 而不是随便扔一个
     * 完全不相干的方向过去.
     */
    private fun neighborTagOf(profile: InterestProfile, random: Random): String? {
        // **跳过「角色」类**: 那一类窄得多 (实测 rank>=1 且上千人评分的: 制服 1 / 美少年 0 /
        // 美少女 6), 随机挑中一个就整行凑不出来 —— 而这一组只有一个来源, 没有交错补位.
        // "换换口味"要换的本来也是**方向** (类型/情绪/设定), 不是角色属性.
        val top = profile.tags.firstOrNull { CanonicalTagKind.matchOrNull(it.name) in NEIGHBOR_KINDS }
            ?: return null
        val kind = CanonicalTagKind.matchOrNull(top.name) ?: return null
        val own = profile.tags.mapTo(HashSet()) { it.name }
        return kind.values.filter { it !in own }.randomOrNull(random)
    }

    /** 本季开始那天, `YYYY-MM-01`. */
    private fun currentSeasonStart(): String {
        val today = PackedDate.now()
        val month = when (today.month) {
            in 1..3 -> 1
            in 4..6 -> 4
            in 7..9 -> 7
            else -> 10
        }
        return "${today.year}-${month.toString().padStart(2, '0')}-01"
    }

    /**
     * 召回出来的一个候选.
     *
     * [tags]、[collectionCount]、[dropRate] 只有搜索那一路才有: `/recs` 给的是精简条目 (`SlimSubject`),
     * 只能从 `info` 串与官方标签里取到 [year]、[format]、[regions].
     */
    private class Candidate(
        val info: RecommendedSubjectInfo,
        val year: Int? = null,
        val tags: Set<String> = emptySet(),
        /** 收藏这部的总人数 (想看+在看+看过+搁置+抛弃). */
        val collectionCount: Int? = null,
        /** 弃番率, 见 [dropRateOf]; 样本太少时为 `null`. */
        val dropRate: Double? = null,
        /**
         * **当下**有多少人在追这部: 在看 + 想看.
         *
         * 给新番用的规模信号. [collectionCount] 与评分人数都是**累积量**, 一部刚开播的番还没有
         * 时间攒 —— 而"多少人在看"当场就有 (2026-09-22 实测本季: 无职转生三期 15218 人在看,
         * 而同一页里的国产网络动画只有 17~98 人). 未开播的条目 `doing` 必然是 0, 所以把"想看"
         * 也算进来 (飙马野郎 9-25 开播, 1446 人想看).
         *
         * 这也是 Ani 服务端的做法: 它的新番榜按 `doing` 降序, 老番榜按收藏总数降序 ——
         * 同一个"观众规模"在作品的两个生命周期阶段本来就该用不同的字段量.
         */
        val activeAudience: Int? = null,
        /** 连载形态 (TV / WEB / 剧场版 / OVA…), 见 [formatOf]; 判不出来时为 `null`. */
        val format: String? = null,
        /** 出品地区 (日本 / 中国 / 美国 / 欧美…), 取自官方标签; 空 = 没标, 当不知道. 见 [Sampling.regionWeight]. */
        val regions: Set<String> = emptySet(),
        /** 集数; 未知 (连载中的长篇也是这样) 时为 `null`. 见 [Sampling.longRunningWeight]. */
        val episodes: Int? = null,
    )

    /**
     * 形态权重: 剧场版与 OVA 往后排, **但不封杀**.
     *
     * 推荐区卖的是"接下来追什么", 一行里混进剧场版/OVA 会打断这个语境; 但一刀切也不对 ——
     * 收藏里剧场版本来就不少, 推「莉兹与青鸟」给喜欢上低音号的人是对的.
     * (Ani 服务端的做法是硬过滤: 登录态那 200 条里剧场版与 OVA **一条都没有**, 2026-09-22 实测.
     * 那样会把用户的一块兴趣整个砍掉, 这里只降权.)
     *
     * 收藏人数足够多的剧场版照样能靠人气权重顶上来 —— 千与千寻那种量级乘 0.6 仍然高过
     * 一部三千收藏的 TV 番.
     */
    private fun formatWeight(format: String?): Double = when (format) {
        null, "TV", "WEB" -> 1.0
        "剧场版" -> 0.6
        "OVA" -> 0.45
        // 短片 / MV / CM / PV / 动态漫画: 不是"追番"的东西
        else -> 0.3
    }

    /**
     * 跟"这一批推荐"绑定的东西: 随机种子与用户常看的地区. 一次重算里所有带随机、带权重的地方都从这里取.
     *
     * **种子按"日期 + 换一批次数"定 ([sampleSeed]), 每个候选的随机数由种子与它的 id 哈希出来**
     * ([unitRandom]): 登录后收藏分批同步、每稳定一次就重算一轮 (十几秒一次), 每轮重新摇的话行会在
     * 用户眼皮底下换. 同一天、没按「换一批」时, 同样的候选拿到同样的随机数, 结果不动; 随机数跟着 id 走
     * 而不是跟着位置走, 池子小变 (多一条少一条) 也不会整行洗牌.
     */
    private inner class Sampling(
        val seed: Long,
        /** 用户收藏里占比够的地区, 见 [regionWeight]. */
        private val watchedRegions: Set<String>,
        /** 用户看过的地区, 见 [regionAllowed]; `null` = 没有收藏可参考, 不过滤. */
        val collectedRegions: Set<String>?,
    ) {
        private val currentYear = PackedDate.now().year

        /** 这一批里的一个专用随机源 (挑邻居标签、高分榜的起点…); [salt] 区分用途, 免得几处拿到同一串数. */
        fun random(salt: Int): Random = Random(seed xor (salt.toLong() * GOLDEN_GAMMA))

        /** 排行榜那几组与种子行的重排权重: 年代 × 人气 × 形态 × 地区 × 篇幅. */
        fun rankGroupWeight(candidate: Candidate): Double =
            ageWeight(candidate.year) * popularityWeight(candidate.collectionCount) *
                    formatWeight(candidate.format) * regionWeight(candidate) * longRunningWeight(candidate)

        /**
         * 超长篇 (航海王、哆啦A梦、柯南、蜡笔小新…) 大幅降权: 这类作品收藏量巨大、标签挂得多、分数也不低,
         * 什么口味都能把它带出来. 判据二选一 —— 集数到 [LONG_RUNNING_EPISODES]; 或者集数未知却已经播了
         * [ONGOING_LONG_YEARS] 年以上 (还在连载的长篇 bangumi 不填总集数: 哆啦A梦 2005 版、蜡笔小新都是 0,
         * 而正常的番播完就填上了). 两季、四季的长番 (二三十集到五六十集) 碰不到.
         */
        fun longRunningWeight(candidate: Candidate): Double =
            if (isLongRunning(candidate)) LONG_RUNNING_WEIGHT else 1.0

        /** 是不是超长篇, 判据见 [longRunningWeight]. 组装时它们只进放宽那一遍 (见 takeGroup). */
        fun isLongRunning(candidate: Candidate): Boolean {
            val episodes = candidate.episodes
            return if (episodes != null) {
                episodes >= LONG_RUNNING_EPISODES
            } else {
                candidate.year != null && currentYear - candidate.year >= ONGOING_LONG_YEARS
            }
        }

        /**
         * 年代权重: 离现在越远越低, 而且降得很快 —— [FRESH_YEARS] 年内的不压, 之后每 [AGE_HALF_LIFE_YEARS] 年
         * 减半: 十年前约 0.5、二十年前约 0.19、三十年前约 0.07、四十年前约 0.03. 画风老的作品大部分人不会点,
         * 分再高也不该占位置; 降权不封杀, 收藏人数足够多的经典偶尔还能出现.
         *
         * 年份未知的按 [UNKNOWN_YEAR_WEIGHT] 算: 搜索结果里没有播出日期的多半是资料不全的冷门条目.
         */
        fun ageWeight(year: Int?): Double {
            if (year == null) return UNKNOWN_YEAR_WEIGHT
            val age = (currentYear - year - FRESH_YEARS).coerceAtLeast(0)
            return 0.5.pow(age.toDouble() / AGE_HALF_LIFE_YEARS)
        }

        /**
         * 用户没看过的地区 (收藏里占比不到 [COLLECTED_REGION_SHARE]), 直接不推 —— 比 [regionWeight] 的降权
         * 更进一步. 多地区合拍的只要有一个地区看过就放行; 没标地区的当不知道, 也放行.
         */
        fun regionAllowed(candidate: Candidate): Boolean =
            collectedRegions == null || candidate.regions.isEmpty() || candidate.regions.any { it in collectedRegions }

        /**
         * 地区权重: 用户**不怎么看**的地区往后排, 但不封杀.
         *
         * "不怎么看"按用户自己的收藏算 (占比不到 [WATCHED_REGION_SHARE] 的地区), 不写死"非日本降权" ——
         * 看国漫多的人, 中国的作品就不该被压. 宽标签 (「战斗」) 会带出瑞克和莫蒂、双城之战这类欧美动画,
         * 靠的就是这一项往后排. 没标地区的当不知道, 不压.
         */
        fun regionWeight(candidate: Candidate): Double = when {
            candidate.regions.isEmpty() -> 1.0
            candidate.regions.any { it in watchedRegions } -> 1.0
            else -> UNWATCHED_REGION_WEIGHT
        }

        /**
         * 按"位次分 × 权重"做**加权随机抽样**排序.
         *
         * 两件事:
         *
         * 1. **位次分有地板** ([RANK_SCORE_FLOOR]): 服务端给的顺序 (`sort=rank` 是全站评分排名) 只占
         *    一半话语权, 另一半交给权重. 不压的话第 1 名与第 40 名差出整整 1.0, 年代/人气/形态那几个
         *    0.15~1.0 的系数根本翻不动盘 —— 于是"分高但没人看"的条目照样坐在行首.
         * 2. **抽样而不是取前 N** (Efraimidis-Spirakis: `key = U^(1/w)`, 按 key 降序等价于按 w 无放回
         *    抽样): 取前 N 的话, 一个冷门条目只要挤进前 12 就钉在那儿. 抽样之后它变成"偶尔出现" ——
         *    权重低不等于永不出现, 换一批就能刷出不同的一行.
         */
        fun ranked(
            candidates: List<Candidate>,
            weightOf: (Candidate) -> Double,
            positional: Boolean = true,
        ): List<Candidate> = ranked(candidates, idOf = { it.info.bangumiId }, weightOf = weightOf, positional = positional)

        /**
         * 同上, 给任意条目用 (「换一批」挑种子也是它); [idOf] 决定每一项拿到哪个随机数.
         *
         * @param positional `false` = 不算位次分, 只看权重. 给"池子就是一条分数线以上的全部"那种组用:
         *   池子里谁排第几没有意义, 算了反而让排行榜头一页的那几部每批都在.
         */
        fun <T> ranked(items: List<T>, idOf: (T) -> Int, weightOf: (T) -> Double, positional: Boolean = true): List<T> {
            if (items.size <= 1) return items
            // **key 必须先算好再排**: 写成 `sortedByDescending { ...随机... }` 的话, TimSort 会对同一个
            // 元素反复调用 selector 取 key —— 随机数若每次不同, 比较不自洽, 排序器当场抛
            // `IllegalArgumentException: Comparison method violates its general contract!`, 整次重算失败
            // (2026-09-22 真机踩到). 排序键一律先物化成一列值.
            return items.withIndex()
                .map { (index, item) ->
                    val rankScore = if (!positional) {
                        1.0
                    } else {
                        RANK_SCORE_FLOOR + (1.0 - RANK_SCORE_FLOOR) * (1.0 - index.toDouble() / items.size)
                    }
                    val weight = rankScore * weightOf(item)
                    item to if (weight <= 0.0) 0.0 else unitRandom(seed, idOf(item)).pow(1.0 / weight)
                }
                .sortedByDescending { it.second }
                .map { it.first }
        }
    }

    /**
     * 这一批的随机种子: **同一天、没按「换一批」时不变**. 日期按 bangumi 的 UTC+8 算 ([PackedDate.now]),
     * 过了北京时间零点才换.
     */
    private fun sampleSeed(): Long {
        val today = PackedDate.now()
        return (today.year * 10_000L + today.month * 100 + today.day) * GOLDEN_GAMMA + shuffleCount
    }

    /**
     * 本进程里按过几次「换一批」. 只在 [refreshOnce] 里读写 ([refreshMutex] 串着).
     *
     * 不落库: 杀进程后回到当天的底种子, 只有"重启之后又恰好要重算"时才会看到换一批之前那组 —— 罕见,
     * 为它给缓存表加一列不值当.
     */
    private var shuffleCount = 0

    /**
     * 本进程里「换一批」这一轮用过的种子 (条目 id): 每按一次, 用过的都往后放, 全部轮完一遍才从头再来.
     * 只躲上一批的话隔一批就轮回来了 —— 排在前面的喜爱度高, 更常被抽中. 读写规矩与不落库的理由同 [shuffleCount].
     */
    private val rotatedSeeds = HashSet<Int>()

    /**
     * 连着按「换一批」时最近几批的全部条目 (最多 [RECENT_BATCHES_TO_AVOID] 批), 各行先躲开它们.
     * 只躲上一批的话会在两组之间来回 (第三次又回到第一次权重最高的那几部). 平时的重算清空它.
     * 读写规矩同 [shuffleCount].
     */
    private val recentBatches = ArrayDeque<Set<Int>>()

    /**
     * 「符合你口味的高分」各标签当天已经取过的页 (见 [searchSampledByTags] 的 cache): 每次重算只随机添几页
     * 没取过的, 能用的池子随「换一批」越滚越大 (两轮重复的期望是 144 / 能用的条数, 要压到 1 以下得有一百四五十部),
     * 请求数却不跟着涨. 过了北京时间零点清空. 读写规矩同 [shuffleCount].
     */
    private val highRatedPages = HashMap<String, TagPages>()
    private var highRatedPagesDate = PackedDate.Invalid

    /** 由 [seed] 与条目 id 确定的 [0, 1) 均匀数 (splitmix64 的混合步): 同种子同 id 永远是同一个数. */
    private fun unitRandom(seed: Long, subjectId: Int): Double {
        var z = seed + subjectId * GOLDEN_GAMMA
        z = (z xor (z ushr 30)) * SPLITMIX_MUL_1
        z = (z xor (z ushr 27)) * SPLITMIX_MUL_2
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /**
     * 弃番率 = 抛弃 / **真的开始看过的人** (在看 + 看过 + 搁置 + 抛弃).
     *
     * 分母刻意不含"想看": 那些人一集都没看, 谈不上弃不弃. 样本少于 [MIN_DROP_SAMPLE] 时返回
     * `null` —— 几十个人里有俩弃了算不出什么, 拿噪声当判据比不用更糟.
     */
    private fun dropRateOf(stats: SubjectCollectionStats): Double? {
        val started = stats.doing + stats.done + stats.onHold + stats.dropped
        if (started < MIN_DROP_SAMPLE) return null
        return stats.dropped.toDouble() / started
    }

    /**
     * 弃番率权重: 越多人追不下去越低.
     *
     * **只用在「本季」那一组**. 排行榜那几组用不上: 有排名的都是幸存者, 实测弃番率清一色
     * 0.2%~2.2% (千与千寻 0.2% / 虫师 1.9%), 区分不出东西.
     *
     * 而本季那一行它非常有效 —— 那是唯一一处"热度会骗人"的地方: 2026-09-07 实测本季按热度排
     * 第 4 的条目 (京阿尼出品) 弃番率 9.9%, 榜上还有 18.1% 的, 而 Re:Zero 第四季只有 0.6%.
     * 分数和热度都看不出这件事, 只有它能.
     *
     * 档位按实测分布定 (本季 0.6%~18%).
     */
    private fun dropRateWeight(dropRate: Double?): Double = when {
        dropRate == null -> 1.0
        dropRate < 0.02 -> 1.0
        dropRate < 0.04 -> 0.9
        dropRate < 0.07 -> 0.7
        dropRate < 0.12 -> 0.5
        else -> 0.3
    }

    /**
     * 人气权重: 看过的人越少越低, **但不封杀**.
     *
     * 为什么需要: bangumi 的 `rank` 只看分数, **不管观众规模** —— 于是"1981 年、292 人评过分、
     * 8.2 分"的作品能排进「机战」标签的前 20 (2026-09-07 实测), 用户看到的就是一行"没听过的
     * 老画风冷门番". [MIN_RATING_COUNT] 那道服务端下限已经砍掉了最极端的, 这里负责把剩下的
     * 排后面.
     *
     * 档位按实测分布定 (排行榜里的收藏人数从一千多到八万): 三万以上是"大家都看过"的量级
     * (命运石之门 7.9 万 / 芙莉莲 7.5 万 / EVA 6.5 万), 一万以下就算小众了
     * (银河英雄传说 1.0 万、水星领航员三期 1.0 万).
     *
     * 拿不到人数时给 1.0 而不是压低: 那只发生在**不重排的那几路** (recs/热门给的是精简条目),
     * 真走到这里说明数据缺了, 不该让它替判断.
     */
    private fun popularityWeight(collectionCount: Int?): Double = when {
        collectionCount == null -> 1.0
        collectionCount >= 30_000 -> 1.0
        collectionCount >= 15_000 -> 0.8
        collectionCount >= 8_000 -> 0.55
        collectionCount >= 3_000 -> 0.3
        else -> 0.15
    }

    private companion object {
        /** 缓存多久算过期. 推荐不是时效性内容, 不必勤快. */
        val TTL_MILLIS = 12.hours.inWholeMilliseconds

        /** 缓存里已有结果时, 进页到开始重算之间等多久 —— 让首屏先画完. */
        val FIRST_FRAME_GRACE = 3.seconds

        /** 缓存是空的 (装完第一次进页) 时等多久: 只让首帧过去, 别让推荐区空着干等. */
        val COLD_START_GRACE = 500.milliseconds

        /** 收藏条数不再变化多久才算"同步完了". */
        val COLLECTION_SETTLE_DEBOUNCE = 15.seconds

        /** 重算开头最多等会话状态多久 (启动时它要等 token 刷新完才出来), 见 [refreshOnce]. */
        val SESSION_STATE_WAIT = 10.seconds

        /** 每组几条. 与电视上一行的卡片数一致, 一组正好一行. */
        const val GROUP_SIZE = 12

        /**
         * 本季 / 近半年那几路的**观众规模地板**: 在看 + 想看少于这个数的不进池.
         *
         * 这个数是查着定的: 2026-09-22 实测本季池子 (TV 两页 + WEB 一页) 在 350~870 之间有一段
         * 空白 —— 过得去的最低是「缎带英雄」868, 挡掉的最高是「时光代理人 第三季」347,
         * 再往下是 139 / 137 / 66 / 51 的国产网络动画. 取中间值留余量.
         *
         * 影响面: TV 那两页 40 条一条不动 (最低也有 847 在看), WEB 那页 20 条剩 5 条,
         * 池子 60 → 45, 仍然够挑满一行 12 个.
         *
         * **不能换成评分人数**: 新番没时间攒评分 (本季「炒翻天」5760 收藏只有 2027 人评分,
         * 刚开播的更极端), 那道线会把整季筛空 —— 这也正是 [seasonFilters] 不加 `rating_count`
         * 的原因.
         */
        const val MIN_SEASON_AUDIENCE = 600

        /**
         * 「大家最近在看」往前看几个月.
         *
         * 半年: 再往前就不叫"最近"了. 与 Ani 服务端把"新番榜/老番榜"切开的那条线同一个意思
         * (它是 180 天, 见 memory).
         */
        const val RECENT_HOT_MONTHS = 6

        /** 「大家最近在看」取几页: 地板会砍掉一部分, 一页 20 条挑不出 12 个. */
        const val RECENT_HOT_PAGES = 2

        /** 「推荐」(没有收藏时那一整组, 见 [computeFeed]) 里老番那条榜取几条, 与 Ani 匿名首页一致. */
        const val FEED_CLASSIC_COUNT = 80

        /** 「推荐」里新番那条榜取几条, 与 Ani 匿名首页一致. */
        const val FEED_RECENT_COUNT = 120

        /**
         * 「推荐」里新番与老番的分界: 往前这么多天以内开播的算新番.
         *
         * 从 Ani 原榜反推的: 2026-09-22 取的榜里 03-28 开播的在新番那条, 而 03-19 开播、在看人数远超新番榜尾的
         * 哪条都没进 —— 切分线落在这两天之间, 即当天往前 180 天左右.
         */
        const val FEED_RECENT_DAYS = 180

        /** 「推荐」按位置分槽: 每 [FEED_SLOT_CYCLE] 个位置里前 [FEED_CLASSIC_SLOTS] 个放老番, 其余放新番. */
        const val FEED_SLOT_CYCLE = 5

        /** 见 [FEED_SLOT_CYCLE]. */
        const val FEED_CLASSIC_SLOTS = 2

        /** 「推荐」的两条榜一批并发取几页, 取完一批判一次要不要接着翻 (见 [searchTopBy]). */
        const val FEED_PAGE_BATCH = 4

        /** 「推荐」每条榜最多翻几页. 2026-09-24 实测各 12 页左右, 这个数只兜异常. */
        const val FEED_MAX_PAGES = 20

        /**
         * 服务端给的顺序在重排里最少占多少分 (见 [Sampling.ranked]).
         *
         * 0.5 = "排名说一半, 年代/人气/形态说另一半". 不设地板时位次分从 1.0 线性掉到 0,
         * 第一名与第四十名差出整整 1.0, 而那几个系数加起来才 0.15~1.0 —— 翻不动盘,
         * 于是"分高但没人看"的照样坐在行首 (「天体战士」那类).
         */
        const val RANK_SCORE_FLOOR = 0.5

        /** "因为你喜欢"最多试几个种子 (每个一个请求). */
        const val MAX_SEED_REQUESTS = 5

        /** 最多出几行种子推荐. 多几行是为了有层次, 但每行一个请求, 别贪. */
        const val MAX_SEED_ROWS = 3

        /**
         * 补齐种子行时 AND 几个标签.
         *
         * 越多越像《X》但召回越少: 实测种子前 1/2/3 个标签 AND 起来是 706/605/326 条 (死神
         * 千年血战篇), 3 个既够近又远远够填一行.
         */
        const val SEED_SIMILAR_TAGS = 3

        /** 两个种子的标签 Jaccard 到这个数就算"风格太像", 后一个不再出行. */
        const val SEED_SIMILARITY_LIMIT = 0.5

        /**
         * 高分组最多用几个兴趣标签 (每个一个请求).
         *
         * 画像收了「角色」类之后从 3 加到 4: 那些标签窄, 混更多个进来才不至于让一两个窄标签
         * 决定整行 —— 各标签的结果是**交错**合并的, 多一个就多一路补位.
         */
        const val MAX_TAG_QUERIES = 4

        /**
         * 每次搜索要几条.
         *
         * **服务端硬性一页 20 条**: `POST /v0/search/subjects` 不管 `limit` 传多少都只给 20
         * (2026-09-07 实测: 传 50 返回 20, `total` 照报 317/1000). 所以这个数既是"要几条",
         * 也是**翻页的步长** —— 写成 50 的话 `offset = 页号 * 50` 会跳过第 20~49 条.
         */
        const val SEARCH_PAGE_SIZE = 20

        /**
         * 排行榜那几组要多少人评过分才算数.
         *
         * bangumi 的 `rank` 只看分数不看观众规模 —— 不设下限的话, "1981 年、292 人评分、8.2 分"
         * 这类条目能排进某个标签的前 20 (2026-09-07 实测「机战」标签). 服务端筛 (`rating_count`)
         * 比客户端降权干净: 那些条目连池子都进不来.
         *
         * 一千这个数是查着定的: 「机战」631→176 条、「武侠」146→17 条、「耽美」214→24 条 ——
         * 最窄的标签也还够一行 (要 12 条), 而再往上到两千, 武侠只剩 9 条就凑不满了.
         *
         * **本季那一组不能用它** (新番还没人评分, 一加就筛空).
         */
        const val MIN_RATING_COUNT = 1000

        /**
         * 种子那一路 (`/recs`) 的评分人数下限, 比 [MIN_RATING_COUNT] 严一档.
         *
         * 共看数据天生带长尾: 种子自己不冷, 排在第 5~9 位的共看作品也会冷下去 ——
         * 2026-09-22 真机上种子《彻夜之歌》那一行中位数只有 3177 收藏 (罗马浴场 2402 收藏 /
         * 十字架与吸血鬼 CAPU2 / 吸血鬼同盟), 全都过了 1000 这道线.
         *
         * **不能提太高**: 2026-09-22 真机上取 2000 时五个种子里四个的 recs 被清成 0 条
         * (只有《机动战士Z高达》那种老番的共看对象评分人数够), 整一路的价值 —— bangumi 按
         * 共看强度给的那个顺序 —— 就没了, 补齐进来的标签搜索结果顶不上.
         */
        const val MIN_RECS_RATING_COUNT = 1200

        /**
         * 种子行的标签补齐一共取几页: 头一页 + 随机 (这个数 - 1) 页 (见 [searchSampledByTags]).
         *
         * 一页 20 条不够: 收藏几百部的账号 (真机 619 条) 补进来的大半会被"已收藏 / 同系列 /
         * 跨组去重"吃掉 —— 2026-09-22 实测补 20 条之后只剩 3~11 条, 而一行要 12 个,
         * 五行种子推荐全被丢掉. 三页六十条留够被吃的余量.
         */
        const val SEED_FILL_PAGES = 3

        /**
         * 「高分经典」与全站高分垫料取几页 (见 [searchTopRated]): 单个来源, 一页 20 条挑 12 个等于没得挑,
         * 重排使不上劲.
         */
        const val RANK_POOL_PAGES = 2

        /**
         * 本季 TV 那一路取几页 (每页 [SEARCH_PAGE_SIZE] 条); WEB 那一路固定一页.
         *
         * 要按口味重排, 池子小了挑不出一行: 一季新番里能对上某个具体口味的本来就不多, 只看
         * 热度前 20 的话常常连半行都凑不满. 二比一是按实测的量分的 (本季 TV 135 条、WEB 73 条).
         */
        const val SEASON_TV_PAGES = 2

        /** 本季一共发几个请求 (TV 两页 + WEB 一页). */
        const val SEASON_POOL_PAGES = SEASON_TV_PAGES + 1

        /**
         * 弃番率至少要有多少人开始看过才算数.
         *
         * 几十个人里有俩弃了说明不了什么 —— 而本季那一行的候选都是热度前列的, 实测样本都在
         * 两千以上 (最高的两个 18.1% / 10.8% 分别是 2570 和 2279 人), 这道门只挡真正的冷门条目.
         */
        const val MIN_DROP_SAMPLE = 200

        /**
         * 「换换口味」允许从哪几类里挑邻居标签.
         *
         * 见 [neighborTagOf] —— 刻意不含 `Character`. 也不含 `Source`: 从"漫画改"换到"游戏改"
         * 换的是制作来源, 不是口味.
         */
        val NEIGHBOR_KINDS = setOf(
            CanonicalTagKind.Genre,
            CanonicalTagKind.Emotion,
            CanonicalTagKind.Setting,
            CanonicalTagKind.Audience,
        )

        /** 标签票数低于条目内最高票数的这个比例就不作数 (与画像那边同一把尺子). */
        const val TAG_CONFIDENCE = 0.15

        /**
         * 用户收藏里占比到这个数的地区才算"常看" (见 [Sampling.regionWeight]); 占比按标了地区的收藏算.
         */
        const val WATCHED_REGION_SHARE = 0.1

        /**
         * 用户不怎么看 (收藏里占比在 [COLLECTED_REGION_SHARE] 与 [WATCHED_REGION_SHARE] 之间) 的地区的权重.
         * 占比更低的直接不推, 见 [Sampling.regionAllowed].
         */
        const val UNWATCHED_REGION_WEIGHT = 0.3

        /** 收藏里占比到这个数的地区才算"看过", 不到的直接不推 (见 [CollectionsSnapshot.collectedRegions]). */
        const val COLLECTED_REGION_SHARE = 0.02

        /** 年代权重 ([Sampling.ageWeight]): 这么多年以内的不压. */
        const val FRESH_YEARS = 3

        /** 年代权重: 超过 [FRESH_YEARS] 之后每这么多年减半. */
        const val AGE_HALF_LIFE_YEARS = 7.0

        /** 年代权重: 年份未知的按这个算 (约等于二十六七年前的作品). */
        const val UNKNOWN_YEAR_WEIGHT = 0.1

        /** 超长篇的集数线 (见 [Sampling.longRunningWeight]): 航海王 1155 / 火影 220 / 银魂 201 / 宝可梦 276. */
        const val LONG_RUNNING_EPISODES = 100

        /** 集数未知的作品播了这么多年还没填总集数, 就当是连载中的长篇. */
        const val ONGOING_LONG_YEARS = 3

        /** 超长篇的权重. */
        const val LONG_RUNNING_WEIGHT = 0.15

        /** 精简条目 `info` 串打头的集数 (`26话 / …`); 集数未知时不写. */
        val INFO_EPISODES = Regex("""^\s*(\d+)话""")

        /** 精简条目 `info` 串里首播日期的年份 (`26话 / 2004年7月4日 / …`). */
        val INFO_YEAR = Regex("""(\d{4})年""")

        /** 没有收藏可参考时 (未登录 / 没取到) 当作常看的地区. */
        val DEFAULT_WATCHED_REGIONS = setOf("日本")

        /** splitmix64 的常数, 见 [unitRandom]. */
        private val GOLDEN_GAMMA = 0x9E3779B97F4A7C15UL.toLong()
        private val SPLITMIX_MUL_1 = 0xBF58476D1CE4E5B9UL.toLong()
        private val SPLITMIX_MUL_2 = 0x94D049BB133111EBUL.toLong()

        /** [Sampling.random] 的用途区分. */
        const val SALT_NEIGHBOR_TAG = 1
        const val SALT_TOP_RATED = 2
        const val SALT_TOP_RATED_PAD_CHANGE_TASTE = 4

        const val SALT_CHANGE_TASTE_PAGE = 5

        /** 种子行补齐随机页的 salt: 这个数 + 种子条目 id. */
        const val SALT_SEED_FILL = 1 shl 20

        /** 「符合你口味的高分」各标签随机页的 salt: 这个数 + 标签序号. */
        const val SALT_HIGH_RATED = 16

        /** 「换换口味」池子的分数线: 换的是方向, 质量仍要过得去. */
        val CHANGE_TASTE_LINE = listOf(">=7")

        /** 「高分经典」与全站高分垫料的分数线: 8 分以上. */
        val HIGH_SCORE = listOf(">=8")

        /**
         * 「符合你口味的高分动画」池子的分数线: 7.5 分以上 (用户定的). 池子就是这条线以上的全部, 不再分
         * 「8 分优先、凑不满再放宽」两档 —— 池子越大, 加权抽样越有腾挪的空间.
         */
        val HIGH_RATED_LINE = listOf(">=7.5")

        /** 「符合你口味的高分」每个标签在头一页之外随机取几页 (见 [searchSampledByTags]). */
        const val HIGH_RATED_RANDOM_PAGES = 3

        /**
         * 「高分经典」与高分垫料的起点在 [0, 这个数) 里随机.
         *
         * 按 [HIGH_SCORE] 的总数定: 2026-09-22 实测全站上千人评分、8 分以上的一共 256 部, 取两页 (40 条),
         * 起点超过 216 就会越过末尾. 留点余量.
         */
        const val TOP_RATED_OFFSET_RANGE = 200

        /** `/recs` 的 limit 上限是 10. */
        const val RECS_PER_SEED = 10

        /**
         * 续作换季时顺前传最多走几跳 (见 [SequelBatch]). 一般 1~2 跳就到头, 长的 (水星领航员、
         * 黑塔利亚) 4 跳; 再往上多半是走进了长寿系列的外传网.
         */
        const val MAX_PREQUEL_HOPS = 6

        /** 换季那几行每行留几个候补: 两格换到同一季时补位用. */
        const val SEQUEL_RESERVES = 4

        /** 换季时能当「一季」的最少集数 (见 [isSeasonFormat]): 6 集的网络番还算, 一两集的特别篇不算. */
        const val MIN_SEASON_EPISODES = 6

        /** 连着「换一批」时躲开最近几批 (见 [recentBatches]). */
        const val RECENT_BATCHES_TO_AVOID = 3

        /**
         * 同时回溯几条. OkHttp 每个 host 最多 5 个并发, 而页面自己 (hero、详情预取) 也在打 next.bgm.tv,
         * 给它留一个名额, 不让换季把页面的请求压在队尾.
         */
        const val SEQUEL_WALK_PARALLELISM = 4

        /** 算画像时往回看多少条收藏. 全表拉出来只为算个画像不值当. */
        const val PROFILE_LOOKBACK = 500

        /**
         * 取收藏时第一页多大 (见 [fetchAllCollections]).
         *
         * 这一页**每轮重算都要取** (拿 total 与指纹), 而多数轮次取完它就复用快照了, 所以越小越便宜.
         * 指纹其实看第一条就够 —— 最近改过的那条必定排第一; 取二十条是留的余量.
         * 实测解析成本随条数线性 (debug 包每条约 3ms, p1 给的是完整条目对象).
         */
        const val COLLECTION_HEAD_PAGE = 20

        /**
         * 第一页之后每页多大. 取接口上限 (`/p1/collections/subjects` 的 limit 最大 100):
         * 页数越少越好 —— 这些页虽然是并发发的, 但 OkHttp 默认每个 host 最多 5 个并发, 多出来的排队.
         */
        const val COLLECTION_FETCH_PAGE = 100

        /**
         * 补「看过」最多取到多少条 (兜底上限).
         *
         * 正常按服务端报的 total 翻到底; 这个上限只防"看过几千部"的极端账号把一次重算拖成
         * 几十个请求. 到这儿还没取完的话, 漏掉的都是最久以前看的, 撞上推荐的概率也最低
         * (列表是按收藏更新时间降序给的).
         */
        const val COLLECTION_FETCH_MAX = 1000
    }
}
