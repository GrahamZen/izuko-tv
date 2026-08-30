/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用 cached 线程池而不是固定大小: 这个调度器同时承担分段下载与文件读写, 固定上限会在
 * "一个任务等另一个任务" 时死锁. 线程空闲后自动回收.
 */
actual fun createCacheDownloadDispatcher(): CoroutineDispatcher {
    val counter = AtomicInteger()
    return Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "CacheDownload-${counter.incrementAndGet()}").apply {
            isDaemon = true
            // Android 上 Thread.priority 会映射成 nice 值; 取比 NORM 低几档而不是最低,
            // 既让路给界面, 又不至于让下载慢到没法用
            priority = Thread.MIN_PRIORITY + 1
        }
    }.asCoroutineDispatcher()
}
