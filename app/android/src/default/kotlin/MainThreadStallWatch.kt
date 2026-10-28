/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主线程超过 4 秒没处理消息时把它的栈、以及卡住的与搜索线程的栈打进日志 (查开播时卡在切主线程这类问题).
 * 开播诊断的一部分, 只在启动时开着调试开关 `playDiagnostics` 才装 (见 AniApplication).
 */
internal object MainThreadStallWatch {
    private const val TAG = "MainThreadStallWatch"

    fun install() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            var reported = false
            while (true) {
                val posted = SystemClock.uptimeMillis()
                val ran = AtomicBoolean(false)
                main.post { ran.set(true) }
                Thread.sleep(4000)
                if (ran.get()) {
                    reported = false
                    continue
                }
                if (reported) continue
                reported = true
                log("main thread stalled > ${SystemClock.uptimeMillis() - posted}ms", Looper.getMainLooper().thread.stackTrace.toList())
                for ((t, frames) in Thread.getAllStackTraces()) {
                    if (t.state == Thread.State.BLOCKED || t.name.startsWith("ani-search") || t.name.startsWith("DefaultDispatcher")) {
                        log("thread ${t.name} (${t.state})", frames.take(25))
                    }
                }
            }
        }, "main-stall-watch").apply { isDaemon = true }.start()
    }

    /** logcat 一条最多四千字左右, 栈按帧分几条打 */
    private fun log(title: String, frames: List<StackTraceElement>) {
        Log.w(TAG, title)
        frames.chunked(30).forEach { chunk -> Log.w(TAG, chunk.joinToString("\n") { "    at $it" }) }
    }
}
