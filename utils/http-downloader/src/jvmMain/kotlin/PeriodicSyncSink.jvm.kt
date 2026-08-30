/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readAtMostTo
import java.io.File
import java.io.FileOutputStream

actual fun openPeriodicSyncSink(absolutePath: String, syncEveryBytes: Long): RawSink? =
    PeriodicSyncFileSink(File(absolutePath), syncEveryBytes)

/** 见 [openPeriodicSyncSink]. 关闭时再 fsync 一次, 保证落盘 (调用方随后会按文件大小校验). */
private class PeriodicSyncFileSink(file: File, private val syncEveryBytes: Long) : RawSink {
    private val stream = FileOutputStream(file)
    private val transferBuffer = ByteArray(64 * 1024)
    private var bytesSinceSync = 0L

    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val chunk = minOf(remaining, transferBuffer.size.toLong()).toInt()
            val read = source.readAtMostTo(transferBuffer, 0, chunk)
            if (read <= 0) break
            stream.write(transferBuffer, 0, read)
            remaining -= read
            bytesSinceSync += read
            if (bytesSinceSync >= syncEveryBytes) {
                stream.flush()
                stream.fd.sync()
                bytesSinceSync = 0
            }
        }
    }

    override fun flush() {
        stream.flush()
    }

    override fun close() {
        try {
            stream.flush()
            stream.fd.sync()
        } finally {
            stream.close()
        }
    }
}

actual fun syncFileContents(absolutePath: String): Boolean {
    val file = File(absolutePath)
    if (!file.exists()) return false
    // append = true: 绝不截断别人正在写的文件; 一个字节也不写, 只借这个 fd 调 fsync
    return try {
        FileOutputStream(file, true).use { it.fd.sync() }
        true
    } catch (e: java.io.IOException) {
        false
    }
}
