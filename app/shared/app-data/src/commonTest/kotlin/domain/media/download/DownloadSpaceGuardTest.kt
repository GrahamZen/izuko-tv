/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.inSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 存储快满时暂停全部正在进行的下载 (见 [DownloadSpaceGuard]).
 */
class DownloadSpaceGuardTest {
    private val dir = Path("downloads").inSystem

    @Test
    fun `pauses every running download once the storage is nearly full`() = runTest {
        val running = testDownload(1)
        val paused = testDownload(2).apply { state.value = MediaCacheState.PAUSED }
        val storage = DownloadTestStorage().apply { listFlow.value = listOf(running, paused) }
        val manager = MediaDownloadManager(listOf(storage), backgroundScope)
        runCurrent()
        val events = mutableListOf<DownloadSpaceGuard.LowSpacePause>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            DownloadSpaceGuard.lowSpacePauses.collect { events += it }
        }

        var free = 2L shl 30
        val guard = DownloadSpaceGuard(manager, { dir }, backgroundScope, freeBytes = { free })
        assertEquals(0, guard.check())
        assertEquals(0, running.pauseCalls)

        free = 512L shl 20
        assertEquals(1, guard.check())
        runCurrent()
        assertEquals(1, running.pauseCalls)
        assertEquals(0, paused.pauseCalls)
        assertEquals(listOf(DownloadSpaceGuard.LowSpacePause(freeBytes = 512L shl 20, paused = 1)), events)

        // 全停下之后不再提示
        assertEquals(0, guard.check())
    }

    @Test
    fun `nothing is paused when the free space is unknown`() = runTest {
        val running = testDownload(1)
        val storage = DownloadTestStorage().apply { listFlow.value = listOf(running) }
        val manager = MediaDownloadManager(listOf(storage), backgroundScope)
        runCurrent()

        assertEquals(0, DownloadSpaceGuard(manager, { dir }, backgroundScope, freeBytes = { null }).check())
        assertEquals(0, running.pauseCalls)
    }

    @Test
    fun `a download whose state never arrives does not keep the others running`() = runTest {
        val stuck = StuckStateCache(testDownload(1))
        val running = testDownload(2)
        val storage = DownloadTestStorage().apply { listFlow.value = listOf(stuck, running) }
        val manager = MediaDownloadManager(listOf(storage), backgroundScope)
        runCurrent()

        val guard = DownloadSpaceGuard(manager, { dir }, backgroundScope, freeBytes = { 512L shl 20 })
        assertEquals(1, guard.check())
        assertEquals(1, running.pauseCalls)
    }

    @Test
    fun `the guard keeps checking after a check fails`() = runTest {
        val running = testDownload(1)
        val storage = DownloadTestStorage().apply { listFlow.value = listOf(running) }
        val manager = MediaDownloadManager(listOf(storage), backgroundScope)
        runCurrent()

        var checks = 0
        val freeBytes = { _: SystemPath ->
            checks++
            if (checks == 1) error("storage unavailable")
            512L shl 20
        }
        DownloadSpaceGuard(manager, { dir }, backgroundScope, freeBytes = freeBytes).start()
        advanceTimeBy(DownloadSpaceGuard.CHECK_INTERVAL * 2)
        runCurrent()
        assertEquals(2, checks)
        assertEquals(1, running.pauseCalls)
    }

    /** 读状态一直等不到结果, 像 torrent 服务断开时那样. */
    private class StuckStateCache(delegate: DownloadTestCache) : MediaCache by delegate {
        override val state: Flow<MediaCacheState> = flow { awaitCancellation() }
    }
}
