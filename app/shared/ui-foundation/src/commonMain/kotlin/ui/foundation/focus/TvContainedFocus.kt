/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch

/**
 * 方向键在这个容器里自己走完并消费: 里面的控件没接住的方向键 (行到了头、原生横滑行不接的上下键) 在这里按方向移焦点,
 * 找不到目标也吞掉, 不交给 Android 的 FocusFinder —— 它按屏幕位置在整个窗口里找, 窗口里有原生视图 (AndroidView) 时
 * 会挑中它们的子视图 (滑过行首的卡、别的行的卡), 焦点就落到看着不相邻的地方.
 *
 * 挂在弹窗 / 独立一块内容的根上 (人物预览弹窗; 详情页的每一页由 PageSection 做同样的事). KeyUp 与 KeyDown 同进退.
 */
fun Modifier.tvContainDirectionalKeys(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    onKeyEvent { event ->
        val direction = when (event.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            else -> return@onKeyEvent false
        }
        if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
        true
    }
}

/**
 * 焦点进入这一块时把整块滚进可见范围 (外层的滚动容器负责滚). 给装着原生视图 (AndroidView) 的块用: 焦点落在原生视图里时
 * Compose 不会替它滚 (只有 Compose 自己的可聚焦节点获焦会发起滚动), 焦点进了屏幕外的一行, 页面却停着不动.
 */
fun Modifier.tvBringIntoViewOnFocus(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    bringIntoViewRequester(requester).onFocusChanged {
        if (it.hasFocus) scope.launch { requester.bringIntoView() }
    }
}

/**
 * 区块标题行右边的按钮 ([action], 如「查看全部」) 夹在上一块与这一块的内容之间: 方向键按几何找焦点时它不在内容的正上 / 正下方, 总被整排卡
 * (正对着的候选优先) 抢掉, 焦点够不着它. 挂在这一块的内容上: 从上面按下进来 (不是从这颗按钮自己按下来的) 先落到按钮; [upFromContent] 时
 * 在内容里按上也先回到按钮 (内容只有一排时用, 如原生横滑行; 多行的内容让第一行自己指过去) —— 照从上往下的顺序: 上一块 → 按钮 → 内容.
 * 程序化送焦 (进页恢复等) 不改道. [actionFocused] = 按钮此刻有没有焦点 (按钮上 onFocusChanged 记着).
 */
fun Modifier.tvHeaderActionFirst(
    action: FocusRequester,
    actionFocused: () -> Boolean,
    upFromContent: Boolean = true,
): Modifier {
    val up = if (upFromContent) {
        Modifier.onPreviewKeyEvent { event ->
            // 按下挪焦点, 抬起跟着吞掉 (同 tvContainDirectionalKeys: KeyUp 与 KeyDown 同进退)
            event.key == Key.DirectionUp &&
                (event.type != KeyEventType.KeyDown || runCatching { action.requestFocus() }.isSuccess)
        }
    } else {
        Modifier
    }
    return this
        .then(up)
        .focusProperties {
            onEnter = {
                if (requestedFocusDirection == FocusDirection.Down && !actionFocused() &&
                    runCatching { action.requestFocus() }.isSuccess
                ) {
                    cancelFocusChange()
                }
            }
        }
        // onEnter 只在焦点组节点上生效
        .focusGroup()
}
