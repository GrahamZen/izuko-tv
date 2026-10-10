/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import me.him188.ani.app.data.models.danmaku.DanmakuFilterConfig
import me.him188.ani.app.data.models.danmaku.DanmakuRegexFilter
import me.him188.ani.app.data.models.preference.AnitorrentConfig
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import me.him188.ani.app.data.models.preference.DebugSettings
import me.him188.ani.app.data.models.preference.EndpointSelection
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.models.preference.ProxySettings
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.models.preference.TorrentPeerConfig
import me.him188.ani.app.data.models.preference.UISettings
import me.him188.ani.app.data.models.preference.UpdateSettings
import me.him188.ani.app.data.models.preference.VideoResolverSettings
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.app.domain.torrent.peer.PeerFilterSubscription
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.foundation.Res
import me.him188.ani.app.ui.foundation.a
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_app_search
import me.him188.ani.app.ui.lang.settings_category_app_ui
import me.him188.ani.app.ui.lang.settings_tab_about
import me.him188.ani.app.ui.lang.settings_tab_appearance
import me.him188.ani.app.ui.lang.settings_tab_player
import me.him188.ani.app.ui.lang.settings_tab_proxy
import me.him188.ani.app.ui.lang.settings_theme_title
import me.him188.ani.app.ui.lang.tv_settings_legacy_hint
import me.him188.ani.app.ui.lang.tv_settings_legacy_open
import me.him188.ani.app.ui.lang.tv_settings_off
import me.him188.ani.app.ui.lang.tv_settings_on
import me.him188.ani.app.ui.settings.account.AccountSettingsState
import me.him188.ani.app.ui.settings.tabs.about.OpenSourceLibraryInfo
import me.him188.ani.app.ui.settings.tabs.about.OpenSourceLicenseInfo
import me.him188.ani.app.ui.settings.tabs.media.PikPakDriveUsagePresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @see buildTvSettingsPage
 */
class TvSettingsModelTest {
    private data class Fake(
        val enabled: Boolean = false,
        val level: Int = 2,
        val hidden: Boolean = true,
        val order: List<Pair<String, Boolean>> = listOf("a" to true, "b" to false, "c" to true),
    )

    private val fake = TvSettingKey.Stored<Fake>("fake") { error("not used") }
    private val rows = TvSettingKey.Live<List<String>>("rows") { error("not used") }

    private val catalog = tvSettingsCatalog {
        section(Lang.settings_category_app_ui)
        category(SettingsTab.APPEARANCE, Lang.settings_tab_appearance) {
            header(Lang.settings_theme_title)
            toggle(
                fake, Lang.settings_theme_title,
                qr = { v -> TvQr("code:${v[fake]?.enabled}", caption = tvText("cap")) },
                read = { it.enabled },
                write = { copy(enabled = it) },
            )
            choice(
                fake, Lang.settings_theme_title,
                options = { listOf(1, 2, 3) },
                label = { tvText("L$it") },
                read = { it.level },
                write = { copy(level = it) },
                optionDescription = { if (it == 3) tvText("three") else null },
            )
            choice(
                fake, Lang.settings_theme_title,
                options = { listOf(1) },
                label = { tvText("hidden") },
                read = { 1 },
                write = { this },
                visible = { !it.hidden },
            )
            // 下面没有项的组标题不出
            header(Lang.settings_app_search)
        }
        category(SettingsTab.PLAYER, Lang.settings_tab_player) {
            header(Lang.settings_theme_title)
            entries(
                listOf(rows),
                entries = { it[rows].orEmpty() },
                key = { it },
                title = { tvText(it) },
                value = { _, row -> tvText("v:$row") },
                move = { order -> listOf(TvSettingsEdit.Run { _ -> error("moved to $order") }) },
            ) { emptyList() }
            sorter(
                fake, Lang.settings_theme_title,
                read = { it.order },
                label = { tvText(it.uppercase()) },
                summary = { list -> tvText(list.filter { it.second }.joinToString { it.first }) },
                write = { copy(order = it) },
            )
        }
        legacy(SettingsTab.PROXY, Lang.settings_tab_proxy)
        category(SettingsTab.ABOUT, Lang.settings_tab_about) {
            group(Lang.settings_theme_title) {
                header(Lang.settings_theme_title)
                toggle(fake, Lang.settings_theme_title, read = { it.enabled }, write = { copy(enabled = it) })
                group(Lang.settings_theme_title) {
                    choice(
                        fake, Lang.settings_theme_title,
                        options = { listOf(1, 2, 3) },
                        label = { tvText("L$it") },
                        read = { it.level },
                        write = { copy(level = it) },
                    )
                }
            }
        }
    }

