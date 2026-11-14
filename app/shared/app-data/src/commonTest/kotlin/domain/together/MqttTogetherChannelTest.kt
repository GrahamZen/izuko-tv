/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.together.mqtt.FakeMqttBroker
import me.him188.ani.app.domain.together.mqtt.MqttConnector
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MqttTogetherChannelTest {
    private val brokerA = FakeMqttBroker()
    private val brokerB = FakeMqttBroker()

    /** 按地址把连接分给两家假代理 */
    private val connector = MqttConnector { url ->
        when (url) {
            URL_A -> brokerA.connector.connect(url)
            URL_B -> brokerB.connector.connect(url)
            else -> error("没有这家代理: $url")
        }
    }

    private class Member(val channel: MqttTogetherChannel, val job: Job) {
        val inbox = mutableListOf<String>()
        var rejections = 0
    }

    private suspend fun TestScope.join(
        room: TogetherRoomId,
        urls: List<String> = listOf(URL_A, URL_B),
        farewell: String? = null,
    ): Member {
        val channel = MqttTogetherChannel(
            key = TogetherRoomKey.derive(room),
            brokerUrls = urls,
            connector = connector,
            farewell = { farewell?.encodeToByteArray() },
            random = Random(1),
        )
        val member = Member(channel, backgroundScope.launch { channel.run() })
        backgroundScope.launch { channel.incoming.collect { member.inbox += it.decodeToString() } }
        backgroundScope.launch { channel.rejections.collect { member.rejections++ } }
        runCurrent()
        return member
    }

    private suspend fun Member.say(text: String): Boolean = channel.send(text.encodeToByteArray())

    @Test
    fun `members of one room hear each other and not themselves`() = runTest {
        val alice = join(ROOM)
        val bob = join(ROOM)
        assertEquals(TogetherLinks(connected = 2, total = 2), alice.channel.links.value)

        assertTrue(alice.say("pause"))
        runCurrent()
        assertEquals(listOf("pause"), bob.inbox)
        assertEquals(emptyList(), alice.inbox)

        assertTrue(bob.say("play"))
        runCurrent()
        assertEquals(listOf("play"), alice.inbox)
        assertEquals(listOf("pause"), bob.inbox)
    }

    @Test
    fun `a message sent through two brokers arrives once`() = runTest {
        val alice = join(ROOM)
        val bob = join(ROOM)
        alice.say("seek")
        runCurrent()
        // 两家代理各转了一份
        assertEquals(1, brokerA.published.size)
        assertEquals(1, brokerB.published.size)
        assertEquals(listOf("seek"), bob.inbox)
    }

    @Test
    fun `someone with the wrong password is told so by a member that answers strangers`() = runTest {
        val host = join(ROOM)
        host.channel.answersStrangers = true
        val member = join(ROOM)
        val intruder = join(TogetherRoomId("48271593", "654321"))

        intruder.say("hello")
        runCurrent()
        // 房主回了一张回执 (两家代理各送一次, 只回一次); 成员不回
        assertEquals(1, intruder.rejections)
        assertEquals(emptyList(), host.inbox)
        assertEquals(emptyList(), member.inbox)

        // 回执说的不是自己发的消息: 不算
        host.say("pause")
        runCurrent()
        assertEquals(0, member.rejections)
        assertEquals(listOf("pause"), member.inbox)
    }

    @Test
    fun `nobody answers strangers unless asked to`() = runTest {
        join(ROOM)
        val intruder = join(TogetherRoomId("48271593", "654321"))
        intruder.say("hello")
        runCurrent()
        assertEquals(0, intruder.rejections)
    }

    @Test
    fun `rooms with different numbers are isolated`() = runTest {
        val alice = join(ROOM)
        val stranger = join(TogetherRoomId("11112222", "123456"))
        alice.say("pause")
        runCurrent()
        assertEquals(emptyList(), stranger.inbox)
    }

    @Test
    fun `nothing readable reaches the broker`() = runTest {
        val alice = join(ROOM)
        alice.say("subject 12345 episode 7")
        runCurrent()
        val (topic, payload) = brokerA.published.single()
        assertFalse("48271593" in topic)
        assertFalse("12345" in payload.decodeToString())
    }

    @Test
    fun `one broker being down does not stop the room`() = runTest {
        brokerA.accepting = false
        val alice = join(ROOM)
        val bob = join(ROOM)
        assertEquals(TogetherLinks(connected = 1, total = 2), alice.channel.links.value)
        assertTrue(alice.channel.links.value.online)

        assertTrue(alice.say("pause"))
        runCurrent()
        assertEquals(listOf("pause"), bob.inbox)
    }

    @Test
    fun `members connected through different brokers still meet on the shared one`() = runTest {
        // 甲只连得上 A, 乙两家都连得上
        val alice = join(ROOM, urls = listOf(URL_A))
        val bob = join(ROOM)
        alice.say("from alice")
        bob.say("from bob")
        runCurrent()
        assertEquals(listOf("from alice"), bob.inbox)
        assertEquals(listOf("from bob"), alice.inbox)
    }

    @Test
    fun `send reports false while no broker is connected and recovers after reconnecting`() = runTest {
        brokerA.accepting = false
        brokerB.accepting = false
        val alice = join(ROOM)
        assertFalse(alice.channel.links.value.online)
        assertFalse(alice.say("lost"))

        brokerB.accepting = true
        advanceTimeBy(40.seconds)
        runCurrent()
        assertTrue(alice.channel.links.value.online)
        assertTrue(alice.say("back"))
    }

    @Test
    fun `a dropped connection makes the broker deliver the farewell to the room`() = runTest {
        val alice = join(ROOM, farewell = "alice gone")
        val bob = join(ROOM)

        brokerA.sessions.first().kill()
        runCurrent()
        // 甲在 A 上的连接断了 (B 上还连着): 房间里收到一份
        assertEquals(listOf("alice gone"), bob.inbox)
        assertEquals(emptyList(), alice.inbox)
    }

    @Test
    fun `leaving cleanly delivers no farewell`() = runTest {
        val alice = join(ROOM, farewell = "alice gone")
        val bob = join(ROOM)
        alice.job.cancelAndJoin()
        runCurrent()
        assertEquals(emptyList(), bob.inbox)
        assertTrue(brokerA.sessions.first().gotDisconnect)
        assertTrue(brokerB.sessions.first().gotDisconnect)
    }

    @Test
    fun `garbage and replays on the topic are ignored`() = runTest {
        val alice = join(ROOM)
        val bob = join(ROOM)
        alice.say("pause")
        runCurrent()
        val (topic, envelope) = brokerA.published.single()

        // 旁人把抓到的那条原样再发一遍、发一段乱码、发一条改过的
        brokerA.inject(topic, envelope)
        brokerA.inject(topic, ByteArray(40) { it.toByte() })
        brokerA.inject(topic, envelope.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() })
        runCurrent()
        assertEquals(listOf("pause"), bob.inbox)
        assertEquals(emptyList(), alice.inbox)
    }

    @Test
    fun `a channel needs at least one broker`() = runTest {
        assertFailsWith<IllegalArgumentException> { join(ROOM, urls = emptyList()) }
    }

    private companion object {
        const val URL_A = "wss://a.test/mqtt"
        const val URL_B = "wss://b.test/mqtt"
        val ROOM = TogetherRoomId("48271593", "123456")
    }
}
