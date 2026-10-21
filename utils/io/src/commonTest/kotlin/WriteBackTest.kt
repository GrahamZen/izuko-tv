/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import me.him188.ani.utils.platform.Uuid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [WriteBackCounter] 的节奏, 与 [syncFileContents] 的安全性: 后者是拿来对**别人正在写**的文件 (ffmpeg 的合并输出、
 * libtorrent 下的文件) 调 fsync 的, 一旦实现里把打开方式写成截断模式, 用户正在下的缓存会当场被清成 0 字节.
 */
class WriteBackTest {
    private val created = mutableListOf<Path>()

    private fun tempFile(content: String): Path {
        val path = Path(SystemTemporaryDirectory, "sync-test-${Uuid.randomString()}.bin")
        SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
        created += path
        return path
    }

    @AfterTest
    fun cleanup() {
        created.forEach { runCatching { SystemFileSystem.delete(it) } }
    }

    @Test
    fun `counter asks for a sync each time enough bytes pile up`() {
        val counter = WriteBackCounter(syncEveryBytes = 10)
        assertEquals(listOf(false, false, true, false, true), listOf(4L, 4L, 4L, 4L, 6L).map { counter.wrote(it) })
        counter.wrote(9)
        counter.synced()
        assertFalse(counter.wrote(9), "a sync done elsewhere restarts the count")
    }

    @Test
    fun `sync must not truncate or modify the file`() {
        val content = "x".repeat(64 * 1024)
        val path = tempFile(content)

        if (!syncFileContents(path.inSystem)) return // 平台做不到, 无从验证

        val after = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
        assertContentEquals(content.encodeToByteArray(), after, "syncFileContents 改动了文件内容")
    }

    @Test
    fun `sync can be called repeatedly`() {
        val path = tempFile("hello")
        if (!syncFileContents(path.inSystem)) return
        assertTrue(syncFileContents(path.inSystem))
        assertTrue(syncFileContents(path.inSystem))
        val after = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
        assertContentEquals("hello".encodeToByteArray(), after)
    }

    @Test
    fun `sync reports failure for a missing file`() {
        // 不能顺手把文件创建出来: 合并还没开始写、缓存刚被删时文件不存在, 那时候只该跳过
        val missing = Path(SystemTemporaryDirectory, "sync-test-missing-${Uuid.randomString()}.bin")
        assertFalse(syncFileContents(missing.inSystem))
        assertFalse(SystemFileSystem.exists(missing), "syncFileContents 不该凭空创建文件")
    }
}
