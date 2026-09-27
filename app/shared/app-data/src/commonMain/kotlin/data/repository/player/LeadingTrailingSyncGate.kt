/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.player

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Runs the first request immediately, then coalesces requests received during [cooldown] into one trailing run.
 * Every trailing run starts a new cooldown of its own.
 *
 * 上游放在 Ani 服务器的播放记录同步器 (PlaybackHistorySyncer) 里; fork 没有那个同步器, 只有
 * [me.him188.ani.app.data.repository.episode.EpisodeCollectionSyncer] 在用, 单独成文件.
 */
internal class LeadingTrailingSyncGate(
    scope: CoroutineScope,
    private val cooldown: Duration,
    name: String = "LeadingTrailingSyncGate",
    private val task: suspend () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        require(cooldown.isPositive()) { "cooldown must be positive" }
        scope.launch(CoroutineName(name)) {
            while (currentCoroutineContext().isActive) {
                requests.receive()
                do {
                    task()
                    delay(cooldown)
                } while (requests.tryReceive().isSuccess)
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }
}
