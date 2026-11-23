/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 导入认的几种形状 (控制台与平板设置共用, 见 [MediaSourceImporter.parse]) */
class MediaSourceImporterParseTest {
    private val one = """{"factoryId":"rss","version":1,"arguments":{"name":"a"}}"""
    private val two = """{"factoryId":"web-selector","version":3,"arguments":{"name":"b"}}"""

    private fun ids(text: String) = MediaSourceImporter.parse(text)?.map { it.factoryId.value }

    @Test
    fun `single source`() {
        assertEquals(listOf("rss"), ids(one))
    }

    @Test
    fun `plain array and media sources list`() {
        assertEquals(listOf("rss", "web-selector"), ids("[$one,$two]"))
        assertEquals(listOf("rss", "web-selector"), ids("""{"mediaSources":[$one,$two]}"""))
    }

    @Test
    fun `subscription content`() {
        assertEquals(
            listOf("web-selector"),
            ids("""{"exportedMediaSourceDataList":{"mediaSources":[$two]},"other":1}"""),
        )
    }

    @Test
    fun `surrounding whitespace is fine and an empty list is empty`() {
        assertEquals(listOf("rss"), ids("\n  $one  \n"))
        assertEquals(emptyList(), ids("""{"mediaSources":[]}"""))
    }

    @Test
    fun `not a source config`() {
        assertNull(ids("hello"))
        assertNull(ids("""{"name":"x"}"""))
        assertNull(ids("""{"mediaSources":{}}"""))
        // 有一个不是数据源时整段不认 (同控制台)
        assertNull(ids("""[$one,{"name":"x"}]"""))
    }
}
