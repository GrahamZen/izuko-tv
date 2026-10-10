/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.media.fetch.seasonEpisodeSorts
import me.him188.ani.app.domain.mediasource.MediaListFilter
import me.him188.ani.app.domain.mediasource.MediaListFilterContext
import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.mediasource.MediaSourceEngineHelpers
import me.him188.ani.app.domain.mediasource.asCandidate
import me.him188.ani.app.domain.mediasource.codec.DefaultMediaSourceCodec
import me.him188.ani.app.domain.mediasource.codec.DontForgetToRegisterCodec
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.app.domain.mediasource.codec.MediaSourceTier
import me.him188.ani.app.domain.mediasource.profile.SourceProfiles
import me.him188.ani.app.domain.mediasource.web.BlockReason
import me.him188.ani.app.domain.mediasource.web.BlockedException
import me.him188.ani.app.domain.mediasource.web.DefaultSelectorMediaSourceEngine
import me.him188.ani.app.domain.mediasource.web.LoadedPage
import me.him188.ani.app.domain.mediasource.web.PageEvaluator
import me.him188.ani.app.domain.mediasource.web.PageExpectation
import me.him188.ani.app.domain.mediasource.web.PageVerdict
import me.him188.ani.app.domain.mediasource.web.SelectorEpisodeProbe
import me.him188.ani.app.domain.mediasource.web.SelectorMediaSource
import me.him188.ani.app.domain.mediasource.web.SelectorSearchConfig
import me.him188.ani.app.domain.mediasource.web.SelectorSearchQuery
import me.him188.ani.app.domain.mediasource.web.SolveRequest
import me.him188.ani.app.domain.mediasource.web.WebCaptchaKind
import me.him188.ani.app.domain.mediasource.web.WebSearchEpisodeInfo
import me.him188.ani.app.domain.mediasource.web.WebSearchSubjectInfo
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.mediasource.web.distinctFallbackKeywords
import me.him188.ani.app.domain.mediasource.web.findMatchingEpisodeOrNull
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormat
import me.him188.ani.app.domain.mediasource.web.format.parseOrNull
import me.him188.ani.app.domain.mediasource.web.orderSubjectsForAutoMatch
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.matcher.WebVideo
import me.him188.ani.datasources.api.matcher.WebVideoDirectResolver
import me.him188.ani.datasources.api.matcher.WebVideoMatcher
import me.him188.ani.datasources.api.matcher.WebVideoMatcherContext
import me.him188.ani.datasources.api.matcher.WebVideoMatcherProvider
import me.him188.ani.datasources.api.matcher.WebViewConfig
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.paging.emptySizedSource
import me.him188.ani.datasources.api.paging.map
import me.him188.ani.datasources.api.source.BrowseChannel
import me.him188.ani.datasources.api.source.BrowseEpisode
import me.him188.ani.datasources.api.source.BrowseSubject
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.HttpMediaSource
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceFactory
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull
import me.him188.ani.datasources.api.util.namedGroup
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(DontForgetToRegisterCodec::class)
@Serializable
data class RuleMediaSourceArguments(
    override val name: String,
    val description: String = "",
    val iconUrl: String = "",
    val rule: RuleConfig = RuleConfig(),
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
) : MediaSourceArguments {
    companion object {
        val Default = RuleMediaSourceArguments(name = "规则源")

        /**
         * 配置示例, 也是添加数据源时给出的模板. 地址是 example.com, 照着把地址与选择器改成目标站点的即可.
         *
         * 例子里用到了常见的几种情况: 网页搜索结果、带线路名的剧集列表、苹果 CMS 播放页与「地址在页面脚本的 JSON 里」两种取地址方式、播放时带 Referer.
         */
        val Example = RuleMediaSourceArguments(
            name = "规则源",
            description = "示例配置, 把地址与选择器换成目标站点的",
            rule = RuleConfig(
                baseUrl = "https://www.example.com",
                search = listOf(
                    RuleStep.Fetch(url = "/search?wd={keyword}"),
                    RuleStep.Subjects(list = listOf(".search-list .item"), linkPattern = "/detail/"),
                ),
                detail = listOf(
                    RuleStep.Fetch(),
                    RuleStep.Episodes(lists = listOf(".play-list"), tabs = listOf(".play-tabs a")),
                ),
                play = listOf(
                    RuleStep.Fetch(),
                    RuleStep.First(
                        listOf(
                            listOf(RuleStep.MacCmsPlayer()),
                            listOf(
                                RuleStep.Select(css = "script#player-config"),
                                RuleStep.Json(path = "url"),
                            ),
                        ),
                    ),
                    RuleStep.MediaHeaders(headers = mapOf("Referer" to "{baseUrl}/")),
                ),
            ),
        )
    }
}

