/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * 手机网页上的图经电视转发: `api/img?u=<图片地址>`, **原样转, 不重新压缩**. 原则是**手机只需连得上电视**: 电视在用户配好的网络
 * (代理) 下拉得到的图, 手机就看得到 —— 走应用自己的 HTTP 客户端, 跟着代理设置走. 网页把它当某一级候选:
 * - TMDB (播放卡的剧照 / 横屏图): `image.tmdb.org` 在大陆移动网络常被阻断, 这是第一候选;
 * - 竖版封面 (搜索结果 / 播放记录, 见 [remoteCoverCandidates]): 手机先直连 Ani 镜像站原图, 连不上才走这里.
 *
 * **不缩图**: 试过电视把镜像站原图缩到「封面框 × 屏幕倍数 × 1.6」再给, 真机上比直连原图明显发虚 (浏览器画大 JPEG 时在解码阶段按
 * 1/2、1/4 取样, 线条很利; 缩过的图再被浏览器二次缩放就软了, 锐度只剩四到七成), 达不到「清晰度不明显降低」, 撤掉了.
 *
 * **不能拖慢遥控**: 图和遥控接口是同一个地址, 手机浏览器对一个地址最多同时开 6 个连接 (本服务一问一答即关), 转发的图
 * 占着连接等, 播放器状态轮询和按键就得排队. 所以:
 * - 拉图封顶 [FETCH_TIMEOUT], 同时最多拉 [MAX_CONCURRENT_FETCHES] 张, 排 [SLOT_WAIT_MILLIS] 还排不上就回 503;
 * - **按图床各自熔断**: 某个图床连着 [BREAKER_THRESHOLD] 次连不上 (超时 / 网络错误; 回了 404 之类不算), [BREAKER_OPEN_MILLIS] 内
 *   这个图床没缓存的一律当场回 503, 网页随即换下一个候选 —— 连不上 TMDB 不连带别的图床.
 *
 * 其余:
 * - **只转发 [ALLOWED_PREFIXES] 这几个图床**: 这是开在局域网上的口子 (虽然有 token), 不能变成随便拉任意地址的通用代理.
 * - 内存里留最近的 [MAX_CACHE_BYTES]; 单张超过 [MAX_IMAGE_BYTES] 不转 (镜像站原图常见几百 KB, 最大见过近 1MB).
 * - 单个地址拉不到记 [FAILURE_TTL_MILLIS], 期间直接回 502.
 * - 回给手机的带 `private, max-age`: 同一个页面里重开面板浏览器自己有, 不再来要.
 */
internal object RemoteImageProxy {
    private val logger = logger<RemoteImageProxy>()
    private val httpClientProvider: HttpClientProvider get() = KoinPlatform.getKoin().get()
    private val client by lazy { httpClientProvider.get() }

    private class Image(val bytes: ByteArray, val contentType: String)

    /** 拉一张的结果; [Unreachable] = 连不上 (计入熔断), [Failed] = 连上了但没拿到图. */
    private sealed interface Fetched {
        class Ok(val image: Image) : Fetched
        data object Failed : Fetched
        data object Unreachable : Fetched
    }

    /** 一个图床的熔断状态. */
    private class Breaker {
        val consecutiveUnreachable = AtomicInteger()

        @Volatile
        var openUntil = 0L
    }

    private val breakers = ConcurrentHashMap<String, Breaker>()

    /** 最近用过的在后; 总大小超了从前面丢. */
    private val cache = object : LinkedHashMap<String, Image>(16, 0.75f, true) {}
    private var cacheBytes = 0L
    private val failures = HashMap<String, Long>()
    private val lock = Any()

    private val slots = Semaphore(MAX_CONCURRENT_FETCHES)

    /** 列表里给手机的地址 (相对页面根, 与其它接口一样). */
    fun proxied(url: String): String = "api/img?u=" + URLEncoder.encode(url, Charsets.UTF_8.name())

    fun handle(request: LanHttpRequest): LanHttpResponse {
        val url = request.query.split('&').firstOrNull { it.startsWith("u=") }
            ?.let { runCatching { URLDecoder.decode(it.substring(2), Charsets.UTF_8.name()) }.getOrNull() }
            ?.takeIf { u -> ".." !in u && ALLOWED_PREFIXES.any { u.startsWith(it) } }
            ?: return LanHttpResponse.status(400, "Bad Request")
        return serve(url)
    }

    /**
     * 电视这边自己定的地址 (数据源配置里的图标, 见 [RemoteSourceIcons]): 不是手机传来的, 不走 [ALLOWED_PREFIXES];
     * 缓存 / 并发上限 / 熔断照旧.
     */
    fun serveTrusted(url: String): LanHttpResponse = serve(url)

