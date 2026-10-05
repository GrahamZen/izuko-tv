/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaPreference.Companion.ANY_FILTER
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.media.selector.SubtitleKindPreference
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.mediasource.MediaListFilter
import me.him188.ani.app.domain.mediasource.MediaListFilterContext
import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.mediasource.StringMatcher
import me.him188.ani.app.domain.mediasource.asCandidate
import me.him188.ani.app.domain.mediasource.toSimplifiedChinese
import me.him188.ani.app.domain.mediasource.codec.MediaSourceTier
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.isLocalCache
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.contains
import me.him188.ani.datasources.api.topic.isSingleEpisode
import me.him188.ani.utils.coroutines.flows.sequenceOfEmptyString
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.app.data.models.episode.displayName

/**
 * [me.him188.ani.app.domain.media.selector.MediaSelector] 的过滤和排序算法实现
 */
class MediaSelectorFilterSortAlgorithm {
    ///////////////////////////////////////////////////////////////////////////
    // 过滤
    ///////////////////////////////////////////////////////////////////////////

    /**
     * 过滤掉 [MediaSelectorSettings] 指定的内容. 例如过滤生肉, 对于完结番过滤掉单集.
     *
     * 第 0 条规则按当前剧集裁剪: 数据源返回条目下的全部资源, 不属于 [MediaSelectorContext.episodeInfo] 的资源
     * 以 [MediaExclusionReason.EpisodeMismatch] 排除. 剧集信息尚未加载时不按集裁剪.
     */
    /**
     * @param memo 逐条筛选结果的记忆表 (key = [Media] 本身); 传入即启用增量计算.
     *
     * 为什么需要它: 搜索期间资源列表是**逐源追加**的 (MediaSourceMediaFetcher 的 runningFold),
     * 每追加一次就会整表重算一遍, 而单条的代价并不低 (要跟条目的全部别名做包含/相似度匹配).
     * 真机实测 (2026-08-29): 127 条要 280~1238ms, 三秒内被触发九次 —— CPU 打满, 界面卡死好几秒.
     * 而**同一条 media 在 (偏好, 设置, context) 不变时结果是确定的**, 所以只算新增的那些.
     * 调用方负责在这三者变化时清空本表.
     */
    fun filterMediaList(
        list: List<Media>,
        preference: MediaPreference,
        settings: MediaSelectorSettings,
        context: MediaSelectorContext,
        memo: MutableMap<Media, MaybeExcludedMedia>? = null,
    ): List<MaybeExcludedMedia> =
        filterMediaList(list, preference, settings, context, matchEpisode = true, memo = memo)

    /**
     * 与 [filterMediaList] 相同, 但不应用第 0 条规则 [MediaExclusionReason.EpisodeMismatch],
     * 得到不按当前剧集裁剪的条目级候选. 供批量下载规划使用.
     */
    fun filterMediaListForSubject(
        list: List<Media>,
        preference: MediaPreference,
        settings: MediaSelectorSettings,
        context: MediaSelectorContext,
    ): List<MaybeExcludedMedia> = filterMediaList(list, preference, settings, context, matchEpisode = false)

