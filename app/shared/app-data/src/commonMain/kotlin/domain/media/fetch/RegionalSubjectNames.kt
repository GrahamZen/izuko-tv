/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.network.TmdbSubjectMapEntry
import me.him188.ani.app.data.network.TmdbSubjectMapRepository
import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.mediasource.toSimplifiedChinese
import me.him188.ani.datasources.api.source.MediaFetchRequest
import org.koin.core.component.KoinComponent

/*
 * 港台译名: 对应表 (bangumi-tmdb-map) 的 names 列, 取自 TMDB 条目的 zh-TW / zh-HK 翻译 (见 [TmdbSubjectMapEntry.regionalNames]).
 *
 * 台湾、香港的站点只认当地译名, 而两岸译名常常不只是简繁之别 (间谍过家家 / 間諜家家酒、药屋少女的呢喃 / 藥師少女的獨語),
 * 拿 Bangumi 的名字搜不到. 用在两处:
 * - 搜索: 接在回退关键词 ([MediaFetchRequest.fallbackSearchKeywords]) 的最后, 一个源用别的名字都没搜到对得上的条目时才用它再搜一次,
 *   已经搜得到的源不受影响. 排在系列名前面的话, 简体站会先用台湾的系列名命中第一季而不再试「XX 第二季」.
 * - 名字比对: 作为条目别名交给选择器, 站点上的繁体条目名才认得出是这一部; 同时把别的季的港台名字交给选择器的系列信息,
 *   别季的繁体页面才会按「是系列里别的季」排除 (只差季号的长名字模糊匹配率能过线, 光有别名会把第一季的页面当成本季).
 *   查询请求认部用的名字 ([MediaFetchRequest.nameHints]) 带上同样的两组, 数据源与选择器按同一组名字认站点上的条目.
 *
 * TMDB 的剧集按整个系列登记, 译名是系列名, 而 Bangumi 一季一个条目, 所以按条目自己名字里的季号补上.
 * 表里没有这个条目 (或设置里关了 TMDB 图片、表从没下载过) 时什么都不加.
 */

/** 查询请求的回退关键词末尾接上港台译名 (见 [regionalSearchName]), 认部用的名字带上港台的本季与别季名字. */
internal suspend fun KoinComponent.withRegionalNames(request: MediaFetchRequest, seriesInfo: SubjectSeriesInfo?): MediaFetchRequest {
    val entry = lookupMapEntry(request.subjectId.toIntOrNull() ?: return request) ?: return request
    return withRegionalNames(request, entry, seriesInfo)
}

/** [withRegionalNames] 查到对应表之后的部分. 名字取 [MediaFetchRequest.nameHints] 里条目服务的 (同选择器), 不取用户改过的搜索名. */
internal fun withRegionalNames(request: MediaFetchRequest, entry: TmdbSubjectMapEntry, seriesInfo: SubjectSeriesInfo?): MediaFetchRequest {
    val hints = request.nameHints
    val names = hints.ownNames.ifEmpty { request.subjectNames }
    val aliases = regionalSubjectAliases(entry, names, seriesInfo?.seasonSort ?: 1)
    val series = seriesInfo?.let { regionalSeriesNames(entry, names, it).series }.orEmpty()
    val withHints = request.copy(
        nameHints = hints.copy(
            ownNames = (hints.ownNames + aliases).distinct(),
            otherWorkNames = (hints.otherWorkNames + series).distinct(),
        ),
    )
    val name = regionalSearchName(entry, request.subjectNames) ?: return withHints
    if (name in request.fallbackSearchKeywords) return withHints
    return withHints.copy(fallbackSearchKeywords = request.fallbackSearchKeywords + name)
}

