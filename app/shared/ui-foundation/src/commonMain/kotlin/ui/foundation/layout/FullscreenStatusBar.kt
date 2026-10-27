/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import me.him188.ani.app.ui.foundation.LocalPlatform
import me.him188.ani.utils.platform.isIos

/**
 * 全屏播放时状态栏跟着播放器控件走: 控件唤出时显示, 收起时藏. 只 iOS 上做: iPad 横屏时状态栏一直在, 一直显示会压住顶栏,
 * 一直藏又看不了时间电量. 安卓全屏时两条系统栏一直藏着, 从屏幕边缘滑一下临时叫出来.
 *
 * @param controlsShown 播放器顶栏此刻显不显示 (手势锁住时算不显示)
 */
@Composable
expect fun FullscreenStatusBarFollowsControlsEffect(controlsShown: () -> Boolean)

/**
 * 全屏播放时控件顶部给状态栏留的高度: 状态栏唤出时的高度, 它藏起来时照留, 顶栏与弹幕不跟着状态栏上下跳.
 * 窗口尺寸变了 (转屏) 重新量; 横屏不显示状态栏的设备 (iPhone) 上是 0.
 */
@Composable
fun rememberFullscreenStatusBarReserve(): WindowInsets = WindowInsets(top = rememberStatusBarReservePx())

/**
 * 播放器控件唤出时状态栏也在 (iPad: 全屏时跟着控件出来, 不全屏时一直在). 这时状态栏里就有时间, 顶栏不用再画时钟.
 */
@Composable
fun statusBarShownWithPlayerControls(): Boolean = LocalPlatform.current.isIos() && rememberStatusBarReservePx() > 0

@Composable
private fun rememberStatusBarReservePx(): Int {
    val top = WindowInsets.statusBars.getTop(LocalDensity.current)
    val held = remember(LocalWindowInfo.current.containerSize) { intArrayOf(0) }
    held[0] = maxOf(held[0], top)
    return held[0]
}
