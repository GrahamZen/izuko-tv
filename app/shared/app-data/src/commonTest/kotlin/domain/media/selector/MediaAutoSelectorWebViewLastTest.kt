/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.selector.testFramework.FetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.tier
import me.him188.ani.app.domain.mediasource.profile.SourceProfiles
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind.WEB
import me.him188.ani.test.DisabledOnNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 解析时要 WebView 嗅探的数据源排在直连的后面 ([MediaSelectorSourceTiers.webViewSources]). */
@DisabledOnNative
class MediaAutoSelectorWebViewLastTest {
    @Test
    fun `exact T0 from a web view source waits for direct sources`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 0 }
                val direct by web { tier = 1 }
            }
        }
        val job = launchSelection(session, webViewSources = setOf(sources.sniffing.instance.mediaSourceId))
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        testScope().advanceTimeBy(1.9.seconds)
        testScope().runCurrent()
        assertNull(selector.selected.value)
        sources.direct.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        assertEquals(sources.direct.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `web view source is selected at once when direct sources find nothing`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 0 }
                val direct by web { tier = 0 }
                val slowSniffing by web { tier = 0 }
            }
        }
        val job = launchSelection(
            session,
            webViewSources = setOf(sources.sniffing.instance.mediaSourceId, sources.slowSniffing.instance.mediaSourceId),
        )
        sources.direct.complete(emptyList<Media>())
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        // 别的源都只能嗅探时照原层级: T0 精确匹配立即选, 不等还在查的嗅探源
        assertEquals(sources.sniffing.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `exact match from a web view source still beats a fuzzy direct match`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 2 }
                val direct by web { tier = 0 }
            }
        }
        val job = launchSelection(session, webViewSources = setOf(sources.sniffing.instance.mediaSourceId))
        sources.direct.complete(media(kind = WEB, subjectName = "Example Series Special"))
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        assertEquals(sources.sniffing.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `lower tier direct exact is selected at once when only web view sources could beat it`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 0 }
                val direct by web { tier = 1 }
                val worse by web { tier = 2 }
            }
        }
        val job = launchSelection(session, webViewSources = setOf(sources.sniffing.instance.mediaSourceId))
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        sources.direct.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        // 还在查的 T2 胜不过 T1, 不必等到五秒的截止时间
        assertEquals(sources.direct.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `web view match is selected when no direct match comes within the grace`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 0 }
                val direct by web { tier = 0 }
            }
        }
        val job = launchSelection(session, webViewSources = setOf(sources.sniffing.instance.mediaSourceId))
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        testScope().advanceTimeBy(1.9.seconds)
        testScope().runCurrent()
        assertNull(selector.selected.value)
        testScope().advanceTimeBy(0.2.seconds)
        testScope().runCurrent()
        assertEquals(sources.sniffing.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `after the grace a direct exact of any tier beats the web view match`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 0 }
                val direct by web { tier = 2 }
                val pending by web { tier = 0 }
            }
        }
        val job = launchSelection(session, webViewSources = setOf(sources.sniffing.instance.mediaSourceId))
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        sources.direct.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        // 还在查的 T0 直连源可能给出更好的, 等到时间
        assertNull(selector.selected.value)
        testScope().advanceTimeBy(2.1.seconds)
        testScope().runCurrent()
        assertEquals(sources.direct.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `rate limited direct source holds the web view fallback only until the last deadline`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 0 }
                val limited by web { tier = 0 }
            }
        }
        val job = launchSelection(session, webViewSources = setOf(sources.sniffing.instance.mediaSourceId))
        // 模糊匹配原本也要等到最后的截止时间, 不按等直连的上限提前
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series Special"))
        sources.limited.rateLimit(retryAfter = 60.seconds)
        testScope().runCurrent()
        testScope().advanceTimeBy(14.seconds)
        testScope().runCurrent()
        assertNull(selector.selected.value)
        testScope().advanceTimeBy(1.seconds)
        testScope().runCurrent()
        assertEquals(sources.sniffing.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `lower tier web view match is selected at the exact deadline while direct sources are still searching`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 1 }
                val pending by web { tier = 0 }
            }
        }
        val job = launchSelection(session, webViewSources = setOf(sources.sniffing.instance.mediaSourceId))
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        // 「立即」档只选 T0
        testScope().advanceTimeBy(4.9.seconds)
        testScope().runCurrent()
        assertNull(selector.selected.value)
        // 到了精确匹配的截止时间不再等还在查的直连源
        testScope().advanceTimeBy(0.2.seconds)
        testScope().runCurrent()
        assertEquals(sources.sniffing.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `lower tier web view match waits only the grace when no better direct source is searching`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 1 }
                val better by web { tier = 0 }
                val sameTier by web { tier = 1 }
            }
        }
        val job = launchSelection(session, webViewSources = setOf(sources.sniffing.instance.mediaSourceId))
        sources.better.complete(emptyList<Media>())
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        testScope().advanceTimeBy(1.9.seconds)
        testScope().runCurrent()
        assertNull(selector.selected.value)
        testScope().advanceTimeBy(0.2.seconds)
        testScope().runCurrent()
        assertEquals(sources.sniffing.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `a source that never played does not hold a web view match`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 0 }
                val neverPlays by web { tier = 0 }
            }
        }
        val job = launchSelection(
            session,
            webViewSources = setOf(sources.sniffing.instance.mediaSourceId),
            neverPlays = setOf(sources.neverPlays.instance.mediaSourceId),
        )
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        assertEquals(sources.sniffing.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `a web view match beats an exact match from a source that never played`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 1 }
                val neverPlays by web { tier = 0 }
                val pending by web { tier = 0 }
            }
        }
        val job = launchSelection(
            session,
            webViewSources = setOf(sources.sniffing.instance.mediaSourceId),
            neverPlays = setOf(sources.neverPlays.instance.mediaSourceId),
        )
        sources.neverPlays.complete(media(kind = WEB, subjectName = "Example Series"))
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        testScope().advanceTimeBy(5.1.seconds)
        testScope().runCurrent()
        assertEquals(sources.sniffing.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    private fun FetchMediaSelectorTestSuite.initWeb() {
        initSubject("Example Series")
        preferenceApi.savedUserPreference.value = MediaPreference.Any
        preferenceApi.mediaSelectorSettings.value = MediaSelectorSettings.AllVisible.copy(
            preferKind = WEB, preferSeasons = false, hideSingleEpisodeForCompleted = false,
        )
    }

    context(scope: TestScope)
    private fun FetchMediaSelectorTestSuite.launchSelection(
        session: MediaFetchSession,
        webViewSources: Set<String>,
        /** 画像里近几天一次都没播起来的源 */
        neverPlays: Set<String> = emptySet(),
    ): Job {
        val tiers = SourceProfiles.demoteFailing(
            preferenceApi.sourceTiers!!.copy(webViewSources = webViewSources),
            SourceProfiles.FailingSources(mostly = neverPlays, never = neverPlays),
        )
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            MediaAutoSelector(selector).select(session, MediaAutoSelector.Config(web = MediaAutoSelector.Web(sourceTiers = tiers)))
        }
    }

    context(scope: TestScope)
    private fun testScope(): TestScope = implicit()
}
