/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent.session

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import me.him188.ani.app.torrent.anitorrent.test.TestTorrentHandle
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TorrentResumeSaverTest {
    private val events = mutableListOf<String>()
    private var progress: Pair<Long, Long>? = 0L to 0L
    private var valid = true
    private var syncFails = false

    private val saver = TorrentResumeSaver(
        progress = { progress },
        syncFiles = {
            if (syncFails) error("sync failed")
            events += "sync"
        },
        postSave = { events += "save" },
        interval = 2.minutes,
    )

    @Test
    fun `progress is saved every interval after the files are synced`() = runTest {
        backgroundScope.launch { saver.run { valid } }
        advanceTimeBy(2.minutes + 1.seconds)
        assertEquals(listOf("sync", "save"), events)

        progress = 1000L to 0L
        advanceTimeBy(2.minutes)
        assertEquals(listOf("sync", "save", "sync", "save"), events)
    }

    @Test
    fun `nothing is written while progress stays the same`() = runTest {
        backgroundScope.launch { saver.run { valid } }
        advanceTimeBy(2.minutes + 1.seconds)
        events.clear()
        advanceTimeBy(10.minutes)
        assertEquals(emptyList(), events)
        // 只有上传量变了也算 (做种时)
        progress = 0L to 5L
        advanceTimeBy(2.minutes)
        assertEquals(listOf("sync", "save"), events)
    }

    @Test
    fun `a stopped file is saved right away`() = runTest {
        backgroundScope.launch { saver.run { valid } }
        advanceTimeBy(2.minutes + 1.seconds)
        events.clear()
        saver.saveSoon()
        runCurrent()
        assertEquals(listOf("sync", "save"), events)
    }

    @Test
    fun `a failed sync still saves the resume data`() = runTest {
        // 目录没了、外接盘读写出错时每次落盘都一样失败: 不写续传数据的话进度就一直只在内存里
        syncFails = true
        backgroundScope.launch { saver.run { valid } }
        advanceTimeBy(2.minutes + 1.seconds)
        assertEquals(listOf("save"), events)

        events.clear()
        saver.saveSoon()
        runCurrent()
        assertEquals(listOf("save"), events)
    }

    @Test
    fun `only files that grew since the last sync are synced again`() {
        assertEquals(listOf("a", "b"), filesToSync(mapOf("a" to 10L, "b" to 5L, "c" to 0L), emptyMap()))
        assertEquals(listOf("b"), filesToSync(mapOf("a" to 10L, "b" to 7L), mapOf("a" to 10L, "b" to 5L)))
        // 下完了在做种: 只有上传量在变, 不再落盘
        assertEquals(emptyList(), filesToSync(mapOf("a" to 10L), mapOf("a" to 10L)))
    }

    @Test
    fun `resume data that fails half way leaves the previous file whole`() = runTest {
        val dir = createTempDirectory("resume").toFile()
        val fastResume = File(dir, "fastresume").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val session = AnitorrentDownloadSession(
            TestTorrentHandle({ _, _ -> }), dir.toKtPath().inSystem, fastResume.toKtPath().inSystem,
            onClose = {}, onPostClose = {}, onDelete = {}, parentCoroutineContext = backgroundScope.coroutineContext,
        )
        session.onSaveResumeData(
            object : TorrentResumeData {
                override fun saveToPath(path: Path) {
                    File(path.toString()).writeBytes(byteArrayOf(9))
                    error("killed half way")
                }
            },
        )
        assertEquals(listOf<Byte>(1, 2, 3), fastResume.readBytes().toList())

        session.onSaveResumeData(
            object : TorrentResumeData {
                override fun saveToPath(path: Path) = File(path.toString()).writeBytes(byteArrayOf(7, 7))
            },
        )
        assertEquals(listOf<Byte>(7, 7), fastResume.readBytes().toList())
        assertFalse(File(dir, "fastresume.tmp").exists())
    }

    @Test
    fun `saving stops once the torrent is removed`() = runTest {
        val job = backgroundScope.launch { saver.run { valid } }
        valid = false
        progress = 1L to 0L
        advanceTimeBy(2.minutes + 1.seconds)
        assertEquals(emptyList(), events)
        assertEquals(true, job.isCompleted)
    }
}
