/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.testFramework

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import me.him188.ani.app.domain.media.fetch.BatchedSizedSource
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.MediaFetcherConfig
import me.him188.ani.app.domain.media.fetch.MediaSourceMediaFetcher
import me.him188.ani.app.domain.media.fetch.batchedSizedSource
import me.him188.ani.app.domain.media.selector.testFramework.MediaSelectorTestSuite.Companion.SOURCE_DMHY
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.mediasource.web.BlockReason
import me.him188.ani.app.domain.mediasource.web.BlockedException
import me.him188.ani.app.domain.mediasource.web.PageExpectation
import me.him188.ani.app.domain.mediasource.web.SolveRequest
import me.him188.ani.app.domain.mediasource.web.WebCaptchaKind
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.paging.emptySizedSource
import me.him188.ani.datasources.api.source.*
import me.him188.ani.utils.platform.Uuid
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 一个测试的 [MediaFetchSession]
 *
 * @see MediaFetchSession
 */
class TestMediaFetchSessionBuilder {
    private val instances = mutableListOf<Handle>()
    private val mediaFetchRequestBuilder: MediaFetchRequestBuilder = MediaFetchRequestBuilder()

    fun request(block: MediaFetchRequestBuilder.() -> Unit) {
        mediaFetchRequestBuilder.apply(block)
    }


    fun web(
        enabled: Boolean = true,
        configure: Handle.() -> Unit = {},
    ) = source(MediaSourceKind.WEB, enabled, configure)

    fun bt(
        enabled: Boolean = true,
        configure: Handle.() -> Unit = {},
    ) = source(MediaSourceKind.BitTorrent, enabled, configure)

    fun localCache(
        enabled: Boolean = true,
        configure: Handle.() -> Unit = {},
    ) = source(MediaSourceKind.LocalCache, enabled, configure)

    fun source(
        kind: MediaSourceKind,
        enabled: Boolean = true,
        configure: Handle.() -> Unit = {},
    ) = PropertyDelegateProvider<Any?, _> { thisRef, prop ->
        lateinit var handle: Handle
        val propertyName = prop.name
        val source = TestMediaSource(
            displayName = propertyName,
            mediaSourceId = propertyName,
            kind = kind,
            fetch = {
                handle.onFetch()
                handle.matches()
            },
        )
        handle = Handle(
            MediaSourceInstance(
                source.mediaSourceId,
                FactoryId(source.mediaSourceId),
                enabled,
                config = MediaSourceConfig.Default,
                source,
            ),
        )
        instances.add(handle)
        configure(handle)

        ReadOnlyProperty<Any?, Handle> { _, _ ->
            handle
        }
    }

    fun <R> build(
        userContext: R,
        dispatcher: CoroutineContext,
    ): TestMediaFetchSession<R> {
        return TestMediaFetchSession(
            MediaSourceMediaFetcher(
                configProvider = { MediaFetcherConfig.Default },
                mediaSources = instances.map { it.instance },
                flowContext = dispatcher,
            ).newSession(mediaFetchRequestBuilder.build()),
            userContext,
            allHandles = this.instances.toList(),
        )
    }
}


