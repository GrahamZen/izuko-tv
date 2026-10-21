/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import me.him188.ani.app.domain.media.selector.filter.isPublishedLongBeforeAiring
import me.him188.ani.app.domain.media.selector.testFramework.assertMedias
import me.him188.ani.app.domain.media.selector.testFramework.runSimpleMediaSelectorTestSuite
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.UTC9
import me.him188.ani.test.DisabledOnNative
import me.him188.ani.test.TestContainer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * 发布得比这一集开播早得多的资源不是这一集 (同名的前几季、老番): 排除, 原因写「早于本集开播」.
 */
@TestContainer
@DisabledOnNative // TODO: ContextParameters crashes on Native
class MediaSelectorPublishedBeforeAiringTest {
    private val airDate = PackedDate.parseFromDate("2026-10-03")
    private val airStart = LocalDate(2026, 10, 3).atStartOfDayIn(UTC9).toEpochMilliseconds()
    private fun relativeToAiring(offset: Duration) = airStart + offset.inWholeMilliseconds

    @Test
    fun `a torrent from months before the episode aired is excluded and a fresh one stays`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("孤独摇滚")
            preferenceApi.mediaSelectorContext.value = preferenceApi.mediaSelectorContext.value.let {
                it.copy(subjectFinished = false, episodeInfo = it.episodeInfo!!.copy(airDate = airDate))
            }
            mediaApi.addMedia(
                media(mediaId = "old", publishedTime = relativeToAiring((-90).days)),
                media(mediaId = "new", publishedTime = relativeToAiring(3.hours)),
            )
        },
    ) {
        assertMedias {
            onSingle(mediaId = "old").assert(included = false, exclusionReason = MediaExclusionReason.PublishedBeforeAiring)
            onSingle(mediaId = "new").assert(included = true)
        }
    }

    @Test
    fun `early streaming a few days ahead and resources without times are kept`() = runSimpleMediaSelectorTestSuite(
        buildTest = {
            initSubject("孤独摇滚")
            preferenceApi.mediaSelectorContext.value = preferenceApi.mediaSelectorContext.value.let {
                it.copy(subjectFinished = false, episodeInfo = it.episodeInfo!!.copy(airDate = airDate))
            }
            mediaApi.addMedia(
                media(mediaId = "early", publishedTime = relativeToAiring((-5).days)),
                media(mediaId = "unknown", publishedTime = 0),
            )
        },
    ) {
        assertMedias {
            onSingle(mediaId = "early").assert(included = true)
            onSingle(mediaId = "unknown").assert(included = true)
        }
    }

    @Test
    fun `only a known air date and a plausible publish time are judged`() {
        val longAgo = relativeToAiring((-90).days)
        assertTrue(isPublishedLongBeforeAiring(longAgo, airDate))
        assertFalse(isPublishedLongBeforeAiring(longAgo, PackedDate.Invalid))
        assertFalse(isPublishedLongBeforeAiring(longAgo, null))
        assertFalse(isPublishedLongBeforeAiring(0, airDate))
        // 给成秒的时间戳不当真
        assertFalse(isPublishedLongBeforeAiring(longAgo / 1000, airDate))
        assertFalse(isPublishedLongBeforeAiring(relativeToAiring((-6).days), airDate))
        assertTrue(isPublishedLongBeforeAiring(relativeToAiring((-8).days), airDate))
    }
}
