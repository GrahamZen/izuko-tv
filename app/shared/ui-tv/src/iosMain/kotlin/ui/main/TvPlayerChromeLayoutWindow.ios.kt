/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem
import me.him188.ani.app.data.models.preference.TvPlayerChromePresets

/** iOS 上播放器是上游的, 没有这一页: 万一被打开就当场关掉. */
@Composable
internal actual fun TvPlayerChromeLayoutWindow(
    onNavigateBack: () -> Unit,
    initialFocus: TvPlayerChromeItem?,
    onCommit: (TvPlayerChromePresets) -> Unit,
) {
    LaunchedEffect(Unit) { onNavigateBack() }
}