object RuleMediaSourceCodec : DefaultMediaSourceCodec<RuleMediaSourceArguments>(
    RuleMediaSource.FactoryId,
    RuleMediaSourceArguments::class,
    currentVersion = 1,
    RuleMediaSourceArguments.serializer(),
)

/**
 * 规则源: 按 [RuleConfig] 里的步骤抓取网站.
 *
 * 站点上的东西怎么取由规则决定 ([RuleEngine]); 取到之后挑出当前这一集、缓存条目页、生成 [Media]
 * 都与网页抓取 (Selector) 数据源相同, 直接复用它的实现. 资源的下载地址是剧集页 ([me.him188.ani.datasources.api.topic.ResourceLocation.WebVideo]),
 * 播放时由 [matcher] 执行播放段取视频地址; 播放段要求嗅探或没取到时, 播放器按 [RuleConfig.matchVideo] 用 WebView 打开剧集页嗅探.
 */
class RuleMediaSource(
    override val mediaSourceId: String,
    config: MediaSourceConfig,
    private val client: ScopedHttpClient,
    private val repository: SelectorMediaSourceEpisodeCacheRepository,
    private val sessionManager: WebSessionManager,
) : HttpMediaSource(), WebVideoMatcherProvider {
    companion object {
        val FactoryId = FactoryId("rule")
        val INFO = MediaSourceInfo(
            displayName = "规则源",
            description = "按 JSON 规则 (搜索、详情、播放三段步骤) 抓取网站",
        )

        /** 被挡页面的判定只对不太大的页面做: 挑战页都很小, 不必为几 MB 的剧集页多解析一遍. */
        private const val MAX_BLOCK_CHECK_LENGTH = 64 * 1024

        /**
         * 站点「N 秒内只能搜一次」的间隔 (苹果 CMS 默认 3 秒). 苹果 CMS 先记这次搜索的时间再判要不要验证码,
         * 被验证页挡住的那次也算一次; 同一页再请求要等过这段, 否则拿到的是冷却页.
         */
        private val SITE_SEARCH_COOLDOWN = 3.seconds
    }

    private val arguments = config.deserializeArgumentsOrNull(RuleMediaSourceArguments.serializer())
        ?: RuleMediaSourceArguments.Default
    private val rule = arguments.rule

    override val kind: MediaSourceKind get() = MediaSourceKind.WEB
    override val location: MediaSourceLocation get() = MediaSourceLocation.Online

    override val info: MediaSourceInfo = MediaSourceInfo(
        displayName = arguments.name,
        description = arguments.description.takeIf { it.isNotBlank() },
        websiteUrl = rule.baseUrl.takeIf { it.isNotBlank() },
        iconUrl = arguments.iconUrl.takeIf { it.isNotBlank() },
        tier = arguments.tier,
    )

    /**
     * 挑集、生成 [Media] 用的网页抓取配置: 只有匹配相关的字段有意义.
     */
    private val matchConfig = SelectorSearchConfig(
        searchUrl = rule.baseUrl,
        requestInterval = rule.requestIntervalMillis.milliseconds,
        searchCacheTtl = rule.searchCacheTtlMillis.milliseconds,
        defaultResolution = rule.defaultResolution,
        defaultSubtitleLanguage = rule.defaultSubtitleLanguage,
        matchVideo = rule.matchVideo,
        autoMatch = rule.autoMatch,
    )

    private val selectorEngine by lazy { DefaultSelectorMediaSourceEngine(client) }
    private val evaluator = PageEvaluator()
    private val episodeSortRegex = rule.matchEpisodeSortFromName.takeIf { it.isNotBlank() }
        ?.let { Regex.parseOrNull(it) }

    private val engine = RuleEngine(
        rule,
        http = { request(it) },
        trace = { label, ok, detail ->
            // 成功的步骤太多, 只记失败的; 整段的结果在调用处记
            if (!ok) logger.info { "RuleMediaSource '$mediaSourceId' $label: $detail" }
        },
    )

    override suspend fun checkConnection(): ConnectionStatus {
        if (rule.baseUrl.isBlank()) return ConnectionStatus.FAILED
        return try {
            request(RuleHttpRequest("GET", rule.baseUrl, rule.headers, null, null))
            ConnectionStatus.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: BlockedException) {
            // 能连上, 只是要验证
            ConnectionStatus.SUCCESS
        } catch (e: Exception) {
            ConnectionStatus.FAILED
        }
    }

    // region HTTP

    /**
     * 直连请求; GET 被站点验证 (Cloudflare 等) 挡住时先试自动过验证, 再用验证码会话加载.
     */
    private suspend fun request(request: RuleHttpRequest): RuleHttpResponse {
        val response = sendHttp(request)
        if (request.method != "GET" || response.text.length > MAX_BLOCK_CHECK_LENGTH) return response
        val verdict = evaluator.evaluate(
            LoadedPage(finalUrl = response.finalUrl, html = response.text, status = response.status),
            PageExpectation.AnyContent,
        )
        val reason = (verdict as? PageVerdict.Blocked)?.reason ?: return response
        return when (reason) {
            is BlockReason.Captcha -> loadThroughSession(request.url, reason.kind) ?: response
            is BlockReason.RateLimited -> {
                delay(reason.retryAfter ?: maxOf(rule.requestIntervalMillis.milliseconds, SITE_SEARCH_COOLDOWN))
                sendHttp(request)
            }

            else -> response
        }
    }

    private suspend fun sendHttp(request: RuleHttpRequest): RuleHttpResponse = withContext(Dispatchers.IO_) {
        client.use { executeRuleRequest(request) }
    }

    /**
     * 直连刚被验证挡住: 先交给自动解验证码, 再由验证码会话取回这一页 (自动解出的页面、浏览器暖会话, 或再直连一次).
     *
     * 苹果 CMS 一类站点的验证页挡在搜索间隔之后 (见 [SITE_SEARCH_COOLDOWN]), 紧接着再请求只会拿到冷却页, 所以先等过这段;
     * 之后仍是冷却页也按验证码报, 界面才会让用户去验证, 而不是显示限流. Cloudflare 的验证页由 CDN 直接给出, 源站没收到这次请求, 不用等.
     */
    private suspend fun loadThroughSession(url: String, kind: WebCaptchaKind): RuleHttpResponse? {
        val expectation = PageExpectation.AnyContent
        if (kind != WebCaptchaKind.Cloudflare && kind != WebCaptchaKind.CloudflareTurnstile) delay(SITE_SEARCH_COOLDOWN)
        sessionManager.solve(SolveRequest(mediaSourceId, url, kind, expectation), interactive = false)
        return when (val verdict = sessionManager.fetchPage(url, expectation)) {
            // 浏览器加载出来的只有解析后的页面, 序列化回 HTML 交给后面的步骤
            is PageVerdict.Ok -> RuleHttpResponse(url, 200, verdict.value.toString())
            is PageVerdict.EmptyContent -> null
            is PageVerdict.Blocked -> {
                val reason = verdict.reason.takeUnless { it is BlockReason.RateLimited } ?: BlockReason.Captcha(kind)
                throw BlockedException(
                    reason,
                    SolveRequest(mediaSourceId, url, (reason as? BlockReason.Captcha)?.kind ?: kind, expectation),
                )
            }
        }
    }

    // endregion

    // region 自动匹配

    @OptIn(ExperimentalAtomicApi::class)
    private val lastSearchTime = AtomicLong(0L)

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun delayUntilNextAllowedSearch() {
        val interval = rule.requestIntervalMillis
        while (true) {
            val now = currentTimeMillis()
            val last = lastSearchTime.load()
            val wait = (last + interval) - now
            if (wait > 0) {
                delay(wait)
                continue
            }
            if (lastSearchTime.compareAndSet(last, now)) return
        }
    }

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        if (!rule.autoMatch.enabled || rule.search.isEmpty()) return emptySizedSource()

        val allSubjectNames = query.subjectNames.toSet()
        val freshnessProbe = query.latestAiredEpisode()?.let {
            SelectorEpisodeProbe(episodeSort = it.sort, episodeEp = it.ep, episodeName = it.name)
        }
        val subjectId = query.subjectId.toIntOrNull()
        val subjectEpisodeSorts = query.wholeSubjectEpisodeSortsOrNull()
        fun searchQuery(name: String) = SelectorSearchQuery(
            subjectName = name,
            episodeSort = query.episodeSort,
            allSubjectNames = allSubjectNames,
            episodeEp = query.episodeEp,
            episodeName = query.episodeName,
            freshnessProbe = freshnessProbe,
            subjectEpisodeSorts = subjectEpisodeSorts,
        )

        val primary = query.subjectNames.take(rule.autoMatch.searchUseSubjectNamesCount.coerceAtLeast(1))
        val fallback = matchConfig.distinctFallbackKeywords(primary, query.fallbackSearchKeywords)
        val filterContext = MediaListFilterContext(
            subjectNames = allSubjectNames,
            episodeSort = query.episodeSort,
            episodeEp = query.episodeEp,
            episodeName = query.episodeName,
        )
        return searchChain(primary, fallback, subjectId, ::searchQuery) { media ->
            media.anySubjectNameMatches(filterContext)
        }.map { MediaMatch(it, MatchKind.FUZZY) }
    }

    /**
     * 关键词链, 规则与网页抓取数据源相同: [primary] 每个都搜; 它们都没搜到名字能对上的条目时再依次试 [fallback], 搜到为止.
     * 命中搜索缓存的关键词不发请求.
     */
    private fun searchChain(
        primary: List<String>,
        fallback: List<String>,
        subjectId: Int?,
        query: (name: String) -> SelectorSearchQuery,
        matches: (List<DefaultMedia>) -> Boolean,
    ): SizedSource<DefaultMedia> {
        val finishedState = MutableStateFlow(false)
        val totalSizeState = MutableStateFlow<Int?>(null)
        return object : SizedSource<DefaultMedia> {
            override val results = flow {
                var total = 0
                var matched = false
                val primarySet = primary.toHashSet()
                for (name in primary + fallback) {
                    if (matched && name !in primarySet) break
                    val searchQuery = query(name)
                    val media = searchFromCacheOrNull(searchQuery, subjectId) ?: searchOnline(searchQuery, subjectId)
                    if (!matched && matches(media)) matched = true
                    total += media.size
                    emitAll(media.asFlow())
                }
                totalSizeState.value = total
                finishedState.value = true
            }
            override val finished = finishedState
            override val totalSize = totalSizeState
        }
    }

    private suspend fun searchFromCacheOrNull(query: SelectorSearchQuery, subjectId: Int?): List<DefaultMedia>? {
        val caches = try {
            repository.getCache(subjectId, mediaSourceId, query.subjectName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "RuleMediaSource '$mediaSourceId': failed to read search cache, falling back to search" }
            return null
        }
        val probe = query.freshnessProbe ?: SelectorEpisodeProbe(query.episodeSort, query.episodeEp, query.episodeName)
        return buildList {
            for (cache in caches) {
                val episodes = cache.webEpisodeInfos
                if (episodes.findMatchingEpisodeOrNull(probe.episodeSort, probe.episodeEp, probe.episodeName) == null) continue
                addAll(selectorEngine.selectFilteredMedia(episodes, matchConfig, query, mediaSourceId, cache.webSubjectInfo.name))
            }
        }.takeIf { it.isNotEmpty() }
    }

    private suspend fun searchOnline(query: SelectorSearchQuery, subjectId: Int?): List<DefaultMedia> {
        delayUntilNextAllowedSearch()
        val keyword = MediaSourceEngineHelpers.getSearchKeyword(
            query.subjectName,
            rule.autoMatch.searchRemoveSpecial,
            rule.autoMatch.searchUseOnlyFirstWord,
        )
        val subjects = engine.search(keyword).map { it.toSubjectInfo() }
        logger.info { "RuleMediaSource '$mediaSourceId': search '$keyword' -> ${subjects.size} subjects" }
        val ordered = matchConfig.orderSubjectsForAutoMatch(subjects).take(rule.maxSubjects.coerceAtLeast(1))
        return buildList {
            for (subject in ordered) {
                val episodes = try {
                    episodesOf(subject.fullUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BlockedException) {
                    throw e
                } catch (e: Exception) {
                    // 单个条目页出错不终止整个搜索
                    logger.warn(e) { "RuleMediaSource '$mediaSourceId': failed to load subject page ${subject.fullUrl}" }
                    continue
                }
                logger.info { "RuleMediaSource '$mediaSourceId': ${subject.fullUrl} -> ${episodes.size} episodes" }
                if (episodes.isEmpty()) continue
                repository.addCache(
                    subjectId, mediaSourceId, query.subjectName, subject, episodes,
                    sourceCacheTtl = rule.searchCacheTtlMillis.milliseconds,
                )
                val selected = selectorEngine.selectFilteredMedia(episodes, matchConfig, query, mediaSourceId, subject.name)
                // 这一页没有要的那一集时也留一条这个条目的资源, 关键词链靠它判断这个关键词搜到了名字对得上的条目
                addAll(
                    selected.ifEmpty {
                        selectorEngine.selectMedia(episodes.take(1).asSequence(), matchConfig, query, mediaSourceId, subject.name)
                            .originalList
                    },
                )
            }
        }
    }

    private suspend fun episodesOf(subjectUrl: String): List<WebSearchEpisodeInfo> =
        engine.detail(subjectUrl).flatMap { channel ->
            channel.episodes.map { episode ->
                WebSearchEpisodeInfo(
                    channel = channel.name,
                    name = episode.name,
                    episodeSortOrEp = parseEpisodeSort(episode.name),
                    playUrl = episode.url,
                )
            }
        }

    private fun parseEpisodeSort(name: String): EpisodeSort {
        val regex = episodeSortRegex
        val raw = if (regex == null) {
            name
        } else {
            regex.find(name)?.let { match ->
                try {
                    match.namedGroup(regex, "ep")?.value
                } catch (_: IllegalArgumentException) {
                    null
                } ?: name
            }
        }
        return SelectorChannelFormat.convertSpecialEpisodes(name, raw)
    }

    private fun RuleSubject.toSubjectInfo() = WebSearchSubjectInfo(
        internalId = url,
        name = name,
        fullUrl = url,
        partialUrl = url,
        origin = null,
    )

    /** 本条目的全部集号 (拆分季的后一段加上整季序号); 剧集未知或集数太多时为 `null`, 那时只产出当前这一集 (与网页抓取数据源相同). */
    private fun MediaFetchRequest.wholeSubjectEpisodeSortsOrNull(): Set<EpisodeSort>? {
        if (episodes.isEmpty() || episodes.size > SelectorMediaSource.MAX_WHOLE_SUBJECT_EPISODES) return null
        return episodes.flatMapTo(mutableSetOf()) { listOfNotNull(it.sort, it.ep) } + seasonEpisodeSorts()
    }

    private fun MediaFetchRequest.latestAiredEpisode(): MediaFetchRequest.Episode? {
        val today = PackedDate.now()
        return episodes.asSequence()
            .filter { it.sort is EpisodeSort.Normal && it.airDate.isValid && it.airDate <= today }
            .maxByOrNull { it.sort }
    }

    // endregion

    // region 浏览

    override val supportsBrowsing: Boolean get() = rule.search.isNotEmpty()

    override suspend fun searchSubjects(keyword: String): List<BrowseSubject> {
        delayUntilNextAllowedSearch()
        return engine.search(keyword).map { BrowseSubject(name = it.name, url = it.url) }
    }

    override suspend fun browseSubject(subject: BrowseSubject): List<BrowseChannel> =
        engine.detail(subject.url).map { channel ->
            BrowseChannel(
                name = channel.name,
                label = channel.name,
                episodes = channel.episodes.map {
                    BrowseEpisode(name = it.name, url = it.url, episodeSort = parseEpisodeSort(it.name))
                },
            )
        }

    override fun createMedia(
        subject: BrowseSubject,
        channelName: String?,
        episode: BrowseEpisode,
        episodeSort: EpisodeSort?,
    ): Media = selectorEngine.createMedia(
        WebSearchEpisodeInfo(
            channel = channelName,
            name = episode.name,
            episodeSortOrEp = episode.episodeSort,
            playUrl = episode.url,
        ),
        episodeSort,
        matchConfig,
        mediaSourceId,
        subjectName = subject.name,
    )

    // endregion

    override val matcher: WebVideoMatcher by lazy {
        object : WebVideoMatcher, WebVideoDirectResolver {
            override fun match(url: String, context: WebVideoMatcherContext): WebVideoMatcher.MatchResult =
                selectorEngine.matchWebVideo(url, rule.matchVideo)

            override suspend fun resolveDirectly(pageUrl: String, context: WebVideoMatcherContext): WebVideo? {
                val result = try {
                    engine.play(pageUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 取不到就退回 WebView, 不让这一步把能嗅探的源拖下水
                    logger.warn(e) { "RuleMediaSource '$mediaSourceId': play failed for $pageUrl" }
                    RulePlayResult.Failed
                }
                logger.info { "RuleMediaSource '$mediaSourceId': play $pageUrl -> $result" }
                SourceProfiles.noteWebView(mediaSourceId, webView = result !is RulePlayResult.Direct)
                return (result as? RulePlayResult.Direct)?.let { WebVideo(it.url, it.headers) }
            }

            override fun patchConfig(config: WebViewConfig): WebViewConfig {
                val configured = rule.matchVideo.cookies.lines().filter { it.isNotBlank() }
                val captchaCookies = sessionManager.cookieJar.getCookieHeaderValues(rule.baseUrl)
                return config.copy(cookies = SelectorMediaSource.mergeCookies(config.cookies, configured, captchaCookies))
            }
        }
    }

    class Factory(
        private val repository: SelectorMediaSourceEpisodeCacheRepository,
        private val sessionManager: WebSessionManager,
    ) : MediaSourceFactory {
        override val factoryId: FactoryId get() = FactoryId
        override val allowMultipleInstances: Boolean get() = true
        override val info: MediaSourceInfo get() = INFO
        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient,
        ): MediaSource = RuleMediaSource(mediaSourceId, config, client, repository, sessionManager)
    }
}

