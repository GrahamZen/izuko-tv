/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.TimeSource

/**
 * iPad 测试包的诊断日志 (只在 CI 测试分支): 安卓上验不到的那几处各记一行, 日志里搜 `iPadDiag`.
 */
object IpadDiag {
    private val logger = logger("iPadDiag")
    private val start = TimeSource.Monotonic.markNow()

    fun log(message: String) {
        logger.info { "[iPadDiag +${start.elapsedNow().inWholeMilliseconds}ms] $message" }
    }
}
