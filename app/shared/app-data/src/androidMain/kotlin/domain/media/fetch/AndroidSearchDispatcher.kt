/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import android.os.Process
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SearchDispatchers] 的 Android 实现: 与 CPU 核数一样多的 `THREAD_PRIORITY_BACKGROUND` 线程, 任务按通道的位次取 (见 [PrioritySearchPool]).
 */
object AndroidSearchDispatcher {
    fun install() {
        val count = AtomicInteger()
        val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val pool = PrioritySearchPool(threads) { task ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    task.run()
                },
                "ani-search-${count.incrementAndGet()}",
            ).apply { isDaemon = true }
        }
        SearchDispatchers.install(pool.shared, pool)
    }
}
