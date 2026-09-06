/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.repository.episode.toEpisodeCollectionInfo
import me.him188.ani.app.data.network.mapper.toEntity
import me.him188.ani.app.data.network.mapper.toUnifiedCollectionType
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.repository.subject.toEntity1
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.canAccessAniApiNow
import me.him188.ani.app.domain.session.canAccessBangumiApiNow
import me.him188.ani.client.apis.SubjectsAniApi
import me.him188.ani.client.models.AniBatchUpdateEpisodeCollectionsRequest
import me.him188.ani.client.models.AniEpisodeCollectionType
import me.him188.ani.client.models.AniEpisodeCollectionTypeUpdate
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.EpisodeType.*
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.paging.Paged
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.apis.DefaultApi
import me.him188.ani.datasources.bangumi.models.BangumiEpType
import me.him188.ani.datasources.bangumi.models.BangumiEpisodeCollectionType
import me.him188.ani.datasources.bangumi.models.BangumiPatchUserSubjectEpisodeCollectionRequest
import me.him188.ani.datasources.bangumi.models.BangumiEpisode
import me.him188.ani.datasources.bangumi.models.BangumiEpisodeDetail
import me.him188.ani.datasources.bangumi.models.BangumiUserEpisodeCollection
import me.him188.ani.datasources.bangumi.processing.toCollectionType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import me.him188.ani.utils.serialization.BigNum
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.CoroutineContext

/**
 * 执行网络请求查询.
 */
sealed interface EpisodeService {
    /**
     * 获取用户在这个条目下的所有剧集的收藏状态. 当用户没有收藏此条目时返回 [collectionType] 均为 [UnifiedCollectionType.NOT_COLLECTED].
     *
     * @return 分页的剧集收藏信息.
     */
    suspend fun getEpisodeCollectionInfosPaged(
        subjectId: Int,
        offset: Int? = 0,
        limit: Int? = 100,
        episodeType: BangumiEpType? = null,
    ): Paged<EpisodeCollectionInfo>

    /**
     * 获取单个剧集的信息和用户的收藏状态. 如果用户没有收藏这个剧集所属的条目, 则返回 [collectionType] 为 [UnifiedCollectionType.NOT_COLLECTED].
     *
     * 只有在 [episodeId] 找不到对应的公开剧集时返回 `null`.
     */
    suspend fun getEpisodeCollectionById(subjectId: Int, episodeId: Int): EpisodeCollectionInfo?

    /**
     * 取条目的**全部**分集 (含自己的观看状态), 直接给出可落库的实体.
     *
     * 未登录时观看状态一律是 [UnifiedCollectionType.NOT_COLLECTED], 分集本身照常返回.
     */
    suspend fun getEpisodeCollectionEntities(subjectId: Int, lastFetched: Long): List<EpisodeCollectionEntity>

    /**
     * 设置多个剧集的收藏状态.
     *
     * 当设置成功时返回 `true`. 返回 `false` 表示用户没有收藏这个条目. 其他异常将会抛出.
     */
    suspend fun setEpisodeCollection(
        subjectId: Int,
        episodeId: List<Int>,
        type: UnifiedCollectionType,
    ): Boolean
}

