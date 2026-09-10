/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.io.IOException
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

object JvmLogHelper {
    /**
     * 用 [File] 而不是 `java.nio.file.Path`: 后者在 Android API 26 以下不存在,
     * 而 minSdk 已经降到 25 (标准的 core library desugaring 不覆盖 java.nio.file).
     */
    @Throws(IOException::class)
    fun deleteOldLogs(logsFolder: File) {
        val now = Clock.System.now()
        val files = if (logsFolder.isDirectory) logsFolder.listFiles().orEmpty() else emptyArray()
        for (file in files) {
            if (file.extension == "log" && (file.name.startsWith("app") || file.name.startsWith("cef-"))
                && now - Instant.fromEpochMilliseconds(file.lastModified()) > 3.days
            ) {
                file.delete()
            }
        }
    }
}
