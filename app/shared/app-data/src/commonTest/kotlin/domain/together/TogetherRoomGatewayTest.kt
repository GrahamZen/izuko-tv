/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 内存里的房间通道: 有两家代理 (0 与 1), 同一个房间号、连着同一家代理的座位互相收得到; 密码不同的收不到,
 * 对方开着 [TogetherChannel.answersStrangers] 时发的人收到「解不开」。
 * 能模拟断线、只断一家、只连得上其中一家、消息在路上的时间、应用被杀。
 */
internal class FakeTogetherHub : TogetherChannelOpener {
    /** 新开的座位一开始就连不上 */
    var startOffline = false

    /** 新开的座位连着哪几家代理 */
    var startBrokers: Set<Int> = setOf(0, 1)

    /** 招呼在路上丢掉 */
    var dropHellos = false

    /** 房主问「还在吗」的那一声在路上丢掉 */
    var dropPings = false

    /** 消息在路上走多久 */
    var latencyMillis = 0L

    val seats = mutableListOf<Seat>()

    inner class Seat(
        val room: TogetherRoomId,
        private val scope: CoroutineScope,
        private val farewell: () -> ByteArray?,
    ) : TogetherChannel {
        private var brokers: Set<Int> = if (startOffline) emptySet() else startBrokers
        override val links = MutableStateFlow(TogetherLinks(brokers.size, BROKERS))
        override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        override val rejections = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        override var answersStrangers = false
        var closed = false

        /** 这个座位发出过的消息 */
        val sent = mutableListOf<TogetherMessage>()

        override suspend fun send(message: ByteArray): Boolean {
            if (closed || brokers.isEmpty()) return false
            val decoded = TogetherMessageCodec.decode(message)
            decoded?.let { sent += it }
            if (dropHellos && decoded is TogetherMessage.Hello) return true
            if (dropPings && decoded is TogetherMessage.Ping) return true
            deliver(message, via = brokers)
            return true
        }

        /** 经 [via] 这几家代理送给同一个房间号的其他座位 */
        private fun deliver(message: ByteArray, via: Set<Int>) {
            val others = seats.filter { it !== this && it.room.number == room.number }
            if (latencyMillis == 0L) {
                others.forEach { it.arrive(message, via, from = this) }
            } else {
                val latency = latencyMillis
                scope.launch {
                    delay(latency)
                    others.forEach { it.arrive(message, via, from = this@Seat) }
                }
            }
        }

        private fun arrive(message: ByteArray, via: Set<Int>, from: Seat?) {
            if (closed || brokers.none { it in via }) return
            when {
                from == null || from.room == room -> incoming.tryEmit(message)
                // 密码不同: 解不开; 开着回执的回一张给发的人
                answersStrangers -> from.rejections.tryEmit(Unit)
            }
        }

        /** 现在只连着这几家 */
        fun connectTo(vararg connected: Int) {
            brokers = connected.toSet()
            links.value = TogetherLinks(brokers.size, BROKERS)
        }

        /** 几家代理全断了 */
        fun goOffline() = connectTo()

        fun goOnline() = connectTo(0, 1)

        /** 只断了一家: 那家替它发遗嘱, 人其实还连着另一家 */
        fun loseOneLink() {
            connectTo(0)
            farewell()?.let { deliver(it, via = setOf(1)) }
        }

        /** 应用被杀: 收发都没了, 代理替它发遗嘱 */
        fun crash() {
            closed = true
            farewell()?.let { deliver(it, via = brokers) }
        }

        /**
         * 断过网又连上了, 人一直在线; 代理过了一阵才认定那条旧连接死了, 这时把遗嘱发给房间。
         *
         * @param via 发遗嘱的是哪几家代理; 默认是它现在连着的那几家
         */
        fun staleWill(via: Set<Int> = brokers) {
            val will = farewell() ?: return
            seats.filter { it.room.number == room.number }.forEach { it.arrive(will, via, from = this) }
        }
    }

    override suspend fun connect(room: TogetherRoomId, scope: CoroutineScope, farewell: () -> ByteArray?): TogetherConnection {
        val seat = Seat(room, scope, farewell)
        seats += seat
        val job = scope.launch {
            try {
                awaitCancellation()
            } finally {
                seat.closed = true
            }
        }
        return TogetherConnection(seat, job)
    }

