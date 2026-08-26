/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.toTmdbMatchHints
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.newestAiredDateStringOrNull
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.episode.EpisodeCompletionContext.isKnownOnAir
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaCache
import me.him188.ani.app.ui.foundation.tv.prefetchTvBackdrop
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger
import kotlin.time.Duration.Companion.seconds

/**
 * 动作面板上「接下来播放」那张卡要播的东西.
 *
 * 只带**身份 + 显示**两样, 不带播放位置以外的任何状态: 真按下去之后位置由播放器自己按 episodeId
 * 从播放进度表续 (见 `RememberPlayProgressExtension`), 这里那份只是拿来画进度线和写时间的.
 */
@Immutable
data class TvUpNextTarget(
    val subjectId: Int,
    val episodeId: Int,
    val subjectTitle: String,
    /** 集号 (如 "12", "20.5"); 空 = 不知道, 卡片那一行就不写. */
    val episodeSort: String,
    /** 上次看到哪; 0 = 这是没看过的下一集. */
    val positionMillis: Long,
    /** 总时长; 0 = 不知道 (那就不画进度线). */
    val durationMillis: Long,
) {
    /** 是"接着上次没看完的"还是"下一集". 决定卡片那行小字与标签行的文案. */
    val continuing: Boolean get() = positionMillis > 0
}

/**
 * **进程级的"接下来播放"**: 没有后台会话时, 动作面板那张卡显示什么.
 *
 * ## 数据从哪来
 *
 * 只看**播放进度表最新的那一条** ([EpisodePlayHistoryRepository.allHistoriesFlow] 已按
 * `max(updatedAt, deletedAt)` 倒序). 关键在于那张表是**软删除**的 —— 看完一集时
 * `RememberPlayProgressExtension` 会 `remove`, 而 `remove` 只打 `deletedAtMillis` 墓碑留着做同步,
 * 本地没有任何硬删除. 于是同一条记录能回答两个问题:
 *
 * - `!isDeleted` → 那一集**没看完** → 就播它 (接着上次的位置);
 * - `isDeleted` → 那一集**看完了** (或被用户在播放历史页删了) → 取同一部里的**下一集**.
 *
 * 因此**不需要另存一份"上次看的是哪一集"**. (早先做过一个 `LastPlayedEpisode` 记录, 理由是
 * "进度表看完就删" —— 那是把 active 流的过滤条件当成了数据本身.)
 *
 * "下一集"按**剧集列表位置**取, 不按 `SubjectProgressInfo` 的「看过」标记推: `autoMarkDone` 用户
 * 可以关, 关了的话看完也不会有标记, 推出来的"下一集"还是刚看完那一集. 未播出的用
 * [isKnownOnAir] 拦掉 (**不能用 `isKnownCompleted` 取反** —— SP/OVA 常常没有日期, 取反会把它们
 * 当成"还没播").
 *
 * ## 为什么是进程级单例而不是 ViewModel
 *
 * 面板要**同步**读到它: 卡片能不能按决定了面板打开时的默认焦点落点, 而落点一旦因为数据晚到而改变,
 * 用户看到的就是"焦点自己跳走了". 单例在根部起一次、之后一直跟着进度表更新, 面板每次打开都是
 * 直接 peek 现成的值; 第一次冷启动那几秒还没算出来的话, 面板就当没有 (落圆钮, 卡片随后补上,
 * 仍能用方向键上去).
 *
 * 查询全部走本地缓存优先的仓库 flow 的**第一次发射**, 且封了超时: 这条链绝不能把面板卡住.
 */
@Stable
object TvUpNextStore {
    private val logger = logger<TvUpNextStore>()

    /** 当前的目标; `null` = 没有 (没看过任何东西 / 还没算出来 / 看完了且没有下一集). */
    var target: TvUpNextTarget? by mutableStateOf(null)
        private set

    /**
     * 跟着播放进度表算, 一直跑到进程结束. 由 TV 根部起一次.
     *
     * `collectLatest`: 记录变化比查询快时 (连着播完几集) 只算最后那一次.
     */
    suspend fun run() {
        val history = GlobalKoin.get<EpisodePlayHistoryRepository>()
        val subjects = GlobalKoin.get<SubjectCollectionRepository>()
        history.allHistoriesFlow
            .map { it.firstOrNull() }
            .distinctUntilChanged()
            .collectLatest { latest ->
                val resolved = if (latest == null) null else resolve(latest, subjects)
                target = resolved?.target
                // 先给卡片, 再去取图: 图慢也不该让"接下来播放"这张卡晚出来
                resolved?.let { warmImages(it) }
            }
    }

