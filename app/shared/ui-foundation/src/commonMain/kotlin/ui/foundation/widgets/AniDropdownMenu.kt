/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior

/**
 * 下拉菜单. 焦点导航 (TV) 上与弹窗同一套外观: 面板色、[CENTERED_PANEL_SHAPE] 圆角、不画阴影 (弹窗都是平的,
 * 菜单单独浮起一层反而像另一套东西); 其余平台就是 M3 [DropdownMenu].
 *
 * 底色比弹窗实 ([MENU_CONTAINER_ALPHA]): 弹窗窗外有一层压暗, 菜单没有 —— 直接盖在列表上, 弹窗那档透明度会让底下的白字
 * 透出来一行行读得清, 与菜单项叠在一起.
 *
 * 菜单项用 [AniDropdownMenuItem]: M3 菜单项的焦点只是一层淡态层, 电视上看不出焦点在哪.
 */
@Composable
fun AniDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalAniUiBehavior.current.focusDrivenNavigation) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
            properties = properties,
            content = content,
        )
        return
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        properties = properties,
        shape = CENTERED_PANEL_SHAPE,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = MENU_CONTAINER_ALPHA),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

/**
 * 菜单项. 焦点导航上:
 * - 聚焦: 内缩的圆角块 (不顶到菜单边缘), 底色同其他小控件 ([aniFocusContainerColor]: 深色主题比 primary 暗一截,
 *   调用方自己上色的字, 如「删除」的红字, 压在上面也读得清);
 * - [selected] (菜单对应的当前值): **只在右端打勾, 不铺底色** —— Apple 的菜单与弹出按钮都是这样表示当前值 (勾本身就好找),
 *   再铺一层选中色只会跟焦点抢眼. 聚焦时勾还在, "焦点正落在当前值上"也看得出.
 *
 * 其余平台是 M3 [DropdownMenuItem], [selected] 按手机端原有的写法把文字与图标染成主题色.
 *
 * 焦点按 onFocusChanged 记: M3 菜单项靠 ripple 收交互事件画焦点, 菜单一打开焦点就落在第一项上,
 * 那次事件可能早于收集开始, 于是"焦点在、高亮不在" (见 FocusHighlight.kt 开头).
 */
@Composable
fun AniDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    if (!LocalAniUiBehavior.current.focusDrivenNavigation) {
        DropdownMenuItem(
            text = text,
            onClick = onClick,
            modifier = modifier,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled,
            colors = if (selected) {
                MenuDefaults.itemColors(
                    textColor = colorScheme.primary,
                    leadingIconColor = colorScheme.primary,
                    trailingIconColor = colorScheme.primary,
                )
            } else {
                MenuDefaults.itemColors()
            },
        )
        return
    }
    var focused by remember { mutableStateOf(false) }
    val container = if (focused) aniFocusContainerColor() else Color.Transparent
    val contentColor = (if (focused) aniFocusContentColor() else colorScheme.onSurface)
        .let { if (enabled) it else it.copy(alpha = DISABLED_CONTENT_ALPHA) }
    Row(
        modifier
            .padding(horizontal = MENU_ITEM_INSET)
            .fillMaxWidth()
            .sizeIn(minWidth = MENU_ITEM_MIN_WIDTH, maxWidth = MENU_ITEM_MAX_WIDTH, minHeight = MENU_ITEM_HEIGHT)
            .clip(MENU_ITEM_SHAPE)
            .background(container)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            if (leadingIcon != null) {
                Box(Modifier.size(MenuDefaults.LeadingIconSize), contentAlignment = Alignment.Center) {
                    leadingIcon()
                }
            }
            Box(Modifier.weight(1f)) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    text()
                }
            }
            trailingIcon?.invoke()
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(MENU_ITEM_CHECK_SIZE))
            }
        }
    }
}

/** 菜单项高亮块与菜单左右边缘的间距; 菜单圆角 16 减去它, 就是高亮块的圆角 (两层圆角同心). */
private val MENU_ITEM_INSET = 8.dp
private val MENU_ITEM_SHAPE = RoundedCornerShape(CENTERED_PANEL_CORNER - MENU_ITEM_INSET)

/** 同 M3 菜单项的尺寸约束. */
private val MENU_ITEM_MIN_WIDTH = 112.dp
private val MENU_ITEM_MAX_WIDTH = 280.dp
private val MENU_ITEM_HEIGHT = 48.dp
private val MENU_ITEM_CHECK_SIZE = 18.dp

private const val DISABLED_CONTENT_ALPHA = 0.38f

/** 菜单底色的不透明度, 见 [AniDropdownMenu]. */
private const val MENU_CONTAINER_ALPHA = 0.95f
