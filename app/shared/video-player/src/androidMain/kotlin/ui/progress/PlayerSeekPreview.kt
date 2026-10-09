/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.progress

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.player_frame_preview_stay_to_load
import me.him188.ani.app.videoplayer.media.LibassExoPlayerMediampPlayer
import me.him188.ani.app.videoplayer.media.SeekPreview
import me.him188.ani.app.videoplayer.media.SeekPreviewOverlay
import org.jetbrains.compose.resources.stringResource
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.source.SeekableInputMediaData
import java.util.concurrent.atomic.AtomicReference

/**
 * 拖动预览由主播放器出画面 (见 [SeekPreview]) 的界面接线. 页面只在拖动的几个节点各调一次:
 * 进入拖动且画面停着时 [begin], 挪圆点时 [seekTo] (接进度条的 onPreview), 确认时 [commit] 再由页面跳到圆点, 取消时 [cancel].
 *
 * 画在小画面上时, 预览期间 [framePreview] 的画面位画主播放器解出的小画面, 标注 (不是这里的画面: 停一下就加载 / 加载中) 跟着 [SeekPreview.overlay];
 * 画在全屏上时 ([begin] 的 onFullScreen) 浮窗只显示时间, 标注由页面在全屏上画 ([SeekPreviewFullScreenLayer]), 弹幕由页面按 [hidesFullScreenExtras] 藏起来.
 * 平时画面位不取缩略图, 浮窗只显示时间 (边播边选时主播放器腾不出来). 页面离开时进行中的预览自动取消.
 * 只在主线程上调用.
 */
@Stable
class PlayerSeekPreviewState internal constructor(
    private val player: MediampPlayer,
    private val cacheProgress: AtomicReference<MediaCacheProgressInfo?>,
) {
    /** 交给进度条的帧预览状态 (`MediaProgressSlider` 的 framePreview). */
    val framePreview = MediaProgressFramePreviewState(
        fetchFrame = { null },
        debounceMillis = 0,
        fetchesUncachedPositions = { true },
        reportsLoadStatus = true,
        isSupported = { false },
    )

    /** 进行中的预览. */
    internal var active: SeekPreview? by mutableStateOf(null)
        private set

    /** 全屏还停在开始那一帧的预览 (进行中, 或取消后正跳回原处), 见 [holdsFrame]. */
    internal var holding: SeekPreview? by mutableStateOf(null)

    /**
     * 预览进行中, 以及取消后跳回原处、还没缓冲好的那一会儿 (见 [SeekPreview.holdingFrame]): 全屏停在开始拖之前的那一帧
     * (画在全屏上时是预览位置的画面, 取消后停在最后预览的那一帧). 这期间主播放器的缓冲是预览引起的 (每挪一步都要重新缓冲一下),
     * 页面据此不报缓冲.
     */
    val holdsFrame: Boolean get() = holding != null

    /** 画在全屏上的预览, 那一面该怎么标 (见 [SeekPreviewFullScreenLayer]); 画在小画面上时一直是 [SeekPreviewOverlay.None]. */
    internal var fullScreenOverlay: SeekPreviewOverlay by mutableStateOf(SeekPreviewOverlay.None)
        private set

    /**
     * 正在画在全屏上的预览: 全屏上是预览位置的画面, 弹幕停在开始拖的那一刻 (播放位置钉在那里), 盖在上面对不上, 页面据此藏起来.
     * 字幕由播放器视图自己藏 (见 `SeekPreviewSubtitleHiding`).
     */
    val hidesFullScreenExtras: Boolean get() = active?.onFullScreen == true

    /**
     * 进入拖动 (画面停着时): 开始预览. 播放器不支持时什么也不做, 浮窗只显示时间.
     * @param onFullScreen 预览画面直接画在全屏上 (视频输出不换, 浮窗只显示时间); 否则画在浮窗的小画面里, 见 [SeekPreview.onFullScreen]
     */
    fun begin(onFullScreen: Boolean = false) {
        if (active != null) return
        val libassPlayer = player as? LibassExoPlayerMediampPlayer ?: return
        val preview = libassPlayer.startSeekPreview(isAvailable(), onFullScreen) ?: return
        active = preview
        holding = preview
        if (onFullScreen) return
        // 在挪圆点之前接上: 浮窗出现时画面位就是小画面, 不先按缩略图收起
        framePreview.liveFrame = { modifier -> SeekPreviewFrame(preview, modifier) }
    }

    /** 挪圆点: 主播放器跳到 [positionMillis] 附近的关键帧, 画进小画面. 没在预览时什么也不做. */
    fun seekTo(positionMillis: Long) {
        active?.seekTo(positionMillis)
    }

    /**
     * 确认: 结束预览 (视频输出接回全屏) 并跳到 [positionMillis]. 停在圆点上校准过 (或正在校准) 时播放器已经精确停在那里、
     * 后面缓冲着一点, 只就地重解一下第一帧, 不联网 (见 [SeekPreview.endAtTarget]); 否则照常跳过去. 没在预览时也照常跳.
     * 播放器停着时调, 之后再置播放: 换输出时丢掉的那几帧要先补上, 不然一开播画面会停一会儿.
     */
    fun commit(positionMillis: Long) {
        val preview = active
        if (preview == null) {
            player.seekTo(positionMillis)
            return
        }
        val atTarget = preview.isAtTarget
        closePopupFrame()
        if (atTarget) {
            preview.endAtTarget()
        } else {
            preview.end()
            player.seekTo(positionMillis)
        }
    }

    /** 取消: 结束预览, 跳过的话跳回开始拖之前的位置 (全屏一直停在那一帧, 直到原处的画面重新解出来, 见 [SeekPreview.cancel]). */
    fun cancel() {
        val preview = active ?: return
        closePopupFrame()
        preview.cancel()
    }

    private fun closePopupFrame() {
        active = null
        framePreview.liveFrame = null
        framePreview.liveFrameStatus = FramePreviewLoadStatus.Idle
        fullScreenOverlay = SeekPreviewOverlay.None
    }

    /** BT: 没下完的那一段一跳过去就会去下, 先看下载到了没有 (在线源由播放器在联网前自己挡). */
    private fun isAvailable(): ((Long) -> Boolean)? {
        if (player.mediaData.value !is SeekableInputMediaData) return null
        return { positionMillis ->
            val total = player.mediaProperties.value?.durationMillis ?: 0L
            total <= 0 || cacheProgress.get().isPositionCached(positionMillis.toFloat() / total)
        }
    }

    internal fun syncOverlay(preview: SeekPreview, overlay: SeekPreviewOverlay) {
        if (preview.onFullScreen) {
            fullScreenOverlay = overlay
            return
        }
        framePreview.liveFrameStatus = when (overlay) {
            SeekPreviewOverlay.None -> FramePreviewLoadStatus.Idle
            SeekPreviewOverlay.StayToLoad -> FramePreviewLoadStatus.NotDownloaded
            SeekPreviewOverlay.Loading -> FramePreviewLoadStatus.Loading
        }
    }
}

