/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 图标圆钮的默认直径. 与左缘侧边栏的图标按钮同尺寸, 详情页 Hero 动作行用的就是这一档;
 * 动作面板里的那一排放大到 [TV_CAPSULE_SIZE_LARGE] —— 那里它们是页面的主要操作.
 */
val TV_CAPSULE_SIZE = 32.dp

/** 动作面板那一排的直径: 圆钮就是主要操作, 且 10 英尺距离要够得着. */
val TV_CAPSULE_SIZE_LARGE = 48.dp

/** 圆钮里的字形尺寸: 外层约束把默认 24dp 的 Icon 收到这个尺寸. */
val TV_ICON_GLYPH_SIZE = 20.dp

/** [TV_CAPSULE_SIZE_LARGE] 配套的字形尺寸. */
val TV_ICON_GLYPH_SIZE_LARGE = 24.dp

/** 聚焦时容器色的不透明度. */
const val TV_FOCUSED_CONTAINER_ALPHA = 0.8f

/**
 * **图标圆钮**: 正圆, 无底色无描边, 仅主题色图标; 聚焦时填充主题主色 (动态主题下即封面取色).
 *
 * 两处在用, 差别只在尺寸与标签形态:
 *
 * - 详情页 Hero 动作行 (32dp): 传 [label], 聚焦时在圆钮**上方**浮现纯文字标签.
 *   标签走 `layout(0, 0)` 不占任何布局空间 —— 占了会推挤同行的其余圆钮.
 * - 动作面板 (48dp): **不传** [label], 由面板自己在整排下面留一行固定标签显示当前聚焦项.
 *   固定行的好处见那边的注释: 永远只有一个标签, 没有多个标签同时淡入淡出的时序问题,
 *   而且用户不必先把焦点挪过去才知道这排是干什么的.
 *
 * @param danger 不可逆动作 (如退出应用): 聚焦时填错误色而不是主色. 全 TV 界面只有这类动作是红的.
 * @param onFocusChanged 供调用方做固定标签行之类的簿记.
 */
@Composable
fun TvCapsuleButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = TV_CAPSULE_SIZE,
    glyphSize: Dp = TV_ICON_GLYPH_SIZE,
    danger: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {},
    label: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val onAccent = if (danger) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    val containerColor by animateColorAsState(
        if (focused) accent.copy(alpha = TV_FOCUSED_CONTAINER_ALPHA) else Color.Transparent,
    )
    val contentColor by animateColorAsState(
        when {
            focused -> onAccent
            danger -> accent
            else -> onSurface
        },
    )
    Box(modifier) {
        Box(
            Modifier
                .size(size)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChanged(it.isFocused)
                }
                .clip(CircleShape)
                .background(containerColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Box(Modifier.size(glyphSize), contentAlignment = Alignment.Center) {
                    icon()
                }
            }
        }
        if (label != null) {
            // 聚焦时上方浮现的文字标签: layout(0,0) 不占任何布局空间;
            // 相对圆钮水平居中, 底缘在圆钮上缘之上 8dp
            Box(
                Modifier.layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints())
                    val anchorWidth = size.roundToPx()
                    layout(0, 0) {
                        placeable.place(
                            x = (anchorWidth - placeable.width) / 2,
                            y = -(placeable.height + 8.dp.roundToPx()),
                        )
                    }
                },
            ) {
                AnimatedVisibility(focused, enter = fadeIn(), exit = fadeOut()) {
                    ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                        CompositionLocalProvider(LocalContentColor provides onSurface) {
                            Row { label() }
                        }
                    }
                }
            }
        }
    }
}
