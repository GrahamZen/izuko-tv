/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemAccessLogEvent
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.accessLog
import platform.AVFoundation.currentItem
import platform.AVFoundation.errorLog

/*
 * iPad 测试包的诊断 (只在 CI 测试分支): 每秒看一次 AVPlayer 当前条目的错误日志与访问日志, 新的错误逐条记,
 * 访问日志的最后一条有变化时记一次. 日志里搜 `iPadDiag] avplayer`. 地址只记路径 (本地代理的分片路由).
 */
@Composable
internal fun IpadPlayerItemLogDiag(avPlayer: AVPlayer) {
    LaunchedEffect(avPlayer) {
        var item: AVPlayerItem? = null
        var loggedErrors = 0
        var lastAccess = ""
        while (true) {
            val current = avPlayer.currentItem
            if (current !== item) {
                item = current
                loggedErrors = 0
                lastAccess = ""
            }
            if (current != null) {
                val errors = current.errorLog()?.events.orEmpty()
                for (index in loggedErrors until errors.size) {
                    val e = errors[index] as? AVPlayerItemErrorLogEvent ?: continue
                    diagLogger.info {
                        "[iPadDiag] avplayer error #$index: ${e.errorStatusCode} ${e.errorDomain} " +
                            "${e.errorComment?.take(200)} uri=${e.URI?.diagPath()}"
                    }
                }
                loggedErrors = errors.size
                val access = current.accessLog()?.events.orEmpty().lastOrNull() as? AVPlayerItemAccessLogEvent
                if (access != null) {
                    val text = "requests=${access.numberOfMediaRequests} stalls=${access.numberOfStalls} " +
                        "bytes=${access.numberOfBytesTransferred} transfer=${access.transferDuration.toInt()}s " +
                        "observed=${(access.observedBitrate / 1000).toInt()}kbps overdue=${access.downloadOverdue} " +
                        "dropped=${access.numberOfDroppedVideoFrames} watched=${access.durationWatched.toInt()}s"
                    if (text != lastAccess) {
                        lastAccess = text
                        diagLogger.info { "[iPadDiag] avplayer access: $text" }
                    }
                }
            }
            delay(1000)
        }
    }
}

private fun String.diagPath(): String = substringAfter("://").substringAfter('/', "").substringBefore('?').let { "/$it" }

private val diagLogger = logger("iPadDiag")
