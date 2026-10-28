/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.him188.ani.app.platform.PreviousExits
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.File

/**
 * [SearchConcurrency] 在 Android 上的平台部分:
 * - 开搜 / 搜完在 `noBackupFilesDir` 记一个标记 (进程号与那时的上限). 进程被杀时标记留在盘上 (写进了内核的页缓存),
 *   下次启动看系统的退出记录: 那个进程是在前台因内存不够被杀的, 就报 [SearchConcurrency.onKilledWhileSearching].
 *   退出记录 Android 11 起才有; 更早的系统分不出被杀还是用户强行停止, 不认.
 * - 系统内存告警 ([ComponentCallbacks2.onTrimMemory] 的 RUNNING_LOW / RUNNING_CRITICAL, Android 14 起系统不再发),
 *   与开搜时问一次系统内存低不低 ([ActivityManager.MemoryInfo.lowMemory]).
 */
object AndroidSearchConcurrency {
    private val logger = logger<AndroidSearchConcurrency>()

    private const val MARKER_FILE = "search_in_flight"

    /** 主进程启动时调一次. */
    fun install(context: Context) {
        val app = context.applicationContext
        val marker = File(app.noBackupFilesDir, MARKER_FILE)
        val am = app.getSystemService(ActivityManager::class.java)
        val concurrency = SearchConcurrency.Default
        val previous = runCatching { Marker.parse(marker.readText()) }.getOrNull()
        if (previous != null && killedInForeground(am, app.packageName, previous.pid)) {
            logger.warn { "The previous run (pid ${previous.pid}) was killed for memory while searching, limit was ${previous.limit}" }
            concurrency.onKilledWhileSearching(previous.limit)
        }
        concurrency.install(
            object : SearchConcurrency.Platform {
                override fun lowMemory(): Boolean = am != null &&
                        runCatching { ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.lowMemory }.getOrDefault(false)

                override fun markSearching(limit: Int?) {
                    runCatching { marker.writeText(limit?.let { Marker(Process.myPid(), it).serialize() }.orEmpty()) }
                }
            },
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        app.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                @Suppress("DEPRECATION") // 安卓 14 起不再发 RUNNING_LOW 等, 之前的版本照常收
                override fun onTrimMemory(level: Int) {
                    when (level) {
                        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> concurrency.onMemoryPressure(critical = true)
                        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> concurrency.onMemoryPressure(critical = false)
                    }
                }

                override fun onConfigurationChanged(newConfig: Configuration) {}

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {
                }
            },
        )
    }

    /** 那个进程是在前台 (或用户看得到的) 时因内存不够被系统杀掉的; 后台被回收不算. */
    private fun killedInForeground(am: ActivityManager?, packageName: String, pid: Int): Boolean {
        if (am == null || Build.VERSION.SDK_INT < 30) return false
        val exit = runCatching { am.getHistoricalProcessExitReasons(packageName, pid, 1).firstOrNull() }.getOrNull() ?: return false
        return PreviousExits.isLikelyLowMemoryKill(exit.reason, exit.status) &&
                exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
    }

    private data class Marker(val pid: Int, val limit: Int) {
        fun serialize(): String = "$pid $limit"

        companion object {
            fun parse(text: String): Marker? {
                val parts = text.trim().split(' ')
                if (parts.size != 2) return null
                return Marker(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null)
            }
        }
    }
}
