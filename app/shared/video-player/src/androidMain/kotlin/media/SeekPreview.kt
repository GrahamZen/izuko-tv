/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.net.Uri
import android.os.Handler
import android.view.Surface
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.app.domain.player.SeekPreviewDecoderFault
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.io.InterruptedIOException
import kotlin.math.abs

/**
 * 拖动预览时由主播放器解出预览位置的画面, 不另开解码器取帧. 画在哪有两种 (见 [onFullScreen]):
 * - 小画面: 视频输出临时接到预览浮窗里的小画面上 ([attachFrameSurface]), 全屏那层没有新帧送进来, 停在开始时那一帧;
 * - 全屏: 视频输出不换, 预览位置的画面直接画在全屏上. 有的盒子的硬解经不起输出换来换去 (见 [SeekPreviewDecoderFault]).
 *
 * 两种都是播放器对外报告的位置停在开始时 (见 [LibassExoPlayerMediampPlayer.startSeekPreview]).
 *
 * 本机有的数据直接读 (存在本机的部分、BT 已下载的部分、本地文件), 读出关键帧就出画面. 本机没有的先不下: 小画面停在上一个位置,
 * 标成停一下就加载 ([SeekPreviewOverlay.StayToLoad]); 在那里停够 [DOWNLOAD_AFTER_MILLIS] 才去下 ([SeekPreviewOverlay.Loading]),
 * 一路挪过去的位置不下. 在线源由 [PlaybackNetworkGate] 在打开连接之前挡住; BT 由调用方给的 [isAvailable] 判断 (一跳过去就会去下那一段).
 *
 * 小画面里的帧不是圆点这里的就标出来 (见 [overlay]), 别让别处的画面冒充这里. 只有刚出过画面、又挪了一步的那一下留 [STALE_FRAME_MILLIS] 不标:
 * 本机有的一两百毫秒就出, 一路拖过去每一步都标的话只是闪.
 *
 * 期间跳到最近的关键帧, 停下来之后再校准到圆点那一帧 (见 [refine]), 缓冲只读到出画面所需 (见 [ThrottledSourceLoadControl.previewing], 停下来看清楚之后照暂停时往后攒, 见 [park]); 同一时间只有一个跳转在做,
 * 挪得比出画面快时只留最新的位置, 那一个出了画面 (或确认没下载、等太久) 再跳. 不用 media3 的拖动模式: 它也是等上一个跳转出了画面才做下一个,
 * 而跳到没下载的位置时数据被挡住, 那一个出不了画面, 之后的跳转就全卡住了.
 *
 * 画在小画面上时, 小画面接上之前不跳 (跳了画面会出在全屏上). 由 [LibassExoPlayerMediampPlayer.startSeekPreview] 开始;
 * 确认时 [end] (输出接回全屏, 之后由调用方跳到圆点), 取消时 [cancel] (先跳回 [originMillis] 再接回全屏). 画在全屏上时没有接回这一步.
 * 只在主线程上调用.
 */
