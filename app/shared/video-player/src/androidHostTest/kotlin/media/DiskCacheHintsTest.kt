/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「边下边播」的提示: 跳到播过的位置后缓冲了一阵还没好才提示; 内存里还有的、往前跳的、小挪动、开了也不会存的都不提示.
 */
class DiskCacheHintsTest {
    private class Fixture(scope: TestScope) {
        var state = Player.STATE_READY
        var previewOrigin: Long? = null
        var applicable = true
        var hints = 0
        val tracker = DiskCacheHints(scope.backgroundScope, { state }, { previewOrigin }, { applicable })

        init {
            scope.backgroundScope.launch { tracker.events.collect { hints++ } }
            scope.runCurrent()
        }

        /** 跳过去, 播放器开始缓冲. */
        fun seek(from: Long, to: Long) {
            tracker.onSeek(from, to)
            state = Player.STATE_BUFFERING
            tracker.onPlaybackStateChanged(state)
        }

        fun ready() {
            state = Player.STATE_READY
            tracker.onPlaybackStateChanged(state)
        }
    }

    private fun TestScope.wait(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `buffering for a while after seeking back gives a hint`() = runTest {
        val f = Fixture(this)
        f.seek(from = 600_000, to = 300_000)
        wait(DiskCacheHints.WAIT_MILLIS - 100)
        assertEquals(0, f.hints)
        wait(200)
        assertEquals(1, f.hints)
    }

    @Test
    fun `ready soon after seeking back gives no hint`() = runTest {
        val f = Fixture(this)
        f.seek(from = 600_000, to = 590_000) // 还在内存里
        wait(300)
        f.ready()
        wait(DiskCacheHints.WAIT_MILLIS * 2)
        assertEquals(0, f.hints)
    }

    @Test
    fun `seeking forward or only a little back gives no hint`() = runTest {
        val f = Fixture(this)
        f.seek(from = 300_000, to = 600_000)
        wait(DiskCacheHints.WAIT_MILLIS * 2)
        f.seek(from = 600_000, to = 600_000 - DiskCacheHints.BACKWARD_MIN_MILLIS + 1)
        wait(DiskCacheHints.WAIT_MILLIS * 2)
        assertEquals(0, f.hints)
    }

    @Test
    fun `seek previews count positions before where the preview started`() = runTest {
        val f = Fixture(this)
        f.previewOrigin = 600_000
        // 预览里从 200 秒挪到 300 秒: 比上一步靠后, 但比开始拖时早
        f.seek(from = 200_000, to = 300_000)
        wait(DiskCacheHints.WAIT_MILLIS + 100)
        assertEquals(1, f.hints)
        // 拖到开始时之后的位置: 还没播过
        f.seek(from = 300_000, to = 700_000)
        wait(DiskCacheHints.WAIT_MILLIS * 2)
        assertEquals(1, f.hints)
    }

    @Test
    fun `another seek restarts the wait`() = runTest {
        val f = Fixture(this)
        f.seek(from = 600_000, to = 300_000)
        wait(DiskCacheHints.WAIT_MILLIS - 500)
        f.seek(from = 300_000, to = 100_000)
        wait(DiskCacheHints.WAIT_MILLIS - 500)
        assertEquals(0, f.hints)
        wait(600)
        assertEquals(1, f.hints)
    }

    @Test
    fun `no hint when the disk cache would not help`() = runTest {
        val f = Fixture(this)
        f.applicable = false
        f.seek(from = 600_000, to = 300_000)
        wait(DiskCacheHints.WAIT_MILLIS * 2)
        assertEquals(0, f.hints)
    }
}
