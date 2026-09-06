/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.ui.foundation.playback.PlaybackPreparingStage
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionStatus
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [statusOf]: 动作面板那行状态与播放画面上转圈时那行字同一套分步.
 */
class RetainedPlaybackSessionStatusTest {
    private val idle = PlayerState(MediaStatus.Idle, playWhenReady = false, isBuffering = false)
    private val search = SourceSearchProgress(
        finished = 9,
        total = 14,
        found = 3,
        pendingNames = emptyList(),
        pendingCount = 5,
        stuck = null,
    )

    private fun status(
        loading: VideoLoadingState,
        playerState: PlayerState = idle,
        selection: SelectionProblem = SelectionProblem.None,
        search: SourceSearchProgress? = this.search,
        awaitingBtService: Boolean = false,
    ) = statusOf(loading, playerState, selection, search, awaitingBtService)

    @Test
    fun `only searching sources carries counts`() {
        val searching = PlaybackSessionStatus.Preparing(PlaybackPreparingStage.SearchingSources, 9, 14, 3)
        assertEquals(searching, status(VideoLoadingState.Initial))
        // 换源 / 换集时取消上一次加载留下的状态, 接下来是重新选源
        assertEquals(searching, status(VideoLoadingState.Cancelled))
        assertEquals(
            PlaybackSessionStatus.Preparing(PlaybackPreparingStage.SearchingSources),
            status(VideoLoadingState.Initial, search = null),
        )
    }

    @Test
    fun `stages after selection follow the player`() {
        assertEquals(
            PlaybackSessionStatus.Preparing(PlaybackPreparingStage.ResolvingSource),
            status(VideoLoadingState.ResolvingSource),
        )
        assertEquals(
            PlaybackSessionStatus.Preparing(PlaybackPreparingStage.StartingBtService),
            status(VideoLoadingState.ResolvingSource, awaitingBtService = true),
        )
        assertEquals(
            PlaybackSessionStatus.Preparing(PlaybackPreparingStage.PreparingVideo),
            status(VideoLoadingState.DecodingData(engineKey = null)),
        )
        assertEquals(
            PlaybackSessionStatus.Preparing(PlaybackPreparingStage.FetchingTorrentInfo),
            status(VideoLoadingState.DecodingData(engineKey = MediaCacheEngineKey.Anitorrent)),
        )
    }

    @Test
    fun `after handing over the player state decides`() {
        assertEquals(PlaybackSessionStatus.Buffering, status(VideoLoadingState.Succeed(engineKey = null)))
        assertEquals(
            PlaybackSessionStatus.Ready,
            status(
                VideoLoadingState.Succeed(engineKey = null),
                PlayerState(MediaStatus.Ready, playWhenReady = false, isBuffering = false),
            ),
        )
    }

    @Test
    fun `problems win over preparing`() {
        assertEquals(
            PlaybackSessionStatus.LoadFailed(VideoLoadingState.ResolutionTimedOut),
            status(VideoLoadingState.ResolutionTimedOut),
        )
        assertEquals(
            PlaybackSessionStatus.NeedsSelection,
            status(VideoLoadingState.Initial, selection = SelectionProblem.NeedsManualSelection),
        )
        assertEquals(
            PlaybackSessionStatus.NoMedia,
            status(VideoLoadingState.Initial, selection = SelectionProblem.NoMedia),
        )
    }
}
