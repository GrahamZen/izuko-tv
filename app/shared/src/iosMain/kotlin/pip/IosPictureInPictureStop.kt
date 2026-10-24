/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.pip

import me.him188.ani.app.domain.episode.ipadDiag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.MediampPlayer
import kotlin.time.Duration.Companion.milliseconds

/**
 * 系统画中画收起时 [IosPictureInPictureController] 要做的两件事:
 * - 用户点了小窗上的「还原」按钮 (AVKit 先问 restoreUserInterface 再收起): 收起之后发一次 [requests], 应用内小窗收到后展开成全屏播放页.
 *   从播放页进的画中画收回播放页本身, 没有人听, 什么也不发生.
 * - 回到应用 (点了还原, 或回前台时应用自己收起) 时系统有时把正在放的视频暂停: 收起前在放、收起后 [KEEP_PLAYING_WINDOW] 内变成暂停的, 接着放.
 *   点小窗上的关闭按钮那种收起不管, 系统本来就该停.
 */
internal class IosPictureInPictureStop(private val player: MediampPlayer) {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: Flow<Unit> get() = _requests

    // 不跟着控制器 dispose: 还原后小窗展开成播放页, 小窗的控制器随即销毁, 等暂停的这一小段还要接着等
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var restoreTapped = false
    private var stoppedByApp = false
    private var playingBeforeStop = false

    fun onRestoreTapped() {
        restoreTapped = true
        ipadDiag("pip restore tapped")
    }

    /** 应用自己要收起小窗 (回前台、页面要求退出). */
    fun onStopRequestedByApp() {
        stoppedByApp = true
    }

    fun onWillStop() {
        playingBeforeStop = player.state.value.playWhenReady
        ipadDiag("pip will stop: playing=$playingBeforeStop byApp=$stoppedByApp restore=$restoreTapped")
    }

    fun onDidStop() {
        val restore = restoreTapped
        val backToApp = restore || stoppedByApp
        restoreTapped = false
        stoppedByApp = false
        ipadDiag("pip did stop: restore=$restore backToApp=$backToApp playingBefore=$playingBeforeStop playingNow=${player.state.value.playWhenReady}")
        if (restore) _requests.tryEmit(Unit)
        if (backToApp && playingBeforeStop) keepPlaying()
    }

    private fun keepPlaying() {
        scope.launch {
            val resumed = withTimeoutOrNull(KEEP_PLAYING_WINDOW) {
                player.state.first { !it.playWhenReady }
                player.play()
            }
            ipadDiag(if (resumed != null) "pip: paused right after stop, resumed" else "pip: still playing after stop")
        }
    }

    private companion object {
        /** iPad 上见过的系统暂停在收起后 1 秒左右到. */
        val KEEP_PLAYING_WINDOW = 1500.milliseconds
    }
}
