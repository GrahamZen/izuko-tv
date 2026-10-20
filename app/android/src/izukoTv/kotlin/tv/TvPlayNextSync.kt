/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.tv

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.episode.EpisodeLink
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.ui.main.TvPlayNextChanges
import me.him188.ani.app.ui.main.TvPlayNextRecord
import me.him188.ani.app.ui.main.TvPlayNextRow
import me.him188.ani.app.ui.main.detectPlayNextChanges
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * 系统主屏「接下来播放」(Watch Next) 上用户的两个操作, 对到 Bangumi 收藏 —— 这一行里放的都是在看的番:
 *
 * - 在频道 (热门动画 / 今日更新) 的卡片上长按「添加到接下来播放」: 系统把卡复制进本应用名下的这一行 (类型 WATCHLIST)
 *   → 标成在看 (没收藏 / 想看 / 搁置才改; 看过、抛弃不动, 重温不该抹掉看过). 进了在看列表后, [TvHomeChannels] 重写时换成按集画的那张;
 * - 在这一行里移除我们写的卡: 系统直接删掉那一行 → 在看的标成搁置, 这部就不再出现在这一行.
 *
 * 系统不给应用发 `ACTION_PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT` / `ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED` 广播 (Shield 的
 * tvlauncher 与 TvProvider 里都没有发它们的代码), 只能自己看行: 记住上次写进去的行 id (落盘, 进程被杀后下次接着认),
 * 不见了又不是我们删的 = 用户移除. 进程在时监听这张表当场处理 ([watch]), 每次重写前也对一遍 ([detect]).
 *
 * 多用户: 这一行整台电视一份, 是最后一个退到后台的用户写的; 记录里连同写它的人一起记 ([TvPlayNextRecord]),
 * 只有当前用户就是写的人时才认主屏上的操作, 换了人就只重写、不改谁的收藏 (见 [detectPlayNextChanges]).
 *
 * 读写都要在 [TvHomeChannels] 的同一把锁里: 我们自己重写时删掉的行, 记录同时换成新写的, 不会被当成用户移除.
 */
