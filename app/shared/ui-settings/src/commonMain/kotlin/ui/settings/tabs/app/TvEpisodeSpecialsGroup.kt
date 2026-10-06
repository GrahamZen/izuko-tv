/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.models.preference.TvEpisodeSpecialsPlacement
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_theme_tv_episode_specials
import me.him188.ani.app.ui.lang.settings_theme_tv_episode_specials_after_main
import me.him188.ani.app.ui.lang.settings_theme_tv_episode_specials_by_number
import me.him188.ani.app.ui.lang.settings_theme_tv_episode_specials_description
import me.him188.ani.app.ui.lang.settings_theme_tv_episode_specials_hidden
import me.him188.ani.app.ui.lang.settings_theme_tv_episodes
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.app.ui.settings.framework.components.DropdownItem
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import org.jetbrains.compose.resources.stringResource

/**
 * 设置 - 界面 -「选集」: TV 选集里特别篇怎么放 (见 [ThemeSettings.tvEpisodeSpecials]).
 * 只有沉浸式外壳 (遥控器形态) 的详情页与播放器读它, 其余形态不显示.
 */
@Composable
internal fun SettingsScope.TvEpisodeSpecialsGroup(themeSettings: SettingsState<ThemeSettings>) {
    if (!LocalAniUiBehavior.current.immersiveShell) return
    val themeConfig by themeSettings
    Group(title = { Text(stringResource(Lang.settings_theme_tv_episodes)) }, useThinHeader = true) {
        DropdownItem(
            selected = { themeConfig.tvEpisodeSpecials },
            values = { TvEpisodeSpecialsPlacement.entries },
            itemText = {
                Text(
                    stringResource(
                        when (it) {
                            TvEpisodeSpecialsPlacement.Hidden -> Lang.settings_theme_tv_episode_specials_hidden
                            TvEpisodeSpecialsPlacement.AfterMain -> Lang.settings_theme_tv_episode_specials_after_main
                            TvEpisodeSpecialsPlacement.ByNumber -> Lang.settings_theme_tv_episode_specials_by_number
                        },
                    ),
                )
            },
            onSelect = { themeSettings.update(themeConfig.copy(tvEpisodeSpecials = it)) },
            title = { Text(stringResource(Lang.settings_theme_tv_episode_specials)) },
            description = { Text(stringResource(Lang.settings_theme_tv_episode_specials_description)) },
        )
    }
}
