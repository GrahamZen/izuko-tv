/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
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
 *
 * @param positionProvider 焦点导航上自定弹层摆在哪 (如 [TvBesideAnchorPositionProvider]: 摆在长按的卡片旁边, 不压住封面); 给了就不走
 *   M3 的定位 (它只会贴着锚点上下摆), 外观照旧, [offset] 不再生效. null = M3 默认.
 */
@Composable
fun AniDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    positionProvider: PopupPositionProvider? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalAniUiBehavior.current.focusDrivenNavigation && positionProvider != null) {
        TvPositionedMenu(expanded, onDismissRequest, modifier, properties, positionProvider, content)
        return
    }
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
 * [AniDropdownMenu] 给了定位器时的画法: 与 M3 菜单同一套外观 (面板色、圆角、不画阴影; 内容上下留 8dp、宽度取最宽的一项, 同 M3 的菜单内容),
 * 只是位置由 [positionProvider] 定. 开合的过渡也照 M3 菜单: 从离锚点最近的那一边缩放着长出来并淡入, 收起时淡出, 淡完才撤弹层
 * (调用方收起时要照样组合着、把 [expanded] 置假, 才看得到淡出).
 */
@Composable
private fun TvPositionedMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    properties: PopupProperties,
    positionProvider: PopupPositionProvider,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded
    if (!expandedState.currentState && !expandedState.targetState) return
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val provider = remember(positionProvider) { TvOriginReportingPositionProvider(positionProvider) { origin = it } }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = properties) {
        val transition = rememberTransition(expandedState, label = "TvPositionedMenu")
        val scale by transition.animateFloat(
            transitionSpec = {
                if (false isTransitioningTo true) tween(MENU_IN_MILLIS, easing = LinearOutSlowInEasing)
                else tween(1, delayMillis = MENU_OUT_MILLIS - 1)
            },
            label = "scale",
        ) { if (it) 1f else MENU_CLOSED_SCALE }
        val alpha by transition.animateFloat(
            transitionSpec = { if (false isTransitioningTo true) tween(MENU_ALPHA_IN_MILLIS) else tween(MENU_OUT_MILLIS) },
            label = "alpha",
        ) { if (it) 1f else 0f }
        Surface(
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = origin
            },
            shape = CENTERED_PANEL_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = MENU_CONTAINER_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier
                    .padding(vertical = 8.dp)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

/** 转交 [delegate] 定位, 顺带按锚点与弹层的相对位置报出缩放的原点 (见 [tvMenuTransformOrigin]). */
private class TvOriginReportingPositionProvider(
    private val delegate: PopupPositionProvider,
    private val onOrigin: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val position = delegate.calculatePosition(anchorBounds, windowSize, layoutDirection, popupContentSize)
        onOrigin(tvMenuTransformOrigin(anchorBounds, IntRect(position, popupContentSize)))
        return position
    }
}

/**
 * 菜单从离锚点最近的那一边长出来 (同 M3 菜单的算法): 在锚点右边就从左缘、左边就从右缘; 与锚点重叠的方向取重叠段的中点.
 */
internal fun tvMenuTransformOrigin(anchor: IntRect, menu: IntRect): TransformOrigin {
    val pivotX = when {
        menu.left >= anchor.right -> 0f
        menu.right <= anchor.left -> 1f
        menu.width == 0 -> 0f
        else -> ((maxOf(anchor.left, menu.left) + minOf(anchor.right, menu.right)) / 2 - menu.left).toFloat() / menu.width
    }
    val pivotY = when {
        menu.top >= anchor.bottom -> 0f
        menu.bottom <= anchor.top -> 1f
        menu.height == 0 -> 0f
        else -> ((maxOf(anchor.top, menu.top) + minOf(anchor.bottom, menu.bottom)) / 2 - menu.top).toFloat() / menu.height
    }
    return TransformOrigin(pivotX, pivotY)
}

/**
 * 弹层摆在锚点 (长按的卡片封面) 旁边, 不压住它: 右边放得下放右边, 否则放左边 (两边都放不下就贴着空得多的那边的窗口边);
 * 竖向与锚点顶对齐, 放不下就往上挪到底边进屏. [gapPx] = 与锚点之间的空隙 (聚焦卡放大后会伸出锚点一截, 要算进去),
 * [marginPx] = 离窗口边的最小距离.
 */
class TvBesideAnchorPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val right = anchorBounds.right + gapPx
        val left = anchorBounds.left - gapPx - w
        val x = when {
            right + w <= windowSize.width - marginPx -> right
            left >= marginPx -> left
            windowSize.width - anchorBounds.right >= anchorBounds.left -> windowSize.width - marginPx - w
            else -> marginPx
        }
        val y = anchorBounds.top.coerceAtMost(windowSize.height - marginPx - h).coerceAtLeast(marginPx)
        return IntOffset(x, y)
    }
}

/** [TvBesideAnchorPositionProvider] 按 dp 给的版本. */
@Composable
fun rememberTvBesideAnchorPositionProvider(gap: Dp = 20.dp, margin: Dp = 24.dp): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density, gap, margin) {
        with(density) { TvBesideAnchorPositionProvider(gap.roundToPx(), margin.roundToPx()) }
    }
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

/** 定位菜单 ([TvPositionedMenu]) 的开合过渡, 同 M3 菜单: 展开时缩放 120ms (从 0.8 倍起)、淡入 30ms, 收起时淡出 75ms. */
private const val MENU_IN_MILLIS = 120
private const val MENU_ALPHA_IN_MILLIS = 30
private const val MENU_OUT_MILLIS = 75
private const val MENU_CLOSED_SCALE = 0.8f
