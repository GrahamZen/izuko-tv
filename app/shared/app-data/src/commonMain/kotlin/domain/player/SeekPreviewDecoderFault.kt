/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 拖动预览把主播放器的视频输出换到小画面上、结束时再换回全屏 (video-player 的 `SeekPreview`), 有的盒子的硬解经不起这样换:
 * Amlogic 的 `OMX.amlogic.*.decoder.awesome` (Android 9) 在小画面上解过帧之后, 接下来一两次换输出时解码器进入错误状态,
 * 报 `ERROR_CODE_DECODING_FAILED` (Java 层是一个没有消息的 IllegalStateException); 之后十几到几十秒里新建的解码器也是坏的
 * (同样的错, 或者跳转时 flush 报 CodecException). 片源本身没有问题, 换源只会把能播的源一个个拉黑.
 *
 * 这里记下换输出的时刻 ([markOutputSwitched]), 据此认出这种错 ([isFault]): 换过输出之后 [SWITCH_WINDOW] 内的解码错误,
 * 以及认出一次之后 [FAULT_WINDOW] 内的解码错误 (解码器还坏着). 认出之后由 `SeekPreviewDecoderFaultExtension` 原地重载,
 * 并把拖动预览改成直接画在全屏上 (不再换输出, 见 [me.him188.ani.app.data.models.preference.SeekPreviewDisplay]).
 *
 * 有的设备不报错, 而是换到小画面一两秒后整个进程原生崩溃 (SIGABRT, 厂商解码或显卡驱动在本进程里 abort), 这里什么也收不到.
 * 那种由安卓端在下次启动时认出 (video-player 的 `SeekPreviewCrashMarker`), 记到 [recordCrashInPreviousRun], 打开播放页时同样改成全屏.
 */
object SeekPreviewDecoderFault {
    /** 换输出之后这么久之内的解码错误, 算作换输出弄坏的. */
    val SWITCH_WINDOW = 20.seconds

    /** 认出一次之后这么久之内的解码错误, 算作解码器还没缓过来 (实测二十到五十几秒后新建的解码器才正常). */
    val FAULT_WINDOW = 60.seconds

    internal var timeSource: TimeSource = TimeSource.Monotonic
    private val lastSwitch = MutableStateFlow<TimeMark?>(null)
    private val faultStarted = MutableStateFlow<TimeMark?>(null)

    private val crashedInPreviousRun = MutableStateFlow(false)
    private val _notice = MutableStateFlow<Reason?>(null)

    /** 拖动预览为什么自动改成了画在全屏上. */
    enum class Reason {
        /** 换输出之后解码出错 ([isFault]) */
        DECODER_ERROR,

        /** 上一次运行在小画面预览时原生崩溃 ([recordCrashInPreviousRun]) */
        CRASHED,
    }

    /**
     * 拖动预览自动改成画在全屏上了、还没提示的原因: 页面提示一次后 [consumeNotice].
     * 上次闪退的那种在打开播放页时就改了, 那时提示的界面可能还没出来, 所以留着等它来取.
     */
    val notice: StateFlow<Reason?> = _notice.asStateFlow()

    /** 拖动预览刚换过主播放器的视频输出 (换到小画面上、换回全屏、或小画面没了). */
    fun markOutputSwitched() {
        lastSwitch.value = timeSource.markNow()
    }

    /** [error] 是不是拖动预览换输出弄坏了解码器 (见 [SeekPreviewDecoderFault]). 不改任何状态. */
    fun isFault(error: Throwable?): Boolean {
        if (!isDecoderFailure(error)) return false
        return lastSwitch.value.isWithin(SWITCH_WINDOW.inWholeMilliseconds) ||
                faultStarted.value.isWithin(FAULT_WINDOW.inWholeMilliseconds)
    }

    /**
     * 认出了一次 (调用方已经用 [isFault] 判过). 返回这是不是新的一轮: 上一轮的 [FAULT_WINDOW] 已经过了 (或从没有过).
     * 一轮从第一次认出时算起, 期间再认出不延长, 原地重试不会没完没了.
     */
    internal fun recordFault(): Boolean {
        if (faultStarted.value.isWithin(FAULT_WINDOW.inWholeMilliseconds)) return false
        faultStarted.value = timeSource.markNow()
        return true
    }

    internal fun notifySwitchedToFullScreen(reason: Reason) {
        _notice.value = reason
    }

    /** 页面已经提示过 [notice]. */
    fun consumeNotice() {
        _notice.value = null
    }

    /** 上一次运行在小画面预览时原生崩溃了 (安卓端启动时认出). 打开播放页时由 `SeekPreviewDecoderFaultExtension` 处理, 见 [consumeCrashInPreviousRun]. */
    fun recordCrashInPreviousRun() {
        crashedInPreviousRun.value = true
    }

    /** 取走 [recordCrashInPreviousRun] 记下的那次: 只处理一次. */
    internal fun consumeCrashInPreviousRun(): Boolean = crashedInPreviousRun.getAndUpdate { false }

    internal fun reset() {
        lastSwitch.value = null
        faultStarted.value = null
        crashedInPreviousRun.value = false
        _notice.value = null
    }

    private fun TimeMark?.isWithin(millis: Long): Boolean = this != null && elapsedNow().inWholeMilliseconds <= millis
}

/**
 * 解码器自己坏了 (不是格式不支持、解码器建不起来): 播放器报 `ERROR_CODE_DECODING_FAILED`, 或者错误链上有 `MediaCodec.CodecException`
 * (跳转时 flush 失败报成 `ERROR_CODE_FAILED_RUNTIME_CHECK`).
 *
 * 按消息与类名匹配: mediamp 的 PlaybackException 消息带着 ExoPlayer 的错误码名, 这里是 commonMain, 看不见 ExoPlayer 与安卓的异常类.
 */
internal fun isDecoderFailure(error: Throwable?): Boolean {
    var current = error
    var depth = 0
    while (current != null && depth++ < 8) {
        if (current.message.orEmpty().contains("ERROR_CODE_DECODING_FAILED")) return true
        if (current.toString().startsWith("android.media.MediaCodec\$CodecException")) return true
        current = current.cause
    }
    return false
}
