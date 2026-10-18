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

/** iOS 上没有导入收藏与换电视 (都走 Web 控制台, 只有安卓有). */
@Composable
internal actual fun rememberPlatformBackgroundTasks(): List<TvBackgroundTask> = emptyList()
