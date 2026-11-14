/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.io.IOException

/**
 * 播放器每次加载出错记一行 (哪个主机、读了多少、什么错). 加载出错后播放器先自己重试, 重试也不行才报播放失败,
 * 中间这几次原来日志里看不到: 播放失败时分不清是一次就坏了, 还是重试了几次都没接上.
 * 网址只记主机, 不记路径和参数 (常带签名).
 */
@AndroidxOptIn(UnstableApi::class)
internal object LoadErrorLog : AnalyticsListener {
    private val logger = logger<LoadErrorLog>()

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        logger.info {
            val host = loadEventInfo.uri.host ?: loadEventInfo.uri.scheme
            val cause = generateSequence<Throwable>(error) { it.cause?.takeIf { cause -> cause !== it } }.take(4)
                .joinToString(" <- ") { "${it::class.java.simpleName}: ${it.message?.let(::stripQuery)?.take(160)}" }
            "[Load] error from $host after ${loadEventInfo.bytesLoaded / 1024} KiB in ${loadEventInfo.loadDurationMs} ms" +
                (if (wasCanceled) ", canceled" else "") + ": $cause"
        }
    }

    private val QUERY = Regex("""\?[^\s"']*""")

    private fun stripQuery(text: String) = QUERY.replace(text, "?…")
}
