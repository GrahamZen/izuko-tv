/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonWrappedHtmlTest {
    @Test
    fun `a page sent back as a json string is turned back into html`() {
        val wrapped = """"<!DOCTYPE html><html><head><title>《虫师》<\/title><\/head><body><ul class=\"hl-plays-list\"><\/ul><\/body><\/html>""""
        assertEquals(
            """<!DOCTYPE html><html><head><title>《虫师》</title></head><body><ul class="hl-plays-list"></ul></body></html>""",
            unwrapJsonEncodedHtml(wrapped),
        )
    }

    @Test
    fun `json and plain pages are left alone`() {
        val json = """{"code":1,"list":[{"id":1,"name":"虫师"}]}"""
        assertEquals(json, unwrapJsonEncodedHtml(json))
        assertEquals("\"just text\"", unwrapJsonEncodedHtml("\"just text\""))
        val html = "<html><body>虫师</body></html>"
        assertEquals(html, unwrapJsonEncodedHtml(html))
    }
}
