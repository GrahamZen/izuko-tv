/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 播放/暂停键全集. 比 [TV_PLAY_KEYS] 多一个 [Key.MediaPause] —— 那边是"能长按的播放键"
 * (长按=回到正在播放), 只暂停的键不该被长按改写; 这边是"按下就切换播放状态"的键, 三个一视同仁.
 */
val TV_PLAY_PAUSE_KEYS = setOf(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause)

/**
 * 遥控器播放/暂停键的处理器: 播放页在自己整棵组合上提供 (值 = 切换播放/暂停), 别处为 null.
 *
 * 与下面的桥接是同一个道理: CompositionLocal 能跨窗口边界 (Dialog 的内容是父组合的子组合),
 * 正好用来把主窗口的处理能力递进独立窗口里.
 */
val LocalTvPlayPauseHandler = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * **独立窗口 (Dialog / Popup / DropdownMenu) 的遥控器全局键桥**: 挂在弹层内容根上, 把本窗口
 * 收到的全局键接回主窗口的处理者.
 *
 * ## 为什么需要它
 *
 * Android 上这三种弹层各是一个独立窗口, 按键送往它自己, **到不了主窗口**: 播放页根部那条唯一
 * 按键路由、应用根部的长按跟踪器 ([TvKeyLongPressHost]) 全都收不到. 于是弹层一开, 那些"在哪儿
 * 都该管用"的键就集体失灵 —— 画面还在弹层后面放着, 播放/暂停按不动; 长按返回本该一步收干净,
 * 却什么也不发生. 弹层自己又不认识这些键, 事件就此消失.
 *
 * ## 三件事
 *
 * 1. **播放/暂停键**: 就地切换播放状态 (读 [LocalTvPlayPauseHandler]). 不碰焦点也不碰控制层 ——
 *    弹层各有各的焦点归属与关闭后的落点, 抢了就乱.
 * 2. **全局长按 (返回 / 播放)**: 转发给根部跟踪器. **短按一律不消费**, 弹层自己的返回照旧只关
 *    一层; 只有长按被跟踪器认领时才消费.
 * 3. **认领的那一刻顺手关掉本窗口** ([onDismissRequest]): 长按的语义是"一步到位", 留着这层弹窗
 *    会挡在刚收干净的画面 (或刚弹出的快捷菜单) 前面. 一次手势只关一次 —— 认领之后剩下的连发与
 *    抬起还会进来 (窗口关掉之前), 不设标志会重复调用.
 *
 * 播放页之外 / 非 TV 形态下三个 local 都为 null, 整个修饰符退化成空操作, 同一个弹层组件在别的
 * 页面零副作用.
 *
 * @param onDismissRequest 关掉本弹层; 传 null 则长按只做全局动作、本窗口留着 (仅用于本身
 *   就是全局菜单的弹层)
 */
@Composable
fun Modifier.tvOverlayWindowKeys(onDismissRequest: (() -> Unit)? = null): Modifier {
    val playPause = LocalTvPlayPauseHandler.current
    val backHost = LocalTvBackLongPressHost.current
    val playHost = LocalTvPlayLongPressHost.current
    if (playPause == null && backHost == null && playHost == null) return this
    val dismiss by rememberUpdatedState(onDismissRequest)
    // 普通 var 而不是 snapshot 状态: 按键回调里写 snapshot 会让每一发连发都触发一轮失效
    val gesture = remember { TvOverlayGestureState() }
    return onPreviewKeyEvent { event ->
        // 长按跟踪器先问 (键集不重叠, 谁认得给谁). 它对"全新按下"一律返回 false, 短按语义不受影响
        val claimed = backHost?.onRootKeyEvent(event) == true || playHost?.onRootKeyEvent(event) == true
        when {
            event.type == KeyEventType.KeyUp -> gesture.dismissed = false
            claimed && !gesture.dismissed -> {
                gesture.dismissed = true
                dismiss?.invoke()
            }
        }
        if (!claimed && playPause != null && event.key in TV_PLAY_PAUSE_KEYS) {
            if (event.type == KeyEventType.KeyDown) playPause()
            return@onPreviewKeyEvent true
        }
        claimed
    }
}

/** [tvOverlayWindowKeys] 的每手势簿记: 本次长按是否已经把窗口关过一次. */
private class TvOverlayGestureState {
    var dismissed = false
}
