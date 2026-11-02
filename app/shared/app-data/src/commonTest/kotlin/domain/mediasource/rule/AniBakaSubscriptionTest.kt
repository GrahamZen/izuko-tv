/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscriptionRequesterImpl
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscriptionUpdater.Companion.calculateDiff
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscriptionUpdater.ExistingArgument
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscriptionUpdater.RemoteEntry
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AniBakaSubscriptionTest {
    private val hubUrl = "https://rules.example.com/repo/index.json"

    private val hub = """
        {"format": "anx-rulehub/2", "entries": [
          {"key": "ok", "title": "能转的", "ref": "ok.json"},
          {"key": "special", "title": "专用步骤站", "ref": "special.json"}
        ]}
    """.trimIndent()

    private val okRule = """
        {"format": "anx-rule/2", "name": "示例站", "baseUrl": "https://www.example.com",
         "search": [{"op": "fetch", "url": "/s?wd={keyword}"}, {"op": "searchList", "selectors": [".item"]}],
         "detail": [{"op": "follow"}, {"op": "episodes", "listSelectors": [".eps"]}],
         "play": [{"op": "sniff", "goal": "video"}]}
    """.trimIndent()

    private val specialRule = """
        {"format": "anx-rule/2", "name": "专用步骤站", "baseUrl": "https://www.example.com",
         "search": [{"op": "fetch", "url": "/s?wd={keyword}"}, {"op": "searchList", "selectors": [".item"]}],
         "detail": [{"op": "follow"}, {"op": "episodes", "listSelectors": [".eps"]}],
         "play": [{"op": "follow"}, {"op": "ecPlayer"}]}
    """.trimIndent()

    private val files = mapOf("https://rules.example.com/repo/ok.json" to okRule, "https://rules.example.com/repo/special.json" to specialRule)

    @Test
    fun `hub becomes subscription data of rule sources`() = runTest {
        val data = checkNotNull(AniBakaSubscription.decodeOrNull(hub, hubUrl) { files.getValue(it) })
        val codecs = MediaSourceCodecManager()
        // 转换不了的规则不算失败, 记成要新版才认得的数据源, 顺序照规则库
        val remotes = data.mediaSources.map { RemoteEntry(it, runCatching { codecs.decode(it) }.getOrNull()) }
        assertEquals(listOf("示例站", "专用步骤站"), remotes.map { it.name })
        assertEquals("示例站", remotes[0].arguments?.name)
        assertNull(remotes[1].arguments)
    }

    @Test
    fun `unsupported rule keeps the source imported before`() = runTest {
        val data = checkNotNull(AniBakaSubscription.decodeOrNull(hub, hubUrl) { files.getValue(it) })
        val codecs = MediaSourceCodecManager()
        val remotes = data.mediaSources.map { RemoteEntry(it, runCatching { codecs.decode(it) }.getOrNull()) }
        fun local(name: String) = ExistingArgument(
            MediaSourceSave(
                instanceId = "local-$name",
                mediaSourceId = "local-$name",
                factoryId = RuleMediaSource.FactoryId,
                isEnabled = false,
                config = MediaSourceConfig(subscriptionId = "s"),
            ),
            RuleMediaSourceArguments(name = name),
        )
        // 上次能转、这次规则改了写法转不了: 本地那个不删, 用户关掉它的设置也还在
        val diff = calculateDiff(remotes, listOf(local("示例站"), local("专用步骤站")))
        assertEquals(emptyList(), diff.removed)
        assertEquals(
            listOf("示例站" to "local-示例站", "专用步骤站" to "local-专用步骤站"),
            diff.matched.map { (remote, existing) -> remote.name to existing?.save?.instanceId },
        )
        // 本地没有的不新建
        assertEquals(listOf("示例站"), calculateDiff(remotes, emptyList()).matched.map { it.first.name })
    }

    @Test
    fun `missing rule file fails the whole update`() = runTest {
        // 缺了的规则若被当成「订阅里删掉了」, 用户对它的启停设置会在下次加回来时丢失
        assertFailsWith<RepositoryNetworkException> {
            AniBakaSubscription.decodeOrNull(hub, hubUrl) { url ->
                if (url.endsWith("ok.json")) okRule else error("timeout")
            }
        }
    }

    @Test
    fun `ordinary subscription content is left alone`() = runTest {
        assertNull(AniBakaSubscription.decodeOrNull("""{"exportedMediaSourceDataList": {"mediaSources": []}}""", hubUrl) { error("no") })
    }

    @Test
    fun `rule files resolve next to a mirrored hub address`() {
        assertEquals(
            "https://mirror.example.com/https://raw.githubusercontent.com/u/r/main/ok.json",
            AniBakaRuleImporter.ruleFileUrl("https://mirror.example.com/https://raw.githubusercontent.com/u/r/main/index.json", "ok.json"),
        )
    }

    @Test
    fun `subscription requester converts a hub address`() = runTest {
        val engine = MockEngine { request ->
            val body = when (request.url.toString()) {
                hubUrl -> hub
                "https://rules.example.com/repo/ok.json" -> okRule
                "https://rules.example.com/repo/special.json" -> specialRule
                else -> return@MockEngine respondError(HttpStatusCode.NotFound)
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val requester = MediaSourceSubscriptionRequesterImpl(HttpClient(engine).asScopedHttpClient())
        val data = requester.request(MediaSourceSubscription(subscriptionId = "s", url = hubUrl))
        assertEquals(2, data.mediaSources.size)
    }
}