    private companion object {
        const val BROKERS = 2
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TogetherRoomGatewayTest {
    private val hub = FakeTogetherHub()

    private class Peer(val room: TogetherRoom, val seat: FakeTogetherHub.Seat) {
        val events = mutableListOf<TogetherRoomEvent>()
        var finished = false
        val latest: TogetherRoomSnapshot
            get() = events.filterIsInstance<TogetherRoomEvent.Snapshot>().lastOrNull()?.snapshot ?: room.initialSnapshot

        fun member(id: String) = latest.members.firstOrNull { it.memberId == id }
        val memberIds get() = latest.members.map { it.memberId }
    }

    private fun watching(episodeId: Int = 7, positionMillis: Long = 0) = TogetherWatchingInfo(
        subjectId = 100, episodeId = episodeId, subjectName = "S", episodeSort = "07", episodeName = "E",
        positionMillis = positionMillis, positionAtMillis = 0, durationMillis = 1_440_000, paused = false,
    )

    private fun report(id: String, watching: TogetherWatchingInfo? = null) =
        TogetherSelfReport(nickname = id.uppercase(), following = true, watching = watching)

    /** 加入房间, 当成员 */
    private suspend fun TestScope.join(
        id: String,
        clockOffset: Long = 0,
        room: TogetherRoomId = ROOM,
        localNow: (() -> Long)? = null,
        asHost: Boolean = false,
    ): Peer {
        val gateway = ChannelTogetherRoomGateway(hub, scope = backgroundScope, localNowMillis = localNow ?: { currentTime + clockOffset })
        val room = gateway.join(room, memberId = id, self = report(id), asHost = asHost)
        val peer = Peer(room, hub.seats.last())
        backgroundScope.launch {
            room.events.collect { peer.events += it }
            peer.finished = true
        }
        runCurrent()
        return peer
    }

    /** 开房间, 当房主 (房间号是自己的设备号) */
    private suspend fun TestScope.host(
        id: String,
        clockOffset: Long = 0,
        room: TogetherRoomId = ROOM,
        localNow: (() -> Long)? = null,
    ): Peer = join(id, clockOffset, room, localNow, asHost = true)

    @Test
    fun `opening ones own room makes this device its host at once`() = runTest {
        val alice = host("alice")
        assertTrue(alice.room.isHost)
        assertTrue(alice.room.created)
        assertEquals(listOf("alice"), alice.memberIds)
        assertEquals("alice", alice.latest.hostId)
        // 不等谁回应
        assertEquals(0, currentTime)
        assertTrue(alice.seat.answersStrangers)
    }

    @Test
    fun `joining a room nobody hosts fails instead of opening it`() = runTest {
        val failure = assertFailsWith<TogetherRoomEmptyException> { join("bob") }
        assertIs<TogetherRoomEmptyException>(failure)
        // 两轮招呼都没人应
        assertEquals(6_000, currentTime)
        assertTrue(hub.seats.single().closed)
        assertEquals(2, hub.seats.single().sent.count { it is TogetherMessage.Hello })
    }

    @Test
    fun `joining with the wrong password is refused by the host`() = runTest {
        val alice = host("alice")
        assertFailsWith<TogetherWrongPasswordException> { join("mallory", room = ROOM.copy(password = "654321")) }
        // 房主当场回了「解不开」, 不用等满一轮
        assertEquals(0, currentTime)
        assertEquals(listOf("alice"), alice.memberIds)
    }

    @Test
    fun `a member whose password went stale is refused when it speaks again`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        // 乙离线期间房主换了密码 (关掉房间, 用新密码重开)
        bob.seat.goOffline()
        runCurrent()
        alice.room.leave(TogetherCloseReason.PASSWORD_CHANGED)
        runCurrent()
        val aliceAgain = host("alice", room = ROOM.copy(password = "654321"))
        // 乙连回来打招呼: 新房间解不开, 房主回执, 乙的房间到此结束
        bob.seat.goOnline()
        runCurrent()
        assertTrue(TogetherRoomEvent.Rejected in bob.events)
        assertTrue(bob.finished)
        assertEquals(listOf("alice"), aliceAgain.memberIds)
    }

    @Test
    fun `members are told why the room closed`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        alice.room.leave(TogetherCloseReason.PASSWORD_CHANGED)
        runCurrent()
        assertEquals(TogetherRoomStatus.CLOSED, bob.latest.status)
        assertEquals(TogetherCloseReason.PASSWORD_CHANGED, bob.latest.closeReason)
    }

