/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.domain.media.player.ActivePlayback
import me.him188.ani.utils.httpdownloader.ByteRateLimiter
import me.him188.ani.utils.httpdownloader.DownloadThroughputGate
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.TimeSource

/**
 * 播放时让缓存下载给播放让路 (所有 HTTP 缓存共用一个). 带宽够不够没法直接测, 看播放器在当前位置之后缓冲了多少
 * ([ActivePlayback.bufferedAheadMillis]):
 * - 缓冲攒得住 (≥ [FREE_MILLIS]): 带宽富余, 缓存照常;
 * - 缓冲在往下掉: 缓存限速到它不受限时速度的一半 ([THROTTLE_FRACTION]);
 * - 卡住了或快见底 (< [PAUSE_MILLIS]): 缓存先停, 缓冲回到 [RESUME_MILLIS] 再放开.
 * 没在播放、暂停着、或播放器报不出缓冲时不管. 进退各有一段余量, 免得在门槛附近来回切.
 *
 * 同一集的缓存不经这里: 播放时直接停下, 见 `MediaDownloadManager.waitingForPlayback`.
 *
 * @param playback 见 [me.him188.ani.app.domain.media.player.PlaybackActivity.current]
 * @param currentPlayback 它此刻的值
 */
class PlaybackYieldingGate(
    private val playback: Flow<ActivePlayback?>,
    private val currentPlayback: () -> ActivePlayback?,
) : DownloadThroughputGate {
    internal enum class Mode { FREE, THROTTLED, PAUSED }

    private val mutex = Mutex()
    private val timeOrigin = TimeSource.Monotonic.markNow()
    private fun nowMillis() = timeOrigin.elapsedNow().inWholeMilliseconds

    private var mode = Mode.FREE
    private var limiter: ByteRateLimiter? = null

    // 不受限时的总速度 (字节/秒): 每 [RATE_WINDOW_MILLIS] 结一次, 再做指数平均
    private var freeRate = 0.0
    private var windowStartedAt = nowMillis()
    private var windowBytes = 0L

    override suspend fun acquire(bytes: Int) {
        while (true) {
            when (mutex.withLock { advance(currentPlayback()) }) {
                Mode.FREE -> {
                    mutex.withLock { recordFree(bytes) }
                    return
                }

                Mode.THROTTLED -> {
                    mutex.withLock { limiter }?.acquire(bytes)
                    return
                }

                // 等到不再需要停为止 (播放结束、或缓冲回来了), 再重新判断
                Mode.PAUSED -> playback.first { mutex.withLock { advance(it) } != Mode.PAUSED }
            }
        }
    }

    /** 按 [playback] 更新 [mode] (带进退余量), 进入限速时按当时的速度建限速器. */
    private fun advance(playback: ActivePlayback?): Mode {
        val next = nextMode(mode, playback)
        if (next != mode) {
            if (next == Mode.THROTTLED) {
                val rate = (freeRate * THROTTLE_FRACTION).toLong().coerceAtLeast(MIN_THROTTLED_BYTES_PER_SECOND)
                limiter = ByteRateLimiter(rate, ::nowMillis)
            }
            logger.info {
                "Cache downloads ${next.name.lowercase()} for playback (buffered ahead ${playback?.bufferedAheadMillis}ms, " +
                        "stalled=${playback?.stalled}, unthrottled ${(freeRate / 1024).toLong()} KiB/s)"
            }
            if (next == Mode.FREE) windowStartedAt = nowMillis().also { windowBytes = 0 }
            mode = next
        }
        return mode
    }

    private fun recordFree(bytes: Int) {
        windowBytes += bytes
        val elapsed = nowMillis() - windowStartedAt
        if (elapsed >= RATE_WINDOW_MILLIS) {
            val rate = windowBytes * 1000.0 / elapsed
            freeRate = if (freeRate == 0.0) rate else freeRate * 0.5 + rate * 0.5
            windowStartedAt += elapsed
            windowBytes = 0
        }
    }

    internal companion object {
        private val logger = logger<PlaybackYieldingGate>()

        /** 缓冲余量不少于这些就不管缓存. */
        const val FREE_MILLIS = 30_000L

        /** 不受限时掉到这以下才开始限速 (与 [FREE_MILLIS] 之间是余量). */
        const val THROTTLE_MILLIS = 25_000L

        /** 低于这些就停. */
        const val PAUSE_MILLIS = 10_000L

        /** 停了之后回到这些才放开 (先限速, 到 [FREE_MILLIS] 再不管). */
        const val RESUME_MILLIS = 20_000L

        const val THROTTLE_FRACTION = 0.5
        const val MIN_THROTTLED_BYTES_PER_SECOND = 128L * 1024
        private const val RATE_WINDOW_MILLIS = 2_000L

        internal fun nextMode(current: Mode, playback: ActivePlayback?): Mode {
            if (playback == null) return Mode.FREE
            if (playback.stalled) return Mode.PAUSED
            val ahead = playback.bufferedAheadMillis ?: return Mode.FREE
            return when (current) {
                Mode.PAUSED -> when {
                    ahead >= FREE_MILLIS -> Mode.FREE
                    ahead >= RESUME_MILLIS -> Mode.THROTTLED
                    else -> Mode.PAUSED
                }

                Mode.THROTTLED -> when {
                    ahead < PAUSE_MILLIS -> Mode.PAUSED
                    ahead >= FREE_MILLIS -> Mode.FREE
                    else -> Mode.THROTTLED
                }

                Mode.FREE -> when {
                    ahead < PAUSE_MILLIS -> Mode.PAUSED
                    ahead < THROTTLE_MILLIS -> Mode.THROTTLED
                    else -> Mode.FREE
                }
            }
        }
    }
}
