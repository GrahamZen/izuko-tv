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
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.network.SubjectCollectionUpdate
import me.him188.ani.app.data.network.SubjectService
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.DeviceAniDatabase
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException

/**
 * 把一个本地用户 (见 [UserProfileKind.LOCAL]) 的收藏加到当前登录的 Bangumi 账号上.
 *
 * **只增不删**: Bangumi 上已经收藏了的条目一概不动 (类型、评分、进度都不改); 还没收藏的按本地的收藏类型、评分与短评加上,
 * 再把本地看过的集标成看过 (想看的不标). Bangumi 没有取消收藏的接口, 加上去的只能去 Bangumi 网页上改, 每一条还会出现在
 * 这个账号的公开时间线上 —— 所以先 [preview] 给人看要加哪些, 确认了才 [import].
 *
 * 本地用户的进程这时没在跑 (一个进程只属于一个用户), 直接打开他的库文件读, 读完关掉 (同 [UserProfileSeeder]).
 */
class LocalProfileImporter(
    private val deviceDatabase: DeviceAniDatabase,
    /** 打开某个用户的库文件 (1 号用户除外: 它就是 [deviceDatabase], 已经开着). */
    private val openDatabase: (fileName: String) -> AniDatabase,
    /** 当前账号在 Bangumi 上已经收藏了的条目 (全部类型), 预览时用来分出要加的. */
    private val fetchBangumiCollectedIds: suspend () -> Set<Int>,
    /** 当前账号在 Bangumi 上有没有收藏这个条目 (任何类型); 查不到条目时抛. */
    private val isCollectedOnBangumi: suspend (subjectId: Int) -> Boolean,
    /** 新建一条 Bangumi 条目收藏. */
    private val addBangumiCollection: suspend (subjectId: Int, update: SubjectCollectionUpdate) -> Unit,
    /** 把这些集在 Bangumi 上标成看过 (条目要先有收藏). */
    private val markBangumiEpisodesWatched: suspend (subjectId: Int, episodeIds: List<Int>) -> Unit,
    /** 加了东西之后调: 让当前用户的收藏缓存过期, 收藏页与继续观看从 Bangumi 重取. */
    private val afterImport: suspend () -> Unit = {},
) {
    /** 本地用户的一条收藏. */
    data class Entry(
        val subjectId: Int,
        val name: String,
        val type: UnifiedCollectionType,
        val rating: SelfRatingInfo,
        /** 本地标了看过的集. */
        val watchedEpisodeIds: List<Int>,
        /** 本地的收藏更新时间 (毫秒). */
        val updatedAt: Long = 0,
    )

    /**
     * @property toAdd Bangumi 上还没收藏的, 导入时加上 (按本地最近动过的在前)
     * @property alreadyCollected Bangumi 上已经收藏了的, 不动
     */
    data class Preview(
        val source: UserProfile,
        val toAdd: List<Entry>,
        val alreadyCollected: List<Entry>,
    )

    /**
     * @property collectionAdded 条目收藏加上了, 只是看过的集没标上
     */
    data class Failure(val entry: Entry, val error: Throwable, val collectionAdded: Boolean)

    /**
     * @property added 加上的条目收藏数
     * @property skipped 没加的 (Bangumi 上已经有了, 含预览之后才加上的)
     * @property episodesMarked 标成看过的集数
     */
    data class Result(
        val added: Int,
        val skipped: Int,
        val episodesMarked: Int,
        val failures: List<Failure>,
    )

    suspend fun preview(source: UserProfile): Preview {
        require(source.isLocal) { "Only local profiles can be imported, got ${source.id}" }
        val entries = readEntries(source)
        val collected = fetchBangumiCollectedIds()
        val (already, toAdd) = entries.partition { it.subjectId in collected }
        logger.info { "Import preview of user profile ${source.id}: ${toAdd.size} to add, ${already.size} already collected" }
        return Preview(source, toAdd = toAdd, alreadyCollected = already)
    }

    /**
     * 按 [preview] 导入. 每一条写之前单独核对一次 Bangumi 上有没有收藏, 已经有的跳过: 预览之后可能在别处加过,
     * 预览时翻页拿到的集合也可能漏 (翻页期间别处改过收藏, 顺序会错位). 已有的收藏一旦被这里写到,
     * 类型会被改掉, 没带评分的还会被清掉评分 (见 [SubjectService.patchSubjectCollection]).
     * 一条失败不影响其余; 核对失败的不写.
     *
     * 本地最早收藏的先加: Bangumi 的收藏列表按收藏时间排, 这样导完的先后与本地一致.
     */
    suspend fun import(preview: Preview, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result {
        val targets = preview.toAdd.asReversed()
        var added = 0
        var skipped = preview.alreadyCollected.size
        var episodesMarked = 0
        val failures = mutableListOf<Failure>()
        targets.forEachIndexed { index, entry ->
            onProgress(index, targets.size)
            try {
                if (isCollectedOnBangumi(entry.subjectId)) {
                    skipped++
                    return@forEachIndexed
                }
                addBangumiCollection(entry.subjectId, entry.toCollectionUpdate())
                added++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to import subject ${entry.subjectId} to Bangumi" }
                failures += Failure(entry, e, collectionAdded = false)
                return@forEachIndexed
            }
            // 想看的本来就不该有看过的集 (标了 Bangumi 那边可能跟着改成在看), 只标其余类型的
            if (entry.type != UnifiedCollectionType.WISH && entry.watchedEpisodeIds.isNotEmpty()) {
                try {
                    markBangumiEpisodesWatched(entry.subjectId, entry.watchedEpisodeIds)
                    episodesMarked += entry.watchedEpisodeIds.size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to mark watched episodes of subject ${entry.subjectId} on Bangumi" }
                    failures += Failure(entry, e, collectionAdded = true)
                }
            }
        }
        onProgress(targets.size, targets.size)
        if (added > 0) afterImport()
        logger.info {
            "Imported user profile ${preview.source.id} to Bangumi: added $added, skipped $skipped, " +
                    "episodes $episodesMarked, failed ${failures.size}"
        }
        return Result(added = added, skipped = skipped, episodesMarked = episodesMarked, failures = failures)
    }

    private suspend fun readEntries(source: UserProfile): List<Entry> = withContext(Dispatchers.IO_) {
        val database = if (source.isPrimary) deviceDatabase.database else openDatabase(source.databaseFileName)
        try {
            database.readLocalCollections()
        } finally {
            if (!source.isPrimary) database.close()
        }
    }

    private companion object {
        private val logger = logger<LocalProfileImporter>()
    }
}

/** 这个库里收藏了的条目 (浏览过而没收藏的不算), 最近动过的在前; 连同标了看过的集. */
internal suspend fun AniDatabase.readLocalCollections(): List<LocalProfileImporter.Entry> {
    val episodes = episodeCollection()
    return subjectCollection()
        .filterMostRecentUpdated(LOCAL_COLLECTED_TYPES, limit = Int.MAX_VALUE)
        .first()
        .map { subject ->
            LocalProfileImporter.Entry(
                subjectId = subject.subjectId,
                name = subject.nameCn.ifEmpty { subject.name },
                type = subject.collectionType,
                rating = subject.selfRatingInfo,
                watchedEpisodeIds = episodes.filterBySubjectId(subject.subjectId).first()
                    .filter { it.selfCollectionType == UnifiedCollectionType.DONE }
                    .map { it.episodeId },
                updatedAt = subject.lastUpdated,
            )
        }
}

private val LOCAL_COLLECTED_TYPES = listOf(
    UnifiedCollectionType.WISH,
    UnifiedCollectionType.DOING,
    UnifiedCollectionType.DONE,
    UnifiedCollectionType.ON_HOLD,
    UnifiedCollectionType.DROPPED,
)

/**
 * 新建收藏的载荷: 类型、评分、短评、标签与私密一起写. 新建的收藏上本来没有评分, 带上评分不会误伤;
 * 没评过分的不带 (评分 0 = 删评分), 空的短评与标签也不带.
 */
internal fun LocalProfileImporter.Entry.toCollectionUpdate(): SubjectCollectionUpdate = SubjectCollectionUpdate(
    collectionType = type,
    score = rating.score.takeIf { it > 0 },
    comment = rating.comment?.takeIf { it.isNotBlank() },
    tags = rating.tags.takeIf { it.isNotEmpty() },
    isPrivate = rating.isPrivate.takeIf { it },
)

/** 当前账号在 Bangumi 上收藏了的全部条目 id, 按服务端报的总数翻到底. */
suspend fun SubjectService.fetchAllCollectedSubjectIds(pageSize: Int = 100): Set<Int> {
    val ids = HashSet<Int>()
    var offset = 0
    while (true) {
        val page = getSubjectCollectionsPage(type = null, offset = offset, limit = pageSize)
        page.items.mapTo(ids) { it.id }
        offset += pageSize
        if (page.items.isEmpty() || offset >= page.total) return ids
    }
}
