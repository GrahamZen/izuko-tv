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
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.data.network.setCookieHeaders
import me.him188.ani.app.domain.mediasource.KeywordSearchScope
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
import me.him188.ani.app.domain.mediasource.web.SubjectPageMediaSource
import me.him188.ani.app.domain.mediasource.web.WebCaptchaKind
import me.him188.ani.app.domain.mediasource.web.WebSearchEpisodeInfo
import me.him188.ani.app.domain.mediasource.web.WebSearchSubjectInfo
import me.him188.ani.app.domain.mediasource.keywordSearchChain
import me.him188.ani.app.domain.mediasource.rememberedKeywords
import me.him188.ani.app.domain.mediasource.search.SiteSearchMemory
import me.him188.ani.app.domain.mediasource.search.SubjectMatch
import me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan
import me.him188.ani.app.domain.mediasource.search.keywordStyle
import me.him188.ani.app.domain.player.diagnostics.PlayTimeline
import me.him188.ani.app.domain.mediasource.web.asEpisodesOf
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.mediasource.web.findMatchingEpisodeOrNull
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormat
import me.him188.ani.app.domain.mediasource.web.format.parseOrNull
import me.him188.ani.app.domain.mediasource.web.selectorSearchQuery
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.matcher.WebVideo
import me.him188.ani.datasources.api.matcher.WebVideoDirectResolver
import me.him188.ani.datasources.api.matcher.WebVideoMatcher
import me.him188.ani.datasources.api.matcher.WebVideoMatcherContext
import me.him188.ani.datasources.api.matcher.WebVideoMatcherProvider
import me.him188.ani.datasources.api.matcher.WebVideoSniffTimeout
import me.him188.ani.datasources.api.matcher.WebViewConfig
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.paging.emptySizedSource
import me.him188.ani.app.domain.media.fetch.mapKeepingBatches
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
import kotlin.time.Duration
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
    /** 线路级的优先级, key 是线路名 (与选集步骤得出的线路名一致), 与网页抓取数据源的同名配置相同. */
    override val channelTiers: Map<String, MediaSourceTier> = emptyMap(),
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
) : HttpMediaSource(), WebVideoMatcherProvider, SubjectPageMediaSource {
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

        /** 站点回 5xx 后隔多久重试 ([request]) */
        private val SERVER_ERROR_RETRY_DELAY = 200.milliseconds

        /** 一次搜索里同时取几个条目页: 一页一页地取, 慢的页挡住后面对的那页 */
        private const val PAGE_CONCURRENCY = 2
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
        // 站点拉黑了这个网络: 冷却期内不再请求, 收到封禁页也不再接着试 (见 RuleSiteBlocks)
        RuleSiteBlocks.throwIfBlocked(request.url)
        val response = sendHttp(request).let { first ->
            // 站点偶尔回 5xx, 过一下再请求多半就好了: GET 重试一次
            if (first.status < 500 || request.method != "GET") first
            else delay(SERVER_ERROR_RETRY_DELAY).let { sendHttp(request) }
        }
        RuleSiteBlocks.checkResponse(request.url, response.status, response.text)
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
        PlayTimeline.mark("t_rule_fetch", info.displayName)

        val subjectId = query.subjectId.toIntOrNull()
        fun searchQuery(name: String) = query.selectorSearchQuery(name)

        // 关键词与什么时候停同网页抓取数据源 (搜索计划); 两次搜索之间的间隔由 searchOnline 按规则自己等
        val plan = SubjectSearchPlan.of(query)
        val keywords = plan.keywords(rule.autoMatch.keywordStyle(), mediaSourceId)
        PlayTimeline.mark("t_keywords", info.displayName)
        val remembered = repository.rememberedKeywords(subjectId, mediaSourceId, keywords.primaryNames + keywords.fallbackNames, logger)
        PlayTimeline.mark("t_remembered", info.displayName)
        return keywordSearchChain(
            keywords.primaryNames, keywords.fallbackNames, remembered,
            requestInterval = Duration.ZERO,
            matches = plan::foundOwn,
            emitPagesEarly = true,
        ) { name ->
            val searchQuery = searchQuery(name)
            PlayTimeline.mark("t_kw", "${info.displayName} $name")
            searchFromCacheOrNull(searchQuery, subjectId, plan, scope = this).also { PlayTimeline.mark("t_cache_checked", info.displayName) }
                ?: searchOnlineUnlessRecentlyEmpty(searchQuery, keywords.keywordOf(name), plan, subjectId, scope = this)
        }.mapKeepingBatches { MediaMatch(it, MatchKind.FUZZY) }
    }

    /** 同网页抓取数据源: 写这些页的那一轮有页没开完时, 先经 [scope] 交出缓存里的, 再返回 `null` 照常重搜补齐. */
    private suspend fun searchFromCacheOrNull(
        query: SelectorSearchQuery,
        subjectId: Int?,
        plan: SubjectSearchPlan,
        scope: KeywordSearchScope?,
    ): List<DefaultMedia>? {
        val caches = try {
            repository.getCache(subjectId, mediaSourceId, query.subjectName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "RuleMediaSource '$mediaSourceId': failed to read search cache, falling back to search" }
            return null
        }
        val probe = query.freshnessProbe ?: SelectorEpisodeProbe(query.episodeSort, query.episodeEp, query.episodeName)
        val media = buildList {
            for (cache in caches) {
                // 写缓存时名字表可能还没读进来, 那时认不出的别的作品的页也开了: 按现在的认部再判一次, 在线搜也不会开它
                if (plan.identify(cache.webSubjectInfo.name) == SubjectMatch.OTHER) continue
                val episodes = cache.webEpisodeInfos.asEpisodesOf(query)
                // 同网页抓取数据源: 页面没有已上映的最新一集时, 有当前这一集、又只查这一集才照用, 否则整个重搜 (不跳过它只用别的页)
                if (episodes.findMatchingEpisodeOrNull(probe.episodeSort, probe.episodeEp, probe.episodeName) == null &&
                    (query.subjectEpisodeSorts != null ||
                            episodes.findMatchingEpisodeOrNull(query.episodeSort, query.episodeEp, query.episodeName) == null)
                ) {
                    return null
                }
                addAll(selectorEngine.selectFilteredMedia(episodes, matchConfig, query, mediaSourceId, cache.webSubjectInfo.name))
            }
        }
        if (media.isEmpty()) return null
        if (caches.any { !it.roundComplete }) {
            scope?.emitEarly(media)
            return null
        }
        return media
    }

    /**
     * 最近给哪一部搜过、什么都没搜到的名字 (条目 id 与名字) → 搜的时刻. 搜索缓存只存搜到了条目的; 不记这个的话, 站点上没有这部番时
     * 每换一集都把所有名字重搜一遍, 请求多了会被站点当成采集拉黑 (见 RuleSiteBlocks). 与搜到时的缓存留一样久 ([RuleConfig.searchCacheTtlMillis]).
     *
     * 「没搜到」是认部、开页之后的结果, 只对这一部算数: 第二季拿系列名搜, 站点只有第一季的页被排除, 换去看第一季时同一个系列名照样要搜.
     * 有条目页没取到 (网络出错) 的那一轮不记, 下次照常重搜.
     */
    private val recentlyEmpty = HashMap<Pair<Int?, String>, Long>()
    private val recentlyEmptyLock = SynchronizedObject()

    private suspend fun searchOnlineUnlessRecentlyEmpty(
        query: SelectorSearchQuery,
        keyword: String,
        plan: SubjectSearchPlan,
        subjectId: Int?,
        scope: KeywordSearchScope?,
    ): List<DefaultMedia> {
        val key = subjectId to query.subjectName
        val now = currentTimeMillis()
        val searchedAt = synchronized(recentlyEmptyLock) { recentlyEmpty[key] }
        if (searchedAt != null && now - searchedAt < rule.searchCacheTtlMillis) return emptyList()
        val result = try {
            searchOnline(query, keyword, plan, subjectId, scope)
        } catch (e: RuleServerErrorException) {
            // 站点出错时照常换下一个关键词, 但不记成「搜过、没搜到」(站点回应的记忆也没记下这次)
            logger.info { "RuleMediaSource '$mediaSourceId': search '$keyword' failed: ${e.message}" }
            return emptyList()
        }
        if (result.media.isEmpty() && result.allPagesLoaded) synchronized(recentlyEmptyLock) { recentlyEmpty[key] = now }
        return result.media
    }

    /** 搜一个关键词的结果; [allPagesLoaded] 为 false 时有条目页没取到 (出错), 「没搜到」不作数. */
    private class OnlineSearchResult(val media: List<DefaultMedia>, val allPagesLoaded: Boolean)

    /**
     * 向站点搜 [keyword] (搜索计划改写好的词), 打开可能是这一部的条目 ([SubjectSearchPlan.pagesToOpen], 最多 [RuleConfig.maxSubjects] 个).
     * 站点回应先查跨条目共用的记忆 ([SiteSearchMemory]).
     */
    private suspend fun searchOnline(
        query: SelectorSearchQuery,
        keyword: String,
        plan: SubjectSearchPlan,
        subjectId: Int?,
        scope: KeywordSearchScope?,
    ): OnlineSearchResult {
        val ttl = rule.searchCacheTtlMillis.milliseconds
        val subjects = repository.siteMemory.searchResults(mediaSourceId, keyword, ttl) {
            delayUntilNextAllowedSearch()
            PlayTimeline.mark("t_http", info.displayName)
            engine.search(keyword).map { it.toSubjectInfo() }.also { PlayTimeline.mark("t_http_done", info.displayName) }
        }.orEmpty()
        logger.info { "RuleMediaSource '$mediaSourceId': search '$keyword' -> ${subjects.size} subjects" }
        PlayTimeline.mark("search_results", "${info.displayName} '$keyword' ${subjects.size}")
        val ordered = plan.pagesToOpen(subjects, matchConfig.autoMatch.preferShorterName) { it.name }
            .take(rule.maxSubjects.coerceAtLeast(1))
        PlayTimeline.mark("pages_open", "${info.displayName} ${ordered.size}/${subjects.size}")
        val probe = query.freshnessProbe ?: SelectorEpisodeProbe(query.episodeSort, query.episodeEp, query.episodeName)
        // 几页同时取 (最多 PAGE_CONCURRENCY 页), 每取完一页就先交出这一页的资源 ([KeywordSearchScope.emitEarly]); 返回的仍按搜索计划排的顺序
        val pages = coroutineScope {
            val gate = Semaphore(PAGE_CONCURRENCY)
            ordered.map { subject ->
                // 按搜索计划的顺序占名额再开: 同时在取的总是排在最前的几页 (各自去抢的话谁先抢到不一定)
                gate.acquire()
                async {
                    try {
                        try {
                            repository.siteMemory.page(
                                mediaSourceId, subject.fullUrl, ttl,
                                isFresh = { it.findMatchingEpisodeOrNull(probe.episodeSort, probe.episodeEp, probe.episodeName) != null },
                            ) { episodesOf(subject.fullUrl) }.orEmpty()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: BlockedException) {
                            throw e
                        } catch (e: SiteIpBlockedException) {
                            throw e
                        } catch (e: Exception) {
                            // 单个条目页出错不终止整个搜索
                            logger.warn(e) { "RuleMediaSource '$mediaSourceId': failed to load subject page ${subject.fullUrl}" }
                            null
                        }.also { PlayTimeline.mark("page_loaded", "${info.displayName} ${subject.name} ${it?.size ?: "失败"}") }
                            ?.let { episodes ->
                                if (episodes.isEmpty()) return@let episodes to emptyList()
                                // 开完一页就写进搜索缓存, 再交出这一页的资源: 交出去的被选中后搜索可能马上被暂停 (取消),
                                // 等全部页开完再写就写不上了, 下一集也就用不上这一页的缓存
                                repository.addCache(
                                    subjectId, mediaSourceId, query.subjectName, subject, episodes,
                                    sourceCacheTtl = rule.searchCacheTtlMillis.milliseconds,
                                )
                                val selected = selectorEngine.selectFilteredMedia(episodes, matchConfig, query, mediaSourceId, subject.name)
                                if (selected.isNotEmpty()) scope?.emitEarly(selected)
                                episodes to selected
                            }
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll()
        }
        val media = buildList {
            for ((subject, loaded) in ordered.zip(pages)) {
                val (episodes, selected) = loaded ?: continue
                logger.info { "RuleMediaSource '$mediaSourceId': ${subject.fullUrl} -> ${episodes.size} episodes" }
                if (episodes.isEmpty()) continue
                // 这一页没有要的那一集时也留一条这个条目的资源, 关键词链靠它判断这个关键词搜到了名字对得上的条目
                addAll(
                    selected.ifEmpty {
                        selectorEngine.selectMedia(episodes.take(1).asSequence(), matchConfig, query, mediaSourceId, subject.name)
                            .originalList
                    },
                )
            }
        }
        val allPagesLoaded = pages.none { it == null }
        // 这一轮要开的页都开成了, 之后读到的缓存才算完整 (见 searchFromCacheOrNull)
        if (allPagesLoaded) repository.markRoundComplete(subjectId, mediaSourceId, query.subjectName)
        return OnlineSearchResult(media, allPagesLoaded)
    }

    override suspend fun mediaOnSubjectPage(subjectUrl: String, subjectName: String, request: MediaFetchRequest): List<Media> {
        if (!rule.autoMatch.enabled) return emptyList()
        val episodes = episodesOf(subjectUrl)
        if (episodes.isEmpty()) return emptyList()
        return selectorEngine.selectFilteredMedia(episodes, matchConfig, request.selectorSearchQuery(subjectName), mediaSourceId, subjectName)
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
        object : WebVideoMatcher, WebVideoDirectResolver, WebVideoSniffTimeout {
            override val sniffTimeoutMillis: Long? = videoSniffTimeoutMillis(rule.play)

            override fun match(url: String, context: WebVideoMatcherContext): WebVideoMatcher.MatchResult =
                selectorEngine.matchWebVideo(url, rule.matchVideo, userAgent = null)

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
                val captchaCookies = sessionManager.cookieJar.getCookieHeaderValues(rule.baseUrl)
                return SelectorMediaSource.patchWebViewConfig(config, rule.matchVideo, userAgent = null, captchaCookies)
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

/** 播放段里嗅探视频的步骤 (含 [RuleStep.First] 的分支里的) 写的最长等待时长; 都没写为 `null`. */
internal fun videoSniffTimeoutMillis(steps: List<RuleStep>): Long? = steps.mapNotNull { step ->
    when (step) {
        is RuleStep.Sniff -> step.timeoutMillis.takeIf { step.goal == RuleStep.Sniff.GOAL_VIDEO && it > 0 }
        is RuleStep.First -> step.branches.mapNotNull { videoSniffTimeoutMillis(it) }.maxOrNull()
        else -> null
    }
}.maxOrNull()

/**
 * 发出规则里的一个请求. 4xx / 5xx 也作为响应返回.
 */
internal suspend fun HttpClient.executeRuleRequest(request: RuleHttpRequest): RuleHttpResponse = try {
    prepareRequest(request.url) {
        method = HttpMethod.parse(request.method)
        for ((name, value) in request.headers) header(name, value)
        // 站点连不上时早点放弃: 共用客户端等 30 秒, 播放段第一步连不上就要白等这么久才轮到下一个分支 (如 WebView 嗅探)
        if (pluginOrNull(HttpTimeout) != null) timeout { connectTimeoutMillis = RULE_CONNECT_TIMEOUT_MILLIS }
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

/** 规则源请求建立连接 (TCP + TLS) 最多等多久, 见 [executeRuleRequest] */
private const val RULE_CONNECT_TIMEOUT_MILLIS = 10_000L

private suspend fun HttpResponse.toRuleResponse() = RuleHttpResponse(
    finalUrl = request.url.toString(),
    status = status.value,
    text = runCatching { bodyAsText() }.getOrDefault("").let(::unwrapJsonEncodedHtml),
    cookies = setCookieHeaders()
        .map { it.substringBefore(';').trim() }
        .filter { it.substringBefore('=', "").isNotBlank() },
)
