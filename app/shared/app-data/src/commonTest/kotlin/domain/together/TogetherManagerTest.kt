/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.RememberedTogetherRoom
import me.him188.ani.app.data.models.preference.TogetherRoomRef
import me.him188.ani.app.data.models.preference.TogetherSettings
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.navigation.EpisodeNavigationGuardRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TogetherManagerTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)

        /** 写不进去 (磁盘出错) */
        var failWrites = false
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            if (failWrites) throw IllegalStateException("disk error")
            state.value = value
        }
    }

    private class FakeRoom(
        override val isHost: Boolean,
        override val created: Boolean,
        override val initialSnapshot: TogetherRoomSnapshot,
        override val clock: TogetherRoomClock,
    ) : TogetherRoom {
        override val selfId: String get() = "member-1"
        val eventChannel = Channel<TogetherRoomEvent>(Channel.UNLIMITED)
        override val events: Flow<TogetherRoomEvent> = eventChannel.receiveAsFlow()
        val reports = mutableListOf<TogetherSelfReport>()
        val transfers = mutableListOf<String>()
        var left = false
        var leftReason: TogetherCloseReason? = null

        override suspend fun report(report: TogetherSelfReport) {
            reports += report
        }

        override suspend fun transferHost(memberId: String) {
            transfers += memberId
        }

        override suspend fun leave(reason: TogetherCloseReason) {
            left = true
            leftReason = reason
            eventChannel.close()
        }

        fun push(snapshot: TogetherRoomSnapshot) {
            eventChannel.trySend(TogetherRoomEvent.Snapshot(snapshot))
        }
    }

    private data class Join(val room: TogetherRoomId, val memberId: String, val self: TogetherSelfReport, val asHost: Boolean)

    private inner class FakeGateway : TogetherRoomGateway {
        var failure: Throwable? = null
        var hostPlayback: TogetherWatchingInfo? = null
        val rooms = mutableListOf<FakeRoom>()
        val joins = mutableListOf<Join>()

        /** 非空时进房间要等它完成 (模拟连接要花时间) */
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun join(room: TogetherRoomId, memberId: String, self: TogetherSelfReport, asHost: Boolean): TogetherRoom {
            joins += Join(room, memberId, self, asHost)
            hold?.await()
            failure?.let { throw it }
            val joined = FakeRoom(
                isHost = asHost,
                created = asHost,
                initialSnapshot = snapshot(version = 1, playback = if (asHost) null else hostPlayback),
                clock = TogetherRoomClock { now() },
            )
            rooms += joined
            return joined
        }
    }

    private var now: () -> Long = { 0L }
    private val gateway = FakeGateway()
    private val settings = MemorySettings(TogetherSettings.Default)
    private var playerOnScreen = true
    private val playerOnScreenChanges = MutableStateFlow(true)
    private var appForeground = true
    private val bridge = TogetherPlaybackBridge(
        playerOnScreen = { playerOnScreen },
        appForeground = { appForeground },
        playerOnScreenChanges = playerOnScreenChanges,
    )

    private fun setPlayerOnScreen(onScreen: Boolean) {
        playerOnScreen = onScreen
        playerOnScreenChanges.value = onScreen
    }
    private val gate = PlaybackAutomationGate()
    private val effects = mutableListOf<TogetherEffect>()
    private val directives = mutableListOf<PlaybackDirective>()
    private val managers = mutableListOf<TogetherManager>()
    private val passwords = ArrayDeque(listOf("111111", "222222", "333333", "444444"))
    private val player = Any()
    private val room get() = gateway.rooms.last()

    @AfterTest
    fun leaveRooms() = runTest {
        // 导航守卫登记在全局, 不离开会留给后面的测试
        managers.forEach { it.leave() }
    }

    private fun TestScope.newManager(profile: TogetherProfileProvider = TogetherProfileProvider { TogetherProfile("Me") }): TogetherManager {
        now = { currentTime }
        val manager = TogetherManager(
            scope = backgroundScope,
            gateway = gateway,
            settings = settings,
            profile = profile,
            playbackBridge = bridge,
            automationGate = gate,
            localNowMillis = { currentTime },
            newMemberId = { "member-1" },
            newDigits = { count -> if (count == TOGETHER_ROOM_NUMBER_DIGITS) OWN.number else passwords.removeFirst() },
        )
        managers += manager
        backgroundScope.launch { manager.effects.collect { effects += it } }
        backgroundScope.launch { manager.navigations.collect { effects += it } }
        backgroundScope.launch { bridge.directives.collect { directives += it } }
        runCurrent()
        return manager
    }

    private fun watching(
        episodeId: Int = 7,
        positionMillis: Long = 0,
        paused: Boolean = false,
        subjectId: Int = 100,
        playbackRate: Float = 1f,
        buffering: Boolean = false,
    ) = TogetherWatchingInfo(
        subjectId = subjectId, episodeId = episodeId, subjectName = "Subject", episodeSort = "07", episodeName = "Episode",
        positionMillis = positionMillis, positionAtMillis = now(), durationMillis = 1_440_000,
        paused = paused, buffering = buffering, playbackRate = playbackRate,
    )

    private fun snapshot(
        version: Long,
        playback: TogetherWatchingInfo? = null,
        status: TogetherRoomStatus = TogetherRoomStatus.OPEN,
        hostState: TogetherMemberState = TogetherMemberState.IDLE,
        guests: List<String> = emptyList(),
    ) = TogetherRoomSnapshot(
        version = version, status = status, hostId = "host", roomTime = now(),
        members = listOf(TogetherMember("host", "Host", isHost = true, following = true, state = hostState, lastSeenAt = now())) +
                guests.map { TogetherMember(it, it, isHost = false, following = true, state = TogetherMemberState.IDLE, lastSeenAt = now()) },
        playback = playback?.let { TogetherPlayback(it) },
    )

    private fun session(manager: TogetherManager) = assertIs<TogetherState.InRoom>(manager.state.value).session

    // ---- 进出房间 ----

    @Test
    fun `opening ones own room makes this device the host of its device number`() = runTest {
        val manager = newManager()
        assertEquals(OWN, manager.createRoom().getOrThrow())
        runCurrent()

        val session = session(manager)
        assertTrue(session.isHost)
        assertEquals(OWN, session.roomId)
        val join = gateway.joins.single()
        assertEquals(OWN, join.room)
        assertTrue(join.asHost)
        assertEquals("member-1", join.memberId)
        assertEquals(TogetherSelfReport("Me", following = true, watching = null), join.self)
        // 编号、设备号、密码生成一次就存下来
        assertEquals("member-1", settings.state.value.memberId)
        assertEquals(OWN.number, settings.state.value.deviceNumber)
        assertEquals(OWN.password, settings.state.value.roomPassword)
        assertEquals(RememberedTogetherRoom(OWN.number, OWN.password, own = true, joinedAt = 0), settings.state.value.rememberedRoom)
        // 房主不受跟随那一套约束
        assertFalse(gate.suppressed.value)

        // 下次开还是同一个房间号与密码
        manager.leave()
        assertEquals(OWN, manager.createRoom().getOrThrow())
    }

    @Test
    fun `joining a friends room is as a member and remembers it`() = runTest {
        val manager = newManager()
        assertTrue(manager.join(FRIEND).isSuccess)
        runCurrent()
        assertFalse(session(manager).isHost)
        assertFalse(gateway.joins.single().asHost)
        assertEquals(TogetherRoomRef(FRIEND.number, FRIEND.password), settings.state.value.lastJoined)
        assertEquals(
            RememberedTogetherRoom(FRIEND.number, FRIEND.password, own = false, joinedAt = 0),
            settings.state.value.rememberedRoom,
        )
    }

    @Test
    fun `joining ones own room number opens it only with the right password`() = runTest {
        val manager = newManager()
        val own = manager.ownRoom()
        assertIs<TogetherWrongPasswordException>(manager.join(own.copy(password = "999999")).exceptionOrNull())
        assertEquals(0, gateway.joins.size)
        assertTrue(manager.join(own).isSuccess)
        assertTrue(gateway.joins.single().asHost)
    }

    @Test
    fun `changing the password while hosting closes the room for everyone and reopens it`() = runTest {
        val manager = newManager()
        manager.createRoom()
        runCurrent()
        val first = room
        val changed = manager.changePassword().getOrThrow()
        runCurrent()
        assertEquals(TogetherRoomId(OWN.number, "222222"), changed)
        assertEquals(TogetherCloseReason.PASSWORD_CHANGED, first.leftReason)
        assertEquals(changed, session(manager).roomId)
        assertTrue(session(manager).isHost)
        assertEquals("222222", settings.state.value.roomPassword)
        assertEquals("222222", settings.state.value.rememberedRoom?.password)
    }

    @Test
    fun `changing the password outside the room only picks a new one`() = runTest {
        val manager = newManager()
        val before = manager.ownRoom()
        val after = manager.changePassword().getOrThrow()
        assertEquals(before.number, after.number)
        assertNotEquals(before.password, after.password)
        assertEquals(TogetherState.Idle, manager.state.value)
        assertEquals(0, gateway.joins.size)
    }

    @Test
    fun `generated room numbers and passwords are digits`() {
        repeat(50) {
            assertTrue(Regex("[0-9]{8}").matches(randomTogetherDigits(8)))
        }
    }

    @Test
    fun `a failed join leaves the manager idle and remembers nothing`() = runTest {
        val manager = newManager()
        for (failure in listOf(TogetherJoinException("unreachable"), TogetherRoomEmptyException(), TogetherWrongPasswordException())) {
            gateway.failure = failure
            assertSame(failure, manager.join(FRIEND).exceptionOrNull())
            assertEquals(TogetherState.Idle, manager.state.value)
            assertNull(settings.state.value.rememberedRoom)
        }
    }

    @Test
    fun `a join survives the screen that started it going away`() = runTest {
        gateway.hold = CompletableDeferred()
        val manager = newManager()
        // 弹窗里按了「加入」, 连接还没好弹窗就关了
        val caller = launch { manager.join(FRIEND) }
        runCurrent()
        assertEquals(TogetherState.Joining(FRIEND.number), manager.state.value)
        caller.cancel()
        runCurrent()
        assertEquals(TogetherState.Joining(FRIEND.number), manager.state.value)

        gateway.hold!!.complete(Unit)
        runCurrent()
        assertEquals(FRIEND, session(manager).roomId)
    }

    @Test
    fun `a room that was entered but could not be recorded is left again`() = runTest {
        settings.state.value = TogetherSettings(memberId = "member-1", deviceNumber = OWN.number, roomPassword = OWN.password)
        val manager = newManager()
        // 进了房间, 记下「在这个房间里」的那一步写不进去
        settings.failWrites = true
        assertTrue(manager.join(FRIEND).isFailure)
        settings.failWrites = false

        assertEquals(TogetherState.Idle, manager.state.value)
        // 不留一个没人管的房间在后台连着
        assertTrue(gateway.rooms.single().left)
        assertFalse(gate.suppressed.value)
    }

    @Test
    fun `effects that nobody collects never block the manager`() = runTest {
        now = { currentTime }
        // 界面不在: 没有人取给界面的指示
        val manager = TogetherManager(
            scope = backgroundScope,
            gateway = gateway,
            settings = settings,
            profile = { TogetherProfile("Me") },
            playbackBridge = bridge,
            automationGate = gate,
            localNowMillis = { currentTime },
            newMemberId = { "member-1" },
        )
        managers += manager
        manager.join(FRIEND)
        runCurrent()
        repeat(100) { round ->
            room.push(snapshot(version = 2L + round, guests = if (round % 2 == 0) listOf("guest") else emptyList()))
            runCurrent()
        }
        room.push(snapshot(version = 200, status = TogetherRoomStatus.CLOSED))
        runCurrent()
        assertEquals(TogetherState.Idle, manager.state.value)

        // 界面回来: 只拿到最近的几条, 房间解散那一条在
        val collected = mutableListOf<TogetherEffect>()
        backgroundScope.launch { manager.effects.collect { collected += it } }
        runCurrent()
        assertTrue(collected.size <= 16, "${collected.size}")
        assertEquals(TogetherEffect.RoomClosed(passwordChanged = false), collected.last())
        // 还能照常进出
        assertTrue(manager.join(FRIEND).isSuccess)
    }

    @Test
    fun `joining again while in a room is refused`() = runTest {
        val manager = newManager()
        assertTrue(manager.join(FRIEND).isSuccess)
        assertTrue(manager.join(TogetherRoomId("00000000", "000000")).isFailure)
        assertEquals(1, gateway.rooms.size)
    }

    @Test
    fun `leaving forgets the room`() = runTest {
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()
        manager.leave()
        assertTrue(room.left)
        assertEquals(TogetherCloseReason.DISBANDED, room.leftReason)
        assertEquals(TogetherState.Idle, manager.state.value)
        assertNull(settings.state.value.rememberedRoom)
        // 上次加入的房间还留着, 下次加入先填上
        assertEquals(TogetherRoomRef(FRIEND.number, FRIEND.password), settings.state.value.lastJoined)
        assertFalse(gate.suppressed.value)
    }

    @Test
    fun `the room being closed by its host ends the session`() = runTest {
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()
        room.push(snapshot(version = 2, status = TogetherRoomStatus.CLOSED))
        runCurrent()
        assertEquals(TogetherState.Idle, manager.state.value)
        assertEquals(listOf<TogetherEffect>(TogetherEffect.RoomClosed(passwordChanged = false)), effects)
        assertNull(settings.state.value.rememberedRoom)
        assertFalse(gate.suppressed.value)
    }

    @Test
    fun `a room closed for a new password and a refusal both say the password changed`() = runTest {
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()
        room.push(snapshot(version = 2, status = TogetherRoomStatus.CLOSED).copy(closeReason = TogetherCloseReason.PASSWORD_CHANGED))
        runCurrent()
        assertEquals(listOf<TogetherEffect>(TogetherEffect.RoomClosed(passwordChanged = true)), effects)

        // 没收到关房间的那条, 之后说话被房主回「解不开」
        effects.clear()
        manager.join(FRIEND)
        runCurrent()
        room.eventChannel.trySend(TogetherRoomEvent.Rejected)
        runCurrent()
        assertEquals(TogetherState.Idle, manager.state.value)
        assertEquals(listOf<TogetherEffect>(TogetherEffect.RoomClosed(passwordChanged = true)), effects)
        assertNull(settings.state.value.rememberedRoom)
    }

    @Test
    fun `yielding the host role rejoins the same room as a member silently`() = runTest {
        val manager = newManager()
        manager.createRoom()
        runCurrent()
        gateway.rooms[0].eventChannel.trySend(TogetherRoomEvent.HostChanged)
        runCurrent()
        assertEquals(2, gateway.joins.size)
        assertFalse(gateway.joins[1].asHost)
        assertFalse(session(manager).isHost)
        assertEquals(emptyList(), effects)
    }

    @Test
    fun `connectivity of the room is reflected in the session`() = runTest {
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()
        val session = session(manager)
        assertEquals(TogetherConnectionState.CONNECTED, session.connection.value)
        room.eventChannel.trySend(TogetherRoomEvent.Connectivity(false))
        runCurrent()
        assertEquals(TogetherConnectionState.RECONNECTING, session.connection.value)
        room.eventChannel.trySend(TogetherRoomEvent.Connectivity(true))
        runCurrent()
        assertEquals(TogetherConnectionState.CONNECTED, session.connection.value)
    }

    // ---- 回到上次的房间 ----

    private fun remember(room: TogetherRoomId, own: Boolean) {
        settings.state.value = TogetherSettings(
            memberId = "member-1", deviceNumber = OWN.number, roomPassword = OWN.password,
            rememberedRoom = RememberedTogetherRoom(room.number, room.password, own = own, joinedAt = 0),
        )
    }

    @Test
    fun `starting reopens ones own room that was never closed`() = runTest {
        remember(OWN, own = true)
        val manager = newManager()
        advanceTimeBy(1.hours)
        manager.start()
        runCurrent()
        assertEquals(OWN, session(manager).roomId)
        assertTrue(gateway.joins.single().asHost)
        assertEquals(listOf<TogetherEffect>(TogetherEffect.Rejoined(own = true, room = OWN)), effects)
    }

    @Test
    fun `starting returns to a friends room as a member`() = runTest {
        remember(FRIEND, own = false)
        val manager = newManager()
        manager.start()
        runCurrent()
        assertEquals(FRIEND, session(manager).roomId)
        assertFalse(gateway.joins.single().asHost)
        assertEquals(listOf<TogetherEffect>(TogetherEffect.Rejoined(own = false, room = FRIEND)), effects)
    }

    @Test
    fun `a room remembered for too long is forgotten instead`() = runTest {
        remember(FRIEND, own = false)
        val manager = newManager()
        advanceTimeBy(13.hours)
        manager.start()
        runCurrent()
        assertEquals(TogetherState.Idle, manager.state.value)
        assertNull(settings.state.value.rememberedRoom)
        assertEquals(0, gateway.joins.size)
    }

    @Test
    fun `returning to a room whose host is away keeps retrying with growing delays`() = runTest {
        remember(FRIEND, own = false)
        gateway.failure = TogetherRoomEmptyException()
        val manager = newManager()
        manager.start()
        advanceTimeBy(15.seconds)
        runCurrent()
        // 第 0、2、6、14 秒; 一直是成员, 不会自己当房主
        assertEquals(4, gateway.joins.size)
        assertTrue(gateway.joins.none { it.asHost })

        gateway.failure = null
        advanceTimeBy(20.seconds)
        runCurrent()
        assertIs<TogetherState.InRoom>(manager.state.value)
        assertEquals(5, gateway.joins.size)
    }

    @Test
    fun `returning to a room whose password changed gives up and says so`() = runTest {
        remember(FRIEND, own = false)
        gateway.failure = TogetherWrongPasswordException()
        val manager = newManager()
        manager.start()
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(1, gateway.joins.size)
        assertNull(settings.state.value.rememberedRoom)
        assertEquals(listOf<TogetherEffect>(TogetherEffect.RoomClosed(passwordChanged = true)), effects)
    }

    // ---- 转让房主 ----

    private fun guest(id: String, state: TogetherMemberState = TogetherMemberState.IDLE, host: Boolean = false) =
        TogetherMember(id, id.uppercase(), isHost = host, following = true, state = state, lastSeenAt = now())

    @Test
    fun `the host asks a member to take over and rejoins as a member once they do`() = runTest {
        val manager = newManager()
        manager.createRoom()
        runCurrent()
        val hosting = room
        hosting.push(snapshot(version = 2, guests = listOf("mia")).let { it.copy(members = it.members + guest("gone", TogetherMemberState.DISCONNECTED)) })
        runCurrent()
        // 掉线的人、自己、名单里没有的人: 不请
        manager.transferHost("gone")
        manager.transferHost("member-1")
        manager.transferHost("stranger")
        assertEquals(emptyList(), hosting.transfers)

        manager.transferHost("mia")
        assertEquals(listOf("mia"), hosting.transfers)
        assertEquals("mia", session(manager).pendingTransfer.value)

        // 乙接手了: 说一声, 回去当成员 (还是这个房间)
        hosting.eventChannel.trySend(TogetherRoomEvent.HostChanged)
        hosting.eventChannel.close()
        runCurrent()
        assertTrue(effects.any { it is TogetherEffect.HostChanged && it.host.memberId == "mia" })
        assertFalse(session(manager).isHost)
        assertEquals(OWN, session(manager).roomId)
        assertFalse(gateway.joins.last().asHost)
        assertEquals(false, settings.state.value.rememberedRoom?.own)
    }

    @Test
    fun `a member asked to take over reopens the room as its host`() = runTest {
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()
        // 重开要重新连代理: 这一阵是「正在切换房主」, 不是没在房间里
        val connecting = CompletableDeferred<Unit>()
        gateway.hold = connecting
        room.eventChannel.trySend(TogetherRoomEvent.PromotedToHost)
        room.eventChannel.close()
        runCurrent()
        assertEquals(TogetherState.Joining(FRIEND.number, handoff = true), manager.state.value)
        connecting.complete(Unit)
        gateway.hold = null
        runCurrent()
        val session = session(manager)
        assertTrue(session.isHost)
        assertEquals(FRIEND, session.roomId)
        assertTrue(gateway.joins.last().asHost)
        assertTrue(TogetherEffect.BecameHost in effects)
        // 应用重开后还是以房主身份回来
        assertEquals(true, settings.state.value.rememberedRoom?.own)

        // 房间是别人的: 密码只有房间的主人能换, 换密码不关房间
        assertFalse(session.ownsRoom)
        assertTrue(manager.changePassword().isFailure)
        runCurrent()
        assertSame(session, session(manager))
        assertFalse(room.left)
    }

    @Test
    fun `a transfer nobody takes up is reported and the host stays`() = runTest {
        val manager = newManager()
        manager.createRoom()
        runCurrent()
        room.push(snapshot(version = 2, guests = listOf("mia")))
        runCurrent()
        manager.transferHost("mia")
        room.eventChannel.trySend(TogetherRoomEvent.TransferFailed("mia"))
        runCurrent()
        val failed = assertIs<TogetherEffect.TransferFailed>(effects.last())
        assertEquals("mia", failed.memberId)
        assertEquals("mia", failed.member?.memberId)
        assertNull(session(manager).pendingTransfer.value)
        assertTrue(session(manager).isHost)
    }

    @Test
    fun `members are told the host changed without hearing everyone leave and come back`() = runTest {
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()
        val base = snapshot(version = 2, guests = listOf("mia", "adam"))
        room.push(base)
        runCurrent()
        effects.clear()

        // 房主让给了乙: 先只有乙自己, 别人陆续跟过去, 原来的房主也回来当成员
        fun hostedByMia(version: Long, vararg others: String) = base.copy(
            version = version, hostId = "mia",
            members = listOf(guest("mia", host = true)) + others.map { guest(it) },
        )
        room.push(hostedByMia(1))
        room.push(hostedByMia(2, "adam"))
        room.push(hostedByMia(3, "adam", "host"))
        runCurrent()
        assertEquals(listOf("mia"), effects.filterIsInstance<TogetherEffect.HostChanged>().map { it.host.memberId })
        assertTrue(effects.none { it is TogetherEffect.MembersChanged })

        // 过了一阵, 进出照常提示
        advanceTimeBy(30.seconds)
        room.push(hostedByMia(4, "adam", "host", "eve"))
        runCurrent()
        assertEquals(
            listOf("eve"),
            effects.filterIsInstance<TogetherEffect.MembersChanged>().flatMap { e -> e.changes.map { it.member.memberId } },
        )
    }

    // ---- 把自己的状态告诉房间 ----

    @Test
    fun `a name that arrives after opening the room is told to the room`() = runTest {
        // 应用刚启动, 账号信息还没读出来: 开房间时名字是空的
        val name = MutableStateFlow("")
        val manager = newManager(
            object : TogetherProfileProvider {
                override suspend fun current() = TogetherProfile(name.value)
                override fun changes() = name.map { TogetherProfile(it) }
            },
        )
        manager.createRoom()
        runCurrent()
        assertEquals("", gateway.joins.single().self.nickname)
        val before = room.reports.size

        // 读出来了: 空闲着的房主也再报一次
        name.value = "Me"
        runCurrent()
        assertEquals("Me", room.reports.drop(before).single().nickname)
    }

    @Test
    fun `the host reports only when the last report no longer predicts its playback`() = runTest {
        val manager = newManager()
        manager.createRoom()
        runCurrent()
        assertEquals(0, room.reports.size)

        // 进了播放页开始播
        bridge.updateLocalWatching(player, watching(positionMillis = 0))
        runCurrent()
        assertEquals(1, room.reports.size)
        assertEquals(0, room.reports.last().watching?.positionMillis)

        // 正常往下播: 每秒一个采样, 都在推算之内
        repeat(10) { second ->
            advanceTimeBy(1.seconds)
            bridge.updateLocalWatching(player, watching(positionMillis = (second + 1) * 1_000L))
            runCurrent()
        }
        assertEquals(1, room.reports.size)

        // 暂停
        advanceTimeBy(1.seconds)
        bridge.updateLocalWatching(player, watching(positionMillis = 11_000, paused = true))
        runCurrent()
        assertEquals(2, room.reports.size)
        assertEquals(true, room.reports.last().watching?.paused)

        // 暂停着不动
        advanceTimeBy(30.seconds)
        bridge.updateLocalWatching(player, watching(positionMillis = 11_000, paused = true))
        runCurrent()
        assertEquals(2, room.reports.size)

        // 拖到别处
        bridge.updateLocalWatching(player, watching(positionMillis = 600_000, paused = true))
        runCurrent()
        assertEquals(3, room.reports.size)

        // 退出播放页
        bridge.clearLocalWatching(player)
        runCurrent()
        assertEquals(4, room.reports.size)
        assertNull(room.reports.last().watching)
    }

    @Test
    fun `the host reports a position that creeps away without ever jumping`() = runTest {
        val manager = newManager()
        manager.createRoom()
        bridge.updateLocalWatching(player, watching(positionMillis = 0))
        runCurrent()
        assertEquals(1, room.reports.size)

        // 每秒多走 0.5 秒: 相邻两次采样之间算不上跳变, 但离上次说的越来越远
        var position = 0L
        repeat(3) {
            advanceTimeBy(1.seconds)
            position += 1_500
            bridge.updateLocalWatching(player, watching(positionMillis = position))
            runCurrent()
        }
        assertEquals(1, room.reports.size)
        advanceTimeBy(1.seconds)
        bridge.updateLocalWatching(player, watching(positionMillis = position + 1_500))
        runCurrent()
        // 第 4 秒: 推算 4000, 实际 6000
        assertEquals(2, room.reports.size)
        assertEquals(6_000, room.reports.last().watching?.positionMillis)
    }

    @Test
    fun `a member reports the changes of its own playback`() = runTest {
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()

        bridge.updateLocalWatching(player, watching(positionMillis = 0))
        runCurrent()
        assertEquals(1, room.reports.size)

        bridge.updateLocalWatching(player, watching(positionMillis = 0, paused = true))
        runCurrent()
        assertEquals(2, room.reports.size)
        assertEquals(true, room.reports.last().watching?.paused)
    }

    // ---- 跟随房主 ----

    @Test
    fun `a following member is taken to the episode the host is playing`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 60_000)
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()

        // 不在播放页: 打开房主那一集
        val navigate = assertIs<TogetherEffect.Navigate>(effects.single())
        assertEquals(100 to 7, navigate.subjectId to navigate.episodeId)
        assertEquals("Subject", navigate.subjectName)
        assertTrue(gate.suppressed.value)
        assertEquals(7, bridge.targetPlayback.value?.info?.episodeId)

        // 进了那一集的播放页之后房主换到下一集: 就地换集
        bridge.updateLocalWatching(player, watching(episodeId = 7))
        runCurrent()
        directives.clear()
        room.push(snapshot(version = 2, playback = watching(episodeId = 8)))
        runCurrent()
        assertEquals(8, assertIs<PlaybackDirective.SwitchEpisode>(directives.single()).episodeId)

        // 房主去看另一部: 退出来再打开
        effects.clear()
        room.push(snapshot(version = 3, playback = watching(subjectId = 200, episodeId = 1)))
        runCurrent()
        val away = assertIs<TogetherEffect.Navigate>(effects.single())
        assertEquals(200 to 1, away.subjectId to away.episodeId)
    }

    @Test
    fun `a following member away from the player is left alone and taken into the next episode`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 0)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0))
        runCurrent()
        directives.clear()
        effects.clear()

        // 退出了播放页: 会话留在后台, 被按成暂停, 而房主还在播
        playerOnScreen = false
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 2_000, paused = true))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(emptyList(), directives)

        // 房主换到下一集: 人不在播放页, 把他带进去, 而不是让后台的会话自己换集
        room.push(snapshot(version = 2, playback = watching(episodeId = 8)))
        runCurrent()
        assertEquals(emptyList(), directives)
        val next = assertIs<TogetherEffect.Navigate>(effects.single())
        assertEquals(100 to 8, next.subjectId to next.episodeId)

        // 人回到播放页: 下一秒就照房主的对
        playerOnScreen = true
        bridge.updateLocalWatching(player, watching(episodeId = 8, positionMillis = 0, paused = true))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertIs<PlaybackDirective.Sync>(directives.last())
    }

    @Test
    fun `a host only preparing an episode in the background is followed in the background`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, paused = true).copy(onScreen = false)
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()

        // 房主只在后台准备着: 不在播放页的成员也只在后台准备
        val prepare = assertIs<TogetherEffect.Navigate>(effects.single())
        assertEquals(100 to 7, prepare.subjectId to prepare.episodeId)
        assertTrue(prepare.inBackground)

        // 房主进了播放页: 带进去
        effects.clear()
        room.push(snapshot(version = 2, playback = watching(episodeId = 7, paused = true)))
        runCurrent()
        val enter = assertIs<TogetherEffect.Navigate>(effects.single())
        assertEquals(100 to 7, enter.subjectId to enter.episodeId)
        assertFalse(enter.inBackground)
    }

    @Test
    fun `a member in the player of another show is taken in even if the host only prepares`() = runTest {
        gateway.hostPlayback = watching(subjectId = 200, episodeId = 1, paused = true).copy(onScreen = false)
        bridge.updateLocalWatching(player, watching(subjectId = 100, episodeId = 7))
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()

        val navigate = assertIs<TogetherEffect.Navigate>(effects.single())
        assertEquals(200 to 1, navigate.subjectId to navigate.episodeId)
        assertFalse(navigate.inBackground)
    }

    @Test
    fun `the host reports going in and out of the player even while paused`() = runTest {
        val manager = newManager()
        manager.createRoom()
        bridge.updateLocalWatching(player, watching(positionMillis = 5_000, paused = true))
        runCurrent()
        assertEquals(true, room.reports.last().watching?.onScreen)
        val reports = room.reports.size

        // 停着退出播放页 (会话留在后台): 画面状态一点没变, 也要告诉房间
        setPlayerOnScreen(false)
        runCurrent()
        assertEquals(reports + 1, room.reports.size)
        assertEquals(false, room.reports.last().watching?.onScreen)

        // 停着回到播放页
        setPlayerOnScreen(true)
        runCurrent()
        assertEquals(reports + 2, room.reports.size)
        assertEquals(true, room.reports.last().watching?.onScreen)
        manager.leave()
    }

    @Test
    fun `a following member whose app is in the background is left alone and catches up on return`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 0)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0))
        runCurrent()
        directives.clear()
        effects.clear()

        // 按 HOME 去了别的应用: 播放页还是栈顶那一页, 播放器被按成暂停
        appForeground = false
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 2_000, paused = true))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(emptyList(), directives)

        // 房主换到下一集: 在后台什么都不做 (不换集、不改页面)
        room.push(snapshot(version = 2, playback = watching(episodeId = 8)))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(emptyList(), directives)
        assertEquals(emptyList(), effects)

        // 回到应用: 下一秒就地换到房主那一集
        appForeground = true
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(8, assertIs<PlaybackDirective.SwitchEpisode>(directives.first()).episodeId)
        assertEquals(emptyList(), effects)
    }

    @Test
    fun `a pending navigation is not pushed out by later notices`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 0)
        now = { currentTime }
        // 界面不在: 没有人取给界面的指示
        val manager = TogetherManager(
            scope = backgroundScope,
            gateway = gateway,
            settings = settings,
            profile = { TogetherProfile("Me") },
            playbackBridge = bridge,
            automationGate = gate,
            localNowMillis = { currentTime },
            newMemberId = { "member-1" },
        )
        managers += manager
        manager.join(FRIEND)
        runCurrent()
        repeat(40) { round ->
            room.push(
                snapshot(version = 2L + round, playback = watching(episodeId = 7), guests = if (round % 2 == 0) listOf("guest") else emptyList()),
            )
            runCurrent()
        }

        val collected = mutableListOf<TogetherEffect>()
        backgroundScope.launch { manager.effects.collect { collected += it } }
        backgroundScope.launch { manager.navigations.collect { collected += it } }
        runCurrent()
        val navigate = collected.filterIsInstance<TogetherEffect.Navigate>().single()
        assertEquals(100 to 7, navigate.subjectId to navigate.episodeId)
    }

    @Test
    fun `leaving finishes even when the caller stops waiting`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7)
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()
        assertTrue(gate.suppressed.value)

        // 发起离开的一方中途不等了 (离开正停在等会话的协程收尾上)
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { manager.leave() }
        caller.cancel()
        runCurrent()

        assertEquals(TogetherState.Idle, manager.state.value)
        assertTrue(room.left)
        assertFalse(gate.suppressed.value)
        assertNull(bridge.targetPlayback.value)
        assertNull(settings.state.value.rememberedRoom)
        assertTrue(EpisodeNavigationGuardRegistry.checkOrNotifyDenied(200, 1))
    }

    @Test
    fun `following is suspended while the host is offline and picks up when the host returns`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 0)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0))
        runCurrent()
        assertTrue(gate.suppressed.value)
        assertFalse(EpisodeNavigationGuardRegistry.checkOrNotifyDenied(100, 8))
        assertTrue(manager.shouldStillNavigate(100, 7))

        // 房主掉线了
        room.push(
            snapshot(version = 1, playback = watching(episodeId = 7, positionMillis = 0), hostState = TogetherMemberState.DISCONNECTED),
        )
        runCurrent()
        directives.clear()
        assertFalse(gate.suppressed.value)
        assertNull(bridge.targetPlayback.value)
        // 不拦着成员自己去看别的, 也不照着早已过时的状态纠正
        assertTrue(EpisodeNavigationGuardRegistry.checkOrNotifyDenied(100, 8))
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0, paused = true))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(emptyList(), directives)
        assertFalse(manager.shouldStillNavigate(100, 7))

        // 房主回来了, 在播下一集
        room.push(snapshot(version = 2, playback = watching(episodeId = 8)))
        runCurrent()
        assertTrue(gate.suppressed.value)
        assertEquals(8, assertIs<PlaybackDirective.SwitchEpisode>(directives.single()).episodeId)
        assertTrue(manager.shouldStillNavigate(100, 8))
        assertFalse(manager.shouldStillNavigate(100, 7))
    }

    @Test
    fun `a member already in the same episode is told to align at once`() = runTest {
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0))
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 300_000)
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()
        val sync = assertIs<PlaybackDirective.Sync>(directives.first())
        assertTrue(sync.initial)
        assertEquals(300_000, sync.playback.info.positionMillis)
        assertEquals(emptyList(), effects)
    }

    @Test
    fun `a following member cannot open other episodes on their own`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7)
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()
        assertTrue(EpisodeNavigationGuardRegistry.checkOrNotifyDenied(100, 7))
        assertFalse(EpisodeNavigationGuardRegistry.checkOrNotifyDenied(100, 8))
        assertFalse(EpisodeNavigationGuardRegistry.checkOrNotifyDenied(200, 1))

        manager.leave()
        assertTrue(EpisodeNavigationGuardRegistry.checkOrNotifyDenied(200, 1))
    }

    @Test
    fun `a member that wanders to another episode in the player is pulled back`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7))
        runCurrent()
        directives.clear()

        // 播放页里自己换到了第 9 集
        bridge.updateLocalWatching(player, watching(episodeId = 9))
        runCurrent()
        assertEquals(7, assertIs<PlaybackDirective.SwitchEpisode>(directives.last()).episodeId)
    }

    @Test
    fun `a following member is corrected every second while out of step`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 0)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0))
        runCurrent()
        directives.clear()

        // 两边一起往下播: 不用纠正
        advanceTimeBy(5.seconds)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 5_000))
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(emptyList(), directives)

        // 成员自己暂停了
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 7_000, paused = true))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertFalse(assertIs<PlaybackDirective.Sync>(directives.last()).initial)

        // 成员落后了 5 秒
        directives.clear()
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 3_000))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertIs<PlaybackDirective.Sync>(directives.last())

        // 倍速不一样
        directives.clear()
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 9_000, playbackRate = 1.5f))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertIs<PlaybackDirective.Sync>(directives.last())
    }

    @Test
    fun `a member is not moved while the host has just sped up and is once it is back`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 0)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0))
        runCurrent()

        // 房主长按加速, 成员也跟到了 2.5 倍, 但改倍速时卡了几秒、落后了
        room.push(snapshot(version = 2, playback = watching(episodeId = 7, positionMillis = 0, playbackRate = 2.5f)))
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0, playbackRate = 2.5f))
        runCurrent()
        directives.clear()
        advanceTimeBy(4.seconds)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 1_000, playbackRate = 2.5f))
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(emptyList(), directives)

        // 一直开着倍速看: 过了那一阵照常纠正
        advanceTimeBy(6.seconds)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 2_000, playbackRate = 2.5f))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertIs<PlaybackDirective.Sync>(directives.last())
    }

    @Test
    fun `a member whose own source cannot keep up is left playing behind and told once`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 0)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0))
        runCurrent()
        advanceTimeBy(10.seconds)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 10_000))
        runCurrent()
        directives.clear()
        effects.clear()

        // 自己落后 5 秒: 跳过去
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 5_000))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertIs<PlaybackDirective.Sync>(directives.single())

        // 跳过去之后在缓冲: 位置停着不动, 不按位置纠正
        directives.clear()
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 11_000, buffering = true))
        advanceTimeBy(8.seconds)
        runCurrent()
        assertEquals(emptyList(), directives)

        // 播起来时又落后了: 认定跟不上, 不再跳, 说一次
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 12_000))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(emptyList(), directives)
        assertEquals(1, effects.count { it == TogetherEffect.FallingBehind })
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(emptyList(), directives)
        assertEquals(1, effects.count { it == TogetherEffect.FallingBehind })

        // 房主自己往后拖了一大段: 照样跟
        room.push(snapshot(version = 2, playback = watching(episodeId = 7, positionMillis = 300_000)))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertIs<PlaybackDirective.Sync>(directives.last())
        manager.leave()
    }

    @Test
    fun `a follower paused for a buffering host is left waiting and released when the host recovers`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 0)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0))
        runCurrent()

        // 房主卡住了, 成员还在播: 让播放页去处理 (它等满两秒才停)
        room.push(snapshot(version = 2, playback = watching(episodeId = 7, positionMillis = 5_000, buffering = true)))
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 5_000))
        runCurrent()
        directives.clear()
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<PlaybackDirective.Sync>(directives.last())

        // 成员停下来等着: 没什么要纠正的
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 5_000, paused = true))
        runCurrent()
        directives.clear()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(emptyList(), directives)

        // 房主好了: 成员该接着播
        room.push(snapshot(version = 3, playback = watching(episodeId = 7, positionMillis = 5_000)))
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<PlaybackDirective.Sync>(directives.last())
    }

    @Test
    fun `free watching is left alone until following is turned back on`() = runTest {
        settings.state.value = TogetherSettings(followHost = false)
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 60_000)
        val manager = newManager()
        manager.start()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 9))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertFalse(gateway.joins.single().self.following)
        assertFalse(gate.suppressed.value)
        assertNull(bridge.targetPlayback.value)
        assertEquals(emptyList(), directives)
        assertEquals(emptyList(), effects)
        assertTrue(EpisodeNavigationGuardRegistry.checkOrNotifyDenied(100, 9))

        val reportsBefore = room.reports.size
        manager.setFollowing(true)
        runCurrent()
        assertTrue(settings.state.value.followHost)
        assertTrue(gate.suppressed.value)
        // 告诉房间自己开始跟随了, 并被带回房主那一集
        assertTrue(room.reports.size > reportsBefore)
        assertTrue(room.reports.last().following)
        assertEquals(7, assertIs<PlaybackDirective.SwitchEpisode>(directives.last()).episodeId)
    }

    @Test
    fun `member changes between snapshots are told apart`() {
        fun member(id: String, state: TogetherMemberState = TogetherMemberState.IDLE) =
            TogetherMember(id, id.uppercase(), isHost = id == "host", following = true, state = state, lastSeenAt = 0)

        fun room(vararg members: TogetherMember) = TogetherRoomSnapshot(
            version = 1, status = TogetherRoomStatus.OPEN, hostId = "host", roomTime = 0, members = members.toList(),
        )

        fun kinds(before: TogetherRoomSnapshot, after: TogetherRoomSnapshot) =
            memberChanges(before, after, selfId = "me").map { it.member.memberId to it.kind }

        val alone = room(member("host"), member("me"))
        // 自己进出不算
        assertEquals(emptyList(), kinds(room(member("host")), alone))
        assertEquals(
            listOf("bob" to TogetherMemberChange.Kind.JOINED),
            kinds(alone, room(member("host"), member("me"), member("bob"))),
        )
        assertEquals(
            listOf("bob" to TogetherMemberChange.Kind.LEFT),
            kinds(room(member("host"), member("me"), member("bob")), alone),
        )
        val bobDown = room(member("host"), member("me"), member("bob", TogetherMemberState.DISCONNECTED))
        assertEquals(
            listOf("bob" to TogetherMemberChange.Kind.DROPPED),
            kinds(room(member("host"), member("me"), member("bob", TogetherMemberState.WATCHING)), bobDown),
        )
        assertEquals(
            listOf("bob" to TogetherMemberChange.Kind.RETURNED),
            kinds(bobDown, room(member("host"), member("me"), member("bob"))),
        )
        // 掉线的人过了很久被从名单里去掉: 早就说过掉线了, 不再说离开
        assertEquals(emptyList(), kinds(bobDown, alone))
        // 状态在空闲与观看之间变不算
        assertEquals(emptyList(), kinds(alone, room(member("host", TogetherMemberState.WATCHING), member("me"))))
    }

    @Test
    fun `people coming and going are announced`() = runTest {
        val manager = newManager()
        manager.createRoom()
        runCurrent()
        effects.clear()

        val bob = TogetherMember("bob", "Bob", isHost = false, following = true, state = TogetherMemberState.IDLE, lastSeenAt = 0)
        val withBob = snapshot(version = 2).let { it.copy(members = it.members + bob) }
        room.push(withBob)
        runCurrent()
        val change = assertIs<TogetherEffect.MembersChanged>(effects.single()).changes.single()
        assertEquals("Bob", change.member.nickname)
        assertEquals(TogetherMemberChange.Kind.JOINED, change.kind)

        effects.clear()
        room.push(snapshot(version = 3))
        runCurrent()
        assertEquals(TogetherMemberChange.Kind.LEFT, assertIs<TogetherEffect.MembersChanged>(effects.single()).changes.single().kind)
    }

    @Test
    fun `a follower is told when its picture stops for a buffering host`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7)
        val manager = newManager()
        manager.join(FRIEND)
        runCurrent()
        effects.clear()
        bridge.notifyWaitingForHost()
        runCurrent()
        assertEquals(listOf<TogetherEffect>(TogetherEffect.WaitingForHost), effects)
    }

    @Test
    fun `the host is never steered by its own room`() = runTest {
        val manager = newManager()
        manager.createRoom()
        bridge.updateLocalWatching(player, watching(episodeId = 7))
        runCurrent()
        room.push(snapshot(version = 2, playback = watching(episodeId = 8, positionMillis = 99_000)))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(emptyList(), directives)
        assertEquals(emptyList(), effects)
        assertFalse(gate.suppressed.value)
        assertTrue(EpisodeNavigationGuardRegistry.checkOrNotifyDenied(300, 3))
    }

    @Test
    fun `being pulled back to the host is announced at most once in five seconds`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7))
        advanceTimeBy(10.seconds)
        runCurrent()
        effects.clear()

        bridge.notifyCorrectionApplied(4_000)
        runCurrent()
        bridge.notifyCorrectionApplied(-3_500)
        runCurrent()
        assertEquals(listOf<TogetherEffect>(TogetherEffect.ResyncedWithHost(4_000)), effects)

        advanceTimeBy(6.seconds)
        bridge.notifyCorrectionApplied(-3_500)
        runCurrent()
        assertEquals(2, effects.size)
        assertNotNull(effects.last() as? TogetherEffect.ResyncedWithHost)

        // 接着播时对齐的那一点点不提示
        advanceTimeBy(6.seconds)
        bridge.notifyCorrectionApplied(1_200)
        runCurrent()
        assertEquals(2, effects.size)
    }

    @Test
    fun `following a jump the host made is not announced as a resync`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 0)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0))
        advanceTimeBy(10.seconds)
        runCurrent()
        effects.clear()

        // 房主快进到 60 秒 (停在那里等大家), 成员跟过去
        room.push(snapshot(version = 2, playback = watching(episodeId = 7, positionMillis = 60_000, paused = true)))
        runCurrent()
        bridge.notifyCorrectionApplied(50_000)
        runCurrent()
        assertEquals(emptyList(), effects.filterIsInstance<TogetherEffect.ResyncedWithHost>())

        // 过一阵自己跟丢了被拉回来: 照常提示
        advanceTimeBy(6.seconds)
        bridge.notifyCorrectionApplied(4_000)
        runCurrent()
        assertEquals(1, effects.filterIsInstance<TogetherEffect.ResyncedWithHost>().size)
    }

    @Test
    fun `a host pause or resume reaches a following member at once`() = runTest {
        gateway.hostPlayback = watching(episodeId = 7, positionMillis = 0)
        val manager = newManager()
        manager.join(FRIEND)
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 0))
        runCurrent()
        directives.clear()

        // 每秒那一轮之间房主按了暂停: 当场让播放页对齐, 不等下一轮
        advanceTimeBy(400)
        room.push(snapshot(version = 2, playback = watching(episodeId = 7, positionMillis = 400, paused = true)))
        runCurrent()
        assertIs<PlaybackDirective.Sync>(directives.single())
        assertTrue(assertNotNull(bridge.targetPlayback.value).info.paused)

        // 房主接着播
        directives.clear()
        bridge.updateLocalWatching(player, watching(episodeId = 7, positionMillis = 400, paused = true))
        room.push(snapshot(version = 3, playback = watching(episodeId = 7, positionMillis = 400)))
        runCurrent()
        assertIs<PlaybackDirective.Sync>(directives.single())

        // 只是照推算往前走了一点的新快照 (比如名单变了): 不算房主的状态变了
        directives.clear()
        advanceTimeBy(300)
        room.push(snapshot(version = 4, playback = watching(episodeId = 7, positionMillis = 700)))
        runCurrent()
        assertEquals(emptyList(), directives)
    }

    private companion object {
        /** 本机的设备号与第一次生成的密码 */
        val OWN = TogetherRoomId("88888888", "111111")

        /** 朋友的房间 */
        val FRIEND = TogetherRoomId("48271593", "123456")
    }
}
