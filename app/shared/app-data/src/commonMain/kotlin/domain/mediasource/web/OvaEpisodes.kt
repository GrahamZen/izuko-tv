/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.mediasource.search.AnimeNames
import me.him188.ani.app.domain.mediasource.search.WorkKind
import me.him188.ani.app.domain.mediasource.search.WorkKinds
import me.him188.ani.app.domain.mediasource.search.isHostPage
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.source.MediaFetchHostWork
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
 * 叫 [pageName] 的页是本条目所依附的本篇的页时, 只留其中本条目的那几集 (见 [onHostPage]).
 */
internal fun List<WebSearchEpisodeInfo>.asEpisodesOf(query: SelectorSearchQuery, pageName: String? = null): List<WebSearchEpisodeInfo> {
    val host = query.hostWork
    if (host != null && pageName != null && host.isHostPage(pageName)) return onHostPage(host, query)
    if (!query.ovaSubject || any { it.episodeSortOrEp is EpisodeSort.Normal }) return this
    return map { info ->
        val sort = info.episodeSortOrEp as? EpisodeSort.Special ?: return@map info
        val number = sort.number ?: return@map info
        if (sort.type != EpisodeType.OVA && sort.type != EpisodeType.OAD) return@map info
        info.copy(episodeSortOrEp = EpisodeSort(number.toString()))
    }
}

/**
 * 本篇 [host] 的页上属于本条目 (接在本篇后面编号) 的集, 按线路各自判断; 其余的集 (本篇的正片) 一集都不留:
 * 本篇第 1 集与本条目第 1 集同号, 留着就会被当成本条目的.
 *
 * 本条目的集号取它实际的 sort ([SelectorSearchQuery.subjectEpisodeSorts] 里接在本篇之后的那些; Bangumi 上可能有空号, 如 14、15、17、18、21),
 * 不知道时按正片集数从本篇之后连着算.
 * - 正片编号要从本篇第一集编起、最多编到本条目最后一集; 编得更远的页装着后面的季, 同号的集不一定是本条目的, 整条线路不要.
 * - 有本条目集号的集 (TV 13 集后面的「14」) 就是它们.
 * - 否则依次找与本条目集数一样多的一组外传, 按先后对上本条目的各集, 集号换成本条目的 sort:
 *   标签正是本条目名字比本篇多出来的那段 (「XX EX」的「EX」); 全部外传 ([isSideStory]: OVA、OAD、SP、特别篇、番外…);
 *   SP 以外的外传 (SP 多是附赠的小短篇, 与 OVA 混在一页时 OVA 才是单独立条目的那部).
 * - 都对不上时, 集名里含着正在看的这一集的名字的那条 (只有一条时) 是这一集.
 * 已经换过集号的 (页上只剩本条目的集) 原样返回.
 */
private fun List<WebSearchEpisodeInfo>.onHostPage(host: MediaFetchHostWork, query: SelectorSearchQuery): List<WebSearchEpisodeInfo> {
    val first = host.firstSort + host.episodeCount
    val sorts = query.subjectEpisodeSorts.orEmpty().mapNotNull { (it as? EpisodeSort.Normal)?.number }.filter { it >= first }.distinct().sorted()
        .ifEmpty { (0 until (query.subjectEpisodeCount ?: 1)).map { (first + it).toFloat() } }
    val last = sorts.last()
    fun numberOf(info: WebSearchEpisodeInfo): Float? = (info.episodeSortOrEp as? EpisodeSort.Normal)?.number
    fun isOwn(info: WebSearchEpisodeInfo): Boolean = numberOf(info)?.let { it in sorts } == true
    val suffixes = suffixesAfter(host.names, query.allSubjectNames)
    fun isOwnLabel(info: WebSearchEpisodeInfo): Boolean = numberOf(info) == null && normalized(info.name) in suffixes
    val episodeName = query.episodeName?.let(::normalized)?.takeIf { it.length >= MIN_EPISODE_NAME_LENGTH }
    return groupBy { it.channel }.values.flatMap { line ->
        if (line.all(::isOwn)) return@flatMap line
        val numbers = line.mapNotNull(::numberOf)
        if (numbers.isEmpty() || numbers.min() > host.firstSort || numbers.max() > last) return@flatMap emptyList()
        line.filter(::isOwn).ifEmpty {
            val sideStories = line.filter { isOwnLabel(it) || isSideStory(it) }
            val group = listOf(line.filter(::isOwnLabel), sideStories, sideStories.filterNot(::isSp)).firstOrNull { it.size == sorts.size }
            if (group != null) {
                return@flatMap group.mapIndexed { index, info -> info.copy(episodeSortOrEp = EpisodeSort(sortText(sorts[index]))) }
            }
            val named = line.filter { info -> episodeName != null && numberOf(info) == null && episodeName in normalized(info.name) }
            named.singleOrNull()?.let { listOf(it.copy(episodeSortOrEp = query.episodeSort)) }.orEmpty()
        }
    }
}

/** 本条目的名字比本篇的名字多出来的那段 (「露蒂的玩具 EX」之于「露蒂的玩具」是「ex」), 规范化后的. */
private fun suffixesAfter(hostNames: List<String>, subjectNames: Collection<String>): Set<String> = buildSet {
    val hosts = hostNames.map(::normalized).filter { it.isNotEmpty() }
    for (name in subjectNames) {
        val own = normalized(name)
        for (host in hosts) if (own.length > host.length && own.startsWith(host)) add(own.substring(host.length))
    }
}

private fun normalized(name: String): String = MediaListFilters.normalizeForCompare(name).lowercase()

private fun isSp(info: WebSearchEpisodeInfo): Boolean =
    (info.episodeSortOrEp as? EpisodeSort.Special)?.type == EpisodeType.SP || info.name.trim().startsWith("SP", ignoreCase = true)

/** 集号写法: `14`、`12.5` (整数不带小数点) */
private fun sortText(sort: Float): String = if (sort == sort.toInt().toFloat()) sort.toInt().toString() else sort.toString()

/**
 * 本篇页上正片以外的外传: 集号解析成 OVA、OAD、SP, 或集名写着「特别篇」「番外」「特典」「OVA」这类 (中文写法解析不出类型).
 * PV、OP、ED、MAD (预告、片头片尾、剪辑) 不算.
 */
private fun isSideStory(info: WebSearchEpisodeInfo): Boolean {
    val sort = info.episodeSortOrEp
    if (sort is EpisodeSort.Normal) return false
    if (sort is EpisodeSort.Special) return sort.type in SIDE_STORY_TYPES
    return SIDE_STORY_WORDS.containsMatchIn(info.name) && !NOT_SIDE_STORY_WORDS.containsMatchIn(info.name)
}

private val SIDE_STORY_TYPES = setOf(EpisodeType.OVA, EpisodeType.OAD, EpisodeType.SP)
private val SIDE_STORY_WORDS = Regex("""特[别別]篇|番外|特典|OVA|OAD|OAV""", RegexOption.IGNORE_CASE)
private val NOT_SIDE_STORY_WORDS = Regex("""预告|預告|PV|CM|NCOP|NCED|片头|片尾""", RegexOption.IGNORE_CASE)

/** 按集名认本篇页上的外传时, 集名至少这么长 (去掉标点空格后): 太短的名字到处都是 */
private const val MIN_EPISODE_NAME_LENGTH = 4
