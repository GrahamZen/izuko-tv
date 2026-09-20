/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.network

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_network_tmdb_images_disable
import me.him188.ani.app.ui.lang.settings_network_tmdb_images_disable_description
import me.him188.ani.app.ui.lang.settings_network_tmdb_images_group
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SwitchItem
import org.jetbrains.compose.resources.stringResource

/**
 * 「不加载 TMDB 背景图」: 给接口或图床在自己网络下连不上的用户 (典型是移动宽带下图床握手即被重置).
 * 打开后背景图与剧照直接改用条目封面, 不再等兜底计时, 也不再发请求. 摆在代理页、紧挨两项 TMDB
 * 连通性测试之后: 用户正是在那里看到红叉的.
 */
@Composable
fun SettingsScope.TmdbImagesGroup(
    state: SettingsState<Boolean>,
    modifier: Modifier = Modifier,
) {
    val disabled by state
    Group(
        title = { Text(stringResource(Lang.settings_network_tmdb_images_group)) },
        modifier = modifier,
        useThinHeader = true,
    ) {
        SwitchItem(
            checked = disabled,
            onCheckedChange = { state.update(it) },
            title = { Text(stringResource(Lang.settings_network_tmdb_images_disable)) },
            description = { Text(stringResource(Lang.settings_network_tmdb_images_disable_description)) },
        )
    }
}
