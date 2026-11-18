/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import kotlinx.serialization.json.Json
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.xml.Html
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiteBangumiIdsTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    // region 认编号

    @Test
    fun `fields named after Bangumi give the id`() {
        assertEquals(400602, SiteBangumiIds.ofJson(json("""{"name": "葬送的芙莉莲", "bangumiId": 400602, "malId": 52991}""")))
        assertEquals(515759, SiteBangumiIds.ofJson(json("""{"bgm_id": "515759", "cover_url": "https://example.com/a.jpg"}""")))
        assertEquals(400602, SiteBangumiIds.ofJson(json("""{"BangumiID": "400602"}""")))
    }

    @Test
    fun `a field that is empty falls back to the cover and two different ids give none`() {
        assertEquals(400602, SiteBangumiIds.ofJson(json("""{"bgm_id": "", "cover": "https://img.example.com/pic/cover/l/13/c5/400602_ZI8Y9.jpg"}""")))
        assertNull(SiteBangumiIds.ofJson(json("""{"bangumiId": 400602, "bgm_id": 515759}""")))
    }

    @Test
    fun `nested items are other works and give no id`() {
        assertNull(SiteBangumiIds.ofJson(json("""{"title": "葬送的芙莉莲 第二季", "prequel": {"bangumiId": 400602}}""")))
        assertNull(SiteBangumiIds.ofJson(json("""[{"bangumiId": 400602}]""")))
    }

    @Test
    fun `a Bangumi style cover path gives the id when its folders are the md5 of the id`() {
        // 稀饭的图床: 目录 13/c5 是 400602 的 MD5 前四位
        assertEquals(400602, SiteBangumiIds.ofText("https://img2.xfmanga.top/r/800/pic/cover/l/13/c5/400602_ZI8Y9.jpg"))
        assertEquals(515759, SiteBangumiIds.ofText("https://cover1.aniwatch.top/pic/cover/l/0b/24/515759_qA1Zc.jpg"))
        // 目录对不上编号的 MD5, 或不是这套路径的, 不认
        assertNull(SiteBangumiIds.ofText("https://img.example.com/pic/cover/l/00/00/400602_ZI8Y9.jpg"))
        assertNull(SiteBangumiIds.ofText("https://pic.example.com/images/2025/06/03/400602_ZI8Y9.jpg"))
    }

    @Test
    fun `a text with covers of two works gives no id`() {
        val two = "/pic/cover/l/13/c5/400602_ZI8Y9.jpg /pic/cover/l/0b/24/515759_qA1Zc.jpg"
        assertNull(SiteBangumiIds.ofText(two))
        assertEquals(400602, SiteBangumiIds.ofText("/pic/cover/l/13/c5/400602_ZI8Y9.jpg /pic/cover/c/13/c5/400602_ZI8Y9.jpg"))
    }

    @Test
    fun `an html card gives the id of its lazy loaded cover also on the card itself`() {
        val document = Html.parse(
            """
            <ul>
              <li class="card"><a href="/anime/272.html" title="葬送的芙莉莲 第二季" data-original="https://img2.xfmanga.top/pic/cover/l/0b/24/515759_qA1Zc.jpg"></a></li>
              <li class="card"><a href="/anime/1.html"><img src="/upload/vod/a.jpg"></a></li>
            </ul>
            <a class="self" href="/anime/26.html" data-original="https://img2.xfmanga.top/pic/cover/l/13/c5/400602_ZI8Y9.jpg">葬送的芙莉莲</a>
            """.trimIndent(),
            "https://www.example.com/",
        )
        val cards = document.select("li.card")
        assertEquals(515759, SiteBangumiIds.ofHtml(cards[0]))
        assertNull(SiteBangumiIds.ofHtml(cards[1]))
        assertEquals(400602, SiteBangumiIds.ofHtml(document.select("a.self").single()))
    }

    // endregion

    // region 开哪些页

    private data class Page(val name: String, val bangumiId: Int?)

    private fun plan(subjectId: String, vararg names: String) = SubjectSearchPlan.of(
        MediaFetchRequest(
            subjectId = subjectId,
            episodeId = "1",
            subjectNames = names.toList(),
            episodeSort = EpisodeSort(1),
            episodeName = "",
        ),
    )

    private fun SubjectSearchPlan.open(pages: List<Page>, subjectId: Int?) =
        pagesToOpenBySiteBangumiId(
            pages, subjectId, preferShorterName = true,
            siteBangumiIdOf = { it.bangumiId }, nameOf = { it.name }, withName = { page, name -> page.copy(name = name) },
        )

    @Test
    fun `pages are opened by the site Bangumi id before their names`() {
        val plan = plan("212293", "进击的巨人 第二季", "進撃の巨人 Season 2")
        val pages = listOf(
            // 页名就是这一部的名字, 编号却是第一季的: 不开
            Page("进击的巨人 第二季", 55770),
            // 没给编号: 照旧按名字认
            Page("进击的巨人 第二季", null),
            // 名字认不出 (站点的别名「自由之翼」两季都有), 编号是这一部: 最先开, 条目名换成这一部的名字
            Page("自由之翼", 212293),
            // 页名就是这一部的名字、编号也对: 名字照用
            Page("進撃の巨人 Season 2", 212293),
        )

        assertEquals(
            listOf(Page("进击的巨人 第二季", 212293), Page("進撃の巨人 Season 2", 212293), Page("进击的巨人 第二季", null)),
            plan.open(pages, subjectId = 212293),
        )
    }

    @Test
    fun `without a Bangumi subject pages are judged by name only`() {
        val plan = plan("", "进击的巨人 第二季")
        val pages = listOf(Page("进击的巨人 第二季", 55770), Page("自由之翼", 212293))

        assertEquals(listOf(Page("进击的巨人 第二季", 55770)), plan.open(pages, subjectId = null))
    }

    // endregion
}
