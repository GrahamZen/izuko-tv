/*
 * Copyright 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:Suppress("ACTUAL_CLASSIFIER_MUST_HAVE_THE_SAME_MEMBERS_AS_NON_FINAL_EXPECT_CLASSIFIER_WARNING")

package me.him188.ani.app.ui.foundation.navigation

import androidx.compose.runtime.Composable
import me.him188.ani.utils.platform.annotations.TestOnly

/**
 * 不在返回栈栈顶的页面 (被新页盖住 / 正在退场) 的返回处理一律不生效 (见 [LocalPageIsForeground]): 转场期间它们还在组合里,
 * 又注册得比 NavDisplay 晚, 会抢先把这一下返回吃掉 —— 进详情页后要等列表页离场才退得出去 (2026-09-14 用户).
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled && LocalPageIsForeground.current.value, onBack)

actual typealias LocalOnBackPressedDispatcherOwner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner

actual typealias OnBackPressedDispatcherOwner = androidx.activity.OnBackPressedDispatcherOwner

actual typealias OnBackPressedDispatcher = androidx.activity.OnBackPressedDispatcher

@TestOnly
actual fun OnBackPressedDispatcher(fallbackOnBackPressed: (() -> Unit)?): OnBackPressedDispatcher {
    return androidx.activity.OnBackPressedDispatcher(fallbackOnBackPressed)
}
