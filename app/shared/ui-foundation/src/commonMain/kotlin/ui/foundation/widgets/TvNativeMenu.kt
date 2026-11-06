/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import kotlin.math.roundToInt

/**
 * 原生菜单的一项 (纯数据). 电视上 [TvNativeMenu] 按它画; 别处调用方照旧画成 [AniDropdownMenuItem].
 *
 * @param dimmed 字压暗但仍能选 (如设备放不了的音轨)
 * @param dividerBefore 这一项上面画一条分隔线
 */
@Immutable
data class TvMenuOption(
    val text: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val dimmed: Boolean = false,
    val dividerBefore: Boolean = false,
)

/** 菜单的配色与字, 在锚点处按当时的主题取 (播放器里强制深色等局部主题照样生效), 同 [AniDropdownMenu] 与 [AniDropdownMenuItem]. */
@Immutable
class TvNativeMenuLook(
    val density: Density,
    val text: TextStyle,
    val container: Color,
    val content: Color,
    val focusContainer: Color,
    val focusContent: Color,
    val divider: Color,
)

/**
 * 一次打开的原生菜单.
 *
 * @param anchorBounds 锚点在窗口里的位置 (像素), 同 Popup 的锚点: 菜单所在的那一层父布局
 * @param positionProvider 摆在哪; null = 同 M3 下拉菜单 ([TvDropdownMenuPositionProvider])
 * @param initialIndex 打开时高亮哪一项; 不能选的往后顺延
 * @param onSelect 选了第几项; 菜单自己先收起, 调用方照常把展开置假
 * @param onDismissRequest 返回键、点菜单外面、长按全局键时
 */
class TvNativeMenuRequest(
    val anchorBounds: IntRect,
    val options: List<TvMenuOption>,
    val look: TvNativeMenuLook,
    val positionProvider: PopupPositionProvider?,
    val offset: DpOffset,
    val initialIndex: Int,
    val onSelect: (index: Int) -> Unit,
    val onDismissRequest: () -> Unit,
)

/** 正开着的那一个菜单. */
interface TvNativeMenuSession {
    /** 开着时选项或锚点变了 (如字幕列表更新). */
    fun update(request: TvNativeMenuRequest)

    /** 收起 (淡出); 已经收起或被别的菜单顶掉了就什么都不做. */
    fun close()
}

/**
 * 把下拉菜单画成主窗口里的原生视图: 不另开窗口、不新建组合, 打开快; 也避开某些电视上独立窗口第一帧之后不更新的问题.
 * 焦点留在锚点上不动, 按键由宿主在根部截下转给菜单.
 * TV 根部提供, 别处为 null.
 */
interface TvNativeMenuPresenter {
    /** 调用处在不在菜单画得到的那个窗口里: Dialog / Popup 里的锚点要照旧开独立窗口的菜单, 主窗口的原生层会被它们盖住. */
    @Composable
    fun isInHostWindow(): Boolean

    fun open(request: TvNativeMenuRequest): TvNativeMenuSession
}

val LocalTvNativeMenuPresenter = staticCompositionLocalOf<TvNativeMenuPresenter?> { null }

/**
 * 焦点导航上锚点在主窗口里时, 把菜单交给原生菜单 ([TvNativeMenuPresenter]) 并返回 true; 交不了就什么都不画、返回 false,
 * 调用方照旧画 [AniDropdownMenu]. 放在 [AniDropdownMenu] 原来的位置 (锚点那一层父布局里).
 *
 * @param options 只在展开时取
 */
