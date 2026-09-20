/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.utils.coroutines.childScope
import org.openani.mediamp.features.PlaybackSpeed
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSpeedExtensionTest : AbstractPlayerExtensionTest() {
    private val newEpisodeId = 3

    private fun TestScope.createCase(
        playbackSpeedFlow: MutableStateFlow<Float>,
    ): Triple<CoroutineScope, EpisodePlayerTestSuite, EpisodeFetchSelectPlayState> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val testScope = this.childScope()
        val suite = EpisodePlayerTestSuite(this, testScope)

        val state = suite.createState(
            extensions = listOf(PlaybackSpeedExtension.Factory(playbackSpeedFlow)),
        )
        state.onUIReady()
        advanceUntilIdle()
        return Triple(testScope, suite, state)
    }

    private val EpisodePlayerTestSuite.playerSpeed: Float?
        get() = player.features[PlaybackSpeed]?.value

    /**
     * 播放页内切集时倍速必须保持. 播放器在换片源后可能把速度重置回 1x, 扩展需要在新 session 上重新应用.
     */
    @Test
    fun `reapplies the speed after switching episode`() = runTest {
        val speed = MutableStateFlow(1f)
        val (testScope, suite, state) = createCase(speed)
        try {
            speed.value = 1.75f
            advanceUntilIdle()
            assertEquals(1.75f, suite.playerSpeed)

            // 模拟播放器换片源后速度被重置
            suite.player.features[PlaybackSpeed]?.set(1f)
            state.switchEpisode(newEpisodeId)
            advanceUntilIdle()

            assertEquals(1.75f, suite.playerSpeed)
        } finally {
            testScope.cancel()
        }
    }
    /**
     * **同一 session 内**媒体重载 (换片源 / 引擎自己重新 prepare) 把速度重置回 1x 时也要补回来 ——
     * 与上面那条的区别是**不换 session**: 那条走 switchEpisode, 扩展的 onStart 会重跑,
     * 靠的是第一个任务重新收到倍速; 这条只有第二个任务 (mediaData -> 起播 -> 强制补发) 能救.
     *
     * 用例取自上游 f512cf7 (open-ani#3323), 但**上游那份实现没有采纳**: 它在 isMediaLoaded 翻转时
     * 直接 set(speed), 而补发时播放器层面早就是那个值了 —— ExoPlayer 对'设成当前值'直接 return,
     * mediamp 0.3.0 的 set 还另有一道 isPlaying 闸门 (isMediaLoaded 早于它), 两道都过不去.
     *
     * **resume() 不能省**: fork 的契约是'真的开始播了才补'(见 PlaybackSpeedExtension.onStart) ——
     * 会话开始时音频管线还不存在, 那时下发只改得动播放器参数, 声音仍是原速, 正是'假倍速'的成因.
     * 上游那份用例不 resume 就断言, 对 fork 是红的, 那是契约差异不是缺陷.
     */
    @Test
    fun `reapplies the speed after media reloads within the same session`() = runTest {
        val speed = MutableStateFlow(1.75f)
        val (testScope, suite, _) = createCase(speed)
        try {
            advanceUntilIdle()
            assertEquals(1.75f, suite.playerSpeed)

            // 模拟新媒体加载过程中底层把倍速重置回 1x
            suite.player.loadMedia(100_000L)
            suite.player.features[PlaybackSpeed]?.set(1f)
            suite.setMediaDuration(100_000L)
            suite.player.resume()
            advanceUntilIdle()

            assertEquals(1.75f, suite.playerSpeed)
        } finally {
            testScope.cancel()
        }
    }

}
