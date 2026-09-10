/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.peek
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.RepositoryRateLimitedException
import me.him188.ani.app.domain.mediasource.MediaListFilter
import me.him188.ani.app.domain.mediasource.MediaListFilterContext
import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.mediasource.MediaSourceEngineHelpers
import me.him188.ani.app.domain.mediasource.asCandidate
import me.him188.ani.app.domain.mediasource.web.format.SelectedChannelEpisodes
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormat
import me.him188.ani.app.domain.mediasource.web.format.SelectorFormatConfig
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormat
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.SubtitleKind
import me.him188.ani.datasources.api.matcher.WebVideo
import me.him188.ani.datasources.api.matcher.WebVideoMatcher
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.SubtitleLanguage
import me.him188.ani.datasources.api.topic.contains
import me.him188.ani.datasources.api.topic.titles.LabelFirstRawTitleParser
import me.him188.ani.datasources.api.util.namedGroup
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.jsonpath.JsonPath
import me.him188.ani.utils.jsonpath.compileOrNull
import me.him188.ani.utils.jsonpath.resolveOrNull
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.xml.Document
import me.him188.ani.utils.xml.Element
import me.him188.ani.utils.xml.Html
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * For [SelectorMediaSourceEngine.selectMedia]
 *
 * [episodeSort], [episodeEp] 与 [episodeName] 是发起查询时的当前剧集, 只用于数据源编辑器的测试功能按集过滤.
 */
data class SelectorSearchQuery(
    val subjectName: String,
    val allSubjectNames: Set<String>,
    val episodeSort: EpisodeSort,
    val episodeEp: EpisodeSort?,
    val episodeName: String?,
    /**
     * 用于判断缓存的条目页面是否陈旧的剧集: 页面包含这一集才算命中. 为 `null` 时以当前剧集判断.
     */
    val freshnessProbe: SelectorEpisodeProbe? = null,
    /**
     * 本条目的全部集号 (`sort` 与 `ep` 都算). 非空时, 按集号裁剪产出的范围放宽到**整个条目**.
     *
     * **为什么不能只产出当次那一集**: 播放页的查询会话是按条目复用的 (见
     * `SubjectMediaFetchSessions`), 切集只重建选择器、不重新查询. 产出里只有第一次进来那一集的话,
     * 切到别的集就一条都匹配不上 —— 界面上表现为在线源全部没有结果, 连"正在加载"都不出现.
     *
     * 为 `null` 时退化成只产出 [episodeSort] 那一集 (长番, 见 `SelectorMediaSource.fetch`).
     */
    val subjectEpisodeSorts: Set<EpisodeSort>? = null,
)

/**
 * 判断缓存的条目页面是否包含某一集时使用的剧集信息.
 * @see SelectorSearchQuery.freshnessProbe
 */
data class SelectorEpisodeProbe(
    val episodeSort: EpisodeSort,
    val episodeEp: EpisodeSort?,
    val episodeName: String?,
)

fun SelectorSearchQuery.toFilterContext() = MediaListFilterContext(
    subjectNames = allSubjectNames,
    episodeSort = episodeSort,
    episodeEp = episodeEp,
    episodeName = episodeName,
)

/**
 * 基于 CSS Selector 解析页面的数据源引擎.
 *
 * 解析流程:
 *
 * 1. 搜索条目列表: [SelectorMediaSourceEngine.searchSubjects]
 * 2. 解析条目页面: [SelectorMediaSourceEngine.selectSubjects]
 * 3. 搜索一个条目的剧集列表 [SelectorMediaSourceEngine.searchEpisodes]
 * 4. 解析剧集列表页面 [SelectorMediaSourceEngine.selectEpisodes]
 * 5. 将剧集信息转换为 [Media]: [SelectorMediaSourceEngine.selectMedia]
 */
abstract class SelectorMediaSourceEngine {
    companion object {
        const val CURRENT_VERSION: UInt = 1u

        // single instance to save memory
        private val defaultSubtitleLanguages = listOf(SubtitleLanguage.ChineseSimplified.id)

        internal val logger = logger<SelectorMediaSourceEngine>()
    }

