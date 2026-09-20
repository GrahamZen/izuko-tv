/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * **弹回已有主页时要切到哪个 tab**.
 *
 * [AniNavigator.popBackOrNavigateToMain] 弹回栈里已有的那个 [NavRoutes.Main] 时, 不会重建它 ——
 * 路由参数 [NavRoutes.Main.initialPage] 只在首次组合时生效, 于是"回到主界面"总是停在用户离开时
 * 的那个 tab, 而调用方要的往往是探索页 (遥控器形态的「回到主界面」尤其明显).
 *
 * 所以另开一条极细的通路: 弹回前把目标页写在这里, 主页组合看见就切一次并清空
 * (见 `AniAppContent` 里 `NavRoutes.Main` 那个 entry).
 *
 * **为什么是进程级单例**: Navigation 3 的返回栈只是一列 [NavRoutes] 数据对象, 没有 Navigation 2
 * 那种可以挂东西的 `NavBackStackEntry.savedStateHandle` (原先就是走那里传的). 而改成"把栈里的
 * `Main(旧页)` 换成 `Main(新页)`"是不行的: NavDisplay 按路由对象认页面, 换一个对象等于换一个
 * 页面 —— 主页的 rememberSaveable 状态与 ViewModel 会被整个丢掉, 表现为回主页时整页重新加载.
 * 全应用只有一个主页, 一个字段足够.
 */
@Stable
object MainPageRequest {
    /** 待切换的目标页; `null` = 没有. 主页消费后置回 `null`. */
    var pending: MainScreenPage? by mutableStateOf(null)
}