@AndroidxOptIn(UnstableApi::class)
class SeekPreview internal constructor(
    private val exoPlayer: ExoPlayer,
    private val gate: PlaybackNetworkGate,
    private val loadControl: ThrottledSourceLoadControl,
    /** 本机有没有这个位置的数据 (BT: 那一段下完了没有); 为 null 时只靠 [gate] 挡. */
    private val isAvailable: ((Long) -> Boolean)?,
    /** 预览画面直接画在全屏上, 视频输出不换; 为 false 时画在 [attachFrameSurface] 给的小画面里. */
    val onFullScreen: Boolean,
    private val seek: (Long) -> Unit,
    /** 结束预览时照常跳 (字幕时钟一起拨): 取消时跳回 [originMillis], 在圆点上确认时就地重新解一遍 (见 [endAtTarget]). */
    private val seekPlayback: (Long) -> Unit,
    /** 视频输出接到小画面 (为 null 时哪儿也不画). */
    private val setOutputSurface: (Surface?) -> Unit,
    /** 视频输出接回全屏. */
    private val restoreOutput: () -> Unit,
    private val onEnd: (SeekPreview) -> Unit,
) {
    /** 开始预览时的播放位置: 取消预览时跳回这里. */
    val originMillis: Long = exoPlayer.currentPosition

    /** 小画面跟上最近一次 [seekTo] 没有. */
    private var status = SeekPreviewStatus.Shown

    private val _overlay = MutableStateFlow(SeekPreviewOverlay.None)

    /** 小画面现在该怎么标, 见 [SeekPreviewOverlay]. */
    val overlay: StateFlow<SeekPreviewOverlay> = _overlay.asStateFlow()

    private val _holdingFrame = MutableStateFlow(true)

    /**
     * 全屏停着, 等接着要播的那一帧: 预览期间, 以及结束时就地重新解第一帧的那一会儿 ([cancel] 跳回原处、[endAtTarget] 在圆点上重解; 最多 [FRAME_HOLD_MILLIS]).
     * 这期间的缓冲是预览引起的, 页面不必报.
     */
    val holdingFrame: StateFlow<Boolean> = _holdingFrame.asStateFlow()

    private var isEnded = false

    /** 播放器跳过没有 (没跳过时还在 [originMillis], 取消预览不必跳回). */
    private var hasSeeked = false

    private val handler = Handler(exoPlayer.applicationLooper)
    private val seekParametersBefore = exoPlayer.seekParameters
    private var targetMillis = originMillis

    /** 播放器已经跳到 [targetMillis] 了 (BT 没下载的位置先不跳). */
    private var seekedToTarget = false
    private var seekStartedAt = 0L

    /** 最近一次挪圆点 ([seekTo]) 的时刻: 停下多久了, 见 [refine]. */
    private var movedAt = 0L

    /** 小画面上已经是 [targetMillis] 那一帧本身, 不只是附近的关键帧 (见 [refine]). */
    private var refined = false

    /** 播放器现在按精确位置跳 (校准时), 不是按关键帧. */
    private var seekingExactly = false

    /** 圆点上正好是关键帧, 没再精确跳: 播放器停在这个关键帧上, 缓冲也从它开始 (见 [endAtTarget]). */
    private var atKeyframe = false

    /** 正在跳时 (或小画面还没接上时) 又挪到的位置, 见 [seekTo]. */
    private var pendingMillis: Long? = null

    /** 画预览的小画面, 见 [attachFrameSurface]. */
    private var frameSurface: Surface? = null

    /** 画预览的那一面上画出过帧 (换一个小画面重新算): 一帧都还没有时没有旧画面要标. 全屏上一开始就是开始时那一帧. */
    private var frameOnSurface = onFullScreen

    /** 最近画出的那一帧在片子里的时间 (播放线程上写), 见 [isFrameOfCurrentSeek]. */
    @Volatile
    private var lastFramePositionUs = C.TIME_UNSET

    private val frameMetadataListener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        lastFramePositionUs = presentationTimeUs
    }

    private val onBlocked: () -> Unit = { handler.post(::onNetworkBlocked) }
    private val downloadTarget = Runnable { if (status == SeekPreviewStatus.NotDownloaded) startDownload() }
    private val settleCheck = Runnable(::checkSettled)
    private val staleFrame = Runnable { if (frameOnSurface) _overlay.value = SeekPreviewOverlay.StayToLoad }
    private val refineTarget = Runnable(::refine)
    private val openGateForRefine = Runnable { if (status == SeekPreviewStatus.Refining) gate.open() }
    private val parkTarget = Runnable(::park)

    private val listener = object : Player.Listener {
        // 每次跳转后解出的第一帧 (渲染器重置后的第一帧) 都会报
        override fun onRenderedFirstFrame() {
            frameOnSurface = true
            if (status == SeekPreviewStatus.Shown) return
            if (!isFrameOfCurrentSeek()) {
                logger.info { "Seek preview at $targetMillis ms: frame at ${lastFramePositionUs / 1000} ms is not from this seek" }
                return
            }
            if (status == SeekPreviewStatus.Refining) {
                logger.info { "Seek preview at $targetMillis ms: exact frame shown after ${millisSince(seekStartedAt)}ms" }
                refined = true
            } else {
                logger.info { "Seek preview at $targetMillis ms: frame shown after ${millisSince(seekStartedAt)}ms" }
            }
            showFrame()
            seekPending()
        }
    }

    /** 结束时就地重新解第一帧, 等播放器缓冲好, 见 [holdingFrame]. */
    private val holdListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_BUFFERING) releaseFrame()
        }
    }
    private val releaseFrameTimeout = Runnable(::releaseFrame)
    private var holdStartedAt = 0L

    init {
        loadControl.previewing = true
        gate.close(onBlocked)
        exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        exoPlayer.addListener(listener)
        exoPlayer.setVideoFrameMetadataListener(frameMetadataListener)
        logger.info { "Seek preview started at $originMillis ms" }
    }

    /** 主播放器跳到 [positionMillis] 附近的关键帧. 离片尾太近的跳到片尾前一点: 跳到末尾会播完, 触发自动连播. */
    fun seekTo(positionMillis: Long) {
        if (isEnded) return
        movedAt = System.nanoTime()
        val duration = exoPlayer.duration
        val target = if (duration > END_MARGIN_MILLIS) positionMillis.coerceIn(0, duration - END_MARGIN_MILLIS) else positionMillis
        if (!onFullScreen && frameSurface == null ||
            status == SeekPreviewStatus.Seeking && millisSince(seekStartedAt) < SEEK_SETTLE_TIMEOUT_MILLIS
        ) {
            pendingMillis = target
            return
        }
        pendingMillis = null
        startSeek(target)
    }

    private fun startSeek(target: Long) {
        handler.removeCallbacks(downloadTarget)
        handler.removeCallbacks(settleCheck)
        handler.removeCallbacks(staleFrame)
        handler.removeCallbacks(refineTarget)
        handler.removeCallbacks(openGateForRefine)
        handler.removeCallbacks(parkTarget)
        gate.close(onBlocked) // 停在上一个位置时开过门: 一路挪过去的位置不下
        loadControl.previewParked = false
        if (seekingExactly) {
            seekingExactly = false
            exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
        refined = false
        atKeyframe = false
        val frameWasCurrent = status == SeekPreviewStatus.Shown || status == SeekPreviewStatus.Refining
        targetMillis = target
        seekStartedAt = System.nanoTime()
        if (isAvailable?.invoke(target) == false) {
            seekedToTarget = false
            notDownloaded()
            return
        }
        seekedToTarget = true
        hasSeeked = true
        status = SeekPreviewStatus.Seeking
        // 小画面刚才还是上一个圆点的画面: 新画面多半马上就到, 等一小会儿再标; 本来就不是 (没下载 / 正在下 / 等太久) 的接着标
        if (frameWasCurrent) {
            handler.postDelayed(staleFrame, STALE_FRAME_MILLIS)
        } else {
            _overlay.value = SeekPreviewOverlay.StayToLoad
        }
        seek(target)
        handler.postDelayed(settleCheck, SETTLE_CHECK_MILLIS)
    }

    /**
     * 跳了 (或开始下了) 还没出画面时隔一会儿看一眼: 落在和上一个位置同一个关键帧上时播放器不重新解码 (不出新画面), 也不重新加载 (不会再被挡一次),
     * 只能从状态上看出这一跳已经有了结果 —— 有读取正被挡着就是没下载, 播放器就绪了就是画面已经是这里的.
     *
     * 跳了 [SEEK_SETTLE_TIMEOUT_MILLIS] 既没出画面也没被挡住, 是数据还在路上 (BT 按下载进度估的位置不准, 那一段其实没下完, 跳过去就在下了):
     * 期间又挪过就跳最新的位置, 没挪过就当作停够了, 报正在下.
     */
    private fun checkSettled() {
        if (isEnded) return
        if (status != SeekPreviewStatus.Seeking && status != SeekPreviewStatus.Downloading && status != SeekPreviewStatus.Refining) return
        when {
            status == SeekPreviewStatus.Seeking && gate.isHoldingReads -> notDownloaded()
            exoPlayer.playbackState == Player.STATE_READY -> {
                if (status == SeekPreviewStatus.Refining) refined = true
                showFrame()
                seekPending()
            }

            status == SeekPreviewStatus.Seeking && millisSince(seekStartedAt) >= SEEK_SETTLE_TIMEOUT_MILLIS -> {
                logger.info { "Seek preview at $targetMillis ms: no frame after ${millisSince(seekStartedAt)}ms" }
                if (pendingMillis != null) seekPending() else startDownload()
            }

            else -> handler.postDelayed(settleCheck, SETTLE_CHECK_MILLIS)
        }
    }

    /**
     * 接上 / 换掉画预览的小画面 ([surface] 为 null: 小画面没了, 输出哪儿也不画, 全屏仍停着). 接上之后跳到当前要看的位置:
     * 停着的播放器换输出面后不会把已经解出的那帧再画一遍. 画在全屏上时 ([onFullScreen]) 什么也不做.
     */
    fun attachFrameSurface(surface: Surface?) {
        if (isEnded || onFullScreen) return
        frameSurface = surface
        frameOnSurface = false
        setOutputSurface(surface)
        SeekPreviewDecoderFault.markOutputSwitched()
        if (surface == null) return
        val target = pendingMillis ?: targetMillis.takeIf { hasSeeked } ?: return
        pendingMillis = null
        startSeek(target)
    }

    /**
     * 刚画出的这一帧是不是当前这一跳的. 当前位置没跳 (BT 没下载的位置) 时不是; 跳了也可能不是 —— 之前那一跳的帧刚画出、通知还没送到时又跳了,
     * 或者换小画面后补画的那一帧. 停着的播放器跳完后位置就在那个关键帧上, 画出的帧时间与之对得上才是这一跳的.
     */
    private fun isFrameOfCurrentSeek(): Boolean {
        if (!seekedToTarget) return false
        val framePositionUs = lastFramePositionUs
        if (framePositionUs == C.TIME_UNSET) return true
        return abs(framePositionUs / 1000 - exoPlayer.currentPosition) <= FRAME_POSITION_TOLERANCE_MILLIS
    }

    /** 小画面上已经是当前位置的画面. 只是附近的关键帧的话, 停下来之后再校准到圆点那一帧 (见 [refine]). */
    private fun showFrame() {
        handler.removeCallbacks(staleFrame)
        handler.removeCallbacks(openGateForRefine)
        status = SeekPreviewStatus.Shown
        _overlay.value = SeekPreviewOverlay.None
        if (pendingMillis != null) return
        if (refined) {
            schedulePark()
        } else {
            handler.removeCallbacks(refineTarget)
            handler.postDelayed(refineTarget, (REFINE_AFTER_MILLIS - millisSince(movedAt)).coerceAtLeast(0))
        }
    }

    /** 圆点那一帧已经出来了: 停够 [DOWNLOAD_AFTER_MILLIS] 就往后缓冲 (见 [park]). */
    private fun schedulePark() {
        handler.removeCallbacks(parkTarget)
        handler.postDelayed(parkTarget, (DOWNLOAD_AFTER_MILLIS - millisSince(movedAt)).coerceAtLeast(0))
    }

    /**
     * 停在这里、圆点那一帧也出来了: 确认时多半就从这里播, 闲着的这段时间照暂停时的规则往后缓冲 (见 [ThrottledSourceLoadControl.previewParked]),
     * 要联网的也放行 (已经停够了). 再挪一步时播放器跳转会掐掉这次加载, 不耽误下一个位置.
     */
    private fun park() {
        if (isEnded || status != SeekPreviewStatus.Shown || !refined || pendingMillis != null) return
        logger.info { "Seek preview at $targetMillis ms: parked, buffering ahead" }
        gate.open()
        loadControl.previewParked = true
    }

    /**
     * 停在一个位置上之后校准: 预览跳的是离圆点最近的关键帧 (解得快), 这时再按精确位置跳一次, 小画面换成圆点那一帧本身,
     * 播放器也就停在圆点上、后面缓冲着一点 —— 确认时不用再跳, 落点准、马上就能播. 校准期间小画面留着关键帧那一帧, 不标.
     * 校准要补的数据大多在本机 (关键帧之后、圆点之前那一段); 要联网的话照旧等停够 [DOWNLOAD_AFTER_MILLIS] 才放行.
     */
    private fun refine() {
        if (isEnded || status != SeekPreviewStatus.Shown || refined || pendingMillis != null) return
        if (abs(exoPlayer.currentPosition - targetMillis) <= FRAME_POSITION_TOLERANCE_MILLIS) {
            refined = true // 关键帧就在圆点上
            atKeyframe = true
            schedulePark()
            return
        }
        status = SeekPreviewStatus.Refining
        seekStartedAt = System.nanoTime()
        seekingExactly = true
        exoPlayer.setSeekParameters(SeekParameters.EXACT)
        val untilDownload = DOWNLOAD_AFTER_MILLIS - millisSince(movedAt)
        if (untilDownload <= 0) gate.open() else handler.postDelayed(openGateForRefine, untilDownload)
        seek(targetMillis)
        handler.postDelayed(settleCheck, SETTLE_CHECK_MILLIS)
    }

    /** 当前这一跳有了结果: 期间又挪过的话接着跳到最新的位置. */
    private fun seekPending() {
        val next = pendingMillis ?: return
        pendingMillis = null
        startSeek(next)
    }

    /**
     * 播放器已经精确停在最近一次 [seekTo] 的位置上 (校准完, 或正在校准: 那一跳本来就是精确跳到这里), 后面也缓冲着一点:
     * 确认时直接接着播就行, 再跳一次反而要重新缓冲.
     */
    val isAtTarget: Boolean
        get() = !isEnded && seekedToTarget && pendingMillis == null &&
            (status == SeekPreviewStatus.Shown && refined || status == SeekPreviewStatus.Refining)

    /**
     * 结束预览, 播放器恢复原来的跳转方式与缓冲、恢复联网, 输出接回全屏. 不跳转: 之后由调用方跳到圆点 (那一跳会清空解码器).
     * 播放器要停着调: 换输出时解码器给小画面多解出的几帧会丢, 播着的话声音先走、画面停一会儿.
     */
    fun end() {
        if (!isEnded) finish(FinishMode.End)
    }

    /**
     * 确认时播放器已经停在圆点上 ([isAtTarget]): 结束预览, 输出接回全屏后就地重新解一遍第一帧再播 (数据都在缓冲里, 不联网).
     * 换输出时解码器给小画面多解出的几帧会丢, 不重解的话一开播声音和弹幕先走、画面停一会儿. 重解期间 [holdingFrame] 仍为 true.
     * 画在全屏上时全屏已经是这一帧, 不重解. 播放器要停着调, 之后再置播放.
     */
    fun endAtTarget() {
        if (!isEnded) finish(FinishMode.EndAtTarget)
    }

    /**
     * 取消: 结束预览并跳回 [originMillis]. 先跳再把输出接回全屏: 跳转会清空解码器, 预览位置已经解出、还没画的帧就不会画到全屏上,
     * 全屏一直停在开始那一帧, 直到原处的画面重新解出来 (画在全屏上时则是停在最后预览的那一帧, 直到原处的画面解出来).
     * 跳回去要重新缓冲一下, 这期间 [holdingFrame] 仍为 true.
     * 已经结束的 (比如换了媒体) 什么也不做.
     */
    fun cancel() {
        if (!isEnded) finish(if (hasSeeked) FinishMode.Cancel else FinishMode.End)
    }

    private enum class FinishMode { End, EndAtTarget, Cancel }

    private fun finish(mode: FinishMode) {
        isEnded = true
        handler.removeCallbacks(downloadTarget)
        handler.removeCallbacks(settleCheck)
        handler.removeCallbacks(staleFrame)
        handler.removeCallbacks(refineTarget)
        handler.removeCallbacks(openGateForRefine)
        handler.removeCallbacks(parkTarget)
        frameSurface = null
        exoPlayer.removeListener(listener)
        exoPlayer.clearVideoFrameMetadataListener(frameMetadataListener)
        exoPlayer.setSeekParameters(seekParametersBefore)
        loadControl.previewParked = false
        loadControl.previewing = false
        gate.open()
        when (mode) {
            FinishMode.Cancel -> {
                seekPlayback(originMillis)
                restoreFullScreenOutput()
                logger.info { "Seek preview canceled at $targetMillis ms, back to $originMillis ms" }
            }

            FinishMode.EndAtTarget -> {
                val position = exoPlayer.currentPosition
                val buffered = exoPlayer.totalBufferedDuration
                // 画在全屏上时输出没换过, 全屏上已经是这一帧, 直接接着播
                if (!onFullScreen) {
                    restoreFullScreenOutput()
                    // 跳到当前位置播放器什么也不做 (不清空解码器), 错开一毫秒. 精确跳过来的往前错: 缓冲从前一个关键帧攒起, 还在缓冲里, 往后的也还够开播;
                    // 停在关键帧上的缓冲就从这一帧开始, 往前错就出了缓冲要重新读, 只能往后错
                    seekPlayback(if (atKeyframe || position == 0L) position + 1 else position - 1)
                }
                logger.info { "Seek preview ended at $targetMillis ms, player already there ($position ms, $buffered ms buffered)" }
            }

            FinishMode.End -> {
                restoreFullScreenOutput()
                logger.info { "Seek preview ended at $targetMillis ms" }
            }
        }
        if (mode != FinishMode.End && exoPlayer.playbackState == Player.STATE_BUFFERING) {
            holdStartedAt = System.nanoTime()
            exoPlayer.addListener(holdListener)
            handler.postDelayed(releaseFrameTimeout, FRAME_HOLD_MILLIS)
        } else {
            _holdingFrame.value = false
        }
        onEnd(this)
    }

    /** 小画面上画的: 视频输出接回全屏. 画在全屏上的输出没换过, 什么也不做. */
    private fun restoreFullScreenOutput() {
        if (onFullScreen) return
        restoreOutput()
        SeekPreviewDecoderFault.markOutputSwitched()
    }

    private fun releaseFrame() {
        if (!_holdingFrame.value) return
        handler.removeCallbacks(releaseFrameTimeout)
        exoPlayer.removeListener(holdListener)
        logger.info { "Seek preview: first frame ready after ${millisSince(holdStartedAt)}ms" }
        _holdingFrame.value = false
    }

    private fun onNetworkBlocked() {
        if (isEnded || status != SeekPreviewStatus.Seeking) return
        notDownloaded()
    }

    private fun notDownloaded() {
        logger.info { "Seek preview at $targetMillis ms: not downloaded (${millisSince(seekStartedAt)}ms)" }
        handler.removeCallbacks(staleFrame)
        status = SeekPreviewStatus.NotDownloaded
        _overlay.value = SeekPreviewOverlay.StayToLoad
        handler.postDelayed(downloadTarget, DOWNLOAD_AFTER_MILLIS)
        seekPending()
    }

    /** 在没下载的位置停够了: 去下这一段 (在线源放行挡住的连接, BT 这时才跳过去). 下好了出画面, 或由 [checkSettled] 看出已就绪. */
    private fun startDownload() {
        if (isEnded) return
        logger.info { "Seek preview at $targetMillis ms: downloading" }
        handler.removeCallbacks(staleFrame)
        status = SeekPreviewStatus.Downloading
        _overlay.value = SeekPreviewOverlay.Loading
        seekStartedAt = System.nanoTime()
        gate.open()
        if (!seekedToTarget) {
            seekedToTarget = true
            hasSeeked = true
            seek(targetMillis)
        }
        handler.removeCallbacks(settleCheck)
        handler.postDelayed(settleCheck, SETTLE_CHECK_MILLIS)
    }

    private companion object {
        private val logger = logger<SeekPreview>()

        /** 预览最多跳到片尾前这么多. */
        private const val END_MARGIN_MILLIS = 1_000L

        /** 一跳这么久还没结果 (没出画面也没被挡住) 就不再等它: 直接跳下一个位置, 没有下一个就去下这里. */
        private const val SEEK_SETTLE_TIMEOUT_MILLIS = 1_500L

        /** 在没下载的位置停这么久才去下. */
        private const val DOWNLOAD_AFTER_MILLIS = 1_000L

        /** 结束时就地重新解第一帧, 缓冲超过这么久就不再当作预览的一部分 (页面照常报缓冲). */
        private const val FRAME_HOLD_MILLIS = 1_000L

        /** 跳了还没结果时隔多久看一眼 (见 checkSettled). */
        private const val SETTLE_CHECK_MILLIS = 250L

        /** 刚出过画面又挪了一步, 新画面这么久还没出来才把小画面里的旧画面标出来. */
        private const val STALE_FRAME_MILLIS = 300L

        /** 画出的帧时间与播放器位置差这么多以内算对得上 (见 isFrameOfCurrentSeek); 相邻两个关键帧至少差这么多. */
        private const val FRAME_POSITION_TOLERANCE_MILLIS = 100L

        /** 停下这么久 (且关键帧那一帧已经出来) 才校准到圆点那一帧 (见 refine): 一路拖过去时不白解. */
        private const val REFINE_AFTER_MILLIS = 300L

        private fun millisSince(nanos: Long) = (System.nanoTime() - nanos) / 1_000_000
    }
}

