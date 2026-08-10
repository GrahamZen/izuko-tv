/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.models.subject.*
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.RepositoryUnknownException
import me.him188.ani.app.data.repository.episode.AnimeScheduleRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.restartOnNewLogin
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.retryWithBackoffDelay
import me.him188.ani.utils.logging.error
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * 用户正在追的条目仓库
 */
class FollowedSubjectsRepository(
    private val subjectCollectionRepository: SubjectCollectionRepository,
    private val animeScheduleRepository: AnimeScheduleRepository,
//    private val subjectProgressRepository: EpisodeProgressRepository,
//    private val subjectCollectionDao: SubjectCollectionDao,
    private val sessionManager: SessionStateProvider,
    settingsRepository: SettingsRepository,
    defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : Repository(defaultDispatcher) {
    private val nsfwModeSettingsFlow = settingsRepository.uiSettings.flow.map { it.searchSettings.nsfwMode }

    private fun followedSubjectsFlow(
        updatePeriod: Duration = 1.hours,
    ): Flow<List<FollowedSubjectInfo>> {
        require(updatePeriod > Duration.ZERO) { "updatePeriod must be positive" }

        val ticker = flow {
            while (true) {
                emit(Unit)
                kotlinx.coroutines.delay(updatePeriod)
            }
        }

        // 对于最近看过的一些条目
        return ticker.flatMapLatest {
            try {
                // 与下面查本地的 limit 对齐: 这一个批量请求是本栏目唯一的刷新来源, 少拉的那部分条目
                // (以前靠逐条 subjectCollectionFlow 顺带刷新) 就会一直看不到新播出的剧集 —— 表现为它不会
                // 被 sorter 的 hasNewEpisodeToPlay 顶到行首、hero 仍写着"已看完". 服务端 limit 无上限
                // (追番页 pager 的 initialLoadSize 已是 120), 拉满比多发几十个单条请求便宜得多.
                subjectCollectionRepository.updateRecentlyUpdatedSubjectCollections(
                    FOLLOWED_SUBJECTS_LIMIT,
                    UnifiedCollectionType.DOING,
                ) // refresh
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val displayE = when (e) {
                    is RepositoryUnknownException -> e
                    is RepositoryException -> null
                    else -> e
                }

                logger.error(displayE) { """Failed to update recently updated subject collections due to ${e}, ignoring. 这只会导致探索页的继续观看栏目可能显示旧结果. """ }
            }

            // 先查询完成 (插入数据库) 再返回 flow 去查数据库. 前端会展示 placeholder 所以延迟没问题.

            subjectCollectionRepository.mostRecentlyUpdatedSubjectCollectionsFlow(
                limit = FOLLOWED_SUBJECTS_LIMIT,
                types = listOf(
                    UnifiedCollectionType.DOING,
                ),
            ).combine(nsfwModeSettingsFlow) { subjectCollectionInfoList, nsfwMode ->
                toFollowedSubjectInfos(subjectCollectionInfoList, nsfwMode)
                    .toMutableList()
                    .apply {
                        sortWith(sorter)
                    }
            }
                // 内层任意一次失败都不能杀掉整条链: 它上面挂着探索页"继续观看"的 cachedIn 收集协程, 一旦
                // 协程死掉这一栏就永久停在旧快照 (改收藏、看完新一集都不再反映), 只能重启应用恢复.
                .retryWithBackoffDelay { e, _ ->
                    if (e is CancellationException) throw e
                    logger.error(e) { "Failed to collect followed subjects, retrying. 这只会导致探索页的继续观看栏目短暂显示旧结果." }
                    true
                }
        }.flowOn(defaultDispatcher)
    }

    /**
     * [subjectCollectionInfoList] 里已经带着各自的剧集列表 ([SubjectCollectionInfo.episodes]), 直接算即可.
     * 不要再为每个条目去订阅一遍剧集 flow —— 那既是重复查询, 又会把整条链变成 N 条 flow 的 `combine`
     * (任意一条卡住或抛异常都会拖垮整栏, 见 [SubjectCollectionRepository.mostRecentlyUpdatedSubjectCollectionsFlow]).
     */
    private fun toFollowedSubjectInfos(
        subjectCollectionInfoList: List<SubjectCollectionInfo>,
        nsfwMode: NsfwMode,
    ): List<FollowedSubjectInfo> = subjectCollectionInfoList.map { subjectCollectionInfo ->
        FollowedSubjectInfo(
            subjectCollectionInfo,
            // 这两个在 SubjectCollectionInfo 里已经按**同样的参数**算好了 (见
            // SubjectCollectionRepository 的 toSubjectCollectionInfo: airDate 同一列、recurrence 同源、
            // episodes 同一份, 只差两次相隔几毫秒的 PackedDate.now()). 直接复用, 不要再算一遍 ——
            // 每次都要遍历并排序该条目的全部剧集, 64 部就是 128 遍.
            subjectCollectionInfo.airingInfo,
            subjectCollectionInfo.progressInfo,
            nsfwMode =
                if (subjectCollectionInfo.subjectInfo.nsfw) nsfwMode
                else NsfwMode.DISPLAY,
        )
    }

    fun followedSubjectsPager(
        updatePeriod: Duration = 1.hours,
    ) = followedSubjectsFlow(updatePeriod)
        .restartOnNewLogin(sessionManager)
        .map {
            PagingData.from(
                it,
                NotLoading,
            )
        }.flowOn(defaultDispatcher)

    private companion object {
        /**
         * "继续观看"栏目的条目数上限. 服务器刷新与本地查询共用, 两者必须一致 —— 只刷新前 N 条却显示前 M 条
         * (N < M) 的话, 中间那段永远拿不到新播出的剧集.
         */
        private const val FOLLOWED_SUBJECTS_LIMIT = 64

        private val NotLoading = LoadStates(
            refresh = LoadState.NotLoading(true),
            prepend = LoadState.NotLoading(true),
            append = LoadState.NotLoading(true),
        )

        val sorter: Comparator<FollowedSubjectInfo> =
            // 不要用最后访问时间排序, 因为刷新后时间会乱
            compareByDescending<FollowedSubjectInfo> { info ->
                // 1. 现在可以看的 > 现在不能看的
                info.subjectProgressInfo.hasNewEpisodeToPlay
            }.thenByDescending { info ->
                // 2. 在看 > 想看
                info.subjectCollectionInfo.collectionType == UnifiedCollectionType.DOING
            }.thenByDescending { info ->
                // 3. 最后播放时间降序
                info.subjectCollectionInfo.lastUpdated
            }.thenByDescending { info ->
                // 4. (已经看了的 sort - first sort) 降序
                val firstEp = info.subjectCollectionInfo.episodes.firstOrNull()?.episodeInfo?.sort
                val firstDone =
                    info.subjectCollectionInfo.episodes.firstOrNull { it.collectionType == UnifiedCollectionType.DONE }
                        ?.episodeInfo?.sort
                if (firstEp != null && firstDone != null) {
                    firstDone.compareTo(firstEp)
                } else {
                    Int.MIN_VALUE
                }
            }

    }
}