    private fun serve(url: String): LanHttpResponse {
        cached(url)?.let { return ok(it) }
        val breaker =breakers.getOrPut(runCatching { URI(url).host }.getOrNull().orEmpty()) { Breaker() }
        if (System.currentTimeMillis() < breaker.openUntil) return LanHttpResponse.status(503, "Service Unavailable")
        val failedAt = synchronized(lock) { failures[url] }
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_TTL_MILLIS) {
            return LanHttpResponse.status(502, "Bad Gateway")
        }
        if (!slots.tryAcquire(SLOT_WAIT_MILLIS, TimeUnit.MILLISECONDS)) return LanHttpResponse.status(503, "Service Unavailable")
        try {
            // 排队期间可能已经有人把同一张拉回来了, 或者这个图床熔断了
            cached(url)?.let { return ok(it) }
            if (System.currentTimeMillis() < breaker.openUntil) return LanHttpResponse.status(503, "Service Unavailable")
            return when (val fetched = fetch(url)) {
                is Fetched.Ok -> {
                    breaker.consecutiveUnreachable.set(0)
                    remember(url, fetched.image)
                    ok(fetched.image)
                }

                else -> {
                    if (fetched == Fetched.Unreachable) {
                        if (breaker.consecutiveUnreachable.incrementAndGet() >= BREAKER_THRESHOLD) openBreaker(breaker, url)
                    } else {
                        breaker.consecutiveUnreachable.set(0)
                    }
                    synchronized(lock) {
                        if (failures.size >= MAX_FAILURES) failures.clear() // 只是省等待的记账, 满了清掉无妨
                        failures[url] = System.currentTimeMillis()
                    }
                    LanHttpResponse.status(502, "Bad Gateway")
                }
            }
        } finally {
            slots.release()
        }
    }

    private fun cached(url: String): Image? = synchronized(lock) { cache[url] }

    private fun ok(image: Image) = LanHttpResponse.bytes(image.bytes, image.contentType, cacheControl = "private, max-age=86400")

    private fun openBreaker(breaker: Breaker, url: String) {
        val wasOpen = System.currentTimeMillis() < breaker.openUntil
        breaker.openUntil = System.currentTimeMillis() + BREAKER_OPEN_MILLIS
        breaker.consecutiveUnreachable.set(0)
        if (!wasOpen) {
            logger.info { "Remote image proxy: ${runCatching { URI(url).host }.getOrNull()} unreachable from TV, failing fast for ${BREAKER_OPEN_MILLIS / 1000}s" }
        }
    }

    private fun fetch(url: String): Fetched {
        val started = System.nanoTime()
        return try {
            runBlocking {
                withTimeoutOrNull(FETCH_TIMEOUT) {
                    client.use {
                        val response = get(url)
                        if (!response.status.isSuccess()) {
                            logger.warn { "Remote image proxy: ${response.status} for $url" }
                            return@use Fetched.Failed
                        }
                        val type = response.headers[HttpHeaders.ContentType]?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                        val bytes = response.readRawBytes()
                        if (bytes.size > MAX_IMAGE_BYTES) Fetched.Failed else Fetched.Ok(Image(bytes, type))
                    }
                }
            } ?: Fetched.Unreachable.also { logger.warn { "Remote image proxy: timed out after $FETCH_TIMEOUT for $url" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Remote image proxy failed after ${(System.nanoTime() - started) / 1_000_000}ms for $url: $e" }
            Fetched.Unreachable
        }
    }

    private fun remember(url: String, image: Image) = synchronized(lock) {
        cache.put(url, image)?.let { cacheBytes -= it.bytes.size }
        cacheBytes += image.bytes.size
        val it = cache.entries.iterator()
        while (cacheBytes > MAX_CACHE_BYTES && it.hasNext()) {
            cacheBytes -= it.next().value.bytes.size
            it.remove()
        }
        failures.remove(url)
    }

    private val ALLOWED_PREFIXES = listOf(
        "https://image.tmdb.org/t/p/",
        "https://lain.bgm.tv/",
        "https://static.myani.org/bangumi/",
    )
    private const val MAX_CACHE_BYTES = 24L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_FAILURES = 200
    private const val FAILURE_TTL_MILLIS = 60_000L
    private val FETCH_TIMEOUT = 5.seconds
    private const val MAX_CONCURRENT_FETCHES = 3
    private const val SLOT_WAIT_MILLIS = 1500L
    private const val BREAKER_THRESHOLD = 2
    private const val BREAKER_OPEN_MILLIS = 30_000L
}
