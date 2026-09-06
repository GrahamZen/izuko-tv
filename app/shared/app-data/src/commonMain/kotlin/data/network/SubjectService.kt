/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.bangumi.BangumiSyncState
import me.him188.ani.app.data.models.subject.CharacterInfo
import me.him188.ani.app.data.models.subject.CharacterRole
import me.him188.ani.app.data.models.subject.PersonPosition
import me.him188.ani.app.data.models.subject.RatingCounts
import me.him188.ani.app.data.models.subject.RelatedCharacterInfo
import me.him188.ani.app.data.models.subject.RelatedPersonInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionCounts
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.checkAccessAniApiNow
import me.him188.ani.app.platform.getAniUserAgent
import me.him188.ani.app.data.network.mapper.toCharacterInfo
import me.him188.ani.app.data.network.mapper.toPersonInfo
import me.him188.ani.client.apis.SubjectsAniApi
import me.him188.ani.client.models.AniCollectionType
import me.him188.ani.client.models.AniSubjectCollection
import me.him188.ani.client.models.AniSubjectRecommendation
import me.him188.ani.client.models.AniUpdateSubjectCollectionRequest
import me.him188.ani.datasources.bangumi.models.BangumiCount
import me.him188.ani.datasources.bangumi.next.apis.CollectionBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCollectSubject
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCollectionType
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubject
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectCharacter
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectType
import me.him188.ani.datasources.bangumi.models.BangumiSubjectCollectionType
import me.him188.ani.datasources.bangumi.models.BangumiUserSubjectCollection
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.coroutines.flows.FlowRestarter
import me.him188.ani.utils.coroutines.flows.restartable
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.logger
import org.koin.core.component.KoinComponent
import kotlin.coroutines.CoroutineContext

/**
 * Performs network requests.
 * Use [SubjectManager] instead.
 */
interface SubjectService {
    suspend fun getSubjectCollections(
        type: BangumiSubjectCollectionType?,
        offset: Int,
        limit: Int
    ): List<BangumiNextSubject>

    /**
     * 当 [subjectId] 不存在时, 返回 `null`.
     */
    suspend fun getSubjectCollection(subjectId: Int): BangumiNextSubject?

    suspend fun getSubjectRelations(
        subjectId: Int,
        withCharacterActors: Boolean,
    ): BatchSubjectRelations

    /**
     * 获取用户对这个条目的收藏状态. flow 一定会 emit 至少一个值或抛出异常. 当用户没有收藏这个条目时 emit `null`. 当没有登录时 emit `null`.
     */
    fun subjectCollectionById(subjectId: Int): Flow<BangumiNextSubject?>

    suspend fun patchSubjectCollection(subjectId: Int, payload: SubjectCollectionUpdate)

    /**
     * bangumi 没有"取消收藏"这个操作 (v0 与 p1 的 DELETE 都是 404), 调用即抛.
     * UI 上这个入口已经去掉, 见 `EditCollectionTypeDropDown` 的 `showDelete`.
     */
    suspend fun deleteSubjectCollection(subjectId: Int)

    suspend fun getSubjectRecommendations(subjectId: Int, limit: Int): List<AniSubjectRecommendation>

    /**
     * 获取各个收藏分类的数量.
     */
    fun subjectCollectionCountsFlow(): Flow<SubjectCollectionCounts>

    /**
     * 执行 Bangumi 全量同步, 从 Bangumi 同步到 ani
     */
    suspend fun performBangumiFullSync()

    suspend fun getBangumiFullSyncState(): BangumiSyncState?
}

data class BatchSubjectCollection(
    val batchSubjectDetails: BatchSubjectDetails,
    /**
     * `null` 表示未收藏
     */
    val collection: BangumiUserSubjectCollection?,
)

/**
 * 改条目收藏的载荷. **每个字段都是 null 表示"不动它"**, 不要用空值去顶.
 *
 * bangumi 的 PUT 名义上是部分更新, 但有一处例外会毁数据: **请求里带了 `type` 却没带 `rate` 时,
 * 服务端把 `rate` 当成 0 写回去, 即删掉用户的评分** (实测条目 8: PUT `{"type":2}` 后 rate 9 → 0,
 * 而短评/标签/进度都还在). 所以改收藏状态时必须把当前评分一起送过去, 见
 * `SubjectCollectionRepositoryImpl.setSubjectCollectionTypeOrDelete`.
 *
 * 同理 `tags` 传空列表 = 清空标签, 想保留就传 null.
 */
data class SubjectCollectionUpdate(
    val collectionType: UnifiedCollectionType? = null,
    val score: Int? = null,
    val comment: String? = null,
    val tags: List<String>? = null,
    val isPrivate: Boolean? = null,
)

