/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.app.domain.media.selector.filter.SeasonNumberConflict
import me.him188.ani.app.domain.mediasource.MediaListFilter
import me.him188.ani.app.domain.mediasource.MediaListFilterContext
import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.mediasource.MediaSourceEngineHelpers
import me.him188.ani.app.domain.mediasource.web.SelectorAutoMatchConfig
import me.him188.ani.app.domain.mediasource.web.isWeakSearchKeyword
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchNameHints
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 一次查询里非 BT 数据源 (网页、规则、JSON API、直连 API、网盘) 共用的搜索计划:
 * - 拿什么词搜、按什么顺序 ([keywords]): 各源只按自己的写法 ([KeywordStyle]) 把名字改成站点上的搜索词;
 *   按标题原样找的站点 (网盘分享站、网盘的按名字搜, [KeywordStyle.literalTitles]) 另有一套排法;
 * - 网页类站点上的条目 (页名) 是不是这一部 ([identify]): 关键词链搜到这一部才停 ([foundOwn]), 自动匹配只打开可能是这一部的条目 ([pagesToOpen]).
 *   网盘的分享标题与路径杂乱, 按同一组名字另外认 (`TitleIdentity`).
 *
 * 认部的判据与选择器过滤 WEB 资源的名称规则相同 (`MediaSelectorFilterSortAlgorithm`: 名字对得上, 不是系列里别的季), 名字也是同一组:
 * 搜索名加上请求带的 [MediaFetchNameHints.ownNames] (条目服务上的全部名字、港台译名、系列关系推出的「系列名 第N季」).
 * 所以这里认为是这一部的, 选择器不会以名字不符或别季为由排除; 这里认为不是的, 选择器也会排除, 不打开不会少资源.
 */
class SubjectSearchPlan private constructor(private val request: MediaFetchRequest) {
    private val hints: MediaFetchNameHints get() = request.nameHints

    /** 认部用的名字: 搜索名在前, 再是 [MediaFetchNameHints.ownNames]. */
    val ownNames: List<String> = (request.subjectNames + hints.ownNames).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 系列名 ([MediaFetchNameHints.seriesNames]), 长的在前: 按它搜会把整个系列搜出来. */
    val seriesNames: List<String> get() = hints.seriesNames

    /** 同系列别的作品的名字 ([MediaFetchNameHints.otherWorkNames]) */
    val otherWorkNames: List<String> get() = hints.otherWorkNames

    private val nameRules = SelectorNameRules(
        ownNames, hints.otherWorkNames, hints.splitSeason,
        seriesNumbered = request.episodes.any { it.ep != null && it.ep != it.sort } ||
            (request.episodeEp != null && request.episodeEp != request.episodeSort),
    )

