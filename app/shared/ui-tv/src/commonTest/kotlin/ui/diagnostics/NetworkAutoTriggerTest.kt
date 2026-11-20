/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkAutoTriggerTest {
    private val start = 1_000_000L
    private val afterGrace = start + NetworkAutoTrigger.STARTUP_GRACE_MILLIS

    /** 从 [from] 起每隔 [gap] 毫秒报一次, 返回最后一次的判断. */
    private fun NetworkAutoTrigger.feed(times: Int, from: Long, gap: Long = 1_000): Boolean {
        var due = false
        repeat(times) { due = due(from + it * gap) }
        return due
    }

    @Test
    fun `fires after enough failures within the window`() {
        val trigger = NetworkAutoTrigger(start)
        assertFalse(trigger.feed(NetworkAutoTrigger.THRESHOLD - 1, afterGrace))
        assertTrue(trigger.due(afterGrace + 10_000))
    }

    @Test
    fun `failures right after startup do not count`() {
        val trigger = NetworkAutoTrigger(start)
        assertFalse(trigger.feed(20, start))
    }

    @Test
    fun `failures spread wider than the window do not add up`() {
        val trigger = NetworkAutoTrigger(start)
        assertFalse(trigger.feed(10, afterGrace, gap = NetworkAutoTrigger.WINDOW_MILLIS / 2))
    }

    @Test
    fun `after firing it waits for the minimum interval`() {
        val trigger = NetworkAutoTrigger(start)
        val at = afterGrace + 5_000
        assertTrue(trigger.feed(NetworkAutoTrigger.THRESHOLD, at))
        trigger.fired(at)
        assertFalse(trigger.feed(NetworkAutoTrigger.THRESHOLD, at + 60_000))
        assertTrue(trigger.feed(NetworkAutoTrigger.THRESHOLD, at + NetworkAutoTrigger.MIN_INTERVAL_MILLIS))
    }

    @Test
    fun `a due trigger that was not run stays due`() {
        val trigger = NetworkAutoTrigger(start)
        assertTrue(trigger.feed(NetworkAutoTrigger.THRESHOLD, afterGrace))
        // 当时不在前台, 没有调 fired: 下一个失败照样该体检
        assertTrue(trigger.due(afterGrace + 30_000))
    }
}
