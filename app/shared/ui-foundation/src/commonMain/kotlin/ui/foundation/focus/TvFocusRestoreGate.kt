/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 「页面正在自己恢复进页落点, 全局兜底别插手」的闸.
 *
 * 全局兜底 (`AniAppContent` 里那个 `while (true) { requestFocus(); delay(100) }`) 的前提是
 * **没有任何东西会给焦点**; 它固定让位 15 帧 (约 250ms) 就开始抢。但"从更深的页面返回列表页"
 * 这条路要等的是**分页数据**: 搜索页返回时实测 `组合 → 焦点落位` 是 312/326/…/1227/1944ms
 * (见 [[project-issue2-search-blank-on-back]] 那份实测表), 远超 250ms。于是兜底必然先抢到,
 * 按 Enter→Right 的几何搜索落到页面顶部的搜索框上, 页面自己的落点随后才把焦点拉到卡片 ——
 * 用户看到的就是"焦点先闪到搜索框再跑到卡上", 中间按的方向键还会按搜索框的拓扑走 (2026-09-18)。
 *
 * 所以让页面在"我正在派落点"期间登记一下, 兜底等它结束再考虑出手。**这不是把兜底关掉**:
 * 兜底那边有超时上限 (见 `FOCUS_FALLBACK_RESTORE_CEILING`), 页面恢复卡死也照样兜得住。
 *
 * 用计数而不是布尔: 同一时刻可能有不止一个页面在组合里 (转场期间新旧两页并存), 谁先结束都不能
 * 把别人的登记一起清掉。
 */
object TvFocusRestoreGate {
    private var claims by mutableIntStateOf(0)

    /** 是否有页面正在恢复自己的进页落点. */
    val restoring: Boolean get() = claims > 0

    internal fun enter() {
        claims++
    }

    internal fun exit() {
        claims = (claims - 1).coerceAtLeast(0)
    }
}

/**
 * 在 [active] 为 true 期间向 [TvFocusRestoreGate] 登记 —— 页面自己在派进页落点, 全局兜底让位.
 *
 * 挂在"恢复流程还没收尾"的判据上, 三段都要覆盖 (缺一段就会漏出一个让兜底插进来的缝, 与
 * [[project-issue2-search-blank-on-back]] 里返回键判据的三段接力是同一回事):
 * 组合 → 派出落点 (`!restoreSettled`) / 派出 → 焦点落位 (`gridFocus.switching`) / 落位之后.
 */
@Composable
fun TvFocusRestoreClaim(active: Boolean) {
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        TvFocusRestoreGate.enter()
        onDispose { TvFocusRestoreGate.exit() }
    }
}