/** 预览画面 (小画面或全屏, 见 [SeekPreview.onFullScreen]) 该怎么标, 见 [SeekPreview.overlay]. */
enum class SeekPreviewOverlay {
    /** 不标: 画面就是圆点这里的, 或者刚挪了一步、新画面马上就到. */
    None,

    /** 画面是别处的, 这里的还没出来: 盖成黑底, 提示停一下就加载. */
    StayToLoad,

    /** 在这里停够了, 正在加载这里的画面: 黑底上画进度环. */
    Loading,
}

/** 拖动预览的画面进展. */
private enum class SeekPreviewStatus {
    /** 小画面就是最近一次跳到的位置 (或预览刚开始、还没跳). */
    Shown,

    /** 跳了, 那里的画面还没出来. */
    Seeking,

    /** 附近的关键帧已经出来了, 正在校准到圆点那一帧 (见 [SeekPreview.refine]). */
    Refining,

    /** 跳到的位置本机没有数据, 小画面停在上一个位置; 停够一会儿才去下. */
    NotDownloaded,

    /** 停够了, 正在下这个位置的数据. */
    Downloading,
}

/**
 * 拖动预览期间挡住播放器的联网读取 (见 [SeekPreview]): 本机没有的部分要去网上读时, 在打开连接之前等, 放行 ([open]) 才接着读.
 * 等的时候播放器跳到别处会打断加载线程 (media3 的 Loader 取消加载时 interrupt), 这里随之抛 [InterruptedIOException].
 */
