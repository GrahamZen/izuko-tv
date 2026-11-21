/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_player
import me.him188.ani.app.ui.lang.settings_tab_player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * @see TvSettingsItemsBuilder.showWhen
 */
class TvSettingsShowWhenTest {
    private val touchPage = TvSettingKey.Platform<Boolean>("touchPage") { null }
    private val player = TvSettingKey.Platform<Int>("player") { null }

    private val items = tvSettingsCatalog {
        category(SettingsTab.PLAYER, Lang.settings_tab_player) {
            toggleOf(listOf(player), Lang.settings_player, read = { false }, write = { emptyList() })
            showWhen(listOf(touchPage), { it[touchPage] == true }) {
                toggleOf(listOf(player), Lang.settings_player, read = { false }, write = { emptyList() })
            }
        }
    }.single().items!!

    private fun values(vararg pairs: Pair<TvSettingKey<*>, Any>) = TvSettingsValues(mapOf(*pairs))

    @Test
    fun `items inside show only when the condition holds`() {
        val (plain, gated) = items
        assertTrue(gated.visible(values(player to 1, touchPage to true)))
        assertFalse(gated.visible(values(player to 1, touchPage to false)))
        assertTrue(plain.visible(values(player to 1, touchPage to false)))
    }

    @Test
    fun `items inside wait for the condition keys and keep their own`() {
        val (_, gated) = items
        assertFalse(gated.visible(values(player to 1)))
        assertFalse(gated.visible(values(touchPage to true)))
        assertEquals(listOf(player, touchPage), gated.keys)
    }
}