    private fun filterMediaList(
        list: List<Media>,
        preference: MediaPreference,
        settings: MediaSelectorSettings,
        context: MediaSelectorContext,
        matchEpisode: Boolean,
        memo: MutableMap<Media, MaybeExcludedMedia>? = null,
    ): List<MaybeExcludedMedia> {
        val subjectInfo = context.subjectInfo?.takeIf { info ->
            info != SubjectInfo.Empty && info.allNames.any { it.isNotBlank() }
        }
        val episodeInfo = context.episodeInfo.takeIf { context.hasEpisode }

        // 条目名去掉季号后的形式, 用于识别站点把整个系列放在一个不带季号的条目下的情况.
        // 每条资源都要与它比, 所以在这里算一次.
        val seasonlessSubjectNames = subjectInfo?.allNames
            ?.mapNotNullTo(HashSet()) { MediaListFilters.removeSeasonMarkerOrNull(it) }
            .orEmpty()

        val excludedAlliances = compileAlliancePatterns(preference.excludedAlliancePatterns)

        val episodeMatch = if (matchEpisode && episodeInfo != null) {
            EpisodeMatch(
                episodeId = episodeInfo.episodeId,
                sort = episodeInfo.sort,
                ep = episodeInfo.ep,
                name = episodeInfo.displayName,
                // OVA 条目在网页源上通常与正篇在同一页面, 剧集名为 "OVA" 或 "OVA2", 无法对应到条目的集数.
                acceptOva = context.subjectInfo?.allNames.orEmpty().any { it.matches(REGEX_OVA_TAILING) },
            )
        } else null

        // 拆分季的后半: 站点页面可能合并整季或接着前半编号, 按页面的编号方式对集
        val splitSeasonMatcher = if (episodeMatch != null) SplitSeasonEpisodeMatcher.create(context, list) else null

        val mediaListFilterContext = if (subjectInfo != null && episodeInfo != null) {
            MediaListFilterContext(
                subjectNames = subjectInfo.allNames.toSet(),
                episodeSort = episodeInfo.sort,
                episodeEp = episodeInfo.ep,
                episodeName = episodeInfo.name,
            )
        } else null
        // 属于本季的页面可能用其他段的名字或季名做标题, 对它们把这些名字也算作当前条目的名字
        val seasonFilterContext = if (mediaListFilterContext != null && splitSeasonMatcher != null) {
            MediaListFilterContext(
                subjectNames = mediaListFilterContext.subjectNames + splitSeasonMatcher.seasonNames,
                episodeSort = mediaListFilterContext.episodeSort,
                episodeEp = mediaListFilterContext.episodeEp,
                episodeName = mediaListFilterContext.episodeName,
            )
        } else null

        val subjectNames = NormalizedNames(context.subjectInfo?.allNames.orEmpty())
        val seriesSubjectNames = NormalizedNames(context.subjectSeriesInfo?.seriesSubjectNamesWithoutSelf.orEmpty().toList())

        return list.map { media ->
            if (memo == null) {
                filterMedia(
                    media, preference, settings, context, mediaListFilterContext, seasonFilterContext, episodeMatch, splitSeasonMatcher,
                    subjectNames, seriesSubjectNames, seasonlessSubjectNames, excludedAlliances,
                )
            } else {
                // **键必须是 media 本身而不是 mediaId**: 同一个 mediaId 可能对应内容不同的两条
                // (例如同一资源的缓存版与原版), 按 id 记忆会把先算的那条的结果错配给后一条
                memo.getOrPut(media) {
                    filterMedia(
                        media, preference, settings, context, mediaListFilterContext, seasonFilterContext, episodeMatch, splitSeasonMatcher,
                        subjectNames, seriesSubjectNames, seasonlessSubjectNames, excludedAlliances,
                    )
                }
            }
        }
    }

    /**
     * 一组名称及其经 [MediaListFilters.normalizeForCompare] 处理后的形式, 每次过滤创建一次.
     * 列表里每个资源都要与同一组名称逐个 [MediaListFilters.specialEquals], 预先处理名称一侧, 免得每个资源都对它们重复执行正则.
     */
    private class NormalizedNames(val names: List<String>) {
        private val normalized = names.map { MediaListFilters.normalizeForCompare(it) }

        /**
         * 按顺序返回第一个与 [normalizedNames] 中任一相等的原名称. [normalizedNames] 须已经过 [MediaListFilters.normalizeForCompare],
         * 结果与逐个 [MediaListFilters.specialEquals] 相同.
         */
        fun firstEqualOrNull(vararg normalizedNames: String): String? {
            for (i in names.indices) {
                if (normalizedNames.any { normalized[i].equals(it, ignoreCase = true) }) return names[i]
            }
            return null
        }

        fun anyEquals(normalizedName: String): Boolean = firstEqualOrNull(normalizedName) != null
    }

