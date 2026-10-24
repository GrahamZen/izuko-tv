/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.him188.ani.app.ui.foundation.IpadDiag

/** 能带点按动作的 [Toaster] (见 [toastWithAction]). */
interface ActionToaster : Toaster {
    fun toast(text: String, onClick: () -> Unit)
}

/**
 * 发一条点了做 [onClick] 的 toast. 当前的 [Toaster] 点不了 (系统 Toast: 电视、手机) 时只显示文字, 动作丢掉 ——
 * 调用方不必分平台, 点不了的地方本来也没法点.
 */
fun Toaster.toastWithAction(text: String, onClick: () -> Unit) {
    if (this is ActionToaster) toast(text, onClick) else toast(text)
}

/**
 * 触屏上 (平板装电视包、iPad) 画在应用里的 toast: 带动作的画在这里 (同 [Toast] 的位置与样子, 整条可点, 末尾一个 ›), 点了先收起再做;
 * 不带动作的交给 [plain] (平板上照旧系统 Toast), [plain] 为 null 时也画在这里 (iPad 一直是应用里画的).
 * 一次只显示一条, 新的顶掉旧的, [ACTION_TOAST_MILLIS] 后收起. 用 [rememberActionToastState] 建, [ActionToastHost] 画.
 */
@Stable
class ActionToastState internal constructor(
    private val plain: Toaster?,
    private val scope: CoroutineScope,
) : ActionToaster {
    internal var text by mutableStateOf("")
        private set
    internal var onClick by mutableStateOf<(() -> Unit)?>(null)
        private set
    internal var showing by mutableStateOf(false)
        private set
    private var hideJob: Job? = null

    override fun toast(text: String) {
        if (plain != null) plain.toast(text) else show(text, null)
    }

    override fun toast(text: String, onClick: () -> Unit) {
        show(text, onClick)
    }

    private fun show(text: String, onClick: (() -> Unit)?) {
        IpadDiag.log("toast show action=${onClick != null} plainHost=${plain != null} text=$text")
        this.text = text
        this.onClick = onClick
        showing = true
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(ACTION_TOAST_MILLIS)
            showing = false
        }
    }

    internal fun click() {
        val action = onClick ?: return
        IpadDiag.log("toast click text=$text")
        hideJob?.cancel()
        showing = false
        action()
    }
}

/** [plain] 见 [ActionToastState]. */
@Composable
fun rememberActionToastState(plain: Toaster?): ActionToastState {
    val scope = rememberCoroutineScope()
    return remember(plain, scope) { ActionToastState(plain, scope) }
}

/** 画 [state] 那一条: 铺满父布局 (位置同 [Toast]), 叠在页面上面. */
@Composable
fun ActionToastHost(state: ActionToastState) {
    val clickable = state.onClick != null
    Toast({ state.showing }, onClick = if (clickable) state::click else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.text)
            if (clickable) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, Modifier.size(20.dp))
        }
    }
}

/** 显示多久 (同 [ToastViewModel]). */
private const val ACTION_TOAST_MILLIS = 4000L
