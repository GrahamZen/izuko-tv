/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.range.StalledMediaRoute
import me.him188.ani.app.videoplayer.link.PlaybackStallReopen
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 播放器认出等数据卡死 (见 [PlaybackStallReopen]) 时原地重新打开当前资源: 记下这个播放器卡在这条资源上 ([StalledMediaRoute],
 * iOS 上直链这次经本机代理, AVPlayer 自己连源站的那条连接坏了), 再 [PlayerExtensionContext.reloadCurrentMedia] 重新取地址,
 * 回到卡住的位置接着播. 不拉黑、不换源: 片源本身是好的, 是这一条连接挂住了.
 *
 * 同一处最多连着重开 [MAX_REOPENS] 次 (重开后往下播过 [MOVED_ON] 再卡算另一处), 两次之间至少隔 [MIN_INTERVAL];
 * 用完了不再管, 播放器接着等, 用户可以自己换源.
 * 只接自己这个播放器的请求 (同时可能还有一个保留在后台的播放会话).
 *
 * @param currentMediaId 正在播的资源; 还没选中时 `null`
 * @param reload 把卡住的资源原地重开到给的位置
 */
class PlaybackStallReopenExtension(
    private val player: Any,
    private val requests: Flow<PlaybackStallReopen.Request>,
    private val currentMediaId: suspend () -> String?,
    private val reload: suspend (mediaId: String, positionMillis: Long) -> Boolean,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : PlayerExtension("PlaybackStallReopen") {
    private var mediaId: String? = null
    private var reopens = 0
    private var lastReopen: TimeMark? = null
    private var lastReopenPositionMillis = Long.MIN_VALUE

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("PlaybackStallReopen") {
            requests.filter { it.player === player }.collect { handle(it) }
        }
    }

    /** 处理一次请求, 返回是否重开了 */
    internal suspend fun handle(request: PlaybackStallReopen.Request): Boolean {
        val current = currentMediaId() ?: return false
        if (current != mediaId) {
            mediaId = current
            reopens = 0
            lastReopen = null
            lastReopenPositionMillis = Long.MIN_VALUE
        }
        // 上次重开后又往下播了一大段才再卡: 上次是管用的, 这次是另一处, 重新计数
        if (request.positionMillis - lastReopenPositionMillis >= MOVED_ON.inWholeMilliseconds) reopens = 0
        if (reopens >= MAX_REOPENS) {
            logger.info { "Playback stalled (${request.reason}) on $current, already reopened $reopens times, leaving it to the player" }
            return false
        }
        if (lastReopen?.let { it.elapsedNow() < MIN_INTERVAL } == true) return false
        reopens++
        lastReopen = timeSource.markNow()
        lastReopenPositionMillis = request.positionMillis
        val reloaded = reload(current, request.positionMillis)
        logger.info { "Playback stalled (${request.reason}) on $current at ${request.positionMillis}ms, reopened in place (#$reopens): $reloaded" }
        return reloaded
    }

    override suspend fun onClose() {
        StalledMediaRoute.forget(player)
    }

    companion object : EpisodePlayerExtensionFactory<PlaybackStallReopenExtension> {
        /** 同一处 (前后不到 [MOVED_ON]) 最多连着重开几次 */
        const val MAX_REOPENS = 3

        /** 上次重开的地方往后播过这么多再卡, 算另一处 (有的源一集里会卡好几回, 每回重开都管用) */
        val MOVED_ON: Duration = 60.seconds

        /** 两次重开之间至少隔多久 (重开后取地址、打开要几秒, 这期间播放器那边可能又报) */
        val MIN_INTERVAL: Duration = 20.seconds

        private val logger = logger<PlaybackStallReopenExtension>()

        override fun create(context: PlayerExtensionContext, koin: Koin): PlaybackStallReopenExtension =
            PlaybackStallReopenExtension(
                player = context.player,
                requests = PlaybackStallReopen.requests,
                currentMediaId = { context.sessionFlow.first().fetchSelectFlow.first()?.mediaSelector?.selected?.value?.mediaId },
                reload = { mediaId, position ->
                    StalledMediaRoute.markStalled(context.player, mediaId)
                    context.reloadCurrentMedia(position, playWhenReady = true)
                },
            )
    }
}