    data class SearchSubjectResult(
        val url: Url,
        /**
         * `null` means 404
         */
        val document: Document?,
        val captchaKind: WebCaptchaKind? = null,
    ) {
        override fun toString(): String = "SearchSubjectResult(url=$url, document=${document?.toString()?.length ?: 0}...)"
    }

    /**
     * 根据给定信息搜索条目列表.
     */
    @Throws(RepositoryException::class, CancellationException::class)
    suspend fun searchSubjects(
        searchUrl: String,
        subjectName: String,
        useOnlyFirstWord: Boolean,
        removeSpecial: Boolean,
    ): SearchSubjectResult {
        val encodedUrl = MediaSourceEngineHelpers.encodeUrlSegment(
            MediaSourceEngineHelpers.getSearchKeyword(subjectName, removeSpecial, useOnlyFirstWord),
        )

        val finalUrl = Url(
            searchUrl.replace("{keyword}", encodedUrl),
        )

        return searchImpl(finalUrl)
    }

    fun parseSearchResult(
        finalUrl: Url,
        html: String,
    ): SearchSubjectResult {
        val captchaKind = WebCaptchaDetector.detect(finalUrl.toString(), html)
        if (captchaKind != null) {
            return SearchSubjectResult(finalUrl, document = null, captchaKind = captchaKind)
        }
        return SearchSubjectResult(finalUrl, document = Html.parse(html))
    }

    fun parseDocument(
        pageUrl: String,
        html: String,
    ): Document {
        val captchaKind = WebCaptchaDetector.detect(pageUrl, html)
        if (captchaKind != null) {
            throw WebPageCaptchaException(pageUrl, captchaKind)
        }
        return Html.parse(html)
    }

    @Throws(RepositoryException::class, CancellationException::class)
    protected abstract suspend fun searchImpl(
        finalUrl: Url,
    ): SearchSubjectResult

    /**
     * 解析条目搜索结果. 返回该页面的所有条目.
     *
     * @return `null` if config is invalid
     */
    open fun selectSubjects(
        document: Element,
        config: SelectorSearchConfig,
    ): List<WebSearchSubjectInfo>? {
        return selectSubjectsForCaptchaProbe(document, config)
    }

    suspend fun searchEpisodes(
        subjectDetailsPageUrl: String,
    ): Document? = try {
        doHttpGet(subjectDetailsPageUrl)
    } catch (e: ClientRequestException) {
        e.response.status.let {
            if (it == HttpStatusCode.NotFound) {
                return null
            }
            throw e
        }
    }

    /**
     * @param subjectUrl episode 所属 (来自) 的条目的完整 URL.
     * @return `null` if config is invalid
     */
    fun selectEpisodes(
        subjectDetailsPage: Element,
        subjectUrl: String,
        config: SelectorSearchConfig,
    ): SelectedChannelEpisodes? = selectEpisodesImpl(subjectDetailsPage, subjectUrl, config)

    data class SelectMediaResult(
        val originalList: List<DefaultMedia>,
        val filteredList: List<DefaultMedia>,
    )

    fun selectMedia(
        episodes: Sequence<WebSearchEpisodeInfo>,
        config: SelectorSearchConfig,
        query: SelectorSearchQuery,
        mediaSourceId: String,
        subjectName: String,
    ): SelectMediaResult {
        val originalMediaList = createMedia(episodes.toList(), config, query, mediaSourceId, subjectName) { true }
        return SelectMediaResult(originalMediaList, filterMedia(originalMediaList, config, query))
    }

