/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.z_stream

/** 系统的 zlib (Ktor 在 iOS 上的 gzip 编码器是原样输出, 不压缩). */
@OptIn(ExperimentalForeignApi::class)
internal actual fun gzip(data: ByteArray): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    // windowBits 加 16: 输出带 gzip 的头尾 (同 GZIPOutputStream), 不是裸的 zlib 流
    val init = deflateInit2_(
        stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY,
        ZLIB_VERSION, sizeOf<z_stream>().convert(),
    )
    check(init == Z_OK) { "deflateInit2 failed: $init" }
    try {
        // 一次压完: 输出缓冲按 deflateBound 给足, 再留点 gzip 头尾的余量
        val out = ByteArray(deflateBound(stream.ptr, data.size.convert()).convert<Int>() + 64)
        // 空数组取不了地址, 给一个字节的占位 (avail_in 仍是 0)
        val input = if (data.isEmpty()) ByteArray(1) else data
        input.usePinned { src ->
            out.usePinned { dst ->
                stream.next_in = src.addressOf(0).reinterpret()
                stream.avail_in = data.size.convert()
                stream.next_out = dst.addressOf(0).reinterpret()
                stream.avail_out = out.size.convert()
                val result = deflate(stream.ptr, Z_FINISH)
                check(result == Z_STREAM_END) { "deflate failed: $result" }
            }
        }
        out.copyOf(stream.total_out.convert<Int>())
    } finally {
        deflateEnd(stream.ptr)
    }
}
