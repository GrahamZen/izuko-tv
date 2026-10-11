/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(UnsafeEpisodeSessionApi::class)

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.getCurrentEpisodeId
import me.him188.ani.utils.coroutines.childScope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 某个扩展的回调出错时, 其余扩展照常调用, 换集照常完成 (见 PlayerExtensionManager.call).
 */
class PlayerExtensionCallFailureTest : AbstractPlayerExtensionTest() {
    private class Recording(private val failing: Boolean) : PlayerExtension("Recording-$failing") {
        val started = mutableListOf<Int>()

        override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
            if (failing) error("broken onStart")
            started += episodeSession.episodeId
        }

        override suspend fun onBeforeSwitchEpisode(newEpisodeId: Int) {
            if (failing) error("broken onBeforeSwitchEpisode")
        }
    }

    @Test
    fun `an extension failing before switching episode does not stop the switch`() = runTest {
        val testScope = childScope()
        val suite = EpisodePlayerTestSuite(this, testScope)
        val healthy = Recording(failing = false)
        val state = suite.createState(
            listOf(EpisodePlayerExtensionFactory { _, _ -> Recording(failing = true) }, EpisodePlayerExtensionFactory { _, _ -> healthy }),
        )
        state.onUIReady()
        advanceUntilIdle()
        val initialEpisodeId = state.getCurrentEpisodeId()

        state.switchEpisode(2)
        advanceUntilIdle()

        assertEquals(2, state.getCurrentEpisodeId())
        // 排在出错的扩展后面的也照常启动了, 两集各一次
        assertEquals(listOf(initialEpisodeId, 2), healthy.started)
        testScope.cancel()
    }
}
