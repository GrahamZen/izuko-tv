/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.notice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.data.models.notice.NoticeLog
import me.him188.ani.app.data.repository.user.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class NoticesTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private var clock = 100.days.inWholeMilliseconds
    private val settings = MemorySettings(NoticeLog.Empty)
    private val notices = Notices(settings, now = { clock })

    private fun notice(key: String, at: Long, vararg args: Pair<String, String>) = Notice("test", key, at, args.toMap())

    private suspend fun keys() = notices.all.first().map { it.notice.key }

    @Test
    fun `posting the same content again changes nothing`() = runTest {
        notices.post(notice("a", clock))
        notices.markSeen(listOf(notice("a", clock)))
        clock += 1000
        notices.post(notice("a", clock))
        val entry = notices.all.first().single()
        assertEquals(clock - 1000, entry.notice.atMillis)
        assertTrue(entry.seen)
    }

    @Test
    fun `posting changed content replaces it and counts as new`() = runTest {
        notices.post(notice("a", clock, "n" to "1"))
        notices.markSeen(listOf(notice("a", clock, "n" to "1")))
        clock += 1000
        notices.post(notice("a", clock, "n" to "2"))
        val entry = notices.all.first().single()
        assertEquals("2", entry.notice.args["n"])
        assertEquals(false, entry.seen)
    }

    @Test
    fun `new first then pending then newer`() = runTest {
        notices.post(notice("old-message", clock - 3000))
        notices.post(notice("new-message", clock - 1000))
        notices.post(notice("seen-message", clock - 500))
        notices.markSeen(listOf(notice("seen-message", clock - 500)))
        notices.addSource(MutableStateFlow(listOf(notice("pending", clock - 2000))))
        assertEquals(listOf("pending", "new-message", "old-message", "seen-message"), keys())
    }

    @Test
    fun `a pending notice whose time moves later is new again`() = runTest {
        val source = MutableStateFlow(listOf(notice("p", clock)))
        notices.addSource(source)
        notices.markSeen(listOf(notice("p", clock)))
        assertTrue(notices.all.first().single().seen)
        source.value = listOf(notice("p", clock + 5000))
        assertEquals(false, notices.all.first().single().seen)
    }

    @Test
    fun `a pending notice disappears with its state`() = runTest {
        val source = MutableStateFlow(listOf(notice("p", clock)))
        notices.addSource(source)
        assertEquals(listOf("p"), keys())
        source.value = emptyList()
        assertEquals(emptyList(), keys())
    }

    @Test
    fun `messages expire after the ttl`() = runTest {
        notices.post(notice("a", clock))
        clock += Notices.MESSAGE_TTL.inWholeMilliseconds + 1
        assertEquals(emptyList(), keys())
    }

    @Test
    fun `retract removes a message`() = runTest {
        notices.post(notice("a", clock))
        notices.retract("a")
        assertEquals(emptyList(), keys())
    }

    @Test
    fun `at most the newest messages are kept`() = runTest {
        repeat(Notices.MAX_MESSAGES + 5) { notices.post(notice("m$it", clock - 1000 + it)) }
        val kept = settings.state.value.messages
        assertEquals(Notices.MAX_MESSAGES, kept.size)
        assertEquals("m${Notices.MAX_MESSAGES + 4}", kept.first().key)
    }

    @Test
    fun `a pending notice without a time uses when it first appeared`() = runTest {
        val source = MutableStateFlow(listOf(notice("p", Notices.UNKNOWN_TIME)))
        val tracked = Notices(settings, backgroundScope, now = { clock })
        tracked.addSource(source)
        val first = clock
        runCurrent()
        clock += 5000
        assertEquals(first, tracked.all.first().single().notice.atMillis)
        // 消失后再出现: 新的一次, 时间是再出现的时刻
        source.value = emptyList()
        runCurrent()
        source.value = listOf(notice("p", Notices.UNKNOWN_TIME))
        runCurrent()
        assertEquals(clock, tracked.all.first().single().notice.atMillis)
    }

    @Test
    fun `a failing source counts as empty`() = runTest {
        notices.addSource(flow { throw IllegalStateException("boom") })
        notices.post(notice("a", clock))
        assertEquals(listOf("a"), keys())
    }
}