suspend inline fun SubjectService.setSubjectCollectionTypeOrDelete(
    subjectId: Int,
    type: UnifiedCollectionType?
) {
    return if (type == null || type == UnifiedCollectionType.NOT_COLLECTED) {
        deleteSubjectCollection(subjectId)
    } else {
        patchSubjectCollection(subjectId, SubjectCollectionUpdate(collectionType = type))
    }
}

class RemoteSubjectService(
    private val subjectApi: ApiInvoker<SubjectsAniApi>,
    private val bangumiSubjectApi: ApiInvoker<SubjectBangumiNextApi>,
    private val bangumiCollectionApi: ApiInvoker<CollectionBangumiNextApi>,
    private val sessionManager: SessionStateProvider,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) : SubjectService, KoinComponent {
    private val logger = logger<RemoteSubjectService>()

    override suspend fun getSubjectCollections(
        type: BangumiSubjectCollectionType?,
        offset: Int,
        limit: Int
    ): List<BangumiNextSubject> = withContext(ioDispatcher) {
        sessionManager.checkAccessAniApiNow()
        val collections = try {
            bangumiCollectionApi {
                // 按收藏更新时间降序返回 (实测), updateRecentlyUpdatedSubjectCollections 依赖这个顺序
                getMySubjectCollections(
                    subjectType = BangumiNextSubjectType.Anime,
                    type = type?.toBangumiNextCollectionType(),
                    limit = limit,
                    offset = offset,
                ).body().data
            }
        } catch (e: ClientRequestException) {
            // invalid: 400 . Text: "{"title":"Bad Request","details":{"path":"/v0/users/him188/collections","method":"GET","query_string":"subject_type=2&type=1&limit=30&offset=35"},"request_id":".","description":"offset should be less than or equal to 34"}
            if (e.response.status == HttpStatusCode.BadRequest) {
                emptyList()
            } else {
                throw e
            }
        }
        logger.info { "bgm-direct: collections type=$type offset=$offset limit=$limit -> ${collections.size} items" }
        return@withContext collections
    }

    override suspend fun getSubjectCollection(subjectId: Int): BangumiNextSubject? {
        return subjectCollectionById(subjectId).first()
    }

    override suspend fun getSubjectRelations(
        subjectId: Int,
        withCharacterActors: Boolean
    ): BatchSubjectRelations = withContext(ioDispatcher) {
        val (characters, positions) = bangumiSubjectApi {
            // 两个请求互不依赖, 并行发 (原先串行, 实测每次白等 300~490ms)
            coroutineScope {
                val chars = async { fetchAllCharacters(subjectId) }
                // 制作人员用 positions 而不是 persons: 一页给全, 且已按职位号排好序 (原作/导演在前).
                // persons 那个是按人分组的, 条目大了要翻三四页, 而且顺序是乱的.
                val staff = async { getSubjectStaffPositions(subjectId, limit = STAFF_POSITION_LIMIT).body() }
                Pair(chars.await(), staff.await())
            }
        }

        logger.info {
            "bgm-direct: relations subject=$subjectId -> characters=${characters.size} " +
                    "staffPositions=${positions.data.size}"
        }
        BatchSubjectRelations(
            subjectId = subjectId,
            relatedCharacterInfoList = characters.mapIndexed { index, rc ->
                RelatedCharacterInfo(
                    index = index,
                    character = rc.toCharacterInfo(),
                    // p1 的 item.type 与 Ani 的 role 是同一个编号 (1 主角 / 2 配角 / 4 客串),
                    // 对照 302286 的 104 个角色分布完全一致. 注意别用 character.role, 那是"角色/机体/组织"
                    role = CharacterRole(rc.type),
                )
            },
            relatedPersonInfoList = positions.data.flatMap { group ->
                group.staffs.map { staff -> group.position.id to staff.person }
            }.mapIndexed { index, (positionId, person) ->
                RelatedPersonInfo(
                    index = index,
                    personInfo = person.toPersonInfo(),
                    position = PersonPosition(positionId),
                )
            },
        )
    }

    private suspend fun CollectionBangumiNextApi.countOf(type: BangumiNextCollectionType): Int =
        getMySubjectCollections(
            subjectType = BangumiNextSubjectType.Anime,
            type = type,
            limit = 1,
        ).body().total

    private suspend fun SubjectBangumiNextApi.fetchAllCharacters(
        subjectId: Int,
    ): List<BangumiNextSubjectCharacter> {
        val result = mutableListOf<BangumiNextSubjectCharacter>()
        var offset = 0
        while (true) {
            // limit 上限是 100, 角色多的条目 (死神 104 个) 要翻页
            val page = getSubjectCharacters(subjectId, limit = CHARACTER_PAGE_SIZE, offset = offset).body()
            result.addAll(page.data)
            offset += page.data.size
            if (page.data.isEmpty() || result.size >= page.total || offset >= MAX_CHARACTERS) break
        }
        return result
    }

    val subjectCountStatsRestarter = FlowRestarter()

    override suspend fun patchSubjectCollection(subjectId: Int, payload: SubjectCollectionUpdate) {
        sessionManager.checkAccessAniApiNow()
        withContext(ioDispatcher) {
            bangumiCollectionApi {
                updateSubjectCollection(
                    subjectId,
                    BangumiNextCollectSubject(
                        type = payload.collectionType?.toBangumiNextCollectionType(),
                        rate = payload.score,
                        comment = payload.comment,
                        `private` = payload.isPrivate,
                        tags = payload.tags,
                    ),
                )
                Unit
            }
        }
        // 写入是最危险的一条路 (带 type 不带 rate 会被服务端把评分清零), 每次都记下发了什么
        logger.info {
            "bgm-direct: WRITE subject=$subjectId type=${payload.collectionType} score=${payload.score} " +
                    "comment=${payload.comment != null} tags=${payload.tags?.size} private=${payload.isPrivate}"
        }
        subjectCountStatsRestarter.restart()
    }

    override suspend fun getSubjectRecommendations(subjectId: Int, limit: Int): List<AniSubjectRecommendation> {
        return subjectApi {
            this.getSubjectRecommendations(
                subjectId = subjectId.toLong(),
                userAgent = getAniUserAgent(),
                limit = limit,
            ).body()
        }
    }

    override suspend fun deleteSubjectCollection(subjectId: Int) {
        throw UnsupportedOperationException("bangumi 没有取消收藏的接口")
    }

    override fun subjectCollectionCountsFlow(): Flow<SubjectCollectionCounts> {
        return flow {
            // bangumi 没有"一次给全部类型计数"的端点, 只能每个类型问一次 total (limit=1).
            // 五个请求互不依赖, 并行发.
            val counts = bangumiCollectionApi {
                coroutineScope {
                    BangumiNextCollectionType.entries
                        .map { type -> type to async { countOf(type) } }
                        .associate { (type, deferred) -> type to deferred.await() }
                }
            }

            logger.info { "bgm-direct: counts $counts" }
            emit(
                SubjectCollectionCounts(
                    wish = counts[BangumiNextCollectionType.Wish] ?: 0,
                    doing = counts[BangumiNextCollectionType.Doing] ?: 0,
                    done = counts[BangumiNextCollectionType.Collect] ?: 0,
                    onHold = counts[BangumiNextCollectionType.OnHold] ?: 0,
                    dropped = counts[BangumiNextCollectionType.Dropped] ?: 0,
                    total = counts.values.sum(),
                ),
            )
        }.restartable(subjectCountStatsRestarter)
//        return sessionManager.username.filterNotNull().map { username ->
//            sessionManager.checkTokenNow()
//            val types = UnifiedCollectionType.entries - UnifiedCollectionType.NOT_COLLECTED
//            val totals = IntArray(types.size) { type ->
//                api {
//                    getUserCollectionsByUsername(
//                        username,
//                        subjectType = BangumiSubjectType.Anime,
//                        type = types[type].toSubjectCollectionType(),
//                        limit = 1, // we only need the total count. API requires at least 1
//                    ).body().total ?: 0
//                }
//            }
//            SubjectCollectionCounts(
//                wish = totals[UnifiedCollectionType.WISH.ordinal],
//                doing = totals[UnifiedCollectionType.DOING.ordinal],
//                done = totals[UnifiedCollectionType.DONE.ordinal],
//                onHold = totals[UnifiedCollectionType.ON_HOLD.ordinal],
//                dropped = totals[UnifiedCollectionType.DROPPED.ordinal],
//                total = totals.sum(),
//            )
//        }.flowOn(ioDispatcher)
    }

    override fun subjectCollectionById(subjectId: Int): Flow<BangumiNextSubject?> {
        return flow {
            emit(
                try {
                    bangumiSubjectApi {
                        this.getSubject(subjectId).body()
                    }
                } catch (e: ResponseException) {
                    if (e.response.status == HttpStatusCode.NotFound) {
                        null
                    } else {
                        throw e
                    }
                }?.also { subject ->
                    logger.info {
                        "bgm-direct: subject $subjectId -> eps=${subject.eps} collected=${subject.interest?.type} " +
                                "rate=${subject.interest?.rate} epStatus=${subject.interest?.epStatus} " +
                                "image=${subject.images != null}"
                    }
                },
            )
        }.flowOn(ioDispatcher)
    }

    override suspend fun performBangumiFullSync() {
        sessionManager.checkAccessAniApiNow()
        subjectApi.invoke {
            bangumiFullSync().body()
        }
    }

    override suspend fun getBangumiFullSyncState(): BangumiSyncState? {
        return subjectApi.invoke {
            val result = getBangumiFullSyncState()
            if (result.status == HttpStatusCode.NoContent.value) {
                return@invoke null
            }
            BangumiSyncState.fromEntity(result.body())
        }
    }

    private companion object {
        const val CHARACTER_PAGE_SIZE = 100 // p1 的 limit 上限

        /**
         * 角色多到这个数量的条目不存在, 只是防止翻页翻不完.
         */
        const val MAX_CHARACTERS = 500

        /**
         * 职位数 (不是人数), 52 个是死神那种大条目的量级.
         */
        const val STAFF_POSITION_LIMIT = 100
    }
}


