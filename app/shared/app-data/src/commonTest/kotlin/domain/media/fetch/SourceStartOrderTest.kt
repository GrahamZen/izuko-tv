/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.mediasource.profile.SearchObservation
import me.him188.ani.app.domain.mediasource.profile.SourceObservation
import me.him188.ani.app.domain.mediasource.profile.SourceProfileSummary
import me.him188.ani.app.domain.mediasource.profile.SourceProfiles
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceTier
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceStartOrderTest {
    private class FakeSource(id: String, tier: Int?, override val kind: MediaSourceKind = MediaSourceKind.WEB) : MediaSourceFetchResult {
        override val instanceId = id
        override val mediaSourceId = id
        override val sourceInfo = MediaSourceInfo(id, tier = tier?.let { MediaSourceTier(it.toUInt()) })
        override val state = MutableStateFlow<MediaSourceFetchState>(MediaSourceFetchState.Idle)
        override val results: Flow<List<Media>> = flowOf(emptyList())
        override fun restart() = Unit
        override fun pause() = Unit
        override fun enable() = Unit
    }

    private fun played(ok: Int, failed: Int = 0, startupMillis: Long = 1_000, searchMillis: Long = 500): SourceProfileSummary =
        SourceProfiles.summarize(
            List(ok) { SourceObservation(at = it.toLong(), ok = true, startupMillis = startupMillis) } +
                    List(failed) { SourceObservation(at = 100L + it, ok = false) },
            List(3) { SearchObservation(at = it.toLong(), millis = searchMillis, outcome = SearchObservation.Outcome.OK) },
        )!!

    @Test
    fun `local cache first then by search priority then sources that played fine`() {
        val sources = listOf(
            FakeSource("new-t0", 0),
            FakeSource("bt-t0", 0, MediaSourceKind.BitTorrent),
            FakeSource("played-t1", 1),
            FakeSource("played-t0", 0),
            FakeSource("cache", null, MediaSourceKind.LocalCache),
            FakeSource("failing-t0", 0),
        )
        val profiles = mapOf(
            "played-t1" to played(3),
            "played-t0" to played(3),
            "failing-t0" to played(ok = 1, failed = 3),
        )
        val order = SourceStartOrder.of(sources) { profiles[it] }
        assertEquals(
            listOf("cache", "played-t0", "new-t0", "failing-t0", "bt-t0", "played-t1"),
            order.map { sources[it].mediaSourceId },
        )
    }

    @Test
    fun `the remembered source starts right after the local caches`() {
        val sources = listOf(
            FakeSource("t0", 0),
            FakeSource("remembered-t3", 3),
            FakeSource("cache", null, MediaSourceKind.LocalCache),
            FakeSource("t1", 1),
        )
        assertEquals(
            listOf("cache", "remembered-t3", "t0", "t1"),
            SourceStartOrder.of(sources, first = "remembered-t3", summaryOf = { null }).map { sources[it].mediaSourceId },
        )
        // 记住的源不在这一轮里: 照常排
        assertEquals(
            listOf("cache", "t0", "t1", "remembered-t3"),
            SourceStartOrder.of(sources, first = "gone", summaryOf = { null }).map { sources[it].mediaSourceId },
        )
    }

    @Test
    fun `faster sources start first among sources that played fine`() {
        val sources = listOf(FakeSource("slow", 0), FakeSource("fast", 0), FakeSource("unknown", 0))
        val profiles = mapOf(
            "slow" to played(3, startupMillis = 6_000, searchMillis = 2_000),
            "fast" to played(3, startupMillis = 800, searchMillis = 400),
        )
        assertEquals(listOf("fast", "slow", "unknown"), SourceStartOrder.of(sources) { profiles[it] }.map { sources[it].mediaSourceId })
    }

    @Test
    fun `tiers reached through channels count - sources missing from the tiers use their own`() {
        val sources = listOf(FakeSource("t2", 2), FakeSource("t1", 1), FakeSource("channels", 4), FakeSource("unlisted", 0))
        val tiers = MediaSelectorSourceTiers(
            tiers = mapOf("t1" to MediaSourceTier(1u), "t2" to MediaSourceTier(2u), "channels" to MediaSourceTier(4u)),
            channelTiers = mapOf("channels" to mapOf("line-a" to MediaSourceTier(1u), "line-b" to MediaSourceTier(6u))),
        )
        assertEquals(
            listOf("unlisted", "t1", "channels", "t2"),
            SourceStartOrder.of(sources, tiers) { null }.map { sources[it].mediaSourceId },
        )
        assertEquals(
            listOf("unlisted", "t1", "t2", "channels"),
            SourceStartOrder.of(sources) { null }.map { sources[it].mediaSourceId },
        )
    }

    @Test
    fun `combine in order subscribes in the given order and keeps the list order`() = runTest {
        val subscribed = mutableListOf<String>()
        fun source(name: String) = flow {
            subscribed += name
            emit(name)
        }
        val combined = SourceStartOrder.combineInOrder(listOf(source("a"), source("b"), source("c")), listOf(2, 0, 1)) { it }
        assertEquals(listOf("a", "b", "c"), combined.first())
        assertEquals(listOf("c", "a", "b"), subscribed)
    }
}
