/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import kotlinx.io.RawSink

// 原生平台暂不支持, 回落到普通 sink (脏页积压是 Android/ext4 上观察到的问题)
actual fun openPeriodicSyncSink(absolutePath: String, syncEveryBytes: Long): RawSink? = null

actual fun syncFileContents(absolutePath: String): Boolean = false
