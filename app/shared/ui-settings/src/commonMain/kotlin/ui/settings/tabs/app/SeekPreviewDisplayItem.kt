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
import me.him188.ani.app.data.models.preference.SeekPreviewDisplay
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_player_seek_preview_display
import me.him188.ani.app.ui.lang.settings_player_seek_preview_display_description
import me.him188.ani.app.ui.lang.settings_player_seek_preview_display_full_screen
import me.him188.ani.app.ui.lang.settings_player_seek_preview_display_window
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.app.ui.settings.framework.components.DropdownItem
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import org.jetbrains.compose.resources.stringResource

/**
 * 电视上拖动预览的画面画在哪 (小画面 / 全屏, 见 [SeekPreviewDisplay]), 带一条分隔线. 拖动预览后解码出错时会被自动改成全屏.
 */
@Composable
internal fun SettingsScope.SeekPreviewDisplayItem(
    config: VideoScaffoldConfig,
    videoScaffoldConfig: SettingsState<VideoScaffoldConfig>,
) {
    HorizontalDividerItem()
    DropdownItem(
        selected = { config.seekPreviewDisplay },
        values = { SeekPreviewDisplay.entries },
        itemText = {
            Text(
                stringResource(
                    when (it) {
                        SeekPreviewDisplay.WINDOW -> Lang.settings_player_seek_preview_display_window
                        SeekPreviewDisplay.FULL_SCREEN -> Lang.settings_player_seek_preview_display_full_screen
                    },
                ),
            )
        },
        onSelect = { videoScaffoldConfig.update(config.copy(seekPreviewDisplay = it)) },
        title = { Text(stringResource(Lang.settings_player_seek_preview_display)) },
        description = { Text(stringResource(Lang.settings_player_seek_preview_display_description)) },
    )
}