private fun BangumiCount.toRatingCounts() = RatingCounts(
    _1 ?: 0,
    _2 ?: 0,
    _3 ?: 0,
    _4 ?: 0,
    _5 ?: 0,
    _6 ?: 0,
    _7 ?: 0,
    _8 ?: 0,
    _9 ?: 0,
    _10 ?: 0,
)


data class BatchSubjectDetails(
    val subjectInfo: SubjectInfo,
    val mainEpisodeCount: Int,
    val lightSubjectRelations: LightSubjectRelations,
)

data class LightSubjectRelations(
    val lightRelatedPersonInfoList: List<LightRelatedPersonInfo>,
    val lightRelatedCharacterInfoList: List<LightRelatedCharacterInfo>,
)

data class LightRelatedPersonInfo(
    val name: String,
    val position: PersonPosition,
)

data class LightRelatedCharacterInfo(
    val id: Int,
    val name: String,
    val nameCn: String,
    val role: CharacterRole,
)

data class BatchSubjectRelations(
    val subjectId: Int,
    val relatedCharacterInfoList: List<RelatedCharacterInfo>,
    val relatedPersonInfoList: List<RelatedPersonInfo>,
) {
    val allPersons
        get() = relatedCharacterInfoList.asSequence()
            .flatMap { it.character.actors } + relatedPersonInfoList.asSequence().map { it.personInfo }
}

