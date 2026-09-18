/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * 进页**落定之前**不许"焦点落位"拉动画面.
 *
 * 症状: 从播放页的角色面板点声优的头像进人物页, **有概率**整个画面被往下拽一截 (用户 2026-09-17).
 *
 * 成因是一条链, 每一环单看都合理:
 *
 * 1. 全屏页里有一批是从移动端直接迁过来的 (人物 / 角色详情、播放记录、缓存详情、条目缓存、
 *    Bangumi 合并), 它们**没有 TV 的进页落点** —— 没有任何锚点会在进页时把焦点送到某处;
 * 2. 于是焦点由 `AniAppContent` 的全局兜底送: `requestFocus()` 打在不可聚焦的 focusGroup 上,
 *    Compose 走 `findChildCorrespondingToFocusEnter`, 它把 `Enter` **转成 Right**、从容器左边缘
 *    起做 2D 搜索, 判决权重是 `13 × 主轴距离² + 次轴距离²`, 而**次轴取两个矩形中心之差** ——
 *    也就是说它挑的是"偏左且纵向靠近页面中心"的那个元素, 不是最顶上那个;
 * 3. 谁能入选取决于**那一刻哪些数据已经到了**: 人物页的大图要等 URL 才 `clickable`, 出演 / 出演作品
 *    那几条要等分页数据才有卡片. 兜底让位 15 帧后每 100ms 重试一次, 正好打在这个变化中的结构上 ——
 *    这就是"有概率"的来源;
 * 4. 选中的元素一旦在首屏之外, `Modifier.focusable` 自带的 bring-into-view 请求就把滚动容器拉过去.
 *    页面看着就是"进来的一瞬间被往下拽了一下".
 *
 * **兜底送焦不是用户的意图**, 它只是"总得有个东西拿着焦点否则方向键全失效"的补救; 用它的落点去
 * 决定页面停在哪一段, 纯属副作用. 所以这里把这条副作用掐掉: 页面刚进来、用户还没按过任何键之前,
 * 焦点落位算出来的滚动距离一律是 0.
 *
 * **只掐"焦点引起的滚动"这一条路**. 页面自己的定位 (`scrollTo` / `animateScrollToItem` / 区块吸附)
 * 都是直接调滚动状态, 不经 [BringIntoViewSpec], 不受影响; 自己 provide 了 spec 的子树
 * (锚位条 [tvAnchorBringIntoViewSpec] 那一族) 内层覆盖外层, 也不受影响.
 *
 * 窗口开得很短 —— 用户一按键, 或者焦点落定后 [GUARD_RELEASE_FRAMES] 帧, 立刻交还控制权, 之后
 * 一切照旧. 焦点落定就放开是因为兜底成功那一下正是要挡的那一下, 挡完就没有再挡的理由了.
 */
@Stable
class TvEntryScrollGuard internal constructor(
    private val default: BringIntoViewSpec,
) {
    private var armed by mutableStateOf(true)
    internal var focusArrived by mutableStateOf(false)

    /** 换页面时重新布防. */
    internal fun arm() {
        armed = true
        focusArrived = false
    }

    /** 交还控制权: 用户按了键, 或焦点已落定并过了让位帧. */
    fun disarm() {
        armed = false
    }

    /**
     * 装在 `LocalBringIntoViewSpec` 上的门控 spec.
     *
     * [BringIntoViewSpec.calculateScrollDistance] 不在组合里调用, 读 [armed] 只是读一次快照值 ——
     * 不会 (也不需要) 触发重组: 门开关的那一刻本来就没有待处理的请求, 而 `ContentInViewNode`
     * 每帧都会重新调它.
     */
    val spec: BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            if (armed) 0f else default.calculateScrollDistance(offset, size, containerSize)
    }
}

/**
 * 焦点落定后再等几帧才交还控制权: 兜底送焦那一下是同步的, 但它引起的 bring-into-view 请求要到
 * 下一帧才被 `ContentInViewNode` 算距离. 只等一帧在慢设备上偶尔来不及, 两帧足够且短到看不出来.
 */
private const val GUARD_RELEASE_FRAMES = 2

/**
 * 建一个 [TvEntryScrollGuard], 每换一个页面 ([entryKey] 变化) 重新布防.
 *
 * @param enabled false (非焦点驱动的平台) 时返回 null, 调用方按原样走默认 spec.
 */
@Composable
fun rememberTvEntryScrollGuard(entryKey: Any?, enabled: Boolean): TvEntryScrollGuard? {
    if (!enabled) return null
    val default = LocalBringIntoViewSpec.current
    val guard = remember(default) { TvEntryScrollGuard(default) }
    LaunchedEffect(guard, entryKey) { guard.arm() }
    return guard
}

/**
 * 把 [guard] 的解除条件接到导航容器上: 焦点落进来 (等几帧) 或用户按键, 两者谁先到都算落定.
 *
 * 挂在 `NavDisplay` 上 —— 按键预览只在焦点路径上触发, 而页面内的焦点一定经过它.
 */
@Composable
fun Modifier.tvEntryScrollGuard(guard: TvEntryScrollGuard?): Modifier {
    if (guard == null) return this
    LaunchedEffect(guard, guard.focusArrived) {
        if (!guard.focusArrived) return@LaunchedEffect
        repeat(GUARD_RELEASE_FRAMES) { withFrameNanos { } }
        guard.disarm()
    }
    return this
        // 用户按了键就是用户在导航了, 从这一刻起滚动都是他要的
        .onPreviewKeyEvent {
            guard.disarm()
            false
        }
        .onFocusChanged { if (it.hasFocus) guard.focusArrived = true }
}