internal class PlaybackNetworkGate {
    private val lock = Object()
    private var closed = false
    private var onBlocked: (() -> Unit)? = null
    private var waiting = 0

    /** 关着, 而且有读取正在门口等. */
    val isHoldingReads: Boolean get() = synchronized(lock) { closed && waiting > 0 }

    /** 关上; 之后每有一次读取被挡住, 在加载线程上调一次 [onBlocked]. */
    fun close(onBlocked: () -> Unit) = synchronized(lock) {
        closed = true
        this.onBlocked = onBlocked
    }

    fun open() = synchronized(lock) {
        closed = false
        onBlocked = null
        lock.notifyAll()
    }

    /** 包一层: 打开 [factory] 建的数据源之前先过这道门. */
    fun wrap(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory { GatedDataSource(factory.createDataSource(), this) }

    /** 在加载线程上: 门关着就等到打开. */
    fun awaitOpen() = synchronized(lock) {
        if (!closed) return
        onBlocked?.invoke()
        waiting++
        try {
            while (closed) {
                try {
                    lock.wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted while network reads are held for the seek preview")
                }
            }
        } finally {
            waiting--
        }
    }
}

@AndroidxOptIn(UnstableApi::class)
private class GatedDataSource(
    private val upstream: DataSource,
    private val gate: PlaybackNetworkGate,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        gate.awaitOpen()
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
