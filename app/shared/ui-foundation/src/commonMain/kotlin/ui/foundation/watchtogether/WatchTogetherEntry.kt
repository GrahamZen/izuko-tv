/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.watchtogether

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * "一起看" 弹窗的开合把手.
 *
 * 弹窗本体挂在应用根部 (WatchTogetherOverlayHost, 与 NavHost 同级), 唯一持有
 * WatchTogetherViewModel; 而入口按钮散落在各页面里 (TV: 长按返回的动作面板 + 播放器胶囊行最右).
 * 入口只经本把手开弹窗与判断该不该显示, **绝不自己 `viewModel<WatchTogetherViewModel>()`** ——
 * 那些页面在不同的 ViewModelStoreOwner 下, 会各自造出一个新实例, 变成多份轮询与多份房间会话.
 *
 * 由 AniAppContent 建好后 provide 给整棵树 (NavHost 与弹窗宿主都在里面), 见
 * [LocalWatchTogetherEntry].
 *
 * **是 ViewModel 而不是 `remember` 出来的普通对象**: 遥控器形态的动作面板组合在
 * `InstallFormFactorUi` 里, 那是 `AniAppContent` 的**外面** —— 它读 [LocalWatchTogetherEntry]
 * 只会拿到默认空实例 (`enabled` 永远 false). 挂成 Activity 级 ViewModel 之后, 两处
 * `viewModel { WatchTogetherEntryState() }` (同 owner 同 key) 拿到的是同一个实例, 与
 * RetainedPlaybackSessionHolder 同一个套路.
 */
@Stable
class WatchTogetherEntryState : ViewModel() {
    /**
     * 功能是否已在设置里打开. 由弹窗宿主写入, 入口按钮据此决定显不显示 ——
     * 关着的时候整个入口不存在 (等同于原来那颗悬浮气泡不出现).
     */
    var enabled: Boolean by mutableStateOf(false)

    /** 弹窗是否展开. 返回键即收起 (由弹窗自身的 onDismissRequest 落到这里). */
    var dialogVisible: Boolean by mutableStateOf(false)
        private set

    /**
     * 本次打开是否压在深色背景上 (播放器画面). 由入口按钮告知 —— 弹窗宿主挂在应用根部,
     * 在播放器那层强制深色的 `AniTheme` 之外, 不跟着强制的话浅色主题下会在画面上弹出一块白板.
     *
     * 用入口自报而不是查"当前是否在播放器": 后者要等本地播放状态上报, 刚进播放器还没起播的
     * 那几秒是 false, 正好是用户最可能开面板的时候.
     */
    var dialogOverDarkBackground: Boolean by mutableStateOf(false)
        private set

    fun open(overDarkBackground: Boolean = false) {
        dialogOverDarkBackground = overDarkBackground
        dialogVisible = true
    }

    fun close() {
        dialogVisible = false
    }
}

/**
 * 默认给一个永远 `enabled = false` 的空实例: 预览与测试里没有弹窗宿主, 入口按钮
 * 自然不渲染, 不必到处判空.
 */
val LocalWatchTogetherEntry = compositionLocalOf { WatchTogetherEntryState() }
