/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 播放时另开的解码器 (进度条缩略图取帧) 最近一次在用的时刻.
 *
 * 硬解实例有限的机器上, 取缩略图的解码器会抢走主播放器的. 系统收回时报 `ERROR_CODE_DECODING_RESOURCES_RECLAIMED`;
 * 厂商驱动直接抢占时 (`OMX_ErrorResourcesPreempted`, issue #10) Java 层只剩一个没有消息的 IllegalStateException,
 * 报成普通的 `ERROR_CODE_DECODING_FAILED`, 与片源本身解不了分不开, 只能看出错前刚取过缩略图没有 (见 [isDecoderPreempted]).
 */
object SecondaryDecoderActivity {
    /** 取缩略图之后这么久之内的解码失败, 算作被它抢走了解码器. */
    val PREEMPTION_WINDOW = 20.seconds

    internal var timeSource: TimeSource = TimeSource.Monotonic
    private val lastUse = MutableStateFlow<TimeMark?>(null)

    /** 另开的解码器开始或结束一次工作 (建取帧会话、取一帧) 时调用. */
    fun markUsed() {
        lastUse.value = timeSource.markNow()
    }

    internal fun usedRecently(): Boolean = lastUse.value?.let { it.elapsedNow() <= PREEMPTION_WINDOW } == true

    internal fun reset() {
        lastUse.value = null
    }
}

/**
 * 播放器报的 [error] 是不是主播放器的解码器被抢走了: 源本身没有问题, 重新装一次通常就能接着播.
 *
 * - `RESOURCES_RECLAIMED`: 系统收回, 一定是.
 * - `ERROR_CODE_DECODING_FAILED`: 只有 [SecondaryDecoderActivity.PREEMPTION_WINDOW] 内取过缩略图才算, 其余照旧当作片源的问题.
 *
 * 按消息匹配: mediamp 的 PlaybackException 消息带着 ExoPlayer 的错误码名, 这里是 commonMain, 看不见 ExoPlayer 的异常类.
 */
fun isDecoderPreempted(error: Throwable?): Boolean {
    var current = error
    var depth = 0
    while (current != null && depth++ < 8) {
        val message = current.message.orEmpty()
        if (message.contains("RESOURCES_RECLAIMED")) return true
        if (message.contains("ERROR_CODE_DECODING_FAILED") && SecondaryDecoderActivity.usedRecently()) return true
        current = current.cause
    }
    return false
}
