/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.MediampPlayer

/**
 * **后台会话最后停在的那一帧** —— 动作面板的"正在播放"缩略图优先用它.
 *
 * 比 TMDB 图好在两点: 永远是**当前这一集** (单集图匹配错季是常事), 以及"我刚才看的画面缩到了
 * 这里"的空间连续性 —— 那正是"播放器还活着"要传达的东西.
 *
 * ## 什么时候截
 *
 * **播放器暂停的那一刻**, 由播放页自己调 ([capture]). 退出播放页必然伴随一次自动暂停
 * (见 RetainedPlaybackSessionHolder), 所以"离开"这个时机是被覆盖的; 手动暂停也顺带更新一张,
 * 语义同样成立 ("停在哪儿").
 *
 * 不能等到面板打开时再取: 那时播放页的组合早没了, Surface 也不在, 只能另起一路解码器 ——
 * 见 [captureTvPlayerFrame] 的说明.
 *
 * ## 只留一张
 *
 * 只保留当前会话那一集的帧 (会话本来也只有一个). 换集/换条目直接顶掉, 不做多张缓存 ——
 * 128dp 的缩略图存 480px 宽已经绰绰有余 (~0.5MB), 但攒起来就不划算了.
 */
object TvRetainedFrameStore {
    /** 缩略图只有 128dp, 480px 宽足够, 且不必为一张小图长期持有几 MB 位图. */
    private const val CAPTURE_WIDTH = 480

    /**
     * 判定"截了个黑屏"的亮度门槛 (0..1). PixelCopy 在部分驱动上对硬解视频层会返回全黑,
     * 而这台机器正是踩过一堆视频层怪事的那类 —— 与其显示一块黑, 不如退回 TMDB 图.
     */
    private const val BLANK_LUMA_THRESHOLD = 0.04f

    private var entry: Entry? by mutableStateOf(null)

    private class Entry(val subjectId: Int, val episodeId: Int, val frame: ImageBitmap)

    private val logger = logger<TvRetainedFrameStore>()

    /** 这一集有没有存着的帧; 不是这一集就返回 null (别拿上一集的画面糊弄). */
    fun frameFor(subjectId: Int, episodeId: Int): ImageBitmap? =
        entry?.takeIf { it.subjectId == subjectId && it.episodeId == episodeId }?.frame

    /**
     * 截一张存起来. 截不到 (Surface 没了 / 驱动返回黑屏) 就**清掉旧的**: 旧帧多半是上一集的,
     * 留着比没有更糟.
     */
    suspend fun capture(player: MediampPlayer, subjectId: Int, episodeId: Int) {
        val frame = captureTvPlayerFrame(player, maxWidth = CAPTURE_WIDTH)
        if (frame == null || frame.looksBlank()) {
            if (frame != null) {
                logger.info { "Player frame capture came back blank, falling back to TMDB image" }
            }
            if (entry?.episodeId != episodeId) entry = null
            return
        }
        entry = Entry(subjectId, episodeId, frame)
    }

    /** 会话结束: 帧跟着作废. */
    fun clear() {
        entry = null
    }

    /**
     * 稀疏采样判断整张是不是全黑. 5×5 = 25 个点, 全部低于门槛才算黑 ——
     * 真有一帧是淡出到全黑的话会被误判, 代价只是这一次退回 TMDB 图.
     */
    private fun ImageBitmap.looksBlank(): Boolean {
        val pixels = toPixelMap()
        for (iy in 1..5) {
            for (ix in 1..5) {
                val x = (width * ix / 6).coerceIn(0, width - 1)
                val y = (height * iy / 6).coerceIn(0, height - 1)
                val c = pixels[x, y]
                if (c.red + c.green + c.blue > BLANK_LUMA_THRESHOLD * 3) return false
            }
        }
        return true
    }
}
