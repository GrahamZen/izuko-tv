/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** 桌面 / iOS 的弹窗与页面画在同一块画布上, 没有窗口级的背后模糊. */
@Composable
actual fun dialogWindowBlurBehind(radius: Dp): Boolean = false
