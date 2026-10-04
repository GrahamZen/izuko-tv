/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.annotation.OptIn as AndroidxOptIn

/**
 * 被限速的网盘直链: 缓冲上限放宽, 开播前与卡住后多攒一些再播 (同一个媒体越卡攒得越多), 播放跟不上时多开连接;
 * 其余源同 ExoPlayer 默认 (上限 50 秒, 卡住后 2 秒就播).
 */
@AndroidxOptIn(UnstableApi::class)
class ThrottledSourceLoadControlTest {
    // DefaultLoadControl 会到时间线里查这个媒体 (看是不是本地播放), 要一个真的时间线
    private val timeline = SinglePeriodTimeline(1_000_000_000L, true, false, false, null, MediaItem.EMPTY)

    private fun parameters(bufferedSeconds: Double, rebuffering: Boolean = false, playWhenReady: Boolean = true) = LoadControl.Parameters(
        PlayerId.UNSET,
        timeline,
        MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
        0,
        (bufferedSeconds * 1_000_000).toLong(),
        1f,
        playWhenReady,
        rebuffering,
        0,
        0,
    )

    private fun loadControl(throttled: Boolean) = ThrottledSourceLoadControl().apply {
        this.throttled = throttled
        onPrepared(PlayerId.UNSET)
    }

    @Test
    fun `played content is kept 30 seconds, 5 seconds with the disk cache on`() {
        assertEquals(30_000_000L, ThrottledSourceLoadControl().getBackBufferDurationUs(PlayerId.UNSET))
        assertEquals(5_000_000L, ThrottledSourceLoadControl(diskCacheOn = true).getBackBufferDurationUs(PlayerId.UNSET))
        assertTrue(ThrottledSourceLoadControl(diskCacheOn = true).retainBackBufferFromKeyframe(PlayerId.UNSET))
    }

    @Test
    fun `ordinary sources buffer up to 50 seconds like the default`() {
        val control = loadControl(throttled = false)
        assertTrue(control.shouldContinueLoading(parameters(10.0)))
        assertFalse(control.shouldContinueLoading(parameters(50.0)))
    }

    @Test
    fun `throttled sources keep loading up to the larger limit`() {
        val control = loadControl(throttled = true)
        assertTrue(control.shouldContinueLoading(parameters(60.0)))
        assertFalse(control.shouldContinueLoading(parameters(121.0)))
    }

    @Test
    fun `paused throttled sources stop loading at 60 seconds`() {
        val throttled = loadControl(throttled = true)
        assertTrue(throttled.shouldContinueLoading(parameters(59.0, playWhenReady = false)))
        assertFalse(throttled.shouldContinueLoading(parameters(60.0, playWhenReady = false)))
        // 恢复播放后照旧攒到大的上限
        assertTrue(throttled.shouldContinueLoading(parameters(60.0)))

        // 普通源暂停时照默认
        val ordinary = loadControl(throttled = false)
        assertTrue(ordinary.shouldContinueLoading(parameters(45.0, playWhenReady = false)))
        assertFalse(ordinary.shouldContinueLoading(parameters(50.0, playWhenReady = false)))
    }

    @Test
    fun `seek preview only loads what the frame needs and counts as ready at once`() {
        val control = loadControl(throttled = true)
        control.previewing = true
        assertTrue(control.shouldContinueLoading(parameters(0.5, playWhenReady = false)))
        assertFalse(control.shouldContinueLoading(parameters(1.0, playWhenReady = false)))
        assertTrue(control.shouldStartPlayback(parameters(0.0, playWhenReady = false)))

        // 预览结束后照旧
        control.previewing = false
        assertTrue(control.shouldContinueLoading(parameters(1.0, playWhenReady = false)))
        assertFalse(control.shouldStartPlayback(parameters(3.0, rebuffering = true)))
    }

    @Test
    fun `seek preview parked on a position buffers like a paused player`() {
        val throttled = loadControl(throttled = true)
        throttled.previewing = true
        throttled.previewParked = true
        assertTrue(throttled.shouldContinueLoading(parameters(30.0, playWhenReady = false)))
        assertFalse(throttled.shouldContinueLoading(parameters(61.0, playWhenReady = false)))
        assertTrue(throttled.shouldStartPlayback(parameters(0.0, playWhenReady = false)))

        val ordinary = loadControl(throttled = false)
        ordinary.previewing = true
        ordinary.previewParked = true
        assertTrue(ordinary.shouldContinueLoading(parameters(30.0, playWhenReady = false)))
        assertFalse(ordinary.shouldContinueLoading(parameters(51.0, playWhenReady = false)))

        // 又挪了一步: 回到只读出画面所需
        ordinary.previewParked = false
        assertFalse(ordinary.shouldContinueLoading(parameters(1.0, playWhenReady = false)))
    }

