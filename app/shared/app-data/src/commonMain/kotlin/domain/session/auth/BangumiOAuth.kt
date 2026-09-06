/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.domain.session.AccessTokenPair
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * 直连 bangumi 的 OAuth 常量.
 *
 * 回调地址在 bangumi 那边是**注册死的**, 不能按平台变 (实测传别的地址, 用户若未登录, bgm 走完
 * 登录页之后就把回调地址忘了, 直接报错). 所以三端统一用这个自定义 scheme:
 * Android 由 manifest 的 intent-filter 接住; **应用内 WebView 则在导航到它时直接拦下来**
 * —— 后者不经过系统, 桌面端也能用.
 */
object BangumiOAuthConstants {
    const val CALLBACK_URL = "ani://bangumi-oauth-callback"

    private const val AUTHORIZE_URL = "https://bgm.tv/oauth/authorize"
    const val TOKEN_URL = "https://bgm.tv/oauth/access_token"

    /**
     * 授权页地址. [state] 原样回传, 用来防止串号 (同一台设备上先后开两次授权).
     */
    fun authorizeUrl(
        clientId: String = currentAniBuildConfig.bangumiOauthClientId,
        state: String,
        callbackUrl: String = CALLBACK_URL,
    ): String = "$AUTHORIZE_URL" +
            "?client_id=${clientId.encodeURLParameter()}" +
            "&response_type=code" +
            "&state=${state.encodeURLParameter()}" +
            "&redirect_uri=${callbackUrl.encodeURLParameter()}"

    /**
     * 这个地址是不是 OAuth 回调 (WebView 拦截判据). bangumi 会带上 `?code=...&state=...`.
     */
    fun isCallback(url: String): Boolean = url.startsWith(CALLBACK_URL)

    /** 从回调地址里取授权码; 不是回调或没有 code 时返回 `null`. */
    fun extractCode(url: String): String? = extractQueryParam(url, "code")

    /** 从回调地址里取 state (与发起时的对照). */
    fun extractState(url: String): String? = extractQueryParam(url, "state")

    private fun extractQueryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', "").takeIf { it.isNotEmpty() } ?: return null
        return query.split('&')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
    }
}

/**
 * 直连 bangumi 的授权码换 token / 刷新 token.
 *
 * 与被它取代的那个走 Ani 服务器的实现 (轮询 `getResult`) 相比, 这里是标准的 OAuth2:
 * 授权页把 `code` 回调给我们, 我们拿 code + client secret 换 token, **中间没有第三方**。
 *
 * **token 只活 7 天** (bangumi 的 `expires_in` 实测 604800), 所以刷新是必须实现的, 不像
 * Ani 服务器那样能给一个月 —— 见 [me.him188.ani.app.domain.session.SessionManager.Config].
 */
class BangumiOAuthClient(
    private val client: ScopedHttpClient,
    private val clientId: String = currentAniBuildConfig.bangumiOauthClientId,
    private val clientSecret: String = currentAniBuildConfig.bangumiOauthClientSecret,
    private val clock: Clock = Clock.System,
) {
    private val logger = logger<BangumiOAuthClient>()
    private val json = Json { ignoreUnknownKeys = true }

    /** 这个构建带了凭据吗. 没带的话授权页一定报错, 入口处要提前说清楚. */
    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /**
     * 授权码换 token.
     *
     * @throws RepositoryException
     */
    suspend fun exchangeCode(code: String, callbackUrl: String = BangumiOAuthConstants.CALLBACK_URL): OAuthResult =
        request(
            Parameters.build {
                append("grant_type", "authorization_code")
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("code", code)
                append("redirect_uri", callbackUrl)
            },
            what = "exchange code",
        )

    /**
     * 用 refreshToken 换一对新 token.
     *
     * @throws RepositoryException
     */
    suspend fun refresh(refreshToken: String, callbackUrl: String = BangumiOAuthConstants.CALLBACK_URL): OAuthResult =
        request(
            Parameters.build {
                append("grant_type", "refresh_token")
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("refresh_token", refreshToken)
                append("redirect_uri", callbackUrl)
            },
            what = "refresh",
        )

    private suspend fun request(form: Parameters, what: String): OAuthResult {
        val resp = try {
            client.use {
                val response = submitForm(BangumiOAuthConstants.TOKEN_URL, form)
                val text = response.bodyAsText()
                if (!response.status.isSuccess()) {
                    // 失败体是 `{"error":"invalid_grant","error_description":"..."}`, 带上它 ——
                    // 授权码过期 (10 分钟) 与 secret 配错在界面上长得一样, 只有这行能分开
                    error("bangumi oauth $what failed: ${response.status}, body=$text")
                }
                json.decodeFromString(BangumiTokenResponse.serializer(), text)
            }
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
        logger.info {
            "bgm-direct: oauth $what ok, expiresIn=${resp.expiresInSeconds}s"
        }
        return resp.toOAuthResult(clock)
    }
}

@Serializable
private class BangumiTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresInSeconds: Long,
    @SerialName("refresh_token") val refreshToken: String,
    // 刻意不声明 `user_id`: 它在 bgm 的响应里有时是数字有时是字符串, 而我们根本用不上
    // (登录后照样要请求 /p1/me 拿昵称头像), 声明了反而会因为类型不符整个解析失败
)

private fun BangumiTokenResponse.toOAuthResult(clock: Clock): OAuthResult = OAuthResult(
    tokens = AccessTokenPair(
        // 直连之后没有 Ani token 了. 这个字段随 client/ 一起删 (S8), 在那之前留空串:
        // 读它的地方只剩下 Ani 的接口, 而那些接口本来就要没了
        aniAccessToken = "",
        expiresAtMillis = clock.now().plus(expiresInSeconds.seconds).toEpochMilliseconds(),
        bangumiAccessToken = accessToken,
    ),
    expiresInSeconds = expiresInSeconds,
    refreshToken = refreshToken,
)
