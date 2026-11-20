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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings

/**
 * 平板模式下铺满全屏的对话框 (窗口铺到系统栏下面的那种, 如首次引导的登录层) 里, 系统栏照外壳的样子 (见 ImmersiveShellSystemBarsEffect):
 * 状态栏藏起, 导航栏按设置「藏起导航栏」, 藏起的栏从屏幕边缘滑一下临时叫出来.
 * 对话框是独立窗口, 不继承 Activity 的系统栏设置, 不设的话它拿到焦点时状态栏会露出来. 在对话框的内容里调; 窗口随对话框销毁, 不用还原.
 */
@Composable
internal fun TvTouchDialogSystemBars() {
    val view = LocalView.current
    val hideNavigationBar = LocalThemeSettings.current.tvHideNavigationBar
    DisposableEffect(view, hideNavigationBar) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
            if (hideNavigationBar) {
                controller.hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                controller.show(WindowInsetsCompat.Type.navigationBars())
            }
        }
        onDispose {}
    }
}
