/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import me.him188.ani.app.navigation.SettingsTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 通知里的写法 ([parseNoticeText] / [parseNoticeLinkTarget]; docs/contributing/code/notices.md 8 节「发版后的通知」). */
class TvNoticeMarkupTest {
    private fun links(line: String) = parseNoticeText(line).links.map { it.label to it.target }

    @Test
    fun `title and detail split on the colon`() {
        val text = parseNoticeText("交流群号已更改：新群号可在「设置」中查看")
        assertEquals("交流群号已更改", text.title)
        assertEquals("新群号可在「设置」中查看", text.plainDetail)
        assertNull(parseNoticeText("没有冒号的一句").plainDetail)
        // 冒号前面太长: 是一句完整的话, 不拆
        val long = "很长".repeat(16) + "：后面"
        assertEquals(long, parseNoticeText(long).title)
    }

    @Test
    fun `links keep their text and give a target`() {
        val line = "群号变更 [加群](https://example.com:8080/x)：请看 [说明](https://example.com/a) 或 [条目](ani://subjects/454684)"
        val text = parseNoticeText(line)
        assertEquals("群号变更 加群", text.title)
        assertEquals("请看 说明 或 条目", text.plainDetail)
        assertEquals(
            listOf(
                "加群" to TvNoticeLinkTarget.Web("https://example.com:8080/x"),
                "说明" to TvNoticeLinkTarget.Web("https://example.com/a"),
                "条目" to TvNoticeLinkTarget.Subject(454684),
            ),
            links(line),
        )
    }

    @Test
    fun `a link alone in its paragraph is not written as text`() {
        val text = parseNoticeText("交流群已更改：新群号见下\\n[查看交流群](ani://settings/about/chat-groups)")
        assertEquals(listOf("新群号见下"), text.paragraphs.map { it.text })
        assertEquals(listOf("查看交流群" to TvNoticeLinkTarget.Settings(SettingsTab.ABOUT, "chat-groups")), links("x：y [查看交流群](ani://settings/about/chat-groups)"))
    }

    @Test
    fun `unknown links stay plain text`() {
        val text = parseNoticeText("标题：看 [旧版](http://example.com) 与 [坏链接](ani://nowhere/1)")
        assertEquals("看 旧版 与 坏链接", text.plainDetail)
        assertTrue(text.links.isEmpty())
    }

    @Test
    fun `link targets`() {
        assertEquals(TvNoticeLinkTarget.Settings(SettingsTab.PROFILE, "notices"), parseNoticeLinkTarget("ani://settings/account/notices"))
        assertEquals(TvNoticeLinkTarget.Settings(SettingsTab.UPDATE, null), parseNoticeLinkTarget("ani://settings/update"))
        assertEquals(TvNoticeLinkTarget.Settings(SettingsTab.MEDIA_SOURCE, null), parseNoticeLinkTarget("ani://settings/media-source"))
        assertNull(parseNoticeLinkTarget("ani://settings/nothing"))
        assertEquals(TvNoticeLinkTarget.WhatsNew, parseNoticeLinkTarget("ani://whatsnew"))
        assertEquals(TvNoticeLinkTarget.Subject(7), parseNoticeLinkTarget("ani://subjects/7"))
        assertNull(parseNoticeLinkTarget("ani://subjects/7?ep=3"))
        assertNull(parseNoticeLinkTarget("ani://episodes/7"))
        assertNull(parseNoticeLinkTarget("http://example.com"))
        assertNull(parseNoticeLinkTarget("not a url"))
    }

    @Test
    fun `bold and paragraphs`() {
        val text = parseNoticeText("标题：先 **停用** 旧地址\\n再 [看说明](https://example.com)")
        assertEquals(listOf("先 停用 旧地址", "再 看说明"), text.paragraphs.map { it.text })
        val spans = text.paragraphs[0].spanStyles
        assertEquals(1, spans.size)
        assertEquals("停用", text.paragraphs[0].text.substring(spans[0].start, spans[0].end))
        assertEquals("代码 code", parseNoticeText("标题：代码 `code`").plainDetail)
    }
}
