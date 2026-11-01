/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemSupportDir
import me.him188.ani.utils.io.resolve

/**
 * iOS: 数据目录即应用的 Application Support (同 AniIos 里建的 IosContextFiles, 日志在它下面的 `logs/`, 每行都已写盘),
 * 没有性能诊断, 装不了 APK.
 */
internal actual object FeedbackPlatform {
    actual fun logsDir(): SystemPath? = SystemSupportDir.resolve("logs")

    actual fun filesDir(): SystemPath? = SystemSupportDir

    actual fun perfReports(limit: Int): List<FeedbackPerfReport> = emptyList()

    actual fun perfReport(id: String): FeedbackPerfReport? = null

    actual val apkInstaller: FeedbackApkInstaller? get() = null
}
