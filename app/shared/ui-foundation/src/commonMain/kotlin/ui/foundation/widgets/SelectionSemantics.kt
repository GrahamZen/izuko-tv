/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState

/**
 * 报出「选中」: 几个选项里选着的那一个 (胶囊、选项格、标签).
 *
 * 自己画选中态的可点控件把它挂在接点击的那个节点上, 读屏与调试界面接口 (`api/debug/ui`) 才读得出哪一个选着;
 * 用 `Modifier.selectable` 的控件自带这一项.
 */
fun Modifier.selectedSemantics(selected: Boolean): Modifier = semantics { this.selected = selected }

/**
 * 报出「勾选」: 能各自勾上或去掉的一项 (多选列表的一行、「不再询问」).
 *
 * 自己画勾选框的可点控件把它挂在接点击的那个节点上; 用 `Modifier.toggleable` 的控件自带这一项.
 */
fun Modifier.checkedSemantics(checked: Boolean): Modifier = semantics { toggleableState = ToggleableState(checked) }