    /**
     * 与 [selectMedia] 的 [SelectMediaResult.filteredList] 相同, 只是不为注定被按集号过滤掉的剧集创建 [DefaultMedia].
     *
     * 请求普通剧集且数据源按集号过滤时, 过滤器只看集号 ([MediaListFilters.ContainsAnyEpisodeInfo] 对普通剧集不看名称),
     * 集号对不上的剧集可以先跳过. 长番的条目页一页上万集, 最后只留下当前这一集.
     */
    fun selectFilteredMedia(
        episodes: List<WebSearchEpisodeInfo>,
        config: SelectorSearchConfig,
        query: SelectorSearchQuery,
        mediaSourceId: String,
        subjectName: String,
    ): List<DefaultMedia> {
        val bySortOnly = config.filterByEpisodeSort && query.episodeSort is EpisodeSort.Normal
        // 只在按集号过滤时才谈得上"放宽到整个条目": 不按集号过滤时本来就全部产出
        val wholeSubject = query.subjectEpisodeSorts?.takeIf { bySortOnly }
        val mediaList = createMedia(episodes, config, query, mediaSourceId, subjectName) { episodeSort ->
            when {
                !bySortOnly -> true
                // 整个条目的集号都留下, 见 [SelectorSearchQuery.subjectEpisodeSorts]
                wholeSubject != null -> episodeSort in wholeSubject
                else -> EpisodeRange.single(episodeSort).let { range ->
                    range.contains(query.episodeSort) || (query.episodeEp != null && range.contains(query.episodeEp))
                }
            }
        }
        if (wholeSubject != null) {
            // 条目级查询: 这一遍也按"集号属于本条目"筛, 而不是按当次那一集 —— 否则刚产出的别的集又被滤光.
            // 它挡的是集号解析不出来、靠剧集名蒙混进来的行 (同一页上的另一部作品).
            return mediaList.filter { media ->
                media.episodeRange?.let { range -> wholeSubject.any { range.contains(it) } } == true
            }
        }
        return filterMedia(mediaList, config, query)
    }

    /**
     * 把剧集转成 media. [keep] 按集号预先挑选, 不要的剧集不创建对象.
     */
    private fun createMedia(
        episodeList: List<WebSearchEpisodeInfo>,
        config: SelectorSearchConfig,
        query: SelectorSearchQuery,
        mediaSourceId: String,
        subjectName: String,
        keep: (EpisodeSort) -> Boolean,
    ): List<DefaultMedia> {
        val parser = LabelFirstRawTitleParser()
        return episodeList.mapNotNull { info ->
            // 有的站点把 `javascript:` 占位按钮也列进剧集里. WebVideo 只收 http/https, 否则抛异常让整个源作废: 只跳过这一集.
            if (!info.playUrl.startsWith("https://") && !info.playUrl.startsWith("http://")) return@mapNotNull null
            val episodeSort = episodeList.matchingEpisodeSortOf(info, query.episodeSort, query.episodeEp, query.episodeName)
                ?: return@mapNotNull null
            if (!keep(episodeSort)) return@mapNotNull null
            val subtitleLanguages = guessSubtitleLanguages(info, parser)
            DefaultMedia(
                mediaId = buildString {
                    append(mediaSourceId)
                    append(".")
                    if (config.selectMedia.distinguishSubjectName) {
                        append(subjectName)
                        append("-")
                    }
                    if (config.selectMedia.distinguishChannelName) {
                        append(info.channel)
                        append("-")
                    }
                    append(info.name)
                    append("-")
                    append(episodeSort)
                },
                mediaSourceId = mediaSourceId,
                originalUrl = info.playUrl,
                download = ResourceLocation.WebVideo(info.playUrl),
                originalTitle = buildString {
                    if (config.selectMedia.distinguishSubjectName) {
                        append(subjectName)
                        append(" ")
                    }
                    append(info.name)
                },
                publishedTime = 0L,
                properties = MediaProperties(
                    subjectName = subjectName,
                    episodeName = info.name,
                    subtitleLanguageIds = subtitleLanguages ?: listOf(config.defaultSubtitleLanguage.id),
                    resolution = config.defaultResolution.id,
                    alliance = info.channel ?: "",
                    size = FileSize.Unspecified,
                    subtitleKind = SubtitleKind.EMBEDDED,
                ),
                episodeRange = EpisodeRange.single(episodeSort),
                location = MediaSourceLocation.Online,
                kind = MediaSourceKind.WEB,
            )
        }
    }

    private fun filterMedia(
        mediaList: List<DefaultMedia>,
        config: SelectorSearchConfig,
        query: SelectorSearchQuery,
    ): List<DefaultMedia> = with(query.toFilterContext()) {
        val filters = config.createFiltersForEpisode()
        mediaList.filter { filters.applyOn(it.asCandidate()) }
    }

