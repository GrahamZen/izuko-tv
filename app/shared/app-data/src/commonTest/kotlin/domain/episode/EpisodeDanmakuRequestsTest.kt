/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.models.subject.TestSubjectCollections
import me.him188.ani.app.domain.media.TestMediaList
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EpisodeDanmakuRequestsTest {
    @Test
    fun `request goes out before the player opens the video`() = runTest {
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        val bundle = createBundle()

        val request = episodeDanmakuRequests(flowOf(bundle), player, flowOf(null)).first()

        assertEquals(bundle.episodeId, request.episodeId)
        assertNull(request.filename)
        assertNotNull(request.awaitVideoFile)
    }

    @Test
    fun `video file is the one the player opens after the request`() = runTest {
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        player.setMediaData(UriMediaData("https://example.com/previous-episode.mp4"))
        val selected = TestMediaList.first()

        val request = episodeDanmakuRequests(flowOf(createBundle()), player, flowOf(selected)).first()
        val file = async { request.awaitVideoFile!!.invoke() }
        runCurrent()
        assertFalse(file.isCompleted, "播放器里还是上一集的视频, 不算")

        player.setMediaData(UriMediaData("https://example.com/this-episode.mp4"))
        assertEquals(selected.originalTitle, file.await()?.filename)
    }

    @Test
    fun `gives up waiting for the video file after a while`() = runTest {
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        val request = episodeDanmakuRequests(flowOf(createBundle()), player, flowOf(TestMediaList.first())).first()

        assertNull(request.awaitVideoFile!!.invoke(), "迟迟没开播: 不按文件匹配, 免得整个匹配超时重来")
    }

    private fun createBundle(): SubjectEpisodeInfoBundle {
        val subjectCollection = TestSubjectCollections.first()
        val episode = subjectCollection.episodes.first()
        return SubjectEpisodeInfoBundle(
            subjectId = subjectCollection.subjectInfo.subjectId,
            episodeId = episode.episodeInfo.episodeId,
            subjectCollectionInfo = subjectCollection,
            episodeCollectionInfo = episode,
            seriesInfo = SubjectSeriesInfo.Fallback,
            subjectCompleted = false,
        )
    }
}