    private suspend fun resolve(
        latest: EpisodeHistory,
        subjects: SubjectCollectionRepository,
    ): Resolved? {
        val subjectId = latest.subjectId ?: return null // 很老的记录可能没有条目 id, 那就没得跳
        // 条目信息两件事都要用: 看完那一路要剧集列表才知道下一集是谁, 而**两路都要它来取图**
        // (见 warmImages —— 卡片上的缩略图只认原名与剧集列表). 本地有缓存就是同步发射, 没有才
        // 走网络, 所以封超时; 拿不到就退化成"只有文字的卡", 而不是没有卡
        val collection = withTimeoutOrNull(LOOKUP_TIMEOUT) {
            runCatching { subjects.subjectCollectionFlow(subjectId).first() }
                .onFailure { logger.error(it) { "Failed to load subject $subjectId for up-next" } }
                .getOrNull()
        }
        if (!latest.isDeleted) {
            // 没看完: 就播它. 剧名/集号/时长在进度记录里本来就有, 条目信息拿不到也不影响这张卡
            return Resolved(
                TvUpNextTarget(
                    subjectId = subjectId,
                    episodeId = latest.episodeId,
                    subjectTitle = collection?.subjectInfo?.displayName
                        ?: latest.subjectName.orEmpty(),
                    episodeSort = latest.episodeSort?.renderSort().orEmpty(),
                    positionMillis = latest.positionMillis,
                    durationMillis = latest.durationMillis ?: 0L,
                ),
                collection,
            )
        }
        // 看完了: 取同一部里的下一集
        collection ?: return null
        val episodes = collection.episodes.filter { it.episodeInfo.sort is EpisodeSort.Normal }
        val index = episodes.indexOfFirst { it.episodeId == latest.episodeId }
        if (index < 0) return null
        val next = episodes.getOrNull(index + 1) ?: return null // 最后一集看完了: 没有下一集
        if (next.episodeInfo.isKnownOnAir(collection.recurrence)) return null // 下一集还没播
        return Resolved(
            TvUpNextTarget(
                subjectId = subjectId,
                episodeId = next.episodeId,
                subjectTitle = collection.subjectInfo.displayName,
                episodeSort = next.episodeInfo.sort.toString(),
                positionMillis = 0L,
                durationMillis = 0L,
            ),
            collection,
        )
    }

    /**
     * **把卡片要的图先取回进程内热表**.
     *
     * 卡片上那两级图源都是 `peek`: `TvHeroMediaCache.peekSubjectInfo` (单集剧照要靠它拿原名与
     * 剧集列表) 与 `TmdbImageService.peekBackdropUrl`. 两张表都只活在进程里 —— 面板刻意不发请求
     * (它可能在任何页面被长按唤出), 于是**冷启动后第一次开面板必然两级全空**, 卡片只能画兜底渐变:
     * 图明明有、URL 甚至就在磁盘缓存里, 只是没人把它读回来. 这里替它读一次.
     *
     * 原名必须传 [SubjectInfo.name] (日文): 传中文译名在 TMDB 上命中率低, 而失败会写**持久**负缓存,
     * 把这个条目的图钉死 (见 TmdbImageService 与 [prefetchTvBackdrop] 的说明).
     *
     * **不预取单集剧照**: 那条路会写 `TvHeroMediaCache.nextEpisodeMedia`, 而那张表的 episodeId
     * 判据与本处不同 (它是"继续观看"hero 的下一集), 互相覆盖会让探索页 hero 显示错集. 卡片自己那条
     * 剧照查询在 `peekSubjectInfo` 有货之后就能跑起来, 拿不到再退回 backdrop.
     */
    private suspend fun warmImages(resolved: Resolved) {
        val collection = resolved.collection ?: return
        TvHeroMediaCache.putSubjectInfo(collection.subjectId, collection)
        val tmdb = GlobalKoin.get<TmdbImageService>()
        tmdb.prefetchTvBackdrop(
            collection.subjectId,
            collection.subjectInfo.name,
            activeAsOfDate = collection.episodes.newestAiredDateStringOrNull(),
            // 与详情页喂同一份输入: 少喂一项就可能算出另一个结果, 而结果是**按条目共享缓存**的,
            // 先算的那次会把后算的钉死 (见 TmdbMatchHints)
            hints = collection.subjectInfo.toTmdbMatchHints(),
        )
    }

    /** [resolve] 的产物: 卡片要显示的东西 + 拿它去取图用的条目信息 (可能没拿到). */
    private class Resolved(
        val target: TvUpNextTarget,
        val collection: SubjectCollectionInfo?,
    )

    /** `12.0` -> `12`, `20.5` -> `20.5`: 进度表里集号是 Float, 卡片上不能写成 "12.0". */
    private fun Float.renderSort(): String =
        if (this == toInt().toFloat()) toInt().toString() else toString()

    /** 查剧集列表的封顶时间. 本地命中是毫秒级, 这个值只为兜住"没缓存 + 网络很慢". */
    private val LOOKUP_TIMEOUT = 8.seconds
}