    @Suppress("PrivatePropertyName")
    /**
     * 当前剧集的匹配条件: 剧集范围包含 sort 或 ep; 特别篇等非正片的资源常常解析不出序号, 标题包含剧集名也算匹配 (#1738).
     * 数据源的资源允许特别篇按序号匹配同号的正片 (站点常把特别篇按正片连续编号);
     * 本地缓存记录的剧集是确定的: 记录了剧集 ID 就按 ID 匹配, 否则只按记录的那一集精确匹配, 免得看 SP01 时自动选中第 01 话的缓存.
     */
    private class EpisodeMatch(
        private val episodeId: Int,
        private val sort: EpisodeSort,
        private val ep: EpisodeSort?,
        name: String,
        private val acceptOva: Boolean,
    ) {
        // 太短的名字 (如 "OP", "ED") 会匹配到所有含 NCOP 之类字样的资源, 不用
        private val nameForSpecial: String? = name.trim().takeIf { sort !is EpisodeSort.Normal && it.length >= MIN_SPECIAL_NAME_LENGTH }

        fun matches(media: Media): Boolean {
            val range = media.episodeRange
            if (media.isLocalCache()) {
                val cacheEpisodeId = (media as? CachedMedia)?.cacheEpisodeId
                if (!cacheEpisodeId.isNullOrEmpty() && episodeId != 0) return cacheEpisodeId == episodeId.toString()
                return range != null && (
                        range.contains(sort, allowSeason = false, allowSpecial = false) ||
                                (ep != null && range.contains(ep, allowSeason = false, allowSpecial = false)))
            }
            if (range != null) {
                if (range.contains(sort)) return true
                if (ep != null && range.contains(ep)) return true
                if (acceptOva && range.knownSorts.any { it is EpisodeSort.Special && it.type == EpisodeType.OVA }) return true
            }
            if (nameForSpecial != null && MediaListFilters.specialContains(media.originalTitle, nameForSpecial)) return true
            return false
        }
    }

    /**
     * 用户填的字幕组正则 ([MediaPreference.excludedAlliancePatterns]). 空白的忽略 (空串会匹配所有字幕组),
     * 不是合法正则的按字面匹配 (设置里随手写的括号之类不该让选源出错). 每条再加上它的简体写法, 与字幕组名的简体写法比 (繁简都认).
     */
    private fun compileAlliancePatterns(patterns: List<String>?): List<Regex> =
        patterns.orEmpty().flatMap { pattern ->
            val trimmed = pattern.trim().takeIf { it.isNotEmpty() } ?: return@flatMap emptyList()
            setOf(trimmed, trimmed.toSimplifiedChinese()).map { p -> runCatching { Regex(p) }.getOrElse { Regex.fromLiteral(p) } }
        }

    private val SEASON_TAILING = Regex("""第\s*(?<season>.+)\s*[部季]""")

    @Suppress("PrivatePropertyName")
    private val REGEX_OVA_TAILING = Regex(".+OVA\\s*\\d*$", RegexOption.IGNORE_CASE)

