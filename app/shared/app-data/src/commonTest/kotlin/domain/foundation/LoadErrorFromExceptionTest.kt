/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 没经过仓库层包装的网络层异常也按同一套规则归类, 不显示成「未知错误」加原始报错. */
class LoadErrorFromExceptionTest {
    private suspend fun thrownBy(status: HttpStatusCode): Throwable {
        val client = HttpClient(MockEngine { respond("", status) }) { expectSuccess = true }
        return runCatching { client.get("https://next.bgm.tv/p1/subjects/1") }.exceptionOrNull()!!
    }

    @Test
    fun `服务端 5xx - 服务暂不可用`() = runTest {
        assertEquals(LoadError.ServiceUnavailable, LoadError.fromException(thrownBy(HttpStatusCode.BadGateway)))
    }

    @Test
    fun `401 - 要登录`() = runTest {
        assertEquals(LoadError.RequiresLogin, LoadError.fromException(thrownBy(HttpStatusCode.Unauthorized)))
    }

    @Test
    fun `429 - 太频繁`() = runTest {
        assertEquals(LoadError.RateLimited, LoadError.fromException(thrownBy(HttpStatusCode.TooManyRequests)))
    }

    @Test
    fun `连不上 - 网络错误`() {
        assertEquals(LoadError.NetworkError, LoadError.fromException(IOException("All bangumi endpoints failed")))
    }

    @Test
    fun `别的 4xx 与普通异常仍是未知错误，带原始异常`() = runTest {
        val notFound = thrownBy(HttpStatusCode.NotFound)
        assertEquals(notFound, assertIs<LoadError.UnknownError>(LoadError.fromException(notFound)).throwable)
        val other = IllegalStateException("boom")
        assertEquals(other, assertIs<LoadError.UnknownError>(LoadError.fromException(other)).throwable)
    }
}
