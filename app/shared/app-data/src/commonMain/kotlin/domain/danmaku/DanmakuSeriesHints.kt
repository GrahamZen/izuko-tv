/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuSeasonNumbering
import me.him188.ani.datasources.api.EpisodeSort

/**
 * 拆分季里这一集的整季编号 ([DanmakuFetchRequest.seasonNumbering]): 弹幕库常把 Bangumi 拆成几段的一季当一部作品、接着编集号.
 * 不是拆分季或不是正片的整数集号时为 `null`.
 */
internal fun SplitSeason?.danmakuSeasonNumbering(sort: EpisodeSort): DanmakuSeasonNumbering? {
    this ?: return null
    val number = (sort as? EpisodeSort.Normal)?.number ?: return null
    if (number % 1f != 0f) return null
    return DanmakuSeasonNumbering(seasonEpisode = seasonNumberOf(number.toInt()), partEpisodeCount = self.episodeCount)
}

/** 填上 [DanmakuFetchRequest.anidbId] (见 bgm-tmdb 对应表的 anidb 列); [anidbId] 为 `null` 时原样返回. */
internal fun DanmakuFetchRequest.withAnidbId(anidbId: Int?): DanmakuFetchRequest {
    if (anidbId == null || anidbId == this.anidbId) return this
    return DanmakuFetchRequest(
        subjectId = subjectId,
        subjectPrimaryName = subjectPrimaryName,
        subjectNames = subjectNames,
        subjectPublishDate = subjectPublishDate,
        episodeId = episodeId,
        episodeSort = episodeSort,
        episodeEp = episodeEp,
        episodeName = episodeName,
        episodeNames = episodeNames,
        filename = filename,
        fileHash = fileHash,
        fileSize = fileSize,
        videoDuration = videoDuration,
        anidbId = anidbId,
        seasonNumbering = seasonNumbering,
    )
}
