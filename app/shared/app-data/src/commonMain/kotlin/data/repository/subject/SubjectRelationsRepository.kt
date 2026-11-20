/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.models.subject.CharacterInfo
import me.him188.ani.app.data.models.subject.PersonInfo
import me.him188.ani.app.data.models.subject.RelatedCharacterInfo
import me.him188.ani.app.data.models.subject.RelatedPersonInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.network.SubjectSeriesIndexService
import me.him188.ani.app.data.network.sortsSpanSeriesOf
import me.him188.ani.app.data.network.splitSeasonOf
import me.him188.ani.app.data.network.toSubjectRelations
import me.him188.ani.app.data.network.withSeasonNames
import me.him188.ani.app.data.network.BatchSubjectRelations
import me.him188.ani.app.data.network.mapper.orBangumiPlaceholder
import me.him188.ani.app.data.network.SubjectService
import me.him188.ani.app.data.persistent.database.dao.RelatedCharacterView
import me.him188.ani.app.data.persistent.database.dao.RelatedPersonView
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionDao
import me.him188.ani.app.data.persistent.database.dao.SubjectRelationsDao
import me.him188.ani.app.data.persistent.database.entity.CharacterActorEntity
import me.him188.ani.app.data.persistent.database.entity.CharacterEntity
import me.him188.ani.app.data.persistent.database.entity.PersonEntity
import me.him188.ani.app.data.persistent.database.entity.SubjectCharacterRelationEntity
import me.him188.ani.app.data.persistent.database.entity.SubjectPersonRelationEntity
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.RepositoryServiceUnavailableException
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.collections.mapToIntArray
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed class SubjectRelationsRepository(
    defaultDispatcher: CoroutineContext = Dispatchers.Default
) : Repository(defaultDispatcher) {
    /**
     * 获取指定条目的所有续集 ID 列表, 包含正片和 SP 等特殊剧集. (仅包含 Anime 类型)
     */
    abstract fun subjectSequelSubjectIdsFlow(subjectId: Int): Flow<List<Int>>

    /**
     * 获取指定条目的所有续集列表, 包含正片和 SP 等特殊剧集. (仅包含 Anime 类型)
     */
    abstract fun subjectSequelSubjectsFlow(subjectId: Int): Flow<List<SubjectCollectionInfo>>

    /**
     * 获取指定条目的所有续集的名称列表, 包含正片和 SP 等特殊剧集, 并排除 [subjectId] 的名称. (仅包含 Anime 类型)
     */
    abstract fun subjectSeriesInfoFlow(subjectId: Int): Flow<SubjectSeriesInfo>

    abstract fun subjectRelatedPersonsFlow(subjectId: Int): Flow<List<RelatedPersonInfo>>
    abstract fun subjectRelatedCharactersFlow(subjectId: Int): Flow<List<RelatedCharacterInfo>>
}

