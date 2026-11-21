/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Composable

/** 只有这一端才有的后台任务 (安卓: 导入收藏、换电视), 排在 [rememberTvBackgroundTasks] 的公共任务后面. */
@Composable
internal expect fun rememberPlatformBackgroundTasks(): List<TvBackgroundTask>