    @Test
    fun `a later joiner is welcomed as a member and shares the room clock`() = runTest {
        val alice = host("alice", clockOffset = 50_000)
        val bob = join("bob", clockOffset = -7_000)
        assertFalse(bob.room.isHost)
        assertFalse(bob.room.created)
        assertEquals(listOf("alice", "bob"), bob.memberIds)
        assertEquals(listOf("alice", "bob"), alice.memberIds)
        // 没等超时: 房主当场回应了
        assertEquals(0, currentTime)
        // 两台设备的时钟差 57 秒, 房间时间是同一个
        assertEquals(alice.room.clock.now(), bob.room.clock.now())
        assertEquals(currentTime + 50_000, bob.room.clock.now())
    }

    @Test
    fun `a member asks the host the time a few more times after joining and again every few minutes`() = runTest {
        val alice = host("alice", clockOffset = 50_000)
        val bob = join("bob", clockOffset = -7_000)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(4, bob.seat.sent.count { it is TogetherMessage.TimeQuery })
        assertEquals(4, alice.seat.sent.count { it is TogetherMessage.TimeReply })
        assertEquals(currentTime + 50_000, bob.room.clock.now())

        // 过了 5 分钟再量一轮
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals(8, bob.seat.sent.count { it is TogetherMessage.TimeQuery })
        assertEquals(currentTime + 50_000, bob.room.clock.now())
        // 房主自己不问
        assertEquals(0, alice.seat.sent.count { it is TogetherMessage.TimeQuery })
    }

    @Test
    fun `rooms with different numbers do not see each other`() = runTest {
        val alice = host("alice")
        assertFailsWith<TogetherRoomEmptyException> { join("stranger", room = TogetherRoomId("00000000", "123456")) }
        assertEquals(listOf("alice"), alice.memberIds)
    }

    @Test
    fun `reports flow through the host into every snapshot`() = runTest {
        val alice = host("alice")
        val bob = join("bob")

        bob.room.report(report("bob", watching(episodeId = 9)))
        runCurrent()
        assertEquals(9, alice.member("bob")?.watching?.episodeId)
        assertEquals(9, bob.member("bob")?.watching?.episodeId)
        // 成员在看什么不是房间的播放状态
        assertNull(bob.latest.playback)

        alice.room.report(report("alice", watching(positionMillis = 30_000)))
        runCurrent()
        assertEquals(30_000, bob.latest.playback?.info?.positionMillis)
        assertEquals(bob.latest, alice.latest)
    }

