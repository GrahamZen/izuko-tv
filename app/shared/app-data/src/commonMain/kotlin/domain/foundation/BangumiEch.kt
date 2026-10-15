/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.HttpClientConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 加密直连 ([BangumiRoute.Ech]) 的连接层: 客户端发往 [BangumiRoute.Ech.HOST_ROOT] 之下的请求, 由它按 ECH 连到官方.
 *
 * 只有支持的平台有实现 (安卓上用 Conscrypt); 没有实现的平台不提供这条线路.
 *
 * 实现要做到:
 * - **解析**: 换成 [BangumiEchAddressList] 里的 Cloudflare 地址 —— 大陆的 DNS 对 bangumi 是污染的;
 * - **握手**: 内层 (加密的) 用真实域名, 外层只露出 Cloudflare 的公共名, 配置来自 [BangumiEchConfig];
 * - **证书校验与 Host 头**: 按真实域名 (Cloudflare 按 Host 头分发到 bangumi);
 * - **不碰别的请求**: 客户端上其他请求的解析、TLS、连接复用都与没装时一样 —— 直连 bangumi 的人不受这条线路影响.
 */
interface BangumiEchTransport {
    /**
     * 这个平台提供加密直连这条线路. 为 `false` 时不提供, [install] 也什么都不装.
     * 真连得上与否到第一次用时才知道: 用不了时那些请求失败, 由调度回落到下一条线路.
     */
    val available: Boolean

    /** 装到客户端的引擎上. 只在建客户端时调用. */
    fun install(config: HttpClientConfig<*>)

    /**
     * 加密直连连不上时调用: 是 ECH 握手被拒 (配置过期之类) 就换一份新配置, 返回 `true` = 换到了, 值得再试一次.
     */
    suspend fun recover(cause: Throwable): Boolean
}

/**
 * 客户端要不要能连加密直连的域名 (见 [BangumiEchTransport]). 只管引擎, 不改写地址 —— 改写由 [BangumiRoutingFeature] 做;
 * 所以连通性检测这种「不改写地址」的客户端也能单独带上它, 直接测加密直连.
 */
val BangumiEchFeature = ScopedHttpClientFeatureKey<Boolean>("BangumiEch")

/**
 * [BangumiEchFeature] 的处理器. 平台不支持加密直连时 [transport] 为 `null`, 什么都不装 —— 但仍要注册:
 * 没注册的特性会让 `DefaultHttpClientProvider` 建客户端时报错.
 */
class BangumiEchFeatureHandler(
    private val transport: BangumiEchTransport?,
) : ScopedHttpClientFeatureHandler<Boolean>(BangumiEchFeature) {
    override fun applyToConfig(config: HttpClientConfig<*>, value: Boolean) {
        if (value) transport?.install(config)
    }
}

/**
 * ECH 配置 (`ECHConfigList`): 握手时用它加密内层. Cloudflare 所有站共用一份, 取自 `crypto.cloudflare.com` 的
 * DNS HTTPS 记录 —— bangumi 自己的记录里没有发布 (Cloudflare 照样接受, 2026-10-13 实测).
 *
 * 包里带一份; 第一次用到时在后台经 DoH 拉一次新的, 之后握手被拒时再拉. 问哪些 DoH 见 [BangumiEchDohList].
 *
 * 配置本身不放进仓库清单: Cloudflare 隔几个小时就轮换, 清单经 CDN 缓存、一天才拉一次, 拿到的总是过期的.
 */
