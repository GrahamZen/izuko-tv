/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * 关掉 Compose 在 iOS 上自带的边缘滑动返回 (从屏幕左边缘往右滑 = 返回): iPad 上的电视界面用左上角的返回按钮回上一页
 * (见 TvTouchBackButton). 边缘手势经 Compose 的返回事件分发器交给导航与各处 BackHandler, 这里把根分发器停用, 整棵树都不再收它.
 * 应用自己的返回按钮走 AniNavigator 与 SkikoOnBackPressedDispatcherOwner, 不经过这个分发器, 照常用.
 */
@Composable
internal fun DisableEdgeSwipeBack() {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return
    DisposableEffect(dispatcher) {
        dispatcher.isEnabled = false
        onDispose { dispatcher.isEnabled = true }
    }
}
