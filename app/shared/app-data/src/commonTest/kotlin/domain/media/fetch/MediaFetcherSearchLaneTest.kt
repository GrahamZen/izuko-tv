/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.mediasource.instance.createTestMediaSourceInstance
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.paging.emptySizedSource
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.TestHttpMediaSource
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 各源的查询换到自己的通道上 ([SearchDispatchers.forSource]) 时, 排在后面、一直轮不到的源也不挡住整个会话. */
class MediaFetcherSearchLaneTest {
    /** 只收任务不执行的通道, 测试结束时 [release] 把攒下的跑完: CPU 被占满时排在后面的源一直轮不到. */
    private class HeldLane : CoroutineDispatcher(), SearchDispatchers.Lane {
        val held = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            held.addLast(block)
        }

        fun release() {
            while (held.isNotEmpty()) held.removeFirst().run()
        }
    }

    private val request = MediaFetchRequest(
        subjectId = "1",
        episodeId = "1",
        subjectNames = listOf("葬送的芙莉莲"),
        episodeSort = EpisodeSort("01"),
        episodeName = "冒险的结束",
    )

    private fun source(name: String) = TestHttpMediaSource(
        mediaSourceId = name,
        kind = MediaSourceKind.WEB,
        fetch = { emptySizedSource() },
        info = MediaSourceInfo(displayName = name),
    )

    @AfterTest
    fun restore() {
        DevSwitches.searchPrioritized.set("0")
        DevSwitches.searchRememberedFirst.set("1")
        SearchDispatchers.install(Dispatchers.Default)
    }

    @Test
    fun `the remembered source of the subject gets the first lane`() = runTest {
        val lanes = HashMap<Int, HeldLane>()
        SearchDispatchers.install(Dispatchers.Default) { rank -> lanes.getOrPut(rank) { HeldLane() } }
        DevSwitches.searchPrioritized.set("1")
        val fetched = mutableListOf<String>()
        fun tracked(name: String) = TestHttpMediaSource(
            mediaSourceId = name,
            kind = MediaSourceKind.WEB,
            fetch = {
                fetched += name
                emptySizedSource()
            },
            info = MediaSourceInfo(displayName = name),
        )
        val fetcher = MediaSourceMediaFetcher(
            configProvider = { MediaFetcherConfig.Default },
            mediaSources = listOf(tracked("a"), tracked("b"), tracked("remembered")).map { createTestMediaSourceInstance(it) },
            flowContext = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
            rememberedSourceOf = { subjectId -> "remembered".takeIf { subjectId == request.subjectId } },
            lookupContext = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
        )
        val session = fetcher.newSession(request)
        backgroundScope.launch { session.cumulativeResults.collect() }
        runCurrent()

        // 只放行第一条通道: 先开查的是记住的源
        repeat(10) {
            lanes.getValue(0).release()
            runCurrent()
        }
        assertEquals(listOf("remembered"), fetched)
        lanes.values.forEach { it.release() }
    }

    @Test
    fun `the remembered source is read when the session is created and not when the first source starts`() = runTest {
        val lanes = HashMap<Int, HeldLane>()
        SearchDispatchers.install(Dispatchers.Default) { rank -> lanes.getOrPut(rank) { HeldLane() } }
        DevSwitches.searchPrioritized.set("1")
        val fetched = mutableListOf<String>()
        fun tracked(name: String) = TestHttpMediaSource(
            mediaSourceId = name,
            kind = MediaSourceKind.WEB,
            fetch = {
                fetched += name
                emptySizedSource()
            },
            info = MediaSourceInfo(displayName = name),
        )
        val fetcher = MediaSourceMediaFetcher(
            configProvider = { MediaFetcherConfig.Default },
            mediaSources = listOf(tracked("a"), tracked("remembered")).map { createTestMediaSourceInstance(it) },
            flowContext = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
            // 弱机冷启动时读一次库要一两秒, 比各源开查时肯等的久
            rememberedSourceOf = {
                delay(1_000)
                "remembered"
            },
            lookupContext = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
        )
        val session = fetcher.newSession(request)
        // 建好会话到开查之间还有选源器、播放器的活
        advanceTimeBy(1_500)
        backgroundScope.launch { session.cumulativeResults.collect() }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        repeat(10) {
            lanes.getValue(0).release()
            runCurrent()
        }
        assertEquals(listOf("remembered"), fetched)
        lanes.values.forEach { it.release() }
    }

    @Test
    fun `the remembered source keeps its usual lane when the switch is off`() = runTest {
        val lanes = HashMap<Int, HeldLane>()
        SearchDispatchers.install(Dispatchers.Default) { rank -> lanes.getOrPut(rank) { HeldLane() } }
        DevSwitches.searchPrioritized.set("1")
        DevSwitches.searchRememberedFirst.set("0")
        val fetched = mutableListOf<String>()
        fun tracked(name: String) = TestHttpMediaSource(
            mediaSourceId = name,
            kind = MediaSourceKind.WEB,
            fetch = {
                fetched += name
                emptySizedSource()
            },
            info = MediaSourceInfo(displayName = name),
        )
        val fetcher = MediaSourceMediaFetcher(
            configProvider = { MediaFetcherConfig.Default },
            mediaSources = listOf(tracked("a"), tracked("remembered")).map { createTestMediaSourceInstance(it) },
            flowContext = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
            rememberedSourceOf = { "remembered" },
            lookupContext = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
        )
        backgroundScope.launch { fetcher.newSession(request).cumulativeResults.collect() }
        runCurrent()

        repeat(10) {
            lanes.getValue(0).release()
            runCurrent()
        }
        assertEquals(listOf("a"), fetched)
        lanes.values.forEach { it.release() }
    }

    @Test
    fun `sources waiting for their lane still hand in empty results so the session can be judged`() = runTest {
        val lane = HeldLane()
        SearchDispatchers.install(Dispatchers.Default) { lane }
        DevSwitches.searchPrioritized.set("1")
        val fetcher = MediaSourceMediaFetcher(
            configProvider = { MediaFetcherConfig.Default },
            mediaSources = listOf(source("a"), source("b")).map { createTestMediaSourceInstance(it) },
            flowContext = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
        )
        val session = fetcher.newSession(request)

        backgroundScope.launch { session.cumulativeResults.collect() }
        val first = backgroundScope.async { session.cumulativeResults.first() }
        runCurrent()
        // 搜索本身还在通道里排着, 起步的空结果与「在查」的状态已经交了
        assertTrue(lane.held.isNotEmpty())
        assertTrue(first.isCompleted)
        assertEquals(emptyList(), first.await())
        assertTrue(session.mediaSourceResults.all { it.state.value == MediaSourceFetchState.Working })

        repeat(10) {
            lane.release()
            runCurrent()
        }
        assertTrue(session.mediaSourceResults.all { it.state.value is MediaSourceFetchState.Succeed })
    }
}
