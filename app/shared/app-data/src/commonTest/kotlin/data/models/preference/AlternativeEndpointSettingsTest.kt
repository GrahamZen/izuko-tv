/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlternativeEndpointSettingsTest {
    private val candidates = listOf("https://images.tmdb.org", "https://image.tmdb.org")

    @Test
    fun `地址归一化 — 补协议、去结尾斜杠、域名转小写`() {
        assertEquals("https://images.tmdb.org", EndpointUrls.normalizeBaseUrl("images.tmdb.org"))
        assertEquals("https://images.tmdb.org", EndpointUrls.normalizeBaseUrl(" https://images.tmdb.org/ "))
        assertEquals("https://images.tmdb.org", EndpointUrls.normalizeBaseUrl("HTTPS://Images.TMDB.org"))
    }

    @Test
    fun `地址归一化 — 保留端口与路径前缀`() {
        assertEquals("http://10.0.0.2:8080/tmdb", EndpointUrls.normalizeBaseUrl("http://10.0.0.2:8080/tmdb/"))
        assertEquals("https://img.example.com/a/B", EndpointUrls.normalizeBaseUrl("img.example.com/a/B"))
    }

    @Test
    fun `地址归一化 — 认不出的返回 null`() {
        for (input in listOf(
            "", "   ", "ftp://x.com", "x.com?a=1", "x.com/#top", "https://user@x.com", "localhost",
            "a b.com", "https://x.com:99999", "https://x.com:", "https://.x.com", "https://x_y.com",
        )) {
            assertNull(EndpointUrls.normalizeBaseUrl(input), input)
        }
    }

    @Test
    fun `模板 — 协议与域名转小写，其余原样`() {
        assertEquals("https://wsrv.nl/?url=image.tmdb.org{path}", EndpointUrls.normalizeBaseUrl("wsrv.nl/?url=image.tmdb.org{path}"))
        assertEquals(
            "https://wsrv.nl/?url=image.tmdb.org{path}",
            EndpointUrls.normalizeBaseUrl("HTTPS://WSRV.NL/?url=image.tmdb.org{path}"),
        )
        assertEquals("https://x.com?u={path}", EndpointUrls.normalizeBaseUrl("https://x.com?u={path}"))
    }

    @Test
    fun `模板 — 占位符只能有一个，不能带片段`() {
        for (input in listOf("https://x.com/{path}?a={path}", "https://x.com/{path}#top", "https://x.com/?u={pth}")) {
            assertNull(EndpointUrls.normalizeBaseUrl(input), input)
        }
    }

    @Test
    fun `拼地址 — 入口接在前面，模板换进去`() {
        assertEquals("https://images.tmdb.org/t/p/w92/a.jpg", EndpointUrls.resolve("https://images.tmdb.org", "/t/p/w92/a.jpg"))
        assertEquals(
            "https://wsrv.nl/?url=image.tmdb.org/t/p/w92/a.jpg",
            EndpointUrls.resolve("https://wsrv.nl/?url=image.tmdb.org{path}", "/t/p/w92/a.jpg"),
        )
    }

    @Test
    fun `显示名 — 模板只显示域名`() {
        assertEquals("images.tmdb.org", EndpointUrls.displayName("https://images.tmdb.org"))
        assertEquals("wsrv.nl", EndpointUrls.displayName("https://wsrv.nl/?url=image.tmdb.org{path}"))
        assertEquals("x.com", EndpointUrls.displayName("https://x.com?u={path}"))
    }

    @Test
    fun `自动 — 按清单顺序`() {
        assertEquals(candidates, EndpointSelection().baseUrls(candidates))
    }

    @Test
    fun `选定清单里的一个 — 只用它`() {
        val selection = EndpointSelection(mode = EndpointSelectionMode.FIXED, fixedBaseUrl = "https://image.tmdb.org")
        assertEquals(listOf("https://image.tmdb.org"), selection.baseUrls(candidates))
    }

    @Test
    fun `选定的后来被清单去掉了也照用`() {
        val selection = EndpointSelection(mode = EndpointSelectionMode.FIXED, fixedBaseUrl = "https://old.example.com")
        assertEquals(listOf("https://old.example.com"), selection.baseUrls(candidates))
    }

    @Test
    fun `自定义 — 用归一化后的地址`() {
        val selection = EndpointSelection(mode = EndpointSelectionMode.CUSTOM, customBaseUrl = "img.example.com/tmdb/")
        assertEquals(listOf("https://img.example.com/tmdb"), selection.baseUrls(candidates))
    }

    @Test
    fun `自定义还没填好 — 按自动`() {
        assertEquals(candidates, EndpointSelection(mode = EndpointSelectionMode.CUSTOM).baseUrls(candidates))
        val invalid = EndpointSelection(mode = EndpointSelectionMode.CUSTOM, customBaseUrl = "not a url")
        assertEquals(candidates, invalid.baseUrls(candidates))
    }
}
