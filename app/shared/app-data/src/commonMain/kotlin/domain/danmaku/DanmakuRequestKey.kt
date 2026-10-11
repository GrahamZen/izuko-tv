/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import me.him188.ani.app.data.models.episode.allNames
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.app.data.repository.danmaku.SearchDanmakuRequest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import kotlin.time.Duration

/**
 * [SearchDanmakuRequest] 里真正决定查哪些弹幕的部分 (就是转成弹幕源请求时用到的字段). 播放页按它判断要不要重新查.
 *
 * 不能拿整个请求比: 条目与分集信息里还有评分、收藏人数、分集评论数, 「继续观看」每小时刷新收藏时把它们写回库里,
 * 数字一变请求就不相等, 同一集的弹幕每小时整套重查一遍 (弹弹 play 按名字搜时一次二十个请求).
 */
internal data class DanmakuRequestKey(
    val subjectId: Int,
    val subjectName: String,
    val subjectNames: List<String>,
    val subjectAirDate: PackedDate,
    val episodeId: Int,
    val episodeSort: EpisodeSort,
    val episodeEp: EpisodeSort?,
    val episodeName: String,
    val episodeNames: List<String>,
    val filename: String?,
    val fileLength: Long?,
    val fileHash: String?,
    val videoDuration: Duration,
    val splitSeason: SplitSeason?,
)

internal val SearchDanmakuRequest.fetchKey: DanmakuRequestKey
    get() = DanmakuRequestKey(
        subjectId = subjectInfo.subjectId,
        subjectName = subjectInfo.displayName,
        subjectNames = subjectInfo.allNames,
        subjectAirDate = subjectInfo.airDate,
        episodeId = episodeId,
        episodeSort = episodeInfo.sort,
        episodeEp = episodeInfo.ep,
        episodeName = episodeInfo.displayName,
        episodeNames = episodeInfo.allNames,
        filename = filename,
        fileLength = fileLength,
        fileHash = fileHash,
        videoDuration = videoDuration,
        splitSeason = splitSeason,
    )
