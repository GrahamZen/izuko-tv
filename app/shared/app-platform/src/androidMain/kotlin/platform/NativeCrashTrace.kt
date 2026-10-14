/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 启动时把上次原生崩溃的现场写进日志 (内容见 [NativeCrashReport]), 行首 `[NativeCrash]`.
 *
 * - Android 12 起: 系统退出记录里 [ApplicationExitInfo.REASON_CRASH_NATIVE] 那条带 tombstone ([ApplicationExitInfo.getTraceInputStream]).
 * - 更早: 读系统日志的 crash 缓冲区 (`logcat -b crash`). 应用只读得到自己 UID 写的, crash_dump 写的崩溃回溯与崩溃的进程同 UID; 重启设备才清空.
 *
 * tombstone 可能有几百 KB, 读与解都在后台线程上. 同一次崩溃只写一次: 记下写过的最晚崩溃时刻.
 */
internal object NativeCrashTrace {
    private val logger = logger<NativeCrashTrace>()

    private const val PREFS_NAME = "native_crash_trace"
    private const val KEY_LOGGED_UNTIL = "logged_until_millis"

    /** 退出记录里攒着好几次原生崩溃时, 一次启动最多写这么多次 (最近的几次). */
    private const val MAX_CRASHES = 2

    private const val LOGCAT_TIMEOUT_SECONDS = 5L

    fun logInBackground(context: Context) {
        val appContext = context.applicationContext
        thread(name = "NativeCrashTrace", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            runCatching { logBlocking(appContext) }
                .onFailure { logger.warn(it) { "Failed to read the previous native crash" } }
        }
    }

    private fun logBlocking(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val loggedUntil = prefs.getLong(KEY_LOGGED_UNTIL, 0L)
        val newest = if (Build.VERSION.SDK_INT >= 31) logTombstones(context, loggedUntil) else logCrashBuffer(loggedUntil)
        if (newest > loggedUntil) prefs.edit().putLong(KEY_LOGGED_UNTIL, newest).apply()
    }

    @RequiresApi(31)
    private fun logTombstones(context: Context, loggedUntil: Long): Long {
        val am = context.getSystemService(ActivityManager::class.java) ?: return loggedUntil
        val crashes = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestamp > loggedUntil }
            .sortedBy { it.timestamp }
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
        for (exit in crashes.takeLast(MAX_CRASHES)) {
            val bytes = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull()
            val details = when {
                bytes == null -> "no tombstone"
                else -> runCatching { NativeCrashReport.describeTombstone(bytes) }.getOrElse { "tombstone unreadable: $it" }
            }
            logger.warn { "[NativeCrash] ${format.format(Date(exit.timestamp))} ${exit.processName} (pid ${exit.pid}): $details" }
        }
        return crashes.maxOfOrNull { it.timestamp } ?: loggedUntil
    }

    private fun logCrashBuffer(loggedUntil: Long): Long {
        val process = ProcessBuilder("logcat", "-d", "-b", "crash", "-v", "epoch")
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val (recent, newest) = NativeCrashReport.crashLogLinesAfter(lines, loggedUntil)
        if (recent.isNotEmpty()) {
            logger.warn { "[NativeCrash] crash log of this app since the last report:\n" + recent.joinToString("\n") }
        }
        return newest
    }
}
