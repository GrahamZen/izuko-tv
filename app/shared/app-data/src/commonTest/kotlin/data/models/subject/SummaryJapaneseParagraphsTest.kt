/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.subject

import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryJapaneseParagraphsTest {
    private val chinese = "文学少年水之江梅理科成绩极差，面临留级危机，被迫加入学生会挽救学分。\n怎知藤成学园的学生会毫不正常，聚集了各路奇葩。"

    // 第二行没有假名, 跟着整段算日文
    private val japanese = "文学で受賞歴もある国語の天才だが、理系科目は壊滅的な水之江梅。\n私立藤成学園高等部一年\n留年を回避するため、生徒会に入ることにするが……。"

    @Test
    fun `leading japanese paragraphs are dropped`() {
        assertEquals(chinese, summaryWithoutLeadingJapanese("$japanese\n\n$chinese\n"))
    }

    @Test
    fun `heading between the japanese and the chinese is dropped and the rest is kept`() {
        val summary = "$japanese\n\n【中文简介】\n\n$chinese\r\n\r\n监督：长沼范裕"
        assertEquals("$chinese\n\n监督：长沼范裕", summaryWithoutLeadingJapanese(summary))
    }

    @Test
    fun `translation heading on the first chinese line is dropped`() {
        assertEquals(chinese, summaryWithoutLeadingJapanese("$japanese\n\n[中文简介]\n$chinese"))
    }

    @Test
    fun `short chinese tagline before the chinese body is kept`() {
        val tagline = "唯有那份耀眼，未曾忘记。"
        assertEquals(
            "$tagline\n\n$chinese",
            summaryWithoutLeadingJapanese("眩しさだけは、忘れなかった。\n\n$japanese\n\n$tagline\n\n$chinese"),
        )
    }

    @Test
    fun `japanese lines without kana inside the japanese part are dropped`() {
        assertEquals(chinese, summaryWithoutLeadingJapanese("$japanese\n\n友達？　恋人？\n\n$japanese\n\n$chinese"))
    }

    @Test
    fun `japanese after the chinese part is kept`() {
        assertEquals("$chinese\n\n$japanese", summaryWithoutLeadingJapanese("$japanese\n\n$chinese\n\n$japanese"))
    }

    @Test
    fun `chinese first is kept as is`() {
        val summary = "$chinese\n\n$japanese"
        assertEquals(summary, summaryWithoutLeadingJapanese(summary))
    }

    @Test
    fun `japanese only is kept as is`() {
        val summary = "$japanese\n\n監督：長沼範裕\n原作：日向夏"
        assertEquals(summary, summaryWithoutLeadingJapanese(summary))
    }

    @Test
    fun `short notes without function words are not taken as chinese`() {
        val summary = "$japanese\n\n（以上摘自官方网站简介）"
        assertEquals(summary, summaryWithoutLeadingJapanese(summary))
    }

    @Test
    fun `japanese only means kana and no chinese paragraph`() {
        assertEquals(true, summaryIsJapaneseOnly("$japanese\n\n監督：長沼範裕"))
        assertEquals(false, summaryIsJapaneseOnly("$japanese\n\n$chinese"))
        assertEquals(false, summaryIsJapaneseOnly("$chinese\n\n$japanese"))
        assertEquals(false, summaryIsJapaneseOnly(chinese))
    }

    @Test
    fun `japanese and chinese in one paragraph are kept as is`() {
        val summary = "$japanese\n$chinese"
        assertEquals(summary, summaryWithoutLeadingJapanese(summary))
    }
}
