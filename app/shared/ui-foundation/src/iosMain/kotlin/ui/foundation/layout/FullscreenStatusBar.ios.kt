/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import me.him188.ani.app.platform.SwiftBridge

@Composable
actual fun FullscreenStatusBarFollowsControlsEffect(controlsShown: () -> Boolean) {
    val latest by rememberUpdatedState(controlsShown)
    LaunchedEffect(Unit) {
        snapshotFlow { latest() }.collect { IosSystemBars.setControlsShown(it) }
    }
    DisposableEffect(Unit) {
        onDispose { IosSystemBars.setControlsShown(false) }
    }
}

/**
 * iOS 上全屏播放时状态栏与主屏指示条的显隐. 全屏时 ([setSystemBarVisible] 传 `false`) 主屏指示条自动隐去,
 * 状态栏只在播放器控件收起时藏 (见 [FullscreenStatusBarFollowsControlsEffect]).
 *
 * 应用最外层是 SwiftUI 的窗口, 状态栏与主屏指示条只认它的修饰符, 不问里面控制器的 `prefersStatusBarHidden`,
 * 所以算好的结果经 `ISwiftBridge.setSystemBarsHidden` 交给 SwiftUI 那层 (ContentView.swift).
 */
internal object IosSystemBars {
    private var fullscreen = false
    private var controlsShown = false

    fun setFullscreen(fullscreen: Boolean) {
        this.fullscreen = fullscreen
        apply()
    }

    fun setControlsShown(shown: Boolean) {
        controlsShown = shown
        apply()
    }

    private fun apply() {
        SwiftBridge.setSystemBarsHidden(statusBar = fullscreen && !controlsShown, homeIndicator = fullscreen)
    }
}
