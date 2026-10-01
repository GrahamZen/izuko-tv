/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.video.loading

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 地址交给播放器后迟迟开不了播时, 提示要说出等了多久, 没收到数据时说在连接服务器.
 */
class EpisodeVideoLoadingIndicatorTest {
    private fun ComposeUiTest.showPreparing(state: VideoLoadingState.DecodingData, speed: FileSize) {
        // 提示里每秒刷新一次等了多久, 时钟不自己走, 免得等空闲时一直往前推
        mainClock.autoAdvance = false
        setContent {
            ProvideCompositionLocalsForPreview {
                EpisodeVideoLoadingIndicator(state, speedProvider = { speed }, optimizeForFullscreen = false)
            }
        }
        mainClock.advanceTimeByFrame()
    }

    private fun ComposeUiTest.textsContaining(part: String): List<String> =
        onAllNodesWithText(part, substring = true).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        }

    private fun waitedSecondsIn(text: String): Int =
        Regex("""(\d+) s""").find(text)?.groupValues?.get(1)?.toInt() ?: error("No seconds in \"$text\"")

    private fun startedSecondsAgo(seconds: Int) =
        VideoLoadingState.DecodingData(engineKey = null, startedAt = TimeSource.Monotonic.markNow() - seconds.seconds)

    @Test
    fun `just started shows only the stage`() = runAniComposeUiTest {
        showPreparing(VideoLoadingState.DecodingData(engineKey = null), FileSize.Unspecified)
        assertEquals(emptyList(), textsContaining("waited"))
        assertEquals(emptyList(), textsContaining("Waited"))
    }

    @Test
    fun `no data after a while says it is connecting`() = runAniComposeUiTest {
        showPreparing(startedSecondsAgo(12), FileSize.Unspecified)
        val texts = textsContaining("Connecting to the video server, waited")
        assertTrue(texts.isNotEmpty())
        assertTrue(waitedSecondsIn(texts.first()) in 12..20, texts.first())
    }

    @Test
    fun `data arriving shows the speed instead`() = runAniComposeUiTest {
        val speed = (850 * 1024L).bytes
        showPreparing(startedSecondsAgo(12), speed)
        assertEquals(emptyList(), textsContaining("Connecting"))
        val texts = textsContaining("Waited")
        assertTrue(texts.isNotEmpty())
        assertTrue(texts.first().endsWith(" · $speed/s"), texts.first())
        assertTrue(waitedSecondsIn(texts.first()) in 12..20, texts.first())
    }
}
