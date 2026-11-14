/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

/**
 * 读视频的连接中途断了: 服务器把连接或流断掉、读超时、连接被重置、读到一半没数据了. 网通着时这多半是暂时的
 * (暂停久了服务器断开空闲连接最常见), 不说明这条资源坏了.
 * 服务器拒绝 ([httpErrorStatus])、回了别的内容 ([isNotVideoResponse])、根本连不上 (主机连不上、域名解析不出) 不算.
 *
 * 按 ExoPlayer 的错误码名与平台异常类名认: commonMain 里看不见这些类; 正式包里我们自己的类名会被混淆, 平台类不会.
 */
fun Throwable.isStreamInterrupted(): Boolean {
    if (httpErrorStatus() != null || isNotVideoResponse()) return false
    val chain = generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.take(8).toList()
    if (chain.any { it::class.simpleName in NOT_CONNECTED_EXCEPTIONS || it.message.orEmpty().contains("failed to connect", ignoreCase = true) }) {
        return false
    }
    return chain.any { error ->
        val message = error.message.orEmpty()
        STREAM_ERROR_CODES.any { message.contains(it) } || error::class.simpleName in STREAM_EXCEPTIONS
    }
}

/** ExoPlayer 的加载错误码: 不明的读取错误 (读到一半没数据了是这个)、超时 (连不上的超时由上面的「连不上」排除) */
private val STREAM_ERROR_CODES = listOf(
    "ERROR_CODE_IO_UNSPECIFIED",
    "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
)

/** 根本没连上 */
private val NOT_CONNECTED_EXCEPTIONS = setOf(
    "ConnectException", "UnknownHostException", "NoRouteToHostException", "PortUnreachableException",
)

/** 读到一半没数据了、连接被重置、读超时、HTTP/1.1 长度不够 (`unexpected end of stream`)、HTTP/2 的流被重置 */
private val STREAM_EXCEPTIONS = setOf(
    "EOFException", "SocketException", "SocketTimeoutException", "ProtocolException", "StreamResetException",
)
