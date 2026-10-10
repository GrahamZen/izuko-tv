/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.app.data.models.subject.SplitSeasonDetector
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.persistent.database.dao.SubjectRelations
import me.him188.ani.datasources.api.EpisodeType

/**
 * [requestingSubject] 所在的拆分季 ([SubjectRelations.splitSeason]); 不是拆分季的一段时为 `null`.
 *
 * 上游由 Ani 服务端识别后随条目关系下发; 直连在本地用 [SplitSeasonDetector] 识别, 不发请求:
 * 主线上别的条目取系列关系图里的名字与分集数据 ([SubjectRelationIndex.seriesMainNodes]),
 * 当前条目用它自己的全部名字与已经取到的分集 (正在播的那一段的分集比表新).
 */
fun SubjectRelationIndex.splitSeasonOf(requestingSubject: SubjectCollectionInfo): SplitSeason? {
    if (seriesMainSubjectIds.size < 2) return null
    val selfId = requestingSubject.subjectId
    val nodes = seriesMainNodes.associateBy { it.id }
    val mainLine = seriesMainSubjectIds.mapNotNull { id ->
        val node = nodes[id]
        if (id == selfId) {
            val sorts = requestingSubject.episodes
                .filter { it.episodeInfo.type == EpisodeType.MainStory }
                .mapNotNull { it.episodeInfo.sort.number }
            SplitSeasonDetector.Entry(
                subjectId = id,
                names = requestingSubject.subjectInfo.allNames,
                firstSort = sorts.minOrNull()?.toInt() ?: node?.firstSort,
                episodeCount = if (sorts.isNotEmpty()) sorts.size else node?.episodes ?: 0,
                inlineSpecialCount = node?.inlineSpecialCount ?: 0,
            )
        } else {
            node?.let {
                SplitSeasonDetector.Entry(
                    subjectId = id,
                    names = listOf(it.nameCn, it.name).filter { name -> name.isNotBlank() }.distinct(),
                    firstSort = it.firstSort,
                    episodeCount = it.episodes ?: 0,
                    inlineSpecialCount = it.inlineSpecialCount,
                )
            }
        }
    }
    return SplitSeasonDetector.detect(selfId, mainLine)
}
