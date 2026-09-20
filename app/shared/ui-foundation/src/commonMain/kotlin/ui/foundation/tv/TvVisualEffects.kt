/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings

/**
 * TV 上跑马灯 (`basicMarquee`) 的滚动次数: 视觉效果完整档无限滚, 其余档滚 [TV_REDUCED_MARQUEE_ITERATIONS] 次后停住.
 *
 * 一直在滚的跑马灯让页面进不了静止态 (每帧都要出一帧), 属于"一直在动的装饰", 与占位脉动同一档 (见 TvVisualEffectsLevel).
 * 播放器片尾「接下来播放」自动展开选集条时尤其要紧: 焦点落在长集名的卡上, 无限滚会把叠在视频上的界面一直顶到
 * 60fps (最长 ~90s), 而倒计时环本身只要 ~10fps.
 */
@Composable
private fun tvAmbientMarqueeIterations(): Int =
    if (LocalThemeSettings.current.visualEffects.ambient) Int.MAX_VALUE else TV_REDUCED_MARQUEE_ITERATIONS

/**
 * 按档挂跑马灯: 流畅档**不挂** (见 `TvVisualEffectsLevel.marquee`), 其余档按
 * [tvAmbientMarqueeIterations] 的趟数滚。
 *
 * 用它而不是直接 `basicMarquee(iterations = …)`: iterations 给 0 仍会走 `basicMarquee` 的测量
 * (它每次都要把整串文字按不换行量一遍来判断要不要滚), 而那正是弱机上要省的那一笔。
 *
 * @param enabled 调用方自己的条件 (如"聚焦时才滚"), 与档位是与的关系.
 * @param iterations 指定趟数, 覆盖 [tvAmbientMarqueeIterations] 的按档取值 —— 给"聚焦时才滚"
 *   那类本来就写死无限滚的调用点用: 它们只被流畅档砍掉, 其余档行为不变.
 */
@Composable
fun Modifier.tvAmbientMarquee(enabled: Boolean = true, iterations: Int? = null): Modifier {
    if (!enabled || !LocalThemeSettings.current.visualEffects.marquee) return this
    return this.basicMarquee(iterations = iterations ?: tvAmbientMarqueeIterations())
}

private const val TV_REDUCED_MARQUEE_ITERATIONS = 3

/**
 * 焦点滚动要不要动画: 流畅档瞬时跳位, 见 `TvVisualEffectsLevel.animatedScroll`.
 *
 * 与 [tvAmbientMarqueeIterations] 放在一处读设置 —— 两者都是"按档砍掉一直在动的东西".
 */
@Composable
fun tvAnimatedScroll(): Boolean = LocalThemeSettings.current.visualEffects.animatedScroll

/**
 * 内容替换要不要过渡 (背景图交叉淡入、hero 文字进出、压暗/渐变插值): 均衡档起, **流畅档直接换**.
 *
 * 砍它的理由与 [tvAnimatedScroll] 同源, 但量更大: 换一次 hero 会同时起 600ms 的背景交叉淡入与
 * 500ms 的文字进出, 两者都长过遥控器连发间隔 (250ms) —— 索尼实测 12 次方向键**原样 98 帧、
 * 时长改 0 后 12 帧**, 每按一格陪跑约 8 帧, 而那 8 帧画的全是"新旧两份在互相淡入淡出"
 * (期间两块整屏图层 + 两棵 CJK 文本树同时活着). 连发有换挡合并兜着 (见 `rememberTvSettledHero`),
 * 单击这一路没有, 正是报告者"单按上下键 127-140ms"的那一份.
 *
 * 终态逐像素一致, 砍掉的只有中间过程.
 *
 * **背景图的交叉淡入是例外, 不归它管** (2026-09-19 回归): `TvModulatedCrossfade` 用 snap 时
 * `transition.currentState` 下一帧就等于目标, 那段"不在动画中只留目标那一张"会**当帧移除旧图**,
 * 而新图还在下载解码 —— 屏幕上空出一段没有背景的窗口, 观感是"hero 背景出来得慢". 那 600ms 不只是
 * 装饰, 它顺带把旧图撑到新图就位. 要砍它得先给 `TvModulatedCrossfade` 一个"新内容已经有像素了"
 * 的信号, 在那之前一律保持淡入.
 */
@Composable
fun tvContentSwapAnimated(): Boolean = LocalThemeSettings.current.visualEffects.transitions

/** 按档取过渡规格: 流畅档 [snap] (下一帧就是终值), 其余档用 [spec]. 见 [tvContentSwapAnimated]. */
@Composable
fun <T> tvSwapSpec(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (tvContentSwapAnimated()) spec else snap()
