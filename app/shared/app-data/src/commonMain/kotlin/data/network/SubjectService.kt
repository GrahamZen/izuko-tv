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
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubject
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSubjectCharacter
import me.him188.ani.datasources.bangumi.models.BangumiSubjectCollectionType
import me.him188.ani.datasources.bangumi.models.BangumiUserSubjectCollection
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
    ): List<AniSubjectCollection>

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

    suspend fun patchSubjectCollection(subjectId: Int, payload: AniUpdateSubjectCollectionRequest)
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

suspend inline fun SubjectService.setSubjectCollectionTypeOrDelete(
    subjectId: Int,
    type: AniCollectionType?
) {
    return if (type == null) {
        deleteSubjectCollection(subjectId)
    } else {
        patchSubjectCollection(subjectId, AniUpdateSubjectCollectionRequest(collectionType = type))
    }
}

class RemoteSubjectService(
    private val subjectApi: ApiInvoker<SubjectsAniApi>,
    private val bangumiSubjectApi: ApiInvoker<SubjectBangumiNextApi>,
    private val sessionManager: SessionStateProvider,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) : SubjectService, KoinComponent {
    private val logger = logger<RemoteSubjectService>()

    override suspend fun getSubjectCollections(
        type: BangumiSubjectCollectionType?,
        offset: Int,
        limit: Int
    ): List<AniSubjectCollection> = withContext(ioDispatcher) {
        sessionManager.checkAccessAniApiNow()
        val collections = try {
            subjectApi {
                getSubjectCollections(
                    type = type?.toAniCollectionType(),
                    limit = limit,
                    offset = offset,
                ).body().items
            }
        } catch (e: ClientRequestException) {
            // invalid: 400 . Text: "{"title":"Bad Request","details":{"path":"/v0/users/him188/collections","method":"GET","query_string":"subject_type=2&type=1&limit=30&offset=35"},"request_id":".","description":"offset should be less than or equal to 34"}
            if (e.response.status == HttpStatusCode.BadRequest) {
                emptyList()
            } else {
                throw e
            }
        }
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

    override suspend fun patchSubjectCollection(subjectId: Int, payload: AniUpdateSubjectCollectionRequest) {
        sessionManager.checkAccessAniApiNow()
        withContext(ioDispatcher) {
            subjectApi {
                this.updateSubjectCollection(
                    subjectId.toLong(),
                    payload,
                )
                Unit
            }
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
        sessionManager.checkAccessAniApiNow()
        subjectApi {
            this.deleteSubjectCollection(subjectId.toLong()).body()
        }
        subjectCountStatsRestarter.restart()
    }

    override fun subjectCollectionCountsFlow(): Flow<SubjectCollectionCounts> {
        return flow {
            val stats = subjectApi {
                this.getSubjectCollectionStats().body()
            }

            emit(
                SubjectCollectionCounts(
                    wish = stats.wish,
                    doing = stats.doing,
                    done = stats.done,
                    onHold = stats.onHold,
                    dropped = stats.dropped,
                    total = stats.wish + stats.doing + stats.done + stats.onHold + stats.dropped,
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

private fun BangumiSubjectCollectionType.toAniCollectionType(): AniCollectionType {
    return when (this) {
        BangumiSubjectCollectionType.Wish -> AniCollectionType.WISH
        BangumiSubjectCollectionType.Done -> AniCollectionType.DONE
        BangumiSubjectCollectionType.Doing -> AniCollectionType.DOING
        BangumiSubjectCollectionType.OnHold -> AniCollectionType.ON_HOLD
        BangumiSubjectCollectionType.Dropped -> AniCollectionType.DROPPED
    }
}

