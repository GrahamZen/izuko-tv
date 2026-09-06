/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.download.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.download.DownloadSnapshot
import me.him188.ani.app.tools.Progress
import me.him188.ani.app.tools.toProgress
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes

class DownloadRowTest {
    @Test
    fun `playback history is converted to clamped progress`() {
        val history = EpisodeHistory(
            episodeId = 1,
            positionMillis = 30_000,
            durationMillis = 60_000,
        )

        assertEquals(0.5f, history.toPlaybackProgress().getOrNull())
        assertEquals(1f, history.copy(positionMillis = 90_000).toPlaybackProgress().getOrNull())
    }

    @Test
    fun `merge progress and waiting for the torrent service are carried over from the snapshot`() {
        val snapshot = DownloadSnapshot(
            id = "1",
            metadata = MediaCacheMetadata(
                subjectId = "1",
                episodeId = "1",
                subjectNames = listOf("Subject"),
                episodeSort = EpisodeSort(1),
                episodeName = "Episode 1",
            ),
            status = MediaCacheState.IN_PROGRESS,
            progress = 1f.toProgress(),
            totalSize = 100.bytes,
            downloadSpeed = 0.bytes,
            canPlay = false,
            mediaSourceId = "source",
            engineKey = MediaCacheEngineKey.WebM3u,
            operation = null,
        )
        snapshot.toDownloadItem(null, null).run {
            assertFalse(isMerging)
            assertEquals(Progress.Unspecified, mergeProgress)
            assertFalse(awaitingTorrentService)
        }
        snapshot.copy(mergeProgress = 0.3f.toProgress()).toDownloadItem(null, null).run {
            assertTrue(isMerging)
            assertEquals(0.3f.toProgress(), mergeProgress)
        }
        snapshot.copy(mergeProgress = Progress.Unspecified).toDownloadItem(null, null).run {
            assertTrue(isMerging)
            assertEquals(Progress.Unspecified, mergeProgress)
        }
        assertTrue(snapshot.copy(awaitingTorrentService = true).toDownloadItem(null, null).awaitingTorrentService)
    }

    @Test
    fun `invalid playback history has unspecified progress`() {
        val history = EpisodeHistory(
            episodeId = 1,
            positionMillis = 30_000,
            durationMillis = 60_000,
        )

        assertEquals(Progress.Unspecified, null.toPlaybackProgress())
        assertEquals(Progress.Unspecified, history.copy(positionMillis = 0).toPlaybackProgress())
        assertEquals(Progress.Unspecified, history.copy(durationMillis = null).toPlaybackProgress())
        assertEquals(Progress.Unspecified, history.copy(durationMillis = 0).toPlaybackProgress())
        assertEquals(Progress.Unspecified, history.copy(deletedAtMillis = 1).toPlaybackProgress())
    }
}
