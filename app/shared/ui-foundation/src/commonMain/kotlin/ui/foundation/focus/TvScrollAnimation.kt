/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import kotlin.math.abs
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * TV 焦点驱动滚动的动画器: 替代 [LazyListState.animateScrollToItem], 补上它缺的两样东西 ——
 * **可感知的时长**与**连发时的速度连续性**.
 *
 * 为什么不用自带的: `animateScrollToItem` 没有动画参数, 对可见目标用默认刚度 spring,
 * 单格距离 ~100-150ms 就结束 —— 低于人眼能看清运动过程的时长 (~200ms), 观感是"闪现到位",
 * 反而像掉帧. 且连按方向键时每次都从零速重启, 快速导航是"顿-冲-顿-冲"而不是连续流动.
 *
 * 本动画器的做法 (对齐 Leanback `GridLinearSmoothScroller` 的手感):
 * - 单格 ~260ms 的低刚度 spring, 快速启动、减速停靠, 运动过程可见;
 * - 动画被新目标取消时把**当前速度**带进下一段 ([velocity]), 连发时列表匀速流动,
 *   松手后自然减速停靠 —— 这是与"单纯调慢"的本质区别, 后者在连发下会积压卡顿;
 * - spring 对更远的目标自动提速, 连发追赶不积压.
 *
 * 用法: 每个列表 (每个 [LazyListState]/[LazyGridState]) 配一个实例, 生命周期跟随驱动滚动的
 * 协程 (effect 内 `val` 或组件级 `remember` 皆可, 重建只是把继承速度归零). 与其他滚动共享
 * scroll mutex: 新动画自动取消旧的.
 *
 * 成本: 与原实现逐帧工作量相同 (布局位移+重绘), 只是同样的位移摊到更多帧; 动画结束即静止,
 * 不产生常驻负载.
 */
