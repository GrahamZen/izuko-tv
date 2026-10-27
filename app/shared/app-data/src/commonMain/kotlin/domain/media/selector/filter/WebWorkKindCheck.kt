/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.mediasource.search.AnimeNames
import me.him188.ani.app.domain.mediasource.search.WorkKind
import me.him188.ani.app.domain.mediasource.search.WorkKinds
import me.him188.ani.datasources.api.EpisodeSort

/**
 * 在线源的条目页名写明是剧场版、外传、OVA ([WorkKinds]) 而条目不是这一类时, 页上的集不是本篇的这一集
 * (「XX OVA」页上的第 01 集按集号会对上第 1 集). 同网盘按作品类别否决文件夹与文件
 * ([me.him188.ani.app.domain.mediasource.clouddrive.DriveSubjectMatcher.matchEpisodes]):
 * 条目自己的类别不算 (名字里写着的, Bangumi 记的类型 ([WorkKinds.ofCategory], 从名字表 [AnimeNames] 取), 只在影院放映的算剧场版),
 * 只有一集的条目 (剧场版) 不查, 合集的名字 (「TV+OVA」) 不算. 只查本篇的集: 特别篇常就在「XX OVA」这种页上.
 */
internal object WebWorkKindCheck {
    private val CHECKED = setOf(WorkKind.MOVIE, WorkKind.SPIN_OFF, WorkKind.OVA)

    fun rejects(pageName: String, context: MediaSelectorContext): Boolean {
        if (context.episodeInfo?.sort !is EpisodeSort.Normal) return false
        val subject = context.subjectInfo ?: return false
        if (subject.totalEpisodes == 1) return false
        val pageKinds = WorkKinds.outsideCollectionsOf(pageName).filterTo(HashSet()) { it in CHECKED }
        if (pageKinds.isEmpty()) return false
        val own = subject.allNames.flatMapTo(HashSet()) { WorkKinds.of(it) }
        own += WorkKinds.ofCategory(AnimeNames.source?.invoke()?.nodeOf(subject.subjectId)?.metaTags.orEmpty())
        if (subject.theatrical) own += WorkKind.MOVIE
        return pageKinds.any { it !in own }
    }
}
