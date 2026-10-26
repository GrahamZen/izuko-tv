/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.app.data.models.preference.BangumiRouteKind
import kotlin.time.Duration.Companion.minutes

/**
 * 发往 bangumi 的请求实际走的一条线路. 由设置里开着的 [BangumiRouteKind] 算出来 (见 [BangumiEndpointProvider]),
 * 交给 [BangumiRoutingFeatureHandler] 依次尝试.
 *
 * 每条线路只声明自己的性质 —— 第三方看不看得到内容、能不能代理主站、浏览器用不用得了 ——
 * 什么请求能走它、浏览器打开哪个站, 都由调度器与 [BangumiEndpointProvider] 按这些性质统一判断.
 */
sealed class BangumiRoute {
    abstract val kind: BangumiRouteKind

    /** 日志里的名字. */
    abstract val label: String

    /**
     * 经第三方转发, 对方看得到明文 (令牌、换 token 的表单). 带凭证的请求只在用户允许时才走它
     * (见 [BangumiRouting.allowCredentialsViaThirdParty]).
     */
    abstract val thirdParty: Boolean

    /** 代理得了 bgm.tv 主站: 换 token 与续期都在主站的 `/oauth/` 上, 只发给这样的线路. */
    abstract val servesMainSite: Boolean

    /** 浏览器能打开这条线路 (网页链接、授权页). 浏览器做不了加密直连. */
    abstract val browsable: Boolean

    /** 浏览器上用的网页根域名; `null` = 官方. */
    open val webRoot: String? get() = null

    /**
     * 请求落在这条线路上之后记住多久 (毫秒); `null` = 一直记着, 直到路由变了.
     * 官方以外的都会过期: 一次网络抖动落到后面线路上的人, 过一阵重新先试前面的线路, 不会一直留在后面.
     * 官方确定连不上另有出路 (调度回报后问用户要不要关掉官方, 见 [BangumiRoutingFeatureHandler]), 不靠粘性.
     */
    open val stickyMillis: Long? get() = FALLBACK_STICKY_MILLIS

    /**
     * 还没在这条线路上成功过时, 后面又还有线路可退, 读请求试它用短超时. 被墙的表现是连接或握手一直没有回音,
     * 默认 30 秒的超时会让第一次打开白等半分钟.
     */
    open val probeWithShortTimeouts: Boolean get() = false

    /** 原站域名在这条线路上发往哪个域名; `null` = 这条线路接不了它. */
    abstract fun hostFor(originHost: String): String?

    /** [hostFor] 的反向; [host] 不是这条线路的域名时为 `null`. */
    abstract fun originHostOf(host: String): String?

    /** bangumi 官方地址. */
    data object Origin : BangumiRoute() {
        override val kind get() = BangumiRouteKind.ORIGIN
        override val label get() = "origin"
        override val thirdParty get() = false
        override val servesMainSite get() = true
        override val browsable get() = true
        override val stickyMillis: Long? get() = null
        override val probeWithShortTimeouts get() = true
        override fun hostFor(originHost: String) = originHost
        override fun originHostOf(host: String) = host.takeIf { BangumiMirrorHosts.isBangumiHost(it) }
    }

    /**
     * 用户自建的整族通配反代 (`*.root → *.bgm.tv`). 是用户自己的服务器: 可信, 也代理得了主站.
     */
    data class Custom(val root: String) : BangumiRoute() {
        override val kind get() = BangumiRouteKind.CUSTOM
        override val label get() = root
        override val thirdParty get() = false
        override val servesMainSite get() = true
        override val browsable get() = true
        override val webRoot get() = root
        override fun hostFor(originHost: String) = BangumiMirrorHosts.mirrorHostOf(originHost, root)
        override fun originHostOf(host: String) = BangumiMirrorHosts.originHostOf(host, root)
    }

    /**
     * 加密直连: 连官方, 但要连的域名藏在 ECH 加密的握手里. 请求改写到只在本机认得的域名 ([HOST_ROOT] 之下),
     * 引擎见到这些域名就按 ECH 连 (见 [BangumiEchTransport]); 浏览器做不了这件事.
     */
    data object Ech : BangumiRoute() {
        /**
         * 本机才认得的域名根: `api.bgm.tv` → `api.bgm-tv.ech.invalid`. `.invalid` 保证解析不出来 ——
         * 哪个客户端漏装了加密直连, 请求只会失败, 不会以明文发到别处.
         */
        const val HOST_ROOT = "bgm-tv.ech.invalid"

        override val kind get() = BangumiRouteKind.ECH
        override val label get() = "ech"
        override val thirdParty get() = false
        override val servesMainSite get() = true
        override val browsable get() = false
        override val probeWithShortTimeouts get() = true
        override fun hostFor(originHost: String) = BangumiMirrorHosts.mirrorHostOf(originHost, HOST_ROOT)
        override fun originHostOf(host: String) = BangumiMirrorHosts.originHostOf(host, HOST_ROOT)
    }

    /**
     * 社区镜像 (整族通配反代). 第三方: 默认不带凭证; 主站挡在反爬验证后面, 换 token 与续期不发给它.
     */
    data class Mirror(val root: String) : BangumiRoute() {
        override val kind get() = BangumiRouteKind.MIRRORS
        override val label get() = root
        override val thirdParty get() = true
        override val servesMainSite get() = false
        override val browsable get() = true
        override val webRoot get() = root
        override fun hostFor(originHost: String) = BangumiMirrorHosts.mirrorHostOf(originHost, root)
        override fun originHostOf(host: String) = BangumiMirrorHosts.originHostOf(host, root)
    }

    private companion object {
        val FALLBACK_STICKY_MILLIS = 30.minutes.inWholeMilliseconds
    }
}

/**
 * 一次请求可以走哪些线路. 由设置 + 镜像清单 + 平台能力算出来, 见 [BangumiEndpointProvider].
 */
data class BangumiRouting(
    /** 依次尝试的线路. 只有 [BangumiRoute.Origin] = 只直连. */
    val routes: List<BangumiRoute>,
    /** 带凭证的请求也允许走第三方线路 (用户在弹窗里了解风险后打开的). */
    val allowCredentialsViaThirdParty: Boolean = false,
    /**
     * 数据里可能出现的线路域名. 镜像会把响应里的图片等地址改写成自己的域名, 这些地址随数据存进本地;
     * 请求它们时先换回原站, 再按本路由决定打哪儿 —— 不然关掉镜像之后它们还一直走镜像, 镜像下线就全挂.
     */
    val knownRoutes: List<BangumiRoute> = routes,
) {
    /** [host] 换回原站域名; 既不是 bangumi 域名也不是任何已知线路的域名时为 `null`. */
    fun originHostOf(host: String): String? =
        BangumiRoute.Origin.originHostOf(host) ?: knownRoutes.firstNotNullOfOrNull { it.originHostOf(host) }

    companion object {
        /** 只直连. */
        val Direct = BangumiRouting(listOf(BangumiRoute.Origin))
    }
}