    /**
     * 一个数据源的关键词链 ([SearchKeywords]):
     * - 主关键词: 搜索名的前 [KeywordStyle.primaryCount] 个. 只取首词的源 ([KeywordStyle.onlyFirstWord]) 本来就是想搜整个系列的短词,
     *   名字里含系列名 ([MediaFetchNameHints.seriesNames]) 时取系列名的首词: 「继 怪怪守护神」的首词「继」什么都搜得到, 系列名「怪怪守护神」才是;
     *   没有系列名而首词太弱 (一个字、很短的英文) 时用整个名字.
     * - 回退关键词, 依次: 请求的回退关键词 ([MediaFetchRequest.fallbackSearchKeywords]: 带季号的别名、系列名、港台译名)、
     *   被系列名换下的名字自己的首词、名字去掉中日文之间空格的写法 (站点按字面找, 「继 怪怪守护神」搜不到「继怪怪守护神」). 太弱的不要.
     * 改写后相同的词只搜一次. 按标题原样找的站点另见 [literalKeywords]; [subtitles] (数据源知道的这一部的副标题) 只给它们用.
     */
    fun keywords(style: KeywordStyle, sourceId: String? = null, subtitles: List<String> = emptyList()): SearchKeywords {
        val names = request.subjectNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (style.literalTitles) {
            return literalKeywords(names, style.primaryCount, subtitles).also { keywords ->
                logger.info { "Search keywords${sourceId?.let { " of '$it'" }.orEmpty()} for ${names.firstOrNull()}: $keywords" }
            }
        }
        val builder = KeywordsBuilder()
        fun siteKeyword(name: String, onlyFirstWord: Boolean = style.onlyFirstWord) =
            MediaSourceEngineHelpers.getSearchKeyword(name, style.removeSpecial, onlyFirstWord).trim()

        val primaryNames = names.take(style.primaryCount.coerceAtLeast(1))
        val replaced = mutableListOf<String>()
        for (name in primaryNames) {
            val full = siteKeyword(name, onlyFirstWord = false)
            if (!style.onlyFirstWord) {
                builder.primary(name, full)
                continue
            }
            val series = seriesNameIn(name)
            val firstWord = siteKeyword(name)
            when {
                series != null -> {
                    builder.primary(series, siteKeyword(series))
                    replaced += name
                }
                // 首词就是整个名字时不嫌它短 (「K」「Air」本来就叫这个)
                firstWord != full && isWeak(firstWord) -> builder.primary(name, full)
                else -> builder.primary(name, firstWord)
            }
        }
        for (name in request.fallbackSearchKeywords) builder.fallback(name, siteKeyword(name), kindOf(name))
        for (name in replaced) builder.fallback(name, siteKeyword(name), KeywordKind.ALIAS)
        for (name in primaryNames) {
            val joined = TitleForms.withoutSpacesBetweenCjk(name)
            if (joined != name) builder.fallback(joined, siteKeyword(joined, onlyFirstWord = false), KeywordKind.WRITING, writingOf = name)
        }
        return builder.build().also { keywords ->
            logger.info { "Search keywords${sourceId?.let { " of '$it'" }.orEmpty()} for ${names.firstOrNull()}: $keywords" }
        }
    }

    /**
     * 按标题原样找的站点的关键词链: 站点拿整段字符串比标题, 名字里的季号与副标题常与分享上的写法不一样, 所以
     * - 主关键词是各名字的主标题 ([TitleForms.mainTitle]: 去掉季号与副标题), 前 [count] 个, 比较写法 ([TitleForms.compact]) 相同的只留一个;
     * - 回退关键词依次是: 主标题的另外写法 ([KeywordKind.WRITING]: 去掉装饰符号、再去掉中日文之间的空格, 原样搜不到时紧接着它试)、
     *   第一个副标题、最长的系列名 (搜出整个系列, 由数据源挑出这一部)、其余副标题 (繁体、原文的写法)、请求里的别的回退关键词 (港台译名之类) 的主标题.
     *   比较写法与某个主关键词相同的不再搜; 写法以外的都要够长 ([TitleForms.isDistinctive]): 站点按整段找, 短词搜出来的多是别的番.
     */
    private fun literalKeywords(names: List<String>, count: Int, subtitles: List<String>): SearchKeywords {
        val primary = names.map(TitleForms::mainTitle)
            .filter { TitleForms.compact(it).length >= MIN_LITERAL_KEYWORD_LENGTH }
            .distinctBy(TitleForms::compact)
            .take(count.coerceAtLeast(1))
            .map { SearchKeyword(it, it) }
        val searched = primary.mapTo(HashSet()) { TitleForms.compact(it.keyword) }
        val fallback = LinkedHashMap<String, SearchKeyword>()
        fun add(keyword: SearchKeyword) {
            if (keyword.keyword.isBlank() || keyword.keyword in fallback) return
            if (keyword.kind != KeywordKind.WRITING) {
                val compact = TitleForms.compact(keyword.keyword)
                if (!TitleForms.isDistinctive(compact) || !searched.add(compact)) return
            }
            fallback[keyword.keyword] = keyword
        }
        for (name in primary) {
            val plain = TitleForms.withoutDecorations(name.keyword)
            for (writing in listOf(plain, TitleForms.withoutSpacesBetweenCjk(plain))) {
                if (writing != name.keyword) add(SearchKeyword(writing, writing, KeywordKind.WRITING, writingOf = name.name))
            }
        }
        val subtitleKeywords = subtitles.map { SearchKeyword(it, it, KeywordKind.SUBTITLE) }
        subtitleKeywords.take(1).forEach(::add)
        hints.seriesNames.maxByOrNull { it.length }?.let { add(SearchKeyword(it, it, KeywordKind.SERIES)) }
        subtitleKeywords.drop(1).forEach(::add)
        for (name in request.fallbackSearchKeywords) {
            val main = TitleForms.mainTitle(name)
            if (TitleForms.compact(main).length >= MIN_LITERAL_KEYWORD_LENGTH) add(SearchKeyword(main, main, kindOf(name)))
        }
        return SearchKeywords(primary, fallback.values.toList())
    }

