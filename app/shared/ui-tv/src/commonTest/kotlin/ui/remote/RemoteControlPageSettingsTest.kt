/*
 * Copyright (C) 2026 OpenAni and contributors.
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「设置」标签的「常规」页: 分类列表与各组卡片对得上; 清单登记的通用项 (见 [RemoteSettingsCatalog]) 有地方画,
 * 并提交到 [RemoteSettings] 认的路径.
 */
class RemoteControlPageSettingsTest {
    private val page = renderRemoteControlPage(
        initialTab = "settings",
        searchFormHtml = "",
        requestSectionHtml = "",
    )

    /**
     * 第一屏的每一行都有对应的一组卡片, 各张卡片的容器都归进了某一组 (没归进去的卡片在网页上就看不到了).
     */
    @Test
    fun `general settings are grouped behind a category list`() {
        val rows = Regex("""<button type="button" class="set-nav-row" data-group="(\w+)">""")
            .findAll(page).map { it.groupValues[1] }.toList()
        val groups = Regex("""<div class="set-group" data-group="(\w+)" hidden>\n(.*?)\n</div>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(page)
            .associate { m ->
                m.groupValues[1] to Regex("""<div id="(set-[\w-]+)"></div>""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
            }

        assertEquals(rows, groups.keys.toList())
        // 「全部」显示方式下每组前面有组名小标题
        for (g in groups.keys) assertContains(page, "<div class=\"set-group\" data-group=\"$g\" hidden>\n<h2 class=\"set-group-h\">")
        assertContains(page, """<button type="button" data-smode="all">全部</button>""")
        assertEquals(
            mapOf(
                "account" to listOf("set-profiles", "set-account", "set-history"),
                "look" to listOf("set-look"),
                "connect" to listOf("set-front", "set-keep"),
                "network" to listOf("set-proxy", "set-bangumi", "set-tmdb"),
                "resources" to listOf("set-generic", "set-pikpak", "set-dmfilter"),
                "maintain" to listOf("set-update", "set-logs", "set-perf", "set-backup"),
            ),
            groups,
        )
    }

    /** 「调试」组只在 debug 包的页面里有, 排在最后, 卡片提交到调试接口. */
    @Test
    fun `debug group only exists in debug builds`() {
        val debugPage = renderRemoteControlPage(
            initialTab = "settings",
            searchFormHtml = "",
            requestSectionHtml = "",
            debugTools = true,
        )
        val rows = Regex("""<button type="button" class="set-nav-row" data-group="(\w+)">""").findAll(debugPage).map { it.groupValues[1] }.toList()
        assertEquals("debug", rows.last())
        assertContains(debugPage, "<div class=\"set-group\" data-group=\"debug\" hidden>\n<h2 class=\"set-group-h\">调试</h2>\n<div id=\"set-debug\"></div>\n</div>")
        assertContains(debugPage, "bindGeneric(dbgBox, 'api/settings/debug/set')")
        assertFalse(page.contains("data-group=\"debug\""))
    }

    @Test
    fun `catalog settings are rendered generically and saved through one endpoint`() {
        assertContains(page, """<div id="set-generic"></div>""")
        assertContains(page, "renderGeneric(d.generic)")
        assertContains(page, "bindGeneric(genBox, 'api/settings/generic/set')")
    }

    /** PikPak 卡片随 `api/settings` 画, 提交到 RemotePikPakSettings 的接口; 设置备份走 RemoteSettingsBackup 的两个接口 */
    @Test
    fun `pikpak and backup cards use their endpoints`() {
        assertContains(page, SETTINGS_EXTRAS_SCRIPT)
        assertContains(page, "if (window.renderPikPak) window.renderPikPak(d.pikpak);")
        assertContains(SETTINGS_EXTRAS_SCRIPT, "post('api/settings/pikpak', new FormData(e.target))")
        assertContains(SETTINGS_EXTRAS_SCRIPT, "post('api/settings/pikpak/test', {})")
        // 密码框不回显已存的密码
        assertContains(SETTINGS_EXTRAS_SCRIPT, """<input type="password" name="password" autocomplete="new-password" placeholder="""")
        assertContains(SETTINGS_EXTRAS_SCRIPT, "getJson('${RemoteSettingsBackup.EXPORT_PATH}', 20000)")
        assertContains(SETTINGS_EXTRAS_SCRIPT, "fetchT('${RemoteSettingsBackup.IMPORT_PATH}', ")
        // 导入前先确认
        assertContains(SETTINGS_EXTRAS_SCRIPT, "if (!confirm(T('这会覆盖当前应用的所有设置，且无法撤销，确认导入吗？'))) return;")
    }

    /** 网页上新加的文案都在译文表里 (英文页面不会漏出简体) */
    @Test
    fun `new card texts are translated`() {
        val keys = REMOTE_I18N_TABLE.map { it.zh }.toSet()
        val texts = Regex("""(?<![\w.])T\('([^']+)'""").findAll(SETTINGS_EXTRAS_SCRIPT).map { it.groupValues[1] }.toList()
        assertTrue(texts.size > 10)
        for (t in texts) assertTrue(t in keys, "没有译文：$t")
    }
}