class Handle(
    val instance: MediaSourceInstance,
) {
    val result = CompletableDeferred<List<MediaMatch>>()

    /**
     * [TestMediaSource.fetch] 被调用的次数, 即该源的查询被驱动的次数.
     */
    var fetchCount: Int = 0
        private set

    internal fun onFetch() {
        fetchCount++
    }

    /** [emitEarly] 交出的, 每次调用一批. */
    private val early = Channel<List<MediaMatch>>(Channel.UNLIMITED)

    /**
     * 为 true 时逐条交出 (同不分批的数据源), 每条之间隔 1 毫秒: 别的线程上的数据源一页还没交完, 下游已经看到前面几条.
     * 默认按批 ([BatchedSizedSource]): 每次 [emitEarly] 一批, [complete] 一批.
     */
    var deliverItemByItem = false

    /** 查询交出的资源: 先是 [emitEarly] 交的 (源仍在查), [complete] 时再交完. */
    internal fun matches(): SizedSource<MediaMatch> {
        val done = MutableStateFlow(false)
        val batches = flow {
            while (true) {
                val batch = select<List<MediaMatch>?> {
                    early.onReceive { it }
                    result.onAwait { null }
                } ?: break
                emit(batch)
            }
            result.await().takeIf { it.isNotEmpty() }?.let { emit(it) }
        }.onCompletion { done.value = true }
        if (!deliverItemByItem) return batchedSizedSource(batches, done, flowOf(null))
        return object : SizedSource<MediaMatch> {
            override val results = batches.transform { batch ->
                for (match in batch) {
                    emit(match)
                    delay(1)
                }
            }
            override val finished = done
            override val totalSize = flowOf(null)
        }
    }

    /** 先交出这些资源, 源仍在查 (同网页源开完一页先交一页). [complete] 时只需交剩下的. */
    fun emitEarly(vararg medias: Media): Handle {
        check(result.isActive)
        early.trySend(medias.map { MediaMatch(it, MatchKind.EXACT).ownedByThisSource() })
        return this
    }

    fun complete(vararg medias: Media) = complete(medias.toList())

    fun complete(vararg medias: MediaMatch) = complete(medias.toList())

    @JvmName("completeListMedia")
    fun complete(medias: List<Media>) = complete(medias.map { MediaMatch(it, MatchKind.EXACT) })

    @JvmName("completeListMediaMatch")
    fun complete(medias: List<MediaMatch>): Handle {
        check(result.isActive)
        result.complete(medias.map { it.ownedByThisSource() })
        return this
    }

    private fun MediaMatch.ownedByThisSource(): MediaMatch {
        check(media.mediaSourceId == SOURCE_DMHY)
        return copy((media as DefaultMedia).copy(mediaSourceId = instance.mediaSourceId))
    }

    /** Ends the query as rate limited. The fetcher retries it once after [retryAfter]. */
    fun rateLimit(retryAfter: Duration = 30.seconds) = block(BlockReason.RateLimited(retryAfter))

    /** Ends the query as blocked by a captcha, which only the user can solve. */
    fun requireCaptcha() = block(BlockReason.Captcha(WebCaptchaKind.Cloudflare))

    private fun block(reason: BlockReason): Handle {
        check(result.isActive)
        val request = SolveRequest(
            mediaSourceId = instance.mediaSourceId,
            pageUrl = "https://example.com/search",
            kind = WebCaptchaKind.Cloudflare,
            expectation = PageExpectation.AnyContent,
        )
        result.completeExceptionally(BlockedException(reason, request))
        return this
    }
}


class TestMediaSource(
    val displayName: String,
    override val mediaSourceId: String = Uuid.randomString(),
    override val kind: MediaSourceKind = MediaSourceKind.BitTorrent,
    private val randomConnectivity: Boolean = false,
    private val fetch: suspend (MediaFetchRequest) -> SizedSource<MediaMatch> = { emptySizedSource() },
) : HttpMediaSource() {
    override val info: MediaSourceInfo = MediaSourceInfo(
        displayName = displayName,
    )

    override suspend fun checkConnection(): ConnectionStatus {
        if (randomConnectivity) {
            return Random.nextBoolean().let {
                if (it) ConnectionStatus.SUCCESS else ConnectionStatus.FAILED
            }
        }
        return ConnectionStatus.SUCCESS
    }

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> = this.fetch.invoke(query)
}

class TestMediaFetchSession<T>(
    val session: MediaFetchSession,
    val userContext: T,
    val allHandles: List<Handle>,
) {
    val allSourceIds get() = allHandles.map { it.instance.mediaSourceId }

    operator fun component1() = this
    operator fun component2() = session
    operator fun component3() = userContext
}


context(testScope: TestScope)
fun <R> TestMediaFetchSessionBuilder.build(userContext: R): TestMediaFetchSession<R> =
    build(userContext, testScope.coroutineContext[ContinuationInterceptor]!!)

context(testScope: TestScope)
inline fun <R> buildTestMediaFetchSession(block: TestMediaFetchSessionBuilder.() -> R): TestMediaFetchSession<R> {
    return buildTestMediaFetchSession(dispatcher = testScope.coroutineContext[ContinuationInterceptor]!!, block)
}

inline fun <R> buildTestMediaFetchSession(
    dispatcher: CoroutineContext,
    block: TestMediaFetchSessionBuilder.() -> R,
): TestMediaFetchSession<R> {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val builder = TestMediaFetchSessionBuilder()
    val r = builder.run(block)
    return builder.build(r, dispatcher)
}
