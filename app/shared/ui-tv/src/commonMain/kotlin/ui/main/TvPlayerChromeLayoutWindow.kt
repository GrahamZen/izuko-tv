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
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem
import me.him188.ani.app.data.models.preference.TvPlayerChromePresets

/**
 * 调整窗口里的「播放器按钮布局」页 (长按电视版播放器的按钮打开, 见安卓的 TvPlayerChromeLayoutPage). 没有电视版播放器的端不会打开它.
 */
@Composable
internal expect fun TvPlayerChromeLayoutWindow(
    onNavigateBack: () -> Unit,
    initialFocus: TvPlayerChromeItem?,
    onCommit: (TvPlayerChromePresets) -> Unit,
)
