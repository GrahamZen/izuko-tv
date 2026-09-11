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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.tv.TV_HERO_ZOOM_NAV_HOLD_MILLIS
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
    // 栈顶条目的 contentKey: 放大进来的详情页还在栈顶时, 下面的条目接着不画 (见 TvHeroZoomHandoff.coverEntryKey)
    val topKey = remember {
        derivedStateOf { currentBackStack.value.lastOrNull()?.let { NavEntry(it) {}.contentKey } }
    }
    // coverEntryKey 只在放大转场那一段有用 (之后列表页已被移出组合); 过了导航转场的时长就清掉, 不留到以后.
    // 否则同一个详情页日后以交叉淡入再进来时 (栈顶 key 又对上了), 淡入期间下面的列表页会被当成"被盖住"藏掉
    LaunchedEffect(Unit) {
        snapshotFlow { TvHeroZoomHandoff.coverEntryKey }.collectLatest { key ->
            if (key == null) return@collectLatest
            delay(TV_HERO_ZOOM_NAV_HOLD_MILLIS + 500L)
            if (TvHeroZoomHandoff.coverEntryKey == key) TvHeroZoomHandoff.coverEntryKey = null
        }
    }
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
                // 真页接手后放大会话就结束了, 但导航转场还撑着列表页到 ~+700ms: 放大进来的详情页仍在栈顶期间接着不画
                // (coverEntryKey, 详情页此时已不透明). 往前跳 / 返回时栈顶一变就不成立
                Box(
                    Modifier.graphicsLayer {
                        val covered = TvHeroZoomHandoff.covering ||
                                TvHeroZoomHandoff.coverEntryKey?.let { it == topKey.value } == true
                        alpha = if (covered && !isForeground.value) 0f else 1f
                    },
                    propagateMinConstraints = true,
                ) {
                    entry.Content()
                }
            }
        }
    }
}
