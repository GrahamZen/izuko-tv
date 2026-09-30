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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.DeviceAniDatabase
import me.him188.ani.app.data.persistent.database.ProtoConverters
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryRecordEntity
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.math.max

/**
 * 一个本地用户 (见 [UserProfileKind.LOCAL]) 导出成的文件: 收藏 (类型、评分、短评、标签、看过的集) 与播放进度.
 * 在 Web 控制台里导出、导入, 用来换电视、重装或备份. 文件自带 [format] 与 [version]; 读的时候忽略不认识的字段,
 * 以后加字段老版本照样能读.
 */
@Serializable
data class ProfileArchive(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0,
    /** 导出时这个用户自己起的名字 (没起为空). */
    val profileName: String = "",
    val collections: List<Collection> = emptyList(),
    val playback: List<Playback> = emptyList(),
) {
    @Serializable
    data class Collection(
        val subjectId: Int,
        /** 条目名, 给人看的 (导入时条目信息从 Bangumi 重新取). */
        val name: String = "",
        /** [UnifiedCollectionType] 的名字: WISH / DOING / DONE / ON_HOLD / DROPPED. */
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

        /** 写出默认值 ([format] 与 [version] 就是默认值), 不写 null; 读时忽略不认识的字段. */
        val Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/**
 * 导出、导入 [ProfileArchive].
 *
 * 导出可以是任何一个本地用户: 当前用户用开着的库, 1 号用户用 [deviceDatabase], 其余临时打开他的库文件、读完关掉
 * (同一个文件不能再开第二个 Room 实例, 同 [UserProfileSeeder]).
 *
 * 导入只进当前用户, 而且当前用户必须是本地用户 —— 写的只是本地库, 不碰 Bangumi 账号 (要进 Bangumi 账号, 先导进一个本地用户,
 * 再用 [LocalProfileImporter]). **只增不删**: 已经收藏的条目不动 (类型、评分、看过的集都不改); 播放进度只在文件里的更新时覆盖.
 * 一次导一条 (条目信息库里没有时要从 Bangumi 取, 一条要一两秒), 调用方逐条调、显示进度; 中途停下再导一遍, 导过的会跳过.
 */
class ProfileArchiver(
    private val currentProfile: () -> UserProfile,
    /** 当前用户的库. */
    private val currentDatabase: AniDatabase,
    private val deviceDatabase: DeviceAniDatabase,
    /** 打开某个用户的库文件 (当前用户与 1 号用户除外: 它们已经开着). */
    private val openDatabase: (fileName: String) -> AniDatabase,
    /** 当前 (本地) 用户把这个条目收藏成这个类型: 库里没有这个条目时先取一次公开信息, 取不到时抛. */
    private val setLocalCollectionType: suspend (subjectId: Int, type: UnifiedCollectionType) -> Unit,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    sealed interface CollectionOutcome {
        /** 当前用户已经收藏了, 没动. */
        data object Skipped : CollectionOutcome

        /** @property episodesMarked 标成看过的集数 (文件里的集当前条目上没有的不算) */
        data class Added(val episodesMarked: Int) : CollectionOutcome
    }

    suspend fun export(profile: UserProfile): ProfileArchive = withContext(Dispatchers.IO_) {
        require(profile.isLocal) { "Only local profiles can be exported, got ${profile.id}" }
        val isCurrent = profile.id == currentProfile().id
        val database = when {
            isCurrent -> currentDatabase
            profile.isPrimary -> deviceDatabase.database
            else -> openDatabase(profile.databaseFileName)
        }
        try {
            ProfileArchive(
                exportedAt = clock(),
                profileName = profile.name,
                collections = database.readLocalCollections().map { it.toArchive() },
                playback = database.playbackHistoryDao().getActiveRecords()
                    .sortedByDescending { it.updatedAtMillis }
                    .map { it.toArchive() },
            )
        } finally {
            if (!isCurrent && !profile.isPrimary) database.close()
        }
    }

    /** [subjectIds] 里当前用户已经收藏了的. */
    suspend fun collectedAmong(subjectIds: Iterable<Int>): Set<Int> = withContext(Dispatchers.IO_) {
        checkCurrentLocal()
        val collected = currentDatabase.subjectCollection().subjectIdsByCollectionType(COLLECTED_TYPES).first().toHashSet()
        subjectIds.filterTo(HashSet()) { it in collected }
    }

    /** 把一条收藏导进当前用户. 类型不认识时抛 [IllegalArgumentException]; 条目信息取不到时照 [setLocalCollectionType] 抛. */
    suspend fun restoreCollection(item: ProfileArchive.Collection): CollectionOutcome = withContext(Dispatchers.IO_) {
        checkCurrentLocal()
        require(item.subjectId > 0) { "Invalid subject id ${item.subjectId}" }
        val type = UnifiedCollectionType.entries.find { it.name == item.type }?.takeIf { it in COLLECTED_TYPES }
            ?: throw IllegalArgumentException("Unknown collection type ${item.type}")
        val subjects = currentDatabase.subjectCollection()
        if (subjects.getById(item.subjectId)?.collectionType in COLLECTED_TYPES) return@withContext CollectionOutcome.Skipped

        setLocalCollectionType(item.subjectId, type)
        // 收藏时间用文件里的: 收藏列表按它排, 导进来的与原来先后一致
        subjects.updateType(item.subjectId, type, lastUpdated = item.updatedAt.takeIf { it > 0 } ?: clock())
        subjects.updateRating(
            item.subjectId,
            score = item.score.takeIf { it in 1..10 },
            comment = item.comment?.takeIf { it.isNotBlank() },
            // 这一列按 protobuf 存, 参数要先自己编码 (同 SubjectCollectionRepository.updateRating)
            tags = item.tags.takeIf { it.isNotEmpty() }?.let { ProtoConverters.StringList().fromList(it) },
            private = item.private.takeIf { it },
        )
        val present = subjects.episodeSelfStatesOf(item.subjectId).mapTo(HashSet()) { it.episodeId }
        val watched = item.watchedEpisodes.distinct().filter { it in present }
        val episodes = currentDatabase.episodeCollection()
        for (episodeId in watched) {
            episodes.updateSelfCollectionType(item.subjectId, episodeId, UnifiedCollectionType.DONE)
        }
        CollectionOutcome.Added(episodesMarked = watched.size)
    }

    /**
     * 把播放进度导进当前用户: 当前用户这一集没有记录, 或者记录 (含删掉它的时刻) 比文件里的旧, 才用文件里的.
     * @return 导进来的条数
     */
    suspend fun restorePlayback(items: List<ProfileArchive.Playback>): Int = withContext(Dispatchers.IO_) {
        checkCurrentLocal()
        val dao = currentDatabase.playbackHistoryDao()
        var restored = 0
        for (item in items) {
            if (item.episodeId <= 0 || item.positionMillis < 0) continue
            val existing = dao.getRecordByEpisodeId(item.episodeId)
            if (existing != null && max(existing.updatedAtMillis, existing.deletedAtMillis ?: 0) >= item.updatedAt) continue
            dao.upsertRecord(item.toEntity())
            restored++
        }
        restored
    }

    private fun checkCurrentLocal() {
        check(currentProfile().isLocal) { "Archives can only be restored into a local profile" }
    }

    private companion object {
        private val COLLECTED_TYPES = UnifiedCollectionType.entries - UnifiedCollectionType.NOT_COLLECTED
    }
}

private fun LocalProfileImporter.Entry.toArchive() = ProfileArchive.Collection(
    subjectId = subjectId,
    name = name,
    type = type.name,
    score = rating.score,
    comment = rating.comment?.takeIf { it.isNotBlank() },
    tags = rating.tags,
    private = rating.isPrivate,
    updatedAt = updatedAt,
    watchedEpisodes = watchedEpisodeIds,
)

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

private fun ProfileArchive.Playback.toEntity() = PlaybackHistoryRecordEntity(
    episodeId = episodeId,
    positionMillis = positionMillis,
    subjectId = subjectId,
    episodeSort = episodeSort,
    subjectName = subjectName,
    subjectImageUrl = subjectImageUrl,
    episodeName = episodeName,
    durationMillis = durationMillis,
    updatedAtMillis = updatedAt,
    deletedAtMillis = null,
)
