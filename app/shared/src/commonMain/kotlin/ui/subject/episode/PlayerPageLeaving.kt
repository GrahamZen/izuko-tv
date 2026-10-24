/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import me.him188.ani.app.domain.episode.ipadDiag
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * 播放页正在退场 (导航的出场动画里: 返回离开, 或被新页面盖住). 视频画在独立的系统图层上 (Android 的 SurfaceView, iOS 的 UIKit 视图),
 * 不跟着页面的透明度淡出 —— 页面其余部分淡没了, 视频那一块还不透明地盖着, 动画放完才一下子消失, 看着像残影.
 * 所以退场一开始就把视频撤掉 (见 EpisodeVideo), 和页面其余部分一起离开. 读的是状态, 在组合里读.
 */
val LocalPlayerPageLeaving = compositionLocalOf<() -> Boolean> { { false } }

/** 播放页的导航条目里调: 按这一条目的进出场动画提供 [LocalPlayerPageLeaving]. */
@Composable
fun ProvidePlayerPageLeaving(content: @Composable () -> Unit) {
    val transition = LocalNavAnimatedContentScope.current.transition
    LaunchedEffect(transition) {
        snapshotFlow { "${transition.currentState} -> ${transition.targetState}" }.collect { ipadDiag("player page transition: $it") }
    }
    CompositionLocalProvider(LocalPlayerPageLeaving provides { transition.targetState == EnterExitState.PostExit }, content = content)
}
