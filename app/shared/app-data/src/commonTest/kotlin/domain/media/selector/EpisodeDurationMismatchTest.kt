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
    fun `a wrong duration switches to the right episode of the same source`() = runFetchMediaSelectorTestSuite {
        initSubject("test")
        val (_, session, sources) = configureFetchSession {
            object {
                val girigiri by web { tier = 0 }
                val other by web { tier = 0 }
            }
        }
        // 同一页上两条线路都是同名的真人电影, 这个源另有正确的第 1 集
        val movie = media(kind = WEB, subjectName = initApi.subjectName, originalTitle = "辉夜 1080P", alliance = "简中")
        val movieOtherLine = media(kind = WEB, subjectName = initApi.subjectName, originalTitle = "辉夜 1080P", alliance = "DB")
        val episode = media(kind = WEB, subjectName = initApi.subjectName, originalTitle = "辉夜 01")
        val elsewhere = media(kind = WEB, subjectName = initApi.subjectName)
        sources.girigiri.complete(movie, movieOtherLine, episode)
        sources.other.complete(elsewhere)
        testScope().runCurrent()
        val selected = selector.filteredCandidatesMedia.first().single { it.mediaId == movie.mediaId }
        selector.select(selected)

        val handler = PlayerLoadErrorHandler(getPreferKind = { WEB }, getSourceTiers = { preferenceApi.sourceTiers!! })
        val job = testScope().launch { handler.switchFromWrongEpisode(session, selector, selected, preferenceApi.sourceTiers!!) }
        testScope().advanceTimeBy(5.seconds)
        testScope().runCurrent()

        assertEquals(true, job.isCompleted)
        assertEquals(episode.mediaId, selector.selected.value?.mediaId)
        // 只排除这部片子的线路, 不动这个源的其它资源
        assertEquals(setOf(movie.mediaId, movieOtherLine.mediaId), handler.blacklistedMedia)
    }

    context(scope: TestScope)
    private fun testScope(): TestScope = implicit()
}
