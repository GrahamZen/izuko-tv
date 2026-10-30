/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
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

/** 开始重温的结果, 见 [SubjectRewatch.start]. */
enum class RewatchStartResult {
    STARTED,

    /** 本地不是看过 (只有看过的番能重温, 已经在重温的也是这个). */
    NOT_DONE,

    /**
     * 这一部还有看过记录没推到 Bangumi. 待推的操作在每次分集落库时都会叠回库里 (`EpisodeCollectionDao.reapplyPendingCollectionTypes`),
     * 重温清掉的看过会被它盖回来, 等推完再开始.
     */
    PENDING_SYNC,
}

/**
 * 重温: 看过的番从头再看一遍, 这一轮的进度只记在本机, Bangumi 上的记录不动.
 *
 * 重温中这一部按本地档处理 (见 `UserProfileKind.LOCAL`): 库里这一行改成在看、各集看过清空, 于是所有读库的地方 (详情页、追番页分组、
 * 继续观看、主屏卡片、控制台) 都照「在看、从第一集开始」显示, 不用各自判断. 要守住的是写:
 * - 重温中标的看过只写本机, 不进同步队列 (见 `EpisodeCollectionRepository`);
 * - Bangumi 回来的数据照常更新条目信息, 收藏类型与看过保留本地的 ([keepsSelfState] / [keepSelfStates]);
 * - 和服务端的收藏列表对账时它按 Bangumi 上的看过算 ([idsAsOnBangumi]), 不会被当成「在别处改了」去纠正.
 *
 * 开始时把 Bangumi 那份 (收藏更新时间 + 各集看过) 存成快照, 结束时照它还原. 改收藏状态就结束 (`SubjectCollectionRepository.setSubjectCollectionTypeOrDelete`);
 * Bangumi 上这部被改成了别的收藏类型 (在别处改的) 也结束, 照 Bangumi 的写; 换了登录账号全部作废. 本机的播放记录不动.
 *
 * 没有在重温的条目, 这里的每个钩子都原样放行.
 *
 * 记录存在按人的一个文件里 ([FILE_NAME]), 不进数据库: 开始时先写文件再改库, 结束时先还原库再删记录, 中途被杀留下的记录
 * 对应的那一行已经不是在看, 下次读文件时作废 ([loaded]).
 */
