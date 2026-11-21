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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.pip.LocalPictureInPictureController
import me.him188.ani.app.pip.PictureInPictureController
import me.him188.ani.app.pip.shouldAutoEnterPictureInPicture
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.utils.platform.currentPlatform
import me.him188.ani.utils.platform.isAndroid

/**
 * 平板模式的系统小窗 (同上游播放器 EpisodePage 的小窗策略): 设置「切换到其他应用时」选了小窗、且正在播时, 回桌面自动进小窗;
 * 视频底下那一排全屏左边的小窗按钮也能手动进.
 *
 * 安卓的小窗是整个应用窗口缩进去: 这时 [occupiesWindow] 为真, 骨架按全屏摆 (见 TabletPlayerFrame)、画面上的控件不画.
 * iOS 的系统小窗只接走视频图层, 应用界面原样不动, 骨架不跟着变 (否则回到应用时先是全屏、等小窗收起才变回分栏).
 *
 * 回桌面会进小窗、或已经在小窗里时, 告诉会话宿主应用退到后台别按暂停 ([EpisodeViewModel.setAutoPictureInPictureArmed]):
 * iOS 进小窗时先报退到后台, 不登记的话保留会话当场把小窗按停, 小窗里再点播放也会被立刻按回去.
 * 安卓进小窗只派发 ON_PAUSE, 应用仍算在前台; 关掉小窗是 ON_STOP, 照旧暂停.
 *
 * 控制器用播放页外层 (EpisodeScreen) 提供的那一个 ([LocalPictureInPictureController]): 一个 Activity 只能有一份小窗参数,
 * 再建一个的话两边各自在播放状态变化时写参数, 谁后写算谁的.
 */
@Stable
internal class TabletPictureInPicture(
    private val controller: PictureInPictureController,
    private val active: State<Boolean>,
) {
    val isSupported: Boolean get() = controller.isSupported

    /** 此刻应用窗口就是小窗 (只有安卓会这样, 见类说明). */
    val occupiesWindow: Boolean get() = active.value && WINDOW_SHRINKS_INTO_PICTURE_IN_PICTURE

    fun enter() {
        if (controller.isPictureInPicturePossible.value) controller.enterPictureInPicture()
    }
}

@Composable
internal fun rememberTabletPictureInPicture(vm: EpisodeViewModel): TabletPictureInPicture {
    val controller = LocalPictureInPictureController.current
    val active = controller.isInPictureInPicture.collectAsStateWithLifecycle()
    if (controller.isSupported) {
        val backgroundBehavior = vm.videoScaffoldConfig.backgroundBehavior
        val playerState by vm.player.state.collectAsStateWithLifecycle()
        val mediaProperties by vm.player.mediaProperties.collectAsStateWithLifecycle(null)
        val autoEnter = shouldAutoEnterPictureInPicture(backgroundBehavior, playerState.playWhenReady)
        // 播放状态 / 设置 / 画面比例一变就刷新 (系统要求; 比例不对小窗会变形)
        LaunchedEffect(
            controller,
            autoEnter,
            mediaProperties?.videoWidth,
            mediaProperties?.videoHeight,
        ) {
            controller.updatePolicy(
                autoEnterEnabled = autoEnter,
                aspectWidth = mediaProperties?.videoWidth,
                aspectHeight = mediaProperties?.videoHeight,
            )
        }
        DisposableEffect(controller) {
            onDispose { controller.updatePolicy(autoEnterEnabled = false, aspectWidth = null, aspectHeight = null) }
        }
        val armOwner = remember { Any() }
        val armed = autoEnter || active.value
        DisposableEffect(vm, armed) {
            vm.setAutoPictureInPictureArmed(armOwner, armed)
            onDispose { vm.setAutoPictureInPictureArmed(armOwner, false) }
        }
    }
    return remember(controller, active) { TabletPictureInPicture(controller, active) }
}

/** 进小窗时整个应用窗口缩进去 (安卓); iOS 的系统小窗只放视频图层. */
private val WINDOW_SHRINKS_INTO_PICTURE_IN_PICTURE = currentPlatform().isAndroid()
