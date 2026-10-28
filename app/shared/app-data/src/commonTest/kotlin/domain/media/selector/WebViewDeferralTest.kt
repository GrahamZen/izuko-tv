/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.resolver.WebViewDeferral
import me.him188.ani.app.domain.media.selector.testFramework.FetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.tier
import me.him188.ani.app.domain.player.extension.PlayerLoadErrorHandler
import me.him188.ani.app.domain.player.extension.WebViewDeferralExtension
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind.WEB
import me.him188.ani.test.DisabledOnNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 选中的网页资源解析到要转去 WebView 嗅探时, 改选手上不用嗅探的资源 (见 [WebViewDeferral], [WebViewDeferralExtension]).
 */
@DisabledOnNative // TODO: ContextParameters crashes on Native
class WebViewDeferralTest {
    @Test
    fun `switches to a direct exact media when the selected one needs the web view`() = runFetchMediaSelectorTestSuite {
        initSubject("test")
        val (_, _, sources) = configureFetchSession {
            object {
                val fallback by web { tier = 0 }
                val direct by web { tier = 1 }
            }
        }
        sources.fallback.complete(media(kind = WEB, subjectName = initApi.subjectName))
        sources.direct.complete(media(kind = WEB, subjectName = initApi.subjectName))
        testScope().runCurrent()
        val (sniffing, direct) = mediaOf(sources.fallback.instance.mediaSourceId, sources.direct.instance.mediaSourceId)
        selector.select(sniffing)
        val deferred = MutableStateFlow(emptySet<String>())

        assertTrue(defer(sniffing, setOf(sniffing.mediaId), deferred))
        assertEquals(direct.mediaId, selector.selected.value?.mediaId)
        assertEquals(setOf(sniffing.mediaId), deferred.value)

        // 每个资源只改选一次: 又选到它 (别的都不行时) 就嗅探
        selector.select(sniffing)
        assertFalse(defer(sniffing, setOf(sniffing.mediaId), deferred))
        assertEquals(sniffing.mediaId, selector.selected.value?.mediaId)
        // 这里没有换源时拉黑的监听来取走改选的记号, 自己取走, 免得留给别的测试
        WebViewDeferral.takeSwitchingAway(sniffing.mediaId)
    }

    @Test
    fun `a media switched away from for the web view is not blacklisted and is tried last`() = runFetchMediaSelectorTestSuite {
        initSubject("test")
        val (_, session, sources) = configureFetchSession {
            object {
                val fallback by web { tier = 0 }
                val direct by web { tier = 1 }
            }
        }
        sources.fallback.complete(media(kind = WEB, subjectName = initApi.subjectName))
        sources.direct.complete(media(kind = WEB, subjectName = initApi.subjectName))
        testScope().runCurrent()
        val fallbackId = sources.fallback.instance.mediaSourceId
        val (sniffing, direct) = mediaOf(fallbackId, sources.direct.instance.mediaSourceId)
        // 改选之后它的源记成了要嗅探, 选源只拿它兜底
        val tiersAfter = preferenceApi.sourceTiers!!.copy(webViewSources = setOf(fallbackId))
        val handler = PlayerLoadErrorHandler(getPreferKind = { WEB }, getSourceTiers = { tiersAfter })
        val observing = testScope().launch { handler.observeMediaSelectorBlacklist(flowOf(selector)) }
        testScope().runCurrent()
        selector.select(sniffing)
        testScope().runCurrent()
        val deferred = MutableStateFlow(emptySet<String>())

        assertTrue(defer(sniffing, setOf(sniffing.mediaId), deferred))
        testScope().runCurrent()
        assertEquals(direct.mediaId, selector.selected.value?.mediaId)
        assertFalse(sniffing.mediaId in handler.blacklist)

        // 直连的那份也播不了: 换源回到它 (只剩它), 这次不再改选, 直接嗅探
        val switching = testScope().launch { handler.handleError(session, selector) }
        testScope().advanceTimeBy(3.seconds)
        testScope().runCurrent()
        assertTrue(switching.isCompleted)
        assertEquals(sniffing.mediaId, selector.selected.value?.mediaId)
        assertEquals(setOf(direct.mediaId), handler.blacklist)
        assertFalse(defer(sniffing, setOf(sniffing.mediaId, direct.mediaId), deferred, tiersAfter))
        observing.cancel()
    }

    @Test
    fun `keeps sniffing without a usable direct alternative`() = runFetchMediaSelectorTestSuite {
        initSubject("test")
        val (_, _, sources) = configureFetchSession {
            object {
                val fallback by web { tier = 0 }
                val other by web { tier = 1 }
            }
        }
        sources.fallback.complete(media(kind = WEB, subjectName = initApi.subjectName))
        sources.other.complete(media(kind = WEB, subjectName = initApi.subjectName))
        testScope().runCurrent()
        val fallbackId = sources.fallback.instance.mediaSourceId
        val otherId = sources.other.instance.mediaSourceId
        val (sniffing, other) = mediaOf(fallbackId, otherId)
        selector.select(sniffing)
        val tiers = preferenceApi.sourceTiers!!

        // 另一个也要嗅探
        assertFalse(defer(sniffing, setOf(sniffing.mediaId), tiers = tiers.copy(webViewSources = setOf(otherId))))
        // 另一个选过 (播不了被换掉的)
        assertFalse(defer(sniffing, setOf(sniffing.mediaId, other.mediaId)))
        // 选源时就知道它要嗅探, 是兜底选上的
        assertFalse(defer(sniffing, setOf(sniffing.mediaId), tiers = tiers.copy(webViewSources = setOf(fallbackId))))
        assertEquals(sniffing.mediaId, selector.selected.value?.mediaId)
    }

    @Test
    fun `before sniff waits for the resolution to be cancelled after switching`() = runTest {
        val media = TestMediaList.first()
        val registration = WebViewDeferral.register { true }
        try {
            val waiting = launch { WebViewDeferral.beforeSniff(media) }
            advanceTimeBy(1.seconds)
            assertTrue(waiting.isActive)
            waiting.cancel()

            // 改选没生效 (解析一直没被取消) 时等一会儿照常嗅探
            val proceeding = launch { WebViewDeferral.beforeSniff(media) }
            advanceTimeBy(6.seconds)
            assertTrue(proceeding.isCompleted)
        } finally {
            registration.close()
        }
        // 没有登记的会话时不等
        val free = launch { WebViewDeferral.beforeSniff(media) }
        runCurrent()
        assertTrue(free.isCompleted)
    }

    private suspend fun FetchMediaSelectorTestSuite.mediaOf(vararg sourceIds: String): List<Media> {
        val candidates = selector.filteredCandidatesMedia.first()
        return sourceIds.map { id -> candidates.single { it.mediaSourceId == id } }
    }

    private suspend fun FetchMediaSelectorTestSuite.defer(
        media: Media,
        attempted: Set<String>,
        deferred: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        tiers: MediaSelectorSourceTiers = preferenceApi.sourceTiers!!,
    ): Boolean = WebViewDeferralExtension.deferToDirectAlternative(selector, media, tiers, attempted, deferred, now = { 0 })

    context(scope: TestScope)
    private fun testScope(): TestScope = implicit()
}
