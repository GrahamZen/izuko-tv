/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * 从网上取的小文件 (清单、加密配置、加密通知、规则…) 的两件公共件, 见 docs/contributing/code/remote-files.md:
 * - [RemoteFetch]: 从几个入口取一份 —— 按顺序试, 每个限时, 内容由调用方校验;
 * - [RemoteRefresh]: 什么时候取 —— 定期看过期没有、回到前台、网络体检时.
 * 取到的存在哪、怎么解析由各自的仓库管.
 */

/**
 * 应用在不在前台 (有界面在显示). Android 上 Application 照进程生命周期设 (STARTED 起算前台); 别的平台不设, 一直是 false,
 * 「回到前台时取」不会触发.
 */
object AppForeground {
    val started = MutableStateFlow(false)
}

/**
 * 从几个入口取一份文件: 按顺序试, 每个入口最多等 [SOURCE_TIMEOUT] (可改) 收到回应, 第一份通过校验的就是结果.
 * 校验写在调用方给的 accept 里: 返回结果, 或 [reject] 说明这个入口给的为什么不能用 (试下一个, 理由写进日志).
 */
object RemoteFetch {
    /** 取到的结果与它来自哪个入口. */
    class Fetched<T : Any>(val value: T, val source: String)

    /** 本机那一份来自哪个入口、那个入口给的 ETag. ETag 是各个 CDN 各算各的, 只对同一个入口有用. */
    class Revision(val source: String?, val etag: String?)

    /** [firstChanged] 的结果. */
    sealed interface Conditional<out T : Any> {
        /** 结果来自哪个入口. */
        val source: String

        /** 和本机那一份一样 (那个入口回了 304). */
        class NotModified(override val source: String) : Conditional<Nothing>

        /** 新的一份与入口给的 ETag. */
        class Changed<out T : Any>(val value: T, val etag: String?, override val source: String) : Conditional<T>
    }

    /** 在校验里调: 这个入口给的不能用, 试下一个; [reason] 写进日志. */
    fun reject(reason: String): Nothing = throw Rejected(reason)

    private class Rejected(val reason: String) : Exception(reason)

    /**
     * 按 [sources] 的顺序试; 回应 (不管状态码) 连同它来自哪个入口交给 [accept]: 返回非 null 就是结果, null (或 [reject]) = 这个入口不算数,
     * 试下一个. 都不行返回 null. [what] 只用于日志.
     */
    suspend fun <T : Any> first(
        what: String,
        sources: List<String>,
        client: () -> ScopedHttpClient,
        timeout: Duration = SOURCE_TIMEOUT,
        accept: suspend (source: String, status: HttpStatusCode, body: String) -> T?,
    ): Fetched<T>? {
        val failures = mutableListOf<String>()
        for (source in sources) {
            val reply = request(source, client, timeout, ifNoneMatch = null, failures) ?: continue
            val value = judge(source, failures) {
                accept(source, reply.status, reply.bytes.decodeToString()) ?: reject(reply.status.value.toString())
            } ?: continue
            logger.info { "$what: got it from $source" }
            return Fetched(value, source)
        }
        logger.info { "$what: no usable source (${failures.joinToString()})" }
        return null
    }

    /** 只认 2xx, 正文交给 [parse]. */
    suspend fun <T : Any> firstSuccessful(
        what: String,
        sources: List<String>,
        client: () -> ScopedHttpClient,
        timeout: Duration = SOURCE_TIMEOUT,
        parse: suspend (body: String) -> T?,
    ): Fetched<T>? = first(what, sources, client, timeout) { _, status, body -> if (status.isSuccess()) parse(body) else null }

    /**
     * 带 ETag 问的取法 (数据表、推荐目录这类大一点、多数时候没变的文件): 按 [sources] 的顺序试, 对 [previous] 来自的那个入口带上它的 ETag,
     * 回 304 就是 [Conditional.NotModified]. 2xx 的正文交给 [accept] 校验 (可以挂起, 如再下几份文件), 返回值就是结果, [reject] 试下一个.
     * 本机没有那一份时 [previous] 给 null (要整份). 都不行返回 null.
     */
    suspend fun <T : Any> firstChanged(
        what: String,
        sources: List<String>,
        client: () -> ScopedHttpClient,
        previous: Revision?,
        timeout: Duration = SOURCE_TIMEOUT,
        accept: suspend (source: String, bytes: ByteArray) -> T,
    ): Conditional<T>? {
        val failures = mutableListOf<String>()
        for (source in sources) {
            val etag = previous?.etag?.takeIf { previous.source == source }
            val reply = request(source, client, timeout, etag, failures) ?: continue
            if (reply.status == HttpStatusCode.NotModified && etag != null) {
                logger.info { "$what: not modified ($source)" }
                return Conditional.NotModified(source)
            }
            val value = judge(source, failures) {
                if (!reply.status.isSuccess()) reject(reply.status.value.toString())
                accept(source, reply.bytes)
            } ?: continue
            logger.info { "$what: got it from $source" }
            return Conditional.Changed(value, reply.etag, source)
        }
        logger.info { "$what: no usable source (${failures.joinToString()})" }
        return null
    }

    private class Reply(val status: HttpStatusCode, val bytes: ByteArray, val etag: String?)