@Stable
class TvScrollAnimator(
    /** false = 瞬时跳位, 不跑 spring (流畅档, 见 TvVisualEffectsLevel.animatedScroll). */
    private val animated: Boolean = true,
) {
    /** 上一段动画被取消那一刻的速度 (px/s), 作为下一段的初速; 自然停靠后归零. */
    private var velocity = 0f

    /**
     * 平滑滚动到 [index], 停靠位由 [scrollOffset] 微调, **符号语义与
     * [LazyListState.animateScrollToItem] 一致**: 正值 = 向列表起点方向多滚 (条目停在
     * 起点线的起点侧, 部分收进留白/视口外), 负值 = 停在起点线之后.
     * 目标不在组合中 (远跳, 如恢复焦点) 时回退自带实现 —— 那类场景本就该快进.
     *
     * 动画路径的停靠条件是 `item.offset == -scrollOffset` (原生停靠后的 layout offset
     * 即为 -scrollOffset). 此前这里写成 `== scrollOffset`, 与回退路径的原生语义正好相反 ——
     * 选集轮播 (唯一非零调用方) 的吸附实际停在了与注释相反的一侧, 固定聚焦框按注释
     * 放位后对不上卡片才暴露出来.
     */
    suspend fun animateScrollToItem(state: LazyListState, index: Int, scrollOffset: Int = 0) {
        if (!animated) {
            // 流畅档: 一步到位, 不产生中间帧 (见 TvVisualEffectsLevel.animatedScroll)
            velocity = 0f
            state.scrollToItem(index, scrollOffset)
            return
        }
        val target = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (target == null) {
            velocity = 0f
            state.animateScrollToItem(index, scrollOffset)
            return
        }
        animateBy(state, (target.offset + scrollOffset).toFloat())
    }

    /** [LazyGridState] 版, 语义同上; 取主轴 offset. */
    suspend fun animateScrollToItem(state: LazyGridState, index: Int, scrollOffset: Int = 0) {
        if (!animated) {
            // 流畅档: 一步到位, 不产生中间帧 (见 TvVisualEffectsLevel.animatedScroll)
            velocity = 0f
            state.scrollToItem(index, scrollOffset)
            return
        }
        val info = state.layoutInfo
        val target = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (target == null) {
            velocity = 0f
            state.animateScrollToItem(index, scrollOffset)
            return
        }
        val mainAxisOffset = if (info.orientation == Orientation.Vertical) target.offset.y else target.offset.x
        animateBy(state, (mainAxisOffset + scrollOffset).toFloat())
    }

    private suspend fun animateBy(state: ScrollableState, distance: Float) {
        if (abs(distance) < 0.5f) {
            velocity = 0f
            return
        }
        // **反向的继承初速必须丢弃**: [velocity] 是上一段被取消时留下的, 用途是连按同方向时
        // 的速度连续. 若这一段的目标在反方向, 带着它启动会让第一帧朝反方向滚 —— 而列表此刻
        // 往往正停在那一侧的边界上 (最典型的一条路: 焦点退回 hero 时那段往回滚的动画被回顶
        // 动画抢占取消, 残速朝上; 用户紧接着按下键进卡片区, 列表已被回顶动画放在顶部), 反
        // 方向滚不动, consumed=0, 下面那条边界判据当场 cancelAnimation: 整段动画 25ms 内收场
        // 且一格没动, 而固定聚焦框钉在锚位 —— 看着就是"框比卡片高出一截".
        // (2026-09-20 真机日志钉死: dist=72 的一次 25ms 就返回, first 始终 0/0.)
        val initialVelocity = if (velocity * distance < 0f) 0f else velocity
        state.scroll {
            var consumedTotal = 0f
            AnimationState(initialValue = 0f, initialVelocity = initialVelocity).animateTo(
                targetValue = distance,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = TV_SCROLL_STIFFNESS,
                    visibilityThreshold = 0.5f,
                ),
            ) {
                val delta = value - consumedTotal
                val consumed = scrollBy(delta)
                consumedTotal += consumed
                // 每帧记录: 协程被新目标取消时, 这就是带进下一段的初速
                this@TvScrollAnimator.velocity = this.velocity
                // 碰到列表边界 (位移被 clamp), 剩余距离滚不动, 提前收尾
                if (abs(delta - consumed) > 0.5f) cancelAnimation()
            }
        }
        // 自然跑完 (或撞边界) 即静止; 只有中途被取消才保留速度 (上面的赋值), 不会执行到这里
        velocity = 0f
    }
}

/**
 * pivot 式 [BringIntoViewSpec] (androidx.tv 弃用 TvLazyList 后的官方迁移做法, 替代
 * `PivotOffsets`): 聚焦项一律滚到距容器起始边 [anchorPx] 的**锚位**, 而不是默认的
 * "最小滚动到可见" (后者让聚焦项在容器内游走, 做不了固定聚焦框).
 *
 * 用法: 普通 LazyRow/LazyColumn + `CompositionLocalProvider(LocalBringIntoViewSpec provides …)`,
 * 滚动**不要手写**: 卡片一聚焦 (`Modifier.focusable` 自带 bring-into-view 请求) 框架就滚到位.
 * [calculateScrollDistance] 每帧重算, 框架的 `UpdatableAnimationState` 据此自动改目标、
 * 速度连续 —— 连按方向键时列表连续流动 (leanback 手感), 无需手写速度继承/取消.
 *
 * [anchorPx] = 内容的 rest 位置 (即该轴的 `contentPadding` 起始值) **加上"卡片外框到焦点
 * 目标矩形的偏差"**: bring-into-view 拿到的是焦点目标 (可聚焦节点) 的矩形, 若卡片的可聚焦
 * 节点比卡片外框内缩了一圈 (如探索页竖版卡的封面内缩 `TvFocusRing.Gap`), 不补这一段,
 * 固定聚焦框与卡片就会差那么多对不齐, 且首项永远停不到 rest 位置.
 *
 * [BringIntoViewSpec.scrollAnimationSpec] 在 Compose 1.10 已标记弃用但仍被 `ContentInViewNode`
 * 读取 (定制动画曲线目前只有这一条路); 若未来版本移除, pivot 定位仍工作, 只是退回默认
 * spring —— 到时再评估手感. 曲线与 [TvScrollAnimator] 共用 [TV_SCROLL_STIFFNESS].
 */