    /**
     * 过滤 media，决定是否包含此它。返回的 [MaybeExcludedMedia] 可以是包含，也可以是排除。排除时会携带原因
     */
    private fun filterMedia(
        media: Media,
        preference: MediaPreference,
        settings: MediaSelectorSettings,
        context: MediaSelectorContext,
        mediaListFilterContext: MediaListFilterContext?,
        seasonFilterContext: MediaListFilterContext?,
        episodeMatch: EpisodeMatch?,
        splitSeasonMatcher: SplitSeasonEpisodeMatcher?,
        subjectNames: NormalizedNames,
        seriesSubjectNames: NormalizedNames,
        seasonlessSubjectNames: Set<String>,
        excludedAlliances: List<Regex>,
    ): MaybeExcludedMedia {
        val mediaSubjectName = media.properties.subjectName
        val mediaSubjectNameOrOriginalTitle = mediaSubjectName ?: media.originalTitle
        val splitSeasonMatch = splitSeasonMatcher?.match(media)
        // 大部分资源在前面的规则就被排除了, 用到时才处理
        val normalizedMediaSubjectName by lazy(LazyThreadSafetyMode.NONE) {
            MediaListFilters.normalizeForCompare(mediaSubjectNameOrOriginalTitle)
        }

        // 系列内序号 ([EpisodeInfo.sort]) 在整个系列中唯一, 与条目内序号 ([EpisodeInfo.ep]) 不同时,
        // 命中它足以确定资源属于本条目的这一集, 即使资源的条目名是系列名而非本季的名字.
        // 站点把整个系列放在一个不带季号的条目下、按系列内序号连续编集时就是这样: 站内 "凡人修仙传"
        // 的第 159 集就是 "凡人修仙传 第四季" 的第 35 集.
        val matchedBySeriesEpisodeSort = run {
            val episodeInfo = context.episodeInfo.takeIf { context.hasEpisode } ?: return@run false
            val ep = episodeInfo.ep ?: return@run false
            val range = media.episodeRange ?: return@run false
            episodeInfo.sort != ep && episodeInfo.sort in range
        }

        // 资源的条目名正是本条目去掉季号后的系列名 (站内 "凡人修仙传" 对本条目 "凡人修仙传 第四季").
        // 只靠序号不够: 无关的长篇连载同样会有这个序号, 名字这一侧必须对上.
        val matchedAsSeasonlessSeries = matchedBySeriesEpisodeSort
                && seasonlessSubjectNames.isNotEmpty()
                && MediaListFilters.nameForSeasonlessCompare(mediaSubjectNameOrOriginalTitle) in seasonlessSubjectNames

        // 由下面实现调用, 方便创建 MaybeExcludedMedia
        fun include(): MaybeExcludedMedia {
            return MaybeExcludedMedia.Included(
                media,
                metadata = calculateMatchMetadata(
                    subjectNames,
                    mediaSubjectNameOrOriginalTitle,
                    normalizedMediaSubjectName,
                    media.episodeRange,
                    context.episodeInfo?.sort,
                    context.episodeInfo?.ep,
                    splitSeasonMatch,
                    matchedAsSeasonlessSeries,
                ),
            )
        }

        fun exclude(reason: MediaExclusionReason): MaybeExcludedMedia = MaybeExcludedMedia.Excluded(media, reason)

        // 第 0 条: 先于本地缓存豁免, 否则看第 2 话时会自动选中第 1 话的缓存.
        if (episodeMatch != null) {
            if (splitSeasonMatch?.pageKind == SplitSeasonPageMatcher.PageKind.OTHER_SEASON) {
                return exclude(MediaExclusionReason.FromSeriesSeason)
            }
            val matches = splitSeasonMatch?.matched ?: episodeMatch.matches(media)
            if (!matches) return exclude(MediaExclusionReason.EpisodeMismatch(media.episodeRange))
        }

                if (media.isLocalCache()) {
            // 缓存还没下完 (web m3u8 分段没下全) 时**显示但不可选**: 直接藏掉的话用户看不出
            // "我明明缓存过", 而放它进正常列表又会被自动选中并播放失败.
            // 判据来自实时流, 下完那一刻本 context 重新 emit, 本次筛选重算, 警告自动消失.
            if (context.unplayableCacheMediaIds?.contains(media.mediaId) == true) {
                return exclude(MediaExclusionReason.CacheNotReady)
            }
            return include() // 本地缓存总是要显示
        }

        // 用户排除的字幕组: 放在其他规则前, 原因就写这一条 (用户自己设的, 一看就懂).
        // 繁简写法都认: 有的数据源给的字幕组名是繁体 (「愛戀字幕社」), 用户多半只写一种
        if (excludedAlliances.isNotEmpty()) {
            val alliance = media.properties.alliance
            val simplified = alliance.toSimplifiedChinese()
            if (excludedAlliances.any { it.containsMatchIn(alliance) || it.containsMatchIn(simplified) }) {
                return exclude(MediaExclusionReason.ExcludedAlliance)
            }
        }

        if (settings.hideSingleEpisodeForCompleted
            && context.subjectFinished == true // 还未加载到剧集信息时, 先显示
            && media.kind == MediaSourceKind.BitTorrent
        ) {
            // 完结番隐藏单集资源
            val range = media.episodeRange
                ?: return exclude(MediaExclusionReason.SingleEpisodeForCompleteSubject(episodeRange = null))
            if (range.isSingleEpisode()) return exclude(
                MediaExclusionReason.SingleEpisodeForCompleteSubject(episodeRange = range),
            )
        }

        if (!preference.showWithoutSubtitle &&
            (media.properties.subtitleLanguageIds.isEmpty() && media.extraFiles.subtitles.isEmpty())
        ) {
            // 不显示无字幕的
            return exclude(MediaExclusionReason.MediaWithoutSubtitle)
        }

        val subtitleKind = media.properties.subtitleKind
        if (context.subtitlePreferences != null && subtitleKind != null) {
            if (context.subtitlePreferences[subtitleKind] == SubtitleKindPreference.HIDE) {
                return exclude(MediaExclusionReason.UnsupportedByPlatformPlayer)
            }
        }

        if (splitSeasonMatch?.pageKind == SplitSeasonPageMatcher.PageKind.SEASON) {
            // 本季其他段的页面或整季的合并页, 已经按季内序号对上了当前集, 不按其他季度排除
        } else if (mediaSubjectName != null) {
            // 数据源可以准确拿到条目名称, 我们采用 specialEquals

            // 首先检查数据源条目名是否与当前条目名称相同.
            // 只有在条目名称不相同的情况下, 才可以考虑续集, 因为续集可能只比前传多一个特殊字符, 能通过 specialEquals.
            if (subjectNames.anyEquals(normalizedMediaSubjectName)) {
                // contextSubjectNames 与条目名称相同, 肯定不能排除它
            } else if (!matchedAsSeasonlessSeries) {
                // 以系列内序号认领的资源就是本条目的这一集, 条目名是系列名而非本季的名字也不改变这一点.
                // 简化数据源结果的季度名称，例如从 "Re：从零开始的休息时间 第2季" 变成 "Re：从零开始的休息时间 2"
                // 条目名称可能是上述后者简化的形式, 但数据源的结果是前者完整版的形式
                // 额外判断一次简化的名称可以正确地排除掉类似这种情况的其他季度的资源.
                if (seriesSubjectNames.names.isNotEmpty()) {
                    // 替换串用序号: 按名字引用 ${season} 要 Android 8.0 起才认 (7.1 兼容包)
                    val mediaSubjectNameSeasonSimplified = mediaSubjectName.replace(SEASON_TAILING, "\$1")
                    val seriesName = seriesSubjectNames.firstEqualOrNull(
                        normalizedMediaSubjectName,
                        MediaListFilters.normalizeForCompare(mediaSubjectNameSeasonSimplified),
                    )
                    if (seriesName != null) {
                        // 排除特殊字符后精确匹配到了是其他季度的名称.
                        //
                        // 注意: 这里也不可以改成用 edit-distance 模糊匹配, 因为
                        // 有些条目可能就只差距一个字母, 例如 "天降之物" 和 "天降之物f", 非常容易满足模糊匹配.

                        // 优化一下类型, 以通过一些现有 test. 在实际选择效果上这两个原因是没什么区别的, 都是排除.
                        return if (context.subjectSeriesInfo?.sequelSubjectNames?.contains(seriesName) == true) {
                            exclude(MediaExclusionReason.FromSequelSeason)
                        } else {
                            exclude(MediaExclusionReason.FromSeriesSeason)
                        }
                    }
                }
                // seriesSubjectNamesWithoutSelf 包括了 sequel, 所以我们不需要再考虑 sequel 了. 
            }
        } else {
            // 不可以拿到条目名称, 只做保守排除

            // 如果续集名称与当前条目名称相同, 说明是续集
            context.subjectSeriesInfo?.sequelSubjectNames?.forEach { sequelName ->
                if (sequelName.isNotBlank() &&
                    // 注意: 这里不可以使用 specialContains, 因为续集可能比前传只多一个特殊字符. See #1912
                    mediaSubjectNameOrOriginalTitle.contains(sequelName, ignoreCase = true)
                ) {
                    // 是其他季度
                    return exclude(MediaExclusionReason.FromSeriesSeason)
                }
            }
        }

        if (mediaListFilterContext != null) {
            val allow = when (media.kind) {
                MediaSourceKind.WEB -> {
                    val nameMatches = with(MediaListFilters.ContainsSubjectName) {
                        // 本季的页面按去掉分段标记的页名, 与本季各段的名字和季名匹配
                        val subjectNameForMatching = splitSeasonMatch?.subjectNameForMatching
                        val filterContext = if (subjectNameForMatching != null && seasonFilterContext != null) seasonFilterContext else mediaListFilterContext
                        val baseContains = filterContext.applyOn(
                            object : MediaListFilter.Candidate by media.asCandidate() {
                                override val subjectName: String get() = subjectNameForMatching ?: mediaSubjectNameOrOriginalTitle
                            },
                        )
                        if (media.episodeRange?.contains(EpisodeSort("OVA")) == true) {
                            // 如果数据源搜到了 OVA 剧集, 那就额外匹配一次 subject name 加上 "OVA" 的情况.
                            val propName = media.properties.subjectName ?: return@with false
                            val ovaContains = mediaListFilterContext.applyOn(
                                object : MediaListFilter.Candidate by media.asCandidate() {
                                    override val subjectName: String get() = "$propName OVA"
                                },
                            )
                            baseContains || ovaContains
                        } else {
                            baseContains
                        }
                    }
                    nameMatches || matchedAsSeasonlessSeries
                }

                MediaSourceKind.BitTorrent -> true
                MediaSourceKind.LocalCache -> true
            }

            if (!allow) {
                return exclude(MediaExclusionReason.SubjectNameMismatch)
            }
        }

        return include()
    }