class DefaultSubjectRelationsRepository(
    private val subjectCollectionDao: SubjectCollectionDao,
    private val subjectRelationsDao: SubjectRelationsDao,
    private val subjectService: SubjectService,
    private val subjectCollectionRepository: SubjectCollectionRepository,
    private val subjectSeriesIndexService: SubjectSeriesIndexService,
    /** 见 [StaleKeyedFetcher]: 关联数据的重取也不能挂在调用方协程上. */
    scope: CoroutineScope,
    defaultDispatcher: CoroutineContext = Dispatchers.Default,
    private val autoRefreshPeriod: Duration = 1.hours,
    // 角色与制作人员几乎不变, 过期时间不宜太短: 该接口曾占服务端出站流量的一半以上.
    private val cacheExpiry: Duration = 3.days,
) : SubjectRelationsRepository(defaultDispatcher) {
    override fun subjectSequelSubjectIdsFlow(subjectId: Int): Flow<List<Int>> = flow {
        emit(
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                // 客户端自己走关系闭包, 每个节点一个请求; 结果有内存缓存, 超时基本只会发生在首次且网络很差时
                subjectSeriesIndexService.getSubjectRelationIndex(subjectId).sequelSubjects
            }
                ?: throw RepositoryServiceUnavailableException("Failed to fetch subject sequel subjects for $subjectId due to timeout"),
        )
    }.flowOn(defaultDispatcher) // no auto refresh

    override fun subjectSequelSubjectsFlow(subjectId: Int): Flow<List<SubjectCollectionInfo>> {
        // no auto refresh
        return subjectSequelSubjectIdsFlow(subjectId)
            .flatMapLatest { list ->
                if (list.isEmpty()) { // combine(emptyList()) 不会 emit
                    return@flatMapLatest flowOf(emptyList())
                }
                combine(
                    list.map { relatedSubjectId ->
                        subjectCollectionRepository.subjectCollectionFlow(relatedSubjectId)
                    },
                ) {
                    it.toList()
                }
            }.flowOn(defaultDispatcher)
    }

    override fun subjectSeriesInfoFlow(subjectId: Int): Flow<SubjectSeriesInfo> = flow {
        emit(subjectSeriesIndexService.getSubjectRelationIndex(subjectId))
    }.combine(subjectCollectionRepository.subjectCollectionFlow(subjectId)) { index, requestingSubject ->
        // 条目库里的 relations 原本由 Ani 服务端随条目下发, 直连后没人填: 用刚走出来的系列索引
        val relations = index.toSubjectRelations().copy(splitSeason = index.splitSeasonOf(requestingSubject))
        SubjectSeriesInfo.compute(requestingSubject.copy(relations = relations))
            .copy(sortsSpanSeries = index.sortsSpanSeriesOf(requestingSubject))
            .withSeasonNames(index, subjectId, requestingSubject.subjectInfo.allNames)
    }.flowOn(defaultDispatcher)