/**
 * 一个**什么都不滚**的 [BringIntoViewSpec]: 焦点进入时框架不自动滚动, 由调用方自己按焦点索引
 * 用 [TvScrollAnimator] 驱动.
 *
 * 为什么要绕开框架: `BringIntoViewSpec.scrollAnimationSpec` 从 **Compose 1.12 起被彻底忽略**
 * ("Animation spec customization is no longer supported" —— 1.11 只是标废弃, 仍生效)。框架改用
 * 自己的默认动画: 快、没有减速停靠, 与 [TvScrollAnimator] 那条路的手感分叉。2026-09-20 实测把
 * 刚度从 260f 改到 40f (理论上慢 2.5 倍) 画面毫无变化, 定案; 官方没有替代 API, TV 文档如今也
 * 只讲 `calculateScrollDistance` ("滚到哪"), 不再谈"怎么滚"。
 *
 * **试过但不可行的路**: 让 spec 算出距离、返回 0, 自己按那个距离跑动画。框架在自滚期间每帧都会
 * 回调, 要从一串相对距离里分辨"这是我自己滚出来的"还是"用户换目标了", 信息不够 ——
 * 按收敛性判 (距离变大就算换目标) 在 LazyRow 首次测量时误判, 真机是"第一轮每隔两张卡跟不上一次,
 * 滑到底回来就再也不犯"; 把阈值放大到半个卡片宽又会漏掉真正的换目标。按**焦点索引**驱动没有这个
 * 歧义: 索引变了就是换目标, 没变就不滚。
 */
val TvNoBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

fun tvAnchorBringIntoViewSpec(anchorPx: Float, animated: Boolean = true): BringIntoViewSpec =
    if (!animated) {
        // 流畅档: 瞬时跳到锚位 (见 TvVisualEffectsLevel.animatedScroll)。snap 之后一次按键只产生一帧
        // 内容变化, 不再是 spring 那二十几帧"每帧重新测量可见项"
        object : BringIntoViewSpec {
            @Deprecated("Animation spec customization is no longer supported.")
            override val scrollAnimationSpec: AnimationSpec<Float> = snap()

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - anchorPx
        }
    } else
    object : BringIntoViewSpec {
        // visibilityThreshold 与 [TvScrollAnimator] 同为 0.5px: 默认 0.01px 让 spring 在肉眼已经停下之后
        // 再拖 ~170ms 的尾巴 (2026-09-09 Shield 录屏: 卡片 2.55s 停, isScrollInProgress 2.72s 才 false),
        // 而低特效档的 hero 文字要等它变 false 才出现, 这段尾巴全算在"按了键文字才慢慢出来"的等待里.
        @Deprecated("Animation spec customization is no longer supported.")
        override val scrollAnimationSpec: AnimationSpec<Float> =
            spring(stiffness = TV_SCROLL_STIFFNESS, visibilityThreshold = 0.5f)

        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            offset - anchorPx
    }

/**
 * 焦点滚动 spring 刚度: 决定"单格滚动多久" (质量 1, 临界阻尼下停靠时间 ≈ 4/√stiffness 秒).
 * 调大更快更利落, 调小更慢更从容; Leanback 的参照区间是单格 200-250ms. 只调这里, 全部 TV 焦点
 * 滚动统一手感 —— 除本动画器 (网格吸顶/选集轮播) 外, public 也给探索页那套官方 pivot 式
 * `BringIntoViewSpec` 的 `scrollAnimationSpec` 用, 两条路径同一条曲线.
 *
 * 260 ≈ 肉眼停靠 ~400ms@Shield. 2026-09-10 试过 550 (~190ms, 对齐 Prime 单格 ~180ms), 用户先说可以、
 * 用了一阵说"太快了, 会闪", 改回 260 —— **别再为了让 hero 文字早点出现去提刚度**, 文字的等待已经压到
 * 停稳判据的最短 (TvScrollActivity), 背景图不等停稳. **用户手调过, 改前先问.**
 */
const val TV_SCROLL_STIFFNESS = 260f
