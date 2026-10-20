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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 本地用户改成 Bangumi 用户时留下的本地收藏 (见 [BangumiProfileConversion]), 存在这个用户自己的一个文件里
 * ([ProfileArchive] 格式, 只有收藏; 播放进度一直在库里, 不用存).
 *
 * 不能留在库里等: 改成 Bangumi 用户之后条目缓存一过期就按账号重取, 没登录 (或账号里没有) 的条目, 自己的收藏状态会被清成未收藏.
 * 文件一直留到处理完: 登录后合并到账号或删掉, 或者没登录就改回本地用户时放回库里.
 */
class PendingLocalCollections(private val file: SystemPath) {
    private val mutex = Mutex()

    /** null = 还没读文件. */
    private val state = MutableStateFlow<List<ProfileArchive.Collection>?>(null)

    /** 等着处理的收藏, 没有时是空列表. 第一次收集时读文件. */
    val collections: Flow<List<ProfileArchive.Collection>> = flow {
        load()
        emitAll(state.filterNotNull())
    }

    suspend fun load(): List<ProfileArchive.Collection> {
        state.value?.let { return it }
        return mutex.withLock { state.value ?: read().also { state.value = it } }
    }

    /** 加进来; 文件里已经有的同一部换成新的. */
    suspend fun add(items: List<ProfileArchive.Collection>) = update { old ->
        val ids = items.mapTo(HashSet()) { it.subjectId }
        items + old.filter { it.subjectId !in ids }
    }

    /** 只留下这些条目, 其余算处理完了. */
    suspend fun keepOnly(subjectIds: Set<Int>) = update { old -> old.filter { it.subjectId in subjectIds } }

    /** 全部删掉 (删了找不回来). */
    suspend fun discard() = update { emptyList() }

    private suspend fun update(transform: (List<ProfileArchive.Collection>) -> List<ProfileArchive.Collection>) {
        mutex.withLock {
            val old = state.value ?: read()
            val new = transform(old)
            if (new != old) write(new)
            state.value = new
        }
    }

    private suspend fun read(): List<ProfileArchive.Collection> = withContext(Dispatchers.IO_) {
        if (!file.exists()) return@withContext emptyList()
        try {
            ProfileArchive.Json.decodeFromString(ProfileArchive.serializer(), file.readText()).collections
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read ${file.name}, treating as empty" }
            emptyList()
        }
    }

    private suspend fun write(items: List<ProfileArchive.Collection>) = withContext(Dispatchers.IO_) {
        if (items.isEmpty()) {
            file.delete()
            return@withContext
        }
        // 先写临时文件再挪过去: 写到一半进程被杀不会留下半截 (这时库里的已经清了, 文件就是唯一的一份)
        val temp = file.resolveSibling(file.name + ".tmp")
        temp.writeText(
            ProfileArchive.Json.encodeToString(ProfileArchive.serializer(), ProfileArchive(exportedAt = currentTimeMillis(), collections = items)),
        )
        // Windows 上刚写过的文件可能正被别的进程 (杀毒、索引) 开着, 替换会被拒一下 (同 UserProfileRegistry); 稍等再试
        var attempt = 1
        while (true) {
            try {
                temp.moveTo(file)
                break
            } catch (e: IOException) {
                if (attempt >= MOVE_ATTEMPTS) throw e
                attempt++
                delay(MOVE_RETRY_DELAY)
            }
        }
    }

    companion object {
        /** 按人的文件 (见 [UserProfile.scopedFileName]), 在应用的数据目录下. */
        const val FILE_NAME = "pending-local-collections.json"

        private const val MOVE_ATTEMPTS = 5
        private val MOVE_RETRY_DELAY = 50.milliseconds
        private val logger = logger<PendingLocalCollections>()
    }
}

/**
 * 本地用户改成 Bangumi 用户 (见 [UserProfileKind]), 以及改完之后怎么处理留下的本地收藏 ([PendingLocalCollections]).
 *
 * 改的时候不用选: 本地收藏一律存进文件留着, 库里的清掉, 然后重启进登录那一步. 登录了 Bangumi 之后再由人选:
 * 合并到账号 ([merge], 只增不删, 同 [LocalProfileImporter]) 或删掉 ([discard]) —— 那时看得到登录的是哪个账号、要加几部.
 * 一直不登录也没关系, 文件一直留着; 没登录就改回本地用户的, 下次启动放回库里 ([restoreIntoLocal]).
 */
