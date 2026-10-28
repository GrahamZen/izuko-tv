/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.mediasource.instance.createTestMediaSourceInstance
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.paging.emptySizedSource
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceTier
import me.him188.ani.datasources.api.source.TestHttpMediaSource
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 查询会话按选源时各源能到的最好层级排开查先后 ([SourceStartOrder.startTierOf], [DevSwitches.searchStartByBestTier]). */
class MediaFetcherStartTierTest {
    private val request = MediaFetchRequest(
        subjectId = "1",
        episodeId = "1",
        subjectNames = listOf("葬送的芙莉莲"),
        episodeSort = EpisodeSort("01"),
        episodeName = "冒险的结束",
    )

    private val started = mutableListOf<String>()

    private fun source(name: String, tier: Int) = TestHttpMediaSource(
        mediaSourceId = name,
        kind = MediaSourceKind.WEB,
        fetch = {
            started += name
            emptySizedSource()
        },
        info = MediaSourceInfo(displayName = name, tier = MediaSourceTier(tier.toUInt())),
    )

    /** 自身 T4、线路里有 T1 的源 */
    private val tiers = MediaSelectorSourceTiers(
        tiers = mapOf("t1" to MediaSourceTier(1u), "t2" to MediaSourceTier(2u), "channels" to MediaSourceTier(4u)),
        channelTiers = mapOf("channels" to mapOf("line-a" to MediaSourceTier(1u), "line-b" to MediaSourceTier(6u))),
    )

    @AfterTest
    fun resetSwitch() {
        DevSwitches.searchStartByBestTier.set("1")
    }

    private fun TestScope.startOrder(): List<String> {
        val fetcher = MediaSourceMediaFetcher(
            configProvider = { MediaFetcherConfig.Default },
            mediaSources = listOf(source("t2", 2), source("t1", 1), source("channels", 4)).map { createTestMediaSourceInstance(it) },
            flowContext = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
            sourceTiers = { tiers },
        )
        val session = fetcher.newSession(request)
        backgroundScope.launch { session.cumulativeResults.collect() }
        runCurrent()
        return started.toList()
    }

    @Test
    fun `a source whose channels reach a better tier starts with that tier`() = runTest {
        DevSwitches.searchStartByBestTier.set("1")
        assertEquals(listOf("t1", "channels", "t2"), startOrder())
    }

    @Test
    fun `sources start by their own tier when the switch is off`() = runTest {
        DevSwitches.searchStartByBestTier.set("0")
        assertEquals(listOf("t1", "t2", "channels"), startOrder())
    }
}