class SubjectRewatch(
    private val database: AniDatabase,
    private val file: SystemPath,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val mutex = Mutex()

    /** null = 还没读文件. */
    private val entries = MutableStateFlow<Map<Int, SubjectRewatchEntry>?>(null)

    /** 这一部是不是正在重温: 有记录且库里这一行是在看. */
    fun rewatchingFlow(subjectId: Int): Flow<Boolean> = flow {
        loaded()
        emitAll(entries.filterNotNull())
    }.combine(database.subjectCollection().findById(subjectId)) { map, row ->
        subjectId in map && row?.collectionType == UnifiedCollectionType.DOING
    }.distinctUntilChanged()

    /** 正在重温的条目 (一页卡片的长按菜单用, 不逐条查库). */
    val rewatchingIdsFlow: Flow<Set<Int>> = flow {
        loaded()
        emitAll(entries.filterNotNull().map { it.keys })
    }.distinctUntilChanged()

    suspend fun isActive(subjectId: Int): Boolean = subjectId in loaded()

    /**
     * 开始重温. 只有本地是看过的能开始; 这一部还有没推到 Bangumi 的看过时不开始 (见 [RewatchStartResult.PENDING_SYNC]).
     *
     * @throws IOException 记录写不进文件 (这时库没动)
     */
    suspend fun start(subjectId: Int): RewatchStartResult {
        val subjects = database.subjectCollection()
        val subject = subjects.getById(subjectId)
        if (subject?.collectionType != UnifiedCollectionType.DONE) return RewatchStartResult.NOT_DONE
        if (database.episodeCollectionPendingOpDao().getPendingOps().any { it.subjectId == subjectId }) {
            return RewatchStartResult.PENDING_SYNC
        }
        val watched = subjects.episodeSelfStatesOf(subjectId)
            .filter { it.selfCollectionType != UnifiedCollectionType.NOT_COLLECTED }
            .associate { it.episodeId to it.selfCollectionType }
        val now = clock()
        update { it + (subjectId to SubjectRewatchEntry(subjectId, startedAt = now, lastUpdated = subject.lastUpdated, episodes = watched)) }
        if (!database.subjectRewatch().beginRewatch(subjectId, now)) {
            // 读完之后收藏类型被改了
            remove(subjectId)
            return RewatchStartResult.NOT_DONE
        }
        logger.info { "Rewatch started: subject $subjectId (${watched.size} watched episodes kept for restore)" }
        return RewatchStartResult.STARTED
    }

    /**
     * 结束重温: 库里还原成开始时的看过与各集看过. 没在重温就什么都不做.
     *
     * @return 这一部之前是不是在重温
     */
    suspend fun end(subjectId: Int): Boolean {
        val entry = loaded()[subjectId] ?: return false
        database.subjectRewatch().endRewatch(subjectId, entry.lastUpdated, entry.episodes)
        remove(subjectId)
        logger.info { "Rewatch ended: subject $subjectId" }
        return true
    }

    /**
     * 结束重温并直接改成 [type] (见 [SubjectRewatchDao.endRewatchInto]): 改收藏状态时用, 本地一步到位, 不经过看过.
     *
     * @return 开始重温时 Bangumi 那份 (看过 + 收藏更新时间), 调用方写 Bangumi 失败时退回它; 没在重温 (或本地已经不是在看, 这时只删记录) 为 null,
     * 调用方照常改
     */
    suspend fun endInto(subjectId: Int, type: UnifiedCollectionType): Pair<UnifiedCollectionType, Long>? {
        val entry = loaded()[subjectId] ?: return null
        val ended = database.subjectRewatch().endRewatchInto(subjectId, type, clock(), entry.episodes)
        remove(subjectId)
        logger.info { "Rewatch ended into $type: subject $subjectId" }
        return if (ended) UnifiedCollectionType.DONE to entry.lastUpdated else null
    }

    /** 换了登录账号: 记录全部作废, 库里那几行等新账号的数据重取时盖掉 (新登录会把取数时间清零, 见 `SessionManager`). */
    suspend fun dropAll() {
        if (loaded().isEmpty()) return
        logger.info { "New login, dropping all rewatches" }
        removeAll { true }
    }

    /**
     * 单个条目从 Bangumi 取回来、落库之前: 重温中且取到的还是看过 (或没有收藏信息: 没登录时是匿名结果, 分不出是不是在别处取消了)
     * 时返回 true, 调用方保留本地的收藏类型与看过. 在别处改成了别的收藏类型就结束重温, 返回 false: 收藏类型由调用方照取到的写,
     * 各集看过先还原成开始时的再标成过期 (这次没连分集一起取的话, 用到时按 Bangumi 重取), 不停在这一轮的进度上.
     */
    suspend fun keepsSelfState(subjectId: Int, fetchedType: UnifiedCollectionType): Boolean {
        val entry = loaded()[subjectId] ?: return false
        if (fetchedType == UnifiedCollectionType.DONE || fetchedType == UnifiedCollectionType.NOT_COLLECTED) return true
        logger.info { "Subject $subjectId is $fetchedType on Bangumi now, rewatch dropped" }
        database.subjectRewatch().dropRewatch(subjectId, entry.episodes)
        remove(subjectId)
        return false
    }

    /**
     * 收藏列表一批落库之前: 重温中的那几部换回本地的收藏类型与收藏更新时间 (规则同 [keepsSelfState]), 其余原样.
     *
     * @param local 这一批在库里的行
     */
    suspend fun keepSelfStates(
        fetched: List<SubjectCollectionEntity>,
        local: Map<Int, SubjectCollectionEntity>,
    ): List<SubjectCollectionEntity> {
        if (loaded().isEmpty()) return fetched
        return fetched.map { subject ->
            val row = local[subject.subjectId]
            if (row != null && keepsSelfState(subject.subjectId, subject.collectionType)) {
                subject.copy(collectionType = row.collectionType, lastUpdated = row.lastUpdated)
            } else {
                subject
            }
        }
    }

    /**
     * 和服务端的收藏列表对账时用的本地 id: 重温中的番本地是在看、Bangumi 上是看过, 按 Bangumi 的算 —— 不算进在看, 算进看过.
     *
     * @param types 这次对账的收藏类型
     * @param localIds 本地标着 [types] 的条目
     */
    suspend fun idsAsOnBangumi(types: Collection<UnifiedCollectionType>, localIds: Collection<Int>): Collection<Int> {
        val active = loaded().keys
        if (active.isEmpty()) return localIds
        val others = localIds.filter { it !in active }
        return if (UnifiedCollectionType.DONE in types) others + active else others
    }

    private suspend fun loaded(): Map<Int, SubjectRewatchEntry> {
        entries.value?.let { return it }
        return mutex.withLock {
            entries.value ?: run {
                val read = read()
                // 中途被杀留下的记录: 那一行已经不是在看, 库里的就是现在的
                val subjects = database.subjectCollection()
                val valid = read.filterValues { subjects.getById(it.subjectId)?.collectionType == UnifiedCollectionType.DOING }
                if (valid.size != read.size) {
                    logger.info { "Dropping stale rewatches: ${read.keys - valid.keys}" }
                    writeQuietly(valid)
                }
                valid
            }.also { entries.value = it }
        }
    }

    private suspend fun update(transform: (Map<Int, SubjectRewatchEntry>) -> Map<Int, SubjectRewatchEntry>) {
        loaded()
        mutex.withLock {
            val new = transform(entries.value.orEmpty())
            write(new)
            entries.value = new
        }
    }

    private suspend fun remove(subjectId: Int) = removeAll { it == subjectId }

    /** 删记录时库已经改好了, 文件写不进去也照样当删了 (下次读文件时那一行已经不是在看, 会作废). */
    private suspend fun removeAll(predicate: (Int) -> Boolean) {
        loaded()
        mutex.withLock {
            val new = entries.value.orEmpty().filterKeys { !predicate(it) }
            writeQuietly(new)
            entries.value = new
        }
    }

    private suspend fun read(): Map<Int, SubjectRewatchEntry> = readSubjectRewatches(file)

    private suspend fun writeQuietly(entries: Map<Int, SubjectRewatchEntry>) {
        try {
            write(entries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to write ${file.name}" }
        }
    }

    private suspend fun write(entries: Map<Int, SubjectRewatchEntry>) = withContext(Dispatchers.IO_) {
        if (entries.isEmpty()) {
            if (file.exists()) file.delete()
            return@withContext
        }
        // 先写临时文件再挪过去: 写到一半进程被杀不会留下半截
        val temp = file.resolveSibling(file.name + ".tmp")
        temp.writeText(json.encodeToString(SubjectRewatchSave.serializer(), SubjectRewatchSave(entries.values.toList())))
        // Windows 上刚写过的文件可能正被别的进程 (杀毒、索引) 开着, 替换会被拒一下 (同 PendingLocalCollections); 稍等再试
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
        /** 按人的文件 (见 `UserProfile.scopedFileName`), 在应用的数据目录下. */
        const val FILE_NAME = "subject-rewatch.json"

        private const val MOVE_ATTEMPTS = 5
        private val MOVE_RETRY_DELAY = 50.milliseconds
        internal val json = Json { ignoreUnknownKeys = true }
        private val logger = logger<SubjectRewatch>()
    }
}

/**
 * 读 [file] 里记着的重温, 不经 [SubjectRewatch]: 导出收藏、换电视时读某个用户的 (不一定是当前用户). 读不出来当没有.
 * 用的时候只认库里那一行还是在看的 (见 [SubjectRewatch] 的「中途被杀」).
 */
internal suspend fun readSubjectRewatches(file: SystemPath): Map<Int, SubjectRewatchEntry> = withContext(Dispatchers.IO_) {
    if (!file.exists()) return@withContext emptyMap()
    try {
        SubjectRewatch.json.decodeFromString(SubjectRewatchSave.serializer(), file.readText()).entries.associateBy { it.subjectId }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger<SubjectRewatch>().warn(e) { "Failed to read ${file.name}, treating as empty" }
        emptyMap()
    }
}

/**
 * 一部正在重温的番.
 *
 * @param lastUpdated 开始时的收藏更新时间 (收藏列表按它排), 结束时还原
 * @param episodes 开始时标过的集 (没标的不记), 结束时照它还原
 */
@Serializable
internal data class SubjectRewatchEntry(
    val subjectId: Int,
    val startedAt: Long,
    val lastUpdated: Long,
    val episodes: Map<Int, UnifiedCollectionType>,
) {
    /** 开始时标了看过的集. */
    val watchedEpisodeIds: List<Int> get() = episodes.filterValues { it == UnifiedCollectionType.DONE }.keys.toList()
}

@Serializable
private data class SubjectRewatchSave(val entries: List<SubjectRewatchEntry> = emptyList())
