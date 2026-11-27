/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(UnsafeEpisodeSessionApi::class)

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.domain.episode.CreateMediaFetchSelectBundleFlowUseCase
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.app.domain.episode.MediaFetchSelectBundle
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.mediaFetchSessionFlow
import me.him188.ani.app.domain.episode.mediaSelectorFlow
import me.him188.ani.app.domain.media.cache.CachePlayRequests
import me.him188.ani.app.domain.media.continuation.EpisodeCacheRecords
import me.him188.ani.app.domain.media.continuation.EpisodeCacheSelection
import me.him188.ani.app.domain.media.continuation.EpisodeContinuation
import me.him188.ani.app.domain.media.continuation.EpisodeContinuations
import me.him188.ani.app.domain.media.continuation.LineageResolution
import me.him188.ani.app.domain.media.continuation.PlaybackLineage
import me.him188.ani.app.domain.media.continuation.PlaybackLineageSelection
import me.him188.ani.app.domain.media.continuation.WebSourceLineage
import me.him188.ani.app.domain.media.continuation.createTestLineageRepository
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.resolver.TestUniversalMediaResolver
import me.him188.ani.app.domain.media.selector.MediaSelectorAutoSelectUseCase
import me.him188.ani.app.domain.media.selector.MediaSelectorAutoSelectUseCaseImpl
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.media.selector.ReplayBrowseMemoryUseCase
import me.him188.ani.app.domain.media.selector.ReplayResult
import me.him188.ani.app.domain.mediasource.GetMediaSelectorSourceTiersUseCase
import me.him188.ani.app.domain.mediasource.GetPreferredWebMediaSourceUseCase
import me.him188.ani.app.domain.settings.GetMediaSelectorSettingsFlowUseCase
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.coroutines.childScope
import org.openani.mediamp.source.UriMediaData
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * @see SourceSelectionExtension
 */
class SourceSelectionExtensionTest : AbstractPlayerExtensionTest() {
    private val defaultSettings = MediaSelectorSettings.AllVisible.copy(
        preferKind = null,
        hideSingleEpisodeForCompleted = false,
        preferSeasons = true,
        autoEnableLastSelected = false,
        fastSelectWebKind = false,
    )

    private val mediaSelectorSettings = MutableStateFlow(
        defaultSettings,
    )
    val preferredWebMediaSource = MutableStateFlow<String?>(null)

    /**
     * 自动选择被启动的次数. 回放命中后必须为 0: 真实 use case 在已有选择时本来就立即返回, 只看 `selected` 分辨不出扩展有没有跳过它.
     */
    private var autoSelectCalls = 0

    /** 设置「没下完的 BT 缓存先边下边播」 */
    private var playUnfinishedFirst = true

    /** 这一集下完的缓存 (选源器里的 mediaId) */
    private var finishedCaches = emptySet<String>()

    /** 这部番的播放线索; 线索上的是同一个数据源的网上资源 ([sameSourceContinuation]) */
    private var lineage: PlaybackLineage? = null

    /** 按线索直接取 (快取) 时取到的资源; 没有时不快取 */
    private var resolvedOnLineage: Media? = null

    private fun sameSourceContinuation() = object : EpisodeContinuation {
        override fun owns(media: Media): Boolean = media.kind == MediaSourceKind.WEB
        override suspend fun lineageOf(media: Media): PlaybackLineage? = null
        override fun continues(lineage: PlaybackLineage, candidate: Media): Boolean = candidate.mediaSourceId == lineage.mediaSourceId
        override suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest): LineageResolution =
            resolvedOnLineage?.let { LineageResolution.Found(it) } ?: LineageResolution.NotFound

