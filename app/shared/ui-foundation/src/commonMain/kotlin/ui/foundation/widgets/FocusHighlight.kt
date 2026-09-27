/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior

/*
 * 焦点导航 (遥控器) 上的示焦约定, 集中在这一处.
 *
 * 手机/桌面用鼠标点, 主题色表示"我是主要动作"; 遥控器没有指针, 屏幕上必须一眼看出方向键
 * 此刻停在哪 —— 于是主题色改为表示"焦点在我身上", 未聚焦的一律压成中性色. 弹窗里同时摆着
 * 好几个按钮时 (搜索/取消、发送、确认), 全是主色实底就完全分不出焦点位置.
 *
 * 弹窗 / 菜单里的控件分两类示焦:
 * - **小控件** (按钮 / 胶囊 / 选项格 / 菜单项): 聚焦实底 ([aniFocusContainerColor], 主题色 tone 40 那一档), 即本文件与
 *   [AniDropdownMenuItem];
 * - **大块内容** (图卡 / 文本块 / 输入框): 2dp 主题色描边, 各处自绘.
 * 选中分两种: 胶囊这类开关 / 多选是 secondaryContainer (TV 上加深过, 见 `withStrongSelectionColors`) 加一枚 ✓;
 * 菜单、值网格这类单选列表的当前值只打勾, 不铺底色 (见 [AniDropdownMenuItem]). 两种都靠 ✓ 让"焦点正落在当前值上"
 * 也看得出 —— 没有 ✓ 的话, 选中项一被焦点的底色盖住就分不出选没选中.
 *
 * 焦点一律按 `onFocusChanged` 记, 不用 `interactionSource.collectIsFocusedAsState()`: 后者靠收集交互事件,
 * 而弹窗打开时焦点往往在控件刚进组合、收集还没开始时就送到了 —— 那一次 Focus 事件丢掉, 表现为
 * "焦点在、高亮不在", 按一下方向键才出现.
 *
 * 只在 [me.him188.ani.app.ui.foundation.AniUiBehavior.focusDrivenNavigation] 的形态上生效.
 */

/**
 * 聚焦实底的颜色: 主题色 tone 40 那一档 —— 深色主题取 inversePrimary (比 primary 暗一截; primary 是满屏最亮的一块,
 * 调用方自己上色的字, 如「删除」的红字, 压在上面读不清), 浅色主题就是 primary.
 */
@Composable
@ReadOnlyComposable
fun aniFocusContainerColor(): Color {
    val colorScheme = MaterialTheme.colorScheme
    return if (colorScheme.isDarkScheme()) colorScheme.inversePrimary else colorScheme.primary
}

/** [aniFocusContainerColor] 上的字色: 两种主题下都是浅字. */
@Composable
@ReadOnlyComposable
fun aniFocusContentColor(): Color {
    val colorScheme = MaterialTheme.colorScheme
    return if (colorScheme.isDarkScheme()) colorScheme.onSurface else colorScheme.onPrimary
}

private fun ColorScheme.isDarkScheme(): Boolean = surface.luminance() < 0.5f

/**
 * 弹窗动作按钮 (确认 / 发送 等): 聚焦即实底 ([aniFocusContainerColor]), 未聚焦是中性容器 + 描边胶囊.
 *
 * 提交中不要用可聚焦性挡重复点击: 不可聚焦的按钮会让焦点当场丢在弹窗里 (遥控器上表现为方向键全失效).
 * 传 [loading] 换成转圈, 重复点击由 [onClick] 自己挡; [enabled] 为 false 时照样可聚焦, 只是字变淡、按了不响应.
 *
 * [accentColor] 给表示特殊语义的按钮 (「删除」的红字): 常态与聚焦都用它做字色, 不传就是正文色.
 *
 * 焦点请求器 / [androidx.compose.ui.focus.focusProperties] 这类导航配置由调用方经 [modifier] 给.
 */