    private fun calculateMatchMetadata(
        contextSubjectNames: NormalizedNames,
        mediaSubjectName: String,
        normalizedMediaSubjectName: String,
        mediaEpisodeRange: EpisodeRange?,
        contextEpisodeSort: EpisodeSort?,
        contextEpisodeEp: EpisodeSort?,
        splitSeasonMatch: SplitSeasonEpisodeMatcher.Result?,
        /**
         * 资源的条目名是本条目去掉季号后的系列名, 且由系列内序号认领了本条目的这一集.
         * 名字与本条目的名字不同, 但指向的就是本条目, 按精确匹配对待.
         */
        matchedAsSeasonlessSeries: Boolean,
    ) = MatchMetadata(
        subjectMatchKind = if (splitSeasonMatch?.exact == true || matchedAsSeasonlessSeries || contextSubjectNames.anyEquals(normalizedMediaSubjectName)) {
            MatchMetadata.SubjectMatchKind.EXACT
        } else {
            MatchMetadata.SubjectMatchKind.FUZZY
        },
        episodeMatchKind = if (splitSeasonMatch != null) {
            splitSeasonMatch.episodeMatchKind
        } else if (mediaEpisodeRange != null) {
            when {
                contextEpisodeSort != null && contextEpisodeSort in mediaEpisodeRange -> {
                    MatchMetadata.EpisodeMatchKind.SORT
                }

                contextEpisodeEp != null && contextEpisodeEp in mediaEpisodeRange -> {
                    MatchMetadata.EpisodeMatchKind.EP
                }

                else -> {
                    MatchMetadata.EpisodeMatchKind.NONE
                }
            }
        } else {
            MatchMetadata.EpisodeMatchKind.NONE
        },
        similarity = (contextSubjectNames.names.asSequence() + sequenceOfEmptyString())
            .map { StringMatcher.calculateMatchRate(it, mediaSubjectName) }
            .max(),
    )

