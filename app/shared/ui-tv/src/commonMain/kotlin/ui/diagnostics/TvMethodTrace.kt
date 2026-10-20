/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import android.os.Debug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.File
import kotlin.concurrent.thread

/**
 * 性能诊断里的「方法采样」: ART 的采样式方法追踪 ([Debug.startMethodTracingSampling]), release 包也能用. 每隔一段时间记一次所有线程的栈,
 * 写成 `.trace` (Android Studio 的 Profiler 能打开), 看主线程卡在哪段代码上.
 *
 * 采样本身很重: 后台满载时几行代码也能显示成几百毫秒, 时长以录制或 Perfetto 为准, 这里只用来看卡在哪. 同一时间只采一份, 文件每次覆盖.
 */
object TvMethodTrace {
    sealed interface Status {
        data object Idle : Status
        data class Running(val seconds: Int, val intervalMicros: Int, val endsAtMillis: Long) : Status
    }

    enum class StartResult { STARTED, NO_UI, BUSY }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 上一次采样的结果; 没有时为 null. */
    fun file(): File? = TvPerfDiagnostics.storageDir()?.let { File(it, FILE_NAME) }?.takeIf { it.isFile && it.length() > 0 }

    @Synchronized
    fun start(seconds: Int, intervalMicros: Int): StartResult {
        if (_status.value is Status.Running) return StartResult.BUSY
        val file = TvPerfDiagnostics.storageDir()?.let { File(it, FILE_NAME) } ?: return StartResult.NO_UI
        file.delete()
        try {
            Debug.startMethodTracingSampling(file.absolutePath, BUFFER_BYTES, intervalMicros)
        } catch (e: RuntimeException) {
            logger.warn(e) { "Cannot start method tracing" }
            return StartResult.BUSY
        }
        _status.value = Status.Running(seconds, intervalMicros, System.currentTimeMillis() + seconds * 1000L)
        logger.info { "Method tracing for ${seconds}s, every ${intervalMicros}us" }
        thread(name = "ani-method-trace", isDaemon = true) {
            try {
                Thread.sleep(seconds * 1000L)
            } finally {
                stop()
            }
        }
        return StartResult.STARTED
    }

    @Synchronized
    private fun stop() {
        if (_status.value !is Status.Running) return
        runCatching { Debug.stopMethodTracing() }.onFailure { logger.warn(it) { "Cannot stop method tracing" } }
        _status.value = Status.Idle
        logger.info { "Method tracing finished: ${file()?.length() ?: 0} bytes" }
    }

    private const val FILE_NAME = "method-trace.trace"

    /** 采样缓冲上限: 30 秒、1 毫秒一采、几十个线程大约用到这么多; 满了 ART 自己停 */
    private const val BUFFER_BYTES = 96 * 1024 * 1024

    private val logger = logger<TvMethodTrace>()
}
