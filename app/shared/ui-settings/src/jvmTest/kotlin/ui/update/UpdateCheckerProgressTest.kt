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
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * 检查更新时逐个来源报进度 ([UpdateCheckProgress]): 设置页与 Web 控制台据此写「GitHub 连不上，正在查镜像 2/4」.
 */
class UpdateCheckerProgressTest {
    private fun checker(handler: MockRequestHandler) = UpdateChecker(
        HttpClient(MockEngine(handler)) { install(HttpTimeout) }.asScopedHttpClient(),
        downloadSources = { listOf(it) },
    )

    private val mirrors = JSDELIVR_HOSTS.size + 1

    @Test
    fun `every mirror is reported after GitHub fails`() = runTest {
        val steps = mutableListOf<UpdateCheckProgress>()
        assertFails {
            checker { respondError(HttpStatusCode.ServiceUnavailable) }
                .checkLatestVersion(ReleaseClass.STABLE, currentVersion = "1.0.0", onProgress = { steps += it })
        }
        assertEquals(
            listOf(UpdateCheckProgress.GitHub) + (1..mirrors).map { UpdateCheckProgress.Mirror(it, mirrors) },
            steps,
        )
        assertEquals(mirrors, UPDATE_CHECK_MIRROR_COUNT)
    }

    @Test
    fun `stops reporting at the mirror that answers`() = runTest {
        val steps = mutableListOf<UpdateCheckProgress>()
        val version = checker { request ->
            if (request.url.host == JSDELIVR_HOSTS.first()) {
                // 镜像上的最新版比当前旧: 查成功, 没有更新
                respond("", HttpStatusCode.OK, headersOf("x-jsd-version", "0.0.1"))
            } else {
                respondError(HttpStatusCode.ServiceUnavailable)
            }
        }.checkLatestVersion(ReleaseClass.STABLE, currentVersion = "1.0.0", onProgress = { steps += it })
        assertNull(version)
        assertEquals(listOf(UpdateCheckProgress.GitHub, UpdateCheckProgress.Mirror(1, mirrors)), steps)
    }
}