/**
 * @param cacheProgressInfoFlow 进度条上的缓存进度 (BT 据此判断某个位置下完了没有)
 */
@Composable
fun rememberPlayerSeekPreviewState(
    player: MediampPlayer,
    cacheProgressInfoFlow: Flow<MediaCacheProgressInfo>,
): PlayerSeekPreviewState {
    // 下载进度一直在变, 不进组合 (读进组合会让整个页面跟着重组), 开始预览时才看
    val cacheProgress = remember(player) { AtomicReference<MediaCacheProgressInfo?>(null) }
    val state = remember(player) { PlayerSeekPreviewState(player, cacheProgress) }
    LaunchedEffect(cacheProgressInfoFlow) {
        cacheProgressInfoFlow.collect { cacheProgress.set(it) }
    }
    // 在协程里看进行中的预览, 不在组合里读: 开始 / 结束预览时不必让整个页面重组
    LaunchedEffect(state) {
        snapshotFlow { state.active }.collectLatest { preview ->
            preview?.overlay?.collect { state.syncOverlay(preview, it) }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.holding }.collectLatest { preview ->
            preview ?: return@collectLatest
            preview.holdingFrame.first { !it }
            if (state.holding === preview) state.holding = null
        }
    }
    DisposableEffect(state) {
        onDispose { state.cancel() }
    }
    return state
}

/**
 * 预览浮窗里的小画面: 预览期间主播放器的视频输出接到这里 (见 [SeekPreview.attachFrameSurface]). 画面没了 (浮窗收起) 时交还,
 * 输出哪儿也不画, 直到预览结束接回全屏.
 *
 * 用 SurfaceView: 解出的帧由系统合成器直接缩小显示, 不经过应用的渲染线程. TextureView 要由渲染线程把每一帧视频导进 GPU 再画,
 * 联发科这类电视上导一帧要卡几百毫秒到一秒多, 而渲染线程是所有窗口共用的, 整个界面跟着停. 代价是这一层是独立的显示层,
 * 浮窗的圆角裁不到它, 所以接着它的时候浮窗与画面位都是直角 (见 `previewPopupShape`); 盖在上面的标注与时间照常画
 * (它在浮窗窗口之下, 浮窗在它上面画的东西盖得住它).
 */
@Composable
private fun SeekPreviewFrame(
    preview: SeekPreview,
    modifier: Modifier = Modifier,
) {
    // 换一次预览就换一个视图: 回调里认的是建视图时那一次预览
    key(preview) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = preview.attachFrameSurface(holder.surface)

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            // 返回之前播放器已经不再往这里画 (attachFrameSurface 等播放线程换完输出才返回)
                            override fun surfaceDestroyed(holder: SurfaceHolder) = preview.attachFrameSurface(null)
                        },
                    )
                }
            },
            modifier = modifier,
        )
    }
}

/**
 * 画在全屏上的预览 (见 [PlayerSeekPreviewState.begin] 的 onFullScreen) 的标注, 由页面叠在视频与弹幕之上、控制层之下:
 * 全屏上的画面还是别处的时候盖成黑底, 别让它冒充圆点这里的画面 —— 停一下就加载 (提示文字), 或正在加载 (进度环). 同小画面上的标注.
 */
@Composable
fun SeekPreviewFullScreenLayer(
    state: PlayerSeekPreviewState,
    modifier: Modifier = Modifier,
) {
    val overlay = state.fullScreenOverlay
    if (overlay == SeekPreviewOverlay.None) return
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when (overlay) {
            SeekPreviewOverlay.StayToLoad -> Text(
                stringResource(Lang.player_frame_preview_stay_to_load),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )

            SeekPreviewOverlay.Loading -> CircularProgressIndicator(color = Color.White)
            SeekPreviewOverlay.None -> {}
        }
    }
}