//    override fun subjectSequelSubjectNamesFlow(subjectId: Int): Flow<Set<String>> {
//        return subjectSequelSubjectsFlow(subjectId)
//            .combine(subjectCollectionRepository.subjectCollectionFlow(subjectId)) { list, requestingSubject ->
//                list.flatMapTo(mutableSetOf()) { it.subjectInfo.allNames }.apply {
//                    removeAll { sequelName ->
//                        // 如果续集名称存在于当前名称中, 则删除, 否则可能导致过滤掉当前季度的条目
//                        requestingSubject.subjectInfo.allNames.any { it.contains(sequelName, ignoreCase = true) }
//                    }
//                }
//            }.onEach {
//                logger.info { "subjectSequelSubjectNamesFlow($subjectId): " + it.joinToString() }
//            }.flowOn(defaultDispatcher)
//    }

    override fun subjectRelatedPersonsFlow(subjectId: Int): Flow<List<RelatedPersonInfo>> {
        return relationsFreshnessFlow(subjectId)
            .autoRefresh()
            .flatMapLatest { cachedCharactersUpdated ->
                subjectRelationsDao.subjectRelatedPersonsFlow(subjectId).map { list ->
                    list.mapTo(ArrayList(list.size)) {
                        it.toRelatedPersonInfo()
                    }.apply {
                        sortWith(RelatedPersonInfo.ImportanceOrder)
                    }
                }.refreshingRelationsIfStale(subjectId, cachedCharactersUpdated)
            }.flowOn(defaultDispatcher)
    }

    override fun subjectRelatedCharactersFlow(subjectId: Int): Flow<List<RelatedCharacterInfo>> {
        return relationsFreshnessFlow(subjectId)
            .autoRefresh()
            .flatMapLatest { cachedCharactersUpdated ->
                subjectRelationsDao.subjectRelatedCharactersFlow(subjectId).flatMapLatest { list ->
                    subjectRelationsDao.characterActorsFlow(list.mapToIntArray { it.character.characterId })
                        .map { actors ->
                            list.mapTo(ArrayList(list.size)) { relatedCharacterView ->
                                val characterId = relatedCharacterView.character.characterId
                                relatedCharacterView.toRelatedCharacterInfo(
                                    actors = actors
                                        .asSequence()
                                        .filter { it.characterId == characterId }
                                        .map { it.person.toPersonInfo() }
                                        .toList(),
                                )
                            }.apply {
                                sortWith(RelatedCharacterInfo.ImportanceOrder)
                            }
                        }
                }.refreshingRelationsIfStale(subjectId, cachedCharactersUpdated)
            }.flowOn(defaultDispatcher)
    }

    /**
     * 角色/制作人员这两条流**只跟着"关联数据什么时候取的"这一个字段走**, 不跟着整个条目走.
     *
     * 它们都是 `flatMapLatest { DAO flow, 过期就同时去取 }` (见 [refreshingRelationsIfStale]), 而上游
     * [SubjectCollectionRepository.subjectCollectionFlow] 底下是 Room 的 `findById` ——
     * **Room 的失效是表级的**: 网格页预取往 `subject_collection` 写**别的**条目, 这条流照样重发,
     * `flatMapLatest` 就把在途的取数掐掉重来.
     *
     * 真机实测 (2026-08-26, 進撃の巨人 S2): 详情页停留 7 秒、期间别的条目落库 8 次, 本条目的流重算
     * 18 次、relations 取数被触发 **10 次、完成 0 次** —— 一次都没跑完, `updateCachedRelationsUpdated`
     * 就从没写过, 于是永远判"过期", 死循环; 更糟的是内层 DAO flow 压根轮不到创建, **角色/制作人员
     * 区块连库里的旧数据都显示不出来**. HTTP 侧同期 `/characters` 打了 10 次、`/staff` 7 次.
     *
     * 收敛到单个 `Long` 再 `distinctUntilChanged`, 无关写入就不再惊动这两条流.
     */
    private fun relationsFreshnessFlow(subjectId: Int): Flow<Long> =
        subjectCollectionRepository.subjectCollectionFlow(subjectId)
            .map { it.cachedCharactersUpdated }
            .distinctUntilChanged()

    private val relationsFetcher = StaleKeyedFetcher<Int>(scope)

    /**
     * 关联数据过期时, 角色 / 制作人员区块一边显示库里的, 一边在后台取, 取到为止 (页面开着就一直试, 见 [refreshRelationsUntilFresh]);
     * 取到后盖章 (`cachedCharactersUpdated`) 变了, 上游重发, 这条流整个换成新鲜的那条.
     *
     * 库里有旧的就先显示旧的; 库里是空的时先显示加载骨架, 最多 [RELATIONS_SKELETON_MAX_WAIT] —— 空列表会被界面读成
     * 「这部作品确实没有角色 / 制作人员」; 等满了还没取到就先显示空的, 取到再换上. 见 [refreshingInBackground].
     */
    private fun <T> Flow<List<T>>.refreshingRelationsIfStale(subjectId: Int, cachedCharactersUpdated: Long): Flow<List<T>> =
        if (relationsFresh(cachedCharactersUpdated, currentTimeMillis(), cacheExpiry)) {
            this
        } else {
            refreshingInBackground(RELATIONS_SKELETON_MAX_WAIT) { refreshRelationsUntilFresh(subjectId) }
        }

    /**
     * 同 [fetchRelationsIfStale], 但**失败不抛, 隔一阵再试, 直到取到** —— 调用方在 flatMapLatest 里,
     * 异常会把整条流杀死: 角色/制作人员区块从此定格, 直到重进页面.
     *
     * 跑在页面的流上, 页面走开就停 (正在取的那一次归 [relationsFetcher] 的 scope, 照样跑完落库).
     * 网络时好时坏 (加密直连时常见) 时, 页面开着期间通了就能补上, 不必等一小时一次的 autoRefresh 或重进页面.
     */
    private suspend fun refreshRelationsUntilFresh(subjectId: Int) {
        retryUntilDone(
            initialDelay = RELATIONS_FETCH_RETRY_DELAY,
            maxDelay = RELATIONS_FETCH_RETRY_MAX_DELAY,
            onFailure = { e, failures ->
                if (failures == 1) {
                    logger.warn(e) { "Failed to fetch relations for subject $subjectId, retrying while the page is open" }
                } else {
                    logger.info { "Failed to fetch relations for subject $subjectId ($failures times): $e" }
                }
            },
        ) {
            fetchRelationsIfStale(subjectId)
        }
    }

    /**
     * **同一条目的关联数据只取一次**: 角色区块与制作人员区块是两条独立的流, 各自判一遍"过期就取",
     * 于是同一份数据取两遍 —— 实测每次进详情页 `/characters` 与 `/staff` **各打两遍**, 落库也各跑
     * 一遍, 而落库本身要 1.5~3 秒且 `upsert 三张表 → 再 upsert relations` 之间没有事务
     * (那个顺序是外键要求的, 两份交错跑没有保护).
     *
     * 去重靠 [StaleKeyedFetcher]: 按 subjectId 串行, 等到锁再重查一次新鲜度, 已经取回来了就直接返回.
     */
    private suspend fun fetchRelationsIfStale(subjectId: Int) {
        relationsFetcher.fetchIfStale(
            key = subjectId,
            isFresh = {
                val updated = subjectCollectionDao.findById(subjectId).first()?.cachedCharactersUpdated ?: 0L
                relationsFresh(updated, currentTimeMillis(), cacheExpiry)
            },
        ) {
            fetchAndSaveSubjectRelations(subjectId)
        }
    }

    private fun <T> Flow<T>.autoRefresh() = refreshTicker().flatMapLatest { this@autoRefresh }

    private fun refreshTicker() = flow {
        while (true) {
            emit(Unit)
            delay(autoRefreshPeriod)
        }
    }

    private suspend fun fetchAndSaveSubjectRelations(subjectId: Int) {
        val t0 = currentTimeMillis()
        // 单个请求会在连接上挂死 (2026-08-26, 高达00 subject 1010, characters 371ms 就回来了而并行的
        // staff 9 秒+ 一直不响应), 另发一个通常几百 ms 就回来: [RELATIONS_HEDGE_AFTER] 没回来就补发一份, 谁先回来用谁.
        // 第一份不掐断: 网慢或请求在排队 (加密直连时外层同一个域名只并发 5 个, 前面挂着的请求能排几分钟)
        // 时掐掉重来永远轮不到. 正常耗时中位 ~500ms.
        val batch = withTimeoutOrNull(RELATIONS_FETCH_TIMEOUT) {
            hedged(RELATIONS_HEDGE_AFTER) {
                subjectService.getSubjectRelations(subjectId, withCharacterActors = true)
            }
        } ?: throw RepositoryServiceUnavailableException("relations fetch for $subjectId timed out")
        val tFetched = currentTimeMillis()
        // 落库是单个事务 (全有或全无 + 一轮 invalidation), 见 [SubjectRelationsDao.upsertBatch];
        // "盖章"留在事务外且放最后: 全部落库成功才算这份数据新鲜
        subjectRelationsDao.upsertBatch(
            subjectId = subjectId,
            persons = batch.allPersons.map { it.toEntity() }.toList(),
            characters = batch.relatedCharacterInfoList.map { it.character.toEntity() },
            characterActors = batch.characterActorRelations().toList(),
            personRelations = batch.relatedPersonInfoList.map { it.toRelationEntity(subjectId) },
            characterRelations = batch.relatedCharacterInfoList.map { it.toRelationEntity(subjectId) },
        )
        subjectCollectionDao.updateCachedRelationsUpdated(subjectId)
        // 网络与落库分开记账: "进详情页角色区块 1.5~3 秒才出来"的大头到底在哪, 靠这一条分辨
        logger.info {
            "Fetched subject $subjectId relations: ${batch.relatedCharacterInfoList.size} characters, " +
                    "network ${tFetched - t0}ms, db ${currentTimeMillis() - tFetched}ms"
        }
    }

    private companion object {
        private val logger = logger<SubjectRelationsRepository>()

        /** 取数失败后首次重试前的等待; 之后每次 ×4 (1s → 4s → 16s → 每分钟一次). */
        private val RELATIONS_FETCH_RETRY_DELAY = 1.seconds

        /** 页面开着时重试的最长间隔. */
        private val RELATIONS_FETCH_RETRY_MAX_DELAY = 1.minutes

        /** 库里没有时加载骨架最多转多久, 之后先显示空区块、后台接着取. 见 [refreshingRelationsIfStale]. */
        private val RELATIONS_SKELETON_MAX_WAIT = 20.seconds

        /** 第一份请求这么久没回来就补发一份. 见 [fetchAndSaveSubjectRelations] 里的挂死记录. */
        private val RELATIONS_HEDGE_AFTER = 5.seconds

        /** 一次取数 (含补发的那份) 最长等多久, 超了算失败, 交给重试. */
        private val RELATIONS_FETCH_TIMEOUT = 1.minutes
    }
}

