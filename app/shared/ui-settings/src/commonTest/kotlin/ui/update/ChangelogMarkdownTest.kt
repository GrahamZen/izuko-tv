/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import me.him188.ani.app.ui.richtext.UIRichElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val Body = ChangelogTextStyle(size = 14f)
private val Section = ChangelogTextStyle(size = 16f, bold = true)

private fun inline(text: String) = parseMarkdownInline(text, Body)
private fun texts(text: String) = inline(text).filterIsInstance<UIRichElement.Annotated.Text>()

class ChangelogMarkdownTest {
    @Test
    fun `bold splits into three segments`() {
        val slice = texts("前**粗**后")
        assertEquals(listOf("前", "粗", "后"), slice.map { it.content })
        assertEquals(listOf(false, true, false), slice.map { it.bold })
    }

    @Test
    fun `link keeps text and url`() {
        val slice = texts("见 [README](https://example.com/a) 一节")
        assertEquals(listOf("见 ", "README", " 一节"), slice.map { it.content })
        assertEquals("https://example.com/a", slice[1].url)
        assertNull(slice[0].url)
    }

    @Test
    fun `code span is not parsed further`() {
        val slice = texts("看 `a**b**c` 这里")
        assertEquals("a**b**c", slice[1].content)
        assertTrue(slice[1].code)
        assertEquals(false, slice[1].bold)
    }

    @Test
    fun `nested markers`() {
        val slice = texts("**粗里有 `码` 和 *斜***")
        assertTrue(slice.all { it.bold })
        assertEquals("码", slice.single { it.code }.content)
        assertEquals("斜", slice.single { it.italic }.content)
    }

    @Test
    fun `unmatched star stays literal`() {
        assertEquals(listOf("3*4 的意思"), texts("3*4 的意思").map { it.content })
    }

    @Test
    fun `underscore inside identifier is not italic`() {
        val slice = texts("字段 media_source_id 变了")
        assertEquals(listOf("字段 media_source_id 变了"), slice.map { it.content })
        assertTrue(slice.none { it.italic })
    }

    @Test
    fun `strikethrough`() {
        val slice = texts("~~作废~~ 了")
        assertEquals(true, slice.first().strikethrough)
    }

    @Test
    fun `inline image degrades to link text`() {
        val slice = texts("看这张 ![截图](https://example.com/a.png) 就懂")
        assertEquals("截图", slice[1].content)
        assertEquals("https://example.com/a.png", slice[1].url)
    }

    @Test
    fun `blocks are classified`() {
        val blocks = parseChangelogBlocks(
            """
                概述一句话。

                ### 修复
                * 顶层条目
                    * 嵌套条目
                > 引用里有 [链接](https://example.com)
                ![图](https://example.com/a.png)
                ---
                | 平台 | 下载 |
            """.trimIndent(),
            Body, Section,
        )
        assertEquals(
            listOf(
                ChangelogBlock.Paragraph::class,
                ChangelogBlock.Section::class,
                ChangelogBlock.Item::class,
                ChangelogBlock.Item::class,
                ChangelogBlock.Quote::class,
                ChangelogBlock.Image::class,
                // 分隔线被丢掉, 表格行退化成普通段落原样显示
                ChangelogBlock.Paragraph::class,
            ),
            blocks.map { it::class },
        )
        assertEquals(0, (blocks[2] as ChangelogBlock.Item).indent)
        assertEquals(1, (blocks[3] as ChangelogBlock.Item).indent)
        assertEquals("https://example.com/a.png", (blocks[5] as ChangelogBlock.Image).url)
    }

    @Test
    fun `section keeps its style`() {
        val blocks = parseChangelogBlocks("### 修复", Body, Section)
        val slice = (blocks.single() as ChangelogBlock.Section).slice
            .filterIsInstance<UIRichElement.Annotated.Text>()
        assertEquals("修复", slice.single().content)
        assertEquals(16f, slice.single().size)
        assertTrue(slice.single().bold)
    }
}
