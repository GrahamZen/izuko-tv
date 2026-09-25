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
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts

/**
 * 把「bangumi 走哪个地址」这个设置算成 [BangumiRouting], 喂给 [BangumiMirrorFeatureHandler].
 */
class BangumiEndpointProvider(
    settings: Flow<BangumiEndpointSettings>,
    mirrors: Flow<List<String>>,
    private val scope: CoroutineScope,
    /** 把设置换成 [BangumiEndpointSettings.afterOriginUnreachable] 并保存, 见 [reportOriginUnreachable]. */
    private val switchToMirror: suspend () -> Unit = {},
) {
    /**
     * **必须是热流**: 每个发往 bangumi 的请求都要 `first()` 它一次, 冷流的话每次请求都重跑一遍
     * combine (还会重读一次 DataStore).
     *
     * 设置与清单读出来之前没有值, 请求先等着 (两者都是本地 DataStore, 很快). 拿一个占位的「只连官方」顶上的话,
     * 启动时头几个请求会只打官方、也不回落 —— 选了「用镜像」也一样, 在大陆就是失败.
     */
    private val routingState: StateFlow<BangumiRouting?> = combine(settings, mirrors) { settings, mirrors ->
        val custom = BangumiMirrorHosts.normalizeMirrorRoot(settings.customBaseUrl)
        // 数据里可能存着用过的任何一个镜像的地址, 哪一档都要能换回原站 (见 BangumiRouting.knownMirrors)
        val known = (mirrors + listOfNotNull(custom)).distinct()
        when (settings.mode) {
            BangumiEndpointMode.DIRECT -> BangumiRouting.Direct.copy(knownMirrors = known)

            // 第三方镜像默认**不可信**: 它能看到经过它的一切. 带 token 的请求由
            // BangumiMirrorFeatureHandler 拦下来只走直连; 用户了解风险后自己打开了的除外
            BangumiEndpointMode.AUTO -> BangumiRouting(
                mirrors = mirrors,
                trusted = settings.allowCredentialsViaMirror,
                preferDirect = true,
                knownMirrors = known,
            )

            // 同上, 只是不再先试官方
            BangumiEndpointMode.MIRROR -> BangumiRouting(
                mirrors = mirrors,
                trusted = settings.allowCredentialsViaMirror,
                preferDirect = false,
                knownMirrors = known,
            )

            // 自建的视为可信 —— 那是用户自己的服务器, 登录与收藏同步都能用.
            // 地址填得不成样子时退回直连, 而不是拿一个错地址去打 (那会让所有请求都失败,
            // 用户更难看出是自己填错了)
            BangumiEndpointMode.CUSTOM ->
                custom?.let { BangumiRouting(listOf(it), trusted = true, preferDirect = false, knownMirrors = known) }
                    ?: BangumiRouting.Direct.copy(knownMirrors = known)
        }
    }.distinctUntilChanged()
        .stateIn<BangumiRouting?>(scope, SharingStarted.Eagerly, null)

    val routing: Flow<BangumiRouting> = routingState.filterNotNull()

    /**
     * 把镜像上的地址换回原站的 (镜像改写过的响应、以及随之存下来的数据里都有这种地址), 其他地址原样返回.
     * 给要按原站域名做判断、又不经过 [BangumiMirrorFeatureHandler] 的地方用 (网页控制台转发图片的白名单);
     * 真正去取时仍经过 handler, 按当前设置决定打原站还是镜像.
     */
    fun canonicalUrl(url: String): String {
        val known = routingState.value?.knownMirrors.orEmpty()
        if (known.isEmpty()) return url
        val parsed = runCatching { Url(url) }.getOrNull() ?: return url
        val host = known.firstNotNullOfOrNull { BangumiMirrorHosts.originHostOf(parsed.host, it) } ?: return url
        return URLBuilder(parsed).apply { this.host = host }.buildString()
    }

    /** 请求最近落在哪儿 (`mirrorRoot` 为 `null` = 原站), 连同报上来时的路由. 见 [reportSettled]. */
    private data class Settled(val routing: BangumiRouting?, val mirrorRoot: String?)

    private val settled = MutableStateFlow(Settled(routing = null, mirrorRoot = null))

    /**
     * [BangumiMirrorFeatureHandler] 最近落在的镜像 (`null` = 原站或还没发过请求), 只认按当前路由落下的:
     * 换了设置或清单之后, 旧路由下的落点不算数, 等新路由下的请求落地再报. 不然从「用镜像」切回
     * 「官方连不上时用镜像」后, 只要还没发新请求 (比如一直停在设置页), 就一直被当成经镜像.
     */
    private val activeMirror: Flow<String?> = combine(routingState, settled) { routing, settled ->
        settled.mirrorRoot?.takeIf { settled.routing == routing }
    }

    /** 由 [BangumiMirrorFeatureHandler] 在请求落到哪个目标变了时调用. */
    fun reportSettled(mirrorRoot: String?) {
        settled.value = Settled(routingState.value, mirrorRoot)
    }

    private val switching = MutableStateFlow(false)

    /**
     * 由 [BangumiMirrorFeatureHandler] 在「官方那一跳连接失败、镜像成功」时调用: 「官方连不上时用镜像」这一档
     * 据此改成「用镜像」并保存, 之后的请求不再先吃一次官方的连接超时. 同一时刻只改一次 (启动时并发的请求会一起报).
     */
    fun reportOriginUnreachable() {
        if (!switching.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                switchToMirror()
            } finally {
                switching.value = false
            }
        }
    }

    /**
     * **可信**镜像的根域名; `null` = 敏感地址一律用原站.
     *
     * 给那些**不经过 HttpClient、换算不了**的地址用 —— 主要是 OAuth 授权页 (要丢给外部浏览器或
     * 内嵌 WebView 打开). 刻意只认可信镜像: 授权页上用户要输入 bangumi 的账号密码,
     * 第三方反代能原样看到.
     *
     * 自建地址与「用镜像」直接用 (请求落在哪个镜像上就用哪个, 还没发过请求时用清单第一个);
     * 「官方连不上时用镜像」且用户允许凭证经过镜像时, 只在请求**已经落到镜像上** (原站连不上) 时才用那个镜像 ——
     * 原站能连就照常用原站.
     */
    val trustedMirrorRoot: StateFlow<String?> = combine(routing, activeMirror) { routing, active ->
        val settled = active?.takeIf { it in routing.mirrors }
        when {
            !routing.trusted -> null
            !routing.preferDirect -> settled ?: routing.mirrors.firstOrNull()
            else -> settled
        }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 现在是不是经第三方镜像连 bangumi: 「用镜像」, 或「官方连不上时用镜像」且请求已经落到镜像上.
     * 这时授权登录走不通 —— 镜像把 bgm.tv 主站 (授权页与换 token 都在那) 挡在反爬验证后面, 只能用个人令牌登录
     * (见 `BangumiOAuthManager.loginWithPersonalToken`). 自建地址不算: 那是用户自己的反代, 授权登录照常.
     */
    val viaThirdPartyMirror: StateFlow<Boolean> = combine(settings, activeMirror) { settings, active ->
        settings.mode == BangumiEndpointMode.MIRROR || (settings.mode == BangumiEndpointMode.AUTO && active != null)
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)
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
     * 这些地址随数据存进本地, 请求时会先换回原站再按当前设置走 (见 [BangumiRouting.knownMirrors]).
     *
     * 清单里必须写**跳转之后**的域名: 跨域名跳转时 Ktor 会去掉 `Authorization` 头, 写旧域名的话
     * 匿名浏览照常 (跟着跳过去), 带凭证的请求却在新域名上收到 401.
     */
    val BUNDLED = listOf("bangumi.vip")
}
