/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.layout

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import me.him188.ani.app.platform.Context

actual fun Context.setSystemBarsVisible(window: PlatformWindowMP, statusBar: Boolean, navigationBar: Boolean) {
    if (this !is Activity) return

    val insetsController = WindowCompat.getInsetsController(this.window, this.window.decorView)
    val statusBars = WindowInsetsCompat.Type.statusBars()
    val navigationBars = WindowInsetsCompat.Type.navigationBars()
    if (statusBar) insetsController.show(statusBars) else insetsController.hide(statusBars)
    if (navigationBar) insetsController.show(navigationBars) else insetsController.hide(navigationBars)
    // 只影响被藏起来的那条栏, 同时显示着的不受它影响
    insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    // 同 setSystemBarVisible: Android 10 及以前藏状态栏还要窗口的全屏标记
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        val fullscreenFlag = WindowManager.LayoutParams.FLAG_FULLSCREEN
        if (statusBar) this.window.clearFlags(fullscreenFlag) else this.window.addFlags(fullscreenFlag)
    }
}
