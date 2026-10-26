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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.models.ApiFailure
import me.him188.ani.app.data.models.fold
import me.him188.ani.app.data.models.runApiRequest
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.media.fetch.wholeSubjectEpisodeSortsOrNull
import me.him188.ani.app.domain.mediasource.MediaSourceEngineHelpers
import me.him188.ani.app.domain.mediasource.codec.DefaultMediaSourceCodec
import me.him188.ani.app.domain.mediasource.codec.DontForgetToRegisterCodec
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.app.domain.mediasource.codec.MediaSourceTier
import me.him188.ani.app.domain.mediasource.keywordSearchChain
import me.him188.ani.app.domain.mediasource.latestAiredEpisode
import me.him188.ani.app.domain.mediasource.rememberedKeywords
import me.him188.ani.app.domain.mediasource.search.SiteSearchMemory
import me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan
import me.him188.ani.app.domain.mediasource.search.keywordStyle
import me.him188.ani.app.domain.mediasource.web.captcha.SolveOutcome
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.matcher.WebVideo
import me.him188.ani.datasources.api.matcher.WebVideoDirectResolver
import me.him188.ani.datasources.api.matcher.WebVideoMatcher
import me.him188.ani.datasources.api.matcher.WebVideoMatcherContext
import me.him188.ani.datasources.api.matcher.WebVideoMatcherProvider
import me.him188.ani.datasources.api.matcher.WebViewConfig
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.paging.map
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
import kotlin.time.Duration.Companion.minutes

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
) : HttpMediaSource(), WebVideoMatcherProvider {
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

        /**
         * 只读回当前一集的缓存时, 页面在这么久之内写入、又有当前这一集, 就直接用, 不因为缺「已上映的最新一集」而重搜.
         * 站点比 Bangumi 晚一两集是常事; 长番切集要换查询会话, 不这样的话每切一集都要把上千集的条目页重新抓一遍、写一遍缓存.
         */
        val RECENT_SEARCH = 30.minutes

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
     * 仅当缓存的条目页面剧集列表中能找到 [SelectorSearchQuery.freshnessProbe] (请求里已上映的最新一集, 未知时为当前剧集)
     * 时才命中, 否则返回 `null` 走完整搜索 (页面可能已更新, 例如刚开播的新集在缓存里还没有).
     */
    private suspend fun EngineType.searchFromCacheOrNull(
        searchConfig: SelectorSearchConfig,
        query: SelectorSearchQuery,
        mediaSourceId: String,
        subjectId: Int?,
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
        val now = currentTimeMillis()
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
        return buildList {
            for (cache in caches) {
                val episodes = cache.webEpisodeInfos
                val fresh = freshPages?.contains(cache.webSubjectInfo.fullUrl)
                    ?: (episodes.findMatchingEpisodeOrNull(probe.episodeSort, probe.episodeEp, probe.episodeName) != null)
                val recentWithCurrent = narrowed && now - cache.cachedAt < RECENT_SEARCH.inWholeMilliseconds &&
                        episodes.findMatchingEpisodeOrNull(query.episodeSort, query.episodeEp, query.episodeName) != null
                if (!fresh && !recentWithCurrent) {
                    continue
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
        }.takeIf(List<DefaultMedia>::isNotEmpty)
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
    ): List<DefaultMedia> = withContext(Dispatchers.Default) {
        val searchUrl = buildSearchUrl(keyword)
        val originalSubjects = repository.siteMemory.searchResults(mediaSourceId, searchUrl, searchConfig.searchCacheTtl) {
            delayUntilNextAllowedSearch()
            fetchPageOrThrow(searchUrl, PageExpectation.SearchResults(searchConfig))
        } ?: return@withContext emptyList()

        val subjects = plan.pagesToOpen(originalSubjects, searchConfig.autoMatch.preferShorterName) { it.name }
        val probe = query.freshnessProbe ?: SelectorEpisodeProbe(query.episodeSort, query.episodeEp, query.episodeName)

        buildList {
            for (subjectInfo in subjects) {
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
                    // 单个条目页的网络错误不终止整个搜索
                    logger.warn(e) { "SelectorMediaSource '$mediaSourceId': failed to load subject page ${subjectInfo.fullUrl}" }
                    null
                } ?: continue
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

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        if (!searchConfig.autoMatch.enabled) {
            // 只用于浏览手动选集的数据源, 不参与自动匹配
            return emptySizedSource()
        }
        if (!isSupportedByPlatformPlayer()) return emptySizedSource()

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
            subjectEpisodeCount = query.mainEpisodeCountOrNull(),
        )

        val plan = SubjectSearchPlan.of(query)
        val keywords = plan.keywords(searchConfig.autoMatch.keywordStyle(), mediaSourceId)
        val remembered = repository.rememberedKeywords(subjectId, mediaSourceId, keywords.primaryNames + keywords.fallbackNames, logger)
        return keywordSearchChain(
            keywords.primaryNames, keywords.fallbackNames, remembered,
            requestInterval = searchConfig.requestInterval,
            matches = plan::foundOwn,
        ) { name ->
            val searchQuery = searchQuery(name)
            // 搜索缓存: 上一次真实搜索已把条目页面的全部剧集写入缓存 (addCache).
            // 若缓存的剧集列表包含当前请求的剧集 (典型场景: 切集), 直接从缓存构建结果, 不发起任何网络请求.
            // 缓存按 TTL 过期, 也会在该条目手动重新查询时被清除.
            engine.searchFromCacheOrNull(searchConfig, searchQuery, mediaSourceId, subjectId)
                ?: run {
                    beforeOnlineSearch()
                    engine.searchOnline(searchConfig, searchQuery, keywords.keywordOf(name), plan, mediaSourceId, subjectId)
                }
        }.map { MediaMatch(it, MatchKind.FUZZY) }
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
