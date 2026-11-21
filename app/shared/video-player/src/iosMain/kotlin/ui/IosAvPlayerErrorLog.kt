/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemNewErrorLogEntryNotification
import platform.AVFoundation.errorLog
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUnderlyingErrorKey

/**
 * 把 AVPlayer 自己记下的错误写进日志 (`[AVError] …`). 打不开或播到一半断了时 mediamp 只报 item 那一层的错误
 * (常是 `NSURLErrorDomain -1 未知错误`), 真正的原因 (HTTP 状态码、CFNetwork 错误码、对面的服务器地址) 在 item 的错误记录
 * (`errorLog`) 与下层错误 (`NSUnderlyingErrorKey`) 里. 地址只记主机名, 不记路径与参数 (签名链接).
 *
 * 第一次盯图层时装上 (见 [IosVideoLayerWake.watch]), 之后一直生效, 所有 item 的都记; item 失败那一下由 [IosVideoLayerWake] 调 [logFailed].
 */
internal object IosAvPlayerErrorLog {
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(AVPlayerItemNewErrorLogEntryNotification, null, NSOperationQueue.mainQueue) { note ->
            val item = note?.`object` as? AVPlayerItem ?: return@addObserverForName
            val event = item.errorLog()?.events?.lastOrNull() as? AVPlayerItemErrorLogEvent ?: return@addObserverForName
            logger.info { "[AVError] 错误记录: ${describe(event)}" }
        }
        center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, null, NSOperationQueue.mainQueue) { note ->
            val error = note?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError
            logger.info { "[AVError] 播到一半断了: ${describe(error)}" }
        }
    }

    /** item 失败 (打不开) 时记一次: 错误本身连同下层错误, 加上错误记录里最近几条. */
    fun logFailed(item: AVPlayerItem) {
        val events = item.errorLog()?.events.orEmpty().filterIsInstance<AVPlayerItemErrorLogEvent>().takeLast(MAX_EVENTS)
        logger.info {
            buildString {
                append("[AVError] item 打不开: ").append(describe(item.error))
                events.forEach { append("\n  错误记录: ").append(describe(it)) }
            }
        }
    }

    private fun describe(event: AVPlayerItemErrorLogEvent): String =
        "${event.errorDomain} ${event.errorStatusCode} ${event.errorComment.orEmpty()}, 主机 ${hostOf(event.URI)}, 服务器 ${event.serverAddress ?: "?"}"

    private fun describe(error: NSError?): String {
        if (error == null) return "(没有错误对象)"
        return buildString {
            var current: NSError? = error
            var depth = 0
            while (current != null && depth < MAX_DEPTH) {
                if (depth > 0) append(" ← ")
                append(current.domain).append(' ').append(current.code).append(" (").append(current.localizedDescription).append(')')
                current = current.userInfo[NSUnderlyingErrorKey] as? NSError
                depth++
            }
        }
    }

    private fun hostOf(uri: String?): String = uri?.let { NSURL.URLWithString(it)?.host } ?: "?"

    private val logger = logger<IosAvPlayerErrorLog>()

    private const val MAX_EVENTS = 3
    private const val MAX_DEPTH = 4
}
