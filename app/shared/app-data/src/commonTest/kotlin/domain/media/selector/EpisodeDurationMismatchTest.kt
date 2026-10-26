/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.tier
import me.him188.ani.app.domain.player.extension.PlayerLoadErrorHandler
import me.him188.ani.app.domain.player.extension.switchFromWrongEpisode
import me.him188.ani.datasources.api.source.MediaSourceKind.WEB
import me.him188.ani.test.DisabledOnNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** @see me.him188.ani.app.domain.player.extension.observeEpisodeDurationMismatch */
@DisabledOnNative // ContextParameters crashes on Native, 同 PlayerLoadErrorHandlerTest
class EpisodeDurationMismatchTest {
    @Test
    fun `a wrong duration excludes only that resource and switches within the same source`() = runFetchMediaSelectorTestSuite {
        initSubject("test")
        val (_, session, sources) = configureFetchSession {
            object {
                val girigiri by web { tier = 0 }
                val other by web { tier = 0 }
            }
        }
        // 这个源上同名的两页: 真人电影的一条线路, 与 TV 版的一条线路 (是对的)
        val movie = media(kind = WEB, subjectName = initApi.subjectName, originalTitle = "辉夜 1080P", alliance = "简中")
        val sameNameTv = media(kind = WEB, subjectName = initApi.subjectName, originalTitle = "辉夜 1080P", alliance = "DB")
        val elsewhere = media(kind = WEB, subjectName = initApi.subjectName)
        sources.girigiri.complete(movie, sameNameTv)
        sources.other.complete(elsewhere)
        testScope().runCurrent()
        val selected = selector.filteredCandidatesMedia.first().single { it.mediaId == movie.mediaId }
        selector.select(selected)

        val handler = PlayerLoadErrorHandler(getPreferKind = { WEB }, getSourceTiers = { preferenceApi.sourceTiers!! })
        val job = testScope().launch { handler.switchFromWrongEpisode(session, selector, selected, preferenceApi.sourceTiers!!) }
        testScope().advanceTimeBy(5.seconds)
        testScope().runCurrent()

        assertEquals(true, job.isCompleted)
        // 只排除这一条: 同名的线路不一定出自同一页, 先在这个源里换
        assertEquals(sameNameTv.mediaId, selector.selected.value?.mediaId)
        assertEquals(setOf(movie.mediaId), handler.blacklistedMedia)
    }

    context(scope: TestScope)
    private fun testScope(): TestScope = implicit()
}
