/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff

/**
 * TV 的导航布局: 放大进来的详情页 ([TvHeroZoomHandoff.decideZoomEntry]) **叠在来源列表页上面**, 列表页常驻组合、垫在下面不画;
 * 其余一律单页 (与库的 SinglePaneScene 一样).
 *
 * 为什么: 放大完成后 (列表页原本 ~700ms 后被移出组合) 按返回, 缩回落地后列表页要整页重建 —— 索尼上 0.5~0.7s 没有卡片; 放大途中返回
 * 列表页还在, 落地即回. 保留数据 (ViewModel / 分页) 省不掉 UI 树重建, 只有让真实的列表页一直在组合里 (2026-09-15 用户要"放大完成后返回也
 * 接近中途返回", 采纳外部分析的方案). 顺带去掉了"列表页被移出"那一帧 (索尼上撞缩回 / 撞进页后第一次翻页的那次卡).
 *
 * **只有列表页、与叠着详情页, 必须是同一个布局类 + 同一个 key** (列表页的 contentKey): NavDisplay 按 (类, key) 区分布局, 一样就当作同一个
 * 布局, 不做转场, 列表页原地不动 —— 换了类或 key, 列表页要从一个布局挪到另一个, 还带一次转场. 所以本策略接管全部布局, 不回落到库的单页布局.
 * 代价: 进出这种详情页没有导航转场 (放大 / 缩回层自己画; 缩不了时详情页自己淡出, 见详情页的返回).
 *
 * 被盖住的列表页: 放大真起跑过才不画 ([TvHeroZoomHandoff.startedEntryKeys]; 没起跑就放弃的, 详情页淡入, 下面要看得见); 焦点不许进
 * (焦点组 onEnter 拒绝, 不停用已在树里的节点, 见焦点守则); 触摸吞掉; 按键由页面装饰器按"不在栈顶"吞; 页内的轮播等按 LocalPageIsForeground 暂停.
 *
 * 本布局位置第一次组合时就已经叠着详情页 (从播放器 / 别的详情页返回到放大进来的详情页, 旧布局早已销毁): 列表页**不跟着建** —— 那一刻正是
 * 详情页在回来的转场里, 再在下面整页组合一份列表页只会卡. 这时返回走缩回的重建路径 (落地后出栈、列表页在层下重建、就绪再撤层).
 */
internal class TvZoomStackSceneStrategy<T : Any> : SceneStrategy<T> {
    // 只读判定结果 (入栈前已由 NavigationHooks 判过): 导航库会反复计算布局、也会算历史布局, 这里不能有副作用
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val top = entries.lastOrNull() ?: return null
        val below = entries.getOrNull(entries.lastIndex - 1)
        val previous = entries.dropLast(1)
        if (below != null && TvHeroZoomHandoff.isZoomEntry(top.contentKey)) {
            return TvZoomStackScene(below.contentKey, below, top, previous)
        }
        return TvZoomStackScene(top.contentKey, top, null, previous)
    }
}

private class TvZoomStackScene<T : Any>(
    override val key: Any,
    private val base: NavEntry<T>,
    private val overlay: NavEntry<T>?,
    override val previousEntries: List<NavEntry<T>>,
) : Scene<T> {
    override val entries: List<NavEntry<T>> = listOfNotNull(base, overlay)

    override val content: @Composable () -> Unit = { Content() }

    @Composable
    private fun Content() {
        // 本布局位置 (同一个 key) 从只有列表页起步的, 列表页一直在; 一上来就叠着详情页的, 列表页等到详情页出栈才建 (见类注释)
        val holder = remember { BaseAlive(overlay == null) }
        if (overlay == null) holder.alive = true
        val covered by rememberUpdatedState(overlay != null)
        val overlayKey = overlay?.contentKey
        Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
            if (holder.alive) {
                Box(
                    Modifier
                        .graphicsLayer {
                            // 详情页在快速路径缩回里只藏着 (shrinkHideKey) 时列表页照常画 —— 运动中由页面装饰器按"被缩回盖着"藏, 落地露出来
                            // 底色渐入 / 化开那两段先别藏: 放大层此时还是半透明的, 藏了就等于硬切.
                            //
                            // **[shrinkRevealing] 这一项原来漏了**: 缩回有 keep / 非 keep 两条路径, 走 keep 时列表页
                            // 碰巧常驻着 (shrinkHideKey 那一项放行), 底色化开就能看到介绍文字慢慢露出来; 走非 keep 时
                            // 列表页整段被藏着, 化开露出来的是黑的 —— 同一个包时灵时不灵, 取决于当时走哪条路
                            // (2026-09-16 用户: "有一个版本能正确看到返回时慢慢露出介绍文字").
                            alpha = if (
                                overlayKey != null && overlayKey in TvHeroZoomHandoff.startedEntryKeys &&
                                TvHeroZoomHandoff.shrinkHideKey != overlayKey && TvHeroZoomHandoff.scrimOpaque &&
                                !TvHeroZoomHandoff.shrinkRevealing
                            ) 0f else 1f
                        }
                        .focusProperties { onEnter = { if (covered) cancelFocusChange() } }
                        .focusGroup()
                        .then(if (overlay != null) Modifier.swallowPointer() else Modifier),
                    propagateMinConstraints = true,
                ) {
                    base.Content()
                }
            }
            overlay?.Content()
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TvZoomStackScene<*> && key == other.key && base == other.base && overlay == other.overlay &&
                previousEntries == other.previousEntries

    override fun hashCode(): Int = ((key.hashCode() * 31 + base.hashCode()) * 31 + overlay.hashCode()) * 31 + previousEntries.hashCode()

    override fun toString(): String = "TvZoomStackScene(key=$key, overlay=${overlay?.contentKey})"
}

private class BaseAlive(var alive: Boolean)

/** 被盖住的列表页不接触摸 (平板触屏): 详情页没接住的点按不能落到下面看不见的卡片上. */
private fun Modifier.swallowPointer(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
