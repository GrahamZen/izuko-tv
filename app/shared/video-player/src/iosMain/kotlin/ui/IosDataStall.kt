/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import me.him188.ani.app.videoplayer.link.PlaybackStallReopen
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.MediampPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.loadedTimeRanges
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSValue

/**
 * 等数据卡死: AVPlayer 想播、item 就绪、在等数据, 已缓冲的部分却一点也不长, 也不报错, 一等就是几分钟 (iPad 上播网页源的 mp4 实测:
 * 同一时刻这台设备自己去取那一段是通的, 是 AVPlayer 不再去要). 原地精确 seek 拨不动; 往后跳一点 (要的是另一段) 当场就接上.
 *
 * 等数据期间缓冲 [STALL_MILLIS] 一点没长就拨: 先往后跳 [SKIP_MILLIS] ([Action.Skip], 当场接上、缓冲留着); 跳过之后不远处 ([SKIP_RANGE_SECONDS] 以内)
 * 又卡死, 就请求原地重新打开 ([Action.Reopen] → [PlaybackStallReopen]: 重新取地址、用新连接, 回到卡住的位置), 每个 item 只请求一次
 * (重开后是新的 item; 一条资源最多重开几次由接的那边管). 拨的时候把 AVPlayer 自己的传输记录记一行 (见 [IosAvPlayerErrorLog.describeTransfer]).
 *
 * 片源慢不算: 慢的时候缓冲在长, 只是长得慢. 判断由 [IosVideoLayerWake] 盯着做 ([Watch]).
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosDataStall {
    /** 等数据时缓冲这么久一点没长就算卡死 */
    private const val STALL_MILLIS = 8_000L

    /** 缓冲长了这么多秒以上才算在长 */
    private const val GROWTH_SECONDS = 0.05

    /** 卡死时往后跳多少 */
    private const val SKIP_MILLIS = 500L

    /** 跳过之后这么远以内又卡死就不再跳, 改成重开 */
    private const val SKIP_RANGE_SECONDS = 30.0

    enum class Action { None, Skip, Reopen }

    /** 一个 item 的观察: 每次轮询调 [check], 返回这一次要怎么拨 */
    class Watch {
        private var item: AVPlayerItem? = null
        private var loaded = 0.0
        private var stalledMillis = 0L
        private var skippedAt: Double? = null
        private var reopened = false

        /**
         * @param waiting AVPlayer 想播、item 就绪, 在等数据
         * @param time 当前播放时间 (秒)
         * @param pollMillis 距上一次调过了多久
         */
        fun check(current: AVPlayerItem?, waiting: Boolean, time: Double, pollMillis: Long): Action {
            if (current != item) {
                item = current
                loaded = 0.0
                stalledMillis = 0L
                skippedAt = null
                reopened = false
            }
            if (current == null || reopened) return Action.None
            val now = loadedSeconds(current)
            // 长了, 或者少了 (拖动后旧的缓冲被丢掉): 都从这里重新算
            val changed = now > loaded + GROWTH_SECONDS || now < loaded - GROWTH_SECONDS
            loaded = now
            if (!waiting || changed) {
                stalledMillis = 0L
                return Action.None
            }
            stalledMillis += pollMillis
            if (stalledMillis < STALL_MILLIS) return Action.None
            stalledMillis = 0L
            val lastSkip = skippedAt
            if (lastSkip == null || time - lastSkip > SKIP_RANGE_SECONDS || time < lastSkip) {
                skippedAt = time
                return Action.Skip
            }
            reopened = true
            return Action.Reopen
        }
    }

    /** 按 [action] 拨一下, 记一行 */
    fun nudge(action: Action, player: MediampPlayer, item: AVPlayerItem, timeSeconds: Double) {
        if (action == Action.None) return
        val loaded = (loadedSeconds(item) * 10).toLong() / 10.0
        val at = (timeSeconds * 10).toLong() / 10.0
        val what = if (action == Action.Skip) "往后跳 ${SKIP_MILLIS}ms" else "请求原地重开"
        logger.info {
            "[AVStall] 等数据 ${STALL_MILLIS / 1000} 秒缓冲一点没长 (t=${at}s, 已缓冲 ${loaded}s), $what; ${IosAvPlayerErrorLog.describeTransfer(item)}"
        }
        val positionMillis = (timeSeconds * 1000).toLong()
        when (action) {
            Action.Skip -> player.seekTo(positionMillis + SKIP_MILLIS)
            Action.Reopen -> PlaybackStallReopen.request(player, positionMillis, "no data for ${STALL_MILLIS / 1000}s at ${at}s")
            Action.None -> {}
        }
    }

    /** item 已经缓冲了多少秒 (各段加起来) */
    private fun loadedSeconds(item: AVPlayerItem): Double = item.loadedTimeRanges.sumOf { value ->
        val seconds = (value as? NSValue)?.CMTimeRangeValue?.useContents { CMTimeGetSeconds(duration.readValue()) } ?: 0.0
        if (seconds.isNaN() || seconds < 0.0) 0.0 else seconds
    }

    private val logger = logger<IosDataStall>()
}
