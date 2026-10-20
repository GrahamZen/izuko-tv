/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import me.him188.ani.app.video.player.R
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.VideoAspectRatio
import kotlin.math.roundToInt
import androidx.media3.ui.R as Media3UiR

/**
 * 同 mediamp 的 `ExoPlayerMediampPlayerSurface`, 只是 [PlayerView] 从精简布局 ([R.layout.izuko_player_view]) 里建.
 *
 * `PlayerView(context)` 不管 `useController` 是什么, 构造时都先膨胀默认布局里的整套控制栏 (十几个按钮、进度条、矢量图标)、
 * 缓冲圈、封面图与错误提示 —— 我们一个都不用. 播放器实例一换 (换集重建、回到保留的会话) 就要重建一次视图, 都在主线程上.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun SlimExoPlayerSurface(
    mediampPlayer: ExoPlayerMediampPlayer,
    modifier: Modifier = Modifier,
    configuration: PlayerView.() -> Unit = {},
) {
    val aspectRatioMode by mediampPlayer.features[VideoAspectRatio.Key]?.mode?.collectAsState() ?: return

    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    AndroidView(
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.izuko_player_view, null) as PlayerView).apply {
                this.player = mediampPlayer.impl
                configuration()
            }.also { playerView = it }
        },
        modifier,
        update = { view ->
            view.player = mediampPlayer.impl
            view.resizeMode = when (aspectRatioMode) {
                AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectRatioMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
    )
    val player = mediampPlayer.impl
    val view = playerView
    if (view != null) {
        DisposableEffect(view, player) {
            // 视频特效下 media3 不报真实画面尺寸, PlayerView 拿不到宽高比; 同 mediamp, 退回读选中轨道的 Format
            val listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) = applyVideoAspectRatioFallback(view, player)
                override fun onVideoSizeChanged(videoSize: VideoSize) = applyVideoAspectRatioFallback(view, player)
            }
            player.addListener(listener)
            applyVideoAspectRatioFallback(view, player)
            onDispose { player.removeListener(listener) }
        }
    }
}

@OptIn(UnstableApi::class)
private fun applyVideoAspectRatioFallback(view: PlayerView, player: ExoPlayer) {
    if (player.videoSize.width > 0 && player.videoSize.height > 0) return // PlayerView 自己会设
    val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return
    val format = group.getTrackFormat((0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0)
    if (format.width <= 0 || format.height <= 0) return
    val width = (format.width * format.pixelWidthHeightRatio).roundToInt()
    val rotated = format.rotationDegrees == 90 || format.rotationDegrees == 270
    val aspect = if (rotated) format.height.toFloat() / width else width.toFloat() / format.height
    view.findViewById<AspectRatioFrameLayout>(Media3UiR.id.exo_content_frame)?.setAspectRatio(aspect)
}
