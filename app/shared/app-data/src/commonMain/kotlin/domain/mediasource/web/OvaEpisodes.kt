/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import me.him188.ani.app.domain.mediasource.search.AnimeNames
import me.him188.ani.app.domain.mediasource.search.WorkKind
import me.him188.ani.app.domain.mediasource.search.WorkKinds
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 条目本身是 OVA: Bangumi 记的类型是 OVA (名字表 [AnimeNames] 里有这一部时), 或名字里写着 OVA.
 * OVA 条目在 Bangumi 上的集是正片第 1、2…集, 站点的条目页上却常写成「OVA01」「OVA02」, 见 [asEpisodesOf].
 */
internal fun MediaFetchRequest.isOvaSubject(): Boolean {
    val node = subjectId.toIntOrNull()?.let { AnimeNames.source?.invoke()?.nodeOf(it) }
    if (node != null && WorkKind.OVA in WorkKinds.ofCategory(node.metaTags)) return true
    return subjectNames.any { WorkKind.OVA in WorkKinds.of(it) }
}

/**
 * 按 [query] 认条目页上的集: OVA 条目 ([SelectorSearchQuery.ovaSubject]) 的条目页上没有正片编号的集、只有「OVA01」这样的
 * 特别篇编号时, 这些就是它的正片, 换成第 n 集. 正片与 OVA 混在一页的 (TV 动画页带几集 OVA) 不动, 那些 OVA 仍是特别篇.
 */
internal fun List<WebSearchEpisodeInfo>.asEpisodesOf(query: SelectorSearchQuery): List<WebSearchEpisodeInfo> {
    if (!query.ovaSubject || any { it.episodeSortOrEp is EpisodeSort.Normal }) return this
    return map { info ->
        val sort = info.episodeSortOrEp as? EpisodeSort.Special ?: return@map info
        val number = sort.number ?: return@map info
        if (sort.type != EpisodeType.OVA && sort.type != EpisodeType.OAD) return@map info
        info.copy(episodeSortOrEp = EpisodeSort(number.toString()))
    }
}
