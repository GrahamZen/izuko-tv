/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.layout

import me.him188.ani.app.platform.Context

/**
 * 分别指定状态栏 (顶部电量 / Wi-Fi / 通知那条) 与导航栏显不显示. 被藏起来的栏从屏幕边缘滑一下能临时叫出来, 过几秒自己藏回去.
 *
 * 与 [setSystemBarVisible] (两条一起) 分开, 是因为沉浸式外壳 (TV 形态) 在平板 / 手机上要的是「状态栏恒隐、导航栏看设置」:
 * 那种形态界面内没有返回按钮, 导航栏是返回的途径. 只在有触摸屏的设备上调 (见 `ImmersiveShellSystemBars.kt`).
 */
expect fun Context.setSystemBarsVisible(window: PlatformWindowMP, statusBar: Boolean, navigationBar: Boolean)
