/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff

/**
 * 给每个导航条目下发 [LocalPageIsForeground] —— "本页此刻是不是返回栈栈顶".
 *
 * 判据直接来自 [backStack]: 栈顶那个路由的 contentKey 与本条目的相同就是在前台. 因此它与组合是否
 * 存活、转场走到哪一帧都无关 —— 这正是 Nav2 时代大家从条目 lifecycle 拿到、而 Nav3 不再提供的那个
 * 信号, 理由与踩过的坑见 [LocalPageIsForeground] 的文档.
 *
 * **不能拿路由对象直接比**: 条目手上只有 [NavEntry.contentKey], 路由对象取不到 (`key` 没有 getter).
 * 于是拿栈顶路由现造一个空 [NavEntry] 问它的 contentKey —— 库自己的默认规则由它算, 与
 * `entryProvider { entry<X> { } }` 建出来的条目必然一致 (库内部那个 `defaultContentKey` 是
 * internal, 照抄它的实现就成了随时会被上游改掉的暗坑). 每次返回栈变化才造一个, 开销可以忽略.
 */
@Composable
fun <T : Any> rememberPageForegroundNavEntryDecorator(backStack: List<T>): NavEntryDecorator<T> {
    val currentBackStack = rememberUpdatedState(backStack)
    return remember {
        NavEntryDecorator { entry ->
            // derivedStateOf: 返回栈每变一次只重算一次, 且只有真的翻转才通知读者;
            // 每个条目一份, remember 在条目自己的组合里 (contentKey 变了就是另一个页面)
            val isForeground = remember(entry.contentKey) {
                derivedStateOf {
                    val top = currentBackStack.value.lastOrNull() ?: return@derivedStateOf true
                    NavEntry(top) {}.contentKey == entry.contentKey
                }
            }
            CompositionLocalProvider(LocalPageIsForeground provides isForeground) {
                // TV 背景放大转场期间, 放大层整屏不透明地盖在最上面 (见 TvHeroZoomHandoff.covering): 被盖住的页整层
                // 不画 —— alpha 0 的节点 HWUI 直接跳过, 省下它每帧的全屏底色与其余内容 (4K 下一次全屏填充约 2~3ms).
                // 在绘制阶段读, 翻转只改这一层的属性, 不重组页面. propagateMinConstraints: 这层 Box 对测量完全透明
                Box(
                    Modifier.graphicsLayer {
                        alpha = if (TvHeroZoomHandoff.covering && !isForeground.value) 0f else 1f
                    },
                    propagateMinConstraints = true,
                ) {
                    entry.Content()
                }
            }
        }
    }
}