    /** 请求的回退关键词 [name] 是系列名还是这一部别的叫法. */
    private fun kindOf(name: String): KeywordKind = if (name in hints.seriesNames) KeywordKind.SERIES else KeywordKind.ALIAS

    /** 名字 [name] 里含的最长的系列名; 没有时为 `null`. */
    private fun seriesNameIn(name: String): String? = hints.seriesNames
        .filter { MediaListFilters.specialContains(name, it) && !MediaListFilters.specialEquals(name, it) }
        .maxByOrNull { it.length }

    /** 按全部动画的名字表认部 ([AnimeNames]); 每次现取, 表在搜索中途读进来时后面的页就用上 */
    private val names: TitleIdentity get() = AnimeNames.identityOf(request)

    /**
     * 站点上叫 [name] 的条目 (页名、分享标题) 与这一部的关系, 见 [SubjectMatch].
     * 名字表认出是别的作品、或只含着本条目太短的名字的不是 ([TitleIdentity.rejectsPageTitle]).
     */
    fun identify(name: String): SubjectMatch =
        if (names.rejectsPageTitle(name)) SubjectMatch.OTHER else nameRules.identify(name)

    /** 搜到的资源里有这一部的 (资源的条目名是站点上的页名): 关键词链搜到这里就不再试回退关键词. */
    fun foundOwn(media: List<DefaultMedia>): Boolean =
        media.any { identify(it.properties.subjectName ?: it.originalTitle) == SubjectMatch.OWN }

    /**
     * 自动匹配要打开的站点条目: 是这一部的在前 ([preferShorterName] 时其中名字短的在前, 搜「第一季」时不先开「第二季」), 说不准的随后 (保持站点的顺序),
     * 不是的不开 (选择器反正会排除, 打开只是多发请求).
     */
    fun <T> pagesToOpen(pages: List<T>, preferShorterName: Boolean, nameOf: (T) -> String): List<T> {
        val judged = pages.map { it to identify(nameOf(it)) }
        val own = judged.filter { it.second == SubjectMatch.OWN }.map { it.first }
            .let { list -> if (preferShorterName) list.sortedBy { nameOf(it).length } else list }
        val maybe = judged.filter { it.second == SubjectMatch.MAYBE }.map { it.first }
        val skipped = judged.filter { it.second == SubjectMatch.OTHER }
        if (skipped.isNotEmpty()) {
            logger.info { "Opening ${own.size + maybe.size} of ${pages.size} pages for ${ownNames.firstOrNull()}, not this subject: ${skipped.take(LOGGED_SKIPPED).map { nameOf(it.first) }}" }
        }
        return own + maybe
    }

    companion object {
        private val logger = logger<SubjectSearchPlan>()
        private const val LOGGED_SKIPPED = 5

        /** 按标题原样找时关键词的比较写法至少几个字: 太短的会搜出一堆无关的 */
        private const val MIN_LITERAL_KEYWORD_LENGTH = 2

        fun of(request: MediaFetchRequest): SubjectSearchPlan = SubjectSearchPlan(request)

        /** 截出来的词太弱: 一个字 (「继」), 或很短的英文 / 数字 ([isWeakSearchKeyword]). */
        internal fun isWeak(keyword: String): Boolean {
            val compact = keyword.filterNot { it.isWhitespace() }
            return compact.length <= 1 || isWeakSearchKeyword(compact)
        }
    }
}