internal class TvPlayNextSync(context: Context, private val koin: Koin) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 已经认过、改不了收藏的手动卡 (看过 / 抛弃的番), 本进程不再一遍遍查. */
    private val settledWatchlistRows: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * 正在改收藏的手动卡. 先写本地收藏, 在看列表一变就触发重写、重写前又对一遍, 那时这张卡还在、Bangumi 可能还没写完:
     * 不跳过的话同一张卡会再认一遍, 赶在本地写之前就会向 Bangumi 重复写.
     */
    private val pendingWatchlistRows: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * 对照现有的行认出用户的操作, 并把认过的移除从记录里去掉 (只认一次).
     *
     * 记着的行 (两张以上) 一下子全没了不算用户移除: 主屏上只能一张张删, 进程在时每删一张就对一次; 整表清空是系统那边的事
     * (比如清了 TvProvider 的数据), 不该把这些番全标成搁置. 进程不在时用户一张张删光的也因此认不出, 宁可漏标.
     */
    fun detect(existing: List<WatchNextProgram>): TvPlayNextChanges {
        val record = readRecord()
        val detection = detectPlayNextChanges(
            record,
            currentProfileId = UserProfiles.currentId,
            existing = existing.map { row ->
                TvPlayNextRow(
                    id = row.id,
                    manuallyAdded = row.watchNextType == TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST,
                    subjectId = subjectIdOf(row.intentUri, row.internalProviderId),
                )
            },
        ) { it in settledWatchlistRows || it in pendingWatchlistRows }
        detection.remainingRows?.let(::record)
        if (detection.externalReset) {
            logger.info { "Play Next: all ${record.rows.size} recorded rows are gone at once, treated as an external reset" }
        }
        if (detection.changes.foreign && existing.isNotEmpty()) {
            logger.info { "Play Next: the row was written by user profile ${record.writerProfileId}, not ${UserProfiles.currentId}; not applying changes" }
        }
        return detection.changes
    }

    /** 把用户的操作写成收藏 (先写本地再发 Bangumi); 在本对象自己的作用域里跑, 重写被新数据打断也照样写完. */
    fun apply(changes: TvPlayNextChanges) {
        if (changes.isEmpty) return
        // 在调用方的锁里当场记下, 下一次 detect 就看得到
        pendingWatchlistRows += changes.added.keys
        scope.launch {
            try {
                for (subjectId in changes.removed) {
                    setCollectionType(subjectId, UnifiedCollectionType.ON_HOLD) { it == UnifiedCollectionType.DOING }
                }
                for ((rowId, subjectId) in changes.added) {
                    if (subjectId in changes.removed) continue
                    val changed = setCollectionType(subjectId, UnifiedCollectionType.DOING) { it in ADD_REPLACES }
                    if (changed == false) settledWatchlistRows += rowId
                }
            } finally {
                pendingWatchlistRows -= changes.added.keys
            }
        }
    }

    /** 我们开始重写这一行: 先清空记录, 删到一半进程被杀时下次不会把我们删的当成用户移除. */
    fun beginRewrite() = record(emptyMap())

    /** 这一轮写进去的行 (行 id -> 条目). */
    fun endRewrite(rows: Map<Long, Int>) = record(rows)

    /** 进程在时监听这张表: 有变化就调 [onChange] (调用方在锁里 [detect] + [apply]); 返回取消监听. */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(TvContractCompat.WatchNextPrograms.CONTENT_URI, true, observer)
        return { resolver.unregisterContentObserver(observer) }
    }

    /** @return 改了没有; `null` = 没改成 (取不到条目 / 写失败, 下次再认) */
    private suspend fun setCollectionType(
        subjectId: Int,
        type: UnifiedCollectionType,
        replaces: (UnifiedCollectionType) -> Boolean,
    ): Boolean? = try {
        withTimeoutOrNull(WRITE_TIMEOUT) {
            // 先把这部取进本地: 没收藏过的番本地可能没有这一行, 改收藏先写本地时就写空了 (在看列表与这一行都靠本地库)
            val current = koin.get<SubjectCollectionRepository>().subjectCollectionFlow(subjectId).first().collectionType
            if (!replaces(current)) {
                logger.info { "Play Next: subject $subjectId is $current, not changed to $type" }
                return@withTimeoutOrNull false
            }
            koin.get<SetSubjectCollectionTypeOrDeleteUseCase>()(subjectId, type)
            logger.info { "Play Next: subject $subjectId $current -> $type" }
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Play Next: failed to set subject $subjectId to $type" }
        null
    }

    private fun readRecord(): TvPlayNextRecord {
        val rows = prefs.getStringSet(KEY_WRITTEN, null).orEmpty().mapNotNull { entry ->
            val rowId = entry.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val subjectId = entry.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
            rowId to subjectId
        }.toMap()
        val writer = if (prefs.contains(KEY_WRITER)) prefs.getInt(KEY_WRITER, UserProfile.PRIMARY_ID) else null
        return TvPlayNextRecord(writer, rows)
    }

    /** 记下这一行现在的样子, 写的人是当前用户. */
    private fun record(rows: Map<Long, Int>) {
        prefs.edit()
            .putStringSet(KEY_WRITTEN, rows.mapTo(HashSet()) { (rowId, subjectId) -> "$rowId:$subjectId" })
            .putInt(KEY_WRITER, UserProfiles.currentId)
            .commit()
    }

    private companion object {
        val logger = logger<TvPlayNextSync>()

        const val PREFS_NAME = "tv_home_channels"
        const val KEY_WRITTEN = "watch_next_written_rows"
        const val KEY_WRITER = "watch_next_written_profile"

        val WRITE_TIMEOUT = 30.seconds

        val ADD_REPLACES = setOf(
            UnifiedCollectionType.NOT_COLLECTED,
            UnifiedCollectionType.WISH,
            UnifiedCollectionType.ON_HOLD,
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * 主屏卡片对应的条目: 先看链接 (`ani://subjects/<id>...`), 再看 internalProviderId (继续观看写的是条目 id, 今日更新是 `条目:分集`).
 * 系统复制进「接下来播放」的卡带着原卡的链接.
 */
internal fun subjectIdOf(intentUri: Uri?, internalProviderId: String?): Int? =
    (intentUri?.toString()?.let(EpisodeLink::parse) as? EpisodeLink.Subject)?.subjectId
        ?: internalProviderId?.substringBefore(':')?.toIntOrNull()
