/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together.mqtt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MqttLinkTest {
    private val broker = FakeMqttBroker()
    private val received = mutableListOf<String>()
    private var willText: String? = null

    private fun newLink() = MqttLink(
        url = "wss://broker.test/mqtt",
        topic = "room",
        connector = broker.connector,
        will = { willText?.encodeToByteArray() },
        onMessage = { received += it.decodeToString() },
        keepAlive = 30.seconds,
        random = Random(1),
    )

    private fun TestScope.start(link: MqttLink): Job = backgroundScope.launch { link.run() }.also { runCurrent() }

    private fun publishedTexts() = broker.published.map { (topic, payload) -> topic to payload.decodeToString() }

    @Test
    fun `connects subscribes and receives only its own topic`() = runTest {
        val link = newLink()
        start(link)
        assertEquals(MqttLinkState.CONNECTED, link.state.value)
        val session = broker.liveSessions.single()
        assertEquals(setOf("room"), session.subscriptions)
        assertEquals(30, session.keepAliveSeconds)
        assertEquals(16, session.clientId?.length)

        broker.inject("room", "hello".encodeToByteArray())
        broker.inject("elsewhere", "noise".encodeToByteArray())
        runCurrent()
        assertEquals(listOf("hello"), received)
    }

    @Test
    fun `publish reaches the broker when connected and reports false when not`() = runTest {
        val link = newLink()
        assertFalse(link.publish("early".encodeToByteArray()))
        start(link)
        assertTrue(link.publish("now".encodeToByteArray()))
        assertEquals(listOf("room" to "now"), publishedTexts())

        broker.accepting = false
        broker.killAll()
        runCurrent()
        assertFalse(link.publish("late".encodeToByteArray()))
    }

    @Test
    fun `reconnects after the connection is lost`() = runTest {
        val link = newLink()
        start(link)
        broker.killAll()
        runCurrent()
        assertEquals(MqttLinkState.WAITING, link.state.value)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(MqttLinkState.CONNECTED, link.state.value)
        assertEquals(2, broker.connectAttempts)

        broker.inject("room", "after".encodeToByteArray())
        runCurrent()
        assertEquals(listOf("after"), received)
    }

    @Test
    fun `retries slow down while the broker keeps refusing`() = runTest {
        broker.accepting = false
        start(newLink())
        // 间隔 1、2、4、8、16、30、30… 秒 (各加减两成): 两分钟里连不了几次
        advanceTimeBy(120.seconds)
        runCurrent()
        assertTrue(broker.connectAttempts in 6..10, "两分钟里连了 ${broker.connectAttempts} 次")
    }

    @Test
    fun `a connection dropped before its first ping answer still counts as a failure`() = runTest {
        broker.closeAfterHandshake = true
        start(newLink())
        advanceTimeBy(120.seconds)
        runCurrent()
        assertTrue(broker.connectAttempts in 6..10, "两分钟里连了 ${broker.connectAttempts} 次")
    }

    @Test
    fun `a refused connack or suback is not a connection`() = runTest {
        broker.connAckReturnCode = 5
        val link = newLink()
        start(link)
        assertEquals(MqttLinkState.WAITING, link.state.value)

        broker.connAckReturnCode = 0
        broker.subAckReturnCode = 0x80
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(MqttLinkState.WAITING, link.state.value)
        assertEquals(2, broker.connectAttempts)
    }

    @Test
    fun `pings on the keep alive interval`() = runTest {
        start(newLink())
        advanceTimeBy(100.seconds)
        runCurrent()
        // 第 15、45、75 秒各发一次
        assertEquals(3, broker.liveSessions.single().pingsReceived)
        assertEquals(1, broker.connectAttempts)
    }

    @Test
    fun `reconnects when pings go unanswered`() = runTest {
        val link = newLink()
        start(link)
        broker.answerPings = false
        advanceTimeBy(31.seconds)
        runCurrent()
        // 第 15 秒发的心跳到第 30 秒还没回应: 这条连接不要了
        assertTrue(broker.sessions.first().closed)
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(2, broker.connectAttempts)
        assertEquals(MqttLinkState.CONNECTED, link.state.value)
    }

    @Test
    fun `cancelling leaves cleanly so the broker does not publish the will`() = runTest {
        willText = "gone"
        val job = start(newLink())
        val session = broker.liveSessions.single()
        assertContentEquals("gone".encodeToByteArray(), session.will?.second)
        assertEquals("room", session.will?.first)

        job.cancelAndJoin()
        assertTrue(session.gotDisconnect)
        assertTrue(session.closed)
        assertEquals(emptyList(), publishedTexts())
    }

    @Test
    fun `the broker publishes the will when the connection dies`() = runTest {
        willText = "gone"
        start(newLink())
        broker.accepting = false
        broker.killAll()
        runCurrent()
        assertEquals(listOf("room" to "gone"), publishedTexts())
    }

    @Test
    fun `the will is asked for again on every connection`() = runTest {
        willText = "first"
        start(newLink())
        assertNotNull(broker.sessions[0].will)

        willText = null
        broker.killAll()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(2, broker.sessions.size)
        assertNull(broker.sessions[1].will)
    }

    @Test
    fun `a handshake that never answers times out and is retried`() = runTest {
        val silent = MqttConnector { _ ->
            object : MqttTransport {
                override suspend fun send(bytes: ByteArray) = Unit
                override suspend fun receive(): ByteArray? = awaitCancellation()
                override suspend fun close() = Unit
            }
        }
        var attempts = 0
        val link = MqttLink(
            url = "wss://broker.test/mqtt", topic = "room",
            connector = MqttConnector { url ->
                attempts++
                silent.connect(url)
            },
            will = { null }, onMessage = {}, random = Random(1),
        )
        backgroundScope.launch { link.run() }
        runCurrent()
        assertEquals(MqttLinkState.CONNECTING, link.state.value)
        advanceTimeBy(13.seconds)
        runCurrent()
        // 握手等 10 秒, 再等约 1 秒重连
        assertEquals(2, attempts)
    }
}
