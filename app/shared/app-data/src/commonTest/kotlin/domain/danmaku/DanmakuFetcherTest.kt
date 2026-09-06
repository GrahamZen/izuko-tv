/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.SimpleDanmakuProvider
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class DanmakuFetcherTest {
    @Test
    fun `failed fetch is marked as failed - not as no match`() = runTest {
        var calls = 0
        val fetcher = DanmakuFetcher(
            FakeProvider {
                calls++
                throw IllegalStateException("offline")
            },
        )

        val info = fetcher.fetch(createRequest()).single().matchInfo

        assertTrue(info.fetchFailed)
        assertEquals(DanmakuMatchMethod.NoMatch, info.method)
        assertEquals(0, info.count)
        assertEquals(2, calls) // 失败后重试一次
    }

    @Test
    fun `timed out fetch is marked as failed`() = runTest {
        val fetcher = DanmakuFetcher(FakeProvider { awaitCancellation() })

        val info = fetcher.fetch(createRequest()).single().matchInfo

        assertTrue(info.fetchFailed)
    }

    @Test
    fun `real no match is not marked as failed`() = runTest {
        val fetcher = DanmakuFetcher(
            FakeProvider {
                listOf(DanmakuFetchResult.noMatch(DanmakuProviderId.Dandanplay, DanmakuServiceId.Dandanplay))
            },
        )

        val info = fetcher.fetch(createRequest()).single().matchInfo

        assertFalse(info.fetchFailed)
        assertEquals(DanmakuMatchMethod.NoMatch, info.method)
    }

    private class FakeProvider(
        private val onFetch: suspend () -> List<DanmakuFetchResult>,
    ) : SimpleDanmakuProvider {
        override val providerId: DanmakuProviderId = DanmakuProviderId.Dandanplay
        override val mainServiceId: DanmakuServiceId = DanmakuServiceId.Dandanplay
        override suspend fun fetchAutomatic(request: DanmakuFetchRequest): List<DanmakuFetchResult> = onFetch()
    }

    private fun createRequest() = DanmakuFetchRequest(
        subjectId = 101,
        subjectPrimaryName = "Subject",
        subjectNames = listOf("Subject"),
        subjectPublishDate = PackedDate.Invalid,
        episodeId = 202,
        episodeSort = EpisodeSort(1),
        episodeEp = EpisodeSort(1),
        episodeName = "Episode 1",
        filename = null,
        fileHash = null,
        fileSize = null,
        videoDuration = Duration.ZERO,
    )
}
