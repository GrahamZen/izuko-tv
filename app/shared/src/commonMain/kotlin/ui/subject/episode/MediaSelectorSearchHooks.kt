/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.media_selector_full_search
import me.him188.ani.app.ui.lang.media_selector_full_search_description
import org.jetbrains.compose.resources.stringResource

/**
 * 选源界面在组合里期间算「选源面板开着」: 进来时放开开播后暂停的数据源, 走时 (正在播又没开完整搜索) 再暂停,
 * 见 `EpisodeViewModel.onMediaSelectorShown` / `onMediaSelectorHidden`. 共享版播放页的每个选源界面 (播放器侧边栏、
 * 侧边栏太矮时的容器、详情页签里的侧边栏 / 弹窗) 各在自己的内容里放一个.
 */
@Composable
fun MediaSelectorShownEffect(onShown: () -> Unit, onHidden: () -> Unit) {
    val shown by rememberUpdatedState(onShown)
    val hidden by rememberUpdatedState(onHidden)
    DisposableEffect(Unit) {
        shown()
        onDispose { hidden() }
    }
}

/**
 * 选源列表最上面的「完整搜索」开关一行: 开着时这一集开播后也接着把全部数据源搜完. 不放在标题行 —— 侧边栏只有 300dp 上下,
 * 标题、模式切换、关闭按钮已经占满. [fullSearch] 为 null (设置里开了始终完整搜索) 时不画.
 */
@Composable
fun MediaSelectorFullSearchRow(
    fullSearch: Boolean?,
    onFullSearchChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (fullSearch == null) return
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = fullSearch, onValueChange = onFullSearchChange, role = Role.Switch)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Lang.media_selector_full_search), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(Lang.media_selector_full_search_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = fullSearch, onCheckedChange = null)
    }
}
