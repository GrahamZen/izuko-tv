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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.domain.foundation.BangumiRoute
import me.him188.ani.app.domain.foundation.BangumiRouting
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 电视替用户登录 bgm 网页并生成个人令牌: 线路选择、cookie 自己管、登录被拒、生成令牌. 对面是按 bgm 实际行为写的假网站. */
class BangumiWebLoginTest {
    /**
     * 假的 bgm. 按真站的行为: 验证码与会话 cookie 绑定、提交一次就作废; 登录成功下发 `chii_auth`;
     * next 子域的 `/demo/access-tokens` 认 `chii_auth`, 回令牌字符串 (JSON).
     */
    private class FakeBangumi {
        var password = "pw"

        /** 这个主机连不上. */
        var unreachable: (host: String) -> Boolean = { false }

        /** 生成令牌被拒 (回 401). */
        var tokenRefused = false

        val hosts = mutableListOf<String>()
        val loginPosts = mutableListOf<Map<String, String>>()
        val tokenRequests = mutableListOf<Pair<String, String>>()

        /** 账号的令牌列表 (编号 → 名字); 生成的令牌值是 `tok<编号>`. */
        val tokens = linkedMapOf<Long, String>()
        private var nextTokenId = 100L
        private var captchaNo = 0
        private var currentCaptcha: String? = null

        val client = HttpClient(MockEngine) {
            followRedirects = false
            install(HttpTimeout)
            engine { addHandler { handle(it) } }
        }

        private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
            val host = request.url.host
            hosts += host
            if (unreachable(host)) throw IOException("blocked: $host")
            val cookies = request.headers[HttpHeaders.Cookie].orEmpty().split("; ").filter { it.isNotEmpty() }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val path = request.url.encodedPath
            return when {
                path == "/login" && request.method == HttpMethod.Get ->
                    html("""<form><input type="hidden" name="formhash" value="h1"></form>""", "chii_sid=sid1; path=/; domain=.bgm.tv")

                path == "/signup/captcha" -> {
                    if (cookies["chii_sid"] != "sid1") return respond("", HttpStatusCode.Forbidden)
                    currentCaptcha = "c${++captchaNo}"
                    respond(
                        byteArrayOf(0x47, 0x49, 0x46, captchaNo.toByte()),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType to listOf("image/gif"), HttpHeaders.SetCookie to listOf("chii_sec=sec$captchaNo; path=/")),
                    )
                }

                path == "/FollowTheRabbit" -> {
                    val form = form(request).parseUrlEncodedParameters().entries().associate { it.key to it.value.first() }
                    loginPosts += form
                    val captchaOk = cookies["chii_sec"] != null && form["captcha_challenge_field"] == currentCaptcha
                    currentCaptcha = null
                    when {
                        form["formhash"] != "h1" -> html("""<p class="text">非法请求</p>""")
                        !captchaOk -> html("""<div><p class="text">验证码错误，请返回重试。</p></div>""", "chii_sec=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/")
                        form["password"] != password -> html("""<p class="text">用户名或密码错误</p>""", "chii_sec=deleted; Max-Age=0; path=/")
                        else -> html("<html>welcome</html>", "chii_auth=auth1; path=/; domain=.bgm.tv; httponly")
                    }
                }

                path.startsWith("/demo/") && !loggedIn(request, cookies) -> respond(
                    """{"statusCode":401,"code":"NEED_LOGIN","error":"Unauthorized","message":"you need to login before delete your token"}""",
                    HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )

                path == "/demo/access-tokens" && request.method == HttpMethod.Post -> {
                    tokenRequests += host to form(request)
                    if (tokenRefused) return respond("""{"message":"refused"}""", HttpStatusCode.Forbidden)
                    val id = nextTokenId++
                    tokens[id] = Json.parseToJsonElement(form(request)).jsonObject["name"]!!.jsonPrimitive.content
                    respond("\"tok$id\"", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                path == "/demo/access-tokens" && request.method == HttpMethod.Delete -> {
                    tokens.remove(Json.parseToJsonElement(form(request)).jsonObject["id"]!!.jsonPrimitive.content.toLong())
                    respond("", HttpStatusCode.OK)
                }

                // 同官网的列表页: 一条一块, 名字在 fw-bold 里
                path == "/demo/access-token" -> html(
                    tokens.entries.reversed().joinToString("\n") { (id, name) ->
                        "<li class='list-group-item' id='token-$id'><div><div class='fw-bold'>${name.replace("&", "&amp;")}</div>" +
                            "<small>2026-10-15</small></div><button class='btn btn-danger' onclick=\"revoke('$id')\">delete</button></li>"
                    },
                )

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        /** 主站登录的 cookie, 或这个账号的某个令牌. */
        private fun loggedIn(request: HttpRequestData, cookies: Map<String, String>): Boolean {
            if (cookies["chii_auth"] == "auth1") return true
            val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ") ?: return false
            return bearer.removePrefix("tok").toLongOrNull() in tokens
        }

        private fun form(request: HttpRequestData): String =
            (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

        private fun MockRequestHandleScope.html(body: String, setCookie: String? = null) = respond(
            body,
            HttpStatusCode.OK,
            headersOf(
                *listOfNotNull(
                    HttpHeaders.ContentType to listOf("text/html; charset=utf-8"),
                    setCookie?.let { HttpHeaders.SetCookie to listOf(it) },
                ).toTypedArray(),
            ),
        )
    }

    private fun login(
        site: FakeBangumi,
        vararg routes: BangumiRoute,
        recover: suspend (BangumiRoute, Throwable) -> Boolean = { _, _ -> false },
        tokenName: String = BangumiWebLogin.TOKEN_NAME,
        currentToken: String? = null,
        scope: CoroutineScope? = null,
    ) = BangumiWebLogin(
        newClient = { site.client },
        routing = { BangumiRouting(routes.toList()) },
        recover = recover,
        tokenName = tokenName,
        currentToken = { currentToken },
        scope = scope,
    )

    private val token = BangumiWebLogin.Result.Token("tok100", BangumiWebLogin.TOKEN_DAYS)

    @Test
    fun `登录并生成令牌`() = runTest {
        val site = FakeBangumi()
        val login = login(site, BangumiRoute.Origin)
        val captcha = login.start()
        assertContentEquals(byteArrayOf(0x47, 0x49, 0x46, 1), captcha.bytes)
        assertEquals("image/gif", captcha.contentType)

        assertEquals(token, login.submit("a@b.c", "pw", "c1"))

        assertEquals("登录", site.loginPosts.single()["loginsubmit"])
        val (host, body) = site.tokenRequests.single()
        assertEquals("next.bgm.tv", host)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(BangumiWebLogin.TOKEN_NAME, json["name"]?.jsonPrimitive?.content)
        assertEquals(365, json["days"]?.jsonPrimitive?.int)
        // 拿到令牌后这一次就结束了
        assertFailsWith<BangumiWebLogin.NotStartedException> { login.newCaptcha() }
    }

    @Test
    fun `生成令牌被拒 — 带回接口的原话`() = runTest {
        val site = FakeBangumi().apply { tokenRefused = true }
        val login = login(site, BangumiRoute.Origin)
        login.start()
        assertEquals(BangumiWebLogin.Result.TokenFailed("refused"), login.submit("a@b.c", "pw", "c1"))
    }

    @Test
    fun `重新登录 — 先撤掉这台电视以前的令牌, 别的不动`() = runTest {
        val site = FakeBangumi().apply {
            tokens[1] = "Izuko TV · SHIELD ab12"
            tokens[2] = "Izuko TV · BRAVIA cd34"
            tokens[3] = "我自己的脚本"
            tokens[4] = "Izuko TV · SHIELD ab12"
        }
        val login = login(site, BangumiRoute.Origin, tokenName = "Izuko TV · SHIELD ab12")
        login.start()
        assertEquals(token, login.submit("a@b.c", "pw", "c1"))
        assertEquals(mapOf(2L to "Izuko TV · BRAVIA cd34", 3L to "我自己的脚本", 100L to "Izuko TV · SHIELD ab12"), site.tokens)
    }

    @Test
    fun `退出登录 — 用当前令牌撤掉这台电视的令牌`() = runTest {
        val site = FakeBangumi().apply {
            tokens[100] = "Izuko TV · SHIELD ab12"
            tokens[2] = "Izuko TV · BRAVIA cd34"
        }
        val login = login(site, BangumiRoute.Origin, tokenName = "Izuko TV · SHIELD ab12", currentToken = "tok100", scope = this)
        login.revokeOnLogout()!!.join()
        assertEquals(mapOf(2L to "Izuko TV · BRAVIA cd34"), site.tokens)
    }

    @Test
    fun `退出登录 — 没登录就什么都不做`() = runTest {
        assertNull(login(FakeBangumi(), BangumiRoute.Origin, scope = this).revokeOnLogout())
    }

    @Test
    fun `令牌列表页`() {
        val html = "<li class='list-group-item' id='token-14217163'><div class='fw-bold'>Izuko TV · A&amp;B</div>" +
            "<button onclick=\"revoke('14217163')\">delete</button></li><li id='token-9'><div class=\"fw-bold\">x</div></li>"
        assertEquals(listOf(14217163L to "Izuko TV · A&B", 9L to "x"), BangumiWebLogin.parseTokenList(html))
    }

    @Test
    fun `验证码错 — 带回网站的原话, 换一张再登`() = runTest {
        val site = FakeBangumi()
        val login = login(site, BangumiRoute.Origin)
        login.start()

        assertEquals(BangumiWebLogin.Result.Rejected("验证码错误，请返回重试。"), login.submit("a@b.c", "pw", "wrong"))
        // 提交过的验证码作废了: 原样再交一次还是错
        assertIs<BangumiWebLogin.Result.Rejected>(login.submit("a@b.c", "pw", "c1"))
        assertTrue(site.tokenRequests.isEmpty())

        val next = login.newCaptcha()
        assertEquals(2, next.bytes.last().toInt())
        assertEquals(token, login.submit("a@b.c", "pw", "c2"))
    }

    @Test
    fun `密码错`() = runTest {
        val site = FakeBangumi()
        val login = login(site, BangumiRoute.Origin)
        login.start()
        assertEquals(BangumiWebLogin.Result.Rejected("用户名或密码错误"), login.submit("a@b.c", "nope", "c1"))
    }

    @Test
    fun `官方连不上 — 换加密直连的域名, 令牌也在加密直连的 next 子域上生成`() = runTest {
        val site = FakeBangumi().apply { unreachable = { it == "bgm.tv" } }
        val login = login(site, BangumiRoute.Origin, BangumiRoute.Ech)
        login.start()
        assertEquals(token, login.submit("a@b.c", "pw", "c1"))
        assertEquals("bgm.tv", site.hosts.first())
        assertTrue(site.hosts.drop(1).all { it.endsWith(BangumiRoute.Ech.HOST_ROOT) })
        assertEquals("next.${BangumiRoute.Ech.HOST_ROOT}", site.tokenRequests.single().first)
    }

    @Test
    fun `加密直连握手被拒 — 换配置再试一次`() = runTest {
        var blocked = true
        val site = FakeBangumi().apply { unreachable = { blocked } }
        val login = login(site, BangumiRoute.Ech, recover = { route, _ -> blocked = false; route == BangumiRoute.Ech })
        login.start()
        assertEquals(listOf(BangumiRoute.Ech.HOST_ROOT, BangumiRoute.Ech.HOST_ROOT), site.hosts.take(2))
    }

    @Test
    fun `社区镜像不用 — 只剩它时说没有线路`() = runTest {
        val site = FakeBangumi()
        val mirror = BangumiRoute.Mirror("mirror.example")
        assertFalse(BangumiWebLogin.available(BangumiRouting(listOf(mirror))))
        assertFailsWith<BangumiWebLogin.NoRouteException> { login(site, mirror).start() }
        assertTrue(site.hosts.isEmpty())

        val custom = BangumiRoute.Custom("my.example")
        assertTrue(BangumiWebLogin.available(BangumiRouting(listOf(mirror, custom))))
        login(site, mirror, custom).start()
        assertTrue(site.hosts.all { it == "my.example" })
    }

    @Test
    fun `都连不上`() = runTest {
        val site = FakeBangumi().apply { unreachable = { true } }
        assertFailsWith<IOException> { login(site, BangumiRoute.Origin, BangumiRoute.Ech).start() }
    }

    @Test
    fun `没开始就提交`() = runTest {
        assertFailsWith<BangumiWebLogin.NotStartedException> {
            login(FakeBangumi(), BangumiRoute.Origin).submit("a@b.c", "pw", "c1")
        }
    }

    @Test
    fun `出错页的那句话`() {
        assertEquals("验证码错误，请返回重试。", BangumiWebLogin.pageMessage("""<p class="text">验证码错误，请返回重试。</p>"""))
        assertEquals("15 分钟内您将不能登录本站", BangumiWebLogin.pageMessage("<p class=\"text\">\n  15 分钟内您将<b>不能</b>登录本站\n</p>"))
        assertEquals(null, BangumiWebLogin.pageMessage("<html></html>"))
        assertEquals("abc", BangumiWebLogin.formhash("""<input type="hidden" name="formhash" value="abc" />"""))
    }
}
