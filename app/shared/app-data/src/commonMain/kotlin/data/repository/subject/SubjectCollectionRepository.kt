/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.map
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.network.mapper.toEntity
import me.him188.ani.app.data.models.subject.RatingCounts
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectAiringInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionCounts
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.SubjectProgressInfo
import me.him188.ani.app.data.models.subject.SubjectRecurrence
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.data.network.EpisodeService
import me.him188.ani.app.data.network.SubjectCollectionUpdate
import me.him188.ani.app.data.network.SubjectService
import me.him188.ani.app.data.persistent.database.ProtoConverters
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionDao
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionAndEpisodes
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionDao
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectRelations
import me.him188.ani.app.data.persistent.database.dao.SubjectRelationsDao
import me.him188.ani.app.data.persistent.database.dao.deleteAll
import me.him188.ani.app.data.persistent.database.dao.filterMostRecentUpdatedWithEpisodes
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.episode.AnimeScheduleRepository
import me.him188.ani.app.data.repository.episode.toEpisodeCollectionInfo
import me.him188.ani.app.data.repository.shouldRetry
import me.him188.ani.app.domain.search.SubjectType
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.checkAccessAniApiNow
import me.him188.ani.app.domain.session.restartOnNewLogin
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubject
import me.him188.ani.datasources.bangumi.processing.toSubjectCollectionType
import me.him188.ani.utils.coroutines.combine
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.annotations.TestOnly
import me.him188.ani.utils.platform.currentTimeMillis
import me.him188.ani.utils.serialization.BigNum
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * 条目信息和条目收藏的仓库.
 *
 * [SubjectInfo], [SubjectCollectionInfo], [SubjectCollectionCounts]
 *
 * 是 abstract 而不是 sealed: 生产实现只有 [SubjectCollectionRepositoryImpl], 但 Bangumi 收藏合并相关的测试 (其他模块) 需要用轻量的 fake 替代它.
 */
abstract class SubjectCollectionRepository(
    defaultDispatcher: CoroutineContext = Dispatchers.Default
) : Repository(defaultDispatcher) {
    /**
     * 获取条目收藏统计信息 cold [Flow]. Flow 将会 emit 至少一个值, 失败时 emit `null`.
     */
    abstract fun subjectCollectionCountsFlow(): Flow<SubjectCollectionCounts?>

    abstract fun subjectCollectionFlow(subjectId: Int): Flow<SubjectCollectionInfo>

    abstract fun subjectCollectionsPager(
        query: CollectionsFilterQuery = CollectionsFilterQuery.Empty,
        /**
         * 这套 pager 是 Room + RemoteMediator: 每次 mediator 写库都会让 PagingSource 失效,
         * 新 generation 只重载锚点附近 [PagingConfig.initialLoadSize] 的窗口, **窗口外的已加载
         * 条目全部退回 placeholder**. 窗口必须盖住最大的视口 —— 4K 原生 density 的 TV 网格一屏
         * 可见 60+ 张卡, 默认 30 (pageSize×3) 会让屏内卡片在每次 append 写库后变灰闪烁,
         * 聚焦卡的 key 从 subjectId 换成 placeholder key 时节点还会被销毁 (焦点逃逸).
         *
         * pageSize 同时是 mediator 每批网络请求的 limit (见 [calculateIndexBasedLoadInfo]):
         * REFRESH 会先清表再按这个批量回填, 批量越小回填波数越多, 每波都是一次全网格 invalidate.
         */
        pagingConfig: PagingConfig = PagingConfig(
            pageSize = 30,
            prefetchDistance = 60,
            initialLoadSize = 120,
        ),
    ): Flow<PagingData<SubjectCollectionInfo>>

    /**
     * 获取本地所有缓存的 [SubjectCollectionInfo] 的 [subjectId][SubjectCollectionInfo.subjectId]
     */
    abstract fun cachedValidSubjectIds(): Flow<List<Int>>

    /**
     * 更新根据服务器上记录的最近有修改的条目收藏. 也就是用户最近操作过的条目收藏.
     */
    abstract suspend fun updateRecentlyUpdatedSubjectCollections(
        limit: Int,
        type: UnifiedCollectionType?,
        offset: Int = 0,
    )

    /**
     * 获取最近更新的条目收藏 cold [Flow].
     */
    abstract fun mostRecentlyUpdatedSubjectCollectionsFlow(
        limit: Int,
        types: List<UnifiedCollectionType>? = null, // null for all
    ): Flow<List<SubjectCollectionInfo>>

    /**
     * @param score 0 to remove rating
     * @param comment set empty to remove
     * @param tags set empty to remove
     */
    abstract suspend fun updateRating(
        subjectId: Int,
        score: Int? = null,
        comment: String? = null,
        tags: List<String>? = null,
        isPrivate: Boolean? = null,
    )

    /**
     * @throws me.him188.ani.app.data.repository.RepositoryAuthorizationException
     */
    abstract suspend fun setSubjectCollectionTypeOrDelete(
        subjectId: Int,
        type: UnifiedCollectionType?,
    )

    /**
     * 只从本地数据库中获取收藏类型, 不进行网络请求.
     */
    abstract fun getSubjectCollectionTypeOffline(subjectId: Int): Flow<UnifiedCollectionType?>

    /**
     * 只从本地数据库中获取条目的展示信息 (名称/封面/总集数), 不进行网络请求.
     * 未收藏 (本地无记录) 时 emit `null`.
     */
    abstract fun getSubjectDisplayInfoOffline(subjectId: Int): Flow<OfflineSubjectDisplayInfo?>

    abstract suspend fun getSubjectIdsByCollectionType(types: List<UnifiedCollectionType>): Flow<List<Int>>

    abstract suspend fun getSubjectNamesCnByCollectionType(types: List<UnifiedCollectionType>): Flow<List<String>>

}

