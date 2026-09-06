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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

/**
 * 控件组合出来之后的头 [TV_FOCUS_LANDING_FRAMES] 帧为 true: 进页 / 返回本页 (页面重建) 的落点就在这几帧里送到. 聚焦效果的过渡在这段里
 * 当场到位, 不先按未聚焦画出来再动画一遍 (原生卡片对应的是按住聚焦态, 见 TvNativeCardView.setFocusLookHeld); 之后按键挪过来的照常动画.
 *
 * 只给页面上常驻的控件用 (标签栏、主按钮): 懒加载列表里的项跟着焦点移动现组合出来, 用户按键挪过去的那一下也落在它的头几帧里.
 */
@Composable
fun rememberTvFocusLandingWindow(): State<Boolean> {
    val landing = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        repeat(TV_FOCUS_LANDING_FRAMES) { withFrameNanos { } }
        landing.value = false
    }
    return landing
}

private const val TV_FOCUS_LANDING_FRAMES = 10
