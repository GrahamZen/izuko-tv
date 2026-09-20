/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import me.him188.ani.app.ui.foundation.focus.tvSwallowKeysWhenLeaving
import me.him188.ani.app.ui.foundation.focus.tvSwallowPointerWhenLeaving
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import me.him188.ani.app.ui.foundation.navigation.LocalNavEntryContentKey
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
    // 放大进来的详情页一离开栈顶 (返回 / 缩回 / 往前跳) 就清掉: 否则 1.2s 内又点进同一条目时栈顶 key 又对上, 放大还没起跑
    // (图还在加载, 放大层透明) 列表页就先被藏掉 —— 整屏黑一下 (2026-09-14 用户: 缩回之后马上再进去闪黑)
    LaunchedEffect(Unit) {
        snapshotFlow { topKey.value }.collect { top ->
            // 缩回出栈前要核对"栈顶仍是发起缩回的那一页" (见 TvHeroZoomHandoff.topEntryKey)
            TvHeroZoomHandoff.topEntryKey = top
            // 放大进来的详情页一离开栈顶, 下面那页就得重新开始画 (见 releaseStartedExcept)
            TvHeroZoomHandoff.releaseStartedExcept(top)
            val k = TvHeroZoomHandoff.coverEntryKey
            if (k != null && k != top) TvHeroZoomHandoff.coverEntryKey = null
        }
    }
    // 放大进来的详情页条目记录 (TvHeroZoomHandoff.isZoomEntry) 只留还在栈里的
    LaunchedEffect(Unit) {
        snapshotFlow { currentBackStack.value.map { NavEntry(it) {}.contentKey }.toSet() }.collect { TvHeroZoomHandoff.retainEntries(it) }
    }
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
                    // 返回缩回期间 (TvHeroZoomHandoff.shrinking) 回到栈顶的列表页先不算"在前台": 它回前台要做的活 (焦点落回卡片、
                    // 聚焦动画、整页重录) 挪到缩回结束硬切回来那一帧, 不落在缩回途中. 缩回只有两三百毫秒, 期间的按键由缩回层吞掉
                    // 快速路径只藏不销毁的详情页 (TvHeroZoomHandoff.shrinkHideKey) 直到出栈都不算前台: 落地后它还在栈顶, 不能又变回前台
                    NavEntry(top) {}.contentKey == entry.contentKey && !TvHeroZoomHandoff.shrinkMoving &&
                            TvHeroZoomHandoff.shrinkHideKey != entry.contentKey
                }
            }
            CompositionLocalProvider(
                LocalPageIsForeground provides isForeground,
                LocalNavEntryContentKey provides entry.contentKey,
            ) {
                // 返回缩回时出栈的详情页: 移出组合之前一直不画 (见 TvHeroZoomHandoff.shrinkHiddenKey), 销毁时清掉标记
                DisposableEffect(entry.contentKey) {
                    onDispose {
                        if (TvHeroZoomHandoff.shrinkHiddenKey == entry.contentKey) TvHeroZoomHandoff.shrinkHiddenKey = null
                        if (TvHeroZoomHandoff.shrinkHideKey == entry.contentKey) TvHeroZoomHandoff.shrinkHideKey = null
                    }
                }
                // TV 背景放大转场期间, 放大层整屏不透明地盖在最上面 (见 TvHeroZoomHandoff.covering): 被盖住的页整层
                // 不画 —— alpha 0 的节点 HWUI 直接跳过, 省下它每帧的全屏底色与其余内容 (4K 下一次全屏填充约 2~3ms).
                // 在绘制阶段读, 翻转只改这一层的属性, 不重组页面. propagateMinConstraints: 这层 Box 对测量完全透明
                // 真页接手后放大会话就结束了, 但导航转场还撑着列表页到 ~+700ms: 放大进来的详情页仍在栈顶期间接着不画
                // (coverEntryKey, 详情页此时已不透明). 往前跳 / 返回时栈顶一变就不成立
                // 返回缩回时出栈的详情页 (见 TvHeroZoomHandoff.shrinkHiddenKey): **不再组合** —— 放大途中出栈时 Nav3 会让它在组合里
                // 再留一阵 (倒着走完进页转场), 只藏起来的话它的重组 / 分帧组合 / 预画 / 数据到达都还在跑, 正落在缩回那几帧里.
                // 移出组合的开销落在缩回起步前那一帧 (静止的图上). 同一条目又回到栈顶 (马上又点进来) 就照常重建
                val shrunkAway by remember(entry.contentKey) {
                    derivedStateOf { TvHeroZoomHandoff.shrinkHiddenKey == entry.contentKey && !isForeground.value }
                }
                Box(
                    Modifier.graphicsLayer {
                        // && scrimOpaque: 放大起跑后底色是**渐入**的 (见 TvHeroZoomHandoff.Session.scrimAlpha), 那一段
                        // 下面的列表页必须接着画. coverEntryKey 在 Session.start() 那一刻就设上, 不一起把关的话
                        // 列表页照样第一帧就被藏掉, 渐入等于白做 (2026-09-16 用户: "遮罩还是一开始就整个出现")
                        val covered = (TvHeroZoomHandoff.covering ||
                                TvHeroZoomHandoff.coverEntryKey?.let { it == topKey.value } == true) &&
                                TvHeroZoomHandoff.scrimOpaque
                        // 返回缩回期间 (TvHeroZoomHandoff.shrinking) 回到栈顶的列表页也不画, 缩进框里那一帧硬切回来
                        // 缩回运动中两页都不画; 落地后 (等列表页就绪的那段) 列表页照常画在缩回层下面, 撤层时已经画好
                        // 缩回尾段底色化开时 (shrinkRevealing) 下面那页提前画出来; 栈顶那页照旧由上面两项藏着
                        val revealingUnder = TvHeroZoomHandoff.shrinkRevealing && !isForeground.value
                        alpha = if (
                            shrunkAway || TvHeroZoomHandoff.shrinkHideKey == entry.contentKey ||
                            (covered && (!isForeground.value || TvHeroZoomHandoff.shrinkMoving) && !revealingUnder)
                        ) 0f else 1f
                    }
                        // 不在栈顶的页 (退场淡出中 / 刚被新页盖住) 吞掉按键, 返回类键除外: 转场期间它还在组合里, 焦点常常还停在
                        // 它的按钮上, 按确认会点到看不见的东西 (2026-09-14 用户: 详情页按返回、淡出途中立刻按确认, 进了播放).
                        // 返回 / Esc / 手柄 B 放行: 转场中连按返回照常逐层退 (见 tvSwallowKeysWhenLeaving)
                        // 返回缩回**运动的那一段**吞: 那时列表页虽已算前台却还被缩回层盖着, 放行的话方向键 / 确认落在
                        // 看不见的列表页上, 能再点进详情页或播放器 (2026-09-15 审查). 返回键由缩回层自己吞.
                        //
                        // **落地之后不再吞** (shrinkMoving 而不是 shrinking): 缩到位到撤层之间还有一段等列表页就绪的时间
                        // (见 TV_HERO_SHRINK_READY_TIMEOUT_MILLIS), 画面这时已经是列表页了, 再吞就是白吃用户的按键
                        // (用户 2026-09-16: "返回过程中点确认会吞"). 与详情页那边"闸只管节奏、不吃输入"同一条原则
                        .tvSwallowKeysWhenLeaving { !isForeground.value || TvHeroZoomHandoff.shrinkMoving }
                        // 指针同理: 快速路径的详情页只是藏起来 (组合与命中测试都在), 触屏 / 鼠标设备上
                        // 这两三百毫秒里点得到看不见的播放按钮 (2026-09-15 审查). 与上面同一判据
                        .tvSwallowPointerWhenLeaving { !isForeground.value || TvHeroZoomHandoff.shrinkMoving },
                    propagateMinConstraints = true,
                ) {
                    if (!shrunkAway) entry.Content()
                }
            }
        }
    }
}
