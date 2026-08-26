/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
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
import me.him188.ani.app.data.network.AniSubjectRelationIndexService
import me.him188.ani.app.data.network.BatchSubjectRelations
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
    private val aniSubjectRelationIndexService: AniSubjectRelationIndexService,
    defaultDispatcher: CoroutineContext = Dispatchers.Default,
    private val autoRefreshPeriod: Duration = 1.hours,
    // 角色与制作人员几乎不变, 过期时间不宜太短: 该接口曾占服务端出站流量的一半以上.
    private val cacheExpiry: Duration = 3.days,
) : SubjectRelationsRepository(defaultDispatcher) {
    override fun subjectSequelSubjectIdsFlow(subjectId: Int): Flow<List<Int>> = flow {
        emit(
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                // 这服务极快, 不会超时. 10 秒还没完, 只能是服务器重启了一下, 正在构造索引
                aniSubjectRelationIndexService.getSubjectRelationIndex(subjectId).sequelSubjects
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
        emit(
            aniSubjectRelationIndexService.getSubjectRelationIndex(subjectId),
        )
    }.combine(subjectCollectionRepository.subjectCollectionFlow(subjectId)) { relations, requestingSubject ->
        combine(
            (relations.sequelSubjects.toSet() + relations.seriesMainSubjectIds).map {
                subjectCollectionRepository.subjectCollectionFlow(it)
            },
        ) { subjectCollectionInfos ->
            SubjectSeriesInfo.compute(
                requestingSubject = requestingSubject,
            )
        }
    }.flatMapLatest {
        it
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
                if ((currentTimeMillis() - cachedCharactersUpdated).milliseconds > cacheExpiry) {
                    fetchRelationsIfStaleOrNull(subjectId)
                }

                subjectRelationsDao.subjectRelatedPersonsFlow(subjectId).map { list ->
                    list.mapTo(ArrayList(list.size)) {
                        it.toRelatedPersonInfo()
                    }.apply {
                        sortWith(RelatedPersonInfo.ImportanceOrder)
                    }
                }
            }.flowOn(defaultDispatcher)
    }

    override fun subjectRelatedCharactersFlow(subjectId: Int): Flow<List<RelatedCharacterInfo>> {
        return relationsFreshnessFlow(subjectId)
            .autoRefresh()
            .flatMapLatest { cachedCharactersUpdated ->
                if ((currentTimeMillis() - cachedCharactersUpdated).milliseconds > cacheExpiry) {
                    fetchRelationsIfStaleOrNull(subjectId)
                }

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
                }
            }.flowOn(defaultDispatcher)
    }

    /**
     * 角色/制作人员这两条流**只跟着"关联数据什么时候取的"这一个字段走**, 不跟着整个条目走.
     *
     * 它们都是 `flatMapLatest { 过期就取数; 再返回 DAO flow }`, 而上游
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

    private val relationsFetcher = StaleKeyedFetcher<Int>()

    /**
     * **同一条目的关联数据只取一次**: 角色区块与制作人员区块是两条独立的流, 各自判一遍"过期就取",
     * 于是同一份数据取两遍 —— 实测每次进详情页 `/characters` 与 `/staff` **各打两遍**, 落库也各跑
     * 一遍, 而落库本身要 1.5~3 秒且 `upsert 三张表 → 再 upsert relations` 之间没有事务
     * (那个顺序是外键要求的, 两份交错跑没有保护).
     *
     * 去重靠 [StaleKeyedFetcher]: 按 subjectId 串行, 等到锁再重查一次新鲜度, 已经取回来了就直接返回.
     */
    /**
     * 同 [fetchRelationsIfStale], 但**取数失败不抛** —— 这里的调用方在 flatMapLatest 里,
     * 异常会把整条流杀死: 角色/制作人员区块从此定格, UI 侧的加载骨架永远收不掉 (count 停在
     * null), 直到重进页面.
     *
     * 失败先**短退避重试** ([RELATIONS_FETCH_RETRIES] 次): 一次瞬时网络抖动如果直接放弃,
     * DAO flow 发出的空列表会被 UI 读成"这部作品确实没有角色/制作人员", 而下一次自动重试
     * 是 autoRefresh 的一小时后. 重试全败才放弃 —— 此时多半是持续断网, 显示空区块 (有旧
     * 缓存则显示旧的) 比让骨架永远转下去诚实.
     */
    private suspend fun fetchRelationsIfStaleOrNull(subjectId: Int) {
        var delayMillis = RELATIONS_FETCH_RETRY_DELAY_MILLIS
        repeat(RELATIONS_FETCH_RETRIES + 1) { attempt ->
            try {
                fetchRelationsIfStale(subjectId)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == RELATIONS_FETCH_RETRIES) {
                    logger.warn(e) { "Failed to fetch relations for subject $subjectId after ${attempt + 1} attempts, showing cached data if any" }
                    return
                }
                delay(delayMillis)
                delayMillis *= 4
            }
        }
    }

    private suspend fun fetchRelationsIfStale(subjectId: Int) {
        relationsFetcher.fetchIfStale(
            key = subjectId,
            isFresh = {
                val updated = subjectCollectionDao.findById(subjectId).first()?.cachedCharactersUpdated ?: 0L
                (currentTimeMillis() - updated).milliseconds <= cacheExpiry
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
        // **必须有超时**: 实测单个 staff 请求会在连接上挂死 9 秒+ (2026-08-26, 高达00 subject
        // 1010, characters 371ms 就回来了而并行的 staff 一直不响应) —— 并行等待要两个都完成,
        // 整个取数跟着挂, UI 的加载骨架也跟着挂; 而退避重试要"失败"才触发, 挂死不算失败,
        // 用户只能退出重进 (流取消) 手动帮它重试. 超时算失败, 交给重试: 这种挂死换个连接
        // 第二发通常几百 ms 就成功. 正常耗时中位 ~500ms, 5 秒已远超长尾.
        val batch = try {
            withTimeout(RELATIONS_FETCH_TIMEOUT_MILLIS) {
                subjectService.getSubjectRelations(subjectId, withCharacterActors = true)
            }
        } catch (e: TimeoutCancellationException) {
            // 转成普通异常: TimeoutCancellationException 是 CancellationException 子类, 原样
            // 往外抛会被上层"取消照抛"的分支当成外部取消, 绕过重试直接杀流
            throw RepositoryServiceUnavailableException("relations fetch for $subjectId timed out", e)
        }
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

        /** 取数失败的追加重试次数 (总尝试 = 这个数 + 1). */
        private const val RELATIONS_FETCH_RETRIES = 2

        /** 首次重试前的等待; 之后每次 x4 (1s -> 4s). */
        private const val RELATIONS_FETCH_RETRY_DELAY_MILLIS = 1000L

        /** 单次取数 (两个并行请求 + 解析) 的超时; 见 [fetchAndSaveSubjectRelations] 里的挂死记录. */
        private const val RELATIONS_FETCH_TIMEOUT_MILLIS = 5000L
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
        imageLarge = imageLarge,
        imageMedium = imageMedium,
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
        imageLarge = imageLarge,
        imageMedium = imageMedium,
        summary = summary,
        locked = false,
        nameCn = nameCn,
    )
}


private fun BatchSubjectRelations.characterActorRelations() =
    relatedCharacterInfoList.asSequence().flatMap { relatedCharacterInfo ->
        relatedCharacterInfo.character.actors.asSequence().map { person ->
            CharacterActorEntity(relatedCharacterInfo.character.id, person.id)
        }
    }

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