    @Test
    fun `a member leaving is removed from the room`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        bob.room.leave()
        runCurrent()
        assertEquals(listOf("alice"), alice.memberIds)
        assertTrue(bob.seat.closed)
        assertTrue(bob.finished)
    }

    @Test
    fun `the host leaving closes the room for everyone`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        alice.room.leave()
        runCurrent()
        assertEquals(TogetherRoomStatus.CLOSED, bob.latest.status)
        // 成员这边的房间也到此结束
        assertTrue(bob.finished)
        assertTrue(bob.seat.closed)
    }

    @Test
    fun `a crashed member is marked disconnected after the grace period and restored on return`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        bob.seat.crash()
        runCurrent()
        assertEquals(TogetherMemberState.IDLE, alice.member("bob")?.state)

        advanceTimeBy(13.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.DISCONNECTED, alice.member("bob")?.state)

        val bobAgain = join("bob")
        assertFalse(bobAgain.room.isHost)
        assertEquals(listOf("alice", "bob"), alice.memberIds)
        assertEquals(TogetherMemberState.IDLE, alice.member("bob")?.state)
    }

    @Test
    fun `a disconnected member is dropped from the list after five minutes`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        bob.seat.crash()
        advanceTimeBy(13.seconds)
        runCurrent()
        assertEquals(listOf("alice", "bob"), alice.memberIds)
        advanceTimeBy(300.seconds)
        runCurrent()
        assertEquals(listOf("alice"), alice.memberIds)
    }

    @Test
    fun `losing one of several broker links is not a disconnect`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        // 断的那家替乙发了遗嘱, 乙随即从另一家说了一声还在
        bob.seat.loseOneLink()
        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.IDLE, alice.member("bob")?.state)

        // 房主这边断一家也一样 (乙先连回那一家, 才收得到那家替房主发的遗嘱)
        bob.seat.goOnline()
        runCurrent()
        alice.seat.loseOneLink()
        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.IDLE, bob.member("alice")?.state)
    }

    @Test
    fun `a member that was offline catches up when the connection returns`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        bob.seat.goOffline()
        runCurrent()
        assertEquals(TogetherRoomEvent.Connectivity(false), bob.events.last())

        alice.room.report(report("alice", watching(positionMillis = 60_000)))
        runCurrent()
        assertNull(bob.latest.playback)

        bob.seat.goOnline()
        runCurrent()
        assertTrue(TogetherRoomEvent.Connectivity(true) in bob.events)
        assertEquals(60_000, bob.latest.playback?.info?.positionMillis)
    }

    @Test
    fun `a member sees the host as disconnected when the host crashes`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        alice.seat.crash()
        advanceTimeBy(13.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.DISCONNECTED, bob.member("alice")?.state)
        // 房间没散, 乙也没有变成房主
        assertEquals(TogetherRoomStatus.OPEN, bob.latest.status)
        assertFalse(bob.finished)
    }

    @Test
    fun `the host reopening the app takes the room back and members sign in again`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        alice.seat.crash()
        advanceTimeBy(13.seconds)
        runCurrent()

        // 房主重开应用, 用同一个编号重新开自己的房间
        val aliceAgain = host("alice")
        assertTrue(aliceAgain.room.isHost)
        advanceTimeBy(1.seconds)
        runCurrent()
        // 乙认新的快照 (版本比之前的大), 发现名单里没有自己, 重新打了招呼
        assertEquals(listOf("alice", "bob"), aliceAgain.memberIds)
        assertEquals(listOf("alice", "bob"), bob.memberIds)
        assertEquals(TogetherMemberState.IDLE, bob.member("alice")?.state)
    }

    @Test
    fun `two devices opening the same room settle on one host`() = runTest {
        // 数据搬到了新电视, 旧电视也还开着: 两台用着同一个房间号, 都开了房间
        val alice = host("alice")
        val bob = join("bob", asHost = true)
        runCurrent()
        // 人数一样, 编号小的留下
        assertTrue(alice.room.isHost)
        assertFalse(alice.finished)
        assertTrue(TogetherRoomEvent.HostChanged in bob.events)
        assertTrue(bob.finished)

        // 让位的那个重新进, 这回是成员
        val bobAgain = join("bob")
        assertFalse(bobAgain.room.isHost)
        assertEquals(listOf("alice", "bob"), alice.memberIds)
    }

    @Test
    fun `a newcomer who opened a second room yields to the established one whatever the ids`() = runTest {
        val zoe = host("zoe")
        val mia = join("mia")
        // 房主暂时断线时, 另一台用着同一个房间号的设备也开了房间
        zoe.seat.goOffline()
        val adam = host("adam")
        assertTrue(adam.room.isHost)

        zoe.seat.goOnline()
        runCurrent()
        // 原来的房间人多, 新人让位, 尽管他的编号更小
        assertTrue(TogetherRoomEvent.HostChanged in adam.events)
        assertFalse(zoe.finished)
        assertEquals("zoe", mia.latest.hostId)
        assertEquals(listOf("zoe", "mia"), zoe.memberIds)
    }

    @Test
    fun `members follow their host to the winner when it yields`() = runTest {
        val zoe = host("zoe")
        // 另一边有人数更多的同名房间: 甲当房主带着两个人, 先与这边隔开
        zoe.seat.goOffline()
        val adam = host("adam")
        val eve = join("eve")
        val finn = join("finn")
        assertEquals(listOf("adam", "eve", "finn"), adam.memberIds)

        // 这边的房主带着一个成员
        adam.seat.goOffline()
        eve.seat.goOffline()
        finn.seat.goOffline()
        zoe.seat.goOnline()
        val mia = join("mia")
        assertEquals(listOf("zoe", "mia"), zoe.memberIds)

        // 两边接通: 人少的房主让位, 她的成员跟着去
        adam.seat.goOnline()
        eve.seat.goOnline()
        finn.seat.goOnline()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(TogetherRoomEvent.HostChanged in zoe.events)
        assertEquals("adam", mia.latest.hostId)
        assertTrue("mia" in adam.memberIds)
    }

    @Test
    fun `the host hands the room to a member it picked`() = runTest {
        val zoe = host("zoe")
        val mia = join("mia")
        val adam = join("adam")
        assertEquals(listOf("zoe", "mia", "adam"), zoe.memberIds)

        zoe.room.transferHost("mia")
        runCurrent()
        // 乙的这个房间到此结束 (不说再见), 由上一层以房主身份重开
        assertTrue(TogetherRoomEvent.PromotedToHost in mia.events)
        assertTrue(mia.finished)
        assertTrue(mia.seat.sent.none { it is TogetherMessage.Bye })
        assertFalse(zoe.finished)

        // 乙重开: 名单里只有自己, 按人数比本该让回去; 但他是被请来的, 原来的房主让位, 别的成员跟过去
        val miaHost = host("mia")
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(miaHost.room.isHost)
        assertFalse(miaHost.finished)
        assertTrue(TogetherRoomEvent.HostChanged in zoe.events)
        assertTrue(zoe.finished)
        assertEquals("mia", adam.latest.hostId)

        // 原来的房主重新进来当成员
        val zoeAgain = join("zoe")
        assertFalse(zoeAgain.room.isHost)
        assertEquals(setOf("mia", "adam", "zoe"), miaHost.memberIds.toSet())
    }

    @Test
    fun `a transfer nobody takes up is dropped and the host stays`() = runTest {
        val zoe = host("zoe")
        val mia = join("mia")
        // 乙收到了请求但一直没重开房间 (旧版本不认得这条消息时也一样)
        zoe.room.transferHost("mia")
        runCurrent()
        assertTrue(mia.finished)
        // 等的途中每个节拍再说一遍
        advanceTimeBy(5.seconds)
        runCurrent()
        assertTrue(zoe.seat.sent.count { it is TogetherMessage.TransferHost } >= 2)

        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf(TogetherRoomEvent.TransferFailed("mia")), zoe.events.filterIsInstance<TogetherRoomEvent.TransferFailed>())
        assertFalse(zoe.finished)
        assertTrue(zoe.room.isHost)
        // 作罢之后不再说
        val sent = zoe.seat.sent.count { it is TogetherMessage.TransferHost }
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(sent, zoe.seat.sent.count { it is TogetherMessage.TransferHost })
    }

    @Test
    fun `only the host can hand the room over and only to someone in it`() = runTest {
        val zoe = host("zoe")
        val mia = join("mia")
        // 成员请别人当房主: 不理会
        mia.room.transferHost("zoe")
        // 名单里没有的人、自己: 不理会
        zoe.room.transferHost("stranger")
        zoe.room.transferHost("zoe")
        runCurrent()
        assertTrue(hub.seats.flatMap { it.sent }.none { it is TogetherMessage.TransferHost })
        assertFalse(mia.finished)
    }

    @Test
    fun `a joiner whose hello is lost joins the existing room instead of opening a second one`() = runTest {
        val alice = host("alice")
        hub.dropHellos = true
        // 等回应的途中房主照常发着快照
        backgroundScope.launch {
            delay(1_000)
            alice.room.report(report("alice", watching(positionMillis = 5_000)))
        }
        val bob = join("bob")
        assertFalse(bob.room.isHost)
        assertEquals("alice", bob.latest.hostId)

        // 路通了之后, 下一份快照里没有自己就再打招呼 (两次招呼之间至少隔几秒)
        hub.dropHellos = false
        advanceTimeBy(6.seconds)
        alice.room.report(report("alice", watching(positionMillis = 9_000)))
        runCurrent()
        assertEquals(listOf("alice", "bob"), alice.memberIds)
        assertEquals(listOf("alice", "bob"), bob.memberIds)
    }

    @Test
    fun `a joiner whose first hello misses the host joins as a member once the shared broker connects`() = runTest {
        // 房主只连得上第二家代理
        hub.startBrokers = setOf(1)
        val alice = host("alice")
        // 新来的先连上第一家 (房主不在那里), 一秒后才连上第二家
        hub.startBrokers = setOf(0)
        val joining = async { join("bob") }
        advanceTimeBy(1.seconds)
        hub.seats.last().connectTo(0, 1)
        val bob = joining.await()

        assertFalse(bob.room.isHost)
        assertFalse(bob.room.created)
        assertEquals(listOf("alice", "bob"), alice.memberIds)
        // 第二家一连上就打了招呼, 房主当场回应: 没等满一轮
        assertEquals(1_000, currentTime)
    }

    @Test
    fun `a broker that connects late in the wait still gets time for the host to answer`() = runTest {
        hub.startBrokers = setOf(1)
        val alice = host("alice")
        // 一来一回 0.8 秒
        hub.latencyMillis = 400
        hub.startBrokers = setOf(0)
        val joining = async { join("bob") }
        // 这一轮还剩 0.2 秒时才连上房主在的那一家
        advanceTimeBy(2_800.milliseconds)
        hub.seats.last().connectTo(0, 1)
        val bob = joining.await()

        assertFalse(bob.room.isHost)
        assertEquals(listOf("alice", "bob"), alice.memberIds)
        assertEquals(2_800 + 800, currentTime)
    }

    @Test
    fun `two hosts that could not hear each other settle on one when a shared broker connects`() = runTest {
        hub.startBrokers = setOf(0)
        val alice = host("alice")
        hub.startBrokers = setOf(1)
        val carol = host("carol")
        assertTrue(carol.room.isHost)

        // 甲多连上一家, 往那一家也发了一遍快照: 两个房主这才看到对方
        alice.seat.connectTo(0, 1)
        runCurrent()
        assertTrue(TogetherRoomEvent.HostChanged in carol.events)
        assertTrue(carol.finished)
        assertFalse(alice.finished)
    }

    @Test
    fun `a member greets the host again when another of its brokers connects`() = runTest {
        val alice = host("alice")
        hub.startBrokers = setOf(0)
        val bob = join("bob")
        val hellosBefore = bob.seat.sent.count { it is TogetherMessage.Hello }

        bob.seat.connectTo(0, 1)
        runCurrent()
        assertEquals(hellosBefore + 1, bob.seat.sent.count { it is TogetherMessage.Hello })
        assertEquals(listOf("alice", "bob"), alice.memberIds)
        assertEquals(listOf("alice", "bob"), bob.memberIds)
    }

    @Test
    fun `a will that arrives after its sender reconnected does not mark them as dropped`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        // 乙断过网又连上了; 代理这时才把他旧连接的遗嘱发给房间
        bob.seat.staleWill()
        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.IDLE, alice.member("bob")?.state)

        // 房主的旧连接也一样
        alice.seat.staleWill()
        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.IDLE, bob.member("alice")?.state)
    }

    @Test
    fun `a will from a broker its sender no longer uses is answered when the host asks`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        // 乙连第二家代理的那条连接无声地断了, 之后也没再连回去
        bob.seat.connectTo(0)
        runCurrent()
        // 过了一阵那家代理才发遗嘱; 乙已经不在那家上, 自己收不到
        advanceTimeBy(30.seconds)
        bob.seat.staleWill(via = setOf(1))
        advanceTimeBy(20.seconds)
        runCurrent()
        // 房主问了一声, 乙从第一家回了话
        assertEquals(TogetherMemberState.IDLE, alice.member("bob")?.state)
        assertTrue(alice.seat.sent.any { it is TogetherMessage.Ping && it.to == "bob" })
    }

    @Test
    fun `a host that may sit on a broker still down is given longer before it counts as gone`() = runTest {
        // 房主只连得上第二家
        hub.startBrokers = setOf(1)
        val alice = host("alice")
        hub.startBrokers = setOf(0, 1)
        val bob = join("bob")

        // 乙断网后先只连回了第一家: 招呼够不着房主
        bob.seat.goOffline()
        runCurrent()
        bob.seat.connectTo(0)
        advanceTimeBy(13.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.IDLE, bob.member("alice")?.state)

        // 第二家也连回来了: 房主回应, 从头到尾没被当成掉线
        bob.seat.connectTo(0, 1)
        advanceTimeBy(13.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.IDLE, bob.member("alice")?.state)
        assertFalse(bob.events.filterIsInstance<TogetherRoomEvent.Snapshot>().any { e ->
            e.snapshot.members.any { it.memberId == "alice" && it.state == TogetherMemberState.DISCONNECTED }
        })

        // 一直只连着第一家: 等得久一些, 最后还是算他不在了
        bob.seat.goOffline()
        runCurrent()
        bob.seat.connectTo(0)
        advanceTimeBy(50.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.DISCONNECTED, bob.member("alice")?.state)
    }

    @Test
    fun `a broker that keeps dropping does not put off the verdict forever`() = runTest {
        hub.startBrokers = setOf(1)
        val alice = host("alice")
        hub.startBrokers = setOf(0, 1)
        val bob = join("bob")
        // 乙离线期间房主解散了; 乙连回来后, 第二家代理连上又断、断了又连
        bob.seat.goOffline()
        runCurrent()
        alice.room.leave()
        runCurrent()
        bob.seat.connectTo(0)
        repeat(12) { round ->
            advanceTimeBy(5.seconds)
            if (round % 2 == 0) bob.seat.connectTo(0, 1) else bob.seat.connectTo(0)
        }
        runCurrent()
        assertEquals(TogetherMemberState.DISCONNECTED, bob.member("alice")?.state)
    }

    @Test
    fun `a host whose will came before the member went offline gets a fresh wait when the member returns`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        // 一来一回 3 秒
        hub.latencyMillis = 1_500
        // 房主一条旧连接的遗嘱到了, 紧接着乙自己断网 20 秒
        alice.seat.staleWill()
        runCurrent()
        bob.seat.goOffline()
        advanceTimeBy(20.seconds)
        bob.seat.goOnline()
        advanceTimeBy(8.seconds)
        runCurrent()
        // 房主一直在: 乙连回来问了一声, 等到了回应, 中间没把房主当成掉线
        assertEquals(TogetherMemberState.IDLE, bob.member("alice")?.state)
        assertFalse(bob.events.filterIsInstance<TogetherRoomEvent.Snapshot>().any { e ->
            e.snapshot.members.any { it.memberId == "alice" && it.state == TogetherMemberState.DISCONNECTED }
        })
    }

    @Test
    fun `a member wrongly listed as disconnected signs in again`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        // 乙一条旧连接的遗嘱到了, 房主问的那几声都丢了
        hub.dropPings = true
        advanceTimeBy(6.seconds)
        bob.seat.staleWill()
        advanceTimeBy(13.seconds)
        runCurrent()
        // 房主把乙标成了掉线; 乙从快照里看到, 重新打了招呼
        assertEquals(TogetherMemberState.IDLE, alice.member("bob")?.state)
        assertEquals(TogetherMemberState.IDLE, bob.member("bob")?.state)
    }

    @Test
    fun `a member that is offline itself does not conclude that the host is gone`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        hub.latencyMillis = 400
        // 乙刚连回来打了招呼, 回应还在路上自己又断了半分钟
        bob.seat.goOffline()
        runCurrent()
        bob.seat.goOnline()
        runCurrent()
        bob.seat.goOffline()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.IDLE, bob.member("alice")?.state)

        bob.seat.goOnline()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(TogetherMemberState.IDLE, bob.member("alice")?.state)
        assertEquals(listOf("alice", "bob"), alice.memberIds)
    }

    @Test
    fun `a member that comes back after the host is gone sees the host as disconnected`() = runTest {
        val alice = host("alice")
        val bob = join("bob")
        // 乙离线期间房主解散了房间: 那条消息乙收不到
        bob.seat.goOffline()
        runCurrent()
        alice.room.leave()
        runCurrent()

        bob.seat.goOnline()
        advanceTimeBy(13.seconds)
        runCurrent()
        // 招呼一直没人应
        assertEquals(TogetherMemberState.DISCONNECTED, bob.member("alice")?.state)
        assertFalse(bob.finished)
    }

    @Test
    fun `a clock that was stepped is measured again from the next snapshot`() = runTest {
        var aliceClockOffset = 0L
        val alice = host("alice", localNow = { currentTime + aliceClockOffset })
        val bob = join("bob")
        assertEquals(alice.room.clock.now(), bob.room.clock.now())

        // 房主的系统时间被对时往前拨了 20 秒
        advanceTimeBy(6.seconds)
        aliceClockOffset = 20_000
        alice.room.report(report("alice", watching(positionMillis = 1_000)))
        runCurrent()
        // 乙从这份快照看出两边对不上, 重新量了一次
        assertEquals(alice.room.clock.now(), bob.room.clock.now())
    }

    @Test
    fun `joining fails when no broker can be reached`() = runTest {
        hub.startOffline = true
        assertFailsWith<TogetherJoinException> { host("alice") }
        assertEquals(20_000, currentTime)
        assertTrue(hub.seats.single().closed)
    }

    @Test
    fun `leaving twice is harmless`() = runTest {
        val alice = host("alice")
        alice.room.leave()
        alice.room.leave()
        runCurrent()
        assertNotNull(alice.room.initialSnapshot)
        assertTrue(alice.finished)
    }

    private companion object {
        val ROOM = TogetherRoomId("48271593", "123456")
    }
}