    ///////////////////////////////////////////////////////////////////////////
    // 排序
    ///////////////////////////////////////////////////////////////////////////

    /**
     * 将 [list] 排序
     */
    @OptIn(UnsafeOriginalMediaAccess::class)
    fun sortMediaList(
        list: List<MaybeExcludedMedia>,
        settings: MediaSelectorSettings,
        context: MediaSelectorContext,
    ): List<MaybeExcludedMedia> {
        return list.sortedWith(
            // stable sort, 保证相同的元素顺序不变
            compareBy<MaybeExcludedMedia> { 0 } // dummy, to use .then* syntax.
                // 排除的总是在最后
                .thenBy { maybe ->
                    when (maybe) {
                        is MaybeExcludedMedia.Included -> 0
                        is MaybeExcludedMedia.Excluded -> 1
                    }
                }
                // 将不能播放的放到后面
                .thenBy { maybe ->
                    val subtitleKind = maybe.original.properties.subtitleKind
                    if (context.subtitlePreferences != null && subtitleKind != null) {
                        if (context.subtitlePreferences[subtitleKind] != SubtitleKindPreference.NORMAL) {
                            return@thenBy 1
                        }
                    }
                    0
                }
                // 按符合用户选择类型排序. 缓存 > 用户偏好的 > 不偏好的, #1522
                .thenByDescending { maybe ->
                    when (maybe.original.kind) {
                        // Show cache on top
                        MediaSourceKind.LocalCache -> {
                            2
                        }

                        MediaSourceKind.WEB,
                        MediaSourceKind.BitTorrent -> {
                            if (settings.preferKind == null) {
                                0
                            } else {
                                if (maybe.original.kind == settings.preferKind) {
                                    1
                                } else {
                                    0
                                }
                            }
                        }
                    }
                }
                .then(
                    compareBy { it.original.costForDownload },
                )
                .thenBy { maybe ->
                    val tiers = context.mediaSourceTiers
                    // channel (alliance) 级 tier 优先, 否则数据源级 tier
                    tiers?.get(maybe.original.mediaSourceId, maybe.original.properties.alliance)
                        ?: MediaSourceTier.MaximumValue // 还没加载出来, 先不排序
                }
                .thenByDescending {
                    it.original.publishedTime
                }
                .thenByDescending {
                    // 相似度越高, 排序越前
                    when (it) {
                        is MaybeExcludedMedia.Excluded -> 0
                        is MaybeExcludedMedia.Included -> it.similarity
                    }
                },
        )
    }