@Composable
fun AniFocusActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
    accentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val contentColor = when {
        accentColor.isSpecified -> accentColor
        focused -> aniFocusContentColor()
        else -> colorScheme.onSurface
    }
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = CircleShape,
        // 未聚焦的底比面板高一档 (与弹窗里的输入框/引用区同一档), 而不是自定 alpha:
        // 走配色表才能保证各个弹窗里的按钮长得一样
        color = if (focused) aniFocusContainerColor() else colorScheme.surfaceContainerHighest,
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = DISABLED_ALPHA),
        border = if (focused) null else BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Box(
            Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * 纯图标按钮 (弹窗标题行的缓存入口、编辑请求…): 常态透明, 聚焦实底, 规则同 [AniFocusActionButton].
 * 焦点导航之外就是 M3 [IconButton].
 */
@Composable
fun AniFocusIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!LocalAniUiBehavior.current.focusDrivenNavigation) {
        IconButton(onClick, modifier) { content() }
        return
    }
    val colorScheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = CircleShape,
        color = if (focused) aniFocusContainerColor() else Color.Transparent,
        contentColor = if (focused) aniFocusContentColor() else LocalContentColor.current,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * 弹窗里可选中的小控件 (胶囊 / 选项格 / 集号方块) 的底座: 聚焦实底 ([aniFocusContainerColor]), 选中 secondaryContainer,
 * 其余 [unselectedColor] (默认比面板高一档的 surfaceContainerHighest, 与 [AniFocusActionButton] 常态同一档).
 * 内容色随三态由 [Surface] 供给; [content] 拿到是否聚焦, 自己另外着色的部分 (如状态尾标) 聚焦时要让位给内容色.
 *
 * 选中的 ✓ 由上层按版式自己摆 ([AniFocusChip] 在字前, 网格格子在右端).
 */
@Composable
fun AniFocusSelectableSurface(
    onClick: () -> Unit,
    selected: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    unselectedColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = shape,
        color = when {
            focused -> aniFocusContainerColor()
            selected -> colorScheme.secondaryContainer
            else -> unselectedColor
        },
        contentColor = when {
            focused -> aniFocusContentColor()
            selected -> colorScheme.onSecondaryContainer
            else -> unselectedContentColor
        },
    ) {
        content(focused)
    }
}

/**
 * 弹窗里的大块可点内容 (资源卡片这类多行图文): 聚焦 2dp 主题色描边, 底色不变 —— 整块铺主题色太重,
 * 卡里的次要文字 (灰字、红字) 压在主色上也读不清. 选中 secondaryContainer, 其余 [unselectedColor].
 */
@Composable
fun AniFocusRingSurface(
    onClick: () -> Unit,
    selected: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    unselectedColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = shape,
        color = if (selected) colorScheme.secondaryContainer else unselectedColor,
        contentColor = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurface,
        border = if (focused) BorderStroke(FOCUS_RING_WIDTH, colorScheme.primary) else null,
        content = content,
    )
}

/** 大块内容的聚焦描边宽度. */
private val FOCUS_RING_WIDTH = 2.dp

/**
 * 弹窗里的胶囊 (筛选项、标签、开关): [AniFocusSelectableSurface] 的圆角胶囊版, 选中时字前多一枚 ✓.
 *
 * 选中与否会改变宽度 (✓ 占位): 与 M3 FilterChip 一致. 常驻占位的话没选中的胶囊左边会空一截, 反而像排版出错.
 */
@Composable
fun AniFocusChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    AniFocusSelectableSurface(
        onClick = onClick,
        selected = selected,
        shape = CircleShape,
        modifier = modifier,
    ) { focused ->
        Row(
            Modifier.padding(
                start = if (selected) FOCUS_CHIP_CHECKED_START_PADDING else FOCUS_CHIP_HORIZONTAL_PADDING,
                end = FOCUS_CHIP_HORIZONTAL_PADDING,
                top = FOCUS_CHIP_VERTICAL_PADDING,
                bottom = FOCUS_CHIP_VERTICAL_PADDING,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(FOCUS_CHIP_CHECK_SIZE))
            }
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                content(focused)
            }
        }
    }
}

/** 只有一行字的 [AniFocusChip]. */
@Composable
fun AniFocusChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AniFocusChip(selected, onClick, modifier) {
        Text(text, maxLines = 1)
    }
}

private val FOCUS_CHIP_HORIZONTAL_PADDING = 14.dp
private val FOCUS_CHIP_VERTICAL_PADDING = 8.dp

/** 带 ✓ 时左边距收窄, 让 ✓ 贴近胶囊左缘 (✓ 自带留白). */
private val FOCUS_CHIP_CHECKED_START_PADDING = 10.dp
private val FOCUS_CHIP_CHECK_SIZE = 16.dp

/**
 * 弹窗 / 面板容器里的控件约定, 由容器 ([AniCenteredPanelDialog]、[AniAlertDialog] 等) 包在内容外面: 对话框按钮
 * ([AniTextButton] 那一组) 画成弹窗动作按钮 ([AniFocusActionButton]). 其余 M3 组件的示焦照 M3 默认.
 *
 * 焦点导航之外原样组合 [content].
 */
@Composable
fun ProvidePopupControlStyle(content: @Composable () -> Unit) {
    if (!LocalAniUiBehavior.current.focusDrivenNavigation) {
        content()
        return
    }
    CompositionLocalProvider(LocalDialogButtonsAsActionButtons provides true, content = content)
}

private const val DISABLED_ALPHA = 0.38f
