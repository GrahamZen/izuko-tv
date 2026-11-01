/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.BrowserUserAgent
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.app.data.models.preference.ProxyConfig
import me.him188.ani.app.data.network.setCookieHeaders
import me.him188.ani.app.domain.foundation.BangumiEchTransport
import me.him188.ani.app.domain.foundation.BangumiLoginPath
import me.him188.ani.app.domain.foundation.BangumiRoute
import me.him188.ani.app.domain.foundation.BangumiRouting
import me.him188.ani.app.domain.media.fetch.toClientProxyConfig
import me.him188.ani.utils.ktor.engineFallbackDns
import me.him188.ani.utils.ktor.getPlatformKtorEngine
import me.him188.ani.utils.ktor.proxy
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * 在电视上替用户登录 Bangumi 网页并生成个人令牌 ([BangumiLoginPath.TV_WEB_LOGIN]): 用户在 Web 控制台填邮箱、密码与验证码,
 * 电视经它连得上的线路登录, 再像官网「个人令牌」页那样生成一个 [TOKEN_DAYS] 天的令牌, 交给
 * [BangumiOAuthManager.loginWithPersonalToken]. 个人令牌不用续期, 之后只剩镜像能连时也照样能用.
 *
 * 令牌名带这台电视的标识 ([tokenName]): 生成之前先撤掉同名的旧令牌 (重装、重新登录留下的), 退出登录时撤掉自己的
 * ([revokeOnLogout]), 用户的令牌列表里不会越积越多. 撤销后 bgm 服务端最长还会认它一天 (认证结果缓存 24 小时).
 *
 * **只走代理得了主站、又不经第三方的线路** (官方 / 自建 / 加密直连, 见 [BangumiRoute.servesMainSite] 与 [BangumiRoute.thirdParty]):
 * 账号密码只经过手机到电视这段局域网, 再直达 bgm. 一条都没有 (只开着社区镜像) 时这条路不可用.
 *
 * 用的是 bgm 网站本身的表单: 登录页的 `formhash` 与图片验证码 (`/signup/captcha`, 与会话 cookie 绑定, 提交一次就作废),
 * `/FollowTheRabbit` 登录成功时下发 `chii_auth`. 生成令牌的接口 (next 子域 `/demo/access-tokens`) 认这个 cookie,
 * 但它与登录时的 User-Agent 绑在一起: 登录与生成必须用同一个客户端. 网站改版时这里跟着改.
 *
 * cookie 与跳转自己管 ([newClient] 不跟跳转、不存 cookie): 共用客户端按请求的域名存 cookie, 加密直连改写过的域名
 * 对不上 bgm 下发的 `.bgm.tv`.
 */
