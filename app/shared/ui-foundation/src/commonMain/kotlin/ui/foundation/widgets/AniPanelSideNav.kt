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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 居中面板弹窗的「左右切换内容」(如相邻集的详情): [onNavigate] 收 `delta` = -1 / +1, [canNavigate] 说那一侧还有没有.
 * 见 [AniCenteredPanelDialog] 的 `sideNav`.
 */
class AniPanelSideNav(
    val onNavigate: (delta: Int) -> Unit,
    val canNavigate: (delta: Int) -> Boolean,
)

/**
 * 给面板加左右切换: 弹窗内容铺满窗口, 面板居中 ([panel]), 面板两侧外面各一个箭头 (同评论长按看图那层的样子, 加一圈暗底在任何页面上都
 * 看得清; 那一侧到头时变淡、点不动); 在弹窗上横着滑一下也切 (往左滑 = 下一个).
 *
 * 箭头贴着面板外侧 ([PANEL_ARROW_GAP]), 不贴屏幕边: 屏幕边上是侧边栏, 贴边的箭头压在栏里的图标上. 面板太宽放不下时退到屏幕边.
 * 铺满窗口之后面板外面也在弹窗里, 点外面关弹窗要自己接 (垫在面板下面的一层). 面板 (M3 Surface) 自己吞掉点击, 点不到这一层.
 * 只在触屏上用 (见 [AniCenteredPanelDialog] 的 sideNav); 箭头仍不可聚焦: 接着键盘时左右键由面板里的按键处理切换, 焦点不该挪到箭头上.
 */
@Composable
internal fun AniPanelSideNavHost(
    sideNav: AniPanelSideNav,
    onDismissRequest: () -> Unit,
    panel: @Composable BoxScope.() -> Unit,
) {
    val nav by rememberUpdatedState(sideNav)
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        val delta = if (dragged < 0) 1 else -1
                        if (abs(dragged) >= PANEL_SWIPE_THRESHOLD.toPx() && nav.canNavigate(delta)) {
                            nav.onNavigate(delta)
                        }
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged += amount
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest,
            ),
        )
        Layout(
            content = {
                Box(propagateMinConstraints = false) { panel() }
                PanelSideArrow(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    enabled = nav.canNavigate(-1),
                    onClick = { nav.onNavigate(-1) },
                    Modifier.testTag(TAG_PANEL_NAV_PREVIOUS),
                )
                PanelSideArrow(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    enabled = nav.canNavigate(1),
                    onClick = { nav.onNavigate(1) },
                    Modifier.testTag(TAG_PANEL_NAV_NEXT),
                )
            },
            Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val panelPlaceable = measurables[0].measure(loose)
            val previous = measurables[1].measure(loose)
            val next = measurables[2].measure(loose)
            val gap = PANEL_ARROW_GAP.roundToPx()
            val edge = PANEL_ARROW_EDGE_PADDING.roundToPx()
            layout(constraints.maxWidth, constraints.maxHeight) {
                val panelX = (constraints.maxWidth - panelPlaceable.width) / 2
                panelPlaceable.place(panelX, (constraints.maxHeight - panelPlaceable.height) / 2)
                val arrowY = (constraints.maxHeight - previous.height) / 2
                previous.place((panelX - gap - previous.width).coerceAtLeast(edge), arrowY)
                next.place((panelX + panelPlaceable.width + gap).coerceAtMost(constraints.maxWidth - edge - next.width), arrowY)
            }
        }
    }
}

@Composable
private fun PanelSideArrow(icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(PANEL_ARROW_TOUCH_SIZE)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = PANEL_ARROW_BACKGROUND_ALPHA))
            .focusProperties { canFocus = false }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            Modifier.size(PANEL_ARROW_ICON_SIZE),
            tint = Color.White.copy(alpha = if (enabled) PANEL_ARROW_ALPHA else PANEL_ARROW_DIM_ALPHA),
        )
    }
}

const val TAG_PANEL_NAV_PREVIOUS = "panelNavPrevious"
const val TAG_PANEL_NAV_NEXT = "panelNavNext"

/** 横着滑多远算一次切换. */
private val PANEL_SWIPE_THRESHOLD = 56.dp

/** 箭头离面板的距离、面板太宽时离屏幕边缘的最小距离、点按范围、图标大小 (同评论看图那层的 40dp). */
private val PANEL_ARROW_GAP = 16.dp
private val PANEL_ARROW_EDGE_PADDING = 8.dp
private val PANEL_ARROW_TOUCH_SIZE = 56.dp
private val PANEL_ARROW_ICON_SIZE = 40.dp

/** 箭头底下那圈暗底、箭头与到头那一侧箭头的不透明度. */
private const val PANEL_ARROW_BACKGROUND_ALPHA = 0.35f
private const val PANEL_ARROW_ALPHA = 0.9f
private const val PANEL_ARROW_DIM_ALPHA = 0.25f
