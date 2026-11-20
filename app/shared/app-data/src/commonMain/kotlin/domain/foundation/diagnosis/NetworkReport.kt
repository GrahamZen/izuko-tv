/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation.diagnosis

import kotlinx.serialization.Serializable

/**
 * 一次网络体检的结果 (见安卓的 `NetworkDiagnosis`): 环境、域名解析、Bangumi 每条线路上常用的请求、自家与外部服务、应用里当时的请求情况.
 * 结论 (给人看的几句话) 由界面层按它算.
 */
@Serializable
data class NetworkReport(
    val environment: NetworkEnvironment,
    val dns: List<DnsCheck>,
    val routes: List<RouteCheck>,
    val services: List<ProbeResult>,
    val app: AppNetworkState,
    /** 整次体检花了多久. */
    val durationMs: Long,
)

@Serializable
data class NetworkEnvironment(
    /** 系统有没有当前在用的网络. */
    val connected: Boolean,
    /** `wifi` / `ethernet` / `cellular` / `vpn` …. */
    val transports: List<String> = emptyList(),
    /** 系统验证过能上网 (连得上它的检测地址). `null` = 拿不到. */
    val validated: Boolean? = null,
    val dnsServers: List<String> = emptyList(),
    /** 安卓的「私人 DNS」: `off` / `auto` / 指定的服务器名. */
    val privateDns: String? = null,
    val ipv4: Boolean = false,
    val ipv6: Boolean = false,
    /** 系统里设的 HTTP 代理 (`host:port`). */
    val systemProxy: String? = null,
    /** 应用里设的代理 (只记地址, 不记账号密码). */
    val appProxy: String? = null,
    /** Bangumi 依次尝试的线路 (`origin` / `ech` / 镜像域名 / 自建地址). */
    val routes: List<String> = emptyList(),
    /** 请求最近落在哪条线路上; `null` = 还没有成功过的. */
    val settledRoute: String? = null,
    val allowCredentialsViaMirror: Boolean = false,
    /** 这台设备能不能加密直连 (平台支持且没设代理). */
    val echUsable: Boolean = false,
    val echConfig: EchConfigState? = null,
    /** 加密直连连的 Cloudflare 地址. */
    val echAddresses: List<String> = emptyList(),
    val loggedIn: Boolean = false,
    /** 本机时钟比服务器快多少秒 (负数 = 慢), 按某个回应的 Date 头算; `null` = 没拿到. */
    val clockSkewSeconds: Long? = null,
)

@Serializable
data class EchConfigState(
    val bundled: Boolean,
    val fetchedAgoSeconds: Long? = null,
    val changedAgoSeconds: Long? = null,
    val rejectedRemembered: Boolean = false,
)

/** 一个域名: 系统 DNS 与加密 DNS (DoH) 各解析一次. */
@Serializable
data class DnsCheck(
    val host: String,
    val system: List<String> = emptyList(),
    val systemError: String? = null,
    val systemMs: Long? = null,
    val doh: List<String> = emptyList(),
    val dohError: String? = null,
) {
    /** 系统 DNS 给了 Teredo 段 (`2001:0::/32`) 的 IPv6: 正常网站不会用, 是被墙的域名常见的伪造回答. */
    val fakeAddress: Boolean
        get() = system.any(::isTeredo)

    /**
     * 系统 DNS 与加密 DNS 的回答一个都对不上. 单凭这个不能说被污染: CDN 上的站 (GitHub、TMDB…) 按问的人在哪给不同节点,
     * 两边本来就常常不同; 要再加上直连这个域名连不上 (见界面层的结论).
     */
    val mismatch: Boolean
        get() = system.isNotEmpty() && doh.isNotEmpty() && system.none { it in doh }

    companion object {
        /** `2001:0::/32` (Teredo): 前两组是 `2001:0` / `2001::`. */
        fun isTeredo(address: String): Boolean {
            val a = address.lowercase().removePrefix("/")
            return a.startsWith("2001::") || a.startsWith("2001:0:") || a.startsWith("2001:0000:")
        }
    }
}

/** 一条线路: 常用的几个请求依次各发一次. */
@Serializable
data class RouteCheck(
    /** 线路名, 同 [NetworkEnvironment.routes]. */
    val route: String,
    /** `origin` / `custom` / `ech` / `mirror`. */
    val kind: String,
    /** 设置里开着这条线路 (没开的也测, 看换过去能不能用). */
    val enabled: Boolean,
    val requests: List<ProbeResult>,
    /** Cloudflare 的 `/cdn-cgi/trace` 回答 (`sni` = encrypted 才是真用上了加密直连, `loc` 是国家 / 地区…). */
    val trace: Map<String, String> = emptyMap(),
)

/** 一个请求. 各段耗时从发起算起, 没走到那一段的为 `null`. */
@Serializable
data class ProbeResult(
    /** 这是什么请求 (`trace` / `subject` / `characters` / `staff` / `cover` / `me` / 服务名…). */
    val name: String,
    val url: String,
    /** `direct` / `ech` / `proxy`. */
    val via: String,
    val status: Int? = null,
    /** 没拿到回应时的原因 (异常类名与消息, 带根因). */
    val error: String? = null,
    /** 失败在哪一段: `dns` / `connect` / `tls` / `request` / `response`. */
    val failedAt: String? = null,
    val totalMs: Long? = null,
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    /** 发出请求到收到响应头. */
    val ttfbMs: Long? = null,
    val bytes: Long? = null,
    /** 连上的地址. */
    val remote: String? = null,
    val tls: String? = null,
    val protocol: String? = null,
    /** 前面的请求都连不上, 这个没发. */
    val skipped: Boolean = false,
    /** 加密直连握手被拒后换了配置重试过. */
    val echRetried: Boolean = false,
) {
    /** 拿到了 HTTP 回应 (不管状态码): 线路是通的. */
    val answered: Boolean get() = status != null
}

/** 应用里当时的 Bangumi 请求 (见 `BangumiRouteStats`). */
@Serializable
data class AppNetworkState(
    val inFlight: List<HostInFlight> = emptyList(),
    /** 最近 10 分钟各条线路成功 / 失败次数. */
    val routeCounts: List<RouteCounts> = emptyList(),
    /** 最近的失败, 新的在前. */
    val recentFailures: List<RecentFailure> = emptyList(),
    /** 最近 10 分钟所有线路都失败的请求数. */
    val allFailedRecently: Int = 0,
) {
    @Serializable
    data class HostInFlight(val host: String, val count: Int, val oldestSeconds: Long)

    @Serializable
    data class RouteCounts(val route: String, val ok: Int, val failed: Int)

    @Serializable
    data class RecentFailure(val agoSeconds: Long, val route: String, val host: String, val path: String, val elapsedMs: Long, val cause: String)
}
