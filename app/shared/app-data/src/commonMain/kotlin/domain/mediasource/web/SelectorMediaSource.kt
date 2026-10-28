/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */


package me.him188.ani.app.domain.mediasource.web

import io.ktor.client.request.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.models.ApiFailure
import me.him188.ani.app.data.models.fold
import me.him188.ani.app.data.models.runApiRequest
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.media.fetch.SearchDispatchers
import me.him188.ani.app.domain.mediasource.KeywordSearchScope
import me.him188.ani.app.domain.mediasource.MediaSourceEngineHelpers
import me.him188.ani.app.domain.mediasource.codec.DefaultMediaSourceCodec
import me.him188.ani.app.domain.mediasource.codec.DontForgetToRegisterCodec
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.app.domain.mediasource.codec.MediaSourceTier
import me.him188.ani.app.domain.mediasource.keywordSearchChain
import me.him188.ani.app.domain.mediasource.rememberedKeywords
import me.him188.ani.app.domain.mediasource.search.SiteSearchMemory
import me.him188.ani.app.domain.mediasource.search.SubjectMatch
import me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan
import me.him188.ani.app.domain.mediasource.search.keywordStyle
import me.him188.ani.app.domain.mediasource.web.captcha.SolveOutcome
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.matcher.WebVideo
import me.him188.ani.datasources.api.matcher.WebVideoDirectResolver
import me.him188.ani.datasources.api.matcher.WebVideoMatcher
import me.him188.ani.datasources.api.matcher.WebVideoMatcherContext
import me.him188.ani.datasources.api.matcher.WebVideoMatcherProvider
import me.him188.ani.datasources.api.matcher.WebViewConfig
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.app.domain.media.fetch.mapKeepingBatches
import me.him188.ani.app.domain.mediasource.web.format.SelectedChannelEpisodes
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.paging.emptySizedSource
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
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException

@Suppress("unused") // bug
private typealias ArgumentType = SelectorMediaSourceArguments
private typealias EngineType = DefaultSelectorMediaSourceEngine

/**
 * [SelectorMediaSource] 的用户侧配置, 用于创建 [SelectorMediaSource] 实例.
 *
 * @since 3.10
 * @see SelectorMediaSourceCodec
 */
@OptIn(DontForgetToRegisterCodec::class)
@Serializable
data class SelectorMediaSourceArguments(
    override val name: String,
    val description: String,
    val iconUrl: String,
    val searchConfig: SelectorSearchConfig = SelectorSearchConfig.Empty,
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
    /**
     * Channel 级别的 tier 覆盖, key 为 channel 名称 (与页面解析出的 channel 名一致).
     * @since 4.9
     */
    override val channelTiers: Map<String, MediaSourceTier> = emptyMap(),
) : MediaSourceArguments {
    companion object {
        val Default = SelectorMediaSourceArguments(
            name = "Selector",
            description = "",
            iconUrl = "",
            searchConfig = SelectorSearchConfig.Empty,
        )
    }
}

object SelectorMediaSourceCodec : DefaultMediaSourceCodec<SelectorMediaSourceArguments>(
    SelectorMediaSource.FactoryId,
    SelectorMediaSourceArguments::class,
    currentVersion = 2,
    SelectorMediaSourceArguments.serializer(),
)

/**
 * @since 3.10
 */
