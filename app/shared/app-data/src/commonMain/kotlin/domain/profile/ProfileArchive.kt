/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryRecordEntity
import me.him188.ani.app.data.repository.subject.toUnifiedCollectionType
import me.him188.ani.client.models.AniEpisodeCollectionType
import me.him188.ani.client.models.AniSubjectCollection
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Instant

/**
 * Izuko TV 本地用户的导出文件 (格式 `izuko-tv-profile` v1): 收藏 (类型、评分、短评、标签、看过的集) 与播放进度.
 * 字段与 Izuko TV 里的同名类逐个一致, 这边只写不读: 导出的文件在 Izuko TV 的 Web 控制台里导进本地用户.
 */
@Serializable
data class ProfileArchive(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0,
    /** 导出的是谁 (Izuko TV 导入时给人看). */
    val profileName: String = "",
    val collections: List<Collection> = emptyList(),
    val playback: List<Playback> = emptyList(),
) {
    @Serializable
    data class Collection(
        val subjectId: Int,
        /** 条目名, 给人看的 (导入时条目信息从 Bangumi 重新取). */
        val name: String = "",
        /** UnifiedCollectionType 的名字: WISH / DOING / DONE / ON_HOLD / DROPPED. */
        val type: String,
        /** 评分 1~10, 0 = 没评. */
        val score: Int = 0,
        val comment: String? = null,
        val tags: List<String> = emptyList(),
        val private: Boolean = false,
        /** 收藏更新时间 (毫秒), 收藏列表按它排. */
        val updatedAt: Long = 0,
        /** 标了看过的集. */
        val watchedEpisodes: List<Int> = emptyList(),
    )

    /** 一集的播放进度, 字段同 [PlaybackHistoryRecordEntity]. */
    @Serializable
    data class Playback(
        val episodeId: Int,
        val positionMillis: Long,
        val durationMillis: Long? = null,
        val subjectId: Int? = null,
        val subjectName: String? = null,
        val subjectImageUrl: String? = null,
        val episodeSort: Float? = null,
        val episodeName: String? = null,
        val updatedAt: Long = 0,
    )

    companion object {
        const val FORMAT = "izuko-tv-profile"
        const val VERSION = 1

        /** 写出默认值 ([format] 与 [version] 就是默认值), 不写 null. */
        val Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/**
 * 把当前登录的 Animeko 账号导出成 [ProfileArchive], 迁移到 Izuko TV 时用: Izuko TV 直接连 Bangumi、不连 Animeko 服务器,
 * 没连接 Bangumi 的账号收藏只存在 Animeko 服务器上, 迁移过去就看不到了.
 *
 * 收藏从服务器整份翻页取 (列表接口带着每部的分集与看过状态, 不用逐部再查), 播放进度取本机的库.
 * 条目与分集的编号就是 Bangumi 的, Izuko TV 原样认.
 *
 * @param fetchCollections 第 offset 起的 limit 部收藏 (所有类型, 按收藏更新时间从新到旧); 翻过头返回空
 */
class AccountArchiveExporter(
    private val fetchCollections: suspend (offset: Int, limit: Int) -> List<AniSubjectCollection>,
    private val database: AniDatabase,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    /** @param onProgress 每取完一页报一次取到了几部 */
    suspend fun export(profileName: String, onProgress: (fetched: Int) -> Unit = {}): ProfileArchive {
        val collections = LinkedHashMap<Int, ProfileArchive.Collection>()
        var offset = 0
        while (true) {
            val page = fetchCollections(offset, PAGE_SIZE)
            val before = collections.size
            for (item in page) {
                val archived = item.toArchive() ?: continue
                collections.putIfAbsent(archived.subjectId, archived)
            }
            onProgress(collections.size)
            // 不满一页 = 最后一页; 整页都是取过的 (服务器没按 offset 翻) 也停, 免得一直取同一页
            if (page.size < PAGE_SIZE || collections.size == before) break
            offset += page.size
        }
        val playback = withContext(Dispatchers.IO_) { database.playbackHistoryDao().getActiveRecords() }
            .sortedByDescending { it.updatedAtMillis }
            .map { it.toArchive() }
        return ProfileArchive(
            exportedAt = clock(),
            profileName = profileName,
            collections = collections.values.toList(),
            playback = playback,
        )
    }

    companion object {
        /** 一页带着这些条目的全部分集, 取太多一页响应慢 (收藏页一次取 30~120). */
        const val PAGE_SIZE = 50
    }
}

/** 没收藏的 (服务器理应不返回) 跳过. */
private fun AniSubjectCollection.toArchive(): ProfileArchive.Collection? {
    val type = collectionType ?: return null
    return ProfileArchive.Collection(
        subjectId = id.toInt(),
        name = nameCn.ifBlank { name },
        type = type.toUnifiedCollectionType().name,
        score = selfRating.score.takeIf { it in 1..10 } ?: 0,
        comment = selfRating.comment?.takeIf { it.isNotBlank() },
        tags = selfRating.tags,
        private = selfRating.isPrivate,
        updatedAt = updatedAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0,
        watchedEpisodes = episodes.filter { it.collectionType == AniEpisodeCollectionType.DONE }.map { it.episodeId.toInt() },
    )
}

private fun PlaybackHistoryRecordEntity.toArchive() = ProfileArchive.Playback(
    episodeId = episodeId,
    positionMillis = positionMillis,
    durationMillis = durationMillis,
    subjectId = subjectId,
    subjectName = subjectName,
    subjectImageUrl = subjectImageUrl,
    episodeSort = episodeSort,
    episodeName = episodeName,
    updatedAt = updatedAtMillis,
)
