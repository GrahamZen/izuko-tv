/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.cache.DiskCache
import kotlinx.coroutines.Dispatchers
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AniImageDiskCacheTest {
    private val fileSystem = FileSystem.SYSTEM

    @Test
    fun `reads entries written by Sketch's own disk cache`() = withCacheDirectory { directory ->
        sketchCache(directory).run {
            put("https://example.com/a.jpg", "data-a", "meta-a")
            put("https://example.com/b.jpg", "data-b", "meta-b")
            close()
        }

        val cache = aniCache(directory)
        assertEquals("data-a" to "meta-a", cache.read("https://example.com/a.jpg"))
        assertEquals("data-b" to "meta-b", cache.read("https://example.com/b.jpg"))
        assertNull(cache.read("https://example.com/missing.jpg"))
        assertEquals("data-a".length + "meta-a".length + "data-b".length + "meta-b".length, cache.size.toInt())
        cache.close()
    }

    @Test
    fun `Sketch's own disk cache reads entries written here`() = withCacheDirectory { directory ->
        aniCache(directory).run {
            put("https://example.com/a.jpg", "data-a", "meta-a")
            close()
        }

        val cache = sketchCache(directory)
        assertEquals("data-a" to "meta-a", cache.read("https://example.com/a.jpg"))
        cache.close()
    }

    @Test
    fun `entries survive reopen and removed ones stay removed`() = withCacheDirectory { directory ->
        aniCache(directory).run {
            put("a", "1", "")
            put("b", "22", "")
            assertTrue(remove("a"))
            close()
        }

        val cache = aniCache(directory)
        assertNull(cache.read("a"))
        assertEquals("22" to "", cache.read("b"))
        assertEquals(2L, cache.size)
        cache.close()
    }

    @Test
    fun `journal replay drops removed and half-written entries`() = withCacheDirectory { directory ->
        val a = "a".entryName()
        val b = "b".entryName()
        val c = "c".entryName()
        val d = "d".entryName()
        fileSystem.createDirectories(directory)
        fileSystem.write(directory / "journal") {
            writeUtf8(
                """
                libcore.io.DiskLruCache
                1
                65537
                2

                CLEAN $a 3 1
                DIRTY $b
                CLEAN $c 2 0
                READ $a
                REMOVE $c
                DIRTY $d
                CLEAN $d 4 4
                DIRTY $d
                """.trimIndent() + "\n",
            )
        }
        writeFile(directory / "$a.0", "aaa")
        writeFile(directory / "$a.1", "m")
        writeFile(directory / "$b.0.tmp", "half")
        writeFile(directory / "$d.0", "dddd")
        writeFile(directory / "$d.1", "mmmm")
        writeFile(directory / "$d.0.tmp", "half")

        val cache = aniCache(directory)
        assertEquals("aaa" to "m", cache.read("a"))
        assertNull(cache.read("b"))
        assertNull(cache.read("c"))
        // 重新写到一半时进程没了: 旧内容也不可信, 连同半成品一起删
        assertNull(cache.read("d"))
        assertEquals(4L, cache.size)
        assertFalse(fileSystem.exists(directory / "$b.0.tmp"))
        assertFalse(fileSystem.exists(directory / "$d.0"))
        assertFalse(fileSystem.exists(directory / "$d.1"))
        assertFalse(fileSystem.exists(directory / "$d.0.tmp"))
        cache.close()
    }

    @Test
    fun `least recently used entry is evicted first`() = withCacheDirectory { directory ->
        aniCache(directory, maxSize = 10).run {
            put("a", "aaaa", "")
            put("b", "bbbb", "")
            assertNotNull(read("a")) // a 比 b 新
            put("c", "cccc", "")
            close() // 关闭时同步裁到上限
        }

        val cache = aniCache(directory, maxSize = 10)
        assertNull(cache.read("b"))
        assertEquals("aaaa" to "", cache.read("a"))
        assertEquals("cccc" to "", cache.read("c"))
        cache.close()
    }

    @Test
    fun `open progress rises to one while reading the journal`() = withCacheDirectory { directory ->
        val entries = 1000
        fileSystem.createDirectories(directory)
        fileSystem.write(directory / "journal") {
            writeUtf8("libcore.io.DiskLruCache\n1\n65537\n2\n\n")
            repeat(entries) { writeUtf8("CLEAN ${"key-$it".entryName()} 7 3\n") }
        }

        val progress = mutableListOf<Float>()
        val cache = AniDiskLruCache(
            fileSystem = fileSystem,
            directory = directory,
            cleanupDispatcher = Dispatchers.Default,
            maxSize = 1L shl 30,
            appVersion = 65537,
            valueCount = 2,
            onOpenProgress = { progress += it },
        )
        assertTrue(progress.isEmpty()) // 没人用之前不打开

        assertEquals(entries * 10L, cache.size())
        assertTrue(progress.size >= 4, "progress=$progress")
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.first() > 0f && progress.first() < 0.5f, "progress=$progress")
        assertEquals(1f, progress.last())
        cache.close()
    }

    @Test
    fun `open progress is reported through the disk cache`() = withCacheDirectory { directory ->
        val cache = aniCache(directory)
        assertEquals(0f, cache.openProgress.value)
        assertEquals(0L, cache.size)
        assertEquals(1f, cache.openProgress.value)
        cache.close()
    }

    private fun withCacheDirectory(block: (directory: Path) -> Unit) {
        val tempDirectory = SystemPaths.createTempDirectory("ani-image-disk-cache-test")
        try {
            block(tempDirectory.absolutePath.toPath() / "download")
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    private fun aniCache(directory: Path, maxSize: Long = 1L shl 20): AniImageDiskCache =
        AniImageDiskCache(PlatformContext.INSTANCE, fileSystem, maxSize, directory)

    private fun sketchCache(directory: Path): DiskCache =
        DiskCache.DownloadBuilder(PlatformContext.INSTANCE, fileSystem)
            .options(DiskCache.Options(directory = directory, maxSize = 1L shl 20))
            .build()

    private fun DiskCache.put(key: String, data: String, metadata: String) {
        val editor = assertNotNull(openEditor(key))
        writeFile(editor.data, data)
        writeFile(editor.metadata, metadata)
        editor.commit()
    }

    private fun DiskCache.read(key: String): Pair<String, String>? {
        val snapshot = openSnapshot(key) ?: return null
        try {
            return readFile(snapshot.data) to readFile(snapshot.metadata)
        } finally {
            snapshot.close()
        }
    }

    private fun writeFile(path: Path, content: String) = fileSystem.write(path) { writeUtf8(content) }

    private fun readFile(path: Path): String = fileSystem.read(path) { readUtf8() }

    private fun String.entryName(): String = encodeUtf8().md5().hex()
}
