/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.io

// File.usableSpace 对不存在的路径返回 0, 所以往上找第一个存在的目录
actual fun SystemPath.usableSpace(): Long? = runCatching {
    generateSequence(toFile().absoluteFile) { it.parentFile }
        .firstOrNull { it.exists() }
        ?.takeIf { it.totalSpace > 0 }
        ?.usableSpace
}.getOrNull()
