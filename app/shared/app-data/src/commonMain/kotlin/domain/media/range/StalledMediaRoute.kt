/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.range

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * 等数据卡死过、改经本机代理的资源, 每个播放器记一条. 卡死后 AVPlayer 自己连这个站的连接要坏一阵, 直连重开一秒内就报网络错
 * (被当成坏源换掉), 所以原地重开 (PlaybackStallReopenExtension) 前在这里记下, 这个播放器再装这条资源时经代理 ([ParallelRangePlayback]).
 * 这个播放器装了别的资源 (换集、换源) 就忘掉, 回到直连; 播放会话关掉时 [forget].
 */
object StalledMediaRoute {
    private val routes = atomic(emptyMap<Any, String>())

    /** [player] 在 [mediaId] 上卡死了: 它再装这条资源时经代理 */
    fun markStalled(player: Any, mediaId: String) = routes.update { it + (player to mediaId) }

    /** [player] 要装 [mediaId]: 是卡死过的那条时返回 true; 不是就忘掉记着的那条 */
    fun throughProxy(player: Any, mediaId: String): Boolean {
        val stalled = routes.value[player] ?: return false
        if (stalled == mediaId) return true
        routes.update { if (it[player] == stalled) it - player else it }
        return false
    }

    fun forget(player: Any) = routes.update { it - player }
}
