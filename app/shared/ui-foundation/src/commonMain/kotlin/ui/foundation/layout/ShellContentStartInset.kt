/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.layout

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 外壳的侧边导航盖在页面上 (电视的沉浸式外壳) 时, 页面内容要让出的左侧宽度. 页面本身从屏幕左缘铺起, 侧边栏是透明浮层,
 * 页面按这一截把自己的内容往右排 —— 页面的边界不停在侧边栏边上, 画进侧边栏底下的放大、投影、滑出屏的卡片不会被哪一层裁掉.
 * 不在这种外壳里时为 0.
 */
val LocalShellContentStartInset: ProvidableCompositionLocal<Dp> = staticCompositionLocalOf { 0.dp }
