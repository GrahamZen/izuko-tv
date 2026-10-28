/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import me.him188.ani.app.platform.DevSwitches
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 自动选源的记忆源阶段 (见 [MediaAutoSelector]) 怎么等记住的源, 可调项 (见 [DevSwitches.searchMemoryFirst]、[DevSwitches.searchMemoryWaitCap];
 * 正式包恒为默认, 默认都不改原来的等法).
 *
 * @property prioritize 记住的源在同时查的名额里排到最前
 * @property waitCap 记忆源阶段最多等这么久, 之后别的源也可以选; null 为一直等到记住的源查完
 */
internal class PreferredSourceTuning(
    val prioritize: Boolean,
    val waitCap: Duration?,
) {
    companion object {
        fun current() = PreferredSourceTuning(
            prioritize = DevSwitches.searchMemoryFirst.value,
            waitCap = DevSwitches.searchMemoryWaitCap.value.takeIf { it > 0f }?.toDouble()?.seconds,
        )
    }
}