    /**
     * 发一次请求, 最多等 [timeout] 收到回应 (被墙的入口常常连接一直挂着, 不限时要等到客户端自己的连接超时, 还会被重试);
     * 正文按客户端的超时读完 (大文件在慢网上要读一阵, 不掐). 连不上、超时记进 [failures] 返回 null.
     */
    private suspend fun request(
        source: String,
        client: () -> ScopedHttpClient,
        timeout: Duration,
        ifNoneMatch: String?,
        failures: MutableList<String>,
    ): Reply? = try {
        coroutineScope {
            val responded = CompletableDeferred<Unit>()
            val call = async {
                client().use {
                    prepareGet(source) {
                        expectSuccess = false
                        if (ifNoneMatch != null) header(HttpHeaders.IfNoneMatch, ifNoneMatch)
                    }.execute { response ->
                        responded.complete(Unit)
                        Reply(response.status, response.readRawBytes(), response.headers[HttpHeaders.ETag])
                    }
                }
            }
            if (withTimeoutOrNull(timeout) { responded.await() } == null) {
                call.cancel()
                failures += "$source timed out after $timeout"
                null
            } else {
                call.await()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failures += "$source ${e::class.simpleName}"
        null
    }

    /** 跑校验; 被 [reject] 或出错时记进 [failures] 返回 null. */
    private suspend fun <T : Any> judge(source: String, failures: MutableList<String>, check: suspend () -> T): T? = try {
        check()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Rejected) {
        failures += "$source ${e.reason}"
        null
    } catch (e: Exception) {
        failures += "$source ${e::class.simpleName}"
        null
    }

    /** 每个入口默认最多等多久收到回应. 连得上的入口几秒内就回. */
    val SOURCE_TIMEOUT = 10.seconds

    private val logger = logger<RemoteFetch>()
}

/**
 * 一份远程文件什么时候取. 取到没有、什么时候取到的由使用方自己记 ([lastFetchedAt]), 这里只管时机, 同一时间只取一次:
 * - 建出来就看一次, 之后每 [checkEvery] 看一眼: 离上次取到超过 [staleAfter] (或从没取到过) 就取 (电视上的应用常常几天不重启);
 * - 给了 [foregroundAfter] 时, 应用回到前台 (冷启动也算) 离上次取到超过它也取 —— 给要尽快送到正在用的电视的 (通知、配置);
 * - [refreshNow]: 不管多久前取过, 现在就取 (网络体检把 [created] 里 [refreshOnNetworkCheck] 的取一遍).
 *
 * @param fetch 取一次并存下, 取到了返回 true
 */
class RemoteRefresh(
    /** 日志与网络体检里用的名字. */
    val name: String,
    scope: CoroutineScope,
    private val staleAfter: Duration,
    private val checkEvery: Duration,
    private val foregroundAfter: Duration? = null,
    /** 网络体检时取不取 (体检限时, 大文件取到一半被掐掉就白下了; 它们按 ETag 定期问). */
    val refreshOnNetworkCheck: Boolean = true,
    private val lastFetchedAt: suspend () -> Long?,
    private val fetch: suspend () -> Boolean,
) {
    private val fetching = Mutex()
    private val firstCheck = CompletableDeferred<Unit>()

    init {
        registry.update { it + this }
        scope.launch {
            refreshIfOlderThan(staleAfter)
            firstCheck.complete(Unit)
            while (true) {
                delay(checkEvery)
                refreshIfOlderThan(staleAfter)
            }
        }.invokeOnCompletion {
            // 作用域已经取消、根本没开始跑 (或中途被取消) 也算检查完了, 等它的地方别一直等
            firstCheck.complete(Unit)
        }
        if (foregroundAfter != null) {
            scope.launch {
                // StateFlow 本来就只在值变了时发: 每次由 false 变 true 就是一次回到前台
                AppForeground.started.filter { it }.collect { refreshIfOlderThan(foregroundAfter) }
            }
        }
    }

    /** 等建出来后的第一次检查做完 (取了或不用取); 想用新的就等它, 不想等就直接读使用方存的那份. */
    suspend fun awaitFirstCheck() = firstCheck.await()

    /** 过期了 (或从没取到过) 就现在取; 没过期什么都不做. 取了且取到返回 true. */
    suspend fun refreshIfStale(): Boolean = refreshIfOlderThan(staleAfter)

    /** 不管多久前取过, 现在就取一次. 取到了返回 true. */
    suspend fun refreshNow(): Boolean = fetching.withLock { fetchCatching() }

    /** 离上次取超过 [age] 就现在取; 没到什么都不做 (一个使用方的某个时机另有自己的间隔时用, 如回到首页). 取了且取到返回 true. */
    suspend fun refreshIfOlderThan(age: Duration): Boolean = fetching.withLock {
        val last = lastFetchedAt()
        if (last != null && currentTimeMillis() - last < age.inWholeMilliseconds) return@withLock false
        fetchCatching()
    }

    /** 取的时候出错 (写盘失败、解析抛异常…) 只记一笔, 不弄死定期检查的循环. */
    private suspend fun fetchCatching(): Boolean = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "$name: refresh failed" }
        false
    }

    companion object {
        private val registry = MutableStateFlow<List<RemoteRefresh>>(emptyList())
        private val logger = logger<RemoteRefresh>()

        /** 这个进程里建过的 (网络体检把其中 [refreshOnNetworkCheck] 的取一遍). */
        val created: List<RemoteRefresh> get() = registry.value
    }
}
