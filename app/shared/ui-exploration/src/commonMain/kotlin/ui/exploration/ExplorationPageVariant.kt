/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 探索页变体: 应用入口可提供一个替代布局 (如遥控器形态的沉浸式 Hero 布局). 提供了就用, 没有回退开关.
 */
fun interface ExplorationPageVariant {
    @Composable
    fun Page(state: ExplorationPageState, modifier: Modifier)
}

val LocalExplorationPageVariant = staticCompositionLocalOf<ExplorationPageVariant?> { null }
