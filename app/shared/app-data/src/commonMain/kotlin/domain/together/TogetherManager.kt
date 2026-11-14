/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.preference.RememberedTogetherRoom
import me.him188.ani.app.data.models.preference.TogetherRecentRoom
import me.him188.ani.app.data.models.preference.TogetherSettings
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.navigation.EpisodeNavigationGuardHandle
import me.him188.ani.app.navigation.EpisodeNavigationGuardRegistry
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

sealed interface TogetherState {
    data object Idle : TogetherState

    /**
     * 正在进 [number] 这个房间 (开自己的、加入别人的、应用重开后回去)。
     *
     * @param handoff 房主换了人, 正以新的身份回到同一个房间 (让出房主的当成员回去, 被请去的当房主重开; 要重新连代理, 慢的设备上要几秒)。
     *   界面上说「正在切换房主」, 不当成没在房间里
     */
    data class Joining(val number: String, val handoff: Boolean = false) : TogetherState
    data class InRoom(val session: TogetherSession) : TogetherState
}

enum class TogetherConnectionState { CONNECTED, RECONNECTING }

/** 在一个房间里的这一段 */
class TogetherSession internal constructor(
    internal val room: TogetherRoom,
    /** 房间号与密码 */
    val roomId: TogetherRoomId,
    initialFollowing: Boolean,
    /** 房间号是这台设备的设备号 (自己开的房间)。被请来临时当房主的不是: 密码只有房间的主人能换 */
    val ownsRoom: Boolean = false,
) {
    val isHost: Boolean get() = room.isHost

    /** 自己是房主时请了谁来当房主、还在等他接手 (见 [TogetherManager.transferHost]) */
    internal val transferringTo = MutableStateFlow<String?>(null)

    /** 请了谁来当房主、还在等他接手; 没有为 null */
    val pendingTransfer: StateFlow<String?> get() = transferringTo

    /** 自己在房间里的编号 */
    val selfId: String get() = room.selfId
    val clock: TogetherRoomClock get() = room.clock

    private val _snapshot = MutableStateFlow(room.initialSnapshot)
    val snapshot: StateFlow<TogetherRoomSnapshot> = _snapshot.asStateFlow()

    private val _connection = MutableStateFlow(TogetherConnectionState.CONNECTED)
    val connection: StateFlow<TogetherConnectionState> = _connection.asStateFlow()

    private val _following = MutableStateFlow(initialFollowing)

    /** 「跟随房主播放」; 房主自己的这一项不起作用 */
    val following: StateFlow<Boolean> = _following.asStateFlow()

    /** 最近一次告诉房间的自己的状态; `null` = 说的是不在播放页 */
    internal val lastReportedWatching = atomic<TogetherWatchingInfo?>(null)
    internal val hasReported = atomic(false)

    /** 应用在后台时该把人带去房主那一集而没带, 回到前台补上 */
    internal val guidancePending = atomic(false)

    /** 最近一次看到房主自己跳了位置的时刻 (本机时间); 跟着他跳的那一下不算「重新同步」, 不提示 */
    internal val hostJumpedAtMillis = atomic(Long.MIN_VALUE / 2)
    private val closing = atomic(false)

    internal fun beginClosing(): Boolean = closing.compareAndSet(expect = false, update = true)
    internal val isClosing: Boolean get() = closing.value

    internal fun updateSnapshot(snapshot: TogetherRoomSnapshot) {
        _snapshot.value = snapshot
    }

    internal fun updateConnection(connection: TogetherConnectionState) {
        _connection.value = connection
    }

    internal fun updateFollowing(following: Boolean) {
        _following.value = following
    }
}

sealed interface TogetherEffect {
    /**
     * 要打开房主那一集 (栈里已经有播放页的话先退掉它)。
     *
     * @param inBackground 为真时不进播放页, 只在后台把那一集准备好: 房主自己也只在后台准备着 (见 [TogetherWatchingInfo.onScreen])
     */
    data class Navigate(
        val subjectId: Int,
        val episodeId: Int,
        val subjectName: String? = null,
        val episodeSort: String? = null,
        val inBackground: Boolean = false,
    ) : TogetherEffect

    /** 房主关掉了房间; [passwordChanged] = 他换了密码 (之前没收到关房间的消息、回去时被拒也算) */
    data class RoomClosed(val passwordChanged: Boolean) : TogetherEffect

    /** 应用重开后回到了上次的房间。[own] = 自己的房间 (重新开了) */
    data class Rejoined(val own: Boolean, val room: TogetherRoomId) : TogetherEffect

    /** 为跟上房主跳了一次位置; [deltaMillis] 正 = 往前跳 */
    data class ResyncedWithHost(val deltaMillis: Long) : TogetherEffect

    /** 房主卡在缓冲 / 加载上, 本机的画面停下来等他 */
    data object WaitingForHost : TogetherEffect

    /** 房主停着等跟随的成员: 这一集加载完停在开头等他们加载好, 或 ([afterSeek]) 自己跳了位置后等他们在新位置缓冲好 */
    data class WaitingForMembers(val afterSeek: Boolean = false) : TogetherEffect

    /** 自己的片源跟不上房主: 不再一次次往前跳, 落后着播 (见 [TogetherFollowerPace]); 每次认定跟不上时发一次 */
    data object FallingBehind : TogetherEffect

    /** 房间里的人有变化 (不含自己) */
    data class MembersChanged(val changes: List<TogetherMemberChange>) : TogetherEffect

    /** 房主换了人, [host] 是新房主: 自己让给了他、房主让给了他, 或原来的房主不在了换成他 */
    data class HostChanged(val host: TogetherMember) : TogetherEffect

    /** 房主请自己来当房主, 已经接手 */
    data object BecameHost : TogetherEffect

    /** 请 [memberId] 来当房主, 等不到他接手, 自己还是房主; [member] 是名单里的他, 已经不在名单里时为 null */
    data class TransferFailed(val memberId: String, val member: TogetherMember?) : TogetherEffect
}

data class TogetherMemberChange(val member: TogetherMember, val kind: Kind) {
    enum class Kind {
        JOINED,
        LEFT,

        /** 连接异常断开, 人还留在名单里 */
        DROPPED,

        /** 掉线的人回来了 */
        RETURNED,
    }
}