/** 交给选择器的条目信息带上港台译名作别名 (见 [regionalSubjectAliases]). */
internal suspend fun KoinComponent.withRegionalAliases(subject: SubjectInfo, seriesInfo: SubjectSeriesInfo): SubjectInfo {
    val entry = lookupMapEntry(subject.subjectId) ?: return subject
    val aliases = regionalSubjectAliases(entry, subject.allNames, seriesInfo.seasonSort)
        .filter { alias -> subject.allNames.none { it == alias } }
    return if (aliases.isEmpty()) subject else subject.copy(aliases = subject.aliases + aliases)
}

/** 交给选择器的系列信息带上别的季的港台名字 (见 [regionalSeriesNames]). */
internal suspend fun KoinComponent.withRegionalSeriesNames(seriesInfo: SubjectSeriesInfo, subject: SubjectInfo): SubjectSeriesInfo {
    val entry = lookupMapEntry(subject.subjectId) ?: return seriesInfo
    val names = regionalSeriesNames(entry, subject.allNames, seriesInfo)
    if (names.series.isEmpty() && names.sequels.isEmpty()) return seriesInfo
    return seriesInfo.copy(
        sequelSubjectNames = seriesInfo.sequelSubjectNames + names.sequels,
        seriesSubjectNamesWithoutSelf = seriesInfo.seriesSubjectNamesWithoutSelf + names.series,
    )
}

/** 系列里别的季的港台名字: [series] 是全部别的季的, [sequels] 是其中续集的. */
internal class RegionalSeriesNames(val series: Set<String>, val sequels: Set<String>)

/**
 * 系列里别的季的港台名字, 与 [regionalSubjectAliases] 一一对应: 别的季的 Bangumi 名字带季号就是译名补上那个季号
 * (「Season 4」「S4」这类写法也认, 补成「第四季」), 完全不带季号的 (第一季、改了名的续作) 是译名本身 ——
 * 本条目自己就用译名本身作别名时 (第一季) 不算. 同样给出简体形式. 只对剧集: 电影的译名只是它自己的.
 *
 * 续集的名字被本条目的名字 (含港台别名) 包含时不算: 选择器对拿不到条目名的 BT 资源按「标题含续集名」排除,
 * 光译名 (「碧藍之海」) 是本季每条资源的标题里都有的, 会把本季的资源全当成续集. 与 [SubjectSeriesInfo.compute] 对 Bangumi 续集名的处理相同.
 */
internal fun regionalSeriesNames(entry: TmdbSubjectMapEntry, subjectNames: List<String>, seriesInfo: SubjectSeriesInfo): RegionalSeriesNames {
    if (entry.backdrop?.type != TYPE_TV) return RegionalSeriesNames(emptySet(), emptySet())
    val own = regionalSubjectAliases(entry, subjectNames, seriesInfo.seasonSort).toSet()
    fun derive(names: Collection<String>): Set<String> = buildSet {
        for (regional in orderedRegionalNames(entry)) {
            if (SEASON_MARKER.containsMatchIn(regional)) continue
            for (name in names) {
                val numbers = SplitSeason.seasonNumbersOf(name)
                val marker = SEASON_MARKER.find(name)?.value ?: numbers.singleOrNull()?.let(::chineseSeasonMarker)
                when {
                    marker != null -> add("$regional $marker")
                    numbers.isEmpty() -> add(regional)
                    // 写了几个季号, 分不清是哪一季: 不加, 光译名会把每一季都算进来
                }
            }
        }
    }.flatMapTo(HashSet()) { listOf(it, it.toSimplifiedChinese()) }.filterTo(HashSet()) { it !in own }
    val ownNames = (subjectNames + own).flatMapTo(HashSet()) { listOf(it, it.toSimplifiedChinese()) }
    val sequels = derive(seriesInfo.sequelSubjectNames).filterTo(HashSet()) { sequel ->
        ownNames.none { MediaListFilters.specialContains(it, sequel) }
    }
    return RegionalSeriesNames(derive(seriesInfo.seriesSubjectNamesWithoutSelf), sequels)
}

