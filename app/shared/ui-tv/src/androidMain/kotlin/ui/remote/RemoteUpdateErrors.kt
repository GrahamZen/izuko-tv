/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import io.ktor.client.plugins.ResponseException
import kotlinx.serialization.SerializationException
import me.him188.ani.app.tools.update.ChecksumMismatchException
import me.him188.ani.app.tools.update.SourceOutcome
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException

/*
 * 控制台「应用更新」里网络与下载出错时给网页的一句话. 异常原文 (英文, 常带完整地址, GitHub 限流时还带着用户的公网 IP)
 * 只进日志, 网页上按类型说原因; 线路只写域名.
 *
 * 判据沿异常的起因链找, 先看状态码再看类型:
 *
 * | 情况 | 判据 | 说法 |
 * |---|---|---|
 * | 被限流 | 403 / 429 | 被限流了 |
 * | 找不到 | 404 | 找不到文件 |
 * | 服务器出错 | 5xx | 服务器出错（502） |
 * | 别的状态码 | 其余 4xx | 服务器拒绝了（451） |
 * | 校验不对 | [ChecksumMismatchException] | 下载的文件校验不对 |
 * | 域名解析不了 | UnknownHost / UnresolvedAddress | 解析不了域名 |
 * | 超时 | 类名带 Timeout (ktor 的请求 / 连接 / 读超时, 协程超时) | 连接超时 |
 * | 安全连接失败 | SSLException (证书不对、握手被掐) | 安全连接失败 |
 * | 连不上 | ConnectException / NoRouteToHost | 连不上 |
 * | 存储满了 | ENOSPC | 电视存储空间不够 |
 * | 连接断了 | SocketException / EOF / 读到一半通道关了 | 连接断了 |
 * | 内容不对 | 解析返回的 JSON 失败 | 返回的内容不对 |
 * | 其他 | — | 出错了（类名） |
 */

/** [e] 的一句话原因, 能接在「GitHub」「gh-proxy.com」这类名字后面读 (「GitHub 连接超时」). */
internal fun updateErrorReason(e: Throwable): String {
    val chain = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    chain.firstNotNullOfOrNull { it as? ResponseException }?.let { r ->
        val code = r.response.status.value
        return when {
            code == 403 || code == 429 -> tr("被限流了")
            code == 404 -> tr("找不到文件")
            code in 500..599 -> tr("服务器出错（{0}）", code)
            else -> tr("服务器拒绝了（{0}）", code)
        }
    }
    fun has(test: (Throwable) -> Boolean) = chain.any(test)
    return when {
        has { it is ChecksumMismatchException } -> tr("下载的文件校验不对")
        has { it is UnknownHostException || it is UnresolvedAddressException } -> tr("解析不了域名")
        has { it::class.simpleName.orEmpty().contains("Timeout") } -> tr("连接超时")
        has { it is SSLException } -> tr("安全连接失败")
        has { it is ConnectException || it is NoRouteToHostException } -> tr("连不上")
        has(::isOutOfSpace) -> tr("电视存储空间不够")
        has { it is SocketException || it is EOFException || it::class.simpleName.orEmpty().contains("ClosedReceiveChannel") } ->
            tr("连接断了")

        has { it is SerializationException } -> tr("返回的内容不对")
        else -> tr("出错了（{0}）", e::class.simpleName ?: "?")
    }
}

/**
 * 写安装会话、拉起确认界面这类系统那边的错误: 存储满了照样说人话, 其余照录系统给的原话 (不带地址, 报障时有用).
 */
internal fun systemErrorText(e: Throwable): String =
    if (generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any(::isOutOfSpace)) {
        tr("电视存储空间不够")
    } else {
        e.message ?: e::class.simpleName.orEmpty()
    }

/**
 * 下载失败的一句话: 每条线路 (按域名) 各自的原因; 都一样时合成一句. 没有逐条结果 (还没开始试线路就出错) 时只说 [e] 的原因.
 */
internal fun downloadFailureText(e: Throwable, outcomes: List<SourceOutcome>): String {
    val reasons = outcomes.groupBy { hostOf(it.url) }.mapNotNull { (host, list) ->
        list.firstNotNullOfOrNull { it.error }?.let { host to updateErrorReason(it) }
    }
    return when {
        reasons.isEmpty() -> updateErrorReason(e)
        reasons.size == 1 -> "${reasons[0].first} ${reasons[0].second}"
        reasons.all { it.second == reasons[0].second } -> tr("{0} 条线路都{1}", reasons.size, reasons[0].second)
        else -> reasons.joinToString(tr("；")) { (host, reason) -> "$host $reason" }
    }
}

/** 线路的名字: 地址的域名 (镜像是「镜像域名/原地址」, 取前面那个); 解析不了时原样返回. */
internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

/** 测出来的速度: 「5.2 MB/s」「380 KB/s」, 同 formatTransferProgress 的写法. */
internal fun formatSpeed(bytesPerSecond: Long): String {
    val kib = 1024L
    val mib = kib * 1024
    if (bytesPerSecond < mib) return "${bytesPerSecond.coerceAtLeast(0) / kib} KB/s"
    val tenths = bytesPerSecond * 10 / mib
    return if (tenths >= 100 || tenths % 10 == 0L) "${tenths / 10} MB/s" else "${tenths / 10}.${tenths % 10} MB/s"
}

private fun isOutOfSpace(t: Throwable): Boolean =
    (t is IOException || t::class.simpleName == "ErrnoException") &&
        t.message.orEmpty().let { it.contains("ENOSPC") || it.contains("No space left", ignoreCase = true) }

private const val MAX_CAUSE_DEPTH = 8