class SelectorMediaSource(
    override val mediaSourceId: String,
    config: MediaSourceConfig,
    val repository: SelectorMediaSourceEpisodeCacheRepository,
    override val kind: MediaSourceKind = MediaSourceKind.WEB,
    private val client: ScopedHttpClient,
    private val sessionManager: WebSessionManager,
) : HttpMediaSource(), WebVideoMatcherProvider, SubjectPageMediaSource {
    companion object {
        val FactoryId = FactoryId("web-selector")

        /**
         * 一次产出整个条目的资源时, 条目集数的上限.
         *
         * 超过它 (柯南、海贼这种上千集的) 就退回只产出当前这一集: 那时源站的条目页往往有同样多的剧集,
         * 给每一集都建资源对象会把低内存设备压垮 (见 `fix(mediasource): 低内存设备看长番时加载或切集被系统杀掉`).
         * 代价是这类条目切集时要换一个查询会话 (见 `servesEpisodeOf`), BT 源随之重新搜索.
         */
        const val MAX_WHOLE_SUBJECT_EPISODES = 300

        /** 一个关键词搜到几个条目页时最多同时开几页 (同规则源; 站点的请求间隔只管搜索请求) */
        private const val PAGE_CONCURRENCY = 2

        /**
         * [matcher] 对 WebView 配置的修改: 依次合并 [config] 已有的 cookies、[matchVideo] 中配置的 cookies 和 [captchaCookies];
         * [userAgent] 非 `null` 时覆盖 [config] 的 User-Agent.
         *
         * 公开给编辑器测试页与外部工具 (如 `tools/datasource-test-mcp`) 复用, 以保证与 App 播放时行为一致.
         *
         * @param userAgent 数据源配置的 UA ([SelectorSearchConfig.userAgentOrNull]).
         * @param captchaCookies 该站点已解出的验证码会话 cookies. HTTP、播放器、各平台共用同一份 cookie 真相.
         */
        fun patchWebViewConfig(
            config: WebViewConfig,
            matchVideo: SelectorSearchConfig.MatchVideoConfig,
            userAgent: String?,
            captchaCookies: List<String> = emptyList(),
        ): WebViewConfig = config.copy(
            cookies = mergeCookies(config.cookies, matchVideo.cookies.lines(), captchaCookies),
            userAgent = userAgent ?: config.userAgent,
        )

        /**
         * 按 cookie 名称合并多组 cookies: 后面列表中的同名 cookie 覆盖前面的, 顺序为名称首次出现的顺序.
         */
        private fun mergeCookies(vararg cookieLists: List<String>): List<String> {
            val merged = linkedMapOf<String, String>()
            for (cookie in cookieLists.asSequence().flatten()) {
                val trimmed = cookie.trim()
                if (trimmed.isBlank()) continue
                val name = trimmed.substringBefore("=").trim()
                if (name.isBlank()) continue
                merged[name] = trimmed
            }
            return merged.values.toList()
        }
    }

    private val arguments =
        config.deserializeArgumentsOrNull(ArgumentType.serializer())
            ?: SelectorMediaSourceArguments.Default
    private val searchConfig = arguments.searchConfig

    private val engine by lazy { EngineType(client) }

    override val location: MediaSourceLocation get() = MediaSourceLocation.Online

    class Factory(
        val repository: SelectorMediaSourceEpisodeCacheRepository,
        val sessionManager: WebSessionManager,
    ) : MediaSourceFactory {
        override val factoryId: FactoryId get() = FactoryId

        override val info: MediaSourceInfo = MediaSourceInfo(
            displayName = "Selector",
            description = "通用 CSS Selector 数据源",
            iconUrl = "",
        )

        override val allowMultipleInstances: Boolean get() = true
        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient
        ): MediaSource =
            SelectorMediaSource(mediaSourceId, config, repository, client = client, sessionManager = sessionManager)
    }

    override suspend fun checkConnection(): ConnectionStatus {
        return kotlin.runCatching {
            runApiRequest {
                client.use {
                    get(searchConfig.searchUrl) // 提交一个请求, 只要它不是因为网络错误就行
                }
            }.fold(
                onSuccess = { ConnectionStatus.SUCCESS },
                onKnownFailure = {
                    when (it) {
                        ApiFailure.NetworkError -> ConnectionStatus.FAILED
                        ApiFailure.ServiceUnavailable -> ConnectionStatus.FAILED
                        ApiFailure.Unauthorized -> ConnectionStatus.SUCCESS
                    }
                },
            )
        }.recover {
            // 只要不是网络错误就行
            ConnectionStatus.SUCCESS
        }.getOrThrow()
    }

    override val info: MediaSourceInfo = MediaSourceInfo(
        displayName = arguments.name,
        description = arguments.description,
        websiteUrl = searchConfig.searchUrl,
        iconUrl = arguments.iconUrl,
        tier = arguments.tier,
    )

    @OptIn(ExperimentalAtomicApi::class)
    private val lastSearchTime = AtomicLong(0L)

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun delayUntilNextAllowedSearch() {
        val interval = searchConfig.requestInterval.inWholeMilliseconds
        while (true) {
            val now = currentTimeMillis()
            val last = lastSearchTime.load()
            val wait = (last + interval) - now
            if (wait > 0) {
                delay(wait)
                continue
            }
            // try to claim the slot
            if (lastSearchTime.compareAndSet(last, now)) return
            // someone else just took it – retry
        }
    }

    /**
     * 获取页面并把被挡状态转换为异常:
     *
     * - 解析成功 → 返回值;
     * - 合法无结果 / 404 → `null`;
     * - [BlockReason.RateLimited] → 延迟后重试一次, 仍失败则抛 [BlockedException];
     * - [BlockReason.Captcha] → 尝试自动解决 (v1: solver 列表为空, 立即失败) → 抛 [BlockedException];
     * - 其他被挡 → 抛 [BlockedException].
     */
    private suspend fun <T> fetchPageOrThrow(
        url: String,
        expectation: PageExpectation<T>,
    ): T? {
        suspend fun once(): PageVerdict<T> = sessionManager.fetchPage(url, expectation)

        var verdict = once()
        ((verdict as? PageVerdict.Blocked)?.reason as? BlockReason.RateLimited)?.let { rateLimited ->
            // 限流不是验证码: 不弹浏览器, 延迟后重试一次
            delay(rateLimited.retryAfter ?: searchConfig.requestInterval)
            delayUntilNextAllowedSearch()
            verdict = once()
        }

        when (val v = verdict) {
            is PageVerdict.Ok -> return v.value
            is PageVerdict.EmptyContent -> return null
            is PageVerdict.Blocked -> {
                val reason = v.reason
                val request = SolveRequest(
                    mediaSourceId = mediaSourceId,
                    pageUrl = url,
                    kind = (reason as? BlockReason.Captcha)?.kind ?: WebCaptchaKind.Unknown,
                    expectation = expectation,
                )
                when (reason) {
                    BlockReason.NotFound -> return null

                    is BlockReason.Captcha -> {
                        if (sessionManager.solve(request, interactive = false) == SolveOutcome.Solved) {
                            delayUntilNextAllowedSearch()
                            when (val retried = once()) {
                                is PageVerdict.Ok -> return retried.value
                                is PageVerdict.EmptyContent -> return null
                                is PageVerdict.Blocked -> throw BlockedException(retried.reason, request)
                            }
                        }
                        throw BlockedException(reason, request)
                    }

                    else -> throw BlockedException(reason, request)
                }
            }
        }
    }

    /**
     * 尝试从 [repository] 缓存中构建搜索结果.
     *
     * 缓存的每一页都要用得上才命中: 页面的剧集列表里有 [SelectorSearchQuery.freshnessProbe] (请求里已上映的最新一集, 未知时为当前剧集),
     * 或者 (只查这一集时) 有当前这一集. 有一页用不上就返回 `null` 走完整搜索 (页面可能已更新, 例如刚开播的新集在缓存里还没有),
     * 不跳过它只用别的页: 同一个词第二次查不能比第一次少.
     * 写这些页的那一轮搜索有页没开完 (被暂停、出错, 见 [WebSearchCache.roundComplete]) 时, 先经 [scope] 交出缓存里的, 再返回 `null` 照常重搜补齐.
     */
    private suspend fun EngineType.searchFromCacheOrNull(
        searchConfig: SelectorSearchConfig,
        query: SelectorSearchQuery,
        mediaSourceId: String,
        subjectId: Int?,
        plan: SubjectSearchPlan,
        scope: KeywordSearchScope?,
    ): List<DefaultMedia>? {
        // 请求普通剧集且按集号过滤时, 只有集号相符的行 (和集号不是纯数字的行) 可能出结果,
        // 不必把长番几千集整页读回内存.
        //
        // **条目级查询要整页读回** (见 [SelectorSearchQuery.subjectEpisodeSorts]): 播放页的会话按条目复用,
        // 只读回当前集的话, 这一页在切集之后产不出任何资源. 窄读因此只留给长番 —— 那时
        // `subjectEpisodeSorts` 本来就是 null.
        val narrowed = query.subjectEpisodeSorts == null &&
                query.episodeSort is EpisodeSort.Normal && searchConfig.autoMatch.filterByEpisodeSort
        val caches = try {
            if (narrowed) {
                repository.getCacheForEpisode(
                    subjectId, mediaSourceId, query.subjectName, query.episodeSort, query.episodeEp,
                )
            } else {
                repository.getCache(subjectId, mediaSourceId, query.subjectName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 读缓存失败不影响功能, 退化为真实搜索. 但要记录, 否则缓存彻底失效时没有任何线索.
            logger.warn(e) { "SelectorMediaSource '$mediaSourceId': failed to read search cache, falling back to search" }
            return null
        }

        val probe = query.freshnessProbe
            ?: SelectorEpisodeProbe(query.episodeSort, query.episodeEp, query.episodeName)
        // 窄读只读回当前这一集的行, 判断页面新旧要找的「已上映的最新一集」不在里面, 每页都会被当成旧的、整个重搜
        // (长番切集就是这样). 另外窄读一次最新那一集: 有它的页面才算新, 仍然不必整页读回
        val freshPages = if (narrowed && probe.episodeSort != query.episodeSort) {
            try {
                repository.getCacheForEpisode(subjectId, mediaSourceId, query.subjectName, probe.episodeSort, probe.episodeEp)
                    .filter { it.webEpisodeInfos.findMatchingEpisodeOrNull(probe.episodeSort, probe.episodeEp, probe.episodeName) != null }
                    .mapTo(HashSet()) { it.webSubjectInfo.fullUrl }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "SelectorMediaSource '$mediaSourceId': failed to read search cache, falling back to search" }
                return null
            }
        } else {
            null
        }
        val media = buildList {
            for (cache in caches) {
                // 写缓存时名字表可能还没读进来, 那时认不出的别的作品的页也开了: 按现在的认部再判一次, 在线搜也不会开它
                if (plan.identify(cache.webSubjectInfo.name) == SubjectMatch.OTHER) continue
                val episodes = cache.webEpisodeInfos.asEpisodesOf(query)
                val fresh = freshPages?.contains(cache.webSubjectInfo.fullUrl)
                    ?: (episodes.findMatchingEpisodeOrNull(probe.episodeSort, probe.episodeEp, probe.episodeName) != null)
                // 页面没有已上映的最新一集 (可能已经更新, 也可能站点把几集合成一条「第12-13集」): 有当前这一集、又只查这一集时照用;
                // 否则整个重搜, 不能跳过这一页只用别的页 —— 那样这一页的资源就悄悄没了, 第二次查比第一次少
                if (!fresh && (query.subjectEpisodeSorts != null ||
                            episodes.findMatchingEpisodeOrNull(query.episodeSort, query.episodeEp, query.episodeName) == null)
                ) {
                    return null
                }
                addAll(
                    selectFilteredMedia(
                        episodes,
                        searchConfig,
                        query,
                        mediaSourceId,
                        subjectName = cache.webSubjectInfo.name,
                    ),
                )
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
     * 配置声明了 [SelectorSearchConfig.onlySupportsPlayers] 时, 当前平台的播放器是否在其中. 不在则自动匹配不发起任何请求.
     */
    private fun isSupportedByPlatformPlayer(): Boolean =
        MediaSourceEngineHelpers.isSupportedByPlatformPlayer(searchConfig.onlySupportsPlayers, info.displayName, logger)

    /**
     * 向站点搜索 [keyword] (搜索计划改写好的词), 返回搜到的条目里可能是这一部的 ([SubjectSearchPlan.pagesToOpen]) 的全部剧集.
     * 不读搜索缓存, 调用方先查 [searchFromCacheOrNull]; 站点回应先查跨条目共用的记忆 ([SiteSearchMemory]).
     */
    private suspend fun EngineType.searchOnline(
        searchConfig: SelectorSearchConfig,
        query: SelectorSearchQuery,
        keyword: String,
        plan: SubjectSearchPlan,
        mediaSourceId: String,
        subjectId: Int?,
        /** 每开完一个条目页就经它先交出这一页的资源 (见 [KeywordSearchScope.emitEarly]) */
        scope: KeywordSearchScope? = null,
    ): List<DefaultMedia> = withContext(SearchDispatchers.parsingContext()) {
        val searchUrl = buildSearchUrl(keyword)
        val originalSubjects = repository.siteMemory.searchResults(mediaSourceId, searchUrl, searchConfig.searchCacheTtl) {
            delayUntilNextAllowedSearch()
            fetchPageOrThrow(searchUrl, PageExpectation.SearchResults(searchConfig))
        } ?: return@withContext emptyList()

        val subjects = plan.pagesToOpen(originalSubjects, searchConfig.autoMatch.preferShorterName) { it.name }
        val probe = query.freshnessProbe ?: SelectorEpisodeProbe(query.episodeSort, query.episodeEp, query.episodeName)

        // 几页同时取 (最多 PAGE_CONCURRENCY 页, 同规则源; 开关关时一页页取), 每取完一页就先写缓存、交出这一页的资源; 返回的仍按搜索计划排的顺序
        val gate = Semaphore(if (DevSwitches.selectorPagesConcurrent.value) PAGE_CONCURRENCY else 1)
        val pages = coroutineScope {
            subjects.map { subjectInfo ->
                // 按搜索计划的顺序占名额再开: 同时在取的总是排在最前的几页
                gate.acquire()
                async {
                    try {
                        loadPage(searchConfig, query, subjectInfo, probe, mediaSourceId, subjectId, scope)
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll()
        }
        // 这一轮要开的页都开成了, 之后读到的缓存才算完整 (见 searchFromCacheOrNull)
        if (pages.none { it == null }) repository.markRoundComplete(subjectId, mediaSourceId, query.subjectName)
        buildList {
            for ((subjectInfo, loaded) in subjects.zip(pages)) {
                val (episodes, selected) = loaded ?: continue
                // 这一页没有要的那一集 (还没更新) 时也留一条这个条目的资源: 关键词链靠产出的资源判断这个关键词搜到了名字对得上的条目
                // (见 searchChain), 一条都没有就会接着用别的关键词再搜. 这条集号对不上, 选源时照常被排除.
                addAll(
                    selected.ifEmpty {
                        selectMedia(episodes.take(1).asSequence(), searchConfig, query, mediaSourceId, subjectInfo.name).originalList
                    },
                )
            }
        }
    }

    /** 打开搜到的一个条目页: 写进搜索缓存, 交出这一页的资源 ([KeywordSearchScope.emitEarly]); 页面出错 (单个条目页的网络错误不终止整个搜索) 为 null. */
    private suspend fun EngineType.loadPage(
        searchConfig: SelectorSearchConfig,
        query: SelectorSearchQuery,
        subjectInfo: WebSearchSubjectInfo,
        probe: SelectorEpisodeProbe,
        mediaSourceId: String,
        subjectId: Int?,
        scope: KeywordSearchScope?,
    ): Pair<List<WebSearchEpisodeInfo>, List<DefaultMedia>>? {
        val episodes = try {
            repository.siteMemory.page(
                mediaSourceId, subjectInfo.fullUrl, searchConfig.searchCacheTtl,
                isFresh = { it.findMatchingEpisodeOrNull(probe.episodeSort, probe.episodeEp, probe.episodeName) != null },
            ) {
                fetchPageOrThrow(
                    subjectInfo.fullUrl,
                    PageExpectation.SubjectDetails(searchConfig, subjectInfo.fullUrl),
                )?.episodes
            }
        } catch (e: BlockedException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "SelectorMediaSource '$mediaSourceId': failed to load subject page ${subjectInfo.fullUrl}" }
            null
        } ?: return null
        repository.addCache(
            subjectId, mediaSourceId, query.subjectName, subjectInfo, episodes,
            sourceCacheTtl = searchConfig.searchCacheTtl,
        )
        val selected = selectFilteredMedia(
            episodes,
            searchConfig,
            query,
            mediaSourceId,
            subjectName = subjectInfo.name,
        )
        scope?.emitEarly(selected)
        return episodes to selected
    }

    override suspend fun mediaOnSubjectPage(subjectUrl: String, subjectName: String, request: MediaFetchRequest): List<Media> {
        if (!searchConfig.autoMatch.enabled || !isSupportedByPlatformPlayer()) return emptyList()
        return withContext(SearchDispatchers.parsingContext()) {
            val episodes = fetchPageOrThrow(subjectUrl, PageExpectation.SubjectDetails(searchConfig, subjectUrl))?.episodes
            if (episodes.isNullOrEmpty()) emptyList()
            else engine.selectFilteredMedia(episodes, searchConfig, request.selectorSearchQuery(subjectName), mediaSourceId, subjectName)
        }
    }

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        if (!searchConfig.autoMatch.enabled) {
            // 只用于浏览手动选集的数据源, 不参与自动匹配
            return emptySizedSource()
        }
        if (!isSupportedByPlatformPlayer()) return emptySizedSource()

        val subjectId = query.subjectId.toIntOrNull()
        fun searchQuery(name: String) = query.selectorSearchQuery(name)

        val plan = SubjectSearchPlan.of(query)
        val keywords = plan.keywords(searchConfig.autoMatch.keywordStyle(), mediaSourceId)
        val remembered = repository.rememberedKeywords(subjectId, mediaSourceId, keywords.primaryNames + keywords.fallbackNames, logger)
        return keywordSearchChain(
            keywords.primaryNames, keywords.fallbackNames, remembered,
            requestInterval = searchConfig.requestInterval,
            matches = plan::foundOwn,
            emitPagesEarly = true,
        ) { name ->
            val searchQuery = searchQuery(name)
            // 搜索缓存: 上一次真实搜索已把条目页面的全部剧集写入缓存 (addCache).
            // 若缓存的剧集列表包含当前请求的剧集 (典型场景: 切集), 直接从缓存构建结果, 不发起任何网络请求.
            // 缓存按 TTL 过期, 也会在该条目手动重新查询时被清除.
            engine.searchFromCacheOrNull(searchConfig, searchQuery, mediaSourceId, subjectId, plan, scope = this@keywordSearchChain)
                ?: run {
                    beforeOnlineSearch()
                    engine.searchOnline(searchConfig, searchQuery, keywords.keywordOf(name), plan, mediaSourceId, subjectId, scope = this@keywordSearchChain)
                }
        }.mapKeepingBatches { MediaMatch(it, MatchKind.FUZZY) }
    }

    // region 浏览: 列表模式, 不做任何自动匹配

    override val supportsBrowsing: Boolean get() = true

    private fun buildSearchUrl(keyword: String): String =
        searchConfig.searchUrl.replace("{keyword}", MediaSourceEngineHelpers.encodeUrlSegment(keyword))

    override suspend fun searchSubjects(keyword: String): List<BrowseSubject> {
        delayUntilNextAllowedSearch()
        val subjects = fetchPageOrThrow(buildSearchUrl(keyword), PageExpectation.SearchResults(searchConfig))
            ?: return emptyList()
        return subjects.map { BrowseSubject(name = it.name, url = it.fullUrl) }
    }

    override suspend fun browseSubject(subject: BrowseSubject): List<BrowseChannel> {
        val selected = fetchPageOrThrow(subject.url, PageExpectation.SubjectDetails(searchConfig, subject.url))
            ?: return emptyList()
        return selected.toBrowseChannels()
    }

    override fun createMedia(
        subject: BrowseSubject,
        channelName: String?,
        episode: BrowseEpisode,
        episodeSort: EpisodeSort?,
    ): Media = engine.createMedia(
        WebSearchEpisodeInfo(
            channel = channelName,
            name = episode.name,
            episodeSortOrEp = episode.episodeSort,
            playUrl = episode.url,
        ),
        episodeSort,
        searchConfig,
        mediaSourceId,
        subjectName = subject.name,
    )

    // endregion

    override val matcher: WebVideoMatcher by lazy {
        object : WebVideoMatcher, WebVideoDirectResolver {
            override fun match(
                url: String,
                context: WebVideoMatcherContext
            ): WebVideoMatcher.MatchResult = engine.matchWebVideo(
                url,
                arguments.searchConfig.matchVideo,
                arguments.searchConfig.userAgentOrNull,
            )

            override suspend fun resolveDirectly(pageUrl: String, context: WebVideoMatcherContext): WebVideo? =
                engine.resolveVideoDirectly(
                    pageUrl,
                    arguments.searchConfig.resolveVideo,
                    arguments.searchConfig.matchVideo,
                    arguments.searchConfig.userAgentOrNull,
                )

            override fun patchConfig(config: WebViewConfig): WebViewConfig = patchWebViewConfig(
                config,
                arguments.searchConfig.matchVideo,
                userAgent = arguments.searchConfig.userAgentOrNull,
                captchaCookies = sessionManager.cookieJar.getCookieHeaderValues(searchConfig.searchUrl),
            )
        }
    }

}

/**
 * 按页面上的播放列表分组, 顺序与页面一致; 没有分组信息时按线路名分组. 没有线路概念的页面得到一个名称为 `null` 的线路.
 * 没有剧集的线路不列出.
 */
internal fun SelectedChannelEpisodes.toBrowseChannels(): List<BrowseChannel> {
    fun List<WebSearchEpisodeInfo>.toBrowseEpisodes() =
        map { BrowseEpisode(name = it.name, url = it.playUrl, episodeSort = it.episodeSortOrEp) }

    channelGroups?.let { groups ->
        return groups.filter { it.episodes.isNotEmpty() }
            .map { BrowseChannel(name = it.name, label = it.label, episodes = it.episodes.toBrowseEpisodes()) }
    }
    if (episodes.isEmpty()) return emptyList()
    if (channels == null) {
        return listOf(BrowseChannel(name = null, label = null, episodes = episodes.toBrowseEpisodes()))
    }
    return episodes.groupBy { it.channel }.map { (channel, episodes) ->
        BrowseChannel(name = channel, label = channel, episodes = episodes.toBrowseEpisodes())
    }
}
