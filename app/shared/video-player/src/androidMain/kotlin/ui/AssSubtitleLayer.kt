/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.media.AssHandler

/**
 * 播放器视图里画 ASS 字幕的那一层 ([LiftableAssSubtitleView]), 只在选中了 ASS 字幕轨时挂在 [container] (播放器的字幕视图) 里.
 *
 * 这层是铺满播放器的 TextureView, 挂着就占着至少一张与它一样大的图形缓冲 (1080p 界面 7.9 MiB, 4K 界面 31.6 MiB),
 * 隐藏也不释放, 要从视图树上拿下来. SRT 这类字幕、没有字幕时都不挂.
 *
 * [container] 在窗口上的期间跟着播放器的轨道变化挂上 / 拿下. [container] 离开窗口时那层随它一起离开、释放缓冲,
 * 回到窗口时再照当时的轨道对一遍. 挪动距离 ([bottomLift]) 记在这里, 新挂上的那层照它画.
 */
internal class AssSubtitleLayer(
    private val container: ViewGroup,
    private val player: Player,
    private val assHandler: AssHandler,
) : Player.Listener, View.OnAttachStateChangeListener {
    /** 此刻挂着的那层; 没选中 ASS 字幕轨时为 null. */
    var view: LiftableAssSubtitleView? = null
        private set

    /** 下半部分的字幕往上挪多少像素, 见 [LiftableAssSubtitleView.bottomLift]. 主线程写. */
    var bottomLift: Float = 0f
        set(value) {
            field = value
            view?.bottomLift = value
        }

    init {
        container.addOnAttachStateChangeListener(this)
        if (container.isAttachedToWindow) onViewAttachedToWindow(container)
    }

    override fun onViewAttachedToWindow(v: View) {
        player.addListener(this)
        update(player.currentTracks)
    }

    override fun onViewDetachedFromWindow(v: View) {
        player.removeListener(this)
    }

    override fun onTracksChanged(tracks: Tracks) = update(tracks)

    private fun update(tracks: Tracks) {
        if (tracks.hasSelectedAssTrack()) add() else remove()
    }

    private fun add() {
        if (view != null) return
        view = LiftableAssSubtitleView(container.context, assHandler).also {
            it.bottomLift = bottomLift
            container.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun remove() {
        val current = view ?: return
        view = null
        container.removeView(current)
    }
}

/**
 * 选中了 ASS 字幕轨. 判据同 [AssHandler] 找要画的轨: 格式是 `text/x-ssa`, 或者原本是 (解析成字幕块之后原格式记在 `codecs` 里).
 */
@OptIn(UnstableApi::class)
internal fun Tracks.hasSelectedAssTrack(): Boolean = groups.any { group ->
    group.isSelected && (0 until group.length).any { index ->
        val format = group.getTrackFormat(index)
        format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA
    }
}