        override fun isQuickToResolve(lineage: PlaybackLineage): Boolean = resolvedOnLineage != null
    }

    data class Context(
        val scope: CoroutineScope,
        val suite: EpisodePlayerTestSuite,
        val state: EpisodeFetchSelectPlayState,
    )

    private fun TestScope.createCase(
        config: (scope: CoroutineScope, suite: EpisodePlayerTestSuite) -> Unit = { _, _ -> },
    ): Context {
        contract {
            callsInPlace(config, InvocationKind.EXACTLY_ONCE)
        }

        val testScope = this.childScope()
        val suite = EpisodePlayerTestSuite(this, testScope)
        suite.registerComponent<GetMediaSelectorSettingsFlowUseCase> {
            GetMediaSelectorSettingsFlowUseCase { mediaSelectorSettings }
        }
        autoSelectCalls = 0
        suite.registerComponent<MediaSelectorAutoSelectUseCase> {
            val impl = MediaSelectorAutoSelectUseCaseImpl(koin)
            object : MediaSelectorAutoSelectUseCase {
                override suspend fun invoke(session: MediaFetchSession, mediaSelector: MediaSelector, selectCache: Boolean) {
                    autoSelectCalls++
                    impl(session, mediaSelector, selectCache)
                }
            }
        }
        suite.registerComponent<MediaResolver> {
            TestUniversalMediaResolver
        }
        suite.registerComponent<GetMediaSelectorSourceTiersUseCase> {
            GetMediaSelectorSourceTiersUseCase {
                flowOf(MediaSelectorSourceTiers.Empty)
            }
        }
        suite.registerComponent<GetPreferredWebMediaSourceUseCase> {
            GetPreferredWebMediaSourceUseCase { preferredWebMediaSource }
        }
        suite.registerComponent<ReplayBrowseMemoryUseCase> {
            ReplayBrowseMemoryUseCase { _, _, _ -> ReplayResult.NOT_FOUND }
        }
        // 播放线索看 lineage (默认没有); 缓存记录覆盖本集, 有没有下完看 finishedCaches
        val lineages = createTestLineageRepository()
        val continuation = EpisodeContinuations(listOf(sameSourceContinuation()))
        lineage = null
        resolvedOnLineage = null
        suite.registerComponent<PlaybackLineageSelection> { PlaybackLineageSelection(lineages, continuation) }
        playUnfinishedFirst = true
        finishedCaches = emptySet()
        suite.registerComponent<EpisodeCacheSelection> {
            EpisodeCacheSelection(lineages, continuation, EpisodeCacheRecords { _, _ -> true }, { _, _ -> finishedCaches }, { playUnfinishedFirst })
        }
        // 与生产的 CreateMediaFetchSelectBundleFlowUseCaseImpl 同形: bundle 由 infoBundleFlow 派生,
        // 因此扩展拿到 bundle 时 infoBundleFlow.replayCache 已有本集信息, 回放才会被调用.
        suite.registerComponent<CreateMediaFetchSelectBundleFlowUseCase> {
            CreateMediaFetchSelectBundleFlowUseCase { infoBundleFlow ->
                val builder = suite.mediaSelectorTestBuilder
                val fetchSession = builder.createMediaFetchSession(builder.createMediaFetcher())
                val bundle = MediaFetchSelectBundle(fetchSession, builder.createMediaSelector(fetchSession))
                infoBundleFlow.map { it?.let { bundle } }.distinctUntilChanged()
            }
        }

        // set null by default
        preferredWebMediaSource.value = null
        config(testScope, suite)
        lineage?.let { launch { lineages.setLineage(subjectId, it) } }


        val state = suite.createState(
            listOf(
                SourceSelectionExtension,
            ),
        )
        state.onUIReady()
        advanceUntilIdle()
        return Context(testScope, suite, state)
    }

    @Test
    fun `auto select default`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val context = createCase { _, suite ->
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
        }
        val (testScope, suite, state) = context

        initializeTest(suite)
        startMediaFetcher(state, testScope)

        val myMedia = suite.mediaSelectorTestBuilder.createMedia("web1")
        web1.complete(listOf(myMedia))
        advanceUntilIdle() // Performs auto select

        // Check result.
        state.assertSelected(myMedia, suite)

        testScope.cancel()
    }

    @Test
    fun `auto select cached - control group`() = runTest {
        val cached: CompletableDeferred<List<Media>>
        val web1: CompletableDeferred<List<Media>>
        val context = createCase { _, suite ->
            cached = suite.mediaSelectorTestBuilder.delayedMediaSource("cached")
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
        }
        val (testScope, suite, state) = context

        initializeTest(suite, preference = MediaPreference.Any.copy(alliance = "alliance2"))
        startMediaFetcher(state, testScope)

        val cachedMedia = suite.mediaSelectorTestBuilder.createMedia(
            "cached",
            kind = MediaSourceKind.WEB,
            alliance = "alliance1",
        )
        val myMedia = suite.mediaSelectorTestBuilder.createMedia("web1", alliance = "alliance2")
        cached.complete(listOf(cachedMedia))
        web1.complete(listOf(myMedia))
        advanceUntilIdle() // Performs auto select

        // Check result.
        state.assertSelected(myMedia, suite) // "cached" is WEB

        testScope.cancel()
    }

    @Test
    fun `auto select cached - test group`() = runTest {
        val cached: CompletableDeferred<List<Media>>
        val web1: CompletableDeferred<List<Media>>
        val context = createCase { _, suite ->
            cached = suite.mediaSelectorTestBuilder.delayedMediaSource("cached")
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
        }
        val (testScope, suite, state) = context

        initializeTest(suite, preference = MediaPreference.Any.copy(alliance = "alliance2"))
        startMediaFetcher(state, testScope)

        val cachedMedia = suite.mediaSelectorTestBuilder.createMedia(
            "cached",
            kind = MediaSourceKind.LocalCache,
            alliance = "alliance1",
        )
        val myMedia = suite.mediaSelectorTestBuilder.createMedia("web1", alliance = "alliance2")
        cached.complete(listOf(cachedMedia))
        web1.complete(listOf(myMedia))
        advanceUntilIdle() // Performs auto select

        // Check result.
        state.assertSelected(cachedMedia, suite) // "cached" is LocalCache, must be selected

        testScope.cancel()
    }


    @Test
    fun `fast select web - control group`() = runTest {
        val bt1: CompletableDeferred<List<Media>>
        val web1: CompletableDeferred<List<Media>>
        val context = createCase { _, suite ->
            bt1 = suite.mediaSelectorTestBuilder.delayedMediaSource("bt1")
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
        }
        val (testScope, suite, state) = context

        initializeTest(
            suite,
            mediaSelectorSettings = defaultSettings.copy(
                fastSelectWebKind = true,
                preferKind = MediaSourceKind.BitTorrent,
            ),
        ) // NOTE: settings disabled
        startMediaFetcher(state, testScope)

        val myMedia = suite.mediaSelectorTestBuilder.createMedia("web1")
        web1.complete(listOf(myMedia))
        // bt1 does not complete
        advanceUntilIdle() // Performs auto select

        // Check result.
        state.assertSelected(null, suite)

        testScope.cancel()
    }

    @Test
    fun `fast select web - test group`() = runTest {
        val bt1: CompletableDeferred<List<Media>>
        val web1: CompletableDeferred<List<Media>>
        val context = createCase { _, suite ->
            bt1 = suite.mediaSelectorTestBuilder.delayedMediaSource("bt1", kind = MediaSourceKind.BitTorrent)
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1", kind = MediaSourceKind.WEB)
        }
        val (testScope, suite, state) = context

        initializeTest(
            suite,
            mediaSelectorSettings = defaultSettings.copy(fastSelectWebKind = true, preferKind = MediaSourceKind.WEB),
        ) // NOTE: settings ENABLED
        startMediaFetcher(state, testScope)

        val myMedia = suite.mediaSelectorTestBuilder.createMedia("web1", kind = MediaSourceKind.WEB)
        web1.complete(listOf(myMedia))
        // bt1 does not complete
        advanceUntilIdle() // Performs auto select

        // Check result.
        state.assertSelected(myMedia, suite)

        testScope.cancel()
    }

    @Test
    fun `select preferred web source - control group`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val web2: CompletableDeferred<List<Media>>
        val context = createCase { _, suite ->
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1", kind = MediaSourceKind.WEB)
            web2 = suite.mediaSelectorTestBuilder.delayedMediaSource("web2", kind = MediaSourceKind.WEB)
        }
        val (testScope, suite, state) = context

        // NOTE: No preferred source is set (GetPreferredWebMediaSourceUseCase returns null by default)
        initializeTest(suite)
        startMediaFetcher(state, testScope)

        val media1 = suite.mediaSelectorTestBuilder.createMedia("web1", kind = MediaSourceKind.WEB)
        val media2 = suite.mediaSelectorTestBuilder.createMedia("web2", kind = MediaSourceKind.WEB)
        web1.complete(listOf(media1))
        web2.complete(listOf(media2))
        advanceUntilIdle() // Performs auto select

        // Check result: should fall back to default selection (first available)
        state.assertSelected(media1, suite)

        testScope.cancel()
    }

    @Test
    fun `select preferred web source - test group`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val web2: CompletableDeferred<List<Media>>
        val context = createCase { _, suite ->
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1", kind = MediaSourceKind.WEB)
            web2 = suite.mediaSelectorTestBuilder.delayedMediaSource("web2", kind = MediaSourceKind.WEB)
            preferredWebMediaSource.value = "web2" // Set preferred source
        }
        val (testScope, suite, state) = context

        initializeTest(suite)
        startMediaFetcher(state, testScope)

        val media1 = suite.mediaSelectorTestBuilder.createMedia("web1", kind = MediaSourceKind.WEB)
        val media2 = suite.mediaSelectorTestBuilder.createMedia("web2", kind = MediaSourceKind.WEB)
        web1.complete(listOf(media1))
        web2.complete(listOf(media2))
        advanceUntilIdle() // Performs auto select

        // Check result: should select from the preferred source "web2"
        state.assertSelected(media2, suite)

        testScope.cancel()
    }

    @Test
    fun `replay hit skips auto select`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val memoryMedia: Media
        val context = createCase { _, suite ->
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
            suite.mediaSelectorTestBuilder.savedUserPreference.value = MediaPreference.Any
            memoryMedia = suite.mediaSelectorTestBuilder.createMedia("web-memory")
            suite.registerComponent<ReplayBrowseMemoryUseCase> {
                ReplayBrowseMemoryUseCase { _, _, mediaSelector ->
                    mediaSelector.select(memoryMedia)
                    ReplayResult.SELECTED
                }
            }
        }
        val (testScope, suite, state) = context

        // 回放在会话启动时已经完成选择, 不等查询结果
        state.assertSelected(memoryMedia, suite)

        startMediaFetcher(state, testScope)
        web1.complete(listOf(suite.mediaSelectorTestBuilder.createMedia("web1")))
        advanceUntilIdle() // 自动选择没有启动, 查询结果不会覆盖回放的选择

        state.assertSelected(memoryMedia, suite)
        assertEquals(0, autoSelectCalls, "回放命中时不启动自动选择")

        testScope.cancel()
    }

    @Test
    fun `replay miss falls back to auto select`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val replayCalls = mutableListOf<Pair<Int, EpisodeInfo>>()
        var selectedAtReplay: Media? = null
        val context = createCase { _, suite ->
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
            suite.registerComponent<ReplayBrowseMemoryUseCase> {
                ReplayBrowseMemoryUseCase { subjectId, episodeInfo, mediaSelector ->
                    replayCalls += subjectId to episodeInfo
                    selectedAtReplay = mediaSelector.selected.value
                    ReplayResult.NOT_FOUND
                }
            }
        }
        val (testScope, suite, state) = context

        initializeTest(suite)
        startMediaFetcher(state, testScope)

        val myMedia = suite.mediaSelectorTestBuilder.createMedia("web1")
        web1.complete(listOf(myMedia))
        advanceUntilIdle() // Performs auto select

        state.assertSelected(myMedia, suite)
        // 回放先于自动选择, 只调用一次, 调用时还没有选择
        val (replayedSubjectId, replayedEpisodeInfo) = replayCalls.single()
        assertEquals(subjectId, replayedSubjectId)
        assertEquals(initialEpisodeId, replayedEpisodeInfo.episodeId)
        assertNull(selectedAtReplay)
        assertEquals(1, autoSelectCalls, "回放未命中才启动自动选择")

        testScope.cancel()
    }

    @Test
    fun `local cache for this episode skips replay`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val cachedMedia: CachedMedia
        var replayCalls = 0
        val context = createCase { _, suite ->
            val cache = suite.mediaSelectorTestBuilder.delayedMediaSource("cache", kind = MediaSourceKind.LocalCache)
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
            suite.mediaSelectorTestBuilder.savedUserPreference.value = MediaPreference.Any
            suite.registerComponent<ReplayBrowseMemoryUseCase> {
                ReplayBrowseMemoryUseCase { _, _, _ ->
                    replayCalls++
                    ReplayResult.NOT_FOUND
                }
            }
            // 本地源是毫秒级完成的, 会话启动前就已有结果; 缓存记录覆盖本集 (夹具的选择器上下文取自查询请求, 即第 1 话).
            val origin = suite.mediaSelectorTestBuilder.createMedia("cache", kind = MediaSourceKind.WEB)
            cachedMedia = CachedMedia(origin, cacheMediaSourceId = "cache", download = origin.download)
            cache.complete(listOf(cachedMedia))
        }
        val (testScope, suite, state) = context

        startMediaFetcher(state, testScope)
        web1.complete(listOf(suite.mediaSelectorTestBuilder.createMedia("web1")))
        advanceUntilIdle() // Performs auto select

        state.assertSelected(cachedMedia, suite) // 本地缓存由自动选择选中
        assertEquals(0, replayCalls, "有本集的本地缓存时不回放")

        testScope.cancel()
    }

    @Test
    fun `an unfinished cache waits for the network when the setting says so`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val context = createCase { _, suite ->
            unfinishedCache(suite)
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
            playUnfinishedFirst = false
        }
        val (testScope, suite, state) = context

        initializeTest(suite)
        startMediaFetcher(state, testScope)
        val myMedia = suite.mediaSelectorTestBuilder.createMedia("web1")
        web1.complete(listOf(myMedia))
        advanceUntilIdle()

        state.assertSelected(myMedia, suite)

        testScope.cancel()
    }

    @Test
    fun `an unfinished cache is played when nothing else turns up`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val cachedMedia: CachedMedia
        val context = createCase { _, suite ->
            cachedMedia = unfinishedCache(suite)
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
            playUnfinishedFirst = false
        }
        val (testScope, suite, state) = context

        initializeTest(suite)
        startMediaFetcher(state, testScope)
        web1.complete(emptyList())
        advanceUntilIdle()

        state.assertSelected(cachedMedia, suite)

        testScope.cancel()
    }

    @Test
    fun `a remembered source is replayed when an unsure cache may not go first`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val memoryMedia: Media
        val context = createCase { _, suite ->
            // 本地缓存源迟迟没查完 (BT 服务还没连上), 不知道有下完的, 设置不让没下完的先播: 缓存本来也不能先选
            suite.mediaSelectorTestBuilder.delayedMediaSource("cache", kind = MediaSourceKind.LocalCache)
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
            playUnfinishedFirst = false
            suite.mediaSelectorTestBuilder.savedUserPreference.value = MediaPreference.Any
            memoryMedia = suite.mediaSelectorTestBuilder.createMedia("web-memory")
            suite.registerComponent<ReplayBrowseMemoryUseCase> {
                ReplayBrowseMemoryUseCase { _, _, mediaSelector ->
                    mediaSelector.select(memoryMedia)
                    ReplayResult.SELECTED
                }
            }
        }
        val (testScope, suite, state) = context

        startMediaFetcher(state, testScope)
        web1.complete(listOf(suite.mediaSelectorTestBuilder.createMedia("web1")))
        advanceUntilIdle()

        state.assertSelected(memoryMedia, suite)
        assertEquals(0, autoSelectCalls, "回放命中时不启动自动选择")

        testScope.cancel()
    }

    @Test
    fun `a finished cache is played whatever the setting`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val cachedMedia: CachedMedia
        val context = createCase { _, suite ->
            cachedMedia = unfinishedCache(suite)
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
            playUnfinishedFirst = false
            suite.mediaSelectorTestBuilder.savedUserPreference.value = MediaPreference.Any
            finishedCaches = setOf(cachedMedia.mediaId)
        }
        val (testScope, suite, state) = context

        // 缓存在会话启动时就选上了, 不等网上的结果
        startMediaFetcher(state, testScope)
        web1.complete(listOf(suite.mediaSelectorTestBuilder.createMedia("web1")))
        advanceUntilIdle()

        state.assertSelected(cachedMedia, suite)

        testScope.cancel()
    }

    @Test
    fun `a finished cache that is slow to show up is not overridden by the lineage`() = runTest {
        val cache: CompletableDeferred<List<Media>>
        val web1: CompletableDeferred<List<Media>>
        val cachedMedia: CachedMedia
        val context = createCase { _, suite ->
            cache = suite.mediaSelectorTestBuilder.delayedMediaSource("cache", kind = MediaSourceKind.LocalCache)
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
            suite.mediaSelectorTestBuilder.savedUserPreference.value = MediaPreference.Any
            val origin = suite.mediaSelectorTestBuilder.createMedia("cache", kind = MediaSourceKind.WEB)
            cachedMedia = CachedMedia(origin, cacheMediaSourceId = "cache", download = origin.download)
            finishedCaches = setOf(cachedMedia.mediaId)
            lineage = WebSourceLineage("web1", "subject", "channel")
            // 本地缓存源迟迟没查完 (冷启动时连 BT 服务慢), 线索上的网上资源直接取得到
            resolvedOnLineage = suite.mediaSelectorTestBuilder.createMedia("web1")
        }
        val (testScope, suite, state) = context

        startMediaFetcher(state, testScope)
        web1.complete(listOf(suite.mediaSelectorTestBuilder.createMedia("web1")))
        cache.complete(listOf(cachedMedia))
        advanceUntilIdle()

        state.assertSelected(cachedMedia, suite)

        testScope.cancel()
    }

    @Test
    fun `from the cache page an unfinished cache is played`() = runTest {
        val web1: CompletableDeferred<List<Media>>
        val cachedMedia: CachedMedia
        val context = createCase { _, suite ->
            cachedMedia = unfinishedCache(suite)
            web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1")
            playUnfinishedFirst = false
            suite.mediaSelectorTestBuilder.savedUserPreference.value = MediaPreference.Any
            CachePlayRequests.mark(subjectId, initialEpisodeId)
        }
        val (testScope, suite, state) = context

        // 缓存在会话启动时就选上了, 不等网上的结果
        startMediaFetcher(state, testScope)
        web1.complete(listOf(suite.mediaSelectorTestBuilder.createMedia("web1")))
        advanceUntilIdle()

        state.assertSelected(cachedMedia, suite)
        // 只管这一次
        assertEquals(false, CachePlayRequests.take(subjectId, initialEpisodeId))

        testScope.cancel()
    }

    private suspend fun EpisodeFetchSelectPlayState.assertSelected(
        expected: Media?,
        suite: EpisodePlayerTestSuite
    ) {
        val mediaSelector = mediaSelectorFlow.first()!!
        assertEquals(expected, mediaSelector.selected.first())
        if (expected == null) {
            assertEquals(null, suite.player.mediaData.first())
        } else {
            assertIs<UriMediaData>(suite.player.mediaData.filterNotNull().first()) // Player is playing
            assertEquals(0, suite.player.currentPositionMillis.value) // State is reset
        }
    }

    /** 本集的一条缓存, 会话启动前就查完 (同真实的本地缓存源); 下没下完看 [finishedCaches]. */
    private fun unfinishedCache(suite: EpisodePlayerTestSuite): CachedMedia {
        val cache = suite.mediaSelectorTestBuilder.delayedMediaSource("cache", kind = MediaSourceKind.LocalCache)
        val origin = suite.mediaSelectorTestBuilder.createMedia("cache", kind = MediaSourceKind.WEB)
        return CachedMedia(origin, cacheMediaSourceId = "cache", download = origin.download).also { cache.complete(listOf(it)) }
    }

    private fun startMediaFetcher(
        state: EpisodeFetchSelectPlayState,
        testScope: CoroutineScope
    ) {
        // MediaFetcher is lazy. We perform fetching in testScope (i.e. foreground). `advanceUntilIdle` will wait for the fetching to complete.
        state.mediaFetchSessionFlow.filterNotNull().flatMapLatest { it.cumulativeResults }.launchIn(testScope)
    }

    private suspend fun TestScope.initializeTest(
        suite: EpisodePlayerTestSuite,
        mediaSelectorSettings: MediaSelectorSettings =
            defaultSettings.copy(preferKind = null),
        preference: MediaPreference = MediaPreference.Any,
    ) {
        this@SourceSelectionExtensionTest.mediaSelectorSettings.value = mediaSelectorSettings
        suite.mediaSelectorTestBuilder.savedUserPreference.value = preference

        // Initialize
        advanceUntilIdle()
        assertEquals(null, suite.player.mediaData.first())
    }
}
