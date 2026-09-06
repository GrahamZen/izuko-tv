/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * GitHub 连不上、走镜像回落时, 镜像说的版本要确认确实发布了才提示 ([UpdateChecker.checkLatestVersion]):
 * jsDelivr 按 tag 解析, 推了 tag 还没发布的草稿、发布后删掉的 release 都会被它当成最新版, 它们的安装包到处都是 404.
 */
class UpdateCheckerMirrorPackageTest {
    private val mirror = "https://mirror.test"
    private val apkHead = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /**
     * GitHub 接口不通, jsDelivr 说最新版是 [jsDelivrVersion]; 安装包请求交给 [packageResponse] (参数是版本与请求地址),
     * ghfast 的 releases/latest 交给 [ghfastLatestResponse].
     */
    private fun checker(
        jsDelivrVersion: String,
        ghfastLatestResponse: MockRequestHandleScope.() -> HttpResponseData = { respondError(HttpStatusCode.ServiceUnavailable) },
        packageResponse: MockRequestHandleScope.(version: String, url: String) -> HttpResponseData,
    ): UpdateChecker {
        val engine = MockEngine { request: HttpRequestData ->
            val url = request.url.toString()
            val packageVersion = PACKAGE_VERSION.find(url)?.groupValues?.get(1)
            when {
                request.url.host == "api.github.com" -> respondError(HttpStatusCode.ServiceUnavailable)
                request.url.host in JSDELIVR_HOSTS ->
                    respond("notes", HttpStatusCode.OK, headersOf("x-jsd-version", "v$jsDelivrVersion"))

                packageVersion != null -> packageResponse(packageVersion, url)
                url.endsWith("/releases/latest") -> ghfastLatestResponse()
                // ghfast 跳转落地的 tag 页, 与经 ghfast 取的更新说明模板
                else -> respond("notes")
            }
        }
        return UpdateChecker(
            HttpClient(engine) { install(HttpTimeout) }.asScopedHttpClient(),
            downloadSources = { listOf(it, "$mirror/$it") },
        )
    }

    private suspend fun UpdateChecker.check() = checkLatestVersion(ReleaseClass.STABLE, currentVersion = "1.0.0")

    @Test
    fun `a version whose packages are not found is not offered`() = runTest {
        val version = checker("1.0.5") { _, _ -> respondError(HttpStatusCode.NotFound) }.check()
        assertNull(version)
    }

    @Test
    fun `a version is offered once any source serves its package`() = runTest {
        val version = checker("1.0.5") { _, url ->
            // GitHub 原地址不通, 下载镜像能下
            if (url.startsWith(mirror)) respond(apkHead, HttpStatusCode.PartialContent)
            else respondError(HttpStatusCode.ServiceUnavailable)
        }.check()
        assertEquals("1.0.5", version?.name)
    }

    @Test
    fun `a page instead of the package does not count as serving it`() = runTest {
        val version = checker("1.0.5") { _, url ->
            if (url.startsWith(mirror)) respond("<html>blocked</html>")
            else respondError(HttpStatusCode.NotFound)
        }.check()
        assertNull(version)
    }

    @Test
    fun `a version is still offered when no source gives an answer`() = runTest {
        // 看不出发没发布 (连不上、出错): 照样提示, 下载时再说清每条线路的原因
        val version = checker("1.0.5") { _, url ->
            if (url.startsWith(mirror)) respond("<html>blocked</html>")
            else respondError(HttpStatusCode.ServiceUnavailable)
        }.check()
        assertEquals("1.0.5", version?.name)
    }

    @Test
    fun `falls back to the published latest release when jsDelivr points at an unpublished one`() = runTest {
        val steps = mutableListOf<UpdateCheckProgress>()
        val version = checker(
            "1.0.5",
            ghfastLatestResponse = {
                val tagPage = ghfastUrl("https://github.com/$FORK_OWNER/$FORK_REPO/releases/tag/v1.0.4")
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, tagPage))
            },
        ) { packageVersion, _ ->
            if (packageVersion == "1.0.4") respond(apkHead, HttpStatusCode.PartialContent)
            else respondError(HttpStatusCode.NotFound)
        }.checkLatestVersion(ReleaseClass.STABLE, currentVersion = "1.0.0", onProgress = { steps += it })
        assertEquals("1.0.4", version?.name)
        val mirrors = UPDATE_CHECK_MIRROR_COUNT
        assertEquals(
            listOf(UpdateCheckProgress.GitHub, UpdateCheckProgress.Mirror(1, mirrors), UpdateCheckProgress.Mirror(mirrors, mirrors)),
            steps,
        )
    }

    @Test
    fun `no update when the published latest release is not newer`() = runTest {
        val version = checker(
            "1.0.5",
            ghfastLatestResponse = {
                val tagPage = ghfastUrl("https://github.com/$FORK_OWNER/$FORK_REPO/releases/tag/v1.0.0")
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, tagPage))
            },
        ) { packageVersion, _ ->
            if (packageVersion == "1.0.0") respond(apkHead, HttpStatusCode.PartialContent)
            else respondError(HttpStatusCode.NotFound)
        }.check()
        assertNull(version)
    }

    private companion object {
        private val PACKAGE_VERSION = Regex("""/releases/download/v([^/]+)/""")
    }
}
