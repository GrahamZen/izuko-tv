/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 回调地址的解析是**手写**的 (自定义 scheme 进不了 Ktor 的 Url 解析那套). 手写解析错了只会在
 * 真机上表现成"授权完回到 app 却说没拿到 code", 而那时用户已经打完账号密码了 —— 这类错误
 * 必须在这里拦住.
 */
class BangumiOAuthConstantsTest {
    @Test
    fun `授权地址带齐必需参数`() {
        val url = BangumiOAuthConstants.authorizeUrl(clientId = "bgm123", state = "abc-def")
        assertTrue(url.startsWith("https://bgm.tv/oauth/authorize?"), url)
        assertTrue("client_id=bgm123" in url, url)
        assertTrue("response_type=code" in url, url)
        assertTrue("state=abc-def" in url, url)
        // 回调地址必须转义: 不转义的话里面的 `/` `:` 会把后面的参数吃掉
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A41890%2Fcallback" in url, url)
    }

    @Test
    fun `认得出回调, 认不出别的`() {
        assertTrue(BangumiOAuthConstants.isCallback("http://127.0.0.1:41890/callback?code=x&state=y"))
        assertTrue(BangumiOAuthConstants.isCallback("http://127.0.0.1:41890/callback"))
        // 迁移到回环地址之前那个仍然要认: 半路换配置时两头都不认最难查
        assertTrue(BangumiOAuthConstants.isCallback("ani://bangumi-oauth-callback?code=x&state=y"))
        assertFalse(BangumiOAuthConstants.isCallback("https://bgm.tv/oauth/authorize?client_id=x"))
        assertFalse(BangumiOAuthConstants.isCallback("ani://subjects/302286"))
        // 别的本机端口不算 —— 端口是与 bgm 注册的那个逐字对上的
        assertFalse(BangumiOAuthConstants.isCallback("http://127.0.0.1:8080/callback?code=x"))
    }

    @Test
    fun `取 code 与 state`() {
        val url = "ani://bangumi-oauth-callback?code=abc123&state=uuid-1"
        assertEquals("abc123", BangumiOAuthConstants.extractCode(url))
        assertEquals("uuid-1", BangumiOAuthConstants.extractState(url))
    }

    @Test
    fun `参数顺序反过来也要认`() {
        val url = "ani://bangumi-oauth-callback?state=uuid-1&code=abc123"
        assertEquals("abc123", BangumiOAuthConstants.extractCode(url))
        assertEquals("uuid-1", BangumiOAuthConstants.extractState(url))
    }

    @Test
    fun `用户拒绝授权时没有 code`() {
        // 拒绝时 bangumi 回的是 error 而不是 code, 此时不能把空串当成 code 拿去换 token
        val url = "ani://bangumi-oauth-callback?error=access_denied&state=uuid-1"
        assertNull(BangumiOAuthConstants.extractCode(url))
        assertEquals("uuid-1", BangumiOAuthConstants.extractState(url))
    }

    @Test
    fun `没有查询串也不能崩`() {
        assertNull(BangumiOAuthConstants.extractCode("ani://bangumi-oauth-callback"))
        assertNull(BangumiOAuthConstants.extractState("ani://bangumi-oauth-callback"))
    }

    @Test
    fun `code 为空串按没有算`() {
        assertNull(BangumiOAuthConstants.extractCode("ani://bangumi-oauth-callback?code=&state=x"))
    }
}
