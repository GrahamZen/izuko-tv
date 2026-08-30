/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 令牌桶限速器: 一次下载内所有并发分段**共用一个**, 限的是这次下载的总吞吐.
 *
 * 通过 [DownloadOptions.maxBytesPerSecond] 选用, 默认不限. 它曾是"缓存时遥控器失灵"的第一版修法
 * (压到 4MB/s 确实不冻了), 但那是间接起效: 真正的机制是大块顺序写把 ext4 data=ordered 的脏页
 * 积起来, 让别的进程的 fsync 等好几秒 —— 现在由 [RawSegmentDownloader] 的周期 fsync 直接解决,
 * 下载可以全速跑. 限速器留作可选项: 想给缓存让出带宽 (例如边看边缓存) 时用.
 */
class ByteRateLimiter(
    private val bytesPerSecond: Long,
    private val currentTimeMillis: () -> Long,
) {
    init {
        require(bytesPerSecond > 0) { "bytesPerSecond must be positive, but was $bytesPerSecond" }
    }

    /**
     * 桶容量取半秒的量 (且不小于一次读取的块大小, 否则大块永远攒不够令牌会死等).
     * 允许短促突发以免把小文件拖成一段一段的, 又不至于攒出一大口重新把 GC 顶起来.
     */
    private val capacity: Double = maxOf(bytesPerSecond / 2, 64L * 1024).toDouble()

    private val mutex = Mutex()
    private var tokens: Double = capacity
    private var lastRefillMillis: Long = currentTimeMillis()

    /**
     * 取走 [bytes] 个令牌, 不够就挂起等到够为止. 已经读到手的字节不会被丢弃 ——
     * 限的是"读下一块之前等多久", 所以不会破坏数据完整性.
     */
    suspend fun acquire(bytes: Int) {
        if (bytes <= 0) return
        val want = bytes.toDouble().coerceAtMost(capacity)
        while (true) {
            val waitMillis = mutex.withLock {
                val now = currentTimeMillis()
                val elapsed = (now - lastRefillMillis).coerceAtLeast(0)
                tokens = (tokens + elapsed * bytesPerSecond / 1000.0).coerceAtMost(capacity)
                lastRefillMillis = now
                if (tokens >= want) {
                    tokens -= want
                    0L
                } else {
                    // 还差多少字节, 就按额定速率折算成毫秒 (至少 1ms, 免得空转)
                    (((want - tokens) * 1000.0) / bytesPerSecond).toLong().coerceAtLeast(1L)
                }
            }
            if (waitMillis == 0L) return
            delay(waitMillis)
        }
    }
}
