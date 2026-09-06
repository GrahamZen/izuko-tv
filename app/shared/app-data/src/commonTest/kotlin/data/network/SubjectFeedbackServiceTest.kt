/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 「反馈」里「对应的作品不对」的备选: 从核对页数据截出条目 ([extractTopLevelObject]) 与解析 ([parseSubjectEntryCandidates]). */
class SubjectFeedbackServiceTest {
    private val shard = """{"135275":{"auto":{"status":"hit","backdrop":"tv/65844","backdropPath":"/k.jpg",""" +
        """"tmdb":{"name":"为美好的世界献上祝福！","original":"この素晴らしい世界に祝福を！","date":"2016-01-14","overview":"有 {花括号} 与 \"引号\""},""" +
        """"candidates":[{"ref":"tv/335013","name":"Kono Subarashii 2","backdrop":"/c.jpg"},{"ref":"tv/65844","name":"重复的"}]}},""" +
        """"1352750":{"auto":{"status":"miss"}},""" +
        """"317":{"auto":{"status":"hit","backdrop":"tv/9","backdropPath":"/a.jpg","tmdb":{"name":"自动的"}},""" +
        """"manual":{"none":false,"backdrop":"tv/1","backdrop_path":"/m.jpg","stills":[],"title":"人工的"}},""" +
        """"554346":{"auto":{"status":"hit","backdrop":"tv/2"},"manual":{"none":true,"backdrop":null,"stills":[]}}}"""

    private fun record(id: Int) = Json.parseToJsonElement(extractTopLevelObject(shard, id.toString())!!) as JsonObject

    @Test
    fun `extracts only the top level record and skips braces in strings`() {
        val text = extractTopLevelObject(shard, "135275")!!
        assertTrue(text.startsWith("{\"auto\"") && text.endsWith("}}"))
        assertEquals("{\"auto\":{\"status\":\"miss\"}}", extractTopLevelObject(shard, "1352750"))
        assertNull(extractTopLevelObject(shard, "999"))
    }

    @Test
    fun `auto result first then alternatives without duplicates`() {
        val c = parseSubjectEntryCandidates(record(135275))
        assertEquals("tv/65844", c.current?.ref)
        assertEquals("2016-01-14", c.current?.date)
        assertEquals(listOf("tv/65844", "tv/335013"), c.options.map { it.ref })
        assertEquals(false, c.currentNone)
    }

    @Test
    fun `manual override is current and the auto result stays an option`() {
        val c = parseSubjectEntryCandidates(record(317))
        assertEquals(SubjectEntryOption("tv/1", "人工的", backdropPath = "/m.jpg"), c.current)
        assertEquals(listOf("tv/1", "tv/9"), c.options.map { it.ref })
    }

    @Test
    fun `confirmed no match`() {
        val c = parseSubjectEntryCandidates(record(554346))
        assertNull(c.current)
        assertTrue(c.currentNone)
        assertEquals(listOf("tv/2"), c.options.map { it.ref })
    }
}