@Composable
fun TvNativeMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: @Composable () -> List<TvMenuOption>,
    onSelect: (index: Int) -> Unit,
    positionProvider: PopupPositionProvider? = null,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    initialIndex: (List<TvMenuOption>) -> Int = { 0 },
): Boolean {
    val presenter = LocalTvNativeMenuPresenter.current ?: return false
    if (!LocalAniUiBehavior.current.focusDrivenNavigation || !presenter.isInHostWindow()) return false
    val anchor = remember { TvNativeMenuAnchor() }
    // 同 Popup: 锚点 = 这个零尺寸节点的父布局
    Layout(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            val bounds = (coordinates.parentLayoutCoordinates ?: coordinates).boundsInWindow()
            anchor.bounds = IntRect(
                bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt(),
            )
            anchor.onMoved?.invoke()
        },
    ) { _, _ -> layout(0, 0) {} }
    if (!expanded) return true
    // 原生菜单自己收起 (选中 / 返回 / 点外面) 时加一: 调用方在同一帧里又把它展开 (收起后马上又按了确定) 时,
    // 组合层看到的展开状态一直是真, 靠它重新打开一个
    val closedByMenu = remember { mutableIntStateOf(0) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val list = options()
    val look = rememberTvNativeMenuLook()
    val request = TvNativeMenuRequest(
        anchorBounds = anchor.bounds,
        options = list,
        look = look,
        positionProvider = positionProvider,
        offset = offset,
        initialIndex = initialIndex(list),
        onSelect = {
            closedByMenu.intValue++
            currentOnSelect(it)
        },
        onDismissRequest = {
            closedByMenu.intValue++
            currentOnDismiss()
        },
    )
    val latest by rememberUpdatedState(request)
    val session = remember { arrayOfNulls<TvNativeMenuSession>(1) }
    DisposableEffect(presenter, closedByMenu.intValue) {
        val opened = presenter.open(latest)
        session[0] = opened
        anchor.onMoved = { opened.update(latest.withAnchor(anchor.bounds)) }
        onDispose {
            anchor.onMoved = null
            session[0] = null
            opened.close()
        }
    }
    SideEffect { session[0]?.update(request) }
    return true
}

@Composable
private fun rememberTvNativeMenuLook(): TvNativeMenuLook {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.labelLarge
    val focusContainer = aniFocusContainerColor()
    val focusContent = aniFocusContentColor()
    return remember(density, colors, text, focusContainer, focusContent) {
        TvNativeMenuLook(
            density = density,
            text = text,
            container = colors.surfaceContainerHigh.copy(alpha = MENU_CONTAINER_ALPHA),
            content = colors.onSurface,
            focusContainer = focusContainer,
            focusContent = focusContent,
            divider = colors.outlineVariant,
        )
    }
}

private fun TvNativeMenuRequest.withAnchor(bounds: IntRect) = TvNativeMenuRequest(
    bounds, options, look, positionProvider, offset, initialIndex, onSelect, onDismissRequest,
)

private class TvNativeMenuAnchor {
    var bounds: IntRect = IntRect.Zero
    var onMoved: (() -> Unit)? = null
}

/**
 * 同 M3 下拉菜单的摆法 (没给定位器时 [AniDropdownMenu] 走的就是它): 横向先与锚点起边对齐, 放不下与锚点末边对齐, 再不行贴窗口边;
 * 竖向先放锚点下面, 放不下放上面, 再不行与锚点顶边居中, 最后贴窗口上下边. 上下离窗口边至少 [verticalMarginPx].
 */
class TvDropdownMenuPositionProvider(
    private val offsetXPx: Int,
    private val offsetYPx: Int,
    private val verticalMarginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val ltr = layoutDirection == LayoutDirection.Ltr
        val startToAnchorStart = if (ltr) anchorBounds.left + offsetXPx else anchorBounds.right - width - offsetXPx
        val endToAnchorEnd = if (ltr) anchorBounds.right - width + offsetXPx else anchorBounds.left - offsetXPx
        val toWindowEdge = if (anchorBounds.center.x < windowSize.width / 2) 0 else windowSize.width - width
        val x = listOf(startToAnchorStart, endToAnchorEnd, toWindowEdge).firstOrNull { it >= 0 && it + width <= windowSize.width }
            ?: toWindowEdge
        val topToAnchorBottom = maxOf(anchorBounds.bottom + offsetYPx, verticalMarginPx)
        val bottomToAnchorTop = anchorBounds.top - height - offsetYPx
        val centerToAnchorTop = anchorBounds.top - height / 2
        val toWindowSide = if (anchorBounds.center.y < windowSize.height / 2) {
            verticalMarginPx
        } else {
            windowSize.height - height - verticalMarginPx
        }
        val y = listOf(topToAnchorBottom, bottomToAnchorTop, centerToAnchorTop).firstOrNull {
            it >= verticalMarginPx && it + height <= windowSize.height - verticalMarginPx
        } ?: toWindowSide
        return IntOffset(x, y)
    }
}

/** M3 菜单离窗口上下边的最小距离. */
val TV_MENU_VERTICAL_MARGIN = 48.dp
