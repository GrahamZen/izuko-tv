/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 对应表末列的标题 logo ([TmdbSubjectMapLogos]). */
class TmdbSubjectMapLogosTest {
    @Test
    fun `parses each language`() {
        val logos = TmdbSubjectMapLogos.parse("o=ja ja=/a.png:2.383 zh=- en=/b.png:10")!!
        assertEquals("ja", logos.original)
        assertEquals(TmdbTitleLogo("/a.png", 2.383f), logos.byLanguage["ja"])
        assertEquals(true to null, logos.decided("zh"))
        assertEquals(true to TmdbTitleLogo("/a.png", 2.383f), logos.decided(null))
        assertEquals(false to null, logos.decided("ko"))
    }

    @Test
    fun `empty or unreadable column means not checked`() {
        assertNull(TmdbSubjectMapLogos.parse(""))
        assertNull(TmdbSubjectMapLogos.parse(null))
        assertNull(TmdbSubjectMapLogos.parse("garbage"))
    }

    @Test
    fun `unreadable parts are skipped`() {
        val logos = TmdbSubjectMapLogos.parse("o=ja ja=/a.jpg:2 en=/b.png:x zh=/c.png:3")!!
        assertEquals(setOf("zh"), logos.byLanguage.keys)
    }

    @Test
    fun `map line carries the logo column`() {
        val (id, entry) = parseTmdbSubjectMapLine("135275\ttv/65844\t/k.jpg\ttv/65844\tauto\tS1E1\to=ja ja=/a.png:2.112")!!
        assertEquals(135275, id)
        assertEquals(TmdbTitleLogo("/a.png", 2.112f), entry.logos?.byLanguage?.get("ja"))
        assertNull(parseTmdbSubjectMapLine("1\ttv/1\t/k.jpg\ttv/1\tauto\t")!!.second.logos)
    }
}
