/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlayerState

/**
 * 正在播的资源放不下去了 (数据源确认接不上 [unreachable], 或地址被拒 [rejected]) 时在后台换: 旧的接着放已缓冲的, 新的解析好立刻换下来,
 * 不先停下播放器等解析 (见 docs/contributing/code/media/playback-recovery.md「确认接不上时换源」「地址过期时在后台重新取」).
 * 一个 [PlayerSession] 一个.
 *
 * [PlayerSession.loadMedia] 要装的时候播放器里那条正常播着、又已放不下去时这样换 ([begin]): 装别的资源 (换源), 或地址被拒时
 * 装同一条 (重新解析出新地址).
 * - 换下来之前加载状态仍是旧的「已装好」, 画面照常; [preparing] 是后台正在准备的那条;
 * - 旧的缓冲先放完报错时停下它, 加载状态改成正在解析; 这个错不算播放失败 (见 PlaybackFailureRecovery);
 * - 新的在换下来之前就装不成时, 旧的接着放, 报给换源那边换下一条 ([failures]);
 * - 换下来的那一刻从旧的最后播到的位置打开新的, 播放意图跟旧的一样 (这期间暂停了就停着).
 */
class PlaybackHandover internal constructor(
    private val player: MediampPlayer,
    /** 正在播的这一条接不上了: 断流后接着读、换地址都没读到数据 (见 PlaybackLinkTracker.unreachable) */
    val unreachable: Flow<Boolean>,
    /** 正在播的这一条的地址被服务器拒了, 数据源又换不了 (见 PlaybackLinkTracker.rejected) */
    val rejected: Flow<Boolean>,
    private val isUnreachable: () -> Boolean,
    private val isRejected: () -> Boolean,
) {
    private val preparingFlow = MutableStateFlow<Media?>(null)

    /** 旧的还在放、后台正在准备的那一条; 没在这样换时为 `null` */
    val preparing: StateFlow<Media?> = preparingFlow.asStateFlow()

    /** 新的那条在换下来之前就装不成 (解析、打开失败) 时报给换源那边, 旧的接着放 */
    class Failure(val media: Media, val state: VideoLoadingState.Failed)

    private val failuresFlow = MutableSharedFlow<Failure>(extraBufferCapacity = 4)

    /**
     * 后台准备的那条在换下来之前就装不成了 (旧的还在放): 换源那边据此拉黑它、选下一条 (见 SwitchMediaOnPlayerErrorExtension),
     * 旧的照样接着放, 下一条同样在后台装. 这时加载状态仍是旧的「已装好」, 不报失败.
     */
    val failures: SharedFlow<Failure> = failuresFlow.asSharedFlow()

    internal fun reportFailure(media: Media, state: VideoLoadingState.Failed) {
        logger.info { "${media.mediaId} failed to load before the handover ($state), the old one keeps playing" }
        failuresFlow.tryEmit(Failure(media, state))
    }

    /** 为它提示过的那条旧的, 它还在放时: 换的途中又改选了别的, 不再提示 */
    private var announcedFor: String? = null

    /**
     * 开始在后台换成 [next]. [playing] 是播放器里正常播着的那条 (没有或不正常时为 `null`); 它还放得下去时返回 `null`, 照常先停再装.
     * [next] 就是 [playing] 时是重新解析它, 只在地址被拒时这样做. 用完 (换下来、装不成、被取消) 要 [Handover.close].
     *
     * @param scope 跟着旧的播放状态的协程在这里跑
     * @param onOldFailed 旧的在换下来之前报错了 (缓冲放完了): 停下它, 加载状态改成正在加载
     */
    internal fun begin(scope: CoroutineScope, playing: Media?, next: Media, onOldFailed: suspend () -> Unit): Handover? {
        if (playing == null) return null
        val refreshing = playing == next
        if (if (refreshing) !isRejected() else !isUnreachable() && !isRejected()) return null
        val handover = Handover(next)
        preparingFlow.value = next
        logger.info {
            if (refreshing) "Link of ${playing.mediaId} was rejected, keeping its buffer playing while it is resolved again"
            else "${playing.mediaId} cannot go on, keeping its buffer playing while ${next.mediaId} loads"
        }
        // 重新解析同一条不提示: 顺利的话只是换下来时停一下
        if (!refreshing && announcedFor != playing.mediaId) {
            announcedFor = playing.mediaId
            startedFlow.tryEmit(Unit)
        }
        handover.watch = scope.launch {
            combine(player.state, player.currentPositionMillis) { state, position -> state to position }.collect { (state, position) ->
                when {
                    state.mediaStatus == MediaStatus.Ready -> handover.record(state, position)

                    state.mediaStatus is MediaStatus.Error && handover.oldPlaying -> {
                        handover.oldPlaying = false
                        logger.info { "${playing.mediaId} ran out of buffer before ${next.mediaId} was ready" }
                        onOldFailed()
                    }
                }
            }
        }
        return handover
    }

    /** 一次后台换源, 见 [begin] */
    internal inner class Handover(private val next: Media) {
        internal var watch: Job? = null

        /** 旧的最后播到的位置; 没读到过时为 `null` */
        var positionMillis: Long? = null
            internal set

        /** 旧的最后的播放意图 */
        var playWhenReady: Boolean = true
            internal set

        /** 旧的还在放: 没报错, 也还没被换下来 */
        var oldPlaying: Boolean = true
            internal set

        internal fun record(state: PlayerState, position: Long) {
            if (state.mediaStatus != MediaStatus.Ready) return
            playWhenReady = state.playWhenReady
            if (position > 0) positionMillis = position
        }

        /**
         * 要停下旧的了: 最后记一次它播到哪 (新的解析得快时跟着它的协程可能还没跑过), 不再跟着它, 停下时的状态变化不算它报错.
         * 这时仍算在换 ([preparing]), 旧的报的错照旧不算播放失败.
         */
        fun detach() {
            if (oldPlaying) record(player.state.value, player.currentPositionMillis.value)
            oldPlaying = false
            watch?.cancel()
        }

        /** 旧的已停、新的要装进播放器了, 或这次装不成、被取消了 */
        fun close() {
            // 旧的已经停了 (换下来了、缓冲放完了): 以后同一条再接不上要重新提示. 还在放 (换的途中又改选了别的) 时不再提示
            if (!oldPlaying) announcedFor = null
            detach()
            preparingFlow.compareAndSet(next, null)
        }
    }

    companion object {
        private val logger = logger<PlaybackHandover>()

        private val startedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** 开始在后台换源时发一次 (换的途中又改选别的不再发), 界面据此提示 */
        val started: SharedFlow<Unit> = startedFlow.asSharedFlow()
    }
}
