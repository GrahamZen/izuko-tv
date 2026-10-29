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
import kotlinx.coroutines.test.runCurrent
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.selector.testFramework.FetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.tier
import me.him188.ani.datasources.api.source.MediaSourceKind.WEB
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.test.DisabledOnNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 本机、局域网上的文件 (本地文件夹、局域网 WebDAV) 紧跟缓存, 先于在线源自动选上 (见 [localFileCandidate]). */
@DisabledOnNative
class MediaAutoSelectorLocalFileTest {
    @Test
    fun `a file on this device is selected before online sources`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val online by web { tier = 0 }
                val local by web { tier = 3 }
            }
        }
        val job = launchSelection(session)
        sources.online.complete(media(kind = WEB, subjectName = "Example Series"))
        sources.local.emitEarly(media(kind = WEB, subjectName = "Example Series", location = MediaSourceLocation.Local))
        testScope().runCurrent()
        assertEquals(sources.local.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `a file on the home network beats the remembered online source`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val online by web { tier = 0 }
                val nas by web { tier = 1 }
            }
        }
        val job = launchSelection(session, preferredSourceId = sources.online.instance.mediaSourceId)
        sources.online.complete(media(kind = WEB, subjectName = "Example Series"))
        sources.nas.emitEarly(media(kind = WEB, subjectName = "Example Series", location = MediaSourceLocation.Lan))
        testScope().runCurrent()
        assertEquals(sources.nas.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `a local source still searching is not waited for`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val (_, session, sources) = configureFetchSession {
            object {
                val online by web { tier = 0 }
                val local by web { tier = 0 }
            }
        }
        val job = launchSelection(session)
        // 盘没接上、NAS 关着: 本地的源一直在查, 不拖住开播
        sources.online.complete(media(kind = WEB, subjectName = "Example Series"))
        testScope().runCurrent()
        assertEquals(sources.online.instance.mediaSourceId, selector.selected.value?.mediaSourceId)
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
    private fun FetchMediaSelectorTestSuite.launchSelection(session: MediaFetchSession, preferredSourceId: String? = null): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            MediaAutoSelector(selector).select(
                session,
                MediaAutoSelector.Config(
                    preferredSourceId = preferredSourceId,
                    web = MediaAutoSelector.Web(sourceTiers = preferenceApi.sourceTiers!!),
                ),
            )
        }

    context(scope: TestScope)
    private fun testScope(): TestScope = implicit()
}
