/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.media.cache.engine.PlaybackYieldingGate
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.player.ActivePlayback
import me.him188.ani.app.domain.media.player.PlaybackActivity
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.TimeSource

/**
 * 播放跟不上时, 正在播的那一集以外的 BT 下载给播放让路. 判断同 HTTP 缓存 ([PlaybackYieldingGate]), 看播放器在当前位置之后缓冲了多少:
 * 不到 [PlaybackYieldingGate.PAUSE_MILLIS] 或卡住了就停下它们, 回到 [PlaybackYieldingGate.FREE_MILLIS] 或不播了 (退出、暂停、播放页不在眼前) 再放开.
 * 缓冲攒得住时不管: 两边都慢 (节点少) 时带宽其实够用, 停了也白停. BT 没有单个种子的限速, 只能停; 停了要重新连节点, 放开的门槛比 HTTP 高.
 *
 * 不停的: 正在播的那一集 (边下边播的就是它; 播放换到被停的那一集时当场放开), 还在等种子信息的 (只取元数据, 占不了带宽),
 * 没在下的 (下完、暂停、删了). 同一个种子里的别的集按文件停. 让路的停不算暂停 (见 [TorrentMediaCacheEngine.TorrentMediaCache.yieldingForPlayback]).
 * 每次停、放开都写日志: 缓冲多少、卡没卡、停了哪几集、哪几集为什么没停、停了多久 —— 查 BT 为什么没在下时看.
 */
internal class TorrentPlaybackYield(
    /** 本机的 BT 下载 (网盘离线下载那种走云端的不算) */
    private val targets: Flow<List<Target>>,
    /** 眼前在播的那一集; 不在播、播放页不在眼前为 `null` */
    private val playback: Flow<ActivePlayback?>,
) {
    /** 一份可能要让路的 BT 下载 */
    interface Target {
        val id: String

        /** 日志里写的: 番名、第几集、哪一份 */
        val label: String
        val subjectId: String
        val episodeId: String

        /** 此刻能停: 在下, 已经拿到种子信息选好了文件 */
        suspend fun canYield(): Boolean

        suspend fun setYielding(yielding: Boolean)
    }

    private val timeOrigin = TimeSource.Monotonic.markNow()
    private fun nowMillis() = timeOrigin.elapsedNow().inWholeMilliseconds

    /** 从哪一刻开始让路; 没在让路为 null */
    private var since: Long? = null

    /** 这里停下的, 放开时只放开它们 */
    private val yielded = LinkedHashMap<String, Target>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(playback, targets) { playing, list -> playing to list }.collect { (playing, list) -> step(playing, list) }
        }
    }

    internal suspend fun step(playing: ActivePlayback?, list: List<Target>) {
        val yielding = since != null
        if (!shouldYield(yielding, playing)) {
            if (yielding) release(reasonOfRelease(playing))
            return
        }
        playing!!
        fun Target.isPlaying() = subjectId == playing.subjectId.toString() && episodeId == playing.episodeId.toString()
        // 播放换到了被停的那一集: 当场放开, 不然播的就是停着的文件
        yielded.values.filter { it.isPlaying() }.forEach { target ->
            yielded.remove(target.id)
            target.setYielding(false)
            logger.info { "BT download ${target.label} stops yielding: it is the episode being played now" }
        }
        val paused = mutableListOf<Target>()
        val keptPlaying = mutableListOf<Target>()
        val keptIdle = mutableListOf<Target>()
        for (target in list) {
            if (target.id in yielded) continue
            when {
                target.isPlaying() -> keptPlaying += target
                !target.canYield() -> keptIdle += target
                else -> {
                    target.setYielding(true)
                    yielded[target.id] = target
                    paused += target
                }
            }
        }
        val state = "subject ${playing.subjectId} episode ${playing.episodeId}, buffered ahead ${playing.bufferedAheadMillis}ms, stalled=${playing.stalled}"
        if (!yielding) {
            since = nowMillis()
            logger.info {
                "BT downloads yield to playback ($state): paused ${paused.size} ${paused.labels()}; " +
                        "not paused: playing ${keptPlaying.labels()}, not downloading or awaiting torrent info ${keptIdle.labels()}"
            }
        } else if (paused.isNotEmpty()) {
            logger.info { "More BT downloads yield to playback ($state): paused ${paused.size} ${paused.labels()}" }
        }
    }

    private suspend fun release(reason: String) {
        val started = since ?: return
        since = null
        val back = yielded.values.toList()
        yielded.clear()
        for (target in back) target.setYielding(false)
        logger.info { "BT downloads resume after yielding to playback for ${(nowMillis() - started) / 1000}s ($reason): ${back.size} ${back.labels()}" }
    }

    private fun reasonOfRelease(playing: ActivePlayback?): String = when {
        playing == null -> "not playing"
        playing.bufferedAheadMillis == null -> "playback paused or loading"
        else -> "buffered ahead ${playing.bufferedAheadMillis}ms"
    }

    private fun List<Target>.labels(): String = joinToString(prefix = "[", postfix = "]") { it.label }

    internal companion object {
        private val logger = logger<TorrentPlaybackYield>()

        /** 该不该让路 (正在让路时为 [yielding]): 卡住了, 或缓冲不到 [PlaybackYieldingGate.PAUSE_MILLIS]; 让路时回到 [PlaybackYieldingGate.FREE_MILLIS] 才放开 */
        fun shouldYield(yielding: Boolean, playing: ActivePlayback?): Boolean {
            if (playing == null) return false
            if (playing.stalled) return true
            val ahead = playing.bufferedAheadMillis ?: return false
            return ahead < if (yielding) PlaybackYieldingGate.FREE_MILLIS else PlaybackYieldingGate.PAUSE_MILLIS
        }

        /** 由 [MediaDownloadManager] 的下载列表与播放状态建 (本机 BT 的下载才让路) */
        fun of(downloads: Flow<List<MediaDownload>>, playbackActivity: PlaybackActivity): TorrentPlaybackYield = TorrentPlaybackYield(
            targets = downloads.map { list ->
                list.mapNotNull { download ->
                    val cache = download.cache as? TorrentMediaCacheEngine.TorrentMediaCache ?: return@mapNotNull null
                    if (download.engineKey.isCloud) return@mapNotNull null
                    CacheTarget(download, cache)
                }
            },
            playback = combine(playbackActivity.current, playbackActivity.onScreen) { playing, onScreen -> playing?.takeIf { onScreen } }
                .distinctUntilChanged(),
        )
    }

    private class CacheTarget(private val download: MediaDownload, private val cache: TorrentMediaCacheEngine.TorrentMediaCache) : Target {
        override val id: String get() = download.id
        override val label: String
            get() = download.metadata.let { "${it.subjectNameCN ?: it.subjectNames.firstOrNull().orEmpty()} ${it.episodeSort} (${download.id})" }
        override val subjectId: String get() = download.metadata.subjectId
        override val episodeId: String get() = download.metadata.episodeId
        override suspend fun canYield(): Boolean = cache.canYieldForPlayback()
        override suspend fun setYielding(yielding: Boolean) = cache.yieldForPlayback(yielding)
    }
}
