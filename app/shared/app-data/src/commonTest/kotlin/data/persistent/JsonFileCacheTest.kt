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
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JsonFileCacheTest {
    private val tempDirectory = SystemPaths.createTempDirectory("json-file-cache-test")
    private val file = tempDirectory.resolve("cache.json")
    private val cache = JsonFileCache(file, Sample.serializer())

    @AfterTest
    fun cleanup() {
        tempDirectory.deleteRecursively()
    }

    /** 推荐的落盘格式里用到的几种类型: 成对的数、整数当键的 Map、Set、可空字段. */
    @Serializable
    data class Sample(
        val pairs: List<Pair<Int, Int>>,
        val pages: Map<Int, List<String>>,
        val tags: Set<String>,
        val optional: Double? = null,
    )

    private val sample = Sample(
        pairs = listOf(1 to 100, 2 to 200),
        pages = mapOf(0 to listOf("a", "b"), 3 to listOf("c")),
        tags = setOf("恋爱", "校园"),
        optional = 0.25,
    )

    @Test
    fun `没有文件 - 当没有`() = runTest {
        assertNull(cache.read())
    }

    @Test
    fun `写了能原样读回来`() = runTest {
        cache.write(sample)
        assertEquals(sample, cache.read())
        assertFalse(tempDirectory.resolve("cache.json.tmp").exists())
    }

    @Test
    fun `再写一次覆盖上一份`() = runTest {
        cache.write(sample)
        cache.write(sample.copy(tags = setOf("日常")))
        assertEquals(setOf("日常"), cache.read()?.tags)
    }

    @Test
    fun `文件坏了 - 当没有并删掉`() = runTest {
        file.writeText("{not json")
        assertNull(cache.read())
        assertFalse(file.exists())
    }

    @Test
    fun `旧版本写的字段对不上 - 当没有`() = runTest {
        file.writeText("""{"pairs":[],"tags":[]}""")
        assertNull(cache.read())
    }

    @Test
    fun `多出来的字段不影响读`() = runTest {
        file.writeText("""{"pairs":[{"first":1,"second":2}],"pages":{},"tags":[],"added":true}""")
        assertEquals(Sample(listOf(1 to 2), emptyMap(), emptySet()), cache.read())
    }
}
