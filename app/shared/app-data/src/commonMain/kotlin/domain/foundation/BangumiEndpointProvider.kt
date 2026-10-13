/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.app.data.models.preference.BangumiRouteKind

/**
 * 把「bangumi 经哪些线路连」这个设置算成 [BangumiRouting], 喂给 [BangumiRoutingFeatureHandler];
 * 再按请求实际落在哪条线路上, 告诉不经过 HttpClient 的地方 (浏览器打开的网页、授权页) 该用哪个站.
 */
class BangumiEndpointProvider(
    settings: Flow<BangumiEndpointSettings>,
    mirrors: Flow<List<String>>,
    private val scope: CoroutineScope,
    /**
     * 确定官方连不上时调用, 参数是通了的那条线路. 由它决定怎么改设置 (关掉「官方直连」, 或先请界面问用户),
     * 见 [reportOriginUnreachable].
     */
    private val onOriginUnreachable: suspend (workingRoute: BangumiRoute) -> Unit = {},
) {
    /**
     * **必须是热流**: 每个发往 bangumi 的请求都要 `first()` 它一次, 冷流的话每次请求都重跑一遍
     * combine (还会重读一次 DataStore).
     *
     * 设置与清单读出来之前没有值, 请求先等着 (两者都是本地 DataStore, 很快). 拿一个占位的「只连官方」顶上的话,
     * 启动时头几个请求会只打官方、也不回落 —— 关掉了官方的人也一样, 在大陆就是失败.
     */
    private val routingState: StateFlow<BangumiRouting?> = combine(settings, mirrors) { settings, mirrors ->
        computeRouting(settings, mirrors)
    }.distinctUntilChanged()
        .stateIn<BangumiRouting?>(scope, SharingStarted.Eagerly, null)

    val routing: Flow<BangumiRouting> = routingState.filterNotNull()

    /** 当前的路由 (设置与清单读出来之前为 `null`). 给按路由记状态、路由一变就作废的地方比对用. */
    val currentRouting: BangumiRouting? get() = routingState.value

    /**
     * 把线路上的地址换回原站的 (镜像改写过的响应、以及随之存下来的数据里都有这种地址), 其他地址原样返回.
     * 给要按原站域名做判断、又不经过 [BangumiRoutingFeatureHandler] 的地方用 (网页控制台转发图片的白名单);
     * 真正去取时仍经过 handler, 按当前设置决定走哪条线路.
     */
    fun canonicalUrl(url: String): String {
        val routing = routingState.value ?: return url
        val parsed = runCatching { Url(url) }.getOrNull() ?: return url
        if (BangumiMirrorHosts.isBangumiHost(parsed.host)) return url
        val host = routing.originHostOf(parsed.host) ?: return url
        return URLBuilder(parsed).apply { this.host = host }.buildString()
    }

    /**
     * 请求最近落在哪条线路上, 连同那个请求用的路由. 见 [reportSettled].
     * 不是 data class: 路由按身份比 (见 [activeRoute]), 内容相同的新旧两份回报不能被 StateFlow 当成重复吞掉.
     */
    private class Settled(val routing: BangumiRouting?, val route: BangumiRoute?)

    private val settled = MutableStateFlow(Settled(routing = null, route = null))

    /**
     * [BangumiRoutingFeatureHandler] 最近落在的线路 (`null` = 还没发过请求), 只认按当前路由落下的:
     * 换了设置或清单之后, 旧路由下的落点不算数, 等新路由下的请求落地再报. 不然从镜像切回官方后,
     * 只要还没发新请求 (比如一直停在设置页), 就一直被当成经镜像.
     *
     * **按对象身份比**: 设置每真变一次, [routingState] 换一个新对象 (相同的不重发). 按相等比的话, 关掉官方
     * → 又打开, 头一次那个路由下还在途的请求落地时会被当成新路由的落点.
     */
    private val activeRoute: Flow<BangumiRoute?> = combine(routingState, settled) { routing, settled ->
        settled.route?.takeIf { routing != null && settled.routing === routing && it in routing.routes }
    }

    /** 由 [BangumiRoutingFeatureHandler] 在请求落到哪条线路变了时调用; [routing] 是这个请求用的路由, 见 [activeRoute]. */
    fun reportSettled(routing: BangumiRouting, route: BangumiRoute) {
        settled.value = Settled(routing, route)
    }

    private val switching = MutableStateFlow(false)

    /**
     * 由 [BangumiRoutingFeatureHandler] 在「官方那一跳连接失败、后面的线路成功」时调用: 交给 [onOriginUnreachable] 据此改设置,
     * 之后的请求不再先吃一次官方的连接超时. 同一时刻只处理一次 (启动时并发的请求会一起报).
     *
     * [routing] 是这个请求用的路由, 不是当前的就不算数 (同样按对象身份比, 见 [activeRoute]): 用户刚重新打开官方时,
     * 旧设置下还在途的请求不能替他关回去.
     */
    fun reportOriginUnreachable(routing: BangumiRouting, workingRoute: BangumiRoute) {
        if (routing !== routingState.value) return
        if (!switching.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                onOriginUnreachable(workingRoute)
            } finally {
                switching.value = false
            }
        }
    }

    /**
     * 要用户在网页上登录的地方 (OAuth 授权页、去网页发表评论) 用哪个站: 可信镜像的根域名, `null` = 官方.
     *
     * 这些地址**不经过 HttpClient**, 要丢给外部浏览器或内嵌 WebView 打开, 所以只能在拼地址时换站. 用户要在这个页面上
     * 输入 bangumi 的账号密码, 第三方镜像只在用户允许凭证经过时才用. 选法见 [browserRoot].
     */
    val trustedMirrorRoot: StateFlow<String?> = combine(routing, activeRoute) { routing, active ->
        browserRoot(routing, active, forCredentials = true)
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 交给浏览器打开的 Bangumi 网页 (条目页、分集页、人物页、评论里的链接…) 用哪个站, `null` = 官方.
     * 与 [trustedMirrorRoot] 不同, 不看「登录也经过镜像」开没开 —— 看网页不带应用的登录凭证.
     */
    val webMirrorRoot: StateFlow<String?> = combine(routing, activeRoute) { routing, active ->
        browserRoot(routing, active, forCredentials = false)
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 交给浏览器之前把地址换到当前线路上 (见 [webMirrorRoot]): 存下来的线路地址先换回原站, 再按现在的设置换站.
     * 不是 Bangumi 的地址原样返回.
     */
    fun webLink(url: String): String {
        val canonical = canonicalUrl(url)
        val root = webMirrorRoot.value ?: return canonical
        val parsed = runCatching { Url(canonical) }.getOrNull() ?: return canonical
        val host = BangumiMirrorHosts.mirrorHostOf(parsed.host, root) ?: return canonical
        return URLBuilder(parsed).apply { this.host = host }.buildString()
    }

    /**
     * 现在是不是经第三方镜像连 bangumi: 请求落在镜像上, 或还没落地而官方关着、排在最前的是镜像.
     * 这时授权登录走不通 —— 换 token 在 bgm.tv 主站上, 镜像把主站挡在反爬验证后面, 也没有别的线路代理得了主站
     * (有的话它排在镜像前面, 请求就落在它上面了), 只能用个人令牌登录 (见 `BangumiOAuthManager.loginWithPersonalToken`).
     */
    val viaThirdPartyMirror: StateFlow<Boolean> = combine(routing, activeRoute) { routing, active ->
        val effective = active ?: routing.routes.first().takeIf { it != BangumiRoute.Origin }
        effective?.thirdParty == true
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** 用户怎么登录 (见 [BangumiLoginPath]): 按请求落在的线路定, 还没落地时按排在最前的那条. */
    val loginPath: StateFlow<BangumiLoginPath> = combine(routing, activeRoute) { routing, active ->
        BangumiLoginPath.of(active ?: routing.routes.first())
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, BangumiLoginPath.BROWSER)

    companion object {
        /** 设置 + 镜像清单 → 依次尝试的线路. 一条都没开 (或开着的都用不了) 时只连官方. */
        fun computeRouting(settings: BangumiEndpointSettings, mirrors: List<String>): BangumiRouting {
            val custom = settings.customRoot?.let { BangumiRoute.Custom(it) }
            val mirrorRoutes = mirrors.map { BangumiRoute.Mirror(it) }
            val routes = BangumiRouteKind.entries.filter { settings.isEnabled(it) }.flatMap { kind ->
                when (kind) {
                    BangumiRouteKind.ORIGIN -> listOf(BangumiRoute.Origin)
                    BangumiRouteKind.CUSTOM -> listOfNotNull(custom)
                    BangumiRouteKind.MIRRORS -> mirrorRoutes
                }
            }.ifEmpty { listOf(BangumiRoute.Origin) }
            return BangumiRouting(
                routes = routes,
                allowCredentialsViaThirdParty = settings.allowCredentialsViaMirror,
                // 数据里可能存着用过的任何一条线路的地址, 开没开都要能换回原站 (见 BangumiRouting.knownRoutes)
                knownRoutes = (mirrorRoutes + listOfNotNull(custom)).distinct(),
            )
        }

        /**
         * 浏览器上用哪个站 (`null` = 官方):
         * - 请求正落在浏览器用得了的线路上 (官方 / 自建 / 镜像) 就用它;
         * - 还没落地而官方开着: 官方;
         * - 落在浏览器用不了的线路上, 或官方关着: 按顺序取后面第一条浏览器用得了的, 都没有就官方.
         *
         * @param forCredentials 用户要在这个网页上登录: 第三方镜像只在用户允许凭证经过时才算
         */
        fun browserRoot(routing: BangumiRouting, active: BangumiRoute?, forCredentials: Boolean): String? {
            fun usable(route: BangumiRoute) = route.browsable &&
                    (!forCredentials || !route.thirdParty || routing.allowCredentialsViaThirdParty)
            if (active != null && usable(active)) return active.webRoot
            if (active == null && BangumiRoute.Origin in routing.routes) return null
            return routing.routes.firstOrNull { it != BangumiRoute.Origin && usable(it) }?.webRoot
        }
    }
}

/**
 * 内置的镜像清单 —— **只是兜底**.
 *
 * 镜像域名的死亡率极高 (2026 年 5~8 月: `bgmmi.anibt.net` 与 `bangumi.rdd.moe` 停服,
 * `bangumi.one` 被 TLS 阻断, `bangumi.lol` 被人拿下, `bangumi.pro` 搬到 `bangumi.vip`), 所以真正的清单要在
 * 运行期拉取 —— 见 `BangumiMirrorListRepository`. 这里这份只在"还没拉到过"时用.
 */
object BangumiMirrorList {
    /**
     * 排在前面的先试.
     *
     * `bangumi.vip`: Mirrox (Rust, MIT, `github.com/mirrox-dev/mirrox`) 搭的整族通配反代,
     * 原先的 `bangumi.pro` 现在把请求 301 到这里. 2026-09-23 实测带 token 的 p1 请求全部 200.
     * **它会改写响应体** —— API 返回的封面地址直接就是镜像域名, 所以换了 API 域名等于图床也一起换了;
     * 这些地址随数据存进本地, 请求时会先换回原站再按当前设置走 (见 [BangumiRouting.knownRoutes]).
     *
     * 清单里必须写**跳转之后**的域名: 跨域名跳转时 Ktor 会去掉 `Authorization` 头, 写旧域名的话
     * 匿名浏览照常 (跟着跳过去), 带凭证的请求却在新域名上收到 401.
     */
    val BUNDLED = listOf("bangumi.vip")
}
