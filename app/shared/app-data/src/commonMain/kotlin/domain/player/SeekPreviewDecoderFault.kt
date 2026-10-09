/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 */
object SeekPreviewDecoderFault {
    /** 换输出之后这么久之内的解码错误, 算作换输出弄坏的. */
    val SWITCH_WINDOW = 20.seconds

    /** 认出一次之后这么久之内的解码错误, 算作解码器还没缓过来 (实测二十到五十几秒后新建的解码器才正常). */
    val FAULT_WINDOW = 60.seconds

    internal var timeSource: TimeSource = TimeSource.Monotonic
    private val lastSwitch = MutableStateFlow<TimeMark?>(null)
    private val faultStarted = MutableStateFlow<TimeMark?>(null)

    private val _switchedToFullScreen = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 认出这种错之后拖动预览自动改成画在全屏上了: 页面据此提示一次. */
    val switchedToFullScreen: SharedFlow<Unit> = _switchedToFullScreen.asSharedFlow()

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

    internal fun notifySwitchedToFullScreen() {
        _switchedToFullScreen.tryEmit(Unit)
    }

    internal fun reset() {
        lastSwitch.value = null
        faultStarted.value = null
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
