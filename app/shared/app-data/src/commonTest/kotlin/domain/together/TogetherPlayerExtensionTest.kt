/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.preference.TogetherSettings
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.mediaSelectorFlow
import me.him188.ani.app.domain.episode.player
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.TestUniversalMediaResolver
import me.him188.ani.app.domain.player.extension.AbstractPlayerExtensionTest
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.isMediaLoaded
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TogetherPlayerExtensionTest : AbstractPlayerExtensionTest() {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        private val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private class FakeRoom(
        override val isHost: Boolean,
        override val initialSnapshot: TogetherRoomSnapshot,
        override val clock: TogetherRoomClock,
    ) : TogetherRoom {
        override val selfId: String get() = "me"
        override val created: Boolean get() = isHost
        val eventChannel = Channel<TogetherRoomEvent>(Channel.UNLIMITED)
        override val events: Flow<TogetherRoomEvent> = eventChannel.receiveAsFlow()
        val reports = mutableListOf<TogetherSelfReport>()

        override suspend fun report(report: TogetherSelfReport) {
            reports += report
        }

        override suspend fun leave(reason: TogetherCloseReason) {
            eventChannel.close()
        }
    }

    private class Case(
        val suite: EpisodePlayerTestSuite,
        val bridge: TogetherPlaybackBridge,
        val manager: TogetherManager,
        val rooms: MutableList<FakeRoom>,
    ) {
        val room get() = rooms.last()
    }

    private val managers = mutableListOf<TogetherManager>()

    @AfterTest
    fun leaveRooms() = runTest {
        managers.forEach { it.leave() }
    }

    private fun member(id: String, isHost: Boolean, following: Boolean = true) =
        TogetherMember(id, id, isHost = isHost, following = following, state = TogetherMemberState.IDLE, lastSeenAt = 0)

    /**
     * @param asHost 进房间时自己是不是房主
     * @param others 房间里除自己之外还在线几个人
     * @param hostPlayback 自己是成员时房主在播什么
     * @param othersFollowing 那几个人开没开「跟随房主播放」
     * @param playerInFront 播放页在不在眼前
     */
    private fun TestScope.createCase(
        asHost: Boolean = true,
        others: Int = 1,
        hostPlayback: TogetherWatchingInfo? = null,
        registerTogether: Boolean = true,
        othersFollowing: Boolean = true,
        playerInFront: () -> Boolean = { true },
    ): Case {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val suite = EpisodePlayerTestSuite(this)
        val bridge = TogetherPlaybackBridge(playerOnScreen = playerInFront)
        val rooms = mutableListOf<FakeRoom>()
        val gateway = TogetherRoomGateway { _, _, _, _ ->
            val members = buildList {
                add(member("host", isHost = true))
                repeat(others) { add(member("other-$it", isHost = false, following = othersFollowing)) }
            }
            FakeRoom(
                isHost = asHost,
                initialSnapshot = TogetherRoomSnapshot(
                    version = 1, status = TogetherRoomStatus.OPEN, hostId = "host", roomTime = currentTime, members = members,
                    playback = hostPlayback?.let { TogetherPlayback(it) },
                ),
                clock = TogetherRoomClock { currentTime },
            ).also { rooms += it }
        }
        val manager = TogetherManager(
            scope = backgroundScope,
            gateway = gateway,
            settings = MemorySettings(TogetherSettings(memberId = "me")),
            profile = { TogetherProfile("Me") },
            playbackBridge = bridge,
            automationGate = PlaybackAutomationGate(),
            localNowMillis = { currentTime },
        )
        managers += manager
        if (registerTogether) {
            suite.registerComponent<TogetherPlaybackBridge> { bridge }
            suite.registerComponent<TogetherManager> { manager }
        }
        suite.registerComponent<MediaResolver> { TestUniversalMediaResolver }
        return Case(suite, bridge, manager, rooms)
    }

    private fun TestScope.hostWatching(
        positionMillis: Long = 0,
        paused: Boolean = false,
        buffering: Boolean = false,
        playbackRate: Float = 1f,
        episodeId: Int = initialEpisodeId,
    ) = TogetherWatchingInfo(
        subjectId = subjectId, episodeId = episodeId, subjectName = "S", episodeSort = "01", episodeName = "E",
        positionMillis = positionMillis, positionAtMillis = currentTime, durationMillis = 100_000,
        paused = paused, buffering = buffering, playbackRate = playbackRate,
    )

    private fun TestScope.pushHost(case: Case, watching: TogetherWatchingInfo, version: Long) {
        case.room.eventChannel.trySend(
            TogetherRoomEvent.Snapshot(
                case.room.initialSnapshot.copy(version = version, playback = TogetherPlayback(watching)),
            ),
        )
        runCurrent()
    }

    @OptIn(UnsafeEpisodeSessionApi::class)
    private suspend fun TestScope.loadSelectedMedia(
        suite: EpisodePlayerTestSuite,
        state: EpisodeFetchSelectPlayState,
        durationMillis: Long = 100_000L,
    ) {
        val media = TestMediaList[0]
        val source = suite.mediaSelectorTestBuilder.delayedMediaSource("together-0")
        source.complete(listOf(media))
        suite.setMediaDuration(durationMillis)
        state.mediaSelectorFlow.filterNotNull().first().select(media)
        // 不能 advanceUntilIdle: 资源装好之后每秒的采样让调度器永远不空闲
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
    }

    private fun EpisodePlayerTestSuite.start(): EpisodeFetchSelectPlayState =
        createState(listOf(TogetherPlayerExtension)).also { it.onUIReady() }

    // ---- 把本机状态写出去 ----

    @Test
    fun `a speed change is reported at once rather than on the next tick`() = runTest {
        val case = createCase(asHost = true, others = 0)
        case.manager.createRoom()
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(1f, case.bridge.localWatching.value?.playbackRate)

        // 长按确定键加速: 不等下一个每秒的节拍
        withContext(Dispatchers.Main) { case.suite.player.features[PlaybackSpeed]!!.set(2.5f) }
        advanceTimeBy(50)
        runCurrent()
        assertEquals(2.5f, case.bridge.localWatching.value?.playbackRate)
    }

    @Test
    fun `entering the player reports a loading episode before media is loaded`() = runTest {
        val case = createCase()
        advanceTimeBy(1_234)
        case.suite.start()
        runCurrent()

        val watching = assertNotNull(case.bridge.localWatching.value)
        assertEquals(subjectId, watching.subjectId)
        assertEquals(initialEpisodeId, watching.episodeId)
        assertEquals(0L, watching.positionMillis)
        assertEquals(1_234L, watching.positionAtMillis)
        assertEquals(0L, watching.durationMillis)
        assertTrue(watching.paused)
        assertTrue(watching.loading)
        assertFalse(watching.buffering)
        assertEquals(1f, watching.playbackRate)
    }

    @Test
    fun `reloading media does not report transient positions`() = runTest {
        val case = createCase()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)

        case.suite.player.injectPosition(30_000L)
        advanceTimeBy(1_100)
        runCurrent()
        val playing = assertNotNull(case.bridge.localWatching.value)
        assertEquals(30_000L, playing.positionMillis)
        assertFalse(playing.paused)

        // 同一集换源: 播放器重新打开资源, 位置瞬间归零。这一阵不写
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        case.suite.player.openBehavior = hold
        val reload = launch {
            case.suite.player.setMediaData(UriMediaData("file://reload.mp4"), playWhenReady = true, startPositionMillis = 31_000L)
        }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        val duringReload = assertNotNull(case.bridge.localWatching.value)
        assertEquals(30_000L, duringReload.positionMillis)
        assertFalse(duringReload.paused)

        hold.release()
        reload.join()
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(31_000L, assertNotNull(case.bridge.localWatching.value).positionMillis)
    }

    @Test
    fun `leaving the player clears what was reported`() = runTest {
        val case = createCase()
        val state = case.suite.start()
        runCurrent()
        assertNotNull(case.bridge.localWatching.value)
        state.onClose()
        runCurrent()
        assertNull(case.bridge.localWatching.value)
    }

    // ---- 加载完先停住 ----

    @Test
    fun `a host with company holds after the first load until play is pressed`() = runTest {
        val case = createCase(asHost = true, others = 1)
        assertTrue(case.manager.join(ROOM).isSuccess)
        runCurrent()

        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)

        // 装好就自动开播的那一下被按住了
        assertTrue(case.suite.player.state.value.isMediaLoaded)
        assertFalse(case.suite.player.state.value.playWhenReady)
        val held = assertNotNull(case.bridge.localWatching.value)
        assertTrue(held.paused)
        assertFalse(held.loading)
        // 按住之前那一下「在播」没有传到房间里
        assertTrue(case.room.reports.mapNotNull { it.watching }.none { !it.paused }, "${case.room.reports}")

        // 房主自己按播放不再被按住, 并且当即告诉房间
        case.suite.player.play()
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(case.suite.player.state.value.isPlaying)
        assertTrue(case.room.reports.mapNotNull { it.watching }.any { !it.paused })
    }

    /** 房主收到的房间全貌里, 别人都在看 [watching] (`null` = 还没进播放页) */
    private fun TestScope.pushOthersWatching(case: Case, watching: TogetherWatchingInfo?, version: Long) {
        val snapshot = case.room.initialSnapshot
        val members = snapshot.members.map {
            if (it.isHost) it else it.copy(state = if (watching == null) TogetherMemberState.IDLE else TogetherMemberState.WATCHING, watching = watching)
        }
        case.room.eventChannel.trySend(TogetherRoomEvent.Snapshot(snapshot.copy(version = version, members = members)))
        runCurrent()
    }

    /** 房主装好这一集、停在开头 */
    private suspend fun TestScope.holdingHost(others: Int = 1): Case {
        val case = createCase(asHost = true, others = others)
        assertTrue(case.manager.join(ROOM).isSuccess)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        assertFalse(case.suite.player.state.value.playWhenReady)
        return case
    }

    @Test
    fun `a holding host starts by itself once the following members have loaded`() = runTest {
        val case = holdingHost()
        val effects = mutableListOf<TogetherEffect>()
        backgroundScope.launch { case.manager.effects.collect { effects += it } }
        runCurrent()
        assertTrue(TogetherEffect.WaitingForMembers(afterSeek = false) in effects, "$effects")

        // 成员进了这一集还在加载: 接着等
        pushOthersWatching(case, hostWatching(paused = true).copy(loading = true), version = 2)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(case.suite.player.state.value.playWhenReady)

        // 装好了 (停在开头等房主): 开播
        pushOthersWatching(case, hostWatching(paused = true), version = 3)
        assertTrue(case.suite.player.state.value.playWhenReady)
    }

    @Test
    fun `a holding host starts when the wait runs out even if a member never loads`() = runTest {
        val case = holdingHost()
        advanceTimeBy(TogetherPlayerExtension.AUTO_START_TIMEOUT_MILLIS - 1_000)
        runCurrent()
        assertFalse(case.suite.player.state.value.playWhenReady)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(case.suite.player.state.value.playWhenReady)
    }

    @Test
    fun `a host who presses play and pause while waiting is not started again`() = runTest {
        val case = holdingHost()
        case.suite.player.play()
        runCurrent()
        case.suite.player.pause()
        runCurrent()
        pushOthersWatching(case, hostWatching(paused = true), version = 2)
        advanceTimeBy(TogetherPlayerExtension.AUTO_START_TIMEOUT_MILLIS + 1_000)
        runCurrent()
        assertFalse(case.suite.player.state.value.playWhenReady)
    }

    /** 房主与跟随的成员都装好了, 一起在 0 秒那里播起来 */
    private suspend fun TestScope.playingHostWithFollower(): Case {
        val case = holdingHost()
        pushOthersWatching(case, hostWatching(paused = true), version = 2)
        assertTrue(case.suite.player.state.value.playWhenReady)
        advanceTimeBy(1_100)
        runCurrent()
        return case
    }

    @Test
    fun `a host that jumps waits there until the followers have buffered`() = runTest {
        val case = playingHostWithFollower()
        val effects = mutableListOf<TogetherEffect>()
        backgroundScope.launch { case.manager.effects.collect { effects += it } }
        val player = case.suite.player

        player.seekTo(60_000)
        advanceTimeBy(1_100)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)

        // 成员跟过去了, 还在缓冲: 接着等; 等过 1 秒才提示
        pushOthersWatching(case, hostWatching(positionMillis = 60_000, paused = true, buffering = true), version = 3)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)
        assertTrue(TogetherEffect.WaitingForMembers(afterSeek = true) in effects, "$effects")

        // 缓冲好了: 接着播
        pushOthersWatching(case, hostWatching(positionMillis = 60_000, paused = true), version = 4)
        assertTrue(player.state.value.playWhenReady)
        assertEquals(60_000L, player.currentPositionMillis.value)

        // 接着播之后不当成又跳了一次
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(player.state.value.playWhenReady)
    }

    @Test
    fun `a host that jumps plays on when the followers take too long`() = runTest {
        val case = playingHostWithFollower()
        val player = case.suite.player
        player.seekTo(60_000)
        advanceTimeBy(1_100)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)
        // 从跳的那一刻起算: 已经过了 1.1 秒
        advanceTimeBy(TogetherPlayerExtension.SEEK_HOLD_TIMEOUT_MILLIS - 2_000)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(player.state.value.playWhenReady)
    }

    @Test
    fun `a host alone in the room seeks without stopping`() = runTest {
        val case = createCase(asHost = true, others = 0)
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        case.suite.player.seekTo(60_000)
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(case.suite.player.state.value.playWhenReady)
    }

    @Test
    fun `followers ready counts only following members who are online`() {
        val loaded = TogetherWatchingInfo(
            subjectId = 1, episodeId = 2, subjectName = "S", episodeSort = "01", episodeName = "E",
            positionMillis = 0, positionAtMillis = 0, durationMillis = 100_000, paused = true,
        )
        fun snapshot(vararg others: TogetherMember) = TogetherRoomSnapshot(
            version = 1, status = TogetherRoomStatus.OPEN, hostId = "host", roomTime = 0,
            members = listOf(member("host", isHost = true)) + others,
        )
        val ready = member("a", isHost = false).copy(state = TogetherMemberState.WATCHING, watching = loaded)
        assertTrue(followersReady(snapshot(), 1, 2))
        assertTrue(followersReady(snapshot(ready), 1, 2))
        // 还在加载、在缓冲、在别的集、还没进播放页: 没好
        assertFalse(followersReady(snapshot(ready, ready.copy(memberId = "b", watching = loaded.copy(loading = true))), 1, 2))
        assertFalse(followersReady(snapshot(ready.copy(watching = loaded.copy(buffering = true))), 1, 2))
        assertFalse(followersReady(snapshot(ready.copy(watching = loaded.copy(episodeId = 3))), 1, 2))
        assertFalse(followersReady(snapshot(member("a", isHost = false)), 1, 2))
        // 自由观看的、掉线的不等
        assertTrue(followersReady(snapshot(ready, member("b", isHost = false, following = false)), 1, 2))
        assertTrue(followersReady(snapshot(ready, member("c", isHost = false).copy(state = TogetherMemberState.DISCONNECTED)), 1, 2))
        // 房主跳了位置: 停在他前后 3 秒以内才算跟到了
        assertTrue(followersReady(snapshot(ready.copy(watching = loaded.copy(positionMillis = 62_000))), 1, 2, nearPositionMillis = 60_000))
        assertFalse(followersReady(snapshot(ready), 1, 2, nearPositionMillis = 60_000))

        // 等满了还没好的写进日志: 成员编号的开头与在做什么, 不写昵称
        val loading = ready.copy(memberId = "member-b-123456", nickname = "Nick", watching = loaded.copy(loading = true))
        val notReady = followersNotReady(snapshot(ready, loading, member("c", isHost = false)), 1, 2)
        assertEquals(listOf("member-b", "c"), notReady.map { it.memberId.take(8) })
        assertEquals("member-b: episode 2 at 0 ms, loading, c: nothing open", describeMembers(notReady))
    }

    @Test
    fun `a host does not stop for a jump the followers are already near or one made while paused`() = runTest {
        val case = playingHostWithFollower()
        val player = case.suite.player
        // 跳得很近 (成员在 0 秒, 落点 2 秒): 不停
        player.seekTo(2_000)
        runCurrent()
        assertTrue(player.state.value.playWhenReady)

        // 停着跳、跳过去还停着: 不停也不替他开
        player.pause()
        runCurrent()
        player.seekTo(60_000)
        advanceTimeBy(TogetherPlayerExtension.SEEK_HOLD_TIMEOUT_MILLIS + 1_000)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)
    }

    @Test
    fun `a host that jumps again while waiting waits at the new place`() = runTest {
        val case = playingHostWithFollower()
        val player = case.suite.player
        player.seekTo(60_000)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)

        // 等的时候又往后跳了 (片长 100 秒): 成员停在第一个落点不算到了
        player.seekTo(90_000)
        runCurrent()
        pushOthersWatching(case, hostWatching(positionMillis = 60_000, paused = true), version = 3)
        assertFalse(player.state.value.playWhenReady)
        pushOthersWatching(case, hostWatching(positionMillis = 90_000, paused = true), version = 4)
        assertTrue(player.state.value.playWhenReady)
    }

    @Test
    fun `a host alone in the room just plays`() = runTest {
        val case = createCase(asHost = true, others = 0)
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(case.suite.player.state.value.playWhenReady)
        assertFalse(assertNotNull(case.bridge.localWatching.value).paused)
    }

    @Test
    fun `a host does not wait for members who are not following`() = runTest {
        val case = createCase(asHost = true, others = 1, othersFollowing = false)
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(case.suite.player.state.value.playWhenReady)
    }

    @Test
    fun `outside a room the player is left alone`() = runTest {
        val case = createCase()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(case.suite.player.state.value.playWhenReady)
    }

    @Test
    fun `without the feature registered the extension does nothing`() = runTest {
        val case = createCase(registerTogether = false)
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(case.suite.player.state.value.playWhenReady)
        assertNull(case.bridge.localWatching.value)
    }

    // ---- 跟随房主 ----

    @Test
    fun `a follower opens the episode where the host is and takes over its pause`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 40_000, paused = true))
        case.manager.join(ROOM)
        runCurrent()

        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(40_000L, case.suite.player.currentPositionMillis.value)
        assertFalse(case.suite.player.state.value.playWhenReady)
    }

    @Test
    fun `a follower joins a playing host at its extrapolated position`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 40_000))
        case.manager.join(ROOM)
        runCurrent()
        advanceTimeBy(10_000)

        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        // 房主 10 秒前在 40 秒, 现在应在 50 秒上下
        val position = case.suite.player.currentPositionMillis.value
        assertTrue(position in 50_000L..53_000L, "position=$position")
        assertTrue(case.suite.player.state.value.playWhenReady)
    }

    @Test
    fun `a follower takes the host pause speed and far jumps`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 0))
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        val player = case.suite.player
        assertTrue(player.state.value.playWhenReady)

        // 房主暂停在 30 秒
        pushHost(case, hostWatching(positionMillis = 30_000, paused = true), version = 2)
        advanceTimeBy(1_500)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)
        assertEquals(30_000L, player.currentPositionMillis.value)

        // 房主两倍速接着播
        pushHost(case, hostWatching(positionMillis = 30_000, playbackRate = 2f), version = 3)
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(player.state.value.playWhenReady)
        assertEquals(2f, player.features[PlaybackSpeed]?.value)
    }

    @Test
    fun `a follower buffering when the host ends fast forward goes back to normal speed once it plays`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 0))
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        val player = case.suite.player
        player.injectPosition(10_000)

        // 房主长按确定键加速 (2.5 倍): 成员跟着
        pushHost(case, hostWatching(positionMillis = 10_000, playbackRate = 2.5f), version = 2)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2.5f, player.nativePlaybackRate)

        // 成员正卡在缓冲上时房主松手: 这一下播放器不下发
        player.injectStall(true)
        runCurrent()
        pushHost(case, hostWatching(positionMillis = 10_000), version = 3)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1f, player.features[PlaybackSpeed]?.value)
        assertEquals(2.5f, player.nativePlaybackRate)

        // 缓冲好了: 真的回到 1 倍
        player.injectStall(false)
        runCurrent()
        assertEquals(1f, player.nativePlaybackRate)
    }

    @Test
    fun `a follower left behind by a fast forward is lined up when the host is back to normal speed`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 0))
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        val player = case.suite.player

        // 房主长按加速: 成员落后 2 秒也不跳 (只跟倍速)
        player.injectPosition(8_000)
        pushHost(case, hostWatching(positionMillis = 10_000, playbackRate = 2.5f), version = 2)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(8_000L, player.currentPositionMillis.value)
        assertEquals(2.5f, player.features[PlaybackSpeed]?.value)

        // 房主松手: 改倍速本来就要停一下, 顺带对齐, 不留下这两秒
        pushHost(case, hostWatching(positionMillis = 20_000), version = 3)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(player.currentPositionMillis.value >= 20_000L, "jumped to ${player.currentPositionMillis.value}")
        assertEquals(1f, player.features[PlaybackSpeed]?.value)
    }

    @Test
    fun `a host change while a jump is still being measured is followed at once`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 0))
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        val player = case.suite.player

        // 房主跳到 60 秒, 成员跳过去卡在缓冲上: 这一轮在等播起来量落点
        player.injectStall(true)
        runCurrent()
        pushHost(case, hostWatching(positionMillis = 60_000), version = 2)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(case.bridge.alignInProgress.value)

        // 量的途中房主开了 2 倍速: 当场跟, 不等那一轮量完 (缓冲可能要好几秒)
        pushHost(case, hostWatching(positionMillis = 60_200, playbackRate = 2f), version = 3)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2f, player.features[PlaybackSpeed]?.value)
    }

    @Test
    fun `a follower just stops when the host pauses and plays on without jumping`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 10_000))
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        val player = case.suite.player
        assertTrue(player.state.value.playWhenReady)
        val stoppedAt = player.currentPositionMillis.value

        // 房主停在 1 秒前 (消息路上走了 1 秒): 成员只停下, 不往回跳
        pushHost(case, hostWatching(positionMillis = stoppedAt - 1_000, paused = true), version = 2)
        advanceTimeBy(500)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)
        assertEquals(stoppedAt, player.currentPositionMillis.value)

        // 房主接着播, 消息同样晚了将近 1 秒到: 两段延迟抵掉, 成员直接接着播
        pushHost(case, hostWatching(positionMillis = stoppedAt - 1_000).copy(positionAtMillis = currentTime - 800), version = 3)
        advanceTimeBy(500)
        runCurrent()
        assertTrue(player.state.value.playWhenReady)
        assertEquals(stoppedAt, player.currentPositionMillis.value)
    }

    @Test
    fun `a follower whose player is not in front is left paused until they come back`() = runTest {
        var inFront = true
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 0), playerInFront = { inFront })
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        val player = case.suite.player
        assertTrue(player.state.value.playWhenReady)

        // 退出了播放页: 会话留在后台, 被按成暂停
        inFront = false
        player.pause()
        runCurrent()
        // 房主接着播, 还拖到了别处: 留在后台的播放器不动
        pushHost(case, hostWatching(positionMillis = 60_000), version = 2)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)
        assertTrue(player.currentPositionMillis.value < 10_000, "${player.currentPositionMillis.value}")

        // 回到播放页: 下一秒就对上
        inFront = true
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(player.state.value.playWhenReady)
        assertTrue(player.currentPositionMillis.value >= 60_000, "${player.currentPositionMillis.value}")
    }

    @Test
    fun `a follower does not wait for a host that is offline`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 0, paused = true))
        case.manager.join(ROOM)
        runCurrent()
        // 房主掉线了: 他最后的状态是暂停, 但没有人可等
        val snapshot = case.room.initialSnapshot
        case.room.eventChannel.trySend(
            TogetherRoomEvent.Snapshot(
                snapshot.copy(members = snapshot.members.map { if (it.isHost) it.copy(state = TogetherMemberState.DISCONNECTED) else it }),
            ),
        )
        runCurrent()

        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(case.suite.player.state.value.playWhenReady)
    }

    @Test
    fun `a follower within three seconds of a playing host is not moved`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 0))
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        val player = case.suite.player

        // 假播放器的位置不会自己走: 摆在房主后面 2 秒
        player.injectPosition(currentTime - 2_000)
        advanceTimeBy(1_100)
        runCurrent()
        val before = player.currentPositionMillis.value
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(before, player.currentPositionMillis.value)
    }

    @Test
    fun `a follower waits while the host keeps buffering and resumes with it`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 0))
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        val player = case.suite.player
        player.injectPosition(20_000)

        // 房主卡在 20 秒
        pushHost(case, hostWatching(positionMillis = 20_000, buffering = true), version = 2)
        advanceTimeBy(1_000)
        runCurrent()
        // 还没等满两秒: 成员照常
        assertTrue(player.state.value.playWhenReady)

        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)
        assertEquals(20_000L, player.currentPositionMillis.value)

        // 等的时候不反复折腾
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(player.state.value.playWhenReady)

        // 房主好了
        pushHost(case, hostWatching(positionMillis = 20_000), version = 3)
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(player.state.value.playWhenReady)
    }

    @Test
    fun `a blip of host buffering does not pause the follower`() = runTest {
        val case = createCase(asHost = false, hostPlayback = hostWatching(positionMillis = 0))
        case.manager.join(ROOM)
        runCurrent()
        val state = case.suite.start()
        runCurrent()
        loadSelectedMedia(case.suite, state)
        advanceTimeBy(1_100)
        runCurrent()
        val player = case.suite.player
        player.injectPosition(20_000)

        pushHost(case, hostWatching(positionMillis = 20_000, buffering = true), version = 2)
        advanceTimeBy(800)
        pushHost(case, hostWatching(positionMillis = 20_000), version = 3)
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(player.state.value.playWhenReady)
    }

    @Test
    fun `the first measured jump sets the seek lead`() {
        // 没多跳、播起来后落后 1.8 秒: 这次跳花了 1.8 秒
        assertEquals(1_800, nextSeekLead(previousMillis = 0, leadMillis = 0, behindMillis = 1_800))
        // 多跳了 2 秒、播起来时超前 0.5 秒: 实际只花了 1.5 秒
        assertEquals(1_500, nextSeekLead(previousMillis = 0, leadMillis = 2_000, behindMillis = -500))
        // 不往回跳, 也不超过上限
        assertEquals(0, nextSeekLead(previousMillis = 0, leadMillis = 300, behindMillis = -900))
        assertEquals(MAX_SEEK_LEAD_MILLIS, nextSeekLead(previousMillis = 0, leadMillis = 4_000, behindMillis = 9_000))
    }

    @Test
    fun `later jumps are averaged into the seek lead`() {
        // 工单 54 那台电视: 估计 1.07 秒, 跳到没缓冲的地方花了 2.25 秒 → 记 1.66 秒, 不是整个换成 2.25 秒
        assertEquals(1_660, nextSeekLead(previousMillis = 1_070, leadMillis = 1_070, behindMillis = 1_180))
        // 落点正好: 估计不变
        assertEquals(1_800, nextSeekLead(previousMillis = 1_800, leadMillis = 1_800, behindMillis = 0))
    }

    @Test
    fun `the seek lead is kept at normal speed while the host plays faster`() {
        // 房主 2.5 倍速: 多跳了 2.5 秒画面、正好落在房主那里, 这次跳花的是 1 秒
        assertEquals(1_000, nextSeekLead(previousMillis = 0, leadMillis = 2_500, behindMillis = 0, playbackRate = 2.5f))
    }

    @Test
    fun `a follow-up jump that overshoots is not followed by another`() {
        // 差得不到 1 秒不补
        assertFalse(shouldJumpAgain(behindMillis = 800, previousBehindMillis = null, jumps = 1))
        assertFalse(shouldJumpAgain(behindMillis = -1_000, previousBehindMillis = null, jumps = 1))
        // 第一跳落后得多: 补
        assertTrue(shouldJumpAgain(behindMillis = 1_180, previousBehindMillis = null, jumps = 1))
        // 补跳还落在同一边: 再补
        assertTrue(shouldJumpAgain(behindMillis = 1_500, previousBehindMillis = 2_400, jumps = 2))
        // 补跳过了头 (落后变超前): 停
        assertFalse(shouldJumpAgain(behindMillis = -1_227, previousBehindMillis = 1_180, jumps = 2))
        // 跳满了: 停
        assertFalse(shouldJumpAgain(behindMillis = 1_500, previousBehindMillis = 2_400, jumps = MAX_ALIGN_JUMPS))
    }

    private companion object {
        val ROOM = TogetherRoomId("48271593", "123456")
    }
}
