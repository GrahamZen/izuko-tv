/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 底栏的「数据源」标签: 顶上五页与各页的容器对得上, 各脚本画进去的容器都在; 地址栏 #sources/页名 认得这几页.
 */
class RemoteControlPageSourcesTabTest {
    private val page = renderRemoteControlPage(
        initialTab = "search",
        searchFormHtml = "",
        requestSectionHtml = "",
    )

    @Test
    fun `tab bar has five tabs with sources before settings`() {
        val tabs = Regex("""<button data-tab="(\w+)">""").findAll(page).map { it.groupValues[1] }.toList()
        assertEquals(listOf("search", "player", "cache", "sources", "settings"), tabs)
        assertContains(page, "<section class=\"tab\" id=\"tab-sources\" hidden>")
    }

    /** 分段的每一页都有一块, 顺序一样; 各页里是哪几个脚本的容器. */
    @Test
    fun `each segment has a pane holding the containers of its scripts`() {
        val segs = Regex("""<button type="button" data-stab="(\w+)"""").findAll(SOURCES_TAB_HTML).map { it.groupValues[1] }.toList()
        val panes = Regex("""<div class="src-pane" data-stab="(\w+)"(?: hidden)?>\n(.*?)\n</div>\n(?=<div class="src-pane"|</section>)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(SOURCES_TAB_HTML)
            .associate { m -> m.groupValues[1] to Regex("""<div id="([\w-]+)"></div>""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList() }
        assertEquals(listOf("list", "subs", "drives", "local", "bt"), segs)
        assertEquals(
            mapOf(
                "list" to listOf("src-list"),
                "subs" to listOf("src-subs"),
                "drives" to listOf("src-drives"),
                "local" to listOf("src-local"),
                "bt" to listOf("src-bt", "set-pikpak"),
            ),
            panes,
        )
        // 添加表单在「+」打开的面板里
        assertContains(SOURCES_TAB_HTML, "<div class=\"sheet-body\"><div id=\"src-add\"></div></div>")
        assertContains(page, "var SRC_TABS = ['list', 'subs', 'drives', 'local', 'bt'];")
    }

    /** 电视设置里「添加 WebDAV」的二维码 (#sources/webdav) 停在本地页, 添加面板直接打开 WebDAV 的表单. */
    @Test
    fun `webdav link opens the local page with the add form`() {
        assertContains(page, "if (h0 === 'sources/webdav') {\n    window.pendingSourceAdd = 'webdav';\n    h0 = 'sources/local';")
        assertContains(page, "      openAddType(k);\n      if (window.sourceAddSheet) window.sourceAddSheet.open();")
    }

    /** 添加面板第一屏是类型列表 (订阅 / 单个数据源 / 本地), 不是下拉框; 面板头有返回键回到列表. */
    @Test
    fun `add panel starts with a list of types`() {
        assertContains(SOURCES_TAB_HTML, "<button type=\"button\" class=\"sheet-btn\" id=\"add-back\" hidden")
        for (row in listOf("addRow('sub'", "addRow('import'", "addRow('local'", "addRow('webdav'")) assertContains(page, row)
        assertTrue("<select id=\"src-new\"" !in page)
    }

    /** 网页上新加的文案都在译文表里 (英文页面不会漏出简体): 静态 HTML 的整段文字与属性、脚本里的 T(). */
    @Test
    fun `texts are translated`() {
        val keys = REMOTE_I18N_TABLE.map { it.zh }.toSet()
        val han = "[一-鿿]"
        val static = Regex(">([^<>]*$han[^<>]*)<").findAll(SOURCES_TAB_HTML).map { it.groupValues[1].trim() } +
            Regex("(?:aria-label|title)=\"([^\"]*$han[^\"]*)\"").findAll(SOURCES_TAB_HTML).map { it.groupValues[1] }
        val scripts = Regex("""(?<![\w.])T\('([^']+)'""").findAll(SOURCES_TAB_SCRIPT).map { it.groupValues[1] }.toList()
        assertTrue(scripts.size >= 3)
        for (t in static + scripts) assertTrue(t in keys, "没有译文：$t")
    }
}
