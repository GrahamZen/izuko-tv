/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager

/*
 * 触屏设备上跑 TV 形态 (平板装了 TV 包) 的触摸适配: 把触摸翻译成 TV 界面已经听得懂的「焦点 + 按键」词汇,
 * 状态机与按键路由一行不动.
 *
 * ## 为什么平板上"点了没反应"
 *
 * TV 界面几乎所有语义都靠焦点驱动 (胶囊聚焦才浮面板 / 侧边栏 hasFocus 才展开 / 卡片 onFocused 才换 hero /
 * 播放器按 focusRegion 路由按键), 而 Compose 的 `clickable` 焦点目标是 `Focusability.SystemDefined` ——
 * **输入模式为 Touch 时不可聚焦** (`Focusability.canFocus`). 平板上第一次触摸, AndroidComposeView 就随系统
 * 进入触摸模式把输入模式切成 Touch; 此后 onClick 照样触发, 但一切"聚焦即发生"的东西全废, 而且正持焦的
 * SystemDefined 节点会被 FocusTargetNode 观察到 canFocus 变假而当场清掉焦点. `focusable()` 默认是 Always,
 * 所以播放器根节点那种自己挂 focusable 的仍能收遥控器按键 —— 平板配遥控器不受影响.
 *
 * ## 三个原语 (都是 @Composable 修饰符工厂, 便于读开关后**原样返回 this**)
 *
 * - [LocalTvTouchInputEnabled]: 设备有触摸屏才为 true (TV 入口按 FEATURE_TOUCHSCREEN 提供). **为 false 时本文件
 *   所有 modifier 都原样返回, 电视上一个节点都不会多** —— 这是"对电视用户零影响"的硬保证.
 * - [tvTouchKeyboardMode]: 挂 TV 根部. 每次按下 (Initial pass, 祖先先收到) 都把输入模式请求回 Keyboard
 *   (Android 上 = `requestFocusFromTouch()`, 同步离开触摸模式). 系统在**每个** ACTION_DOWN 派发前都会重新进入
 *   触摸模式, 所以每次都要做; Compose 那步"canFocus 变假就清焦点"由 snapshot 观察者异步做, 在同一次派发里
 *   切回来它就看不到 Touch 态, 焦点得以保住.
 * - [tvTouchFocusOnTap]: 挂在可聚焦组件的 clickable **之前** (同一条链上). 按下即把焦点请求到该节点, 抬起由
 *   clickable 照常点击 = 遥控器的"移过去再按确认"一步到位. `twoStep` 给"聚焦本身就有副作用"的组件 (播放器
 *   胶囊聚焦即浮面板): 未聚焦时的第一下只聚焦, 吞掉抬起让 clickable 当作取消; 已聚焦再点才是点击.
 *
 * 触摸下的"用户介入"信号 (取消在途的程序送焦) 走 [tvTouchPressSignal], 页面根的
 * `tvFocusNavSignal` 在开关打开时同时旁听指针按下. 独立窗口 (Dialog/Popup) 有自己的输入模式管理器,
 * [tvTouchFocusOnTap] 在按下时也顺手请求一次 Keyboard 模式, 所以弹窗里的条目同样能点即聚焦.
 */

/** 设备有触摸屏 (平板装了 TV 包) 才为 true; 电视上恒 false, 本文件所有 modifier 退化为空. */
val LocalTvTouchInputEnabled = staticCompositionLocalOf { false }

/**
 * TV 根部: 每次指针按下都把输入模式请求回 Keyboard, 让 `clickable` 那类 SystemDefined 焦点目标在触屏上
 * 保持可聚焦. 不消费任何事件. 开关关闭时原样返回.
 */
@Composable
fun Modifier.tvTouchKeyboardMode(): Modifier {
    if (!LocalTvTouchInputEnabled.current) return this
    val inputModeManager = LocalInputModeManager.current
    return pointerInput(inputModeManager) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                }
            }
        }
    }
}

/**
 * 旁听指针按下 (Initial pass, 不消费, 子节点吃不吃掉都会收到): 给"用户介入"一类信号用.
 * 开关关闭时原样返回.
 */
@Composable
fun Modifier.tvTouchPressSignal(onPress: () -> Unit): Modifier {
    if (!LocalTvTouchInputEnabled.current) return this
    val current = rememberUpdatedState(onPress)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) current.value()
            }
        }
    }
}

/**
 * 点即聚焦: 挂在可聚焦组件的 clickable **之前**, 按下那一刻把焦点请求到该节点, 抬起交给 clickable 照常点击.
 *
 * @param twoStep 未聚焦时的第一下只聚焦 —— 吞掉抬起, 下面的 clickable 把这次手势当作取消; 已聚焦再点才是点击.
 *   给"聚焦本身就是动作"的组件用 (播放器胶囊: 聚焦即浮出面板, 点击另有含义).
 */
@Composable
fun Modifier.tvTouchFocusOnTap(twoStep: Boolean = false): Modifier {
    if (!LocalTvTouchInputEnabled.current) return this
    val requester = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    // 普通字段: 只在指针事件里读, 不进快照系统 (按下阶段写快照状态会打断正在识别的点击手势)
    val holder = remember { TvTouchFocusHolder() }
    return this
        .onFocusChanged { holder.focused = it.isFocused }
        .pointerInput(twoStep, inputModeManager) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val wasFocused = holder.focused
                // 弹窗是独立窗口, 有自己的输入模式管理器, 根部那次请求管不到这里
                inputModeManager.requestInputMode(InputMode.Keyboard)
                val gained = runCatching { requester.requestFocus() }.getOrDefault(false)
                if (twoStep && !wasFocused && gained) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            change.consume() // clickable 见到被消费的抬起 = 取消
                            break
                        }
                        if (!change.pressed) break
                    }
                }
            }
        }
        .focusRequester(requester)
}

private class TvTouchFocusHolder {
    var focused: Boolean = false
}