class BangumiWebLogin(
    /** 每次开始新建一个: 不跟跳转、不存 cookie、能连加密直连的域名 (见 [createBangumiWebLoginClient]). */
    private val newClient: suspend () -> HttpClient,
    /** 当前的线路 (见 `BangumiEndpointProvider.routing`). */
    private val routing: suspend () -> BangumiRouting,
    /** 某条线路连接失败时有没有补救 (加密直连的配置过期了就换新的), 与 `BangumiRoutingFeatureHandler` 的同名参数一样. */
    private val recover: suspend (route: BangumiRoute, cause: Throwable) -> Boolean = { _, _ -> false },
    /** 生成的令牌在 Bangumi「个人令牌」列表里的名字, 每台电视一个 (`Izuko TV · 型号 设备号`). */
    val tokenName: String = TOKEN_NAME,
    /** 当前会话的令牌 (退出登录时用它撤掉自己的令牌); 没登录为 `null`. */
    private val currentToken: suspend () -> String? = { null },
    /** 退出登录时在后台撤销令牌, 不让退出等网络. */
    private val scope: CoroutineScope? = null,
    private val random: Random = Random.Default,
) {
    private val logger = logger<BangumiWebLogin>()

    /** 验证码图片. */
    class Captcha(val bytes: ByteArray, val contentType: String)

    sealed interface Result {
        /** 登录并生成了令牌, [validDays] 天后过期. */
        data class Token(val token: String, val validDays: Int) : Result

        /**
         * bgm 拒绝了 (密码或验证码不对、错太多次被暂时锁住…). [message] 是 bgm 页面上的原话, 没取到时为 `null`.
         * 验证码提交一次就作废, 再试要换一张 ([newCaptcha]).
         */
        data class Rejected(val message: String?) : Result

        /** 登录成功了, 生成令牌没成. [message] 是接口回的原话, 没取到时为 `null`. */
        data class TokenFailed(val message: String?) : Result
    }

    /** 现在没有能直达 Bangumi 主站、又不经第三方的线路 (只开着社区镜像). */
    class NoRouteException : IllegalStateException("No route reaches the Bangumi main site without a third party")

    /** 还没 [start] 就提交 / 换验证码 (或那一次已经结束). */
    class NotStartedException : IllegalStateException("Bangumi web login was not started")

    /** 一次登录: 选好的线路与网站下发的 cookie. */
    private class Session(val client: HttpClient, val route: BangumiRoute) {
        val base = "https://" + checkNotNull(route.hostFor(BangumiMirrorHosts.ORIGIN_ROOT))
        val nextBase = "https://" + checkNotNull(route.hostFor("next.${BangumiMirrorHosts.ORIGIN_ROOT}"))
        val cookies = linkedMapOf<String, String>()
        var formhash: String? = null
    }

    private val lock = Mutex()
    private var session: Session? = null

    /**
     * 开始一次 (之前那次作废): 按顺序找一条打得开登录页的线路, 取一张验证码.
     *
     * @throws NoRouteException 没有能走的线路
     * @throws IOException 都连不上
     */
    suspend fun start(): Captcha = lock.withLock {
        closeSession()
        val routes = candidates(routing())
        if (routes.isEmpty()) throw NoRouteException()
        val client = newClient()
        var lastError: Throwable? = null
        for ((index, route) in routes.withIndex()) {
            val candidate = Session(client, route)
            try {
                candidate.openLoginPage(probe = index < routes.size - 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                logger.info { "bgm-direct: 网页登录在 ${route.label} 上打不开登录页 (${e::class.simpleName}), 试下一条" }
                continue
            }
            session = candidate
            logger.info { "bgm-direct: 网页登录走 ${route.label}" }
            return@withLock candidate.captcha()
        }
        client.close()
        throw IOException("Bangumi login page is unreachable on every route", lastError)
    }

    /**
     * 换一张验证码 (看不清, 或上一次提交之后它已作废). 重新打开登录页: `formhash` 与验证码要配同一次会话.
     *
     * @throws NotStartedException
     */
    suspend fun newCaptcha(): Captcha = lock.withLock {
        val current = session ?: throw NotStartedException()
        current.openLoginPage(probe = false)
        current.captcha()
    }

    /**
     * 提交登录; 成功则接着生成令牌.
     *
     * @throws NotStartedException
     */
    suspend fun submit(email: String, password: String, captcha: String): Result = lock.withLock {
        val current = session ?: throw NotStartedException()
        val response = current.client.submitForm(
            current.base + "/FollowTheRabbit",
            Parameters.build {
                append("formhash", current.formhash.orEmpty())
                append("referer", "")
                append("dreferer", "")
                append("email", email)
                append("password", password)
                append("captcha_challenge_field", captcha)
                append("loginsubmit", "登录")
            },
        ) {
            cookieHeader(current)
            header(HttpHeaders.Referrer, "https://${BangumiMirrorHosts.ORIGIN_ROOT}/login")
        }
        current.absorbCookies(response)
        if (LOGIN_COOKIE !in current.cookies) {
            val message = pageMessage(response.bodyAsText())
            logger.info { "bgm-direct: 网页登录被拒: $message" }
            return@withLock Result.Rejected(message)
        }
        logger.info { "bgm-direct: 网页登录成功, 生成个人令牌" }
        // 先撤掉这台电视以前生成的 (重装、重新登录留下的); 撤不掉不耽误这次登录
        try {
            current.revokeNamed { cookieHeader(current) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info { "bgm-direct: 撤销旧令牌失败 (${e::class.simpleName}), 照常生成" }
        }
        val result = current.createToken()
        if (result is Result.Token) closeSession()
        result
    }

    /**
     * 退出登录前调用: 在后台撤掉这台电视生成的令牌 (按 [tokenName] 找, 用当前会话的令牌认证). 调用方不等结果, 撤不掉就算了
     * (一年后自己过期).
     *
     * @return 后台的那个任务; 没登录时为 `null`
     */
    suspend fun revokeOnLogout(): Job? {
        val token = currentToken() ?: return null
        val scope = scope ?: return null
        return scope.launch {
            try {
                val routes = candidates(routing())
                if (routes.isEmpty()) return@launch
                val client = newClient()
                try {
                    for (route in routes) {
                        try {
                            Session(client, route).revokeNamed { header(HttpHeaders.Authorization, "Bearer $token") }
                            return@launch
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.info { "bgm-direct: 退出登录时在 ${route.label} 上撤销令牌失败 (${e::class.simpleName}), 试下一条" }
                        }
                    }
                } finally {
                    client.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.info { "bgm-direct: 退出登录时撤销令牌失败: ${e::class.simpleName}" }
            }
        }
    }

    /** 放弃这一次. */
    suspend fun cancel() {
        lock.withLock { closeSession() }
    }

    private fun closeSession() {
        session?.client?.close()
        session = null
    }

    /** 打开登录页, 记下 `formhash` 与 cookie. 加密直连握手被拒 (配置过期) 时换新配置再试一次. */
    private suspend fun Session.openLoginPage(probe: Boolean) {
        val response = try {
            fetch("/login", probe)
        } catch (e: IOException) {
            if (!recover(route, e)) throw e
            fetch("/login", probe)
        }
        val hash = formhash(response.bodyAsText())
            ?: throw IOException("Bangumi login page has no formhash (HTTP ${response.status.value})")
        formhash = hash
    }

    private suspend fun Session.captcha(): Captcha {
        // 地址末尾的数字只是防缓存, 与网站自己的写法一样
        val response = fetch("/signup/captcha?${random.nextLong(1_000_000_000_000L, 9_999_999_999_999L)}", probe = false)
        val bytes = response.readRawBytes()
        if (response.status.value != 200 || bytes.isEmpty()) {
            throw IOException("Bangumi captcha failed (HTTP ${response.status.value})")
        }
        return Captcha(bytes, response.contentType()?.toString() ?: "image/gif")
    }

    /** 登录之后生成个人令牌, 同官网「个人令牌」页; 回应体是 JSON 字符串. */
    private suspend fun Session.createToken(): Result {
        val response = client.post("$nextBase/demo/access-tokens") {
            cookieHeader(this@createToken)
            val body = buildJsonObject {
                put("name", tokenName)
                put("days", TOKEN_DAYS)
            }
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        val text = response.bodyAsText()
        val token = text.trim().removeSurrounding("\"")
        if (response.status.value != 200 || !TOKEN.matches(token)) {
            logger.info { "bgm-direct: 生成个人令牌失败 (HTTP ${response.status.value})" }
            return Result.TokenFailed(ERROR_MESSAGE.find(text)?.groupValues?.get(1))
        }
        return Result.Token(token, TOKEN_DAYS)
    }

    /**
     * 撤掉名为 [tokenName] 的令牌 (列表页 `/demo/access-token` 上找, 逐个 `DELETE`). 登录态由 [auth] 加到请求上.
     * 列表读不出来 (没登录会被 302 到登录页) 就什么都不撤.
     */
    private suspend fun Session.revokeNamed(auth: HttpRequestBuilder.() -> Unit) {
        val page = client.get("$nextBase/demo/access-token") { auth() }
        if (page.status.value != 200) return
        val ids = parseTokenList(page.bodyAsText()).filter { it.second == tokenName }.map { it.first }
        for (id in ids) {
            val response = client.delete("$nextBase/demo/access-tokens") {
                auth()
                setBody(TextContent(buildJsonObject { put("id", id) }.toString(), ContentType.Application.Json))
            }
            logger.info { "bgm-direct: 撤销这台电视的旧令牌 $id: HTTP ${response.status.value}" }
        }
    }

    private suspend fun Session.fetch(path: String, probe: Boolean): HttpResponse =
        client.get(base + path) {
            cookieHeader(this@fetch)
            // 还不知道这条线路通不通、后面又还有线路可退: 缩短超时, 与 BangumiRoutingFeatureHandler 试线路时一样
            if (probe) {
                timeout {
                    connectTimeoutMillis = PROBE_CONNECT_MILLIS
                    socketTimeoutMillis = PROBE_SOCKET_MILLIS
                }
            }
        }.also { absorbCookies(it) }

    private fun HttpRequestBuilder.cookieHeader(session: Session) {
        if (session.cookies.isNotEmpty()) {
            header(HttpHeaders.Cookie, session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
    }

    /** 记下 `Set-Cookie`; 网站用 `deleted` / `Max-Age=0` 删 cookie. */
    private fun Session.absorbCookies(response: HttpResponse) {
        for (header in response.setCookieHeaders()) {
            val parts = header.split(';')
            val name = parts.first().substringBefore('=').trim()
            val value = parts.first().substringAfter('=', "").trim()
            if (name.isEmpty()) continue
            val expired = parts.drop(1).any { it.trim().equals("Max-Age=0", ignoreCase = true) }
            if (value.isEmpty() || value == "deleted" || expired) cookies.remove(name) else cookies[name] = value
        }
    }

    companion object {
        /** 生成的令牌在 Bangumi「个人令牌」列表里的名字, 后面接这台电视的标识 (见 [tokenName]). */
        const val TOKEN_NAME = "Izuko TV"

        /** 生成的令牌的有效期 (天). */
        const val TOKEN_DAYS = 365

        /** [routing] 下有没有这条路能走的线路. */
        fun available(routing: BangumiRouting): Boolean = candidates(routing).isNotEmpty()

        private fun candidates(routing: BangumiRouting): List<BangumiRoute> =
            routing.routes.filter { it.servesMainSite && !it.thirdParty }

        /** 登录成功时网站下发的 cookie. */
        internal const val LOGIN_COOKIE = "chii_auth"

        private const val PROBE_CONNECT_MILLIS = 5_000L
        private const val PROBE_SOCKET_MILLIS = 8_000L

        private val FORMHASH = Regex("""name="formhash"\s+value="([^"]+)"""")

        /** 出错页上的那句话 (`<p class="text">验证码错误，请返回重试。</p>`). */
        private val MESSAGE = Regex("""<p class="text"[^>]*>([\s\S]*?)</p>""")

        private val TOKEN = Regex("[A-Za-z0-9]+")

        private val TOKEN_ITEM_NAME = Regex("""fw-bold['"]>([^<]*)<""")

        /** 接口报错时回应体里的 `"message":"…"`. */
        private val ERROR_MESSAGE = Regex(""""message"\s*:\s*"([^"]*)"""")

        internal fun formhash(html: String): String? = FORMHASH.find(html)?.groupValues?.get(1)

        /** 令牌列表页上的 (编号, 名字): 每条是 `id='token-<编号>'` 开头的一块, 名字在其中的 `fw-bold` 里. */
        internal fun parseTokenList(html: String): List<Pair<Long, String>> =
            html.split("id='token-").drop(1).mapNotNull { block ->
                val id = block.takeWhile { it.isDigit() }.toLongOrNull() ?: return@mapNotNull null
                val name = TOKEN_ITEM_NAME.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
                id to unescapeHtml(name.trim())
            }

        private fun unescapeHtml(text: String): String =
            text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&amp;", "&")

        internal fun pageMessage(html: String): String? =
            MESSAGE.find(html)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("&nbsp;", " ")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }
}

/**
 * [BangumiWebLogin] 用的客户端: 不跟跳转、不存 cookie, 浏览器的 User-Agent (登录的是网页), 代理与共用客户端一样,
 * 装上 [ech] 才连得了加密直连的域名.
 */
fun createBangumiWebLoginClient(proxy: ProxyConfig?, ech: BangumiEchTransport?): HttpClient =
    HttpClient(getPlatformKtorEngine()) {
        followRedirects = false
        expectSuccess = false
        BrowserUserAgent()
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        engineFallbackDns()
        proxy(proxy?.toClientProxyConfig())
        ech?.install(this)
    }