private fun RelatedCharacterView.toRelatedCharacterInfo(
    actors: List<PersonInfo>,
): RelatedCharacterInfo {
    return RelatedCharacterInfo(
        index = index,
        character = character.toCharacterInfo(actors),
        role = role,
    )
}

private fun CharacterEntity.toCharacterInfo(actors: List<PersonInfo>): CharacterInfo {
    return CharacterInfo(
        id = characterId,
        name = name,
        nameCn = nameCn,
        actors = actors,
        // 出库时再兜一次占位图: 库里存着一批空串 —— 是补占位图之前写进去的 (真机上 person 表
        // 7393 行里 34 行为空), 而这两张表按 TTL 缓存, 不重新取就一直画成黑块.
        imageLarge = imageLarge.orBangumiPlaceholder(),
        imageMedium = imageMedium.orBangumiPlaceholder(),
    )
}

private fun RelatedPersonView.toRelatedPersonInfo(): RelatedPersonInfo {
    return RelatedPersonInfo(
        index = index,
        personInfo = person.toPersonInfo(),
        position = position,
    )
}

private fun PersonEntity.toPersonInfo(): PersonInfo {
    return PersonInfo(
        id = personId,
        name = name,
        type = type,
        careers = emptyList(),
        // 同上: 出库时兜占位图
        imageLarge = imageLarge.orBangumiPlaceholder(),
        imageMedium = imageMedium.orBangumiPlaceholder(),
        summary = summary,
        locked = false,
        nameCn = nameCn,
    )
}


