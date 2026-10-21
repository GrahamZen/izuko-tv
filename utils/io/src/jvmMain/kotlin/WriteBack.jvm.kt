/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.io

import java.io.IOException
import java.io.RandomAccessFile

/**
 * 只读打开: 绝不截断, 文件已删时打开失败而不会把它重新建出来. Linux / Android / macOS 上只读句柄的 fsync
 * 照样刷这个文件的脏页; Windows 要可写句柄, 那里返回 false (NTFS 没有「一次 fsync 等全盘脏页」的问题).
 */
actual fun syncFileContents(path: SystemPath): Boolean = try {
    RandomAccessFile(path.toFile(), "r").use { it.fd.sync() }
    true
} catch (e: IOException) {
    false
}