    private val Media.costForDownload
        get() = when (location) {
            MediaSourceLocation.Local -> 0
            MediaSourceLocation.Lan -> 1
            else -> 2
        }

    ///////////////////////////////////////////////////////////////////////////
    // Preference
    ///////////////////////////////////////////////////////////////////////////

    // 只是在本次显示中使用
    fun filterByPreference(
        mediaList: List<MaybeExcludedMedia>,
        mergedPreferences: MediaPreference,
    ): List<MaybeExcludedMedia> {
        infix fun <Pref : Any> Pref?.matches(prop: Pref): Boolean =
            this == null || this == prop || this == ANY_FILTER

        infix fun <Pref : Any> Pref?.matches(prop: List<Pref>): Boolean =
            this == null || this in prop || this == ANY_FILTER

        /**
         * 当 [it] 满足当前筛选条件时返回 `true`.
         */
        fun filterCandidate(it: Media): Boolean {
            if (it.isLocalCache()) {
                return true // always show local, so that [makeDefaultSelection] will select a local one
            }

            return mergedPreferences.alliance matches it.properties.alliance &&
                    mergedPreferences.resolution matches it.properties.resolution &&
                    mergedPreferences.subtitleLanguageId matches it.properties.subtitleLanguageIds &&
                    mergedPreferences.mediaSourceId matches it.mediaSourceId
        }

        return mediaList.filter {
            @OptIn(UnsafeOriginalMediaAccess::class)
            filterCandidate(it.original)
        }
    }
}

/**
 * 特别篇按剧集名匹配资源标题时剧集名的最短长度.
 */
private const val MIN_SPECIAL_NAME_LENGTH = 3
