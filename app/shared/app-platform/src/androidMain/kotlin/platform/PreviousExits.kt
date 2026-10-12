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
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 系统记下的本应用进程退出记录 (Android 11 起才有).
 *
 * 被系统杀掉 (内存不够、强行停止) 时进程来不及写任何东西, 导出的日志只是突然断掉; 启动时把最近几条记进日志 ([log]),
 * 才分得出上次是崩溃、无响应还是内存不够被杀.
 */
object PreviousExits {
    private val logger = logger<PreviousExits>()

    /** SIGKILL */
    private const val SIGNAL_KILL = 9

    /** 启动时记进日志的条数 */
    private const val LOGGED_EXITS = 5

    /**
     * 多半是整机内存不够被杀: 记成 [ApplicationExitInfo.REASON_LOW_MEMORY], 或者「被信号 9 终止」——
     * Android 11 的 lmkd 杀进程记成后者 (Shield 上前台播 4K 被杀就是这样), 不是 REASON_LOW_MEMORY.
     */
    fun isLikelyLowMemoryKill(reason: Int, status: Int): Boolean =
        reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            (reason == ApplicationExitInfo.REASON_SIGNALED && status == SIGNAL_KILL)

    /** 把最近几条退出记录写进日志; 只在主进程启动时调一次. */
    fun log(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching { log30(context) }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason=$reason"
    }

    @RequiresApi(30)
    private fun log30(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
        for (e in am.getHistoricalProcessExitReasons(context.packageName, 0, LOGGED_EXITS)) {
            logger.info {
                buildString {
                    append("Previous exit ${format.format(Date(e.timestamp))} ${e.processName}: ${reasonName(e.reason)}, status=${e.status}")
                    if (isLikelyLowMemoryKill(e.reason, e.status)) append(" (likely low memory)")
                    append(", importance=${e.importance}, pss=${e.pss / 1024}MB, rss=${e.rss / 1024}MB")
                    e.description?.let { append(", description=$it") }
                }
            }
        }
    }
}
