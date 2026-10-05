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
import me.him188.ani.app.ui.lang.settings_storage_delete_cache_when_done
import me.him188.ani.app.ui.lang.settings_storage_delete_cache_when_done_description
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SwitchItem
import org.jetbrains.compose.resources.stringResource

/** 「标记看过后删除缓存」开关 (见 MediaCacheSettings.deleteWhenMarkedDone / DeleteCacheWhenMarkedDoneUseCase), 默认关. */
@Composable
fun SettingsScope.DeleteCacheWhenMarkedDoneItem(state: CacheDirectoryGroupState) {
    val mediaCacheSettings by state.mediaCacheSettingsState
    val tasker = rememberAsyncHandler()
    SwitchItem(
        checked = mediaCacheSettings.deleteWhenMarkedDone,
        onCheckedChange = { checked ->
            tasker.launch {
                state.mediaCacheSettingsState.updateSuspended(mediaCacheSettings.copy(deleteWhenMarkedDone = checked))
            }
        },
        title = { Text(stringResource(Lang.settings_storage_delete_cache_when_done)) },
        description = { Text(stringResource(Lang.settings_storage_delete_cache_when_done_description)) },
    )
}
