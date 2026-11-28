/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.videoplayer.link.PlaybackStallReopen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * 等数据卡死时原地重开: 带着卡住的资源回到卡住的位置; 同一条资源最多重开几次、两次之间隔多久; 换了资源重新算; 没选中资源时不做.
 */
class PlaybackStallReopenExtensionTest {
    private val player = Any()
    private val time = TestTimeSource()
    private var mediaId: String? = "a"
    private val reloads = mutableListOf<Pair<String, Long>>()

    private val extension = PlaybackStallReopenExtension(
        player = player,
        requests = emptyFlow(),
        currentMediaId = { mediaId },
        reload = { media, position -> reloads += media to position; true },
        timeSource = time,
    )

    private fun request(positionMillis: Long) = PlaybackStallReopen.Request(player, positionMillis, "test")

    @Test
    fun `reopens at the stalled position`() = runTest {
        assertTrue(extension.handle(request(5_000)))
        assertEquals(listOf("a" to 5_000L), reloads)
    }

    @Test
    fun `waits between reopens and stops after the limit`() = runTest {
        assertTrue(extension.handle(request(1_000)))
        // 刚重开过: 不再重开
        assertFalse(extension.handle(request(1_000)))
        repeat(PlaybackStallReopenExtension.MAX_REOPENS - 1) {
            time += PlaybackStallReopenExtension.MIN_INTERVAL + 1.seconds
            assertTrue(extension.handle(request(2_000)))
        }
        time += PlaybackStallReopenExtension.MIN_INTERVAL + 1.seconds
        assertFalse(extension.handle(request(3_000)))
        assertEquals(PlaybackStallReopenExtension.MAX_REOPENS, reloads.size)
    }

    @Test
    fun `stalls far apart are counted separately`() = runTest {
        repeat(PlaybackStallReopenExtension.MAX_REOPENS) {
            extension.handle(request(5_000))
            time += PlaybackStallReopenExtension.MIN_INTERVAL + 1.seconds
        }
        assertFalse(extension.handle(request(5_000)))
        // 往下播了好几分钟又卡在另一处
        assertTrue(extension.handle(request(291_000)))
    }

    @Test
    fun `another media starts counting again`() = runTest {
        repeat(PlaybackStallReopenExtension.MAX_REOPENS) {
            extension.handle(request(1_000))
            time += PlaybackStallReopenExtension.MIN_INTERVAL + 1.seconds
        }
        mediaId = "b"
        assertTrue(extension.handle(request(1_000)))
    }

    @Test
    fun `does nothing without a selected media`() = runTest {
        mediaId = null
        assertFalse(extension.handle(request(1_000)))
        assertEquals(emptyList(), reloads)
    }
}
