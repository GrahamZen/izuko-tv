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
 * 「设置」标签: 分类列表与各组卡片对得上; 清单登记的通用项 (见 [RemoteSettingsCatalog]) 按 page 有地方画,
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
        // 搜索时各组对得上的卡片平铺成一页, 每组前面有组名小标题
        for (g in groups.keys) assertContains(page, "<div class=\"set-group\" data-group=\"$g\" hidden>\n<h2 class=\"set-group-h\">")
        assertContains(page, """<input type="search" id="set-q" placeholder="搜索设置" aria-label="搜索设置" autocomplete="off">""")
        assertEquals(
            mapOf(
                "account" to listOf("set-profiles", "set-account", "set-history"),
                "play" to listOf("set-generic", "set-dmfilter"),
                "network" to listOf("set-proxy", "set-bangumi", "set-tmdb"),
                "device" to listOf("set-look", "set-front", "set-keep"),
                "feedback" to listOf("set-feedback"),
                "maintain" to listOf("set-update", "set-logs", "set-perf", "set-backup", "set-migrate"),
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

    /** 清单里的项按 page 分到播放与弹幕组与数据源标签的 BT 页, 两处都提交到同一个接口; 缓存放在哪、片库只在电视上改 (有引导), 不在清单里. */
    @Test
    fun `catalog settings are rendered generically and saved through one endpoint`() {
        for (box in listOf("set-generic", "src-bt")) assertContains(page, """<div id="$box"></div>""")
        assertContains(page, "renderGeneric(d.generic)")
        for (box in listOf("genBox", "btBox")) assertContains(page, "bindGeneric($box, 'api/settings/generic/set')")
        val pages = RemoteSettingsCatalog.items.associate { it.key to it.page }
        for (key in listOf("extraTrackers", "trackerSubscriptions", "peerIpBlackList", "peerIpRules", "peerIdRules", "peerClientRules")) {
            assertEquals(RemoteSettingPage.Bt, pages[key], key)
        }
        assertTrue("cacheLocation" !in pages && "mediaLibrary" !in pages)
        assertTrue("data-group=\"storage\"" !in page)
        assertEquals(RemoteSettingPage.Play, pages["preferredAlliances"])
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

    /** 「反馈」那一组: 卡片脚本在页面里, 进组时读一次; 网页上的文案与分类行的静态文字都在译文表里 */
    @Test
    fun `feedback group is wired and translated`() {
        assertContains(page, FEEDBACK_SCRIPT)
        assertContains(page, "if (g === 'feedback' && window.loadFeedback) window.loadFeedback();")
        val keys = REMOTE_I18N_TABLE.map { it.zh }.toSet()
        val texts = Regex("""(?<![\w.])T\('([^']+)'""").findAll(FEEDBACK_SCRIPT).map { it.groupValues[1] }.toList()
        assertTrue(texts.size > 10)
        for (t in texts) assertTrue(t in keys, "没有译文：$t")
        for (t in listOf("反馈", "故障反馈，以及标题 logo、对应作品修正的处理进度")) assertTrue(t in keys, "没有译文：$t")
    }

    /** 「最近改过」在首页最上面 (搜索框下面), 脚本在页面里, 首页画出来时跟着画; 账号、反馈、维护几组不记. */
    @Test
    fun `recently changed settings row is on the home page`() {
        assertContains(page, "<div id=\"set-home\">\n<div id=\"set-recent\" hidden></div>\n<div class=\"card set-nav set-me\">")
        assertContains(page, SETTINGS_RECENT_SCRIPT)
        assertContains(page, "if (!q && !g && window.paintRecentSettings) window.paintRecentSettings();")
        assertContains(SETTINGS_RECENT_SCRIPT, "var SKIP = { account: true, feedback: true, maintain: true };")
        val keys = REMOTE_I18N_TABLE.map { it.zh }.toSet()
        for (t in Regex("""(?<![\w.])T\('([^']+)'""").findAll(SETTINGS_RECENT_SCRIPT).map { it.groupValues[1] }) {
            assertTrue(t in keys, "没有译文：$t")
        }
    }
}

