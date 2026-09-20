/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.settings

import io.ktor.client.request.get
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.foundation.ServerListFeatureConfig
import me.him188.ani.app.domain.settings.ServiceConnectionTester.Service
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.bangumi.BangumiClient
import me.him188.ani.utils.coroutines.SingleTaskExecutor
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * Orchestrates the concurrent testing of multiple [Service] instances.
 *
 * Each [Service] is tested asynchronously when [testAll] is called. The state of each service
 * transitions through [TestState] according to the outcome of its [Service.test] function.
 *
 * - If [testAll] is called again while a previous test run is still in progress,
 *   the existing tasks are canceled and set to [TestState.Idle], and new tasks begin.
 * - If the caller's coroutine (that invokes [testAll]) is canceled, all testing coroutines
 *   are also canceled, and their states revert to [TestState.Idle].
 * - [stopAll] can be invoked manually to cancel any ongoing tests and reset all states to [TestState.Idle].
 *
 * This class is **thread-safe** and can be called from multiple coroutines/threads concurrently.
 *
 * @param defaultDispatcher coroutine dispatcher to run the tests ([Service.test]) and results aggregation.
 *
 * @see ServiceConnectionTesters.createDefault
 */
class ServiceConnectionTester(
    services: List<Service>,
    private val defaultDispatcher: CoroutineContext = Dispatchers.Default,
) {
    private val services = services.map { ServiceImpl(it) }

    /**
     * A [Flow] of [Results], which contains the current [TestState] of all [Service]s being tested.
     */
    val results: Flow<Results> =
        combine(this.services.map { service -> service.state.map { service.service to it } }) { states ->
            Results(states.toMap(LinkedHashMap())) // retain order
        }.shareIn(
            CoroutineScope(defaultDispatcher), // note: we can't use backgroundScope here because backgroundScope may have a Job, which is not accepted by shareIn.
            started = SharingStarted.WhileSubscribed(), replay = 0,
        )

    private val singleTaskExecutor = SingleTaskExecutor(defaultDispatcher)

    /**
     * Start testing all services and suspend until all services are tested.
     *
     * Lifecycle of the testing task is bounded by this function.
     * That is, is this function is cancelled, all testing coroutines are also cancelled.
     * Calling this function the second time will cancel the previous call.
     */
    suspend fun testAll() {
        singleTaskExecutor.invoke {
            for (service in services) {
                launch {
                    service.test()
                }
            }
        }
    }

    /**
     * Stop all testing.
     *
     * This cancels all testing coroutines and results running services' states to [TestState.Idle],
     * but does not clear the completed states.
     */
    fun stopAll() {
        singleTaskExecutor.cancelCurrent()
    }

    class Service(
        /**
         * 给调用方识别的 ID. [ServiceConnectionTester] 不会使用此 ID.
         */
        val id: String,
        /**
         * Test if this service is available.
         *
         * This function is not allowed to throw exceptions, otherwise it will become [TestState.Error] and is considered a bug.
         */
        val test: suspend () -> Boolean,
    )

    sealed class TestState {
        // also initial state
        data object Idle : TestState()

        data object Testing : TestState()
        data class Success(
            val time: Duration,
        ) : TestState()

        /**
         * Indicates a normal failure, e.g., HTTP status code is not 200.
         */
        data object Failed : TestState()

        /**
         * Indicates an unexpected error, e.g., an exception is thrown.
         * This should be considered a bug.
         */
        data class Error(
            val e: Throwable,
        ) : TestState()
    }

    class Results internal constructor(
        internal val states: Map<Service, TestState>,
    ) {
        val idToStateMap: Map<String, TestState> by lazy { states.mapKeys { it.key.id } }

        fun findStateById(id: String): TestState? = states.keys.find { it.id == id }?.let { states[it] }

        fun anyFailed() = states.values.any { it is TestState.Failed }
        fun allCompleted() = states.values.all {
            when (it) {
                is TestState.Error -> true
                TestState.Failed -> true
                TestState.Idle -> false
                is TestState.Success -> true
                TestState.Testing -> false
            }
        }
    }

    private class ServiceImpl(
        val service: Service,
    ) {
        private val _state: MutableStateFlow<TestState> = MutableStateFlow(TestState.Idle)
        val state: StateFlow<TestState> = _state.asStateFlow()
        private val lock = Mutex()

        /**
         * Test the service.
         *
         * This function must be called by only one coroutine at a time, otherwise it throws.
         *
         * If the coroutine is cancelled, the state is re-set to [TestState.Idle] and the [CancellationException] is propagated.
         */
        suspend fun test() {
            // Note that we set `owner=this` (which is always the same), 
            // so that the lock basically ensures the function is always called by a single coroutine at a time.
            // This is a strong assertion to ensure the `testAll` algorithm works correctly.
            lock.withLock(owner = this) {
                _state.value = TestState.Testing
                try {
                    val (res, t) = measureTimedValue { service.test() }
                    _state.value = if (res) TestState.Success(t) else TestState.Failed
                } catch (e: CancellationException) {
                    _state.value = TestState.Idle
                    throw e
                } catch (e: Throwable) {
                    _state.value = TestState.Error(e)
                }
            }
        }

        fun resetToIdle() {
            _state.value = TestState.Idle
        }
    }
}


