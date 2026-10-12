/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每次交给系统安装都记一笔 (方式、时间、版本、包大小、剩余空间), 结果回来了也记上. 装上了助手就被 Izuko TV 换掉,
 * 所以助手还能再打开、又有一笔记录, 就是上次没装上. 这时把那一次的情况写在界面与诊断信息里, 连同系统记的助手进程是怎么结束的
 * (Android 11 起): 结束原因写着 installPackageLI = 系统已经走到替换那一步; 是崩溃 = 问题在助手; 没有记录 = 安装界面在更早就没了.
 *
 * 记录用 commit 同步写: 交给系统之后助手进程随时会被结束.
 */
object InstallAttempts {
    const val METHOD_SYSTEM = "system"
    const val METHOD_SESSION = "session"

    /**
     * @property result 系统给的结果 (会话的状态码与原文、安装界面关掉时的返回码); 没收到为 `null`
     * @property exit 那次之后助手进程是怎么结束的 (系统的记录); Android 11 以前或没有记录时为 `null`
     */
    class Attempt(
        val time: Long,
        val method: String,
        val version: String,
        val apkBytes: Long,
        val freeBytes: Long,
        val result: String?,
        val exit: String?,
    )

    private const val PREFS = "install_attempts"

    @Volatile
    private var loaded = false

    @Volatile
    private var previous: Attempt? = null

    /** 这个进程起来之前的最后一次 (这个进程里新记的不算); 没有, 或那次其实装上了 (调试包与正式版包名不同), 为 `null`. */
    fun previous(context: Context): Attempt? {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    previous = load(context)
                    loaded = true
                }
            }
        }
        return previous
    }

    fun record(context: Context, method: String, version: String, apk: File) {
        previous(context)
        val free = runCatching { StatFs(apk.parent ?: context.cacheDir.path).availableBytes }.getOrDefault(-1L)
        prefs(context).edit().clear()
            .putLong("time", System.currentTimeMillis())
            .putString("method", method)
            .putString("version", version)
            .putLong("apk", apk.length())
            .putLong("free", free)
            .commit()
    }

    /** 最近一笔记录的时间 (这个进程里新记的也算); 没有为 0. */
    fun lastRecordTime(context: Context): Long = prefs(context).getLong("time", 0)

    fun recordResult(context: Context, result: String) {
        val prefs = prefs(context)
        if (!prefs.contains("time")) return
        prefs.edit().putString("result", result).commit()
    }

    /** 一行说清那一次: 方式、时间、版本、包大小与当时的剩余空间、系统的结果、助手进程怎么结束的. */
    fun describe(context: Context, attempt: Attempt): String {
        val method = context.getString(if (attempt.method == METHOD_SESSION) R.string.attempt_method_session else R.string.attempt_method_system)
        val parts = mutableListOf(method, SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(attempt.time)), "Izuko ${attempt.version}")
        if (attempt.apkBytes >= 0 && attempt.freeBytes >= 0) {
            parts += context.getString(R.string.attempt_sizes, megabytes(attempt.apkBytes), megabytes(attempt.freeBytes))
        }
        parts += context.getString(R.string.attempt_result, attempt.result ?: context.getString(R.string.attempt_no_result))
        attempt.exit?.let { parts += context.getString(R.string.attempt_exit, it) }
        return parts.joinToString(" · ")
    }

    private fun megabytes(bytes: Long) = "${bytes / 1024 / 1024} MB"

    private fun load(context: Context): Attempt? {
        val prefs = prefs(context)
        val time = prefs.getLong("time", 0)
        if (time == 0L) return null
        // 调试包与正式版包名不同, 装上之后助手还在: 装上了就不算失败
        if (context.packageName != BuildConfig.TARGET_PACKAGE && isInstalled(context, BuildConfig.TARGET_PACKAGE)) return null
        return Attempt(
            time = time,
            method = prefs.getString("method", null).orEmpty(),
            version = prefs.getString("version", null).orEmpty(),
            apkBytes = prefs.getLong("apk", -1),
            freeBytes = prefs.getLong("free", -1),
            result = prefs.getString("result", null),
            exit = lastExit(context, time),
        )
    }

    private fun isInstalled(context: Context, packageName: String) =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /**
     * 那次之后助手进程第一次结束的记录: 「原因 (描述)」. 助手在那之后被重装过 (重新下载安装助手、调试时 adb 装) 就不给:
     * 重装也会以 installPackageLI 结束进程, 分不清是哪一次.
     */
    private fun lastExit(context: Context, since: Long): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            if (context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime > since) return null
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .filter { it.timestamp >= since }
                .minByOrNull { it.timestamp } ?: return null
            val reason = exitReasonName(info.reason)
            info.description?.takeIf { it.isNotBlank() }?.let { "$reason ($it)" } ?: reason
        }.getOrNull()
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init-failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason $reason"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