/**
 * 每个角色落一行声优: character_actor 表的主键只有角色, 一个角色只存得下一位 —— 全写进去的话后写的覆盖先写的,
 * 存下的就成了接口顺序里的最后那位 (常常是中配 / 日配, 不是原版). 存排第一的那位 (原版优先, 见 sortedByOriginalCast).
 */
internal fun BatchSubjectRelations.characterActorRelations(): Sequence<CharacterActorEntity> =
    relatedCharacterInfoList.asSequence().mapNotNull { relatedCharacterInfo ->
        relatedCharacterInfo.character.actors.firstOrNull()?.let { person ->
            CharacterActorEntity(relatedCharacterInfo.character.id, person.id)
        }
    }

/**
 * 条目的关联数据 (角色 / 制作人员 / 声优) 在 [now] 还算不算新鲜: [updated] 是上次取完的时刻, 过了 [expiry] 就重取.
 *
 * [RELATIONS_VALID_SINCE_MILLIS] 之前取的一律当过期: 那时每个角色存下的声优是接口顺序里的最后一位, 常常是配音而不是原版
 * (见 [characterActorRelations]), 已看过的条目下次进详情页就重取. 设备时钟早于它时不看这条 —— 否则取完盖的章总早于它,
 * 每次都判过期、反复重取.
 */
internal fun relationsFresh(updated: Long, now: Long, expiry: Duration): Boolean {
    if (now >= RELATIONS_VALID_SINCE_MILLIS && updated < RELATIONS_VALID_SINCE_MILLIS) return false
    return (now - updated).milliseconds <= expiry
}

/** 2026-09-29T03:40:00Z, 声优改成原版优先、每个角色只存一位 (见 [relationsFresh]). */
internal const val RELATIONS_VALID_SINCE_MILLIS = 1_790_653_200_000L

private fun CharacterInfo.toEntity(): CharacterEntity {
    return CharacterEntity(
        characterId = id,
        name = name,
        nameCn = nameCn,
        imageLarge = imageLarge,
        imageMedium = imageMedium,
    )
}

private fun PersonInfo.toEntity(): PersonEntity {
    return PersonEntity(
        personId = id,
        name = name,
        nameCn = nameCn,
        type = type,
        imageLarge = imageLarge,
        imageMedium = imageMedium,
        summary = summary,
    )
}

private fun RelatedPersonInfo.toRelationEntity(subjectId: Int): SubjectPersonRelationEntity {
    return SubjectPersonRelationEntity(
        subjectId = subjectId,
        index = index,
        personId = personInfo.id,
        position = position,
    )
}

private fun RelatedCharacterInfo.toRelationEntity(subjectId: Int): SubjectCharacterRelationEntity {
    return SubjectCharacterRelationEntity(
        subjectId = subjectId,
        index = index,
        characterId = character.id,
        role = role,
    )
}
