/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.app.domain.mediasource.subscription.SubscriptionMetadata
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [DanmakuServerAdmin]: 应用里管弹幕服务器的那几步, 规则同 Web 控制台。服务器一律回 404 (没有版本接口也算连得上)。
 * 要测连接的几条用真实时间跑: 虚拟时间下请求还在别的线程上走, 连接超时会被直接跳过去。
 */
class DanmakuServerAdminTest {
    private val dir = SystemPaths.createTempDirectory("danmaku-server-admin-test")
    private val store = DanmakuServerStore(dir.resolve("servers.json"))
    private val subscriptions = MutableStateFlow(
        listOf(
            MediaSourceSubscription(
                subscriptionId = "sub",
                url = "https://subs.example.org/list.json",
                metadata = SubscriptionMetadata(name = "订阅 A"),
                danmakuServers = listOf(DanmakuServerEntry("来自订阅", "https://dm.example.org")),
            ),
        ),
    )
    private val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }).asScopedHttpClient()
    private val admin = DanmakuServerAdmin(DanmakuServerRegistry(store, subscriptions) { client }, store, subscriptions)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `lists subscribed servers with the subscription name`() = runTest {
        val server = admin.list().single()
        assertEquals("dm.example.org", server.host)
        assertEquals("订阅 A", server.from)
        assertFalse(server.manual)
    }

    @Test
    fun `adds a server and tests it once`() = runBlocking {
        val result = admin.add("自建", "https://danmaku.example.net/")

        assertEquals(DanmakuServerAdmin.AddResult.Added(DanmakuServerAdmin.Probe.Ok), result)
        val added = admin.list().single { it.manual }
        assertEquals("自建", added.name)
        assertTrue(added.enabled)
    }

    @Test
    fun `rejects bad and duplicate addresses`() = runBlocking {
        assertEquals(DanmakuServerAdmin.AddResult.InvalidUrl, admin.add("", "danmaku.example.net"))
        assertIs<DanmakuServerAdmin.AddResult.Added>(admin.add("", "https://danmaku.example.net"))
        assertEquals(DanmakuServerAdmin.AddResult.Duplicate, admin.add("again", "https://danmaku.example.net"))
    }

    @Test
    fun `subscribed servers can be turned off but not deleted`() = runTest {
        val url = admin.list().single().url

        assertFalse(admin.delete(url))
        admin.setEnabled(url, false)
        assertFalse(admin.list().single().enabled)
        admin.setEnabled(url, true)
        assertTrue(admin.list().single().enabled)
    }

    @Test
    fun `deletes a manual server`() = runBlocking {
        admin.add("自建", "https://danmaku.example.net")
        val url = admin.list().single { it.manual }.url

        assertTrue(admin.delete(url))
        assertEquals(listOf(false), admin.list().map { it.manual })
    }
}
