/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.isDirectory
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonUserDataFileTest {
    private val tempDirectory = SystemPaths.createTempDirectory("json-user-data-test")
    private val file = tempDirectory.resolve("data.json")
    private val data = JsonUserDataFile(file, Sample.serializer())

    @AfterTest
    fun cleanup() {
        tempDirectory.deleteRecursively()
    }

    @Serializable
    data class Sample(val names: List<String>, val count: Int)

    @Test
    fun `what was written reads back`() = runTest {
        assertNull(data.read())
        data.write(Sample(listOf("a", "b"), 2))
        assertEquals(Sample(listOf("a", "b"), 2), data.read())
    }

    @Test
    fun `an unreadable file is kept aside instead of deleted`() = runTest {
        file.writeText("""{"names": ["a"], "count": "not a number"}""")
        assertNull(data.read())
        assertFalse(file.exists())
        val kept = tempDirectory.resolve("data.json" + JsonUserDataFile.UNREADABLE_SUFFIX)
        assertEquals("""{"names": ["a"], "count": "not a number"}""", kept.readText())

        data.write(Sample(emptyList(), 0))
        assertEquals(Sample(emptyList(), 0), data.read())
    }

    @Test
    fun `a file that fails to read for another reason stays and is not written over`() = runTest {
        // 读的时候出 IO 错 (这里用同名目录代替): 不是内容坏了, 不挪开; 当没有用, 但这个进程里不写回, 免得空的盖掉原来的
        file.createDirectories()
        assertNull(data.read())
        assertTrue(file.isDirectory())
        assertFalse(tempDirectory.resolve("data.json" + JsonUserDataFile.UNREADABLE_SUFFIX).exists())

        file.deleteRecursively()
        data.write(Sample(emptyList(), 0))
        assertFalse(file.exists())
    }
}
