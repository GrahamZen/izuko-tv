/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import me.him188.ani.app.videoplayer.media.StallTracker.Event
import me.him188.ani.app.videoplayer.media.StallTracker.Outcome
import me.him188.ani.app.videoplayer.media.StallTracker.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RebufferDiagnosticsTest {
    @Test
    fun `buffering before the first ready is not a stall`() {
        val tracker = StallTracker()
        tracker.onPlayWhenReady(true)
        assertNull(tracker.onState(State.BUFFERING, 0))
        assertNull(tracker.onState(State.READY, 1_000))
        // 开播之后的缓冲才算
        assertEquals(Event.Started(1), tracker.onState(State.BUFFERING, 5_000))
        assertEquals(Event.Ended(1, 3_000, Outcome.RESUMED), tracker.onState(State.READY, 8_000))
    }

    @Test
    fun `buffering right after a seek or a new media is not a stall`() {
        val tracker = StallTracker()
        tracker.onPlayWhenReady(true)
        tracker.onState(State.READY, 0)
        assertNull(tracker.onSeek(1_000))
        assertNull(tracker.onState(State.BUFFERING, 1_000))
        tracker.onState(State.READY, 2_000)
        assertNull(tracker.onMediaChanged(3_000))
        assertNull(tracker.onState(State.BUFFERING, 3_000))
        tracker.onState(State.READY, 4_000)
        assertEquals(Event.Started(1), tracker.onState(State.BUFFERING, 9_000))
    }

    @Test
    fun `buffering while paused is not a stall`() {
        val tracker = StallTracker()
        tracker.onPlayWhenReady(true)
        tracker.onState(State.READY, 0)
        tracker.onPlayWhenReady(false)
        assertNull(tracker.onState(State.BUFFERING, 1_000))
    }

    @Test
    fun `a stall ends when the user seeks away, switches media or playback stops`() {
        val tracker = StallTracker()
        tracker.onPlayWhenReady(true)
        tracker.onState(State.READY, 0)
        tracker.onState(State.BUFFERING, 1_000)
        assertEquals(Event.Ended(1, 4_000, Outcome.MEDIA_CHANGED), tracker.onMediaChanged(5_000))
        tracker.onState(State.READY, 6_000)
        tracker.onState(State.BUFFERING, 7_000)
        assertEquals(Event.Ended(2, 1_000, Outcome.SEEKED), tracker.onSeek(8_000))
        tracker.onState(State.READY, 9_000)
        tracker.onState(State.BUFFERING, 10_000)
        assertEquals(Event.Ended(3, 2_000, Outcome.STOPPED), tracker.onState(State.STOPPED, 12_000))
    }

    @Test
    fun `window keeps the last 30 seconds and prefers transfer samples for received bytes`() {
        val window = LoadWindow()
        window.addLoad(LoadWindow.Load(0, "old.example.com", 9_000_000, 9_000, error = null))
        window.addTransfer(1_000, 1_000_000)
        window.addLoad(LoadWindow.Load(20_000, "cdn.example.com", 2_000_000, 4_800, error = null))
        window.addLoad(LoadWindow.Load(25_000, "cdn.example.com", 500_000, 600, error = "HttpDataSourceException"))
        window.addTransfer(26_000, 3_000_000)

        val summary = window.summary(32_000)
        assertEquals(30_000, summary.spanMillis)
        // 1 秒那次测速采样已经出了窗口, 实收按采样算 (直链文件不会每块都报「取完」)
        assertEquals(3_000_000, summary.transferredBytes)
        assertEquals(800_000, summary.receivedBitsPerSecond)
        assertEquals(2, summary.requests)
        assertEquals(1, summary.errors)
        assertEquals("cdn.example.com", summary.slowest?.host)
        assertEquals(4_800, summary.slowest?.durationMillis)
        assertEquals(7_000, summary.sinceLastLoadMillis)
        // 累计量不随窗口丢弃, 用来算卡住期间收了多少
        assertEquals(4_000_000, window.transferredBytesTotal)
    }

    @Test
    fun `window without transfer samples falls back to request bytes`() {
        val window = LoadWindow()
        window.addLoad(LoadWindow.Load(1_000, "cdn.example.com", 1_500_000, 2_000, error = null))
        val summary = window.summary(4_000)
        assertEquals(3_000, summary.spanMillis)
        assertEquals(1_500_000, summary.transferredBytes)
        assertEquals(4_000_000, summary.receivedBitsPerSecond)
    }

    @Test
    fun `window counts the bytes the player actually read since the media changed`() {
        val window = LoadWindow()
        window.addTransfer(1_000, 9_000_000) // 上一个媒体的
        window.clear(10_000)
        // 网盘直链一次打开读到底: 一直没有「取完」也没有测速采样, 实收只能按实际读到的算
        val spans = mutableListOf<Long>()
        val summary = window.summary(16_000) { span -> spans += span; 6_000_000 }
        assertEquals(listOf(6_000L), spans)
        assertEquals(6_000, summary.spanMillis)
        assertEquals(6_000_000, summary.transferredBytes)
        assertEquals(8_000_000, summary.receivedBitsPerSecond)
        assertEquals(0, summary.requests)
    }

    @Test
    fun `recent bytes are counted per second and old seconds drop out`() {
        var now = 0L
        val bytes = RecentBytes { now }
        bytes.add(1_000)
        now = 500
        bytes.add(1_000)
        now = 10_200
        bytes.add(5_000)
        now = 30_900
        bytes.add(7_000)
        assertEquals(14_000, bytes.total())
        // 最近 30 秒 = 第 0 秒之后: 0 秒那两次不算
        assertEquals(12_000, bytes.bytesInLast(30_000))
        assertEquals(7_000, bytes.bytesInLast(1_000))
        // 同一个桶一圈之后再用, 旧的清掉
        now = 30_900 + RecentBytes.BUCKETS * 1_000L
        bytes.add(1)
        assertEquals(1, bytes.bytesInLast(1_000))
        assertEquals(14_001, bytes.total())
    }
}