@OptIn(ExperimentalEncodingApi::class)
class BangumiEchConfig(
    /** 惰性取, 理由同 [RepoHostedList] 的构造参数. */
    private val client: () -> ScopedHttpClient,
    private val scope: CoroutineScope,
    /** 按顺序问的 DoH 地址, 每次拉时取 (见 [BangumiEchDohList]); 第一次真用到时才取, 理由同 [ConscryptEchTransport] 的地址清单. */
    private val dohEndpoints: () -> List<String> = { BangumiEchDohList.BUNDLED },
    private val now: () -> Long = { currentTimeMillis() },
) {
    private val logger = logger<BangumiEchConfig>()

    /** 现在用的配置. */
    @Volatile
    var current: ByteArray = Base64.decode(BUNDLED)
        private set

    private val mutex = Mutex()
    private var fetchedAt: Long? = null

    /** 上次换到新配置的时刻. */
    private var changedAt: Long? = null
    private val backgroundStarted = atomic(false)

    /** 这个进程里第一次用到时在后台拉一次: 包里那份可能已经轮换掉了. */
    fun refreshInBackgroundOnce() {
        if (backgroundStarted.compareAndSet(expect = false, update = true)) {
            scope.launch { refresh() }
        }
    }

    /**
     * 拉一份新的. 返回 `true` = 拿到了与原来不同的配置.
     * 两次之间至少隔 [MIN_REFRESH_INTERVAL_MILLIS]: 握手被拒可能另有原因 (比如被拦截), 不能每个请求都去问一遍 DNS.
     *
     * @param acceptRecentChange 握手被拒后调用时为 `true`: 配置在 [RECENT_CHANGE_MILLIS] 内刚换过 (别的请求拉到的) 也返回 `true` ——
     * 被拒的那次握手多半还在用旧配置, 值得用新的再试一次.
     */
    suspend fun refresh(acceptRecentChange: Boolean = false): Boolean = mutex.withLock {
        val changed = changedAt
        if (acceptRecentChange && changed != null && now() - changed < RECENT_CHANGE_MILLIS) return true
        val last = fetchedAt
        if (last != null && now() - last < MIN_REFRESH_INTERVAL_MILLIS) return false
        fetchedAt = now()
        for (endpoint in dohEndpoints()) {
            val config = try {
                withTimeoutOrNull(DOH_TIMEOUT) {
                    client().use { get(endpoint) { header(HttpHeaders.Accept, "application/dns-json") }.bodyAsText() }
                }?.let(::parseDohAnswer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.info { "ECH config: $endpoint failed (${e::class.simpleName})" }
                null
            }
            if (config == null) continue
            val isNew = !config.contentEquals(current)
            current = config
            if (isNew) changedAt = now()
            logger.info { "ECH config fetched from $endpoint, changed=$isNew" }
            return isNew
        }
        logger.info { "ECH config: all DoH endpoints failed, keeping the current one" }
        false
    }

    companion object {
        /**
         * 从 `crypto.cloudflare.com` 取的 (公共名 `cloudflare-ech.com`). Cloudflare 隔几个小时就轮换一次, 所以这份多半已经过期:
         * 第一次握手被拒后经 DoH 换新的再试 (见 [refresh]).
         */
        const val BUNDLED = "AEX+DQBBMQAgACA/y247e5JDoD0Lch7mj0p0Uie7Z8Z/LCbpIDyspl9aNAAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA="

        private val MIN_REFRESH_INTERVAL_MILLIS = 1.minutes.inWholeMilliseconds
        private val RECENT_CHANGE_MILLIS = 30.seconds.inWholeMilliseconds
        private val DOH_TIMEOUT = 5.seconds

        /** DoH JSON 回答里 HTTPS 记录的 `ech=` 参数 (阿里的回答给值加了引号, 在 JSON 里转义成 `\"`). */
        private val ECH_PARAM = Regex("""ech=(?:\\")?([A-Za-z0-9+/]+=*)""")

        /** 从 DoH 的 JSON 回答里取出 ECH 配置; 没有或解不开时为 `null`. */
        fun parseDohAnswer(body: String): ByteArray? {
            val encoded = ECH_PARAM.find(body)?.groupValues?.get(1) ?: return null
            return runCatching { Base64.decode(encoded) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * 拉 ECH 配置 ([BangumiEchConfig]) 问哪些 DoH: 仓库根目录的 `bangumi-ech-doh.json`, 内置一份兜底 (见 [RepoHostedList]), 按顺序问.
 *
 * 每条是一个完整的地址, 回 DoH 的 JSON 格式 (`application/dns-json`), 问的是带 Cloudflare 公共 ECH 配置的 HTTPS 记录 ——
 * 换一家 DoH、或者换一个查询的域名, 都只改清单. 大陆连得上的排前面.
 */
class BangumiEchDohList(
    cache: Settings<RepoHostedListCache>,
    client: () -> ScopedHttpClient,
    scope: CoroutineScope,
) : RepoHostedList(SPEC, cache, client, scope) {
    /** 当前清单. */
    val endpoints: StateFlow<List<String>> = entries.stateIn(scope, SharingStarted.Eagerly, SPEC.bundled)

    companion object {
        private const val QUERY_NAME = "crypto.cloudflare.com"

        /** 前两个大陆连得上 (2026-10-15 都回 JSON、带 ech 参数). */
        val BUNDLED = listOf(
            "https://dns.alidns.com/resolve?name=$QUERY_NAME&type=65",
            "https://doh.360.cn/resolve?name=$QUERY_NAME&type=65",
            "https://cloudflare-dns.com/dns-query?name=$QUERY_NAME&type=HTTPS",
            "https://dns.google/resolve?name=$QUERY_NAME&type=65",
        )

        private val HTTPS_URL = Regex("""https://[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+(:\d+)?([/?]\S*)?""")

        /** 只认带域名的 https 地址 (清单是远程内容); 认不出返回 `null`. */
        fun normalizeEndpoint(input: String): String? = input.trim().takeIf { HTTPS_URL.matches(it) }

        private val SPEC = Spec(
            fileName = "bangumi-ech-doh.json",
            field = "endpoints",
            bundled = BUNDLED,
            normalize = ::normalizeEndpoint,
        )
    }
}

/**
 * 加密直连连哪些 Cloudflare 地址: 仓库根目录的 `bangumi-ech-addresses.json`, 内置一份兜底 (见 [RepoHostedList]).
 *
 * 不用 DNS 解析: 大陆对 bangumi 的解析是污染的. 任何一个 Cloudflare 地址都按握手里的域名把请求交给 bangumi,
 * 所以清单里也放别的站的地址 —— 只封 bangumi 自己那几个地址拦不住. 地址被限速或封了就改清单, 不用发版.
 */
class BangumiEchAddressList(
    cache: Settings<RepoHostedListCache>,
    client: () -> ScopedHttpClient,
    scope: CoroutineScope,
) : RepoHostedList(SPEC, cache, client, scope) {
    /** 当前清单. 连接时在引擎线程上同步取, 所以是 StateFlow. */
    val addresses: StateFlow<List<String>> = entries.stateIn(scope, SharingStarted.Eagerly, SPEC.bundled)

    companion object {
        /** 2026-10-13 实测都能加密直连到 bangumi; 后两个是 bangumi 自己的. */
        val BUNDLED = listOf("172.67.134.140", "104.21.6.61", "172.67.73.67", "104.26.8.23")

        private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

        /** 只认 IP 地址 (清单是远程内容, 写进去的东西不由我们决定); 认不出返回 `null`. */
        fun normalizeAddress(input: String): String? {
            val s = input.trim()
            IPV4.matchEntire(s)?.let { match ->
                return s.takeIf { match.groupValues.drop(1).all { it.toInt() <= 255 } }
            }
            // IPv6: 只有十六进制数字与冒号, 至少两个冒号
            return s.takeIf { it.count { c -> c == ':' } >= 2 && it.all { c -> c == ':' || c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
        }

        private val SPEC = Spec(
            fileName = "bangumi-ech-addresses.json",
            field = "addresses",
            bundled = BUNDLED,
            normalize = ::normalizeAddress,
        )
    }
}