    private val resolver
        get() = TvTextResolver { res ->
            when (res) {
                Lang.tv_settings_on -> "on"
                Lang.tv_settings_off -> "off"
                Lang.tv_settings_legacy_open -> "open"
                Lang.tv_settings_legacy_hint -> "hint"
                else -> "t"
            }
        }

    private fun values(value: Fake = Fake(), rowKeys: List<String>? = listOf("x", "y:z")) =
        TvSettingsValues(buildMap { put(fake, value); if (rowKeys != null) put(rows, rowKeys) })

    private fun build(nav: TvSettingsNav = TvSettingsNav(), value: Fake = Fake(), rowKeys: List<String>? = listOf("x", "y:z")) =
        buildTvSettingsPage(catalog, values(value, rowKeys), nav, resolver)

    @Test
    fun `rail lists sections and categories`() {
        val content = build()
        assertEquals(
            listOf(TvSettingsRowKind.Header) + List(4) { TvSettingsRowKind.Category },
            content.rail.map { it.kind },
        )
        assertEquals("cat:APPEARANCE", content.railKey)
    }

    @Test
    fun `items show current values and hide what is not shown`() {
        val rows = build().right.rows
        assertEquals(listOf(TvSettingsRowKind.Header, TvSettingsRowKind.Item, TvSettingsRowKind.Item), rows.map { it.kind })
        assertEquals("off", rows[1].value)
        assertEquals("L2", rows[2].value)
        assertTrue(rows[2].chevron)
        assertEquals("on", build(value = Fake(enabled = true)).right.rows[1].value)
        // 显示条件满足时多出一项
        assertEquals(4, build(value = Fake(hidden = false)).right.rows.size)
    }

    @Test
    fun `qr codes go into the info of their row`() {
        val content = build()
        val toggle = content.right.rows[1]
        assertEquals(TvSettingsQr("code:false", "cap", ""), content.info[toggle.id]?.qr)
        assertNull(content.info[content.right.rows[2].id]?.qr)
    }

    @Test
    fun `drilling into a choice lists options with the current one checked and focused`() {
        val choiceId = build().right.rows[2].id
        val content = build(TvSettingsNav(categoryId = "APPEARANCE", drill = listOf(choiceId)))
        val right = content.right
        assertEquals(listOf("L1", "L2", "L3"), right.rows.map { it.title })
        assertEquals(listOf(false, true, false), right.rows.map { it.checked })
        assertEquals(right.rows[1].id, right.focusId)
        assertEquals("cat:APPEARANCE/$choiceId", right.key)
        assertEquals(choiceId to 2, TvSettingsRowIds.parseOption(right.rows[2].id))
        // 有自己说明的选项, 说明栏写它
        assertEquals(TvSettingsInfo("L3", "three"), content.info[right.rows[2].id])
        assertEquals("t", content.info[right.rows[0].id]?.title)
    }

    @Test
    fun `choice writes only when the value changes`() {
        val values = values()
        val choice = assertIs<TvSettingItem.Choice<*>>(catalog.first().items!![2])
        assertTrue(choice.edits(values, 1).isEmpty())
        val edit = assertIs<TvSettingsEdit.Update<*>>(choice.edits(values, 0).single())
        @Suppress("UNCHECKED_CAST")
        assertEquals(Fake(level = 1), (edit as TvSettingsEdit.Update<Fake>).transform(Fake()))
        assertNull(choice.confirmFor(values, 0))
    }