/** 两份快照之间房间里的人有什么变化, 不算 [selfId] 自己。掉线的人被从名单里去掉不算离开 (早就说过掉线了) */
internal fun memberChanges(before: TogetherRoomSnapshot, after: TogetherRoomSnapshot, selfId: String): List<TogetherMemberChange> {
    val old = before.members.associateBy { it.memberId }
    val new = after.members.associateBy { it.memberId }
    val changes = ArrayList<TogetherMemberChange>()
    for (member in after.members) {
        if (member.memberId == selfId) continue
        val previous = old[member.memberId]
        val wasDown = previous?.state == TogetherMemberState.DISCONNECTED
        val isDown = member.state == TogetherMemberState.DISCONNECTED
        when {
            previous == null -> if (!isDown) changes += TogetherMemberChange(member, TogetherMemberChange.Kind.JOINED)
            !wasDown && isDown -> changes += TogetherMemberChange(member, TogetherMemberChange.Kind.DROPPED)
            wasDown && !isDown -> changes += TogetherMemberChange(member, TogetherMemberChange.Kind.RETURNED)
        }
    }
    for (member in before.members) {
        if (member.memberId == selfId || member.memberId in new) continue
        if (member.state != TogetherMemberState.DISCONNECTED) changes += TogetherMemberChange(member, TogetherMemberChange.Kind.LEFT)
    }
    return changes
}

/** 自己在房间里显示的名字 */
data class TogetherProfile(val nickname: String)

fun interface TogetherProfileProvider {
    suspend fun current(): TogetherProfile

    /**
     * 名字变了 (应用刚启动时账号信息还没读出来, 读出来了; 换了账号) 时发出。在房间里时据此把自己的状态再报一次:
     * 房主只在播放状态变化时才报, 不然空闲着的房主一直显示成「观众 xxxx」。
     */
    fun changes(): Flow<TogetherProfile> = emptyFlow()
}

/**
 * 一起看的总管: 进出房间, 把本机的播放状态告诉房间, 跟随房主时把房主的状态变成给播放页的指令。
 *
 * 行为照上游的一起看 (房主的播放状态就是房间的; 跟随的成员被带到房主那一集, 每秒对一次位置 / 播放暂停 / 倍速,
 * 不能自己进别的播放页; 关掉「跟随房主播放」是自由观看)。与它不同的是没有服务器, 所以:
 *
 * - **只在状态变化时告诉房间**: 上一次说的状态还推得出现在的状态 (同一集、播放暂停缓冲倍速没变、位置差在
 *   [REPORT_TOLERANCE_MILLIS] 内) 就不说。没有定时上报。
 * - 房间的事 (谁是房主、名单、时间) 都在 [TogetherRoomGateway] 那一层。
 * - 记住的房间超过 [REMEMBER_MILLIS] 没回去就忘掉: 没有服务器替房间过期, 不然应用每次启动都回到一个早就没人的房间。
 * - **跟随只对着眼前的播放页做**: 电视退出播放页后播放会话还留在后台 (被按成暂停), 应用退到后台时播放器也被按停。
 *   这两种时候都不去纠正它。人去了别的页面时房主换集按「不在播放页」处理, 把人带进那一集;
 *   应用在后台时跟随的事一件都不做, 回到前台再跟上。
 * - **房主掉线期间不跟随**: 没有可跟的状态, 也不拦着成员自己去看别的; 房主回来再接上。
 */
