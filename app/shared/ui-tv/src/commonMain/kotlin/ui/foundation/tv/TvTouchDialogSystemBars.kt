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

/**
 * 平板模式下铺满全屏的对话框 (窗口铺到系统栏下面的那种, 如首次引导的登录层) 里, 系统栏照外壳的样子 (见 ImmersiveShellSystemBarsEffect):
 * 状态栏藏起, 导航栏按设置「藏起导航栏」, 藏起的栏从屏幕边缘滑一下临时叫出来. 在对话框的内容里调.
 * 安卓上对话框是独立窗口, 要在它自己的窗口上设; iPad 上对话框画在同一个界面里, 状态栏跟着外壳, 不用做什么.
 */
@Composable
internal expect fun TvTouchDialogSystemBars()
