/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.list

import androidx.compose.runtime.Immutable
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectRecurrence
import me.him188.ani.app.domain.episode.EpisodeCompletionContext
import me.him188.ani.app.domain.episode.EpisodeCompletionContext.mapAirDate
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.utils.platform.annotations.TestOnly
import me.him188.ani.utils.serialization.BigNum
import kotlin.time.Instant

@Immutable
data class EpisodeListUiState(
    val subjectTitle: String,
    val mainEpisodes: List<EpisodeListItem>,
    val otherEpisodes: List<EpisodeListItem>,
    /**
     * 全部分集, 保持数据源顺序 (数据库 `ORDER BY sortNumber ASC, sort ASC`, 见 EpisodeCollectionDao) ——
     * 也就是播放器选集列表看到的那个顺序: 特别篇按其序号**插在正片之间**, 如尸鬼的 20.5 落在 20 与 21 中间.
     *
     * 不能拿 [mainEpisodes] + [otherEpisodes] 拼出来: 二者各自按 [EpisodeSort] 排过, 而
     * `EpisodeSort.compareTo` 无条件判定 `Normal < Special`, 拼接会把所有特别篇甩到末尾.
     * 数据库排的是 `sortNumber` (= `sort.number`, 特别篇也有数值), 语义与之不同.
     *
     * 需要"正片归正片、特别篇归特别篇"的分组视图 (如选集网格、旧版选集对话框)
     * 仍用 [mainEpisodes] / [otherEpisodes].
     */
    val allEpisodes: List<EpisodeListItem> = emptyList(),
    val isPlaceholder: Boolean = false,
) {
    companion object {
        fun from(
            collection: SubjectCollectionInfo,
            currentTime: Instant,
        ): EpisodeListUiState {
            val allEpisodes = collection.episodes.map { episode ->
                EpisodeListItem.from(
                    episode,
                    isBroadcast = isEpisodeBroadcast(collection.recurrence, episode.episodeInfo.airDate, currentTime),
                )
            }
            val (mainEpisodes, otherEpisodes) = allEpisodes.partition {
                it.sort is EpisodeSort.Normal
            }

            return EpisodeListUiState(
                subjectTitle = collection.subjectInfo.displayName,
                mainEpisodes = mainEpisodes.sortedBy { it.sort },
                otherEpisodes = otherEpisodes.sortedBy { it.sort },
                allEpisodes = allEpisodes,
            )
        }

        /**
         * 剧集列表的 "未开播" 着色规则.
         *
         * - 能算出播出时刻 (见 [EpisodeCompletionContext.mapAirDate]) 时, 以 [currentTime] 是否已到达该时刻为准;
         * - 算不出时 (剧集没有上映日期): 有 [recurrence] 说明条目仍在连载, 没有日期的剧集视为未开播;
         *   没有 [recurrence] 时保持显示为已开播.
         *
         * 注意 [recurrence] 为 `null` 只表示 bangumi-data 没有该条目的 `broadcast` 信息, 与是否完结无关 (完结番同样保留 `broadcast`).
         */
        fun isEpisodeBroadcast(
            recurrence: SubjectRecurrence?,
            airDate: PackedDate,
            currentTime: Instant,
        ): Boolean = recurrence.mapAirDate(airDate)?.let { it <= currentTime } ?: (recurrence == null)

        val Placeholder = EpisodeListUiState(
            subjectTitle = "",
            mainEpisodes = emptyList(),
            otherEpisodes = emptyList(),
            isPlaceholder = true,
        )
    }
}

@TestOnly
val TestEpisodeListUiState
    get() = run {
        val main = TestEpisodeListItems
        val other = main.take(2).map { it.copy(sort = EpisodeSort(BigNum(it.sort.number!!), EpisodeType.SP)) }
        EpisodeListUiState(
            subjectTitle = "测试标题",
            mainEpisodes = main,
            otherEpisodes = other,
            allEpisodes = main + other,
        )
    }

@TestOnly
val TestEpisodeListUiStateVeryLong
    get() = run {
        val main = buildList {
            repeat(100) {
                add(createTestEpisodeListItem(EpisodeSort(it + 1)))
            }
        }
        val other = TestEpisodeListItems.take(2)
            .map { it.copy(sort = EpisodeSort(BigNum(it.sort.number!!), EpisodeType.SP)) }
        EpisodeListUiState(
            subjectTitle = "测试标题",
            mainEpisodes = main,
            otherEpisodes = other,
            allEpisodes = main + other,
        )
    }

@TestOnly
val TestEpisodeListItems
    get() = buildList {
        repeat(12) {
            add(createTestEpisodeListItem(EpisodeSort(it + 1)))
        }
    }
