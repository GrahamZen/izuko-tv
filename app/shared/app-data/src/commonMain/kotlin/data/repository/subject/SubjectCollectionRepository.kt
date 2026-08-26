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
import me.him188.ani.app.data.models.bangumi.BangumiSyncState
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.preference.NsfwMode
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
import me.him188.ani.app.data.network.SubjectService
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
import me.him188.ani.client.models.AniAnimeRecurrence
import me.him188.ani.client.models.AniCollectionType
import me.him188.ani.client.models.AniEpisodeCollection
import me.him188.ani.client.models.AniEpisodeCollectionType
import me.him188.ani.client.models.AniEpisodeType
import me.him188.ani.client.models.AniFavourite
import me.him188.ani.client.models.AniInfobox
import me.him188.ani.client.models.AniSelfRatingInfo
import me.him188.ani.client.models.AniSubjectCollection
import me.him188.ani.client.models.AniSubjectRelations
import me.him188.ani.client.models.AniTag
import me.him188.ani.client.models.AniUpdateSubjectCollectionRequest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.processing.toSubjectCollectionType
import me.him188.ani.utils.coroutines.combine
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.annotations.TestOnly
import me.him188.ani.utils.platform.currentTimeMillis
import me.him188.ani.utils.serialization.BigNum
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
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

    abstract suspend fun performBangumiFullSync()

    abstract suspend fun getBangumiFullSyncState(): BangumiSyncState?

    /**
     * 使 [subjectIds] 对应条目的本地缓存失效, 并立即从服务端重新拉取这些条目 (并行度有限, 见实现):
     * - 服务端仍有收藏 → 用服务端的值覆盖本地行与剧集缓存 (正在展示的收藏列表随之更新);
     * - 服务端已无收藏 (条目不存在或未收藏) → 删除本地行 (剧集缓存随之级联删除);
     * - 网络失败 → 保留本地行 (绝不因失败删除), 只将其 `lastFetched` 置 0, 下次访问时重新拉取;
     *   首次失败后不再对剩余条目发起新的拉取 (多半是断网, 逐个等待超时会让 "应用合并" 长时间转圈), 已发起的照常完成.
     *
     * 之后将所有条目的 `lastFetched` 置 0 (下次创建收藏列表分页器时从服务端刷新), 并发出 [collectionsInvalidated].
     *
     * [subjectIds] 为空时不做任何事.
     *
     * 用于服务端解决 Bangumi 收藏冲突之后: 这些条目在服务端的值已经改变, 本地缓存不再可信.
     */
    abstract suspend fun invalidateCache(subjectIds: List<Int>)

    /**
     * 将所有条目的 `lastFetched` 置 0 (不删除本地数据), 使下次进入收藏页或条目页时从服务端刷新, 并发出 [collectionsInvalidated].
     *
     * 用于服务端 Bangumi 全量同步 (对账) 完成之后: 自动合并的结果已写入服务端, 本地缓存可能过期.
     */
    abstract suspend fun invalidateAllCaches()

    private val _collectionsInvalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * [invalidateCache] / [invalidateAllCaches] 完成后发出一次, 供已经创建的收藏列表分页器重新加载.
     *
     * 分页器只在创建时 (`RemoteMediator.initialize`) 根据 `lastFetched` 决定是否从服务端刷新, 已在展示的列表不会因为
     * `lastFetched` 被置 0 而自动刷新; 收藏页的 ViewModel 收集此流并重建分页器.
     */
    val collectionsInvalidated: SharedFlow<Unit> = _collectionsInvalidated.asSharedFlow()

    /**
     * [collectionsInvalidated] 当前的订阅者数. 仅测试用: 等 ViewModel 订阅之后再触发失效, 否则事件没有订阅者会被丢弃.
     */
    @TestOnly
    val collectionsInvalidatedSubscriptionCount: StateFlow<Int>
        get() = _collectionsInvalidated.subscriptionCount

    /**
     * 缓存失效完成后调用, 发出 [collectionsInvalidated]. 没有订阅者时直接丢弃; 订阅者来不及处理时多次失效合并为一次.
     */
    protected fun notifyCollectionsInvalidated() {
        _collectionsInvalidated.tryEmit(Unit)
    }
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

                // 如果没有缓存, 则 fetch 然后插入 subject 缓存
                if (existing == null || existing.isExpired()) {
                    refetchSubjectCollection(subjectId)
                    // TODO: 2025/5/24 handle subject not found 
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
    }.flowOn(defaultDispatcher)

    /**
     * 从服务端拉取条目 (含用户的收藏状态与剧集) 并写入本地缓存: 覆盖同 id 的旧行 (`lastFetched` 为当前时间), 删除本地多余的剧集.
     *
     * @return 服务端返回的条目; 条目不存在 (404) 时为 `null`, 此时不写入任何东西.
     */
    private suspend fun refetchSubjectCollection(subjectId: Int): AniSubjectCollection? {
        val subject = subjectService.getSubjectCollection(subjectId) ?: return null
        val lastFetched = currentTimeMillis()
        val subjectEntity = subject.toEntity(lastFetched = lastFetched)
        val episodeEntities = subject.episodes.map {
            it.toEntity1(subjectId, lastFetched = lastFetched)
        }
        subjectCollectionDao.upsert(subjectEntity)

        // 更新剧集列表
        val oldIds = episodeCollectionDao.listIdBySubjectId(subjectId).first().toMutableList()
        episodeCollectionDao.upsert(episodeEntities)
        for (newEntity in episodeEntities) {
            oldIds.remove(newEntity.episodeId)
        }
        if (oldIds.isNotEmpty()) { // 删除本地存的多余的剧集 (通常没有)
            episodeCollectionDao.deleteAllByEpisodeIds(subjectId, oldIds)
        }
        return subject
    }

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
                    fetchAndSaveSubjectCollectionsWithEpisodes(type, limit, offset)
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
     */
    private suspend inline fun fetchAndSaveSubjectCollectionsWithEpisodes(
        type: UnifiedCollectionType?,
        limit: Int,
        offset: Int,
        onFetched: (items: List<AniSubjectCollection>) -> Unit = {},
    ) {
        require(type != UnifiedCollectionType.NOT_COLLECTED) { "type must not be NOT_COLLECTED" }
        require(limit > 0) { "limit must be positive" }

        // 执行网络请求查询好需要的 subject 和 episodes
        val items = subjectService.getSubjectCollections(
            type = type?.toSubjectCollectionType(),
            offset = offset,
            limit = limit,
        )

        onFetched(items)

        // 批量插入条目信息
        val lastFetched = currentTimeMillis()
        subjectCollectionDao.upsert(
            items.mapIndexed { index, batchSubjectCollection ->
                batchSubjectCollection.toEntity(lastFetched = lastFetched)
            },
        )

        // 必须先插入好条目信息, 否则插入 episode 会 foreign key constraint failed
        episodeCollectionDao.upsert(
            items
                .flatMap { it.episodes }
                .map { episode ->
                    episode.toEntity1(
                        subjectId = episode.subjectId.toInt(),
                        lastFetched = lastFetched,
                    )
                },
        )
    }

    override suspend fun updateRating(
        subjectId: Int,
        score: Int?, // 0 to remove rating
        comment: String?, // set empty to remove
        tags: List<String>?,
        isPrivate: Boolean?,
    ) {
        withContext(defaultDispatcher) {
            subjectService.patchSubjectCollection(
                subjectId,
                AniUpdateSubjectCollectionRequest(
                    selfRating = AniSelfRatingInfo(
                        score = score ?: 0,
                        comment = comment,
                        tags = tags.orEmpty(),
                        isPrivate = isPrivate ?: false,
                    ),
                ),
            )

            subjectCollectionDao.updateRating(
                subjectId,
                score,
                comment,
                tags,
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
                patchSubjectCollection(
                    subjectId,
                    AniUpdateSubjectCollectionRequest(collectionType = type.toAniSubjectCollectionType()),
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
        payload: AniUpdateSubjectCollectionRequest,
    ) {
        withContext(defaultDispatcher) {
            subjectService.patchSubjectCollection(subjectId, payload)
            subjectCollectionDao.updateType(subjectId, payload.collectionType.toUnifiedCollectionType())
        }
    }

    private suspend fun deleteSubjectCollection(subjectId: Int) {
        withContext(defaultDispatcher) {
            subjectService.deleteSubjectCollection(subjectId)
            subjectCollectionDao.delete(subjectId)
        }
    }

    override suspend fun performBangumiFullSync() {
        try {
            withContext(defaultDispatcher) {
                subjectService.performBangumiFullSync()
            }
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }

    override suspend fun getBangumiFullSyncState(): BangumiSyncState? {
        return try {
            withContext(defaultDispatcher) {
                subjectService.getBangumiFullSyncState()
            }
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }

    override suspend fun invalidateCache(subjectIds: List<Int>) {
        if (subjectIds.isEmpty()) return
        withContext(defaultDispatcher) {
            coroutineScope {
                // 有限并行: 解决冲突后通常要重新拉取几十个条目 (每个都带完整剧集列表), 串行会让 "应用合并" 等几十个 RTT.
                val semaphore = Semaphore(INVALIDATE_REFETCH_PARALLELISM)
                // 首次网络失败后不再发起新的拉取: 断网时每个请求都要等到连接超时, 剩余行由下面的 resetAllLastFetched 覆盖.
                val failed = atomic(false)
                subjectIds.distinct().map { subjectId ->
                    async {
                        semaphore.withPermit {
                            if (failed.value) return@withPermit
                            val fetched = try {
                                refetchSubjectCollection(subjectId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // 网络失败: 保留本地行, 靠下面的 resetAllLastFetched 让它下次重新拉取.
                                // 绝不因失败删除, 否则条目会从正在展示的收藏列表里消失.
                                failed.value = true
                                logger.warn(e) { "Failed to refetch subject collection $subjectId after invalidation, keeping the cached row and skipping the remaining refetches" }
                                return@withPermit
                            }
                            if (fetched == null || fetched.collectionType == null) {
                                // 服务端已无收藏 (条目不存在或未收藏): 删除本地行, 剧集缓存有 ON DELETE CASCADE 随之删除
                                subjectCollectionDao.delete(subjectId)
                            }
                        }
                    }
                }.awaitAll()
            }
            // 分页器创建时只看最新的 lastFetched 决定是否从服务端刷新; 已在展示的分页器由 collectionsInvalidated 触发重建
            subjectCollectionDao.resetAllLastFetched()
        }
        notifyCollectionsInvalidated()
    }

    override suspend fun invalidateAllCaches() {
        withContext(defaultDispatcher) {
            subjectCollectionDao.resetAllLastFetched()
        }
        notifyCollectionsInvalidated()
    }

    private companion object {
        private val logger = logger<SubjectCollectionRepository>()

        /**
         * [invalidateCache] 重新拉取条目的最大并行数.
         */
        private const val INVALIDATE_REFETCH_PARALLELISM = 4
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
        airingInfo = SubjectAiringInfo.computeFromEpisodeList(
            episodes.map { it.episodeInfo },
            airDate,
            recurrence,
        ),
        progressInfo = SubjectProgressInfo.compute(subjectInfo, episodes, currentDate, recurrence),
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

fun AniSubjectCollection.toEntity(
    lastFetched: Long,
): SubjectCollectionEntity {
    return SubjectCollectionEntity(
        subjectId = id.toInt(),
        name = name,
        nameCn = nameCn,
        summary = summary,
        nsfw = nsfw,
        imageLarge = staticSubjectImageLargeUrl(id.toInt()),
        totalEpisodes = episodes.size,
        airDate = PackedDate.parseFromDate(airDate),
        aliases = buildList {
            addAll(aliases)
            // Also extract "别名" entries from infobox — the server's aliases field may be
            // incomplete and miss Traditional Chinese / English / other-language names.
            infobox?.fields
                ?.filter { it.key == "别名" }
                ?.flatMap { item -> item.propertyValues.map { it.v } }
                ?.filter { it.isNotBlank() && !aliases.contains(it) }
                ?.let { addAll(it) }
        },
        tags = tags.map { it.toTag() },
        collectionStats = favorite.toSubjectCollectionStats(),
        ratingInfo = RatingInfo(
            rank = rank ?: 0,
            total = scoreDetails.values.sum(),
            count = RatingCounts(
                s1 = scoreDetails["1"] ?: 0,
                s2 = scoreDetails["2"] ?: 0,
                s3 = scoreDetails["3"] ?: 0,
                s4 = scoreDetails["4"] ?: 0,
                s5 = scoreDetails["5"] ?: 0,
                s6 = scoreDetails["6"] ?: 0,
                s7 = scoreDetails["7"] ?: 0,
                s8 = scoreDetails["8"] ?: 0,
                s9 = scoreDetails["9"] ?: 0,
                s10 = scoreDetails["10"] ?: 0,
            ),
            score = score ?: "0",
        ),
        completeDate = PackedDate.Invalid,
        selfRatingInfo = selfRating.toSelfRatingInfo(),
        collectionType = collectionType.toUnifiedCollectionType(),
        recurrence = airingInfo?.recurrence?.toSubjectRecurrence(),
        relations = relations.toSubjectRelationsEntity(),
        screeningYear = infobox?.screeningYearOrNull(PackedDate.parseFromDate(airDate).year),
        theatrical = infobox?.isTheatricalOnly() == true,
        lastUpdated = updatedAt?.let { Instant.parse(it) }?.toEpochMilliseconds() ?: 0,
        lastFetched = lastFetched,
        cachedStaffUpdated = 0,
        cachedCharactersUpdated = 0,
    )
}

/** infobox 里表示"影院上映日期"的字段名. */
private val SCREENING_DATE_KEYS = setOf("上映年度", "上映日期", "其他上映日期", "其他上映年度")

private val YEAR_REGEX = Regex("""(?:19|20)\d{2}""")

/**
 * infobox 「上映年度」里**最早**的那个年份; 没有该字段, **或 [airYear] 本来就在这些年份里**,
 * 都返回 `null` —— 后者说明 `airDate` 记的就是上映日, 没必要换个年份去判.
 *
 * 只取最早那个: 老片的 infobox 会把重映年也列上 (攻殻機動隊 是 `[1995, 2025]`, 2025 是 4K 重映),
 * 全盘接受会让 2026 年的新片「The Ghost in the Shell」也过年份判据、顶掉 1995 那部正解.
 */
private fun AniInfobox.screeningYearOrNull(airYear: Int?): Int? {
    val years = fields.asSequence()
        .filter { it.key in SCREENING_DATE_KEYS }
        .flatMap { item -> item.propertyValues.asSequence().map { it.v } }
        .mapNotNull { YEAR_REGEX.find(it)?.value?.toIntOrNull() }
        .toList()
    if (years.isEmpty() || airYear in years) return null
    return years.min()
}

/**
 * 是否**只在影院放映**: 有上映日期而没有「放送开始」. 见 [SubjectCollectionEntity.theatrical].
 */
private fun AniInfobox.isTheatricalOnly(): Boolean {
    val keys = fields.mapTo(mutableSetOf()) { it.key }
    return keys.any { it in SCREENING_DATE_KEYS } && "放送开始" !in keys
}

/**
 * 条目大封面的静态 CDN 地址. 不依赖本地数据库, 可用于本地无记录时的兜底展示.
 */
fun staticSubjectImageLargeUrl(subjectId: Int): String =
    "https://static.myani.org/bangumi/subjects/$subjectId/large"

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

fun AniSubjectRelations.toSubjectRelationsEntity(): SubjectRelations {
    return SubjectRelations(
        seriesMainSubjectIds,
        seriesMainSubjectNames,
        sequelSubjects,
        sequelSubjectNames,
    )
}

fun AniTag.toTag(): Tag = Tag(
    name = name,
    count = count,
)

fun AniFavourite.toSubjectCollectionStats(): SubjectCollectionStats {
    return SubjectCollectionStats(
        wish = wish,
        doing = doing,
        done = done,
        onHold = onHold,
        dropped = dropped,
    )
}

fun AniAnimeRecurrence.toSubjectRecurrence(): SubjectRecurrence? {
    return SubjectRecurrence(
        Instant.parse(startTime),
        interval = intervalMillis.milliseconds,
    )
}

fun AniCollectionType?.toUnifiedCollectionType(): UnifiedCollectionType {
    return when (this) {
        AniCollectionType.WISH -> UnifiedCollectionType.WISH
        AniCollectionType.DOING -> UnifiedCollectionType.DOING
        AniCollectionType.DONE -> UnifiedCollectionType.DONE
        AniCollectionType.ON_HOLD -> UnifiedCollectionType.ON_HOLD
        AniCollectionType.DROPPED -> UnifiedCollectionType.DROPPED
        null -> UnifiedCollectionType.NOT_COLLECTED
    }
}

fun AniEpisodeCollection.toEntity1(
    subjectId: Int,
    lastFetched: Long,
): EpisodeCollectionEntity {
    return EpisodeCollectionEntity(
        subjectId = subjectId,
        episodeId = episodeId.toInt(),
        episodeType = type.toEpisodeType(),
        name = name,
        nameCn = nameCn,
        airDate = airdate?.let { PackedDate.parseFromDate(it) } ?: PackedDate.Invalid,
        comment = 0,
        desc = description,
        sort = EpisodeSort(BigNum(sort), type.toEpisodeType()),
        ep = ep?.let { EpisodeSort(BigNum(it), type.toEpisodeType()) },
        sortNumber = sort.toFloatOrNull() ?: 0f,
        selfCollectionType = collectionType.toUnifiedCollectionType(),
        lastFetched = lastFetched,
    )
}

fun AniEpisodeType.toEpisodeType(): EpisodeType? {
    return when (this) {
        AniEpisodeType.MAIN -> EpisodeType.MainStory
        AniEpisodeType.SPECIAL -> EpisodeType.SP
        AniEpisodeType.OP -> EpisodeType.OP
        AniEpisodeType.ED -> EpisodeType.ED
        AniEpisodeType.TRAILER -> EpisodeType.PV
        AniEpisodeType.MAD -> EpisodeType.MAD
        AniEpisodeType.OTHER -> null
    }
}

fun AniEpisodeCollectionType?.toUnifiedCollectionType(): UnifiedCollectionType {
    return when (this) {
        null -> UnifiedCollectionType.NOT_COLLECTED
        AniEpisodeCollectionType.DONE -> UnifiedCollectionType.DONE
    }
}

fun AniSelfRatingInfo.toSelfRatingInfo(): SelfRatingInfo {
    return SelfRatingInfo(
        score = score, comment = comment, tags = tags, isPrivate = isPrivate,
    )
}

fun UnifiedCollectionType.toAniSubjectCollectionType(): AniCollectionType? {
    return when (this) {
        UnifiedCollectionType.WISH -> AniCollectionType.WISH
        UnifiedCollectionType.DOING -> AniCollectionType.DOING
        UnifiedCollectionType.DONE -> AniCollectionType.DONE
        UnifiedCollectionType.ON_HOLD -> AniCollectionType.ON_HOLD
        UnifiedCollectionType.DROPPED -> AniCollectionType.DROPPED
        UnifiedCollectionType.NOT_COLLECTED -> null
    }
}