class EpisodeServiceImpl(
    private val subjectApi: ApiInvoker<SubjectsAniApi>,
    private val bangumiV0Api: ApiInvoker<DefaultApi>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) : EpisodeService, KoinComponent {
    private val logger = logger<EpisodeServiceImpl>()
    private val sessionManager: SessionStateProvider by inject()

    override suspend fun getEpisodeCollectionEntities(
        subjectId: Int,
        lastFetched: Long,
    ): List<EpisodeCollectionEntity> = withContext(ioDispatcher) {
        // **先问登录状态**, 别无条件去打需要登录的那个端点: 登出时每个条目都要白等一次 401 才退到
        // 公开端点, 真机日志里每次 300~480ms, 逛得越多废请求越多 (2026-09-06 实测).
        // 下面的 catch 只当兜底 —— 有 session 但 token 过期/被吊销时还是要能退回去.
        val authorized = sessionManager.canAccessBangumiApiNow()
        bangumiV0Api {
            val withSelfStatus = if (!authorized) null else try {
                // 一个请求同时给分集与自己的观看状态; 对未收藏的条目也返回全部分集 (状态 0)
                fetchAllPages { offset ->
                    getUserSubjectEpisodeCollection(subjectId, offset = offset, limit = PAGE_SIZE).body()
                        .let { page -> page.total to page.data.orEmpty() }
                }.map { it.episode.toEntity(subjectId, it.type.toUnifiedCollectionType(), lastFetched) }
            } catch (e: ClientRequestException) {
                if (e.response.status != HttpStatusCode.Unauthorized) throw e
                logger.info { "bgm-direct: episodes subject=$subjectId 有 session 但被拒, 退到公开端点" }
                null
            }
            withSelfStatus ?: run {
                // 未登录 (或 token 失效): 公开端点, 没有观看状态
                fetchAllPages { offset ->
                    getEpisodes(subjectId, offset = offset, limit = PAGE_SIZE).body()
                        .let { page -> (page.total ?: 0) to page.data.orEmpty() }
                }.map { it.toEntity(subjectId, UnifiedCollectionType.NOT_COLLECTED, lastFetched) }
            }
        }
    }

    /**
     * v0 的 limit 上限是 100, 长番要翻页. [fetch] 返回 (总数, 本页).
     */
    private suspend inline fun <T> fetchAllPages(fetch: (offset: Int) -> Pair<Int, List<T>>): List<T> {
        val result = mutableListOf<T>()
        var offset = 0
        while (true) {
            val (total, page) = fetch(offset)
            result.addAll(page)
            offset += page.size
            if (page.isEmpty() || result.size >= total || offset >= MAX_EPISODES) break
        }
        return result
    }

    override suspend fun getEpisodeCollectionInfosPaged(
        subjectId: Int,
        offset: Int?,
        limit: Int?,
        episodeType: BangumiEpType?,
    ): Paged<EpisodeCollectionInfo> {
        return withContext(ioDispatcher) {

            subjectApi.invoke {
                this.getSubject(subjectId.toLong()).body() // TODO: 2025/6/15 API 不支持 paging 
            }.let { subjectCollection ->
                Paged(
                    subjectCollection.episodes.map {
                        it.toEntity1(subjectId, lastFetched = currentTimeMillis())
                            .toEpisodeCollectionInfo()
                    },
                )
            }
//                .run {
////                    Paged.processPagedResponse(total, limit ?: 100, data)
////                }.map {
////                    it.toEpisodeCollectionInfo()
////                }
        }
    }


    override suspend fun getEpisodeCollectionById(subjectId: Int, episodeId: Int): EpisodeCollectionInfo? =
        withContext(ioDispatcher) {
            try {
                return@withContext subjectApi.invoke {
                    this.getEpisode(subjectId.toLong(), episodeId.toLong()).body().toEpisodeCollectionInfo()
                }
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.NotFound) {
                    return@withContext null
                }
                throw e
            }
        }

    override suspend fun setEpisodeCollection(
        subjectId: Int,
        episodeId: List<Int>,
        type: UnifiedCollectionType,
    ): Boolean = withContext(ioDispatcher) {
        if (!sessionManager.canAccessAniApiNow()) {
            return@withContext false
        }
        try {
            bangumiV0Api {
                // v0 的批量端点与 Ani 那个形状一致: 一次给一批 episodeId 设同一个状态
                patchUserSubjectEpisodeCollection(
                    subjectId,
                    BangumiPatchUserSubjectEpisodeCollectionRequest(
                        episodeId = episodeId,
                        type = type.toBangumiEpisodeCollectionType(),
                    ),
                ).body()
            }
            true
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) {
                return@withContext false
            }
            throw e
        }
    }

    private companion object {
        const val PAGE_SIZE = 100 // v0 的 limit 上限

        /**
         * 长番 (海贼王一千多集) 的封顶, 防止翻页翻不完.
         */
        const val MAX_EPISODES = 3000

        fun HttpStatusCode.isUnauthorized(): Boolean {
            return this == HttpStatusCode.Unauthorized || this == HttpStatusCode.Forbidden
        }

        fun HttpStatusCode.isServerError(): Boolean {
            return this.value in 500..599
        }
    }
}

/**
 * bangumi 的分集收藏状态没有"搁置"这一档; [UnifiedCollectionType.NOT_COLLECTED] 映射成 0 (未收藏),
 * 效果是把这一集的状态清掉.
 */
private fun UnifiedCollectionType.toBangumiEpisodeCollectionType(): BangumiEpisodeCollectionType = when (this) {
    UnifiedCollectionType.WISH -> BangumiEpisodeCollectionType.WATCHLIST
    UnifiedCollectionType.DONE -> BangumiEpisodeCollectionType.WATCHED
    UnifiedCollectionType.DROPPED -> BangumiEpisodeCollectionType.DISCARDED
    UnifiedCollectionType.DOING, UnifiedCollectionType.ON_HOLD,
    UnifiedCollectionType.NOT_COLLECTED -> BangumiEpisodeCollectionType.NOT_COLLECTED
}

