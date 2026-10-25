/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.widgets.ANI_SCROLL_FADE

/**
 * 打开 [tvReadableBlock]: 遥控器在这块内容里一段一段读长文, 不被焦点一下滚过去. 默认关, 由要它的容器打开 (电视播放器胶囊上方的叠层).
 */
val LocalTvReadableBlocks: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * **遥控器读长内容**: 一段长内容 (简介全文、基本信息表) 当一个焦点停靠点, 比看得见的部分高时, 停在上面按上下键先在这一段里翻页
 * (每次翻看得见那截的 [TV_SCROLL_PAGE_FRACTION], 留一截重叠接着读), 翻到这一头才把方向键交出去, 焦点照常走到上 / 下一个.
 *
 * 焦点驱动的页面只认可聚焦的东西: 没有这层时, 焦点从这段上面直接跳到下面那个按钮, 页面跟着一下滚过去, 中间没读到的部分就看不到了.
 * 翻页借外层滚动容器的滚进视野 (请求这一段里下一页的那一行), 不用拿滚动状态; 外层要配 ProvideScrollFadeFocusMargin
 * (焦点项看得见就不滚, 滚的话让开上下渐隐的 [ANI_SCROLL_FADE]).
 *
 * 只在 [LocalTvReadableBlocks] 打开的地方生效, 别处原样.
 *
 * @param focusable 这一段本身不可聚焦 (纯展示的表) 时让它可聚焦, 聚焦时垫一层浅白底板 (同 tvOS 按钮底板, 白 10%);
 * 已经可点的 (简介正文, 点一下展开) 传 false, 聚焦样式用它自己的.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvReadableBlock(focusable: Boolean = false): Modifier = composed {
    if (!LocalTvReadableBlocks.current) return@composed Modifier
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val fadePx = with(LocalDensity.current) { ANI_SCROLL_FADE.toPx() }
    this
        .onPlaced { placed[0] = it }
        .bringIntoViewRequester(requester)
        .onPreviewKeyEvent { event ->
            val down = when (event.key) {
                Key.DirectionDown -> true
                Key.DirectionUp -> false
                else -> return@onPreviewKeyEvent false
            }
            // 焦点搜索只在 KeyDown 发生, KeyUp 放行
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val coordinates = placed[0]?.takeIf { it.isAttached } ?: return@onPreviewKeyEvent false
            val target = nextPageLine(coordinates, down, fadePx) ?: return@onPreviewKeyEvent false
            val width = coordinates.size.width.toFloat()
            scope.launch { requester.bringIntoView(Rect(0f, target, width, target + 1f)) }
            true
        }
        .ifThen(focusable) {
            drawBehind {
                if (focused) {
                    val inset = TV_READABLE_BLOCK_PLATTER_INSET.toPx()
                    drawRoundRect(
                        TV_READABLE_BLOCK_PLATTER_COLOR,
                        topLeft = Offset(-inset, -inset),
                        size = Size(size.width + inset * 2, size.height + inset * 2),
                        cornerRadius = CornerRadius(TV_READABLE_BLOCK_PLATTER_CORNER.toPx()),
                    )
                }
            }.focusable(interactionSource = interactionSource)
        }
}

/**
 * 往 [down] 方向翻一页要露出的那一行 (这一段自己的纵坐标); 这一头已经全看得见 (不用翻) 时 null.
 * 看得见的范围按窗口里没被裁掉的那截算, 被裁的那一边再让开渐隐的 [fade] (那截字是半透明的, 不算读到).
 */
private fun nextPageLine(coordinates: LayoutCoordinates, down: Boolean, fade: Float): Float? {
    val height = coordinates.size.height.toFloat()
    val top = coordinates.positionInWindow().y
    val visible = coordinates.boundsInWindow()
    val visibleTop = visible.top - top
    val visibleBottom = visible.bottom - top
    val readableTop = if (visibleTop > 0.5f) visibleTop + fade else 0f
    val readableBottom = if (visibleBottom < height - 0.5f) visibleBottom - fade else height
    val page = ((readableBottom - readableTop) * TV_SCROLL_PAGE_FRACTION).coerceAtLeast(1f)
    return if (down) {
        if (readableBottom >= height - 0.5f) null else (readableBottom + page).coerceAtMost(height - 1f)
    } else {
        if (readableTop <= 0.5f) null else (readableTop - page).coerceAtLeast(0f)
    }
}

private val TV_READABLE_BLOCK_PLATTER_COLOR = Color.White.copy(alpha = 0.1f)
private val TV_READABLE_BLOCK_PLATTER_INSET = 8.dp
private val TV_READABLE_BLOCK_PLATTER_CORNER = 12.dp
