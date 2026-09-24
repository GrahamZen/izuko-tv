/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 首次启动引导页的插槽. 实现在 `ui-tv` (`TvOnboardingPage`), 由 TV 应用入口装上; 为 null 就没有引导页.
 */
interface TvOnboardingVariant {
    /**
     * 这次启动要不要先进引导页: 引导还没做完时为 true (全新安装, 以及从落地版更新上来的).
     * 应用启动时读一次, 决定返回栈的第一页.
     */
    val pendingOnLaunch: Boolean

    /**
     * 引导页只承载第一步 (检测网络、选连接方式). [onFinished]: 选好了, 由调用方换成主页 ——
     * 第二步 (登录) 由实现方盖在主页上面, 主页在它下面照常加载, 登录完出来就是加载好的探索页.
     */
    @Composable
    fun Page(onFinished: () -> Unit, modifier: Modifier)
}

val LocalTvOnboardingVariant = staticCompositionLocalOf<TvOnboardingVariant?> { null }