    /**
     * 有的 channel 会叫 "简中" 和 "繁中"
     */
    private fun guessSubtitleLanguages(
        info: WebSearchEpisodeInfo,
        parser: LabelFirstRawTitleParser
    ): List<String>? {
        val languagesFromChannel = info.channel?.let { parser.parseSubtitleLanguages(it) } ?: emptyList()
        val languagesFromName = info.name.let { parser.parseSubtitleLanguages(it) }

        return when {
            languagesFromChannel.isEmpty() && languagesFromName.isEmpty() -> null
            else -> languagesFromChannel.asSequence()
                .plus(languagesFromName)
                .map {
                    it.id
                }
                .toList()
                .ifEmpty {
                    null
                }
        }
    }

    fun shouldLoadPage(url: String, config: SelectorSearchConfig.MatchVideoConfig): Boolean {
        if (config.enableNestedUrl) {
            config.matchNestedUrlRegex?.find(url)?.let {
                return true
            }
        }
        return false
    }

    fun matchWebVideo(url: String, searchConfig: SelectorSearchConfig.MatchVideoConfig): WebVideoMatcher.MatchResult {
        if (shouldLoadPage(url, searchConfig)) {
            return WebVideoMatcher.MatchResult.LoadPage
        }

        val regex = searchConfig.matchVideoUrlRegex ?: return WebVideoMatcher.MatchResult.Continue
        val result = regex.find(url) ?: return WebVideoMatcher.MatchResult.Continue
        val videoUrl = try {
            result.namedGroup(regex, "v")?.value ?: url
        } catch (_: IllegalArgumentException) { // no group
            url
        }

        return WebVideoMatcher.MatchResult.Matched(WebVideo(videoUrl, videoHeaders(searchConfig)))
    }

    private fun videoHeaders(searchConfig: SelectorSearchConfig.MatchVideoConfig) = mapOf(
        "User-Agent" to searchConfig.addHeadersToVideo.userAgent,
        "Referer" to searchConfig.addHeadersToVideo.referer,
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "macOS",
        "Sec-Fetch-Dest" to "video",
        "Sec-Fetch-Mode" to "no-cors",
        "Sec-Fetch-Site" to "cross-site",
    )

    /**
     * 直连取流: 不开 WebView, 按 [config] 请求站点自己的取流接口, 从响应里取出视频地址.
     *
     * 见 [SelectorSearchConfig.ResolveVideoConfig]. 没配置 / 取不到时返回 `null`, 调用方退回 WebView ——
     * **这条路只做加法, 失败一律不抛**, 免得把原本能用 WebView 播的源拖下水.
     */
    suspend fun resolveVideoDirectly(
        pageUrl: String,
        config: SelectorSearchConfig.ResolveVideoConfig,
        matchVideoConfig: SelectorSearchConfig.MatchVideoConfig,
    ): WebVideo? {
        if (!config.enabled) return null

        val variables = buildMap {
            put("pageUrl", pageUrl)
            val regex = config.matchPageUrlRegex
            if (regex != null) {
                val result = regex.find(pageUrl)
                if (result == null) {
                    logger.info { "resolveVideoDirectly: matchPageUrl did not match $pageUrl" }
                    return null
                }
                // 命名分组的名字就是变量名; 名字只能从 pattern 里数 (MatchResult 不暴露分组名),
                // 顺便也避开了 API 26 以下 groups[name] 抛 NoSuchMethodError 的坑
                for (name in namedGroupNames(regex.pattern)) {
                    result.namedGroup(regex, name)?.value?.let { put(name, it) }
                }
            }
        }

        val url = substituteVariables(config.requestUrl, variables)
        val body = substituteVariables(config.requestBody, variables)
        val headers = config.requestHeaders.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(':') }
            .map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            .map { (name, value) -> name to substituteVariables(value, variables) }
            .toList()