    @Test
    fun `entries become rows with their keys and can be moved`() {
        val right = build(TvSettingsNav(categoryId = "PLAYER")).right
        val entryRows = right.rows.filter { TvSettingsRowIds.parseEntry(it.id) != null }
        assertEquals(listOf("x", "y:z"), entryRows.map { it.title })
        assertEquals(listOf("v:x", "v:y:z"), entryRows.map { it.value })
        // key 里有冒号也拆得回来
        val itemId = catalog[1].items!![1].id
        assertEquals(itemId to "y:z", TvSettingsRowIds.parseEntry(entryRows[1].id))
        assertTrue(entryRows.all { it.moveGroup == itemId })
    }

    @Test
    fun `header over an empty list is dropped`() {
        val rows = build(TvSettingsNav(categoryId = "PLAYER"), rowKeys = emptyList()).right.rows
        // 只剩排序那一行 (组标题下面还有它, 组标题留着)
        assertEquals(listOf(TvSettingsRowKind.Header, TvSettingsRowKind.Item), rows.map { it.kind })
        // 列表的值还没到: 列表不出
        assertEquals(2, build(TvSettingsNav(categoryId = "PLAYER"), rowKeys = null).right.rows.size)
    }

    @Test
    fun `sorter lists every option and toggles or reorders them`() {
        val sorter = assertIs<TvSettingItem.Sorter>(catalog[1].items!![2])
        val item = build(TvSettingsNav(categoryId = "PLAYER")).right.rows.last()
        assertEquals(sorter.id, item.id)
        assertEquals("a, c", item.value)

        val drilled = build(TvSettingsNav(categoryId = "PLAYER", drill = listOf(sorter.id))).right
        assertEquals(listOf("A", "B", "C"), drilled.rows.map { it.title })
        assertEquals(listOf(true, false, true), drilled.rows.map { it.checked })
        assertTrue(drilled.rows.all { it.moveGroup == sorter.id })

        @Suppress("UNCHECKED_CAST")
        fun apply(edits: List<TvSettingsEdit>) = (edits.single() as TvSettingsEdit.Update<Fake>).transform(Fake()).order
        assertEquals(listOf("a" to true, "b" to true, "c" to true), apply(sorter.toggleEdits(values(), "b")))
        assertEquals(listOf("c" to true, "a" to true, "b" to false), apply(sorter.reorderEdits(values(), listOf("c", "a", "b"))))
        // 对不上全部选项的顺序不写
        assertTrue(sorter.reorderEdits(values(), listOf("c", "a")).isEmpty())
    }

    @Test
    fun `groups drill in level by level`() {
        val outer = build(TvSettingsNav(categoryId = "ABOUT")).right.rows.single()
        assertEquals("ABOUT.0", outer.id)
        assertTrue(outer.chevron)

        val inGroup = build(TvSettingsNav("ABOUT", listOf(outer.id))).right
        assertEquals("cat:ABOUT/ABOUT.0", inGroup.key)
        assertEquals("t", inGroup.title)
        assertEquals(listOf(TvSettingsRowKind.Header, TvSettingsRowKind.Item, TvSettingsRowKind.Item), inGroup.rows.map { it.kind })
        assertEquals(inGroup.rows[1].id, inGroup.focusId)
        val inner = inGroup.rows.last()
        assertEquals("ABOUT.0.2", inner.id)

        val choice = build(TvSettingsNav("ABOUT", listOf(outer.id, inner.id))).right.rows.single()
        assertIs<TvSettingItem.Choice<*>>(findTvSettingItem(catalog, choice.id))
        val options = build(TvSettingsNav("ABOUT", listOf(outer.id, inner.id, choice.id))).right
        assertEquals("cat:ABOUT/ABOUT.0/ABOUT.0.2/${choice.id}", options.key)
        assertEquals(listOf("L1", "L2", "L3"), options.rows.map { it.title })
        assertEquals(listOf(false, true, false), options.rows.map { it.checked })

        // 走不通的那一段之后不再往里: 停在走得到的那一层
        assertEquals("cat:ABOUT/ABOUT.0", build(TvSettingsNav("ABOUT", listOf(outer.id, "nope", inner.id))).right.key)
    }