    @Test
    fun `throttled sources buffer 5 seconds before starting, ordinary ones start at once`() {
        val throttled = loadControl(throttled = true)
        assertFalse(throttled.shouldStartPlayback(parameters(1.5)))
        assertEquals(ThrottledBufferWait(targetMillis = 5_000, stall = 0), throttled.bufferWait.value)
        assertTrue(throttled.shouldStartPlayback(parameters(5.0)))
        assertNull(throttled.bufferWait.value)

        val ordinary = loadControl(throttled = false)
        assertTrue(ordinary.shouldStartPlayback(parameters(1.5)))
        assertNull(ordinary.bufferWait.value)
    }

    @Test
    fun `each stall of the same media waits twice as long, up to 60 seconds`() {
        val throttled = loadControl(throttled = true)
        val waits = mutableListOf<Long>()
        repeat(5) {
            // 一次卡住期间会问很多次, 只算一次
            assertFalse(throttled.shouldStartPlayback(parameters(1.0, rebuffering = true)))
            assertFalse(throttled.shouldStartPlayback(parameters(2.0, rebuffering = true)))
            val wait = throttled.bufferWait.value!!
            assertEquals(it + 1, wait.stall)
            waits += wait.targetMillis
            assertTrue(throttled.shouldStartPlayback(parameters(wait.targetMillis / 1000.0, rebuffering = true)))
        }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L), waits)

        // 卡住后照默认 2 秒就播
        val ordinary = loadControl(throttled = false)
        assertTrue(ordinary.shouldStartPlayback(parameters(3.0, rebuffering = true)))
    }

    @Test
    fun `seeking away during a stall ends it, and a new media starts counting again`() {
        val throttled = loadControl(throttled = true)
        assertFalse(throttled.shouldStartPlayback(parameters(1.0, rebuffering = true)))
        // 卡住时拖走了: 按开播的门槛等, 下一次卡住才是第 2 次
        assertFalse(throttled.shouldStartPlayback(parameters(1.0)))
        assertEquals(ThrottledBufferWait(5_000, 0), throttled.bufferWait.value)
        assertTrue(throttled.shouldStartPlayback(parameters(5.0)))
        assertFalse(throttled.shouldStartPlayback(parameters(15.0, rebuffering = true)))
        assertEquals(ThrottledBufferWait(20_000, 2), throttled.bufferWait.value)

        // 换媒体 (准备新媒体时会重设 throttled): 从第 1 次算, 等待提示也清掉
        throttled.throttled = true
        assertNull(throttled.bufferWait.value)
        assertTrue(throttled.shouldStartPlayback(parameters(10.0, rebuffering = true)))

        // 停止后同样从头算
        assertFalse(throttled.shouldStartPlayback(parameters(1.0, rebuffering = true)))
        throttled.onStopped(PlayerId.UNSET)
        assertNull(throttled.bufferWait.value)
        assertTrue(throttled.shouldStartPlayback(parameters(10.0, rebuffering = true)))
    }

    @Test
    fun `playing faster needs fewer seconds of buffer`() {
        val throttled = loadControl(throttled = true)
        val fast = LoadControl.Parameters(
            PlayerId.UNSET, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0, 2_600_000, 2f, true, false, 0, 0,
        )
        assertTrue(throttled.shouldStartPlayback(fast))
    }

    @Test
    fun `throttled sources get more connections while the buffer is low`() {
        val control = loadControl(throttled = true)
        // 开播 / 跳转后缓冲从零攒起, 一开始就多开
        control.shouldContinueLoading(parameters(0.5))
        assertTrue(control.boosted)
        assertTrue(control.shouldStartPlayback(parameters(5.0)))

        control.shouldContinueLoading(parameters(20.0))
        assertTrue(control.boosted)
        // 回到 60 秒才收回
        control.shouldContinueLoading(parameters(45.0))
        assertTrue(control.boosted)
        control.shouldContinueLoading(parameters(61.0))
        assertFalse(control.boosted)
        control.shouldContinueLoading(parameters(45.0))
        assertFalse(control.boosted)
        // 卡住了
        control.shouldStartPlayback(parameters(40.0, rebuffering = true))
        assertTrue(control.boosted)

        // 换媒体从头算
        control.onStopped(PlayerId.UNSET)
        assertFalse(control.boosted)
        control.onPrepared(PlayerId.UNSET)
        control.shouldContinueLoading(parameters(5.0))
        assertTrue(control.boosted)
    }

    @Test
    fun `ordinary sources never get more connections`() {
        val control = loadControl(throttled = false)
        assertTrue(control.shouldStartPlayback(parameters(1.5)))
        control.shouldContinueLoading(parameters(5.0))
        assertFalse(control.boosted)
    }
}
