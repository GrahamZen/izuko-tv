/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tools.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * 更新下载 / 上传 / 写入安装会话那一行进度字 ([formatTransferProgress]) 与速度 ([TransferRateMeter]).
 */
class TransferProgressFormatTest {
    private val kib = 1024L
    private val mib = 1024L * kib
    private val gib = 1024L * mib

    @Test
    fun `two numbers share the unit picked by the total`() {
        assertEquals("38/79 MB", formatTransferProgress(38 * mib + 500 * kib, 79 * mib + 900 * kib))
        assertEquals("300/800 KB", formatTransferProgress(300 * kib, 800 * kib))
        assertEquals("1.5/2 GB", formatTransferProgress(gib + gib / 2, 2 * gib))
    }

    @Test
    fun `below ten keeps one decimal and never rounds up`() {
        assertEquals("5.3/79 MB", formatTransferProgress(5 * mib + 390 * kib, 79 * mib))
        assertEquals("5/79 MB", formatTransferProgress(5 * mib, 79 * mib))
        assertEquals("0/79 MB", formatTransferProgress(0, 79 * mib))
        // 差一个字节也不显示成下完了
        assertEquals("78/79 MB", formatTransferProgress(79 * mib - 1, 79 * mib))
    }

    @Test
    fun `unknown total shows only what is done`() {
        assertEquals("12 MB", formatTransferProgress(12 * mib, null))
        assertEquals("512 KB", formatTransferProgress(512 * kib, null))
    }

    @Test
    fun `speed is appended with its own unit`() {
        assertEquals("38/79 MB · 2.1 MB/s", formatTransferProgress(38 * mib, 79 * mib, 2 * mib + 160 * kib))
        assertEquals("38/79 MB · 12 MB/s", formatTransferProgress(38 * mib, 79 * mib, 12 * mib + 700 * kib))
        assertEquals("38/79 MB · 512 KB/s", formatTransferProgress(38 * mib, 79 * mib, 512 * kib))
        assertEquals("38/79 MB · 0 KB/s", formatTransferProgress(38 * mib, 79 * mib, 0))
    }

    @Test
    fun `rate is the average over the last window`() {
        val time = TestTimeSource()
        val meter = TransferRateMeter(window = 5.seconds, timeSource = time)
        assertNull(meter.sample(0), "一个样本算不出速度")
        time += 1.seconds
        assertEquals(1000L, meter.sample(1000))
        time += 1.seconds
        assertEquals(1000L, meter.sample(2000))
        // 之后一直没有新数据: 平均速度一路往下, 满一个窗口后归零
        repeat(4) {
            time += 1.seconds
            meter.sample(2000)
        }
        time += 1.seconds
        assertEquals(0L, meter.sample(2000))
    }
}
