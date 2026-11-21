/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// iPad 上只用平板的选源面板 (TabletSourcePanel), 遥控器的原生面板不出现
@Composable
internal actual fun TvSourcePanelNativeView(
    state: TvSourcePanelState,
    controller: TvSourcePanelController,
    leaving: Boolean,
    resume: TvSourceResumePoint?,
    onClose: () -> Unit,
    modifier: Modifier,
) {
}

@Composable
internal actual fun PrewarmTvSourcePanelView(canPrewarmNow: () -> Boolean) {
}
