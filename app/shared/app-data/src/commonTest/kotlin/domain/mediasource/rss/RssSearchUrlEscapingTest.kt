/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rss

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.mediasource.MediaSourceEngineHelpers
import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `{keyword}` 替换进 [RssSearchConfig.searchUrl] 时的转义规则.
 *
 * 有的模板把 `{keyword}` 放在一段 JSON 里 (如 animes.garden 的
 * `feed.xml?filter=[{"type":"动画","search":["{keyword}"]}]`). 这种模板只做 URL 编码是不够的:
 * 服务端按 URL 解码之后拿到的是裸引号, 会把外层 JSON 打断并直接返回 400 —— 番剧名里带英文引号
 * (例如「孤独摇滚！」的别名 `Bocchi the "Guitar Hero" Rock Story`) 时, 这个数据源永远搜不出结果.
 */
class RssSearchUrlEscapingTest {
    private class CapturingEngine : RssMediaSourceEngine() {
        var captured: Url? = null

        override suspend fun searchImpl(
            finalUrl: Url,
            config: RssSearchConfig,
            query: RssSearchQuery,
            page: Int?,
            mediaSourceId: String,
        ): Result {
            captured = finalUrl
            return Result(finalUrl, query, null, null, null)
        }
    }

    /** 名字里自带一对英文引号, 正是线上踩到的那个. */
    private val quotedName = """Bocchi the "Guitar Hero" Rock Story"""

    private fun query(name: String) = RssSearchQuery(
        subjectName = name,
        allSubjectNames = listOf(name),
        episodeSort = EpisodeSort(1),
        episodeEp = null,
        episodeName = null,
    )

    private suspend fun searchWith(searchUrl: String, name: String): Url {
        val engine = CapturingEngine()
        engine.search(
            searchConfig = RssSearchConfig(searchUrl = searchUrl),
            query = query(name),
            page = 0,
            mediaSourceId = "test",
        )
        return requireNotNull(engine.captured)
    }

    /**
     * `{keyword}` 被双引号直接包着 = 它落在一段 JSON 字符串里, 服务端解码后必须仍是合法 JSON.
     */
    @Test
    fun `quotes in keyword keep a JSON template parseable`() = runTest {
        val url = searchWith(
            """https://example.com/feed.xml?filter=[{"type":"动画","search":["{keyword}"]}]""",
            quotedName,
        )
        // Url.parameters 给出的就是服务端 URL 解码之后看到的值
        val filter = requireNotNull(url.parameters["filter"])
        // 解析不过就会抛, 这正是线上那个 400 的成因
        val entries = Json.decodeFromString<List<FilterEntry>>(filter)
        // 转义只是传输用的, 关键词本身要原样送到
        assertEquals(quotedName, entries.single().search.single())
    }

    /**
     * 普通 query 模板里的 `{keyword}` 不在 JSON 里, 不能画蛇添足加反斜杠.
     */
    @Test
    fun `quotes in keyword are left alone for plain templates`() = runTest {
        val url = searchWith("https://example.com/search?q={keyword}", quotedName)
        assertEquals(quotedName, url.parameters["q"])
    }

    /**
     * 只有把 `{keyword}` 用双引号包起来的模板才走转义; 其余形状的模板逐字节不变。
     *
     * 期望值直接用「只做 URL 编码」算出来, 跟实现无关 —— 只要这条绿着,
     * 就能说明转义逻辑没有溢出到普通数据源上。
     */
    @Test
    fun `templates that do not quote the keyword are byte-identical`() = runTest {
        val names = listOf(
            quotedName,
            """back\slash\name""",
            "孤独摇滚！",
            "Re:Zero -Starting Life-",
            "A & B + C",
            "100%",
        )
        val templates = listOf(
            "https://example.com/search?q={keyword}",
            "https://acg.rip/page/{page}.xml?term={keyword}",
            "https://example.com/{keyword}/rss.xml",
            "https://example.com/rss?name={keyword}&page={page}",
            // 引号在别处、没有包住 {keyword}: 同样不该走转义
            """https://example.com/rss?tag="anime"&q={keyword}""",
        )
        for (template in templates) {
            for (name in names) {
                val actual = searchWith(template, name).toString()
                val expected = Url(
                    template
                        .replace("{keyword}", MediaSourceEngineHelpers.encodeUrlSegment(name))
                        .replace("{page}", "0"),
                ).toString()
                assertEquals(expected, actual, "template=$template name=$name")
            }
        }
    }

    /**
     * 不含引号的名字, 两种模板都该原样送到.
     */
    @Test
    fun `keyword without quotes is unchanged`() = runTest {
        val plain = searchWith("https://example.com/search?q={keyword}", "孤独摇滚")
        assertEquals("孤独摇滚", plain.parameters["q"])

        val json = searchWith(
            """https://example.com/feed.xml?filter=[{"search":["{keyword}"]}]""",
            "孤独摇滚",
        )
        val filter = requireNotNull(json.parameters["filter"])
        assertEquals("孤独摇滚", Json.decodeFromString<List<FilterEntry>>(filter).single().search.single())
    }
}

@kotlinx.serialization.Serializable
private data class FilterEntry(
    val type: String? = null,
    val search: List<String>,
)
