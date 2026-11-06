/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.progress

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.him188.ani.app.ui.foundation.widgets.TvMenuOption
import me.him188.ani.app.ui.foundation.widgets.TvNativeMenu
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_audio_track_unplayable
import me.him188.ani.app.ui.lang.video_player_audio_track_unplayable_format
import org.jetbrains.compose.resources.stringResource

/**
 * [PlayerControllerDefaults.OptionsSwitcher] 的下拉在 TV 主窗口里画成原生菜单 (见 [TvNativeMenu]: 字幕上百条也是一打开就有, 不用等逐项组合);
 * 没给 [optionLabel] 或交不了 (手机 / 桌面 / 弹窗里) 时什么都不画, 返回 false, 调用方照旧画下拉.
 * 选项在打开时取一次, 同下拉. 当前值打勾; 打开时高亮第一项, 同下拉.
 */
@Composable
internal fun <T> OptionsSwitcherNativeMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    value: T,
    optionsProvider: () -> List<T>,
    optionLabel: (@Composable (T) -> TvMenuOption)?,
    onSelect: (T) -> Unit,
): Boolean {
    if (optionLabel == null) return false
    val shown = remember { ShownOptions<T>() }
    return TvNativeMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        options = {
            val options = remember(optionsProvider) { optionsProvider() }
            shown.list = options
            options.map { optionLabel(it).copy(selected = it == value) }
        },
        // 选项本身可以是 null (字幕的「关闭」), 按下标判断, 不能用 getOrNull
        onSelect = { index -> if (index in shown.list.indices) onSelect(shown.list[index]) },
    )
}

private class ShownOptions<T> {
    var list: List<T> = emptyList()
}

/** 音轨菜单里的一项, 同 [UnplayableAwareAudioName]: 设备放不了的注明格式并压暗. */
@Composable
internal fun unplayableAwareAudioOption(name: String, unplayableFormat: String?): TvMenuOption = when {
    unplayableFormat == null -> TvMenuOption(name)
    unplayableFormat.isEmpty() -> TvMenuOption(stringResource(Lang.video_player_audio_track_unplayable, name), dimmed = true)
    else -> TvMenuOption(stringResource(Lang.video_player_audio_track_unplayable_format, name, unplayableFormat), dimmed = true)
}