    @Test
    fun `entries and actions carry their info image`() {
        val image = TvImage(Res.drawable.a, round = false)
        val catalog = tvSettingsCatalog {
            category(SettingsTab.ABOUT, Lang.settings_tab_about) {
                action(Lang.settings_theme_title, image = image) { emptyList() }
                entries(emptyList(), entries = { listOf("x") }, key = { it }, title = { tvText(it) }, image = { TvImage(Res.drawable.a) }) {
                    emptyList()
                }
            }
        }
        val content = buildTvSettingsPage(catalog, TvSettingsValues(emptyMap()), TvSettingsNav(), resolver)
        assertEquals(listOf(image, TvImage(Res.drawable.a)), content.right.rows.map { content.info[it.id]?.image })
    }

    @Test
    fun `legacy category has one row that opens the old page`() {
        val content = build(TvSettingsNav(categoryId = "PROXY"))
        val row = content.right.rows.single()
        assertEquals("legacy:PROXY", row.id)
        assertEquals("open", row.title)
        assertEquals("hint", content.info[row.id]?.body)
    }

    @Test
    fun `unknown category falls back to the first one with items`() {
        assertEquals("cat:APPEARANCE", build(TvSettingsNav(categoryId = "NOPE")).railKey)
    }

    @Test
    fun `real catalog builds every category with unique ids and checked options`() {
        val values = TvSettingsValues(
            mapOf(
                TvSettingKeys.ui to UISettings.Default,
                TvSettingKeys.theme to ThemeSettings.Default,
                TvSettingKeys.player to VideoScaffoldConfig.Default,
                TvSettingKeys.danmakuFilter to DanmakuFilterConfig.Default,
                TvSettingKeys.debug to DebugSettings.Default.copy(enabled = true),
                TvSettingKeys.language to "",
                TvSettingKeys.displayModes to listOf(TvDisplayMode(1, 60)),
                TvSettingKeys.danmakuRules to listOf(DanmakuRegexFilter(id = "r1", regex = "签")),
                TvAppSettingKeys.account to AccountSettingsState.Empty,
                TvAppSettingKeys.update to UpdateSettings.Default,
                TvAppSettingKeys.metered to false,
                TvAppSettingKeys.perfStatus to TvOptionalText(null),
                TvSourceSettingKeys.selector to MediaSelectorSettings.Default,
                TvSourceSettingKeys.preference to MediaPreference.PlatformDefault,
                TvSourceSettingKeys.resolver to VideoResolverSettings.Default,
                TvSourceSettingKeys.sources to emptyList<MediaSourceInstance>(),
                TvSourceSettingKeys.sourceTests to TvSourceTestsState(emptyMap(), running = false),
                TvSourceSettingKeys.subscriptions to listOf(MediaSourceSubscription(subscriptionId = "s1", url = "https://github.com/a/b/raw/x.json")),
                TvSourceSettingKeys.subscriptionUpdate to TvSubscriptionUpdate(running = false, progress = null),
                TvSourceSettingKeys.drives to emptyList<TvDrive>(),
                TvNetworkSettingKeys.proxy to ProxySettings.Default,
                TvNetworkSettingKeys.tmdbDisabled to false,
                TvNetworkSettingKeys.tmdbEndpoint to EndpointSelection.Default,
                TvNetworkSettingKeys.tmdbHosts to listOf("https://image.tmdb.org"),
                TvNetworkSettingKeys.bangumi to BangumiEndpointSettings.Default,
                TvNetworkSettingKeys.bangumiMirrors to listOf("mirror.example.com"),
                TvNetworkSettingKeys.bangumiLoggedIn to false,
                TvNetworkSettingKeys.proxyTest to TvProxyTestState(null, running = false),
                TvNetworkSettingKeys.torrent to AnitorrentConfig.Default,
                TvNetworkSettingKeys.peer to TorrentPeerConfig.Default,
                TvNetworkSettingKeys.peerSubscriptions to emptyList<PeerFilterSubscription>(),
                TvNetworkSettingKeys.pikpak to PikPakConfig.Default.copy(enabled = true),
                TvNetworkSettingKeys.pikpakUsage to PikPakDriveUsagePresentation.Idle,
                TvNetworkSettingKeys.mediaCache to MediaCacheSettings.Default,
                TvAboutSettingKeys.openSourceLibraries to TvOpenSourceLibraries(
                    listOf(
                        OpenSourceLibraryInfo(
                            "a:b", "Lib", "1.0", "https://example.com",
                            listOf(OpenSourceLicenseInfo("Apache License 2.0", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")),
                        ),
                    ),
                ),
            ),
        )
        val ids = HashSet<String>()
        val categories = TvSettingsCatalogEntries.filter { it.visible(values) }
        // 全部分类都是新样式, 没有要打开原来设置页的
        assertTrue(categories.all { it.items != null })
        // 分类本身与每一组 (一层层进去) 都建一遍
        fun visit(category: TvSettingsCategory, path: List<String>, items: List<TvSettingItem>) {
            val content = buildTvSettingsPage(TvSettingsCatalogEntries, values, TvSettingsNav(category.id, path), TvTextResolver { "x" })
            assertTrue(content.right.rows.any { it.focusable }, "nothing to focus in ${category.id} $path")
            for (row in content.right.rows) assertTrue(ids.add(row.id), "duplicate row id ${row.id}")
            for (item in items.filter { it.shown(values) }) {
                when (item) {
                    is TvSettingItem.Group -> visit(category, path + item.id, item.items)
                    is TvSettingItem.Choice<*> -> {
                        val options = buildTvSettingsPage(
                            TvSettingsCatalogEntries, values, TvSettingsNav(category.id, path + item.id), TvTextResolver { "x" },
                        ).right.rows
                        assertTrue(options.isNotEmpty(), "no options for ${item.id}")
                        // 默认值都在档位里 (调色板的默认色、各开关的默认档); 删规则那种没有当前值的单选不算
                        if (item.currentLabel(values) != null) {
                            assertEquals(1, options.count { it.checked }, "current option of ${item.id} not found")
                        }
                    }

                    else -> {}
                }
            }
        }
        for (category in categories) visit(category, emptyList(), category.items.orEmpty())
        // 开源许可读完了: 「正在读取」那一行不出, 一个库一行, 行尾写许可证的短名
        val about = TvSettingsCatalogEntries.single { it.id == "ABOUT" }
        val thanks = about.items.orEmpty().filterIsInstance<TvSettingItem.Group>()[1]
        val oss = thanks.items.filterIsInstance<TvSettingItem.Group>().single()
        val libraries = buildTvSettingsPage(
            TvSettingsCatalogEntries, values, TvSettingsNav("ABOUT", listOf(thanks.id, oss.id)), TvTextResolver { "x" },
        ).right.rows
        assertEquals(listOf("Lib" to "Apache-2.0"), libraries.map { it.title to it.value })
    }

    @Test
    fun `subscription labels are short`() {
        assertEquals("weixianweide/sources", subscriptionLabel("https://raw.githubusercontent.com/weixianweide/sources/main/a.json"))
        assertEquals("a/b", subscriptionLabel("https://cdn.jsdelivr.net/gh/a/b@main/x.json"))
        assertEquals("example.com", subscriptionLabel("https://www.example.com/sub.json"))
    }

    @Test
    fun `templates format positional and sequential placeholders`() {
        assertEquals("提前 5 秒", formatTvTemplate("提前 %1\$d 秒", listOf("5")))
        assertEquals("海报墙重、详情页轻", formatTvTemplate("海报墙%1\$s、详情页%2\$s", listOf("重", "轻")))
        assertEquals("a b 100%", formatTvTemplate("%s %s 100%%", listOf("a", "b")))
        // 安卓资源写法的引号转义换回引号
        assertEquals("Export today's \"log\"", unescapeQuotes("Export today\\'s \\\"log\\\""))
    }
}
