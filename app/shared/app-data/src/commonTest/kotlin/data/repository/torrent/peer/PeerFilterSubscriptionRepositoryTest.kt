/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.torrent.peer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.torrent.peer.PeerFilterSubscription
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 内置 Peer 过滤规则: 从本项目仓库的 `peer-filter-rules.json` 拉 (jsDelivr 各节点依次试), 拉不到用本机存的上一份.
 */
class PeerFilterSubscriptionRepositoryTest {
    private val dir: SystemPath = SystemPaths.createTempDirectory("peer-filter-test")

    @AfterTest
    fun cleanup() = dir.deleteRecursively()

    private val rules = """{"_comment": "说明", "blockedIpPattern": ["1.2.3.4", "5.6.7.0/24"], "blockedIdRegex": ["\\-XL.+\\-"], "blockedClientRegex": []}"""
    private val oldRules = """{"blockedIpPattern": ["9.9.9.9"], "blockedIdRegex": [], "blockedClientRegex": []}"""
    private val savedFile get() = dir.resolve("${PeerFilterSubscription.BUILTIN_SUBSCRIPTION_ID}.json")

    /** @param bodies 按地址给响应体; 返回 `null` = 连不上 */
    private fun repository(requested: MutableList<String> = mutableListOf(), bodies: (String) -> String?) =
        PeerFilterSubscriptionRepository(
            MemoryDataStore(PeerFilterSubscriptionsSaveData.Default),
            dir,
            HttpClient(
                MockEngine { request ->
                    val url = request.url.toString()
                    requested += url
                    respond(bodies(url) ?: throw IOException("blocked"), HttpStatusCode.OK)
                },
            ).asScopedHttpClient(),
            repository = { "owner/repo" },
        )

    private suspend fun PeerFilterSubscriptionRepository.builtin() =
        presentationFlow.first().single { it.subscriptionId == PeerFilterSubscription.BUILTIN_SUBSCRIPTION_ID }

    @Test
    fun `拉到规则就生效，并存到本地`() = runTest {
        val requested = mutableListOf<String>()
        val repo = repository(requested) { rules }
        repo.updateOrLoadAll()
        assertEquals(listOf("https://testingcf.jsdelivr.net/gh/owner/repo@main/peer-filter-rules.json"), requested)
        val loaded = repo.rulesFlow.first().single()
        assertEquals(listOf("1.2.3.4", "5.6.7.0/24"), loaded.blockedIpPattern)
        assertEquals(2, repo.builtin().lastLoaded?.ruleStat?.ipRuleCount)
        assertNull(repo.builtin().lastLoaded?.error)
        assertEquals(rules, savedFile.readText())
    }

    @Test
    fun `前面的入口不通或给的不是规则，试下一个`() = runTest {
        val requested = mutableListOf<String>()
        val repo = repository(requested) { url ->
            when {
                "testingcf" in url -> null
                "gcore" in url -> "<html>404</html>"
                else -> rules
            }
        }
        repo.updateOrLoadAll()
        assertEquals(3, requested.size)
        assertEquals(2, repo.rulesFlow.first().single().blockedIpPattern.size)
    }

    @Test
    fun `都拉不到 — 用本地存的上一份`() = runTest {
        savedFile.writeText(oldRules)
        val repo = repository { null }
        repo.updateOrLoadAll()
        assertEquals(listOf("9.9.9.9"), repo.rulesFlow.first().single().blockedIpPattern)
        assertNotNull(repo.builtin().lastLoaded?.ruleStat)
    }

    @Test
    fun `拿到的不是规则 — 不覆盖本地那份`() = runTest {
        savedFile.writeText(oldRules)
        val repo = repository { "<html>error</html>" }
        repo.updateOrLoadAll()
        assertEquals(oldRules, savedFile.readText())
        assertEquals(listOf("9.9.9.9"), repo.rulesFlow.first().single().blockedIpPattern)
    }
}