class SubjectCollectionRepositoryImpl(
    private val subjectService: SubjectService,
    private val subjectCollectionDao: SubjectCollectionDao,
    private val subjectRelationsDao: SubjectRelationsDao,
    private val animeScheduleRepository: AnimeScheduleRepository,
    private val episodeService: EpisodeService,
    private val episodeCollectionDao: EpisodeCollectionDao,
    private val sessionManager: SessionStateProvider,
    private val nsfwModeSettingsFlow: Flow<NsfwMode>,
    private val getCurrentDate: () -> PackedDate = { PackedDate.now() },
    private val getEpisodeTypeFiltersUseCase: GetEpisodeTypeFiltersUseCase,
    defaultDispatcher: CoroutineContext = Dispatchers.Default,
    private val cacheExpiry: Duration = 1.hours,
    private val subjectDetailsExpiry: Duration = 1.minutes,
) : SubjectCollectionRepository(defaultDispatcher) {
    override fun subjectCollectionCountsFlow(): Flow<SubjectCollectionCounts?> {
        return (subjectService.subjectCollectionCountsFlow() as Flow<SubjectCollectionCounts?>)
            .restartOnNewLogin(sessionManager)
            .retry(2) { e ->
                RepositoryException.shouldRetry(e)
            }
            .catch {
                logger.error("Failed to get subject collection counts", it)
                emit(null)
            }
            .flowOn(defaultDispatcher)
//        return combine(
//            subjectCollectionDao.countCollected(UnifiedCollectionType.WISH),
//            subjectCollectionDao.countCollected(UnifiedCollectionType.DOING),
//            subjectCollectionDao.countCollected(UnifiedCollectionType.DONE),
//            subjectCollectionDao.countCollected(UnifiedCollectionType.ON_HOLD),
//            subjectCollectionDao.countCollected(UnifiedCollectionType.DROPPED),
//        ) { wish, doing, done, onHold, dropped ->
//            SubjectCollectionCounts(
//                wish = wish,
//                doing = doing,
//                done = done,
//                onHold = onHold,
//                dropped = dropped,
//                total = wish + doing + done + onHold + dropped,
//            )
//        }
    }

    private fun SubjectCollectionEntity.isExpired(): Boolean {
        return (currentTimeMillis() - lastFetched).milliseconds > cacheExpiry
    }

    /**
     * 详情页用的新鲜度阈值, 比列表那个 [cacheExpiry] 短得多.
     *
     * 收藏状态/评分/短评在**别处**改了 (bgm 网页、手机上的另一个安装) 时, 本地要能很快对齐 ——
     * 按一小时算的话, 网页上改完评分回到 app 进详情页看到的还是旧值, 而且怎么退出重进都不变.
     * 这一档只影响**条目本身**要不要重取; 分集另算 (见 [episodesExpired]), 所以代价是一个请求.
     */
    private fun SubjectCollectionEntity.isSubjectStaleForDetails(): Boolean {
        return (currentTimeMillis() - lastFetched).milliseconds > subjectDetailsExpiry
    }

    private val subjectFetcher = StaleKeyedFetcher<Int>()

    /**
     * **同一条目的重取只做一次**.
     *
     * [subjectCollectionFlow] 在仓库里有十几个调用点 (详情页状态工厂 / EpisodeCollectionRepository /
     * SubjectRelationsRepository / GetSubjectEpisodeInfoBundleFlowUseCase / MediaSelectorFactory /
     * 缓存页…), **每个调用都是一条独立冷流, 各自跑一遍"要不要 fetch"的判定** —— 实测一次进详情页
     * 有 4 条判定、3 条真的发了请求. 后果按严重度:
     *
     * 1. **并发写同一批行**: 下面是 `upsert(subject)` → 读 `oldIds` → `upsert(episodes)` →
     *    `deleteAllByEpisodeIds(oldIds - new)`, 而"读 oldIds"与"delete"之间没有事务. 多副本交错时,
     *    某个副本只拿到部分集就会删掉另一副本刚写进去的行 —— 「选集区永久空白」那个 bug 的同族温床;
     * 2. 进页延迟: 三份重复网络与 DB 写和首屏抢 IO;
     * 3. 缓存过期后每次进页发 N 次请求而不是 1 次, 对 Ani API / bgm.tv 也是 N 倍.
     *
     * 去重靠 [StaleKeyedFetcher] (串行 + 进临界区后重查), 取舍见那里.
     *
     * 写完不必自己 emit: 上游是 Room flow, 写库会让它重新发射, `transform` 再跑一遍时
     * `existing` 已经是新的了.
     *
     * 落库整段已包进单个 Room 事务 ([SubjectCollectionDao.upsertSubjectWithEpisodes]),
     * 中间态与并发交错都堵死了.
     */
    private suspend fun fetchSubjectCollectionIfStale(subjectId: Int) {
        subjectFetcher.fetchIfStale(
            key = subjectId,
            isFresh = {
                val fresh = subjectCollectionDao.findById(subjectId).first()
                    ?.isSubjectStaleForDetails() == false && !needsEpisodes(subjectId)
                // 等到锁却发现数据已经新鲜 = 刚被另一个订阅者取回来了, 这一次省掉了
                if (fresh) logger.info { "Subject $subjectId already fresh, skipped duplicate fetch" }
                fresh
            },
        ) {
            fetchAndSaveSubjectCollection(subjectId)
            episodesFetchAttemptedLock.withLock { episodesFetchAttempted.add(subjectId) }
            // TODO: 2025/5/24 handle subject not found
        }
    }

    /**
     * **条目行新鲜不代表分集也在**.
     *
     * 追番列表现在只取条目不取分集 (Ani 那个列表接口是把分集内联一起给的), 于是"刚被列表刷新过"
     * 的条目进详情页时, 只看 [SubjectCollectionEntity.isExpired] 会判成新鲜、直接跳过取数,
     * 选集条就是空的 —— 死神千年血战篇就是这么空的 (库里 0 条分集).
     *
     * 本进程内每个条目最多因此多取一次: 真的没有分集 (未播出/未定档) 的条目不会陷入反复重取.
     */
    private val episodesFetchAttempted = mutableSetOf<Int>()
    private val episodesFetchAttemptedLock = Mutex()

    private suspend fun needsEpisodes(subjectId: Int): Boolean {
        if (episodesFetchAttemptedLock.withLock { subjectId in episodesFetchAttempted }) return false
        return episodeCollectionDao.listIdBySubjectId(subjectId).first().isEmpty()
    }

    /**
     * 取单个条目的收藏与分集并落库.
     *
     * @return 服务端是否有这个条目的收藏记录; `false` 表示未收藏 (数据库不动)
     */
    private suspend fun episodesExpired(subjectId: Int): Boolean {
        val episodes = episodeCollectionDao.filterBySubjectId(subjectId).first()
        if (episodes.isEmpty()) return true
        return episodes.all { (currentTimeMillis() - it.lastFetched).milliseconds > cacheExpiry }
    }

    private suspend fun fetchAndSaveSubjectCollection(subjectId: Int): Boolean {
        val subject = subjectService.getSubjectCollection(subjectId) ?: return false
        val lastFetched = currentTimeMillis()
        // p1 的条目里没有 recurrence 与 relations (那两个是 Ani 服务端自己算的). 在它们各自的替代
        // 方案接上之前, 沿用库里已有的值 —— 否则每刷新一次条目就把之前取到的抹成空.
        val existing = subjectCollectionDao.findById(subjectId).first()
        val subjectEntity = subject.toEntity(
            lastFetched = lastFetched,
            // 库里没有播出周期时从 bangumi-data 补一次 (它按月缓存, 同一个月的第二个条目起不发请求)
            recurrence = existing?.recurrence
                ?: animeScheduleRepository.getSubjectRecurrence(subjectId, subject.airtime.date),
            relations = existing?.relations ?: SubjectRelations.Empty,
        )
        // 分集单独按 cacheExpiry 判: 详情页的条目重取比分集频繁得多 (见 isSubjectStaleForDetails),
        // 每次都跟着把分集也拉一遍不值当 —— 分集变化远比收藏状态慢
        if (episodesExpired(subjectId)) {
            val episodeEntities = episodeService.getEpisodeCollectionEntities(subjectId, lastFetched)
            // 条目 + 分集 + 差集删除在**单个事务**里 (含保留 relations 盖章), 见该方法 KDoc
            subjectCollectionDao.upsertSubjectWithEpisodes(subjectEntity, episodeEntities)
            logger.info { "bgm-direct: fetched subject $subjectId with ${episodeEntities.size} episodes" }
        } else {
            subjectCollectionDao.upsert(subjectEntity)
            logger.info { "bgm-direct: fetched subject $subjectId (分集还新鲜, 没重取)" }
        }
        return true
    }

    override fun subjectCollectionFlow(
        subjectId: Int
    ): Flow<SubjectCollectionInfo> = getEpisodeTypeFiltersUseCase().flatMapLatest { epTypes ->
        subjectCollectionDao.findById(subjectId)
            .restartOnNewLogin(sessionManager)
            .transform { existing ->
                if (existing != null) {
                    // 不管是不是过期都先 emit, 确保离线时能播放
                    emit(existing)
                }

                // 没有缓存, 过期 (详情页用的是分钟级阈值), 或者条目行在但分集没有 (见 needsEpisodes)
                if (existing == null || existing.isSubjectStaleForDetails() || needsEpisodes(subjectId)) {
                    fetchSubjectCollectionIfStale(subjectId)
                }
            }
            .filterNotNull()
            // 有 subject 缓存后才能从 episodeCollectionRepository fetch episodes
            .combine(
                episodeCollectionDao
                    .filterBySubjectId(subjectId, epTypes)
                    .map { list -> list.map { it.toEpisodeCollectionInfo() } }
                    .distinctUntilChanged(),
                nsfwModeSettingsFlow,
            ) { entity, episodes, nsfwModeSettings ->
                entity.toSubjectCollectionInfo(
                    episodes = episodes,
                    currentDate = getCurrentDate(),
                    nsfwModeSettings = nsfwModeSettings,
                )
            }
            // Room 的失效是**表级**的: 往 subject_collection / episode_collection 写**别的**条目也会
            // 让这条流重发一份一模一样的值. 下游用 flatMapLatest 的消费者 (关联数据、媒体选择器…) 会
            // 因此把在途的工作掐掉重来 —— 详情页的角色/制作人员就是这么被反复重启到一次都取不完的
            // (见 SubjectRelationsRepository.relationsFreshnessFlow). 内容没变就别往下传.
            .distinctUntilChanged()
    }.flowOn(defaultDispatcher)

    /**
     * 整条链只有**一条** Room flow: 条目与其剧集在同一次查询里取出 (`@Relation`), 数据库一变就整体重算.
     *
     * 曾经的实现是先查条目列表, 再为每个条目订阅一条 [subjectCollectionFlow] 拿剧集. 那条 flow 带条件网络
     * 请求 (缓存过期就 `getSubjectCollection`), 于是改一次收藏就会让几十条 flow 重建并发请求, 其中任意一条
     * 失败就会顺着 `combine` 抛穿上层, 把收集协程一起打死 —— 表现为探索页"继续观看"永久停在旧快照, 只能
     * 重启应用. 这里的数据新鲜度由调用方先行的 [updateRecentlyUpdatedSubjectCollections] 保证, 本就不需要
     * 逐条再拉一遍.
     */
    override fun mostRecentlyUpdatedSubjectCollectionsFlow(
        limit: Int,
        types: List<UnifiedCollectionType>?, // null for all
    ): Flow<List<SubjectCollectionInfo>> = combine(
        subjectCollectionDao.filterMostRecentUpdatedWithEpisodes(types, limit)
            .restartOnNewLogin(sessionManager),
        nsfwModeSettingsFlow,
        getEpisodeTypeFiltersUseCase(),
    ) { list, nsfwModeSettings, epTypes ->
        val currentDate = getCurrentDate()
        list.map { it.toSubjectCollectionInfo(epTypes, currentDate, nsfwModeSettings) }
    }
        // Room 的 flow 只要表被 invalidate 就重发, 而 entity 的 lastFetched 每次刷新都会变、却又不进
        // SubjectCollectionInfo —— 于是"刷新了但数据没变"(每小时的批量刷新、进详情页/播放器时的单条
        // 刷新、追番页 mediator 每批写库) 会让下游白跑一整轮: 重建 PagingData、LazyPagingItems 换掉整个
        // snapshot list、"继续观看"整行重组. 深比较 64 个条目比那一轮便宜一到两个数量级.
        .distinctUntilChanged()
        .flowOn(defaultDispatcher)

    override fun subjectCollectionsPager(
        query: CollectionsFilterQuery,
        pagingConfig: PagingConfig,
    ): Flow<PagingData<SubjectCollectionInfo>> =
        combine(getEpisodeTypeFiltersUseCase(), nsfwModeSettingsFlow) { epTypes, nsfwModeSettings ->
            epTypes to nsfwModeSettings
        }.restartOnNewLogin(sessionManager).flatMapLatest { (epTypes, nsfwModeSettings) ->
            Pager(
                config = pagingConfig,
                initialKey = 0,
                remoteMediator = SubjectCollectionRemoteMediator(query),
                pagingSourceFactory = {
                    subjectCollectionDao.filterByCollectionTypePaging(
                        query.type,
                        includeNsfw = nsfwModeSettings != NsfwMode.HIDE,
                    )
                },
            ).flow.map { data ->
                data.map { it.toSubjectCollectionInfo(epTypes, getCurrentDate(), nsfwModeSettings) }
            }
        }.flowOn(defaultDispatcher)

    override fun cachedValidSubjectIds(): Flow<List<Int>> {
        return subjectCollectionDao.subjectIdsWithValidEpisodeCollection().flowOn(defaultDispatcher)
    }

    private val updateRecentlyUpdatedSubjectCollectionsMutex = Mutex()
    override suspend fun updateRecentlyUpdatedSubjectCollections(
        limit: Int,
        type: UnifiedCollectionType?,
        offset: Int
    ) {
        try {
            withContext(defaultDispatcher) {
                // 只允许同时一个请求. 防止多个请求浪费带宽.
                // 一般来说不会有多个请求. 最常见的并行请求可能是用户刚刚打开 APP 进入探索页自动刷新"继续观看"栏目, 在刷新还在进行时切换到收藏页触发自动刷新.
                updateRecentlyUpdatedSubjectCollectionsMutex.withLock {
                    val fetched = fetchAndSaveSubjectCollectionsWithEpisodes(type, limit, offset)
                    // 只有"某个类型最近更新的前 limit 条"这种请求能反推出哪些本地行已经不该是这个类型了,
                    // offset != 0 时窗口不完整, 反推不成立
                    if (type != null && offset == 0) {
                        reconcileCollectionsLeftType(type, limit, fetched)
                    }
                }
            }
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }

    // transparent exception
    /**
     * 执行网络查询条目收藏及其剧集列表, 在所有网络请求都成功后调用 [onFetched], 然后保存查询结果到数据库.
     *
     * @param onFetched 当所有网络请求都成功后调用
     * @return 本次从服务端拿到并写入数据库的条目
     */
    private suspend inline fun fetchAndSaveSubjectCollectionsWithEpisodes(
        type: UnifiedCollectionType?,
        limit: Int,
        offset: Int,
        onFetched: (items: List<BangumiNextSubject>) -> Unit = {},
    ): List<SubjectCollectionEntity> {
        require(type != UnifiedCollectionType.NOT_COLLECTED) { "type must not be NOT_COLLECTED" }
        require(limit > 0) { "limit must be positive" }

        // 执行网络请求查询好需要的 subject 和 episodes
        val items = subjectService.getSubjectCollections(
            type = type?.toSubjectCollectionType(),
            offset = offset,
            limit = limit,
        )

        onFetched(items)

        // 批量插入条目信息与分集, 单个事务 (含保留 relations 盖章, 否则这里每写一批就会把
        // 详情页刚取好的角色/制作人员时间戳抹回 0, 触发一轮强制重取); 条目在前, 分集有外键依赖
        val lastFetched = currentTimeMillis()
        val existing = subjectCollectionDao.filterByIds(items.map { it.id }.toIntArray()).first()
            .associateBy { it.subjectId }
        val subjects = items.map {
            // recurrence/relations 沿用库里的, 理由同 fetchAndSaveSubjectCollection
            it.toEntity(
                lastFetched = lastFetched,
                recurrence = existing[it.id]?.recurrence,
                relations = existing[it.id]?.relations ?: SubjectRelations.Empty,
            )
        }
        subjectCollectionDao.upsertSubjectsWithEpisodes(subjects = subjects, episodes = emptyList())
        fetchEpisodesForInProgressSubjects(subjects, lastFetched)
        return subjects
    }

    /**
     * 列表接口只给条目不给分集 (Ani 那个是内联的), 一页 30 条要逐个补分集就是 30 个请求.
     *
     * 只补**在看**与**搁置**的 (看到一半的): 「继续观看」要的下一集 id、卡片上的进度与"更新至 X 话"
     * 只有它们用得上; 看过/想看的卡片在没有分集时会走 [toSubjectCollectionInfo] 里的降级路径 (用条目
     * 自带的总集数与收藏状态), 进过一次详情页之后分集自然就齐了.
     *
     * 这两类的量级很小 (几部到几十部), 且**已经有新鲜分集的跳过**, 所以稳态下这里基本不发请求.
     */
    private suspend fun fetchEpisodesForInProgressSubjects(
        subjects: List<SubjectCollectionEntity>,
        lastFetched: Long,
    ) {
        val doing = subjects.filter {
            it.collectionType == UnifiedCollectionType.DOING || it.collectionType == UnifiedCollectionType.ON_HOLD
        }
        if (doing.isEmpty()) return
        for (subject in doing) {
            val cached = episodeCollectionDao.filterBySubjectId(subject.subjectId).first()
            if (cached.isNotEmpty() && cached.all { currentTimeMillis() - it.lastFetched < cacheExpiry.inWholeMilliseconds }) {
                continue
            }
            try {
                val episodes = episodeService.getEpisodeCollectionEntities(subject.subjectId, lastFetched)
                episodeCollectionDao.upsert(episodes)
                logger.info { "bgm-direct: 补在看/搁置条目的分集 subject=${subject.subjectId} -> ${episodes.size}" }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                // 分集补取失败不该让整页收藏加载失败: 卡片退化成没有进度, 下次刷新再补
                logger.warn { "Failed to fetch episodes for in-progress subject ${subject.subjectId}: $e" }
            }
        }
    }

    /**
     * 纠正"本地还标着 [type], 而服务端上已经不是了"的条目.
     *
     * [updateRecentlyUpdatedSubjectCollections] 只做 upsert, 服务端不再返回的条目没人来改. 于是在
     * **另一台设备/另一个安装**上把番从"在看"改成"看过"之后, 本机那行会永远停在在看 —— 探索页
     * "继续观看"和系统主屏预览频道一直挂着已经看完的番, 进一次详情页 (走单条 fetch) 才好.
     *
     * 服务端按收藏更新时间降序返回, 所以"本地标着 [type]、更新时间落在本次返回的窗口内、却没被返回"
     * 就说明它的收藏状态在别处变过了. 逐个重取纠正; 服务端已无这条收藏记录 = 在别处取消了收藏, 删本地行.
     */
    private suspend fun reconcileCollectionsLeftType(
        type: UnifiedCollectionType,
        limit: Int,
        fetched: List<SubjectCollectionEntity>,
    ) {
        val fetchedIds = fetched.mapTo(HashSet()) { it.subjectId }
        // 返回数量不足 limit ⇒ 窗口盖住了服务端上该类型的**全部**条目, 没被返回的一律有问题;
        // 拉满了就只信"更新时间不早于窗口最末一条"的那些 —— 更早的可能只是排在窗口之外.
        val windowFloor = if (fetched.size < limit) Long.MIN_VALUE else fetched.minOf { it.lastUpdated }
        val stale = subjectCollectionDao.filterMostRecentUpdated(listOf(type), limit).first()
            .filter { it.subjectId !in fetchedIds && it.lastUpdated >= windowFloor }
        if (stale.isEmpty()) return

        logger.info { "Local collections still marked $type but missing from server: ${stale.map { it.subjectId }}" }
        // 正常情况只有零星几条. 封顶是防"服务端排序与上述假设不符"时每小时都刷一整屏单条请求,
        // 没纠正完的下一轮继续
        for (entity in stale.take(RECONCILE_LEFT_TYPE_LIMIT)) {
            subjectFetcher.fetchIfStale(
                key = entity.subjectId,
                // 与详情页那条单条 fetch 去重: 已经被它纠正过就不必再取
                isFresh = { subjectCollectionDao.findById(entity.subjectId).first()?.collectionType != type },
            ) {
                if (!fetchAndSaveSubjectCollection(entity.subjectId)) {
                    subjectCollectionDao.delete(entity.subjectId)
                }
            }
        }
    }

    override suspend fun updateRating(
        subjectId: Int,
        score: Int?, // 0 to remove rating
        comment: String?, // set empty to remove
        tags: List<String>?,
        isPrivate: Boolean?,
    ) {
        withContext(defaultDispatcher) {
            // 每个字段原样透传: null = 不动它. 用 orEmpty()/?: false 去顶会把用户在 bangumi 上
            // 的标签清空、把"仅自己可见"改掉 —— 详情页改评分时 tags 就是 null.
            subjectService.patchSubjectCollection(
                subjectId,
                SubjectCollectionUpdate(
                    score = score,
                    comment = comment,
                    tags = tags,
                    isPrivate = isPrivate,
                ),
            )

            subjectCollectionDao.updateRating(
                subjectId,
                score,
                comment,
                // 这一列按 protobuf 存, 查询参数得先自己编码 (直接传列表会被 Room 展开, 见 DAO 上的说明)
                tags?.let { ProtoConverters.StringList().fromList(it) },
                isPrivate,
            )
        }
    }

    private inner class SubjectCollectionRemoteMediator<T : Any>(
        private val query: CollectionsFilterQuery,
    ) : RemoteMediator<Int, T>() {
        override suspend fun initialize(): InitializeAction = withContext(defaultDispatcher) {
            val lastUpdated = subjectCollectionDao.lastFetched(query.type)
            if ((currentTimeMillis() - lastUpdated).milliseconds > cacheExpiry) {
                InitializeAction.LAUNCH_INITIAL_REFRESH
            } else {
                InitializeAction.SKIP_INITIAL_REFRESH
            }
        }

        override suspend fun load(
            loadType: LoadType,
            state: PagingState<Int, T>,
        ): MediatorResult = try {
            withContext(defaultDispatcher) {
                val (offset, limit) = calculateIndexBasedLoadInfo(loadType, state)
                    ?: return@withContext MediatorResult.Success(endOfPaginationReached = true)
                logger.debug { "${loadType}, Loading $offset, limit=$limit" }

                var endOfPaginationReached = false
                fetchAndSaveSubjectCollectionsWithEpisodes(
                    type = query.type,
                    limit = limit,
                    offset = offset,
                    onFetched = { items ->
                        if (loadType == LoadType.REFRESH) {
                            // 仅在网络请求成功后才删除缓存, 否则会导致无网络时清空缓存
                            // 必须清除缓存, 让顺序与服务器同步, 否则会死循环刷新
                            subjectCollectionDao.deleteAll(query.type)
                            // 数字与卡片一起对齐: 计数是另一组请求, 不跟着列表走
                            subjectService.invalidateCollectionCounts()
                        }

                        // 拿到的数量小于请求的 limit 就代表这是最后一页, 否则总数不是 limit 整数倍时
                        // 会永远在同一个 offset 重复请求, 造成无限刷新循环 (列表反复重排/跳动)
                        endOfPaginationReached = items.size < limit
                    },
                )

                MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
            }
        } catch (e: Exception) {
            MediatorResult.Error(RepositoryException.wrapOrThrowCancellation(e))
        }
    }

    override suspend fun setSubjectCollectionTypeOrDelete(
        subjectId: Int,
        type: UnifiedCollectionType?,
    ) {
        return withContext(defaultDispatcher) {
            sessionManager.checkAccessAniApiNow()
            if (type == null || type == UnifiedCollectionType.NOT_COLLECTED) {
                deleteSubjectCollection(subjectId)
            } else {
                // 必须把当前评分一起送过去: bangumi 收到带 type 而不带 rate 的请求会把评分清零
                val currentScore = subjectCollectionDao.findById(subjectId).first()
                    ?.selfRatingInfo?.score?.takeIf { it > 0 }
                patchSubjectCollection(
                    subjectId,
                    SubjectCollectionUpdate(collectionType = type, score = currentScore),
                )
            }
        }
    }

    override fun getSubjectCollectionTypeOffline(subjectId: Int): Flow<UnifiedCollectionType?> {
        return subjectCollectionDao.findById(subjectId).map { it?.collectionType }
    }

    override fun getSubjectDisplayInfoOffline(subjectId: Int): Flow<OfflineSubjectDisplayInfo?> {
        return subjectCollectionDao.findById(subjectId).map { entity ->
            entity?.run {
                OfflineSubjectDisplayInfo(
                    subjectId = this.subjectId,
                    displayName = nameCn.ifEmpty { name },
                    imageLarge = imageLarge,
                    totalEpisodes = totalEpisodes,
                )
            }
        }
    }

    override suspend fun getSubjectIdsByCollectionType(types: List<UnifiedCollectionType>): Flow<List<Int>> {
        return subjectCollectionDao.subjectIdsByCollectionType(types).flowOn(defaultDispatcher)
    }

    override suspend fun getSubjectNamesCnByCollectionType(types: List<UnifiedCollectionType>): Flow<List<String>> {
        return subjectCollectionDao.subjectNamesCnByCollectionType(types).flowOn(defaultDispatcher)
    }

    private suspend fun patchSubjectCollection(
        subjectId: Int,
        payload: SubjectCollectionUpdate,
    ) {
        withContext(defaultDispatcher) {
            subjectService.patchSubjectCollection(subjectId, payload)
            payload.collectionType?.let { subjectCollectionDao.updateType(subjectId, it) }
        }
    }

    private suspend fun deleteSubjectCollection(subjectId: Int) {
        withContext(defaultDispatcher) {
            subjectService.deleteSubjectCollection(subjectId)
            subjectCollectionDao.delete(subjectId)
        }
    }


    private companion object {
        private val logger = logger<SubjectCollectionRepository>()

        /** 一轮刷新里最多纠正几条"本地类型已过期"的记录, 见 [reconcileCollectionsLeftType]. */
        private const val RECONCILE_LEFT_TYPE_LIMIT = 8
    }
}

data class CollectionsFilterQuery(
    val type: UnifiedCollectionType?,
) {
    companion object {
        val Empty = CollectionsFilterQuery(null)
    }
}

private fun SubjectCollectionEntity.toSubjectInfo(): SubjectInfo {
    return SubjectInfo(
        subjectId = subjectId,
        subjectType = SubjectType.ANIME,
        name = name,
        nameCn = nameCn,
        summary = summary,
        nsfw = nsfw,
        imageLarge = imageLarge,
        totalEpisodes = totalEpisodes,
        airDate = airDate,
        tags = tags,
        aliases = aliases,
        ratingInfo = ratingInfo,
        collectionStats = collectionStats,
        completeDate = completeDate,
        screeningYear = screeningYear,
        theatrical = theatrical,
    )
}

private fun SubjectCollectionEntity.toSubjectCollectionInfo(
    episodes: List<EpisodeCollectionInfo>,
    currentDate: PackedDate,
    nsfwModeSettings: NsfwMode,
): SubjectCollectionInfo {
    val subjectInfo = toSubjectInfo()
    return SubjectCollectionInfo(
        collectionType = collectionType,
        subjectInfo = subjectInfo,
        selfRatingInfo = selfRatingInfo,
        episodes = episodes,
        // 没有分集时退到只用条目自身的信息. 追番列表只为"在看"与"搁置"的条目补分集 (见
        // fetchEpisodesForInProgressSubjects), 其余类型的卡片走这条路, 进过详情页之后分集就齐了.
        airingInfo = if (episodes.isEmpty()) {
            SubjectAiringInfo.computeFromSubjectInfo(subjectInfo, totalEpisodes)
        } else {
            SubjectAiringInfo.computeFromEpisodeList(
                episodes.map { it.episodeInfo },
                airDate,
                recurrence,
            )
        },
        progressInfo = if (episodes.isEmpty() && collectionType == UnifiedCollectionType.DONE) {
            // 没有分集时 compute 会算成"还没看过"→ 显示"开始观看". 用户已经标了看过, 直接给完成态.
            SubjectProgressInfo.Done
        } else {
            SubjectProgressInfo.compute(subjectInfo, episodes, currentDate, recurrence)
        },
//        isOnAir = ,
        recurrence = recurrence,
        cachedStaffUpdated = cachedStaffUpdated,
        cachedCharactersUpdated = cachedCharactersUpdated,
        lastUpdated = lastUpdated,
        nsfwMode = if (nsfw) nsfwModeSettings else NsfwMode.DISPLAY,
        relations = relations ?: SubjectRelations.Empty,
    )
}

/**
 * `@Relation` 一次取出的"条目 + 其全部剧集"到 [SubjectCollectionInfo] 的**唯一**映射,
 * 追番页 pager 与探索页"继续观看"共用.
 *
 * 两处必须共用, 否则下面的排序只在其中一处生效 —— 曾经就是如此, 追番页那条路吐出的是数据库原始顺序.
 */
private fun SubjectCollectionAndEpisodes.toSubjectCollectionInfo(
    epTypes: List<EpisodeType>,
    currentDate: PackedDate,
    nsfwModeSettings: NsfwMode,
): SubjectCollectionInfo = collection.toSubjectCollectionInfo(
    episodes = episodesOfAnyType
        .asSequence()
        .filter { it.episodeType in epTypes }
        // @Relation 生成的子查询没有 ORDER BY, SQLite 按 (subjectId, episodeId) 索引吐回,
        // 而 SubjectCollectionInfo.episodes 约定按 sort 升序. 条目后补一集 (episodeId 更大但 sort 靠前,
        // 常见于补录的 SP/前置话) 时两者就会分叉, 吃这个顺序的下游全部算错:
        // SubjectAiringInfo.computeFromEpisodeList 的 firstSort/latestSort (卡片"全 X 话/更新至 X 话")、
        // TmdbEpisodeStills.matchToEpisodes 按 index±1 取锚点的三明治插值 (hero 剧照错位).
        // (SubjectProgressInfo.compute 自己会再排一次, 不在此列.)
        // 等价于 DAO 里其余查询的 `ORDER BY sortNumber ASC, sort ASC`:
        // - 次级键不能省: sortNumber 是 EpisodeSort.number, 拿不到序号时一律是 Float.MAX_VALUE
        //   (见 EpisodeCollectionEntity 的 defaultValue), 特殊剧集全挤在同一个值上;
        // - 次级键用 sort.toString() 而不是 EpisodeSort 自身: sort 列存的就是 toString()
        //   (EpisodeSortConverter), 字符串比较与 SQLite 一致; 而 EpisodeSort.compareTo 的 Special
        //   分支不满足反对称性 (a<b 却 b==a), 剧集一多会撞上 TimSort 的 contract 检查而抛异常.
        .sortedWith(compareBy({ it.sortNumber }, { it.sort.toString() }))
        .map { it.toEpisodeCollectionInfo() }
        .toList(),
    currentDate = currentDate,
    nsfwModeSettings = nsfwModeSettings,
)


data class LoadInfo(
    val offset: Int,
    val limit: Int,
)

fun <T : Any> calculateIndexBasedLoadInfo(
    loadType: LoadType,
    state: PagingState<Int, T>
): LoadInfo? {
    return when (loadType) {
        LoadType.REFRESH -> {
            LoadInfo(0, state.config.pageSize)
        }

        LoadType.PREPEND -> {
            val firstLoadedPage = state.pages.firstOrNull()
            if (firstLoadedPage != null) {
                if (firstLoadedPage.itemsBefore == 0) {
                    // 没有更多数据了
                    return null
                }
                val offset = firstLoadedPage.itemsBefore - state.config.pageSize
                if (offset >= 0) {
                    LoadInfo(
                        offset,
                        state.config.pageSize,
                    )
                } else {
                    LoadInfo(
                        0,
                        (state.config.pageSize + offset).coerceAtLeast(1),
                    )
                }
            } else {
                LoadInfo(
                    0,
                    state.config.pageSize,
                )
            }
        }

        LoadType.APPEND -> {
            val lastLoadedPage = state.pages.lastOrNull()
            //                        logger.warn { "Mediator APPEND, lastLoadedPage ${}" }
            val offset = if (lastLoadedPage != null) {
                lastLoadedPage.itemsBefore + lastLoadedPage.data.size
            } else {
                0
            }
            LoadInfo(
                offset,
                state.config.pageSize,
            )
        }
    }
}

/** infobox 里表示"影院上映日期"的字段名. */
private val SCREENING_DATE_KEYS = setOf("上映年度", "上映日期", "其他上映日期", "其他上映年度")

private val YEAR_REGEX = Regex("""(?:19|20)\d{2}""")


/**
 * 条目大封面的地址, 不依赖本地数据库 —— 本地没有记录时的兜底展示.
 *
 * 走 bangumi 自己的重定向端点而不是直接拼图床路径: `lain.bgm.tv` 的路径里带着图片自己的哈希
 * (`/pic/cover/l/b7/58/302286_s3o3E.jpg`), 光有 subjectId 拼不出来; 这个端点 302 过去.
 *
 * 它是 `bgm.tv` 域名, 所以连不上官方时会被镜像改写一并接管 (见 `BangumiMirrorFeature`).
 */
fun staticSubjectImageLargeUrl(subjectId: Int): String =
    "https://api.bgm.tv/v0/subjects/$subjectId/image?type=large"

/**
 * 本地数据库中缓存的条目展示信息.
 * @see SubjectCollectionRepository.getSubjectDisplayInfoOffline
 */
data class OfflineSubjectDisplayInfo(
    val subjectId: Int,
    val displayName: String,
    val imageLarge: String,
    val totalEpisodes: Int,
)

