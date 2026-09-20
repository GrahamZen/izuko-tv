/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch

/**
 * **遥控器翻页**: 上下键把一整块可滚内容按视口比例翻一页, 而不是让焦点搜索跳出去.
 *
 * 用在"整块一个焦点"的区域上 —— 弹窗正文、评论引用区这类里面没有可聚焦子项、只能整体滚的内容.
 * 没有这层的话上下键会被空间焦点搜索直接送到区域外的按钮上, 内容永远翻不动.
 *
 * ## 边界行为
 *
 * 翻到底/翻到顶后**放行**该方向键 (返回 false), 焦点自然落到下方按钮行或上方目标 —— 这是
 * 用户唯一能离开这块内容的方式, 一律消费会把焦点锁死在里面. 若调用方另有出口 (如左右键翻条目)
 * 并且确实需要锁住上下, 传 [consumeAtEdge] = true.
 *
 * 只认 `KeyDown`: 焦点搜索也只发生在 KeyDown, 放行 KeyUp 不会有副作用, 拦了反而可能吞掉别处
 * 依赖 KeyUp 的手势 (见 `tvLongPressKey`).
 *
 * @param fraction 一次翻多少个视口. 默认 [TV_SCROLL_PAGE_FRACTION] 留一点重叠, 免得读到的
 *   最后一行正好被翻走.
 */
@Composable
fun Modifier.tvPageScrollKeys(
    scrollState: ScrollState,
    fraction: Float = TV_SCROLL_PAGE_FRACTION,
    consumeAtEdge: Boolean = false,
): Modifier {
    val scope = rememberCoroutineScope()
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        // 视口尚未测量时 viewportSize 为 0, 至少翻 1px (动画立即结束), 别把键当没处理
        val page = (scrollState.viewportSize * fraction).coerceAtLeast(1f)
        when (event.key) {
            Key.DirectionDown ->
                if (scrollState.canScrollForward) {
                    scope.launch { scrollState.animateScrollBy(page) }
                    true
                } else consumeAtEdge

            Key.DirectionUp ->
                if (scrollState.canScrollBackward) {
                    scope.launch { scrollState.animateScrollBy(-page) }
                    true
                } else consumeAtEdge

            else -> false
        }
    }
}

/**
 * 一次翻多少个视口: 留 20% 重叠, 翻页后上一屏的末尾还在视野里, 读长文不容易断行.
 */
const val TV_SCROLL_PAGE_FRACTION = 0.8f
