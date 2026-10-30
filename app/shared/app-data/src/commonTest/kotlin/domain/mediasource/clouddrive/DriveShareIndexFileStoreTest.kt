/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * [DriveShareIndexFileStore]: 存到文件、换一个实例 (应用重启) 读回来, 过期的写回时丢掉, 坏文件当作没有.
 */
class DriveShareIndexFileStoreTest {
    private val tempDirectory = SystemPaths.createTempDirectory("drive-share-index-test")

    @AfterTest
    fun cleanup() {
        tempDirectory.deleteRecursively()
    }

    private val now = 1000.hours.inWholeMilliseconds

    private fun index(indexedAtMillis: Long) = DriveShareIndex(
        depth = 2,
        indexedAtMillis = indexedAtMillis,
        folders = listOf(DriveShareIndexedFolder("autumn", listOf("番剧合集", "2023 秋")), DriveShareIndexedFolder("frieren", listOf("番剧合集", "2023 秋", "A 4k 葬送的芙莉莲"))),
    )

    /** 读写都在测试的调度器上跑 (不挂在测试的 Job 下), 攒着的那次写回由 [advanceUntilIdle] 推进. */
    private fun TestScope.store(file: SystemPath) =
        DriveShareIndexFileStore(file, StandardTestDispatcher(testScheduler), now = { now }, ioContext = EmptyCoroutineContext)

    @Test
    fun `indexes are written to the file and read back after a restart`() = runTest {
        val file = tempDirectory.resolve("indexes.json")
        val store = store(file)
        store.put("coll", index(now - 1.hours.inWholeMilliseconds))
        store.put("stale", index(now - 7.hours.inWholeMilliseconds))
        store.put("old", index(now - 31.days.inWholeMilliseconds))
        advanceUntilIdle()
        assertTrue(file.exists())

        val reopened = store(file)
        assertEquals(index(now - 1.hours.inWholeMilliseconds), reopened.get("coll"))
        // 过了六小时的照样留着: 搜索先拿旧索引找 (见 DriveShareSearchEngine)
        assertEquals(index(now - 7.hours.inWholeMilliseconds), reopened.get("stale"))
        // 很久没重列过的写回时丢掉
        assertNull(reopened.get("old"))
    }

    @Test
    fun `a broken file is treated as empty and overwritten`() = runTest {
        val file = tempDirectory.resolve("indexes.json").apply { writeText("{not json") }
        val store = store(file)
        assertNull(store.get("coll"))
        store.put("coll", index(now))
        advanceUntilIdle()

        assertEquals(index(now), store(file).get("coll"))
    }
}