/**
 * 给单项探测封顶, 超时即判失败.
 *
 * 探测请求多数没覆盖超时, 吃的是全局 30 秒连接超时, 而 IPv4 + IPv6 各试一次就是 60 秒 ——
 * `api.bgm.tv` 在墙内被 DNS 投毒时, 设置页那一行要整整转一分钟才变红叉 (issue #7 报告者
 * 实测), 这期间用户只看到一个转圈, 分不清是在等还是已经卡死.
 *
 * **必须用 [withTimeoutOrNull] 而不是 `withTimeout`**: 后者抛的是 `CancellationException`
 * 的子类, 会被 [ServiceConnectionTester.Service] 的取消分支当成"用户主动取消"而置回
 * `TestState.Idle` —— 那样这一行会永远停在转圈上, 比现在还糟.
 */
private suspend fun withTestTimeout(
    id: String,
    timeoutMillis: Long = ServiceConnectionTesters.DEFAULT_TEST_TIMEOUT_MILLIS,
    block: suspend () -> Boolean,
): Boolean = withTimeoutOrNull(timeoutMillis) { block() } ?: run {
    // 超时这条路径**必须自己留一行日志**: 到点了 withTimeoutOrNull 取消里面那个协程, 探测函数
    // 自己的 catch 会把 CancellationException 重抛而不打日志 —— 于是"探测报红但 app.log 里
    // 关于它一个字都没有"就成了唯一症状, 事后完全无法区分"真的连不上"和"只是没跑完"
    // (2026-08-17 用户报告图床自动探测失败、手动重试就好, 正是这种情形).
    logger.warn { "Service '$id' test timed out after ${timeoutMillis}ms, reporting as failed" }
    false
}

private val logger = logger("ServiceConnectionTesters")

object ServiceConnectionTesters {
    const val ID_BANGUMI = "BANGUMI"
    const val ID_BANGUMI_NEXT = "BANGUMI_NEXT"
    const val ID_ANI = "ANI"
    const val ID_TMDB = "TMDB"

    /**
     * 图片 CDN 单独一项: 与接口是两个域名, 在墙内各自独立被墙且方向常常相反
     * (接口不通、图床却通). 合成一项的话用户分不清该不该挂代理, 见 [TmdbImageService].
     */
    const val ID_TMDB_IMAGE = "TMDB_IMAGE"

