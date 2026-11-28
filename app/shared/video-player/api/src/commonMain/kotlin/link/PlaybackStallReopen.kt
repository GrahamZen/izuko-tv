/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.link

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 等数据卡死时请求原地重新打开: 播放器在等数据, 已缓冲的部分却一点也不长, 也不报错 (播放器自己连源站的连接挂住了).
 * 平台播放器认出来就 [request] (现在只有 iOS, 见 IosDataStall); 播放会话那边 (PlaybackStallReopenExtension)
 * 收到自己那个播放器的请求后重新取地址、直链这次经本机代理, 回到卡住的位置接着播.
 */
object PlaybackStallReopen {
    /**
     * @property player 卡住的播放器 (MediampPlayer), 接的一方只认自己的
     * @property positionMillis 卡在哪里
     * @property reason 写进日志的说明
     */
    class Request(val player: Any, val positionMillis: Long, val reason: String)

    private val requestFlow = MutableSharedFlow<Request>(extraBufferCapacity = 8)

    val requests: SharedFlow<Request> = requestFlow.asSharedFlow()

    fun request(player: Any, positionMillis: Long, reason: String) {
        requestFlow.tryEmit(Request(player, positionMillis, reason))
    }
}
