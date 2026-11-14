/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TogetherRoomHostTest {
    private var clock = 1_000L
    private val host = TogetherRoomHost(selfId = "host", now = { clock })

    private fun watching(episodeId: Int = 7, positionMillis: Long = 0, paused: Boolean = false) = TogetherWatchingInfo(
        subjectId = 100, episodeId = episodeId, subjectName = "S", episodeSort = "07", episodeName = "E",
        positionMillis = positionMillis, positionAtMillis = clock, durationMillis = 1_440_000, paused = paused,
    )

    private fun report(nickname: String, following: Boolean = true, watching: TogetherWatchingInfo? = null) =
        TogetherSelfReport(nickname = nickname, following = following, watching = watching)

    @Test
    fun `opening a room lists only the host`() {
        val snapshot = host.open(report("Host"))
        // 版本号从建房时刻起步
        assertEquals(1_000, snapshot.version)
        assertEquals(TogetherRoomStatus.OPEN, snapshot.status)
        assertEquals("host", snapshot.hostId)
        assertEquals(1_000, snapshot.roomTime)
        val member = snapshot.members.single()
        assertTrue(member.isHost)
        assertEquals(TogetherMemberState.IDLE, member.state)
        assertNull(snapshot.playback)
    }

    @Test
    fun `a hello adds the member and bumps the version`() {
        host.open(report("Host"))
        clock = 2_000
        val snapshot = host.onHello("bob", report("Bob", following = false))
        assertEquals(1_001, snapshot.version)
        assertEquals(listOf("host", "bob"), snapshot.members.map { it.memberId })
        val bob = snapshot.members[1]
        assertFalse(bob.isHost)
        assertFalse(bob.following)
        assertEquals(2_000, bob.lastSeenAt)
    }

    @Test
    fun `the playback of the room is what the host is watching`() {
        host.open(report("Host"))
        host.onHello("bob", report("Bob"))
        clock = 5_000

        // 成员在看什么不算房间的播放状态
        val afterMember = assertNotNull(host.onReport("bob", report("Bob", watching = watching(episodeId = 9))))
        assertNull(afterMember.playback)
        assertEquals(TogetherMemberState.WATCHING, afterMember.members[1].state)

        clock = 6_000
        val afterHost = assertNotNull(host.onReport("host", report("Host", watching = watching(positionMillis = 30_000))))
        val playback = assertNotNull(afterHost.playback)
        assertEquals(7, playback.info.episodeId)
        assertEquals(30_000, playback.info.positionMillis)

        // 房主退出播放页
        clock = 7_000
        assertNull(assertNotNull(host.onReport("host", report("Host"))).playback)
    }

    @Test
    fun `reporting the same state again changes nothing`() {
        host.open(report("Host"))
        val state = report("Bob", watching = watching())
        host.onHello("bob", state)
        val version = host.snapshot().version
        clock = 9_000
        assertNull(host.onReport("bob", state))
        assertEquals(version, host.snapshot().version)
        // 但记下了他还在
        assertEquals(9_000, host.snapshot().members[1].lastSeenAt)
    }

    @Test
    fun `a report from someone who never said hello is taken in`() {
        host.open(report("Host"))
        val snapshot = assertNotNull(host.onReport("carol", report("Carol")))
        assertEquals(listOf("host", "carol"), snapshot.members.map { it.memberId })
    }

    @Test
    fun `leaving removes the member`() {
        host.open(report("Host"))
        host.onHello("bob", report("Bob"))
        val snapshot = assertNotNull(host.onLeft("bob"))
        assertEquals(listOf("host"), snapshot.members.map { it.memberId })
        assertNull(host.onLeft("bob"))
        assertNull(host.onLeft("host"))
    }

    @Test
    fun `a dropped member stays listed as disconnected until they come back`() {
        host.open(report("Host"))
        host.onHello("bob", report("Bob", watching = watching()))
        val dropped = assertNotNull(host.onDropped("bob"))
        assertEquals(TogetherMemberState.DISCONNECTED, dropped.members[1].state)
        assertNull(host.onDropped("bob"))
        assertNull(host.onDropped("host"))
        assertNull(host.onDropped("nobody"))

        val back = host.onHello("bob", report("Bob"))
        assertEquals(TogetherMemberState.IDLE, back.members[1].state)
    }

    @Test
    fun `members are pruned a while after they were marked disconnected`() {
        host.open(report("Host"))
        host.onHello("bob", report("Bob"))
        host.onHello("carol", report("Carol"))
        // 两人都安静地看了很久, 最后一条消息是很久以前的; 甲先掉线
        clock = 1_000_000
        host.onDropped("bob")
        clock = 1_010_000
        host.onDropped("carol")
        assertNull(host.pruneDisconnected(olderThanMillis = 60_000))

        clock = 1_065_000
        // 甲掉线 65 秒, 丙 55 秒
        val snapshot = assertNotNull(host.pruneDisconnected(olderThanMillis = 60_000))
        assertEquals(listOf("host", "carol"), snapshot.members.map { it.memberId })
        assertNull(host.pruneDisconnected(olderThanMillis = 60_000))
    }

    @Test
    fun `reopening the room continues above every version sent before`() {
        host.open(report("Host"))
        repeat(50) { host.onHello("bob", report("Bob")) }
        val last = host.snapshot().version
        // 房主的应用重开, 隔了一会儿重新建房
        clock = 5_000
        assertTrue(host.open(report("Host")).version > last)
    }

    @Test
    fun `closing marks the room closed`() {
        host.open(report("Host"))
        val snapshot = host.close()
        assertEquals(TogetherRoomStatus.CLOSED, snapshot.status)
        assertEquals(1_001, snapshot.version)
    }

    @Test
    fun `messages survive the codec and unknown ones are dropped`() {
        host.open(report("Host", watching = watching(positionMillis = 12_345)))
        val messages = listOf(
            TogetherMessage.Hello("bob", report("Bob 中文"), sentAt = 42),
            TogetherMessage.Welcome("host", to = "bob", echoSentAt = 42, snapshot = host.snapshot()),
            TogetherMessage.Report("bob", report("Bob", following = false, watching = watching())),
            TogetherMessage.Snapshot("host", host.snapshot()),
            TogetherMessage.Bye("bob", dropped = true),
            TogetherMessage.Handover("host", to = "carol"),
            TogetherMessage.Ping("host", to = "bob"),
        )
        for (message in messages) {
            assertEquals(message, TogetherMessageCodec.decode(TogetherMessageCodec.encode(message)))
        }
        assertIs<TogetherMessage.Bye>(TogetherMessageCodec.decode("""{"t":"bye","sender":"x","later":1}""".encodeToByteArray()))
        assertNull(TogetherMessageCodec.decode("""{"t":"from-a-newer-version","sender":"x"}""".encodeToByteArray()))
        assertNull(TogetherMessageCodec.decode("not json".encodeToByteArray()))
        assertNull(TogetherMessageCodec.decode(ByteArray(0)))
    }

    @Test
    fun `position is extrapolated only while actually playing`() {
        val playing = watching(positionMillis = 10_000).copy(positionAtMillis = 1_000, playbackRate = 2f)
        assertEquals(10_000, playing.positionAt(1_000))
        assertEquals(16_000, playing.positionAt(4_000))
        // 时刻早于采样: 不往回推
        assertEquals(10_000, playing.positionAt(500))
        // 不超过片长
        assertEquals(1_440_000, playing.positionAt(10_000_000))

        assertEquals(10_000, playing.copy(paused = true).positionAt(4_000))
        assertEquals(10_000, playing.copy(buffering = true).positionAt(4_000))
        assertEquals(10_000, playing.copy(loading = true).positionAt(4_000))
    }

    @Test
    fun `the room clock is the midpoint of the round trip`() {
        var local = 0L
        val roomClock = TogetherRoomClock(localNowMillis = { local })
        assertEquals(0, roomClock.currentOffsetMillis())

        // 本机 1000 发出, 1200 收到, 房主那时是 50_100: 中点 1100 ⇒ 差 49_000
        assertTrue(roomClock.recordSample(roomTimeMillis = 50_100, sentAtMillis = 1_000, receivedAtMillis = 1_200))
        assertEquals(49_000, roomClock.currentOffsetMillis())
        local = 2_000
        assertEquals(51_000, roomClock.now())

        // 来回太久的那次不准, 已经有差值时不用
        assertFalse(roomClock.recordSample(roomTimeMillis = 90_000, sentAtMillis = 3_000, receivedAtMillis = 9_000))
        assertFalse(roomClock.recordSample(roomTimeMillis = 90_000, sentAtMillis = 3_000, receivedAtMillis = 2_000))
        assertEquals(49_000, roomClock.currentOffsetMillis())

        assertTrue(roomClock.recordSample(roomTimeMillis = 52_050, sentAtMillis = 3_000, receivedAtMillis = 3_100))
        assertEquals(49_000, roomClock.currentOffsetMillis())
    }

    @Test
    fun `the room clock keeps the fastest round trip of a round`() {
        val roomClock = TogetherRoomClock(localNowMillis = { 0L })
        // 来回 800 毫秒 (去程慢、回程快): 取中点偏了 300 毫秒
        assertTrue(roomClock.recordSample(roomTimeMillis = 10_700, sentAtMillis = 0, receivedAtMillis = 800))
        assertEquals(10_300, roomClock.currentOffsetMillis())
        // 来回 100 毫秒的那次误差小, 换成它
        assertTrue(roomClock.recordSample(roomTimeMillis = 11_050, sentAtMillis = 1_000, receivedAtMillis = 1_100))
        assertEquals(10_000, roomClock.currentOffsetMillis())
        // 来回 300 毫秒的不如它, 不换
        assertFalse(roomClock.recordSample(roomTimeMillis = 12_400, sentAtMillis = 2_000, receivedAtMillis = 2_300))
        assertEquals(10_000, roomClock.currentOffsetMillis())

        // 新一轮: 第一次直接用 (时钟可能走偏了), 之后照样只认更快的
        roomClock.startRound()
        assertTrue(roomClock.recordSample(roomTimeMillis = 13_250, sentAtMillis = 3_000, receivedAtMillis = 3_300))
        assertEquals(10_100, roomClock.currentOffsetMillis())
        assertFalse(roomClock.recordSample(roomTimeMillis = 14_500, sentAtMillis = 4_000, receivedAtMillis = 4_400))
        assertEquals(10_100, roomClock.currentOffsetMillis())
    }

    @Test
    fun `a slow first round trip is still better than no clock at all`() {
        val roomClock = TogetherRoomClock(localNowMillis = { 0L })
        assertTrue(roomClock.recordSample(roomTimeMillis = 10_000, sentAtMillis = 0, receivedAtMillis = 6_000))
        assertEquals(7_000, roomClock.currentOffsetMillis())
    }
}
