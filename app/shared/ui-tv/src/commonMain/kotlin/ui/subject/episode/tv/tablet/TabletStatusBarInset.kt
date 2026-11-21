/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalPlatform
import me.him188.ani.utils.platform.isIos

/**
 * 侧边那一块贴着屏幕顶边时, 顶上会被系统状态栏压住的那几样 (右边带按钮的栏顶、右栏顶上的数据源卡片) 让出的高度.
 * 由 [TabletPlayerFrame] 给: 分栏右栏、全屏抽屉贴着顶边, 是 [tabletStatusBarInset]; 竖屏面板在视频下面, 是 0.
 *
 * 只让真压上的: iPad 状态栏的时间、定位 / Wi-Fi / 电量图标只占那一条的中间一段, 视频左上的返回与标题、只有 ✕ 的栏顶离它们还隔着几点, 不让.
 */
internal val LocalTabletTopInset = staticCompositionLocalOf { 0.dp }

/**
 * 常驻的系统状态栏 (时间、电量那一条) 此刻的高度: 只有 iPad 上有. iPad 分栏时状态栏一直在; 全屏时跟着控件出来,
 * 抽屉开着时控件收着、状态栏也藏着, 这里是 0, 栏顶顶到最上面.
 *
 * 安卓平板上状态栏由沉浸式外壳藏着, 从顶边划下来才临时盖在画面上, 不用让, 恒为 0
 * (进播放页时上游会先把系统栏显示一下再由外壳藏掉, 那一下不能算进来).
 */
@Composable
internal fun tabletStatusBarInset(): Dp {
    if (!LocalPlatform.current.isIos()) return 0.dp
    val density = LocalDensity.current
    return with(density) { WindowInsets.statusBars.getTop(density).toDp() }
}