        val text = try {
            doHttpText(config.method.uppercase(), url, headers, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info { "resolveVideoDirectly: request failed for $url: $e" }
            return null
        } ?: return null

        val videoUrl = extractVideoUrl(text, config)
        if (videoUrl.isNullOrBlank()) {
            logger.info { "resolveVideoDirectly: no url extracted from response of $url" }
            return null
        }
        logger.info { "resolveVideoDirectly: $pageUrl -> $videoUrl" }
        return WebVideo(videoUrl, videoHeaders(matchVideoConfig))
    }

    private fun extractVideoUrl(text: String, config: SelectorSearchConfig.ResolveVideoConfig): String? {
        if (config.selectUrlJsonPath.isNotBlank()) {
            val path = JsonPath.compileOrNull(config.selectUrlJsonPath)
            if (path != null) {
                val json = try {
                    Json.parseToJsonElement(text)
                } catch (e: Exception) {
                    logger.info { "resolveVideoDirectly: response is not JSON: $e" }
                    null
                }
                json?.resolveOrNull(path)?.let { firstStringOrNull(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
        val regex = config.selectUrlRegexCompiled ?: return null
        val result = regex.find(text) ?: return null
        return try {
            result.namedGroup(regex, "v")?.value ?: result.value
        } catch (_: IllegalArgumentException) { // no group named v
            result.value
        }
    }

    /**
     * JSONPath 解出来的可能是字符串本身 (`$.url`), 也可能是数组或对象 (`$..url`), 都取第一个字符串.
     */
    private fun firstStringOrNull(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.firstNotNullOfOrNull { firstStringOrNull(it) }
        is JsonObject -> element.values.firstNotNullOfOrNull { firstStringOrNull(it) }
    }

    /**
     * 把 `{名字}` 换成变量值. 没有对应变量的 `{...}` 原样保留 (可能是 JSON 里本来就有的花括号).
     */
    private fun substituteVariables(template: String, variables: Map<String, String>): String {
        if (template.isEmpty() || !template.contains('{')) return template
        return buildString(template.length) {
            var i = 0
            while (i < template.length) {
                val open = template.indexOf('{', i)
                if (open < 0) {
                    append(template, i, template.length)
                    break
                }
                val close = template.indexOf('}', open + 1)
                val name = if (close < 0) null else template.substring(open + 1, close)
                val value = name?.let { variables[it] }
                if (value == null) {
                    append(template, i, open + 1)
                    i = open + 1
                } else {
                    append(template, i, open)
                    append(value)
                    i = close + 1
                }
            }
        }
    }

    /**
     * 发一个请求并拿到响应文本. 默认不支持 (返回 `null`), 由 [DefaultSelectorMediaSourceEngine] 实现.
     *
     * 故意给默认实现而不是 abstract: 测试与工具里还有别的 [SelectorMediaSourceEngine] 子类, 不该被迫实现它.
     */
    protected open suspend fun doHttpText(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: String,
    ): String? = null

    @Throws(RepositoryException::class, CancellationException::class)
    protected abstract suspend fun doHttpGet(uri: String): Document
}

/**
 * 正则 pattern 里所有命名分组 `(?<name>...)` 的名字, 按出现顺序.
 *
 * Kotlin/JVM 的 [MatchResult] 不暴露分组名, 只能从 pattern 里数.
 */
internal fun namedGroupNames(pattern: String): List<String> {
    val names = mutableListOf<String>()
    var i = 0
    var classDepth = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c == '\\') {
            i += 2
            continue
        }
        if (classDepth > 0) {
            when (c) {
                '[' -> classDepth++
                ']' -> classDepth--
            }
        } else if (c == '[') {
            classDepth = 1
            if (pattern.getOrNull(i + 1) == '^') i++
            if (pattern.getOrNull(i + 1) == ']') i++
        } else if (c == '(' &&
            pattern.getOrNull(i + 1) == '?' &&
            pattern.getOrNull(i + 2) == '<' &&
            pattern.getOrNull(i + 3)?.isLetter() == true
        ) {
            val end = pattern.indexOf('>', startIndex = i + 3)
            if (end > 0) names.add(pattern.substring(i + 3, end))
        }
        i++
    }
    return names
}

/**
 * 解析条目详情页的剧集列表. 纯函数, 供 [SelectorMediaSourceEngine.selectEpisodes] 与 [PageEvaluator] 共用.
 *
 * @return `null` if config is invalid
 */
internal fun selectEpisodesImpl(
    subjectDetailsPage: Element,
    subjectUrl: String,
    config: SelectorSearchConfig,
): SelectedChannelEpisodes? {
    val channelFormat = SelectorChannelFormat.findById(config.channelFormatId)
        ?: throw UnsupportedOperationException("Unsupported channel format: ${config.channelFormatId}")

    @Suppress("UNCHECKED_CAST")
    channelFormat as SelectorChannelFormat<SelectorFormatConfig>
    val formatConfig = config.getFormatConfig(channelFormat)
    if (!formatConfig.isValid()) {
        return null
    }

    val finalBaseUrl = kotlin.runCatching {
        URLBuilder(subjectUrl).apply {
            pathSegments = pathSegments.dropLast(1)
        }.buildString()
    }.getOrElse {
        return null
    }
    return channelFormat.select(
        subjectDetailsPage,
        finalBaseUrl,
        formatConfig,
    )
}

internal fun selectSubjectsForCaptchaProbe(
    document: Element,
    config: SelectorSearchConfig,
): List<WebSearchSubjectInfo>? {
    val subjectFormat = SelectorSubjectFormat.findById(config.subjectFormatId)
        ?: throw UnsupportedOperationException("Unsupported subject format: ${config.subjectFormatId}")

    @Suppress("UNCHECKED_CAST")
    subjectFormat as SelectorSubjectFormat<SelectorFormatConfig>

    val formatConfig = config.getFormatConfig(subjectFormat)
    if (!formatConfig.isValid()) {
        return null
    }
    return subjectFormat.select(document, config.finalBaseUrl, formatConfig)
}

// TODO: require MediaListFilterContext when context parameters
fun WebSearchSubjectInfo.asCandidate(): MediaListFilter.Candidate {
    val info = this
    return object : MediaListFilter.Candidate {
        override val originalTitle: String get() = info.name
        override val episodeRange: EpisodeRange? get() = null
    }
}

/**
 * If you change, you also need to change
 */
internal fun SelectorSearchConfig.createFiltersForSubject(): List<MediaListFilter<MediaListFilterContext>> = buildList {
//    if (filterBySubjectName) add(MediaListFilters.ContainsSubjectName)
}

internal fun SelectorSearchConfig.createFiltersForEpisode(): List<MediaListFilter<MediaListFilterContext>> = buildList {
    // 不使用 filterBySubjectName, 因为 web 的剧集名称通常为 "第x集", 不包含 subject
    if (filterByEpisodeSort) add(MediaListFilters.ContainsAnyEpisodeInfo)
}

class DefaultSelectorMediaSourceEngine(
    /**
     * Engine 自己不会 cache 实例, 每次都调用 `.first()`.
     */
    private val client: ScopedHttpClient,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) : SelectorMediaSourceEngine() {
    override suspend fun searchImpl(
        finalUrl: Url,
    ): SearchSubjectResult = withContext(ioDispatcher) {
        try {
            client.use {
                prepareGet(finalUrl) {
                    accept(ContentType.Text.Html)
                }.execute { response ->
                    when (response.status) {
                        HttpStatusCode.NotFound -> SearchSubjectResult(
                            finalUrl,
                            document = null,
                        )

                        // 限流不是验证码: 429 走独立的异常, 不伪装成 captchaKind.
                        HttpStatusCode.TooManyRequests -> throw RepositoryRateLimitedException()

                        in blockedSearchStatuses -> SearchSubjectResult(
                            finalUrl,
                            document = null,
                            captchaKind = detectCaptchaKindFromBlockedResponse(
                                finalUrl.toString(),
                                response.bodyAsText(),
                            ),
                        )

                        else -> {
                            val channel = response.body<ByteReadChannel>()
                            parseSearchResult(finalUrl, channel)
                        }
                    }
                }
            }
        } catch (e: ClientRequestException) {
            return@withContext when (e.response.status) {
                HttpStatusCode.NotFound -> SearchSubjectResult(
                    finalUrl,
                    document = null,
                )

                HttpStatusCode.TooManyRequests -> throw RepositoryRateLimitedException(cause = e)

                in blockedSearchStatuses -> SearchSubjectResult(
                    finalUrl,
                    document = null,
                    captchaKind = detectCaptchaKindFromBlockedResponse(
                        finalUrl.toString(),
                        runCatching { e.response.bodyAsText() }.getOrDefault(""),
                    ),
                )

                else -> throw RepositoryException.wrapOrThrowCancellation(e)
            }
        } catch (e: RepositoryException) {
            throw e
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }


    override suspend fun doHttpText(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: String,
    ): String = withContext(ioDispatcher) {
        // Content-Type 是 Ktor 托管的内容头: 用 header() 设会被 body 转换覆盖掉 (配了 application/json
        // 也发不出去, 实际发的是 text/plain), 必须连同 body 一起用 TextContent 给
        val contentType = headers.firstOrNull { it.first.equals(HttpHeaders.ContentType, ignoreCase = true) }
            ?.second?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?: ContentType.Text.Plain

        client.use {
            // 响应体必须在 use 块内读完: ScopedHttpClient 不允许把 HttpResponse 带出去
            val builder: HttpRequestBuilder.() -> Unit = {
                for ((name, value) in headers) {
                    if (name.equals(HttpHeaders.ContentType, ignoreCase = true)) continue
                    header(name, value)
                }
                if (body.isNotEmpty()) setBody(TextContent(body, contentType))
            }
            if (method == "POST") post(url, builder).bodyAsText() else get(url, builder).bodyAsText()
        }
    }

    @Throws(RepositoryException::class, CancellationException::class)
    public override suspend fun doHttpGet(uri: String): Document = withContext(ioDispatcher) {
        try {
            client.use {
                prepareGet(uri) {
                    accept(ContentType.Text.Html)
                }.execute { response ->
                    if (response.status == HttpStatusCode.TooManyRequests) {
                        throw RepositoryRateLimitedException()
                    }
                    if (response.status in blockedSearchStatuses) {
                        throw WebPageCaptchaException(
                            uri,
                            detectCaptchaKindFromBlockedResponse(
                                uri,
                                response.bodyAsText(),
                            ),
                        )
                    }
                    parseDocument(
                        uri,
                        response.body<ByteReadChannel>(),
                    )
                }
            }
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.TooManyRequests) {
                throw RepositoryRateLimitedException(cause = e)
            }
            if (e.response.status in blockedSearchStatuses) {
                throw WebPageCaptchaException(
                    uri,
                    detectCaptchaKindFromBlockedResponse(
                        uri,
                        runCatching { e.response.bodyAsText() }.getOrDefault(""),
                    ),
                )
            }
            throw e
        } catch (e: RepositoryException) {
            throw e
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }

    private fun detectCaptchaKindFromBlockedResponse(
        pageUrl: String,
        html: String,
    ): WebCaptchaKind {
        return WebCaptchaDetector.detect(pageUrl, html) ?: WebCaptchaKind.Unknown
    }

    private companion object {
        val blockedSearchStatuses = setOf(
            HttpStatusCode.Forbidden,
            HttpStatusCode(468, "Captcha Required"),
        )
    }

    private suspend fun parseSearchResult(
        finalUrl: Url,
        channel: ByteReadChannel,
    ): SearchSubjectResult {
        val html = readHtml(channel)
        return parseSearchResult(finalUrl, html)
    }

    private suspend fun parseDocument(
        uri: String,
        channel: ByteReadChannel,
    ): Document {
        val html = readHtml(channel)
        return parseDocument(uri, html)
    }

    private suspend fun readHtml(channel: ByteReadChannel): String {
        if (channel.peek(1)?.decodeToString() == "\"") {
            // 非常奇怪, 有时候会是一个字符串
            // slow path

            // Channel can only read once, and we may need to read twice, so we must cache
            var body = channel.readRemaining().readString()
            if (body.startsWith("\"")) {
                body = runCatching {
                    Json.parseToJsonElement(body).jsonPrimitive.content
                }.getOrNull() ?: body
            }
            return body
        } else {
            return channel.readRemaining().readString()
        }
    }
}