class TogetherManager(
    private val scope: CoroutineScope,
    private val gateway: TogetherRoomGateway,
    private val settings: Settings<TogetherSettings>,
    private val profile: TogetherProfileProvider,
    private val playbackBridge: TogetherPlaybackBridge,
    private val automationGate: PlaybackAutomationGate,
    private val localNowMillis: () -> Long = ::currentTimeMillis,
    private val newMemberId: () -> String = ::randomTogetherMemberId,
    private val newDigits: (count: Int) -> String = ::randomTogetherDigits,
) {
    private val started = atomic(false)
    private val operationMutex = Mutex()
    private val reportMutex = Mutex()

    private val _state = MutableStateFlow<TogetherState>(TogetherState.Idle)
    val state: StateFlow<TogetherState> = _state

    // 给界面的指示不排队等人: 界面不在的时候 (界面被系统收走、进程还在) 只留最近几条, 发的一方从不被卡住
    private val effectChannel = Channel<TogetherEffect>(capacity = EFFECT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 给界面的提示 ([TogetherEffect.Navigate] 不在这里, 见 [navigations]) */
    val effects: Flow<TogetherEffect> = effectChannel.receiveAsFlow()

    private val navigationChannel = Channel<TogetherEffect.Navigate>(Channel.CONFLATED)

    /**
     * 「去房主那一集」的指示。与 [effects] 分开取: 只留最新的一条, 不会被后来的提示挤掉;
     * 取的一方还没准备好 (界面刚起来) 时它留在这里不丢。
     */
    val navigations: Flow<TogetherEffect.Navigate> = navigationChannel.receiveAsFlow()

    /** 最近进过的别人的房间, 最近的在前 (见 TogetherRecentRooms.kt) */
    val recentRooms: StateFlow<List<TogetherRecentRoom>> =
        settings.flow.map { it.recentRoomList }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var sessionJob: Job? = null
    private var autoJoinJob: Job? = null
    private var navigationGuard: EpisodeNavigationGuardHandle? = null

    /** 应用启动后调一次: 回到上次没离开的房间, 并让「跟随房主播放」的设置对正在进行的房间生效 */
    fun start() {
        if (!started.compareAndSet(expect = false, update = true)) return
        scope.launch(CoroutineName("TogetherManager.lifecycle")) {
            val remembered = settings.flow.first().rememberedRoom
            if (remembered != null) {
                val valid = TogetherRoomId.of(remembered.number, remembered.password) != null
                if (!valid || localNowMillis() - remembered.joinedAt > REMEMBER_MILLIS) clearRememberedRoom() else scheduleAutoJoin(announce = true)
            }
            settings.flow.map { it.followHost }.distinctUntilChanged().collect { followHost ->
                (_state.value as? TogetherState.InRoom)?.session?.updateFollowing(followHost)
            }
        }
    }

    /**
     * 加入 [room] 这个房间, 当成员。房间里没有房主时失败 ([TogetherRoomEmptyException]), 不会自己当房主;
     * 房主说密码不对时失败 ([TogetherWrongPasswordException])。房间号是本机的设备号时等于开自己的房间 (密码要对)。
     *
     * 在总管自己的作用域里进行: 发起的界面中途没了 (弹窗关掉) 也照样进完。
     */
    suspend fun join(room: TogetherRoomId): Result<Unit> = scope.async {
        cancelAutoJoin()
        val own = ownRoom()
        when {
            room.number != own.number -> joinInternal(room, asHost = false, silent = false)
            room.password == own.password -> joinInternal(own, asHost = true, silent = false)
            else -> Result.failure(TogetherWrongPasswordException())
        }
    }.await()

    /**
     * 开自己的房间: 房间号是本机的设备号, 密码是现在的密码 (见 [ownRoom]), 自己是房主。
     *
     * @return 房间号与密码
     */
    suspend fun createRoom(): Result<TogetherRoomId> = scope.async {
        cancelAutoJoin()
        val own = ownRoom()
        joinInternal(own, asHost = true, silent = false).map { own }
    }.await()

    /**
     * 用记下的密码再进最近的房间 [number] (见 [recentRooms]); 不在最近的房间里时失败。
     * 房主换过密码时以 [TogetherWrongPasswordException] 失败, 记下的不动: 用新密码进成了就换掉。
     */
    suspend fun joinRecent(number: String): Result<Unit> {
        val recent = settings.flow.first().recentRoomList.firstOrNull { it.number == number }
        val room = recent?.let { TogetherRoomId.of(it.number, it.password) }
            ?: return Result.failure(NoSuchElementException("Not a recent room: $number"))
        return join(room)
    }

    /** 从最近的房间里去掉 [number] */
    suspend fun forgetRecentRoom(number: String) {
        settings.update { copy(recentRooms = recentRoomList.without(number), lastJoined = null) }
    }

    /** 停掉自动回房间; 换房主后正要重新进房的那一下 ([TogetherState.Joining.handoff]) 被打断了就回到空闲 */
    private suspend fun cancelAutoJoin() {
        autoJoinJob?.cancelAndJoin()
        autoJoinJob = null
        _state.update { if (it is TogetherState.Joining && it.handoff) TogetherState.Idle else it }
    }

    /**
     * 自己房间换一个随机密码。开着自己的房间时先关掉 (成员收到「房主换了密码」, 要用新密码重新加入), 再用新密码重开。
     *
     * @return 新的房间号与密码
     */
    suspend fun changePassword(): Result<TogetherRoomId> = scope.async {
        val session = (_state.value as? TogetherState.InRoom)?.session
        // 被请来临时当房主的: 房间是别人的, 密码只有房间的主人能换
        if (session != null && session.isHost && !session.ownsRoom) {
            // 不用 IllegalStateException: 控制台把它当成「已在房间中」
            return@async Result.failure(UnsupportedOperationException("Only the owner of the room can change its password"))
        }
        val hosting = session?.isHost == true
        if (hosting) leaveNow(TogetherCloseReason.PASSWORD_CHANGED)
        val password = newDigits(TOGETHER_ROOM_PASSWORD_DIGITS)
        settings.update { copy(roomPassword = password) }
        val own = ownRoom()
        if (hosting) joinInternal(own, asHost = true, silent = false).map { own } else Result.success(own)
    }.await()

    /** 这台设备自己的房间: 房间号是设备号, 密码是现在的密码。第一次用时生成 */
    suspend fun ownRoom(): TogetherRoomId {
        val config = ensureIdentity()
        return TogetherRoomId(config.deviceNumber, config.roomPassword)
    }

    /**
     * 离开房间; 房主离开 = 解散。
     *
     * 在总管自己的作用域里做: 等的一方不等了 (界面没了、超时) 收尾也照样做完, 不会停在「已经空闲、但会话的东西还没收拾」的半路上。
     */
    suspend fun leave() {
        // 作用域已经结束时 (应用在退出) 就地做
        if (!scope.isActive) return leaveNow()
        scope.async { leaveNow() }.await()
    }

    private suspend fun leaveNow(reason: TogetherCloseReason = TogetherCloseReason.DISBANDED) {
        autoJoinJob?.cancelAndJoin()
        autoJoinJob = null
        operationMutex.withLock {
            val session = (_state.value as? TogetherState.InRoom)?.session
            // 先让这个会话的协程认出「已经不是当前会话」, 再停掉它们、复位它们写过的东西
            session?.beginClosing()
            _state.value = TogetherState.Idle
            stopSessionResources()
            try {
                clearRememberedRoom()
            } finally {
                if (session != null) {
                    runCatching { session.room.leave(reason) }
                        .onFailure { logger.debug { "Failed to leave the room: ${it.message}" } }
                }
            }
        }
    }

    /**
     * 自己是房主时请房间里的 [memberId] 来当房主 (临时的: 房间号与密码不变, 密码仍只有房间的主人能换)。
     * 他接手后自己变成成员; 结果经 [effects] 说: 成了是 [TogetherEffect.HostChanged], 等不到是 [TogetherEffect.TransferFailed]。
     * 不是房主、请的是自己或掉线的人时什么都不做。
     */
    suspend fun transferHost(memberId: String) {
        val session = (_state.value as? TogetherState.InRoom)?.session ?: return
        if (!session.isHost || memberId == session.selfId || !isCurrent(session)) return
        val member = session.snapshot.value.members.firstOrNull { it.memberId == memberId } ?: return
        if (member.state == TogetherMemberState.DISCONNECTED) return
        session.transferringTo.value = memberId
        session.room.transferHost(memberId)
    }

    suspend fun setFollowing(following: Boolean) {
        val session = operationMutex.withLock {
            val current = (_state.value as? TogetherState.InRoom)?.session
            current?.updateFollowing(following)
            settings.update { copy(followHost = following) }
            current
        }
        if (session != null) report(session, force = true)
    }

    /**
     * 早先发出的「去房主那一集」现在还作不作数: 还在跟随, 房主也还在那一集。
     * 界面不在的时候这类指示会攒着, 等界面回来时房主多半已经换了集。
     */
    fun shouldStillNavigate(subjectId: Int, episodeId: Int): Boolean {
        if (!isFollowingHost()) return false
        val session = (_state.value as? TogetherState.InRoom)?.session ?: return false
        val host = session.snapshot.value.playback?.info ?: return false
        return host.subjectId == subjectId && host.episodeId == episodeId
    }

    /** 此刻是不是在跟着房主: 在房间里、开着「跟随房主播放」、自己不是房主、房主也没掉线 */
    fun isFollowingHost(): Boolean = (_state.value as? TogetherState.InRoom)?.session?.let { followingActive(it) } == true

    /** 房间时间; 不在房间里时是本机时间 */
    fun roomNowMillis(): Long = (_state.value as? TogetherState.InRoom)?.session?.clock?.now() ?: localNowMillis()

    /**
     * 回到记住的房间: 自己的房间 (以及被请去当房主的房间) 当房主重新开; 别人的房间当成员加入, 房主不在就隔一会儿再试 (他可能也在重开应用)。
     * 房主说密码不对 (他换过密码) 时不再试, 忘掉这个房间。
     *
     * @param promoted 被房主请去当房主: 开成了说一声
     */
    private fun scheduleAutoJoin(announce: Boolean, promoted: Boolean = false) {
        if (autoJoinJob?.isActive == true) return
        autoJoinJob = scope.launch(CoroutineName("TogetherManager.autoJoin")) {
            var retryDelayMillis = AUTO_JOIN_INITIAL_RETRY_MILLIS
            while (currentCoroutineContext().isActive) {
                val remembered = settings.flow.first().rememberedRoom ?: return@launch idleAfterHandoff()
                if (!idleOrHandoff()) return@launch
                val room = TogetherRoomId.of(remembered.number, remembered.password) ?: return@launch run {
                    idleAfterHandoff()
                    clearRememberedRoom()
                }
                val result = joinInternal(room, asHost = remembered.own, silent = true)
                if (result.isSuccess) {
                    if (promoted) {
                        effectChannel.send(TogetherEffect.BecameHost)
                    } else if (announce) {
                        effectChannel.send(TogetherEffect.Rejoined(own = remembered.own, room = room))
                    }
                    return@launch
                }
                if (result.exceptionOrNull() is TogetherWrongPasswordException) {
                    clearRememberedRoom()
                    effectChannel.send(TogetherEffect.RoomClosed(passwordChanged = true))
                    return@launch
                }
                delay(retryDelayMillis)
                retryDelayMillis = min(retryDelayMillis * 2, AUTO_JOIN_MAX_RETRY_MILLIS)
            }
        }
    }

    /** 空闲, 或换房主后正要重新进房 ([TogetherState.Joining.handoff], 那一下就是在等这里进) */
    private fun idleOrHandoff(): Boolean = _state.value.let { it is TogetherState.Idle || (it is TogetherState.Joining && it.handoff) }

    /** 换房主后没有房间可回: 回到空闲 */
    private fun idleAfterHandoff() {
        _state.update { if (it is TogetherState.Joining && it.handoff) TogetherState.Idle else it }
    }

    private suspend fun joinInternal(room: TogetherRoomId, asHost: Boolean, silent: Boolean): Result<Unit> {
        return operationMutex.withLock {
            if (!idleOrHandoff()) {
                return@withLock Result.failure(IllegalStateException("Already in a room or joining one"))
            }
            _state.value = TogetherState.Joining(room.number, handoff = (_state.value as? TogetherState.Joining)?.handoff == true)
            var entered: TogetherRoom? = null
            try {
                val config = ensureIdentity()
                val watching = localReport()
                val joined = gateway.join(room, config.memberId, selfReport(config.followHost, watching), asHost).also { entered = it }
                val session = TogetherSession(joined, room, initialFollowing = config.followHost, ownsRoom = room.number == config.deviceNumber)
                // 进房时的招呼里已经带了自己的状态
                session.lastReportedWatching.value = watching
                session.hasReported.value = true
                settings.update {
                    // 别人的房间记进最近的房间 (被请去临时当房主的也是别人的房间); 自己的房间不记, 它有「开启房间」
                    val othersRoom = room.number != config.deviceNumber
                    copy(
                        recentRooms = if (othersRoom) recentRoomList.withJoined(room, localNowMillis()) else recentRooms,
                        lastJoined = if (othersRoom) null else lastJoined,
                        rememberedRoom = RememberedTogetherRoom(room.number, room.password, own = asHost, joinedAt = localNowMillis()),
                    )
                }
                _state.value = TogetherState.InRoom(session)
                startSession(session)
                Result.success(Unit)
            } catch (throwable: Throwable) {
                // 被取消也要回到空闲, 不然永远停在「正在加入」
                _state.value = TogetherState.Idle
                // 房间已经进了、后面的步骤没走完: 退出来, 不留一个没人管的房间在后台连着
                withContext(NonCancellable) {
                    stopSessionResources()
                    entered?.let { room -> runCatching { room.leave() } }
                }
                currentCoroutineContext().ensureActive()
                if (!silent) logger.warn(throwable) { "Failed to join the room" }
                Result.failure(throwable)
            }
        }
    }

    /** 成员编号、设备号、自己房间的密码, 没有的生成并存下 */
    private suspend fun ensureIdentity(): TogetherSettings {
        val current = settings.flow.first()
        if (current.memberId.isNotEmpty() && TogetherRoomId.of(current.deviceNumber, current.roomPassword) != null) return current
        val id = newMemberId()
        val number = newDigits(TOGETHER_ROOM_NUMBER_DIGITS)
        val password = newDigits(TOGETHER_ROOM_PASSWORD_DIGITS)
        settings.update {
            copy(
                memberId = memberId.ifEmpty { id },
                deviceNumber = deviceNumber.takeIf { it.length == TOGETHER_ROOM_NUMBER_DIGITS } ?: number,
                roomPassword = roomPassword.takeIf { it.length == TOGETHER_ROOM_PASSWORD_DIGITS } ?: password,
            )
        }
        return settings.flow.first()
    }

    private suspend fun selfReport(following: Boolean, watching: TogetherWatchingInfo?): TogetherSelfReport {
        val me = profile.current()
        return TogetherSelfReport(nickname = me.nickname, following = following, watching = watching)
    }

    private suspend fun startSession(session: TogetherSession) {
        stopSessionResources()
        sessionJob = scope.launch(CoroutineName("TogetherRoom")) {
            supervisorScope {
                launch(CoroutineName("events")) { runEvents(session) }
                launch(CoroutineName("report-discontinuities")) {
                    playbackBridge.discontinuities.collect { report(session, force = false) }
                }
                launch(CoroutineName("report-on-screen")) {
                    // 进出播放页时画面可能一点没变 (停着进出): 成员要据此决定是进播放页, 还是只在后台准备
                    playbackBridge.playerOnScreenChanges.collect { report(session, force = false) }
                }
                launch(CoroutineName("report-profile")) {
                    // 名字晚到 (进房时账号信息还没读出来) 或换了: 再报一次, 别人看到的名字才对
                    profile.changes().distinctUntilChanged().collect { latest ->
                        if (session.snapshot.value.members.firstOrNull { it.memberId == session.selfId }?.nickname != latest.nickname) {
                            report(session, force = true)
                        }
                    }
                }
                launch(CoroutineName("report-drift")) {
                    // 位置一点点偏出去 (每次都不算跳变) 时也要说: 别人是照上一次说的往后推的
                    playbackBridge.localWatching.collect {
                        if (session.isHost) report(session, force = false)
                    }
                }
                launch(CoroutineName("follower-mode")) { observeFollowerMode(session) }
                launch(CoroutineName("guidance")) { observeGuidance(session) }
                launch(CoroutineName("local-deviation")) { observeLocalDeviation(session) }
                launch(CoroutineName("host-changes")) { observeHostChanges(session) }
                launch(CoroutineName("correction")) { runContinuousCorrection(session) }
                launch(CoroutineName("correction-toast")) { observeCorrectionToasts(session) }
                launch(CoroutineName("waiting-for-host")) {
                    playbackBridge.waitingForHost.collect {
                        if (!session.isHost && session.following.value && isCurrent(session)) effectChannel.send(TogetherEffect.WaitingForHost)
                    }
                }
                launch(CoroutineName("waiting-for-members")) {
                    playbackBridge.waitingForMembers.collect { afterSeek ->
                        if (session.isHost && isCurrent(session)) effectChannel.send(TogetherEffect.WaitingForMembers(afterSeek))
                    }
                }
                launch(CoroutineName("members")) { observeMembers(session) }
                launch(CoroutineName("recent-room-host")) { recordRecentHost(session) }
            }
        }
    }

    /** 停掉会话的协程并等它们停干净, 再复位它们写过的东西: 不等的话, 正要写的那一个会在复位之后再写一次 */
    private suspend fun stopSessionResources() {
        sessionJob?.cancelAndJoin()
        sessionJob = null
        navigationGuard?.dispose()
        navigationGuard = null
        automationGate.setSuppressed(false)
        playbackBridge.setTargetPlayback(null)
    }

    private suspend fun runEvents(session: TogetherSession) {
        session.room.events.collect { event ->
            if (!isCurrent(session)) return@collect
            when (event) {
                is TogetherRoomEvent.Connectivity -> session.updateConnection(
                    if (event.online) TogetherConnectionState.CONNECTED else TogetherConnectionState.RECONNECTING,
                )

                is TogetherRoomEvent.Snapshot -> {
                    session.updateSnapshot(event.snapshot)
                    if (event.snapshot.status == TogetherRoomStatus.CLOSED) {
                        scheduleRoomLoss(
                            session,
                            if (event.snapshot.closeReason == TogetherCloseReason.PASSWORD_CHANGED) RoomLoss.PASSWORD_CHANGED else RoomLoss.CLOSED,
                        )
                    }
                }

                // 另一台用着同一个房间号的设备 (数据搬到新电视、旧电视也还开着) 人多, 让给它; 或请来的房主接手了: 回去时当成员
                TogetherRoomEvent.HostChanged -> {
                    val invited = session.transferringTo.value?.let { id -> session.snapshot.value.members.firstOrNull { it.memberId == id } }
                    if (invited != null) effectChannel.send(TogetherEffect.HostChanged(invited))
                    scheduleRoomLoss(session, RoomLoss.YIELDED)
                }

                TogetherRoomEvent.Rejected -> scheduleRoomLoss(session, RoomLoss.PASSWORD_CHANGED)
                // 房主请自己来当房主: 以房主身份重开这个房间
                TogetherRoomEvent.PromotedToHost -> scheduleRoomLoss(session, RoomLoss.PROMOTED)
                is TogetherRoomEvent.TransferFailed -> {
                    session.transferringTo.value = null
                    val member = session.snapshot.value.members.firstOrNull { it.memberId == event.memberId }
                    effectChannel.send(TogetherEffect.TransferFailed(event.memberId, member))
                }
            }
        }
        // 房间那边自己结束了 (不是这里离开的): 重新进
        scheduleRoomLoss(session, RoomLoss.REJOIN)
    }

    /**
     * 把本机的状态告诉房间。
     *
     * @param force 为假时, 上一次说的还推得出现在的状态就不说
     */
    private suspend fun report(session: TogetherSession, force: Boolean) {
        if (!isCurrent(session)) return
        reportMutex.withLock {
            if (!isCurrent(session)) return@withLock
            val watching = localReport()
            if (!force && session.hasReported.value) {
                val last = session.lastReportedWatching.value
                val unchanged = if (last == null || watching == null) last == watching else last.predicts(watching, REPORT_TOLERANCE_MILLIS)
                if (unchanged) return@withLock
            }
            session.lastReportedWatching.value = watching
            session.hasReported.value = true
            try {
                session.room.report(selfReport(session.following.value, watching))
            } catch (throwable: Throwable) {
                currentCoroutineContext().ensureActive()
                logger.debug { "Failed to report to the room: ${throwable.message}" }
            }
        }
    }

    private fun scheduleRoomLoss(session: TogetherSession, loss: RoomLoss) {
        if (!isAttached(session) || !session.beginClosing()) return
        scope.launch { handleRoomLoss(session, loss) }
    }

    private suspend fun handleRoomLoss(session: TogetherSession, loss: RoomLoss) {
        operationMutex.withLock {
            if (!isAttached(session)) return
            // 换房主: 马上以新的身份回同一个房间, 这一阵界面上说「正在切换房主」, 不显示成没在房间里
            val handoff = loss == RoomLoss.YIELDED || loss == RoomLoss.PROMOTED
            _state.value = if (handoff) TogetherState.Joining(session.roomId.number, handoff = true) else TogetherState.Idle
            stopSessionResources()
            when (loss) {
                RoomLoss.CLOSED, RoomLoss.PASSWORD_CHANGED -> {
                    clearRememberedRoom()
                    effectChannel.send(TogetherEffect.RoomClosed(passwordChanged = loss == RoomLoss.PASSWORD_CHANGED))
                }

                RoomLoss.YIELDED -> {
                    carryRoster(session.snapshot.value)
                    settings.update { copy(rememberedRoom = rememberedRoom?.copy(own = false)) }
                }

                RoomLoss.PROMOTED -> {
                    carryRoster(session.snapshot.value)
                    settings.update { copy(rememberedRoom = rememberedRoom?.copy(own = true)) }
                }

                RoomLoss.REJOIN -> Unit
            }
        }
        if (loss == RoomLoss.REJOIN || loss == RoomLoss.YIELDED || loss == RoomLoss.PROMOTED) {
            scheduleAutoJoin(announce = false, promoted = loss == RoomLoss.PROMOTED)
        }
    }

    /**
     * 换房主时原来名单上的人 (见 [carryRoster]): 这一阵里他们重新进房间 (跟着去找新房主) 不算「加入」, 一时不在名单上也不算「离开」。
     * 换房主的那一端是新的会话, 所以记在总管上。
     */
    private class RosterCarry(val memberIds: Set<String>, val untilMillis: Long)

    private val rosterCarry = atomic<RosterCarry?>(null)

    private fun carryRoster(snapshot: TogetherRoomSnapshot) {
        rosterCarry.value = RosterCarry(
            snapshot.members.filter { it.state != TogetherMemberState.DISCONNECTED }.mapTo(HashSet()) { it.memberId },
            localNowMillis() + ROSTER_CARRY_MILLIS,
        )
    }

    private fun carried(change: TogetherMemberChange): Boolean {
        val carry = rosterCarry.value ?: return false
        if (localNowMillis() >= carry.untilMillis) return false
        val kind = change.kind
        return change.member.memberId in carry.memberIds && (kind == TogetherMemberChange.Kind.JOINED || kind == TogetherMemberChange.Kind.LEFT)
    }

    private suspend fun observeFollowerMode(session: TogetherSession) {
        combine(session.following, session.snapshot.map { hostOnline(it) }.distinctUntilChanged()) { following, online -> following && online }
            .distinctUntilChanged()
            .collect { wanted ->
                val active = wanted && !session.isHost && isCurrent(session)
                automationGate.setSuppressed(active)
                playbackBridge.setTargetPlayback(if (active) session.snapshot.value.playback else null)
                navigationGuard?.dispose()
                navigationGuard = if (active) {
                    EpisodeNavigationGuardRegistry.register { subjectId, episodeId ->
                        val host = session.snapshot.value.playback?.info
                        when {
                            // 没赶上拆除的登记不能一直拦着
                            !isCurrent(session) -> null
                            host != null && host.subjectId == subjectId && host.episodeId == episodeId -> null
                            else -> NAVIGATION_DENIED_MESSAGE
                        }
                    }
                } else {
                    null
                }
            }
    }

    /** 要告诉房间的本机状态: 播放页写的那份, 加上播放页此刻在不在眼前 */
    private fun localReport(): TogetherWatchingInfo? {
        val watching = playbackBridge.localWatching.value ?: return null
        val onScreen = playbackBridge.isPlayerOnScreen
        return if (watching.onScreen == onScreen) watching else watching.copy(onScreen = onScreen)
    }

    /** 开始跟随时、房主换了集时、房主进出播放页时、掉线的房主回来时: 把成员带到房主那一集 */
    private suspend fun observeGuidance(session: TogetherSession) {
        combine(
            session.following,
            session.snapshot
                .map { snapshot -> hostOnline(snapshot) to snapshot.playback?.info?.let { Triple(it.subjectId, it.episodeId, it.onScreen) } }
                .distinctUntilChanged(),
        ) { following, (online, _) -> following && online }
            .collect { wanted ->
                val active = wanted && !session.isHost && isCurrent(session)
                playbackBridge.setTargetPlayback(if (active) session.snapshot.value.playback else null)
                if (active) guide(session) else session.guidancePending.value = false
            }
    }

    /**
     * 把跟随的成员带到房主那一集。应用在后台时不做 —— 在后台改页面、让后台的会话去加载新的一集 (加载完会响一下才被按停)
     * 都不合适 —— 记下来, 回到前台时由每秒的那一轮补上。
     * 房主只在后台准备着那一集时, 不在播放页的成员也只在后台准备 ([TogetherEffect.Navigate.inBackground]), 房主进了播放页再带进去;
     * 正开着别的番的播放页的成员照常带进去。
     */
    private fun guide(session: TogetherSession) {
        if (!playbackBridge.isAppForeground) {
            session.guidancePending.value = true
            return
        }
        session.guidancePending.value = false
        val playback = session.snapshot.value.playback ?: return
        when (val action = SyncGuidanceEngine.compute(playback, playbackBridge.localPosition(), session.clock.now())) {
            null -> Unit
            is SyncAction.PushEpisode -> navigateTo(playback.info, inBackground = !playback.info.onScreen)
            is SyncAction.PopThenPushEpisode -> navigateTo(playback.info)
            is SyncAction.SwitchEpisodeInPlace -> playbackBridge.emitDirective(PlaybackDirective.SwitchEpisode(action.episodeId, playback))
            is SyncAction.SeekOnly -> playbackBridge.emitDirective(PlaybackDirective.Sync(playback, initial = true))
        }
    }

    /**
     * 跟随的成员实际在播的集与房主的不一样时拉回去: 播放页里换集不经过导航守卫。
     * 离开播放页不算 (只在开始跟随与房主换集时带人)。进房那一刻在哪一集由 [observeGuidance] 带, 这里只管之后的变化,
     * 不然进房时正开着别的番的播放页的成员会被带两次。
     */
    private suspend fun observeLocalDeviation(session: TogetherSession) {
        playbackBridge.localWatching
            .map { watching -> watching?.let { it.subjectId to it.episodeId } }
            .distinctUntilChanged()
            .drop(1)
            .collect { localKey ->
                if (localKey == null || !followingActive(session) || !playbackBridge.isPlayerInFront) return@collect
                val playback = session.snapshot.value.playback ?: return@collect
                val host = playback.info
                if (host.subjectId == localKey.first && host.episodeId == localKey.second) return@collect
                when (
                    val action = SyncGuidanceEngine.compute(
                        playback, LocalPosition.InPlayer(localKey.first, localKey.second), session.clock.now(),
                    )
                ) {
                    is SyncAction.SwitchEpisodeInPlace ->
                        playbackBridge.emitDirective(PlaybackDirective.SwitchEpisode(action.episodeId, playback))

                    is SyncAction.PopThenPushEpisode ->
                        navigateTo(host)

                    else -> Unit
                }
            }
    }

    /**
     * 房主播放 / 暂停、缓冲、倍速变了或位置跳了: 当场让播放页对齐, 不等每秒那一轮。这样成员停下与接着播都只比房主晚一段网络延迟,
     * 两段差不多就互相抵掉, 接着播时多半用不着再跳; 等那一轮的话两头各晚 0~1 秒不等, 每次暂停、继续都要跳一下。
     * 换集由 [observeGuidance] 管; 每秒那一轮照常兜底。
     */
    private suspend fun observeHostChanges(session: TogetherSession) {
        var previous: TogetherWatchingInfo? = null
        session.snapshot.collect { snapshot ->
            val playback = snapshot.playback
            val before = previous
            previous = playback?.info
            if (playback == null || before == null) return@collect
            val host = playback.info
            if (before.subjectId != host.subjectId || before.episodeId != host.episodeId) return@collect
            if (before.predicts(host, HOST_CHANGE_TOLERANCE_MILLIS)) return@collect
            if (abs(host.positionMillis - before.positionAt(host.positionAtMillis)) > HOST_CHANGE_TOLERANCE_MILLIS) {
                session.hostJumpedAtMillis.value = localNowMillis()
            }
            if (!followingActive(session) || !playbackBridge.isPlayerInFront) return@collect
            val local = playbackBridge.localWatching.value ?: return@collect
            if (local.subjectId != host.subjectId || local.episodeId != host.episodeId) return@collect
            playbackBridge.setTargetPlayback(playback)
            playbackBridge.emitDirective(PlaybackDirective.Sync(playback))
        }
    }

    /**
     * 跟随中每秒对一次: 位置差得多、播放 / 暂停不一样、倍速不一样就让播放页纠正。只看本机, 不发消息。
     * 房主缓冲、加载时成员停着等他 (播放页那边等满 2 秒才停, 一闪而过的缓冲不管)。
     * 成员自己的片源跟不上时不再按位置一次次往前跳, 落后着播 (见 [TogetherFollowerPace])。
     */
    private suspend fun runContinuousCorrection(session: TogetherSession) {
        val pace = TogetherFollowerPace()
        // 房主上一次改倍速 (长按确定键加速的开始与结束) 是什么时候
        var hostRate = 1f
        var hostRateChangedAt = Long.MIN_VALUE / 2
        while (currentCoroutineContext().isActive && isCurrent(session)) {
            delay(CORRECTION_INTERVAL_MILLIS)
            if (!followingActive(session)) {
                pace.reset()
                continue
            }
            if (session.guidancePending.value && playbackBridge.isAppForeground) guide(session)
            val playback = session.snapshot.value.playback ?: continue
            playbackBridge.setTargetPlayback(playback)
            // 播放页不在眼前: 留在后台的会话被按着暂停, 不去跟它较劲; 人回到播放页时下一秒就对上
            if (!playbackBridge.isPlayerInFront) continue
            val host = playback.info
            val local = playbackBridge.localWatching.value ?: continue
            if (host.subjectId != local.subjectId || host.episodeId != local.episodeId) {
                pace.reset()
                continue
            }
            pace.observeLocal(local, localNowMillis())
            pace.observeHost(host)
            val now = session.clock.now()
            // 正 = 成员落在房主后面
            val gap = host.positionAt(now) - local.positionAt(now)
            val drift = abs(gap)
            val rateDiffers = abs(host.playbackRate - local.playbackRate) > 0.01f
            // 自己在缓冲 / 加载时位置停着不动, 看着像落后, 其实是在等数据; 播放页正在一轮对齐里时由它自己补跳: 都不按位置纠正
            if (host.playbackRate != hostRate) {
                hostRate = host.playbackRate
                hostRateChangedAt = localNowMillis()
            }
            // 房主刚开始加速的这几秒只跟倍速、不按位置跳: 成员改倍速时播放器常要停一下 (开着隧道播放的要重配解码器, 索尼上 4 秒多),
            // 这时带着 2.5 倍的提前量去跳很容易过头。房主回到原速时当场对一次位置 (observeHostChanges)
            val speedingUp = host.playbackRate > 1f && localNowMillis() - hostRateChangedAt < SPEED_UP_SETTLE_MILLIS
            val unsettled = local.buffering || local.loading || playbackBridge.alignInProgress.value || speedingUp
            val correctPosition = when {
                unsettled -> false
                drift <= CORRECTION_THRESHOLD_MILLIS -> {
                    pace.inStep()
                    false
                }

                else -> when (pace.decide(localNowMillis(), behind = gap > 0)) {
                    TogetherFollowerPace.Decision.Correct -> true
                    TogetherFollowerPace.Decision.FallBehind -> {
                        logger.info { "Together: this member keeps falling behind the host ($gap ms), playing on behind it" }
                        effectChannel.send(TogetherEffect.FallingBehind)
                        false
                    }

                    TogetherFollowerPace.Decision.StayBehind -> false
                }
            }
            // 房主卡在缓冲 / 加载上时成员也停着等, 所以比的是「房主画面在不在走」与「成员想不想播」
            if (correctPosition || host.holding != local.paused || rateDiffers) {
                playbackBridge.emitDirective(PlaybackDirective.Sync(playback))
            }
        }
    }

    private suspend fun observeCorrectionToasts(session: TogetherSession) {
        var lastToastAtMillis = 0L
        playbackBridge.corrections.collect { deltaMillis ->
            if (session.isHost || !session.following.value || !isCurrent(session)) return@collect
            // 接着播时对齐的那一点点 (几百毫秒到一两秒) 不提示, 看着像出了什么事
            if (abs(deltaMillis) < CORRECTION_THRESHOLD_MILLIS) return@collect
            val now = localNowMillis()
            // 房主自己快进快退了, 成员跟过去: 画面是房主让跳的, 不是自己跟丢了
            if (now - session.hostJumpedAtMillis.value < HOST_JUMP_QUIET_MILLIS) return@collect
            if (now - lastToastAtMillis < CORRECTION_TOAST_MIN_INTERVAL_MILLIS) return@collect
            lastToastAtMillis = now
            effectChannel.send(TogetherEffect.ResyncedWithHost(deltaMillis))
        }
    }

    /**
     * 在别人的房间里: 把房主的名字记进最近的房间, 名字晚到 (房主的账号信息还没读出来)、改了名都跟着换。
     * 自己的房间不记; 自己被请去当房主时名单上的房主是自己, 也不记。
     */
    private suspend fun recordRecentHost(session: TogetherSession) {
        if (session.ownsRoom) return
        val number = session.roomId.number
        session.snapshot
            .map { it.recentHost(session.selfId) }
            .distinctUntilChanged { a, b -> a?.memberId == b?.memberId && a?.nickname == b?.nickname }
            .collect { host ->
                if (host == null) return@collect
                settings.update {
                    val recent = recentRoomList
                    val entry = recent.firstOrNull { it.number == number }
                    if (entry == null || (entry.hostMemberId == host.memberId && entry.hostName == host.nickname)) this
                    else copy(recentRooms = recent.withHost(number, host), lastJoined = null)
                }
            }
    }

    private suspend fun observeMembers(session: TogetherSession) {
        var previous = session.snapshot.value
        session.snapshot.collect { snapshot ->
            // 房主换了人 (房主让给了别人, 或原来的房主不在了换成别人): 说一声; 大家跟着去找新房主, 名单会乱一阵
            val newHost = snapshot.members.firstOrNull { it.memberId == snapshot.hostId }
            if (snapshot.hostId != previous.hostId && isCurrent(session)) {
                carryRoster(previous)
                if (newHost != null && newHost.memberId != session.selfId) effectChannel.send(TogetherEffect.HostChanged(newHost))
            }
            val changes = memberChanges(previous, snapshot, session.selfId).filterNot { carried(it) }
            previous = snapshot
            if (changes.isNotEmpty() && isCurrent(session)) effectChannel.send(TogetherEffect.MembersChanged(changes))
        }
    }

    private fun navigateTo(host: TogetherWatchingInfo, inBackground: Boolean = false) {
        navigationChannel.trySend(TogetherEffect.Navigate(host.subjectId, host.episodeId, host.subjectName, host.episodeSort, inBackground))
    }

    private suspend fun clearRememberedRoom() {
        settings.update { copy(rememberedRoom = null) }
    }

    /** 跟随此刻算不算数: 开着「跟随房主播放」, 自己不是房主, 房主也没掉线 */
    private fun followingActive(session: TogetherSession): Boolean =
        session.following.value && !session.isHost && hostOnline(session.snapshot.value) && isCurrent(session)

    private fun hostOnline(snapshot: TogetherRoomSnapshot): Boolean =
        snapshot.members.none { it.isHost && it.state == TogetherMemberState.DISCONNECTED }

    private fun isCurrent(session: TogetherSession): Boolean = isAttached(session) && !session.isClosing

    private fun isAttached(session: TogetherSession): Boolean =
        (_state.value as? TogetherState.InRoom)?.session === session

    /** 一个房间怎么没的: 房主关了、换了密码、房间那边自己结束了 (重新进)、自己让出了房主 (当成员回去)、被请去当房主 (当房主重开) */
    private enum class RoomLoss { CLOSED, PASSWORD_CHANGED, REJOIN, YIELDED, PROMOTED }

    companion object {
        /** 导航守卫拦下时给出的原因 */
        const val NAVIGATION_DENIED_MESSAGE = "TOGETHER_FOLLOWING"

        private const val AUTO_JOIN_INITIAL_RETRY_MILLIS = 2_000L
        private const val AUTO_JOIN_MAX_RETRY_MILLIS = 30_000L
        private const val CORRECTION_INTERVAL_MILLIS = 1_000L
        private const val CORRECTION_THRESHOLD_MILLIS = 3_000L

        /** 房主加速后这么久里不按位置纠正成员 (见 runContinuousCorrection); 长按加速一般几秒, 一直开着倍速看的过了这段照常纠正 */
        private const val SPEED_UP_SETTLE_MILLIS = 10_000L

        /** 房主的位置比照上一份推算差出这么多才算他自己跳了 (同 [TogetherPlaybackBridge] 认不连续的那一档) */
        private const val HOST_CHANGE_TOLERANCE_MILLIS = 2_500L

        /** 房主自己跳了之后这么久以内, 成员为跟上他跳的那一下不提示 */
        private const val HOST_JUMP_QUIET_MILLIS = 5_000L
        private const val CORRECTION_TOAST_MIN_INTERVAL_MILLIS = 5_000L

        /** 上一次说的位置与实际的差多少以内不用再说; 比成员的纠正门槛 (3 秒) 小, 留出时钟与网络的误差 */
        private const val REPORT_TOLERANCE_MILLIS = 1_500L

        private const val EFFECT_BUFFER = 16

        /** 记住的房间多久没回去就忘掉 */
        private const val REMEMBER_MILLIS = 12 * 60 * 60 * 1000L

        /** 换房主后这么久里原来名单上的人进进出出不提示 (见 [RosterCarry]) */
        private const val ROSTER_CARRY_MILLIS = 20_000L

        private val logger = logger<TogetherManager>()
    }
}

internal fun randomTogetherMemberId(): String = buildString(16) { repeat(16) { append("0123456789abcdef"[Random.nextInt(16)]) } }
