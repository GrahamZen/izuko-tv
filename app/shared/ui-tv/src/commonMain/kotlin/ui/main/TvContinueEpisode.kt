/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.UnifiedCollectionType

/** 接着看一部番时该去哪一集, 见 [tvContinueEpisode]. */
sealed interface TvContinueEpisode {
    /** 还没开始看: 没标过看过, 正片也没有播放记录. */
    data object NotStarted : TvContinueEpisode

    /** 播 [episode]; [resume] 是这一集看到一半的播放记录, 从头播为 null. */
    data class Play(val episode: EpisodeCollectionInfo, val resume: EpisodeHistory?) : TvContinueEpisode

    /** 追平连载: 下一集 [episode] 一定还没播出. */
    data class Upcoming(val episode: EpisodeCollectionInfo) : TvContinueEpisode
}

/**
 * 一部番接着看该去哪一集 (系统主屏「继续观看」卡片用). 只算正片, 与 `SubjectProgressInfo` 同口径.
 *
 * 「看到哪了」取两处里靠后的那个:
 * - 标过看过 (含抛弃) 的最后一集;
 * - 播放记录里这一部最近动过的那一集, 它看完了的话 (软删除的墓碑或停在结尾 5 秒内, 见 `TvUpNextStore`).
 *   `autoMarkDone` 关掉时看完一集不会有标记, 只看标记的话会一直停在刚看完的那一集.
 *
 * 最近动过的那一集停在中间、又在「看到哪了」之后 → 接着它播. 否则播下一集; 下一集一定还没播出 ([isKnownOnAir], **不能用
 * `isKnownCompleted` 取反**: 没有日期的集取反会被当成没播) → [TvContinueEpisode.Upcoming]; 正片全看完 → 从第一集重温 (同追番页播放键).
 *
 * @param episodes 条目的剧集, 已按集号排好.
 * @param historyByEpisode 播放记录 (含墓碑), 按分集 id.
 */
fun tvContinueEpisode(
    episodes: List<EpisodeCollectionInfo>,
    historyByEpisode: Map<Int, EpisodeHistory>,
    isKnownOnAir: (EpisodeInfo) -> Boolean,
): TvContinueEpisode {
    val main = episodes.filter { it.episodeInfo.sort is EpisodeSort.Normal }
    val lastMarked = main.indexOfLast {
        it.collectionType == UnifiedCollectionType.DONE || it.collectionType == UnifiedCollectionType.DROPPED
    }
    val latest = main.withIndex()
        .mapNotNull { (index, episode) -> historyByEpisode[episode.episodeId]?.let { index to it } }
        .maxByOrNull { (_, record) -> record.versionMillis }
    val latestIndex = latest?.first ?: -1
    val latestRecord = latest?.second
    val latestFinished = latestRecord != null && (latestRecord.isDeleted || latestRecord.isFinished)
    val doneThrough = maxOf(lastMarked, if (latestFinished) latestIndex else -1)
    if (latestRecord != null && !latestFinished && latestRecord.positionMillis > 0 && latestIndex > doneThrough) {
        return TvContinueEpisode.Play(main[latestIndex], latestRecord)
    }
    if (doneThrough < 0) return TvContinueEpisode.NotStarted
    val next = main.getOrNull(doneThrough + 1) ?: return TvContinueEpisode.Play(main.first(), null)
    if (isKnownOnAir(next.episodeInfo)) return TvContinueEpisode.Upcoming(next)
    return TvContinueEpisode.Play(next, null)
}
