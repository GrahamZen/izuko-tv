/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 登录页右侧栏的插槽: TV 上放手机控制台的二维码 (电视上用遥控器输账号密码很累, 扫码在手机上登录更顺手).
 * 实现在 `ui-tv`, 由 TV 应用入口装上; 为 null 就没有右侧栏.
 */
val LocalTvLoginSidePanel = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
