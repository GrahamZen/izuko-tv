/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import me.him188.ani.app.domain.player.SeekPreviewDecoderFault
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.File

/**
 * 小画面拖动预览期间进程原生崩溃的痕迹. 有的设备在视频输出换到小画面之后一两秒整个进程 SIGABRT (厂商解码或显卡驱动在本进程里 abort),
 * Java 层收不到错误, [SeekPreviewDecoderFault] 认不出, 用户每拖一次进度条就闪退一次.
 *
 * 输出换到小画面时写一个标记文件 ([onPreviewFrame]), 换回全屏时在里面记下时刻 ([onFullScreen]). 进程崩溃时文件留在盘上
 * (写进了内核的页缓存, 进程死了也在). 下次启动时 [checkPreviousRun] 看标记: 系统退出记录说那个进程是原生崩溃的 (Android 11 起才有退出记录,
 * 更早的系统只能凭标记没清掉), 就交给 [SeekPreviewDecoderFault.recordCrashInPreviousRun], 打开播放页时改成画在全屏上并提示一次.
 *
 * 标记只在主线程上读写 (拖动预览在主线程上换输出), 每次预览最多写两次几十字节的小文件.
 */
object SeekPreviewCrashMarker {
    private val logger = logger<SeekPreviewCrashMarker>()

    private const val FILE_NAME = "seek_preview_window"

    /** 换回全屏后这么久之内的原生崩溃也算: 换回去那一下解码器同样可能出事 (见 [SeekPreviewDecoderFault]). */
    internal const val AFTER_FULL_SCREEN_MILLIS = 10_000L

    /** [checkPreviousRun] 之后才有; 没有时 (别的进程、测试) 什么也不记. */
    @Volatile
    private var file: File? = null

    /** 当前进程的输出是不是在小画面上 (标记里还没有换回全屏的时刻). */
    private var onPreview = false

    /** 启动时调一次 (主进程): 认出上一次运行死在小画面预览里, 并清掉标记. */
    fun checkPreviousRun(context: Context) {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        this.file = file
        if (!file.exists()) return
        val marker = runCatching { Marker.parse(file.readText()) }.getOrNull()
        file.delete()
        if (marker == null) return
        val exitInfoAvailable = Build.VERSION.SDK_INT >= 30
        val exit = if (exitInfoAvailable) previousExit(context, marker.pid) else null
        if (!isCrashDuringPreview(marker, exitInfoAvailable, exit?.first, exit?.second ?: 0L)) {
            logger.info { "Seek preview marker of pid ${marker.pid} left over, not a crash in the preview (exit reason ${exit?.first})" }
            return
        }
        logger.warn { "The previous run (pid ${marker.pid}) crashed during a windowed seek preview, seek previews will show full screen" }
        SeekPreviewDecoderFault.recordCrashInPreviousRun()
    }

    /** 视频输出换到了小画面上 (或小画面换了一块面). */
    internal fun onPreviewFrame() {
        if (onPreview) return
        onPreview = true
        write(Marker(Process.myPid(), backAtMillis = 0L))
    }

    /** 视频输出接回了全屏. */
    internal fun onFullScreen() {
        if (!onPreview) return
        onPreview = false
        write(Marker(Process.myPid(), backAtMillis = System.currentTimeMillis()))
    }

    private fun write(marker: Marker) {
        val file = file ?: return
        runCatching { file.writeText(marker.serialize()) }
    }

    /** 那个进程的退出原因与时刻; 查不到时为 null. */
    private fun previousExit(context: Context, pid: Int): Pair<Int, Long>? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()?.let { it.reason to it.timestamp }
        }.getOrNull()
    }

    /**
     * @param backAtMillis 接回全屏的时刻; 0 = 进程死的时候输出还在小画面上
     */
    internal data class Marker(val pid: Int, val backAtMillis: Long) {
        fun serialize(): String = "$pid $backAtMillis"

        companion object {
            fun parse(text: String): Marker? {
                val parts = text.trim().split(' ')
                if (parts.size != 2) return null
                return Marker(
                    parts[0].toIntOrNull() ?: return null,
                    parts[1].toLongOrNull() ?: return null,
                )
            }
        }
    }
}

/**
 * 留下 [marker] 的那个进程是不是在小画面预览时 (或刚接回全屏 [SeekPreviewCrashMarker.AFTER_FULL_SCREEN_MILLIS] 内) 原生崩溃的.
 *
 * 有退出记录 ([exitInfoAvailable], Android 11 起) 时只认 [ApplicationExitInfo.REASON_CRASH_NATIVE]: 内存不够被杀、用户强行停止都不算.
 * 没有时只能凭输出还在小画面上 (标记里没有接回全屏的时刻) 就没了.
 */
internal fun isCrashDuringPreview(
    marker: SeekPreviewCrashMarker.Marker,
    exitInfoAvailable: Boolean,
    exitReason: Int?,
    exitTimeMillis: Long,
): Boolean {
    if (!exitInfoAvailable) return marker.backAtMillis == 0L
    if (exitReason != ApplicationExitInfo.REASON_CRASH_NATIVE) return false
    return marker.backAtMillis == 0L || exitTimeMillis - marker.backAtMillis <= SeekPreviewCrashMarker.AFTER_FULL_SCREEN_MILLIS
}