private fun EpisodeInfo.createNotCollected(): EpisodeCollectionInfo {
    return EpisodeCollectionInfo(
        episodeInfo = this,
        collectionType = UnifiedCollectionType.NOT_COLLECTED,
    )
}

private fun BangumiUserEpisodeCollection.toEpisodeCollectionInfo() =
    EpisodeCollectionInfo(episode.toEpisodeInfo(), type.toCollectionType())

internal fun BangumiEpisode.toEpisodeInfo(): EpisodeInfo {
    return EpisodeInfo(
        episodeId = this.id,
        type = getEpisodeTypeByBangumiCode(this.type),
        name = this.name,
        nameCn = this.nameCn,
        airDate = PackedDate.parseFromDate(this.airdate),
        comment = this.comment,
//        duration = this.duration,
        desc = this.desc,
//        disc = this.disc,
        sort = EpisodeSort(this.sort, getEpisodeTypeByBangumiCode(this.type)),
        ep = EpisodeSort(this.ep ?: BigNum.ONE, getEpisodeTypeByBangumiCode(this.type)),
//        durationSeconds = this.durationSeconds
    )
}

internal fun BangumiEpisodeDetail.toEpisodeInfo(): EpisodeInfo {
    return EpisodeInfo(
        episodeId = id,
        type = this.type.toEpisodeType(),
        name = name,
        nameCn = nameCn,
        sort = EpisodeSort(this.sort, this.type.toEpisodeType()),
        airDate = PackedDate.parseFromDate(this.airdate),
        comment = comment,
//        duration = duration,
        desc = desc,
//        disc = disc,
        ep = EpisodeSort(this.ep ?: BigNum.ONE, this.type.toEpisodeType()),
    )
}


internal fun EpisodeType.toBangumiEpType(): BangumiEpType {
    return when (this) {
        MainStory -> BangumiEpType.MainStory
        SP -> BangumiEpType.SP
        OP -> BangumiEpType.OP
        ED -> BangumiEpType.ED
        PV -> BangumiEpType.PV
        MAD -> BangumiEpType.MAD
        EpisodeType.OVA -> BangumiEpType.Other
        EpisodeType.OAD -> BangumiEpType.Other
    }
}

internal fun BangumiEpType.toEpisodeType(): EpisodeType? {
    return when (this) {
        BangumiEpType.MainStory -> MainStory
        BangumiEpType.SP -> SP
        BangumiEpType.OP -> OP
        BangumiEpType.ED -> ED
        BangumiEpType.PV -> PV
        BangumiEpType.MAD -> MAD
        BangumiEpType.Other -> null
    }
}

private fun getEpisodeTypeByBangumiCode(code: Int): EpisodeType? {
    return when (code) {
        0 -> MainStory
        1 -> SP
        2 -> OP
        3 -> ED
        4 -> PV
        5 -> MAD
        else -> null
    }
}

fun UnifiedCollectionType.toAniEpisodeCollectionType(): AniEpisodeCollectionType? {
    return when (this) {
        UnifiedCollectionType.NOT_COLLECTED -> null
        UnifiedCollectionType.WISH -> null
        UnifiedCollectionType.DOING -> null
        UnifiedCollectionType.DONE -> AniEpisodeCollectionType.DONE
        UnifiedCollectionType.ON_HOLD -> null
        UnifiedCollectionType.DROPPED -> null
    }
}

fun UnifiedCollectionType.toAniEpisodeCollectionTypeUpdate(): AniEpisodeCollectionTypeUpdate {
    return when (this) {
        UnifiedCollectionType.NOT_COLLECTED -> AniEpisodeCollectionTypeUpdate.NOT_COLLECTED
        UnifiedCollectionType.WISH -> AniEpisodeCollectionTypeUpdate.NOT_COLLECTED
        UnifiedCollectionType.DOING -> AniEpisodeCollectionTypeUpdate.NOT_COLLECTED
        UnifiedCollectionType.DONE -> AniEpisodeCollectionTypeUpdate.DONE
        UnifiedCollectionType.ON_HOLD -> AniEpisodeCollectionTypeUpdate.NOT_COLLECTED
        UnifiedCollectionType.DROPPED -> AniEpisodeCollectionTypeUpdate.NOT_COLLECTED
    }
}
