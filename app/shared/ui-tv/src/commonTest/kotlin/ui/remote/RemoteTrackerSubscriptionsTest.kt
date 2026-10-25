/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import me.him188.ani.app.data.repository.torrent.TrackerSubscriptionEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteTrackerSubscriptionsTest {
    private val time: (Long) -> String = { "T$it" }

    @Test
    fun `no address shows no status`() {
        assertNull(RemoteTrackerSubscriptions.statusText(emptyList(), time))
    }

    @Test
    fun `each address says how its last fetch went`() {
        val text = RemoteTrackerSubscriptions.statusText(
            listOf(
                TrackerSubscriptionEntry("https://a", listOf("udp://1", "udp://2"), fetchedAtMillis = 10),
                TrackerSubscriptionEntry("https://b", listOf("udp://2", "udp://3"), fetchedAtMillis = 20, error = "timeout"),
                TrackerSubscriptionEntry("https://c", error = TrackerSubscriptionEntry.EMPTY_LIST),
                TrackerSubscriptionEntry("https://d"),
            ),
            time,
        )

        assertEquals(
            listOf(
                "订阅里共有 3 个 tracker，与内置、额外的一起用",
                "https://a：2 个，T10 拉取",
                "https://b：这次没拉到（timeout），继续用 T20 拉到的 2 个",
                "https://c：没拉到（里面没有 tracker）",
                "https://d：还没拉过",
            ),
            text?.lines(),
        )
    }
}