class BangumiProfileConversion(
    private val manager: UserProfileManager,
    private val records: SelfCollectionRecords,
    private val pending: PendingLocalCollections,
    private val importer: LocalProfileImporter,
    private val currentProfile: () -> UserProfile = { UserProfiles.current },
    /** 当前 (本地) 用户库里的收藏, 见 [ProfileArchiver.export]. */
    private val exportCollections: suspend () -> List<ProfileArchive.Collection>,
    /** 把一条收藏放回当前 (本地) 用户的库, 见 [ProfileArchiver.restoreCollection]. */
    private val restoreCollection: suspend (ProfileArchive.Collection) -> Unit,
) {
    /** 当前用户可以改成 Bangumi 用户: 这台设备能重启应用, 当前是本地用户. */
    val isOffered: Boolean
        get() = manager.isSupported && currentProfile().isLocal

    /** 等着处理的本地收藏, 见 [PendingLocalCollections.collections]. */
    val pendingCollections: Flow<List<ProfileArchive.Collection>> get() = pending.collections

    /** 改之前给人看: 有多少收藏会留着. */
    suspend fun records(): SelfCollectionRecords.Counts = records.counts()

    /** 改成 Bangumi 用户, 然后重启应用 (进来先弹登录). 本地收藏先存进文件, 存好了才清库. */
    suspend fun convert() {
        check(isOffered) { "User profile ${currentProfile().id} cannot be converted to Bangumi" }
        val collections = exportCollections()
        if (collections.isNotEmpty()) pending.add(collections)
        records.clear()
        logger.info { "Converting user profile ${currentProfile().id} to Bangumi, ${collections.size} local collections kept" }
        manager.convertCurrentToBangumi()
    }

    /**
     * 合并前的预览: 留下的收藏里哪些会加进当前登录的账号, 哪些账号里已经有了. 没有留下的收藏时为 null.
     * 要把账号的收藏翻一遍, 慢一点; 调用方确认当前登录着.
     */
    suspend fun previewMerge(): LocalProfileImporter.Preview? {
        val profile = currentProfile()
        check(!profile.isLocal) { "Local profiles cannot merge into Bangumi" }
        val items = pending.load()
        if (items.isEmpty()) return null
        return importer.preview(profile, items.mapNotNull { it.toImporterEntry() })
    }

    /**
     * 照 [preview] 合并到账号. 收藏没加上的留在文件里, 下次再问; 其余 (加上了的、账号里本来就有的) 算处理完了.
     * 文件已经处理掉 (在别处选了删除或合并完了) 时抛 [IllegalStateException].
     */
    suspend fun merge(preview: LocalProfileImporter.Preview, onProgress: (LocalProfileImporter.Progress) -> Unit = {}): LocalProfileImporter.Result {
        check(!currentProfile().isLocal) { "Local profiles cannot merge into Bangumi" }
        check(pending.load().isNotEmpty()) { "Pending local collections were already handled" }
        val result = importer.import(preview, onProgress)
        val failed = result.failures.filterNot { it.collectionAdded }.mapTo(HashSet()) { it.entry.subjectId }
        pending.keepOnly(failed)
        return result
    }

    /** 留下的收藏不要了. */
    suspend fun discard() {
        pending.discard()
        logger.info { "Discarded pending local collections of user profile ${currentProfile().id}" }
    }

    /**
     * 当前是本地用户而文件还在 (改成 Bangumi 用户后没登录又改回来了): 放回库里. 放不回去的 (取不到条目信息) 留着下次再放.
     * @return 放回去的条数
     */
    suspend fun restoreIntoLocal(): Int {
        if (!currentProfile().isLocal) return 0
        val items = pending.load()
        if (items.isEmpty()) return 0
        val failed = HashSet<Int>()
        var restored = 0
        for (item in items) {
            try {
                restoreCollection(item)
                restored++
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // 文件里的数据不对 (类型不认识), 再放也放不回去
                logger.warn(e) { "Dropping pending local collection ${item.subjectId}" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to restore pending local collection ${item.subjectId}, will retry" }
                failed += item.subjectId
            }
        }
        pending.keepOnly(failed)
        logger.info { "Restored $restored pending local collections into user profile ${currentProfile().id}, ${failed.size} left" }
        return restored
    }

    private companion object {
        private val logger = logger<BangumiProfileConversion>()
    }
}

/** 文件里的一条收藏换成导入用的; 类型不认识的为 null. */
internal fun ProfileArchive.Collection.toImporterEntry(): LocalProfileImporter.Entry? {
    val type = UnifiedCollectionType.entries.find { it.name == type }?.takeIf { it != UnifiedCollectionType.NOT_COLLECTED } ?: return null
    return LocalProfileImporter.Entry(
        subjectId = subjectId,
        name = name,
        type = type,
        rating = SelfRatingInfo(score = score.coerceIn(0, 10), comment = comment, tags = tags, isPrivate = private),
        watchedEpisodeIds = watchedEpisodes,
        updatedAt = updatedAt,
    )
}
