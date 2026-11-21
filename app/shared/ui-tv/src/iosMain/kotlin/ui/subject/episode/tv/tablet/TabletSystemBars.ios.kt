/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.ui.foundation.layout.FullscreenStatusBarFollowsControlsEffect
import me.him188.ani.app.ui.foundation.layout.LocalPlatformWindow
import me.him188.ani.app.ui.foundation.layout.setSystemBarVisible

/** 同上游播放页 (EpisodePage 按全屏设系统栏, EpisodeVideo 让状态栏跟着控件), 全屏状态取平板骨架自己的. */
@Composable
internal actual fun TabletSystemBarsEffect(fullScreen: Boolean, controlsShown: () -> Boolean) {
    val context by rememberUpdatedState(LocalContext.current)
    val window = LocalPlatformWindow.current
    LaunchedEffect(window, fullScreen) {
        context.setSystemBarVisible(window, !fullScreen)
    }
    DisposableEffect(window) {
        onDispose { context.setSystemBarVisible(window, true) }
    }
    FullscreenStatusBarFollowsControlsEffect(controlsShown)
}