/** 一个数据源怎样把名字改成站点上的搜索词, 前三项含义同 [SelectorAutoMatchConfig] 的同名配置. */
data class KeywordStyle(
    /** 只取名字的第一个词 ([SelectorAutoMatchConfig.searchUseOnlyFirstWord]) */
    val onlyFirstWord: Boolean,
    /** 去掉特殊字符与「剧场版」等标记 ([SelectorAutoMatchConfig.searchRemoveSpecial]) */
    val removeSpecial: Boolean,
    /** 主关键词用几个名字 ([SelectorAutoMatchConfig.searchUseSubjectNamesCount]) */
    val primaryCount: Int,
    /** 站点按整段标题原样找 (网盘分享站、网盘的按名字搜): 关键词按 [SubjectSearchPlan] 的 `literalKeywords` 排, [onlyFirstWord] 与 [removeSpecial] 不用 */
    val literalTitles: Boolean = false,
) {
    companion object {
        /** 按标题原样找的站点, 主关键词用前 [primaryCount] 个名字 */
        fun literal(primaryCount: Int) = KeywordStyle(onlyFirstWord = false, removeSpecial = false, primaryCount = primaryCount, literalTitles = true)
    }
}

fun SelectorAutoMatchConfig.keywordStyle(): KeywordStyle =
    KeywordStyle(onlyFirstWord = searchUseOnlyFirstWord, removeSpecial = searchRemoveSpecial, primaryCount = searchUseSubjectNamesCount)

/**
 * 关键词链上的一个词: 搜索缓存按名字 [name] 记 (同一个源上互不相同), 发给站点的是 [keyword].
 * [kind] 是它从哪来的, 数据源按它决定搜到的条目怎么认 (网盘按副标题搜到的分享标题要同时有系列名与副标题).
 */
data class SearchKeyword(
    val name: String,
    val keyword: String,
    val kind: KeywordKind = KeywordKind.NAME,
    /** [KeywordKind.WRITING] 时是哪个主关键词的写法 (它的 [name]) */
    val writingOf: String? = null,
)

/** 关键词从哪来 ([SubjectSearchPlan.keywords]). */
enum class KeywordKind {
    /** 条目的名字 (主关键词) */
    NAME,

    /** 同一个名字的另一种写法: 去掉装饰符号、中日文之间的空格 */
    WRITING,

    /** 这一部别的叫法: 带季号的别名、港台译名、被系列名换下的名字 */
    ALIAS,

    /** 系列名: 搜出整个系列 */
    SERIES,

    /** 这一部的副标题 (「系列名 + 副标题」的条目, 数据源给的) */
    SUBTITLE,
}

/** 一个数据源的关键词链 (见 [SubjectSearchPlan.keywords]): [primary] 每个都搜, 都没搜到这一部时依次试 [fallback], 搜到为止. */
class SearchKeywords(val primary: List<SearchKeyword>, val fallback: List<SearchKeyword>) {
    val primaryNames: List<String> get() = primary.map { it.name }
    val fallbackNames: List<String> get() = fallback.map { it.name }
    val all: List<SearchKeyword> get() = primary + fallback

    /** 主关键词 [name] 的另外写法 ([KeywordKind.WRITING]) */
    fun writingsOf(name: SearchKeyword): List<SearchKeyword> = fallback.filter { it.kind == KeywordKind.WRITING && it.writingOf == name.name }

    /** 回退关键词里不是主关键词写法的那些 */
    val wider: List<SearchKeyword> get() = fallback.filter { it.kind != KeywordKind.WRITING }

    private val keywordByName = all.associate { it.name to it.keyword }

    /** 名字 [name] 发给站点的搜索词. */
    fun keywordOf(name: String): String = keywordByName[name] ?: name

    override fun toString(): String = "${primary.map { it.keyword }} + fallback ${fallback.map { it.keyword }}"
}

/** 收集关键词: 名字与改写后的词都不重复, 回退关键词里太弱的不要. */
private class KeywordsBuilder {
    private val primary = mutableListOf<SearchKeyword>()
    private val fallback = mutableListOf<SearchKeyword>()
    private val names = HashSet<String>()
    private val keywords = HashSet<String>()

    fun primary(name: String, keyword: String) = add(primary, SearchKeyword(name, keyword))

    fun fallback(name: String, keyword: String, kind: KeywordKind, writingOf: String? = null) {
        if (SubjectSearchPlan.isWeak(keyword)) return
        add(fallback, SearchKeyword(name, keyword, kind, writingOf))
    }

