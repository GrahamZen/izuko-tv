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
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.selector.testFramework.FetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.tier
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind.WEB
import me.him188.ani.test.DisabledOnNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 还在查的源已经交出的精确匹配按选源阶段接纳 (见 [MediaAutoSelector]). */
@DisabledOnNative
class MediaAutoSelectorWorkingSourceTest {
    @Test
    fun `exact match from a lower tier source still searching is selected at the exact stage`() =
        runFetchMediaSelectorTestSuite {
                initWeb()
            val (_, session, sources) = configureFetchSession {
                object {
                    val best by web { tier = 0 }
                    val other by web { tier = 1 }
                }
            }
            val job = launchSelection(session)
            sources.other.emitEarly(media(kind = WEB, subjectName = "Example Series"))
            testScope().advanceTimeBy(4.9.seconds)
            testScope().runCurrent()
            // 「立即」档不接纳层级不够的, T0 还在查
            assertNull(selector.selected.value)
            assertEquals(MediaSourceFetchState.Working, session.mediaSourceResults.first { it.mediaSourceId == "other" }.state.value)

            testScope().advanceTimeBy(0.2.seconds)
            testScope().runCurrent()
            assertEquals(sources.other.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
            assertTrue(job.isCompleted)
        }

    @Test
    fun `fuzzy match from a source still searching waits for it to finish`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val best by web { tier = 0 }
                val other by web { tier = 1 }
            }
        }
        val job = launchSelection(session)
        sources.other.emitEarly(media(kind = WEB, subjectName = "Example Series Special"))
        testScope().advanceTimeBy(20.seconds)
        testScope().runCurrent()
        assertNull(selector.selected.value)

        // 查完后过了模糊匹配的截止时间, 选它
        sources.other.complete(emptyList<Media>())
        testScope().runCurrent()
        assertEquals(sources.other.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `exact T0 from a source still searching is selected at once`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val best by web { tier = 0 }
                val other by web { tier = 1 }
            }
        }
        val job = launchSelection(session)
        sources.best.emitEarly(media(kind = WEB, subjectName = "Example Series"))
        testScope().advanceTimeBy(0.5.seconds)
        testScope().runCurrent()
        assertEquals(sources.best.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `after the web view grace a direct exact from a source still searching beats the web view match`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val sniffing by web { tier = 0 }
                val direct by web { tier = 1 }
                val betterDirect by web { tier = 0 }
            }
        }
        val job = launchSelection(session, webViewSources = setOf(sources.sniffing.instance.mediaSourceId))
        sources.sniffing.complete(media(kind = WEB, subjectName = "Example Series"))
        // 直连的 T1 交出了精确匹配, 还在开别的页; T0 直连源还没有结果, 等直连的期间它可能胜过
        sources.direct.emitEarly(media(kind = WEB, subjectName = "Example Series"))
        testScope().advanceTimeBy(1.9.seconds)
        testScope().runCurrent()
        assertNull(selector.selected.value)

        // 等够了: 任意层级的直连精确匹配胜过要嗅探的
        testScope().advanceTimeBy(0.2.seconds)
        testScope().runCurrent()
        assertEquals(sources.direct.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
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
    private fun FetchMediaSelectorTestSuite.launchSelection(session: MediaFetchSession, webViewSources: Set<String> = emptySet()): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            MediaAutoSelector(selector).select(
                session,
                MediaAutoSelector.Config(
                    web = MediaAutoSelector.Web(sourceTiers = preferenceApi.sourceTiers!!.copy(webViewSources = webViewSources)),
                ),
            )
        }

    context(scope: TestScope)
    private fun testScope(): TestScope = implicit()
}
