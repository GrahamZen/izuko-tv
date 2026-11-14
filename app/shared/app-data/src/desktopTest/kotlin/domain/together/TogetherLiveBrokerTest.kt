/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.domain.together.mqtt.MqttLink
import me.him188.ani.app.domain.together.mqtt.MqttLinkState
import me.him188.ani.app.domain.together.mqtt.WebSocketMqttConnector
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.ktor.createDefaultHttpClient
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * 连真实的公共代理, 平时不跑 (要外网, 结果随对方服务的状态变)。要跑时设环境变量 `IZUKO_TOGETHER_LIVE=1`:
 * `IZUKO_TOGETHER_LIVE=1 ./scripts/gradle-lock.sh :app:shared:app-data:desktopTest --tests 'me.him188.ani.app.domain.together.TogetherLiveBrokerTest'`
 */
class TogetherLiveBrokerTest {
    private val enabled = System.getenv("IZUKO_TOGETHER_LIVE") == "1"

    private fun newClient() = createDefaultHttpClient(bare = true) { install(WebSockets) }

    /**
     * 当另一台设备的对端用: 设 `IZUKO_TOGETHER_PEER_ROOM=<房间号与密码>` (如 `28689131 482193`) 时进那个房间, 每两秒发一条 `desktop N`, 把收到的打出来,
     * 持续 `IZUKO_TOGETHER_PEER_SECONDS` 秒 (默认 60)。
     */
    @Test
    fun `acts as a peer in a given room`() = runBlocking {
        val room = System.getenv("IZUKO_TOGETHER_PEER_ROOM")?.let { TogetherInvite.parse(it) } ?: return@runBlocking
        val seconds = System.getenv("IZUKO_TOGETHER_PEER_SECONDS")?.toIntOrNull() ?: 60
        val client = newClient()
        try {
            val connection = TogetherChannelFactory(client = { client.asScopedHttpClient() }, brokerUrls = { LIVE_BROKERS })
                .connect(room, this, farewell = { "desktop dropped".encodeToByteArray() })
            val collector = launch {
                connection.channel.incoming.collect { println("[peer] 收到: ${it.decodeToString()}") }
            }
            withTimeout(30.seconds) { connection.channel.links.first { it.online } }
            println("[peer] 进了房间 ${room.number}, 连着 ${connection.channel.links.value}")
            repeat(seconds / 2) { round ->
                val sent = connection.channel.send("desktop $round".encodeToByteArray())
                println("[peer] 发出 desktop $round: $sent, 连着 ${connection.channel.links.value.connected} 家")
                delay(2000)
            }
            collector.cancelAndJoin()
            connection.close()
        } finally {
            client.close()
        }
    }

    @Test
    fun `each listed broker accepts a connection and relays a message`() = runBlocking {
        if (!enabled) return@runBlocking
        val client = newClient()
        try {
            val connector = WebSocketMqttConnector { client.asScopedHttpClient() }
            for (url in LIVE_BROKERS) {
                val topic = buildString { repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) } }
                var echoed: String? = null
                val link = MqttLink(url, topic, connector, will = { null }, onMessage = { echoed = it.decodeToString() })
                val job = launch { link.run() }
                val connectTime = measureTime { withTimeout(30.seconds) { link.state.first { it == MqttLinkState.CONNECTED } } }
                val relayTime = measureTime {
                    link.publish("ping".encodeToByteArray())
                    withTimeout(10.seconds) { while (echoed == null) delay(20) }
                }
                println("[live] $url 连上 ${connectTime.inWholeMilliseconds} ms, 转发 ${relayTime.inWholeMilliseconds} ms")
                assertEquals("ping", echoed)
                job.cancelAndJoin()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `two members of one room exchange messages through the public brokers`() = runBlocking {
        if (!enabled) return@runBlocking
        val clientA = newClient()
        val clientB = newClient()
        try {
            val room = TogetherRoomId(
                buildString { repeat(TOGETHER_ROOM_NUMBER_DIGITS) { append(Random.nextInt(10)) } },
                buildString { repeat(TOGETHER_ROOM_PASSWORD_DIGITS) { append(Random.nextInt(10)) } },
            )
            val key = TogetherRoomKey.derive(room)
            val alice = MqttTogetherChannel(key, LIVE_BROKERS, WebSocketMqttConnector { clientA.asScopedHttpClient() }, farewell = { null })
            val bob = MqttTogetherChannel(key, LIVE_BROKERS, WebSocketMqttConnector { clientB.asScopedHttpClient() }, farewell = { null })
            val jobs = listOf(launch { alice.run() }, launch { bob.run() })
            val bobInbox = mutableListOf<String>()
            val collector = launch { bob.incoming.collect { bobInbox += it.decodeToString() } }

            withTimeout(30.seconds) {
                alice.links.first { it.connected == it.total }
                bob.links.first { it.connected == it.total }
            }
            println("[live] 房间 ${room.number} 两边都连上了 ${alice.links.value.total} 家")
            val relayTime = measureTime {
                alice.send("hello from alice".encodeToByteArray())
                withTimeout(10.seconds) { while (bobInbox.isEmpty()) delay(20) }
            }
            delay(1500) // 等另外两家代理的那两份也到, 确认只算一条
            println("[live] 乙收到 $bobInbox, 第一份 ${relayTime.inWholeMilliseconds} ms")
            assertEquals(listOf("hello from alice"), bobInbox)

            collector.cancelAndJoin()
            jobs.forEach { it.cancelAndJoin() }
        } finally {
            clientA.close()
            clientB.close()
        }
    }
}
