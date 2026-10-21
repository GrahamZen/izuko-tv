/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.dialogs

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogWindowProvider
import java.util.function.Consumer

@Composable
actual fun dialogWindowBlurBehind(radius: Dp): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blurBehind(radius) else false

@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun blurBehind(radius: Dp): Boolean {
    val view = LocalView.current
    // 同 DialogWindowDimAmount: 沿视图链往上找弹窗窗口, 找不到就放弃
    val window = remember(view) {
        var node: View? = view
        while (node != null) {
            (node as? DialogWindowProvider)?.let { return@remember it.window }
            node = node.parent as? View
        }
        null
    } ?: return false
    val windowManager = remember(view) { view.context.getSystemService(WindowManager::class.java) }
    // 跨窗口模糊会被系统随时关开 (省电模式、开发者选项、隧道播放): 跟着它变
    var enabled by remember(windowManager) { mutableStateOf(windowManager.isCrossWindowBlurEnabled) }
    DisposableEffect(windowManager) {
        val listener = Consumer<Boolean> { enabled = it }
        windowManager.addCrossWindowBlurEnabledListener(listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    val radiusPx = with(LocalDensity.current) { radius.roundToPx() }
    SideEffect {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.also { it.blurBehindRadius = radiusPx }
    }
    return enabled
}
