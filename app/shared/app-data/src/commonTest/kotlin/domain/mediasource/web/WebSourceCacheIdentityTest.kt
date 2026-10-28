/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.mediasource.rule.createTestRuleMediaSource
import me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan
import me.him188.ani.app.domain.mediasource.search.keywordStyle
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormatNoChannel
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormatA
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchNameHints
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * 缓存里的条目页按现在的认部再判一次: 写缓存时名字表可能还没读进来, 那时认不出的别的作品 (这里是第一季) 的页也开了,
 * 读回时不再交出它; 这一部的页照常交出.
 */
class WebSourceCacheIdentityTest {
    /** 站点什么都搜不到: 交出的只能来自缓存 */
    private val engine = MockEngine {
        respond("<html><body></body></html>", headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
    }

    private val request = MediaFetchRequest(
        subjectId = "1",
        episodeId = "1",
        subjectNameCN = "继 怪怪守护神",
        subjectNames = listOf("继 怪怪守护神"),
        episodeSort = EpisodeSort(1),
        episodeName = "",
        episodeEp = EpisodeSort(1),
        nameHints = MediaFetchNameHints(
            ownNames = listOf("继 怪怪守护神", "怪怪守护神 第2季"),
            seriesNames = listOf("怪怪守护神"),
            otherWorkNames = listOf("怪怪守护神", "怪怪守护神 第1季"),
        ),
    )

    private val config = SelectorSearchConfig(
        searchUrl = "https://example.com/search?wd={keyword}",
        requestInterval = Duration.ZERO,
        subjectFormatId = SelectorSubjectFormatA.id,
        selectorSubjectFormatA = SelectorSubjectFormatA.Config(selectLists = "div.result > a"),
        channelFormatId = SelectorChannelFormatNoChannel.id,
        selectorChannelFormatNoChannel = SelectorChannelFormatNoChannel.Config(selectEpisodes = ".list > a"),
    )

    /** 在关键词 [keyword] 下缓存一页这一部的、一页第一季的, 都算那一轮搜全了 */
    private suspend fun SelectorMediaSourceEpisodeCacheRepository.cacheBothPages(mediaSourceId: String, keyword: String) {
        for ((id, name) in listOf("2" to "继 怪怪守护神", "1" to "怪怪守护神第一季")) {
            addCache(
                1, mediaSourceId, keyword,
                WebSearchSubjectInfo(id, name, "https://example.com/subject/$id.html", "/subject/$id.html", null),
                listOf(WebSearchEpisodeInfo(null, "第1集", EpisodeSort(1), "https://example.com/play/$id-1.html")),
                sourceCacheTtl = 1.hours,
            )
        }
        markRoundComplete(1, mediaSourceId, keyword)
    }

    private suspend fun MediaSource.deliveredUrls(): Set<String> =
        fetch(request).results.toList().map { it.media.originalUrl }.toSet()

    @Test
    fun `selector source does not deliver a cached page of another work`() = runTest {
        val dao = InMemoryWebSearchSessionCacheDao()
        val source = createTestSelectorMediaSource(config, engine, cacheDao = dao, cacheTtl = 1.hours)
        val keyword = SubjectSearchPlan.of(request).keywords(config.autoMatch.keywordStyle()).primaryNames.first()
        SelectorMediaSourceEpisodeCacheRepository(dao, flowOf(1.hours)).cacheBothPages("test-source", keyword)

        assertEquals(setOf("https://example.com/play/2-1.html"), source.deliveredUrls())
    }

    @Test
    fun `rule source does not deliver a cached page of another work`() = runTest {
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val repository = SelectorMediaSourceEpisodeCacheRepository(InMemoryWebSearchSessionCacheDao(), flowOf(1.hours))
            val source = createTestRuleMediaSource(engine, repository, sessionScope)
            // 测试规则源用默认的自动匹配配置
            val keyword = SubjectSearchPlan.of(request).keywords(SelectorAutoMatchConfig.Default.keywordStyle()).primaryNames.first()
            repository.cacheBothPages("test-source", keyword)

            assertEquals(setOf("https://example.com/play/2-1.html"), source.deliveredUrls())
        } finally {
            sessionScope.cancel()
        }
    }
}
