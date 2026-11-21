/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * 下拉菜单那一列的高度上限: 不超过窗口高度. 给 Material `DropdownMenu` 的 `modifier` 用 (它加在菜单那一列的最外面).
 *
 * iOS 上转屏那一下, 开着的菜单会拿到不限高的约束; 菜单那一列能竖着滚, 不限高时直接抛异常, 整个应用退出.
 * 有了上限照常量出来, 转屏后菜单接着开着. 平时窗口给菜单的高度本来就不超过窗口, 加了没有区别.
 */
@Composable
fun Modifier.menuHeightCap(): Modifier {
    val heightPx = LocalWindowInfo.current.containerSize.height
    val height = with(LocalDensity.current) { heightPx.toDp() }
    return heightIn(max = if (heightPx > 0) height else MENU_FALLBACK_MAX_HEIGHT)
}

/**
 * 弹窗 (Dialog、底部或侧边弹层) 那一层的尺寸上限: 宽高都不超过窗口. 加在弹窗面板最外面, 在按比例定宽高 (`fillMaxWidth(比例)`、
 * [fillMaxHeightAtMost]) 之前.
 *
 * 同 [menuHeightCap]: iOS 转屏那一下, 开着的弹窗会拿到不限宽或不限高的约束, 按比例定的宽高这时不起作用, 里面可以滚的
 * (列表、网格、`verticalScroll`) 收到无限大就抛异常, 整个应用退出. 有了上限照常量出来; 平时窗口给的本来就不超过窗口, 加了没有区别.
 */
@Composable
fun Modifier.windowSizeCap(): Modifier {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val width = with(density) { size.width.toDp() }
    val height = with(density) { size.height.toDp() }
    return widthIn(max = if (size.width > 0) width else MENU_FALLBACK_MAX_HEIGHT)
        .heightIn(max = if (size.height > 0) height else MENU_FALLBACK_MAX_HEIGHT)
}

/** 窗口尺寸还不知道时的上限 */
private val MENU_FALLBACK_MAX_HEIGHT = 2000.dp