internal fun BangumiUserSubjectCollection?.toSelfRatingInfo(): SelfRatingInfo {
    if (this == null) {
        return SelfRatingInfo.Empty
    }
    return SelfRatingInfo(
        score = rate,
        comment = comment.takeUnless { it.isNullOrBlank() },
        tags = tags,
        isPrivate = private,
    )
}

private fun UnifiedCollectionType.toBangumiNextCollectionType(): BangumiNextCollectionType? = when (this) {
    UnifiedCollectionType.WISH -> BangumiNextCollectionType.Wish
    UnifiedCollectionType.DONE -> BangumiNextCollectionType.Collect
    UnifiedCollectionType.DOING -> BangumiNextCollectionType.Doing
    UnifiedCollectionType.ON_HOLD -> BangumiNextCollectionType.OnHold
    UnifiedCollectionType.DROPPED -> BangumiNextCollectionType.Dropped
    UnifiedCollectionType.NOT_COLLECTED -> null
}

private fun BangumiSubjectCollectionType.toBangumiNextCollectionType(): BangumiNextCollectionType {
    return when (this) {
        BangumiSubjectCollectionType.Wish -> BangumiNextCollectionType.Wish
        BangumiSubjectCollectionType.Done -> BangumiNextCollectionType.Collect
        BangumiSubjectCollectionType.Doing -> BangumiNextCollectionType.Doing
        BangumiSubjectCollectionType.OnHold -> BangumiNextCollectionType.OnHold
        BangumiSubjectCollectionType.Dropped -> BangumiNextCollectionType.Dropped
    }
}

private fun BangumiSubjectCollectionType.toAniCollectionType(): AniCollectionType {
    return when (this) {
        BangumiSubjectCollectionType.Wish -> AniCollectionType.WISH
        BangumiSubjectCollectionType.Done -> AniCollectionType.DONE
        BangumiSubjectCollectionType.Doing -> AniCollectionType.DOING
        BangumiSubjectCollectionType.OnHold -> AniCollectionType.ON_HOLD
        BangumiSubjectCollectionType.Dropped -> AniCollectionType.DROPPED
    }
}