    private fun add(list: MutableList<SearchKeyword>, keyword: SearchKeyword) {
        if (keyword.keyword.isEmpty() || keyword.name in names || !keywords.add(keyword.keyword.lowercase())) return
        names += keyword.name
        list += keyword
    }

    fun build() = SearchKeywords(primary.toList(), fallback.toList())
}

/** 站点上的一个条目与这一部的关系 ([SubjectSearchPlan.identify]). */
enum class SubjectMatch {
    /** 是这一部: 选择器按名字认它, 关键词链搜到它就停. */
    OWN,

    /** 说不准, 要打开看: 整个系列放在一页的条目 (按系列内序号可能有这一集)、拆分季的条目 (站点可能用别的段或整季的名字)、带 OVA 的同名条目. */
    MAYBE,

    /** 不是: 名字对不上, 或是系列里别的季. */
    OTHER,
}

/**
 * 站点上的条目名与这一部的关系, 规则照选择器过滤 WEB 资源 (`MediaSelectorFilterSortAlgorithm`) 对资源条目名的判断:
 * 与本条目某个名字相同的是; 正是本条目去掉季号的系列名、而本条目的集按系列内序号编 ([seriesNumbered]: 有集的 sort 与 ep 不同) 时说不准
 * (站点把整个系列放在一页、按系列内序号编集, 选择器按序号还会认); 季号是别的季 ([SeasonNumberConflict])、与同系列别的作品同名、
 * 写明是真人作品或短剧 ([NotAnimeMarkers]) 的不是;
 * 剩下的名字对得上 ([MediaListFilters.ContainsSubjectName]) 才是. 拆分季的一段 ([splitSeason]) 不判别季, 名字对不上的也说不准.
 */
internal class SelectorNameRules(
    private val ownNames: List<String>,
    otherNames: List<String>,
    private val splitSeason: Boolean,
    private val seriesNumbered: Boolean,
) {
    private val ownNormalized = ownNames.mapTo(HashSet()) { MediaListFilters.normalizeForCompare(it).lowercase() }
    private val otherNormalized = otherNames.mapTo(HashSet()) { MediaListFilters.normalizeForCompare(it).lowercase() }
    private val seasonless = ownNames.mapNotNullTo(HashSet()) { MediaListFilters.removeSeasonMarkerOrNull(it) }
    private val conflict = SeasonNumberConflict.of(ownNames, otherNames)
    private val context = MediaListFilterContext(ownNames.toSet(), EpisodeSort("1"), null, null)
    private val ownNotAnime = ownNames.any(NotAnimeMarkers::isNotAnime)

    fun identify(name: String): SubjectMatch {
        if (ownNames.isEmpty()) return SubjectMatch.MAYBE
        // 真人作品、短剧 (「【2019日影】…」) 不是这一部, 条目自己就是的除外
        if (!ownNotAnime && NotAnimeMarkers.isNotAnime(name)) return SubjectMatch.OTHER
        val normalized = MediaListFilters.normalizeForCompare(name).lowercase()
        if (normalized in ownNormalized) return SubjectMatch.OWN
        if (seriesNumbered && MediaListFilters.nameForSeasonlessCompare(name) in seasonless) return SubjectMatch.MAYBE
        if (!splitSeason) {
            if (conflict?.let { it.isOtherSeason(name) || it.isSeasonlessEarlierSeason(name) } == true) return SubjectMatch.OTHER
            val simplified = MediaListFilters.normalizeForCompare(name.replace(SEASON_TAILING, "\$1")).lowercase()
            if (normalized in otherNormalized || simplified in otherNormalized) return SubjectMatch.OTHER
        }
        if (nameMatches(name)) return SubjectMatch.OWN
        if (nameMatches("$name OVA") || splitSeason) return SubjectMatch.MAYBE
        return SubjectMatch.OTHER
    }

    private fun nameMatches(name: String): Boolean = with(context) {
        MediaListFilters.ContainsSubjectName.applyOn(
            object : MediaListFilter.Candidate {
                override val originalTitle: String get() = name
                override val episodeRange: EpisodeRange? get() = null
            },
        )
    }

    private companion object {
        /** 同选择器: 「XX 第2季」简化成「XX 2」再与别的季的名字比 */
        val SEASON_TAILING = Regex("""第\s*(.+)\s*[部季]""")
    }
}
