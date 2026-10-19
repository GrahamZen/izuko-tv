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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DebugNetworkHooksTest {
    private var sent = 0
    private val client = HttpClient(MockEngine { sent++; respond("{}", HttpStatusCode.OK) }).apply { installDebugNetworkHooks() }

    @AfterTest
    fun reset() {
        DebugNetworkHooks.blockRules = emptyList()
        DebugNetworkHooks.stopRecording()
        client.close()
    }

    @Test
    fun `blocked urls fail without reaching the server`() = runTest {
        DebugNetworkHooks.blockRules = listOf(DebugNetworkHooks.BlockRule("api.bgm.tv"))

        assertFailsWith<IOException> { client.get("https://api.bgm.tv/v0/episodes?subject_id=1") }
        client.get("https://next.bgm.tv/p1/subjects/1")

        assertEquals(1, sent)
    }

    @Test
    fun `recording keeps each request with its status`() = runTest {
        DebugNetworkHooks.startRecording()
        DebugNetworkHooks.blockRules = listOf(DebugNetworkHooks.BlockRule("blocked.example"))

        client.get("https://next.bgm.tv/p1/subjects/1")
        assertFailsWith<IOException> { client.get("https://blocked.example/a") }

        val (since, total, records) = DebugNetworkHooks.snapshot()
        assertEquals(true, since != null)
        assertEquals(2, total)
        assertEquals(listOf("200", "BLOCKED"), records.map { it.status })
    }

    @Test
    fun `nothing is recorded while recording is off`() = runTest {
        val before = DebugNetworkHooks.snapshot().second
        client.get("https://next.bgm.tv/p1/subjects/1")

        assertEquals(before, DebugNetworkHooks.snapshot().second)
    }
}