/** 「第N季」, 十以内用汉字 (港台站点多这样写). */
private fun chineseSeasonMarker(season: Int): String =
    "第${CHINESE_NUMBERS.getOrNull(season - 1) ?: season.toString()}季"

private val CHINESE_NUMBERS = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

private suspend fun KoinComponent.lookupMapEntry(subjectId: Int): TmdbSubjectMapEntry? {
    if (subjectId <= 0) return null
    val map = getKoin().getOrNull<TmdbSubjectMapRepository>() ?: return null
    return map.lookup(subjectId)?.takeIf { it.regionalNames.isNotEmpty() }
}

/**
 * 搜索用的港台译名, 只有一个 (台湾的优先): 条目名字带季号时补上季号, 只搜到这一季.
 * 只搜一次是因为用它搜到的繁体条目名对不上 Bangumi 的名字, 关键词链认不出已经搜到, 再给一个就会对同一个站再搜一遍.
 * 合集的名字是整个系列电影的总称, 不用.
 */
internal fun regionalSearchName(entry: TmdbSubjectMapEntry, subjectNames: List<String>): String? {
    val type = entry.backdrop?.type
    if (type == TYPE_COLLECTION) return null
    val name = orderedRegionalNames(entry).firstOrNull() ?: return null
    val marker = if (type == TYPE_TV && !SEASON_MARKER.containsMatchIn(name)) seasonMarkerOf(subjectNames) else null
    return if (marker != null) "$name $marker" else name
}

/**
 * 当作条目别名的港台译名:
 * - 电影, 或译名自带季号: 原样.
 * - 条目名字带季号 (「药屋少女的呢喃 第二季」): 译名补上这个季号 (「藥師少女的獨語 第二季」).
 * - 系列的第一季, 或不属于系列: 原样.
 * - 后面的季又没有季号 (「更多 出包王女」): 不用, 否则系列名会让第一季的资源也算作本季.
 * - 合集: 不用 (同上, 总称对得上系列里每一部).
 *
 * 选择器在安卓上把资源的条目名转成简体再与别名比 (`MediaListFilters.ContainsSubjectName`), 所以同时给出简体形式.
 *
 * @param seasonSort 条目是系列中的第几季, 见 [SubjectSeriesInfo.seasonSort]
 */
internal fun regionalSubjectAliases(entry: TmdbSubjectMapEntry, subjectNames: List<String>, seasonSort: Int): List<String> {
    val type = entry.backdrop?.type ?: return emptyList()
    if (type == TYPE_COLLECTION) return emptyList()
    val marker = if (type == TYPE_TV) seasonMarkerOf(subjectNames) else null
    val names = orderedRegionalNames(entry).mapNotNull { name ->
        when {
            type != TYPE_TV || SEASON_MARKER.containsMatchIn(name) -> name
            marker != null -> "$name $marker"
            seasonSort <= 1 -> name
            else -> null
        }
    }
    return names.flatMap { listOf(it, it.toSimplifiedChinese()) }.distinct()
}

private fun orderedRegionalNames(entry: TmdbSubjectMapEntry): List<String> =
    REGIONS.mapNotNull { entry.regionalNames[it] } + entry.regionalNames.filterKeys { it !in REGIONS }.values

/** 条目名字里的季号, 用「第X季」的优先 (港台站点多这样写); 没有返回 null. */
private fun seasonMarkerOf(subjectNames: List<String>): String? {
    val markers = subjectNames.mapNotNull { SEASON_MARKER.find(it)?.value }
    return markers.firstOrNull { it.endsWith('季') } ?: markers.firstOrNull()
}

private val REGIONS = listOf("tw", "hk")
private const val TYPE_TV = "tv"
private const val TYPE_COLLECTION = "collection"

/** 「第二季」「第2期」「第三部」. */
private val SEASON_MARKER = Regex("""第\s*[一二三四五六七八九十\d]+\s*[季期部]""")
