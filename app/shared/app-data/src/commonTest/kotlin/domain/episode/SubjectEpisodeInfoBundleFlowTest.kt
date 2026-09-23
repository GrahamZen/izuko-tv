/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.createTestSubjectCollection
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 条目先于剧集列表到达时 (从播放记录直接进播放页), 播放页要等剧集列表, 不能当场报「找不到这一集」.
 */
class SubjectEpisodeInfoBundleFlowTest {
    private val subjectId = 356774
    private val episodeId = 1110573

    private val episode = EpisodeCollectionInfo(
        EpisodeInfo(episodeId = episodeId, type = EpisodeType.MainStory, sort = EpisodeSort(1)),
        UnifiedCollectionType.DONE,
    )
    private val subjectWithoutEpisodes = createTestSubjectCollection(subjectId, emptyList(), UnifiedCollectionType.DOING)
    private val subjectWithEpisodes = createTestSubjectCollection(subjectId, listOf(episode), UnifiedCollectionType.DOING)

    @Test
    fun `剧集列表晚到时等它，不报错`() = runTest {
        val subjects = flow {
            emit(subjectWithoutEpisodes)
            delay(300) // 剧集列表从 Bangumi 拉回来
            emit(subjectWithEpisodes)
        }
        val bundles = subjectEpisodeInfoBundleFlow(subjects, subjectId, episodeId).toList()
        assertEquals(listOf(episodeId), bundles.map { it.episodeId })
        assertEquals(episode, bundles.single().episodeCollectionInfo)
    }

    @Test
    fun `一直没有这一集，等满时限才报错`() = runTest {
        val subjects = flow {
            emit(subjectWithoutEpisodes)
            awaitCancellation()
        }
        assertFailsWith<NoSuchElementException> {
            subjectEpisodeInfoBundleFlow(subjects, subjectId, episodeId).toList()
        }
        assertEquals(EPISODE_ARRIVAL_WAIT.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `数据流结束了还没有这一集，立刻报错`() = runTest {
        assertFailsWith<NoSuchElementException> {
            subjectEpisodeInfoBundleFlow(flowOf(subjectWithoutEpisodes), subjectId, episodeId).toList()
        }
        assertTrue(currentTime < EPISODE_ARRIVAL_WAIT.inWholeMilliseconds)
    }

    @Test
    fun `找到之后后来的数据缺这一集，沿用上一次`() = runTest {
        val subjects = flow {
            emit(subjectWithEpisodes)
            delay(EPISODE_ARRIVAL_WAIT * 2)
            emit(subjectWithoutEpisodes)
        }
        val bundles = subjectEpisodeInfoBundleFlow(subjects, subjectId, episodeId).toList()
        assertEquals(1, bundles.size)
    }
}
