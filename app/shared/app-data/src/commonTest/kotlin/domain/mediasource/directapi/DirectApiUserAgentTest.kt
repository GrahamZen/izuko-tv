/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.directapi

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.foundation.DeviceBrowserUserAgentHolder
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.UserAgentFeatureHandler
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * HTTP client 自带的 UA 是写死的常量, 每台设备一模一样, 站点按它就能认出这个应用的全部用户,
 * 所以这条路要带本机浏览器的真实 UA.
 */
class DirectApiUserAgentTest {
    @AfterTest
    fun reset() {
        DeviceBrowserUserAgentHolder.install { null }
    }

    private suspend fun userAgentSent(config: DirectApiConfig): String? {
        var seen: String? = null
        val engine = MockEngine { request ->
            seen = request.headers[HttpHeaders.UserAgent]
            respond("[]")
        }
        val client = HttpClient(engine)
        // 覆盖 UA 的拦截装在 client 上 (见 UserAgentFeatureHandler.applyToClient), 测试要按同样的方式接线
        UserAgentFeatureHandler.applyToClient(client, ScopedHttpClientUserAgent.BROWSER)
        DirectApiEngine(config, client.asScopedHttpClient()).checkConnection()
        return seen
    }

    private fun config(userAgent: String = "") = DirectApiConfig(
        baseUrl = "https://api.example.com",
        subject = DirectApiConfig.SubjectConfig(
            request = DirectApiConfig.RequestConfig(url = "{baseUrl}/search?keyword={subjectName}"),
        ),
        userAgent = userAgent,
    )

    @Test
    fun `uses the device browser user agent`() = runTest {
        DeviceBrowserUserAgentHolder.install { "Mozilla/5.0 (Linux; Android 11; Device) AppleWebKit/537.36" }

        assertEquals("Mozilla/5.0 (Linux; Android 11; Device) AppleWebKit/537.36", userAgentSent(config()))
    }

    @Test
    fun `config overrides the device user agent`() = runTest {
        DeviceBrowserUserAgentHolder.install { "Mozilla/5.0 (Linux; Android 11; Device) AppleWebKit/537.36" }

        assertEquals("Configured/1.0", userAgentSent(config(userAgent = "Configured/1.0")))
    }

    @Test
    fun `falls back to the client default when the device ua is unavailable`() = runTest {
        // 例如电视上没有可用的 WebView
        DeviceBrowserUserAgentHolder.install { null }

        assertNull(userAgentSent(config()))
    }
}