/**
 * 发出规则里的一个请求. 4xx / 5xx 也作为响应返回.
 */
internal suspend fun HttpClient.executeRuleRequest(request: RuleHttpRequest): RuleHttpResponse = try {
    prepareRequest(request.url) {
        method = HttpMethod.parse(request.method)
        for ((name, value) in request.headers) header(name, value)
        if (request.body != null) {
            // Content-Type 是 Ktor 托管的内容头, 直接 header() 会被覆盖, 要随请求体一起给
            setBody(TextContent(request.body, ContentType.parse(request.contentType ?: "text/plain")))
        }
    }.execute { it.toRuleResponse() }
} catch (e: ClientRequestException) {
    e.response.toRuleResponse()
} catch (e: ServerResponseException) {
    e.response.toRuleResponse()
}

private suspend fun HttpResponse.toRuleResponse() = RuleHttpResponse(
    finalUrl = request.url.toString(),
    status = status.value,
    text = runCatching { bodyAsText() }.getOrDefault(""),
)

/**
 * 结果里是否有条目名能对上请求条目的. 判据与选择器过滤 WEB 资源的一致, 所以这里认为对上的, 选择器不会以名字不符为由排除.
 */
private fun List<DefaultMedia>.anySubjectNameMatches(context: MediaListFilterContext): Boolean = with(context) {
    any { media ->
        MediaListFilters.ContainsSubjectName.applyOn(
            object : MediaListFilter.Candidate by media.asCandidate() {
                override val subjectName: String get() = media.properties.subjectName ?: media.originalTitle
            },
        )
    }
}
