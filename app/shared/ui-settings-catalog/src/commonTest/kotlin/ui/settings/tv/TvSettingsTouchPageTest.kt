/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import me.him188.ani.app.data.models.preference.LowRamDevice
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.models.preference.UISettings
import me.him188.ani.app.navigation.SettingsTab
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「界面」与「主题与色彩」两类在触屏版设置页 (平板模式与 iPad) 上不列只对遥控器起作用的项, 模糊背景不给「不模糊」, 见 [TvSettingsCatalogEntries].
 * 行标题按文案的 key 取.
 */
class TvSettingsTouchPageTest {
    private val resolver = TvTextResolver { it.key }

    // 建目录时有几项向 Koin 要东西 (片库支不支持、内存小不小)
    @BeforeTest
    fun startKoinBeforeTest() {
        startKoin { modules(module { single { LowRamDevice(false) } }) }
    }

    @AfterTest
    fun stopKoinAfterTest() = stopKoin()

    private fun values(touchPage: Boolean, vararg extra: Pair<TvSettingKey<*>, Any>) = TvSettingsValues(
        mapOf(
            TvSettingKeys.ui to UISettings.Default,
            TvSettingKeys.theme to ThemeSettings.Default,
            TvSettingKeys.touchPage to touchPage,
            *extra,
        ),
    )

    private fun rows(tab: SettingsTab, values: TvSettingsValues, drill: List<String> = emptyList()) =
        buildTvSettingsPage(TvSettingsCatalogEntries, values, TvSettingsNav(tab.name, drill), resolver).right.rows

    private fun titles(values: TvSettingsValues) =
        (rows(SettingsTab.APPEARANCE, values) + rows(SettingsTab.THEME, values)).map { it.title }

    @Test
    fun `touch page leaves out the items that only work with a remote`() {
        val remoteOnly = listOf(
            "settings_theme_tv_back_long_press",
            "settings_theme_tv_play_long_press",
            "settings_theme_tv_explore_layout",
            "settings_theme_tv_poster_idle_dim",
        )
        val tv = titles(values(touchPage = false))
        val touch = titles(values(touchPage = true))
        assertTrue(tv.containsAll(remoteOnly), "电视版设置页列着: $tv")
        assertTrue(touch.none { it in remoteOnly }, "触屏版设置页不列: $touch")
    }

    @Test
    fun `touch page offers no backdrop combo without blur`() {
        fun options(touchPage: Boolean): List<String> {
            val values = values(touchPage)
            val blur = rows(SettingsTab.THEME, values).single { it.title == "settings_theme_tv_backdrop_blur" }
            return rows(SettingsTab.THEME, values, drill = listOf(blur.id)).map { it.title }
        }
        val tv = options(touchPage = false)
        val touch = options(touchPage = true)
        assertTrue("settings_theme_tv_backdrop_blur_none" in tv)
        assertFalse("settings_theme_tv_backdrop_blur_none" in touch)
        assertEquals(tv.size - 1, touch.size, "只少了不模糊那一档")
    }

    @Test
    fun `hiding the navigation bar is listed only in tablet mode`() {
        fun listed(vararg interfaceMode: Pair<TvSettingKey<*>, Any>) = "settings_theme_tv_hide_navigation_bar" in
            rows(SettingsTab.APPEARANCE, values(true, TvSettingKeys.touchscreen to true, *interfaceMode)).map { it.title }
        assertTrue(listed(TvSettingKeys.interfaceMode to false))
        assertFalse(listed(TvSettingKeys.interfaceMode to true))
        // 没有模式可选的端 (iOS): 系统栏不归它管
        assertFalse(listed())
    }
}
