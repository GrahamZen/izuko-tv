/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.layout.LocalPlatformWindow
import me.him188.ani.app.ui.foundation.layout.setSystemBarsVisible
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled

/*
 * 沉浸式外壳 (TV 形态) 在触屏设备上的系统栏. 发布的安装包只有 TV 变体, 平板 / 手机装的也是它. 只在有触摸屏时做
 * ([LocalTvTouchInputEnabled]): 电视上一行都不跑 —— 藏状态栏在 Android 10 及以前要给窗口加全屏标记, 那会让输入法不再顶起窗口.
 *
 * - 外壳: 状态栏恒藏 (背景是整屏 hero 图, 压在上面多余); 导航栏默认留着 (界面内没有返回按钮), 设置「藏起导航栏」打开时也藏.
 * - 播放页: 两条都藏, 离开时回到外壳的样子.
 * 被藏的栏从屏幕边缘滑一下能临时叫出来, 过几秒自己藏回去.
 */

/** 外壳的系统栏, 挂在根部. 不是沉浸式外壳或没有触摸屏时什么都不做. */
@Composable
fun ImmersiveShellSystemBarsEffect() {
    if (!LocalAniUiBehavior.current.immersiveShell || !LocalTvTouchInputEnabled.current) return
    val context = LocalContext.current
    val window = LocalPlatformWindow.current
    val hideNavigationBar = LocalThemeSettings.current.tvHideNavigationBar
    LaunchedEffect(context, window, hideNavigationBar) {
        context.setSystemBarsVisible(window, statusBar = false, navigationBar = !hideNavigationBar)
    }
}

/**
 * 播放页在沉浸式外壳下要不要把两条系统栏都藏起来; 为 `true` 时离开播放页会恢复外壳的样子.
 *
 * 调用方在按全屏状态设系统栏的那个 effect 里、设完之后藏: 上游按 `isFullscreen` 设系统栏, 而 TV 形态的播放页恒全屏,
 * `isFullscreen` 却从 `false` 起步, 不压回去两条栏就一直显示着. 放进同一个 effect 是为了先后确定.
 */
@Composable
fun rememberImmersiveShellPlayerHidesSystemBars(): Boolean {
    if (!LocalAniUiBehavior.current.immersiveShell || !LocalTvTouchInputEnabled.current) return false
    val context = LocalContext.current
    val window = LocalPlatformWindow.current
    val hideNavigationBar by rememberUpdatedState(LocalThemeSettings.current.tvHideNavigationBar)
    DisposableEffect(context, window) {
        onDispose {
            context.setSystemBarsVisible(window, statusBar = false, navigationBar = !hideNavigationBar)
        }
    }
    return true
}
