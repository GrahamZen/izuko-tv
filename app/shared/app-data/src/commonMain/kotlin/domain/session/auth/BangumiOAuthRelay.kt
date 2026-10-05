/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.ktor.ScopedHttpClient

/**
 * 手机授权、电视登录, 不用复制粘贴回调地址: bgm 把 code 回调给 Cloudflare Worker ([CALLBACK_URL]), Worker 把手机浏览器
 * 跳回电视 Web 控制台的 [RETURN_PATH], 电视在那里拿 code 换 token.
 *
 * bgm 的回调地址按应用注册死 (见 [BangumiOAuthConstants]), 主应用注册的是电视本机的回环地址, 所以这条路用另一个 bgm 应用
 * (凭据 `ani.bangumi.oauth.relay.client.*`, 回调注册成 [CALLBACK_URL]).
 *
 * - **Worker 什么都不存**: 电视的局域网地址放在 `state` 里 ([state]), Worker 只认内网 IPv4 ([isPrivateLanHost]), 302 过去.
 *   code 换 token 要 secret, 只有电视做得了.
 * - **手机要能连上电视** (与打开控制台同一条路). 连不上时手机停在一个打不开的页面, 那个地址照旧能粘回控制台 ([isRelayUrl]).
 * - **会话只能由同一个应用续期**: refresh token 只认发它的应用, 所以存进会话的 refresh token 带 [REFRESH_TOKEN_PREFIX],
 *   续期时据此选应用 (见 `BangumiSessionRefresher`).
 */
object BangumiOAuthRelay {
    /** 与 bgm 应用设置里注册的回调逐字一致. */
    const val CALLBACK_URL = "https://bangumi-tmdb-map-report.grahamzen.workers.dev/bgm/callback"

    /** Worker 跳回电视时落在控制台服务的这个路径 (不带 token: 跳转地址里只有 state 带过去的局域网地址). */
    const val RETURN_PATH = "bgm-oauth"

    private const val REFRESH_TOKEN_PREFIX = "relay:"
    private const val STATE_SEPARATOR = '~'

    /** 授权的 `state`: 随机串 + 电视的局域网地址 (`IPv4:端口`, 手机打开控制台用的那个). Worker 按后半段跳回电视. */
    fun state(nonce: String, lanHost: String): String = "$nonce$STATE_SEPARATOR$lanHost"

    /** 存着的 refresh token 是不是中转应用发的. */
    fun isRelayRefreshToken(stored: String): Boolean = stored.startsWith(REFRESH_TOKEN_PREFIX)

    internal fun encodeRefreshToken(token: String): String = REFRESH_TOKEN_PREFIX + token

    internal fun decodeRefreshToken(stored: String): String = stored.removePrefix(REFRESH_TOKEN_PREFIX)

    /**
     * 粘回来的地址是不是这条路上的: Worker 的回调 (Worker 没跳成), 或跳回电视的那一跳 (手机连不上电视).
     * 与 [BangumiOAuthConstants.isCallback] 一样只看路径; 是不是这一次发起的由 state 判.
     */
    fun isRelayUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return path == CALLBACK_URL || (path.startsWith("http://") && path.endsWith("/$RETURN_PATH"))
    }

    /**
     * [host] (`IPv4:端口`) 能不能放进 state 让 Worker 跳过去: 只认内网 IPv4 —— 10/8、172.16/12、192.168/16, 以及
     * 100.64/10 (Tailscale 之类). Worker 那边按同一张表判, 别的地址 (公网、域名、IPv6) 走粘贴回调地址那条.
     */
    fun isPrivateLanHost(host: String): Boolean {
        val match = HOST_REGEX.matchEntire(host) ?: return false
        val octets = match.groupValues.subList(1, 5).map { it.toInt() }
        val port = match.groupValues[5].toInt()
        if (octets.any { it > 255 } || port !in 1..65535) return false
        val (a, b) = octets
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }

    private val HOST_REGEX = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})""")
}

/**
 * 中转应用 ([BangumiOAuthRelay]) 的授权码换 token 与续期. 换出来的 refresh token 带上中转应用的前缀, 直接存进会话.
 */
class BangumiOAuthRelayClient(
    httpClient: ScopedHttpClient,
    private val clientId: String = currentAniBuildConfig.bangumiOauthRelayClientId,
    clientSecret: String = currentAniBuildConfig.bangumiOauthRelayClientSecret,
) {
    private val client = BangumiOAuthClient(httpClient, clientId, clientSecret)

    /** 这个构建带了中转应用的凭据吗; 没带就只能粘贴回调地址. */
    val isConfigured: Boolean get() = client.isConfigured

    /** @param mirrorRoot 同 [BangumiOAuthConstants.authorizeUrl], 只能是可信镜像 */
    fun authorizeUrl(state: String, mirrorRoot: String?): String =
        BangumiOAuthConstants.authorizeUrl(clientId, state, BangumiOAuthRelay.CALLBACK_URL, mirrorRoot)

    /** @throws RepositoryException */
    suspend fun exchangeCode(code: String): OAuthResult =
        client.exchangeCode(code, BangumiOAuthRelay.CALLBACK_URL).withRelayRefreshToken()

    /**
     * @param stored 会话里存着的、带前缀的 refresh token
     * @throws RepositoryException
     */
    suspend fun refresh(stored: String): OAuthResult =
        client.refresh(BangumiOAuthRelay.decodeRefreshToken(stored), BangumiOAuthRelay.CALLBACK_URL)
            .withRelayRefreshToken()

    private fun OAuthResult.withRelayRefreshToken(): OAuthResult =
        copy(refreshToken = BangumiOAuthRelay.encodeRefreshToken(refreshToken))
}