    /**
     * 单项探测的时间上限, 见 [withTestTimeout].
     *
     * **原来是 5 秒, 2026-08-17 放到 10 秒**: 真机日志显示冷启动后第一轮探测里, bgm 两项与
     * Animeko 那项会各自恰好被 5000ms 整掐掉 (`CANCELLED in 4.99s`, 从没拿到响应), 而几秒后
     * 第二三轮同样的域名 200、只要 200~500ms. 原因是**探测用的客户端是新建的**:
     * [ProxyTester] 为 bgm 与 Ani 各建一个 HttpClient (只有 TMDB 那两项复用应用单例), 新客户端
     * 有自己的连接池, 首轮要自己走一遍 DNS + TCP + TLS; 而那一刻应用启动的请求风暴 (实测 17 个
     * 并发 subject 请求, 耗时从 350ms 爬到 1.46s) 还在占着带宽.
     *
     * 于是 5 秒的代价不是"慢", 是**把好的网络报成红叉** —— 而这一行是用户判断"要不要挂代理"的
     * 唯一依据, 误报比多等几秒有害得多. 10 秒仍然远小于加封顶之前的病态情形 (全局 30 秒连接超时
     * × IPv4/IPv6 各一次 = 一分钟).
     */
    internal const val DEFAULT_TEST_TIMEOUT_MILLIS = 10_000L

    /** TMDB 那项内部串了两三个请求, 单独给更宽的上限, 见调用处. */
    internal const val TMDB_TEST_TIMEOUT_MILLIS = 15_000L


    val DefaultServiceIds = setOf(ID_BANGUMI, ID_BANGUMI_NEXT, ID_ANI, ID_TMDB, ID_TMDB_IMAGE)

    fun createDefault(
        bangumiClient: BangumiClient,
        aniClient: ScopedHttpClient,
        tmdbImageService: TmdbImageService,
        serviceIds: Set<String> = DefaultServiceIds,
        defaultDispatcher: CoroutineContext = Dispatchers.Default,
    ): ServiceConnectionTester {
        return ServiceConnectionTester(
            listOf(
                Service(ID_BANGUMI) {
                    withTestTimeout(ID_BANGUMI) {
                        bangumiClient.testConnectionMaster() == ConnectionStatus.SUCCESS
                    }
                },
                Service(ID_BANGUMI_NEXT) {
                    withTestTimeout(ID_BANGUMI_NEXT) {
                        bangumiClient.testConnectionNext() == ConnectionStatus.SUCCESS
                    }
                },
                Service(ID_ANI) {
                    withTestTimeout(ID_ANI) {
                        runCatching {
                            // Note, we may have `expectSuccess = true` so on failure it will throw an exception.
                            aniClient.use {
                                // 与 ServerSelector 一致, 用轻量的 /status 探活, 避免请求业务接口浪费服务器资源
                                get(ServerListFeatureConfig.MAGIC_ANI_SERVER) {
                                    url { appendPathSegments("status") }
                                }.status.isSuccess()
                            }
                        }.getOrElse { false }
                    }
                },
                // 详情页背景图与选集卡片剧照的来源. 接口与图片 CDN 是两个域名, 分成两项各自
                // 出结果 —— 墙内两者常常一通一不通, 合成一项会让用户无从判断, 见 TmdbImageService
                Service(ID_TMDB) {
                    // 可能要依次试主备两个域名, 给的上限比别人宽, 否则通的网络也会被判超时
                    withTestTimeout(ID_TMDB, TMDB_TEST_TIMEOUT_MILLIS) {
                        tmdbImageService.testApiConnection()
                    }
                },
                Service(ID_TMDB_IMAGE) {
                    // 只有一个域名一次 HEAD, 用默认上限就够 —— 而且它是五项里最不容易超时的那个:
                    // 走的是应用的单例客户端 (见 tmdbImageService 那行的注释), 连接在启动那波
                    // 请求里早就热了, 实测每轮都是 170~530ms
                    withTestTimeout(ID_TMDB_IMAGE) {
                        tmdbImageService.testImageConnection()
                    }
                },
            ).filter { it.id in serviceIds },
            defaultDispatcher,
        )

    }
}
