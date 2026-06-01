/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.collectLatest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 本页 (当前这个导航条目) 此刻是不是返回栈栈顶 —— 也就是用户正看着它, 而不是被压在别的页面下面.
 *
 * 由 `rememberPageForegroundNavEntryDecorator` 逐条目下发 (见 `AniAppContent`); 没有下发的场合
 * (预览、测试、非 NavDisplay 宿主) 恒为 `true`.
 *
 * **为什么不用页面自己的 `LocalLifecycleOwner`** (2026-08-22, Navigation 3 迁移之后):
 * Nav3 的入口 LifecycleOwner 由 `BackStackAwareLifecycleNavEntryDecorator` 在**入口内容的组合里**
 * 造出来, `maxState` 只看"这个条目还在返回栈里没有" —— **前进导航时被盖住的条目一直是 RESUMED**,
 * 唯一的事件来自组合销毁 (退场动画之后), pop 时才降到 CREATED. 于是 Nav2 时代那套
 * "离开页面有 ON_STOP、回到页面有 ON_START, 且与组合是否存活无关" 的假设整个不成立了:
 * 转场没走完就按返回 (子树一直没被销毁) 时一个事件都不会发, 靠它补焦点落点的页面就此失效.
 * 本信号直接读返回栈, 与组合存活、与转场进度都无关, 正是那些地方原本想要的东西.
 *
 * 类型是 [State] 而不是 `Boolean`: 读者基本都在 effect/lambda 里读 (`.value`), 值翻转不该让整页
 * 重组 (见 TV 的重组纪律). 需要"回到栈顶时做一件事"就用 [OnReturnToForeground].
 */
val LocalPageIsForeground: ProvidableCompositionLocal<State<Boolean>> =
    staticCompositionLocalOf { AlwaysForeground }

private val AlwaysForeground = object : State<Boolean> {
    override val value: Boolean get() = true
}

/**
 * 本页**重新**回到栈顶时跑一次 [block] —— 进页面那一次不算 (那时页面的初始落点自有人管).
 *
 * 典型用途: 从更深的页面返回后补发一次焦点落点. 离开期间被销毁的节点会把焦点带走, 而 Compose
 * 清掉焦点时不会交给焦点祖先 (见 `FocusTargetNode.onReset`), 表现就是"回来后看不到焦点圈 / 方向键
 * 全失效". 详见 [LocalPageIsForeground] 里关于 Nav3 的说明.
 *
 * [block] 用 `collectLatest` 收: 补落点期间用户又离开本页 (常见于连按返回) 时当场取消, 不会有
 * 一个过期的重试循环在后面接着抢焦点.
 *
 * [page] 只用于日志: 本效应管的那个窗口 (转场没走完就按返回 → 整棵子树没被销毁) 在界面上与
 * "组合重建后走各页初始落点"那条正常路**看不出区别**, 事后只有日志分得清 —— 三条 info 各对应
 * 一种去向, 判读方法见文件末尾.
 */
@Composable
fun OnReturnToForeground(page: String, block: suspend () -> Unit) {
    val isForeground = LocalPageIsForeground.current
    val currentBlock = rememberUpdatedState(block)
    LaunchedEffect(isForeground) {
        var leftForeground = false
        var armed = false
        snapshotFlow { isForeground.value }.collectLatest { foreground ->
            if (!armed) {
                armed = true
                // 本页的组合是新的. 首帧就在栈顶 = 正常进页面/组合重建后返回 (落点归各页自己的
                // 初始效应); 首帧不在栈顶 = 组合活过了一次导航 (那就等着下面补落点)
                logger.info { "OnReturnToForeground armed: page=$page, foreground=$foreground" }
            }
            if (!foreground) {
                leftForeground = true
                logger.info { "Page left foreground: page=$page (组合还活着, 回来要补落点)" }
                return@collectLatest
            }
            if (!leftForeground) return@collectLatest // 进页面的第一次, 不是"回来"
            leftForeground = false
            logger.info { "Page returned to foreground: page=$page, restoring focus" }
            currentBlock.value()
        }
    }
}

/**
 * 怎么读这三条日志 (`grep -E "OnReturnToForeground|Page (left|returned)"`):
 *
 * - `armed: foreground=true` 之后就没别的了 = **慢速返回**, 组合已经销毁重建, 落点归各页自己的
 *   `LaunchedEffect(Unit)`. 本效应不该动, 也确实没动;
 * - `left` → `returned` 中间**没有** `armed` = **命中了本效应要修的那个窗口** (转场没走完就按返回,
 *   子树一直没被销毁). 修好的表现就是这里一定跟着一条 `returned`;
 * - 只有 `left`, 迟迟没有 `returned` = 用户还在别的页面上, 正常;
 * - `armed: foreground=false` = 组合是在"已经不在栈顶"之后才建的 (少见, 例如转场中途被重建),
 *   随后回到栈顶照样会补一次落点.
 */
private val logger = logger("PageForeground")
