/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 可选文本容器: 焦点导航的形态上退化成普通容器.
 *
 * [SelectionContainer] 的 modifier 链里带着 `.focusable()` (见 `SelectionManager.modifier`),
 * 于是**每一块可选文本本身就是一个焦点落点**. 手机/桌面上无所谓 (那里靠指针选字, 焦点无关),
 * 遥控器上却是实打实的负担: 一屏十几处说明文字全都要停一下, 夹在中间的按钮 (复制 / 打开链接 /
 * 浏览文件) 就变得很难摸到 —— 而遥控器根本没有指针, 选文本这件事在那里做不到, 落点纯属白给.
 *
 * 所以焦点导航形态下不建这个容器: 少十几个假落点, 且没有任何功能损失.
 */
@Composable
fun AniSelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalAniUiBehavior.current.focusDrivenNavigation) {
        Box(modifier) { content() }
    } else {
        SelectionContainer(modifier) { content() }
    }
}
