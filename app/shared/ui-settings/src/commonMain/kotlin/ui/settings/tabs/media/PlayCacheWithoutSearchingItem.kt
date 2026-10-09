/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import me.him188.ani.app.ui.foundation.rememberAsyncHandler
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_storage_play_cache_without_searching
import me.him188.ani.app.ui.lang.settings_storage_play_cache_without_searching_description
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SwitchItem
import org.jetbrains.compose.resources.stringResource

/** 「有缓存时直接播, 不搜索」开关 (见 MediaCacheSettings.playCacheWithoutSearching / PlayCacheWithoutSearchingExtension), 默认关. */
@Composable
fun SettingsScope.PlayCacheWithoutSearchingItem(state: CacheDirectoryGroupState) {
    val mediaCacheSettings by state.mediaCacheSettingsState
    val tasker = rememberAsyncHandler()
    SwitchItem(
        checked = mediaCacheSettings.playCacheWithoutSearching,
        onCheckedChange = { checked ->
            tasker.launch {
                state.mediaCacheSettingsState.updateSuspended(mediaCacheSettings.copy(playCacheWithoutSearching = checked))
            }
        },
        title = { Text(stringResource(Lang.settings_storage_play_cache_without_searching)) },
        description = { Text(stringResource(Lang.settings_storage_play_cache_without_searching_description)) },
    )
}
