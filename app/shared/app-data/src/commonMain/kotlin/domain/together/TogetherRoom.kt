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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.math.abs

/** 已经进了的一个房间 */
interface TogetherRoom {
    /** 自己在房间里的编号 */
    val selfId: String

    /** 自己是不是房主 (开的是自己的房间)。进房时定下, 之后不变: 房主让位时这个房间以 [TogetherRoomEvent.HostChanged] 结束 */
    val isHost: Boolean

    /** 这个房间是自己开的 */
    val created: Boolean
    val initialSnapshot: TogetherRoomSnapshot
    val clock: TogetherRoomClock

    /** 只能有一个收集者 */
    val events: Flow<TogetherRoomEvent>

    /** 自己的状态变了 (在看什么、看到哪、跟不跟随) */
    suspend fun report(report: TogetherSelfReport)

    /** 离开房间, 等断开完成。房主离开 = 关掉房间, [reason] 告诉成员为什么 */
    suspend fun leave(reason: TogetherCloseReason = TogetherCloseReason.DISBANDED)

    /**
     * 自己是房主时请成员 [memberId] 来当房主 ([TogetherMessage.TransferHost])。他以房主身份重开这个房间, 这边看到他的快照就让位,
     * 这个房间以 [TogetherRoomEvent.HostChanged] 结束; 一直等不到他接手就作罢 ([TogetherRoomEvent.TransferFailed])。
     * 成员调用、或 [memberId] 不在名单里时什么都不做。
     */
    suspend fun transferHost(memberId: String) {}
}

sealed interface TogetherRoomEvent {
    /** 与代理的连接断了 / 又连上了 */
    data class Connectivity(val online: Boolean) : TogetherRoomEvent

    /** 房间的新快照。[TogetherRoomSnapshot.status] 为 CLOSED 的是最后一份: 房主解散了房间 */
    data class Snapshot(val snapshot: TogetherRoomSnapshot) : TogetherRoomEvent

    /** 自己原来是房主, 房间里出现了另一个房主, 自己让位了。这个房间到此结束, 重新进就是成员 */
    data object HostChanged : TogetherRoomEvent

    /** 自己是成员, 房主说这边的密码不对 (他换过密码)。这个房间到此结束 */
    data object Rejected : TogetherRoomEvent

    /** 自己是成员, 房主请自己来当房主 ([TogetherRoom.transferHost])。这个房间到此结束, 以房主身份重开 */
    data object PromotedToHost : TogetherRoomEvent

    /** 请 [memberId] 来当房主, 等不到他接手, 作罢; 自己还是房主 */
    data class TransferFailed(val memberId: String) : TogetherRoomEvent
}

fun interface TogetherRoomGateway {
    /**
     * 进 [room] 这个房间。
     *
     * @param asHost 开自己的房间 (房间号是这台设备的设备号): 不等谁回应, 当场当房主。
     *   为假时加入别人的房间, 当成员: 房主回应了才算进去, 一直没人回应不会自己当房主。
     * @param memberId 自己在房间里的编号; 同一台设备每次用同一个, 房主重开应用回来后成员还认得
     * @throws TogetherJoinException 一家代理都连不上
     * @throws TogetherNoBrokersException 没有代理清单 (见 [TogetherBrokerList])
     * @throws TogetherRoomEmptyException 加入时房间里没有房主
     * @throws TogetherWrongPasswordException 加入时房主说密码不对
     */
    suspend fun join(room: TogetherRoomId, memberId: String, self: TogetherSelfReport, asHost: Boolean): TogetherRoom
}

class TogetherJoinException(message: String) : Exception(message)

/**
 * 用 [TogetherChannel] 搭起来的房间 (docs/contributing/code/together.md「房间」一节)。
 *
 * @param scope 房间的连接与处理循环跑在这里
 */
class ChannelTogetherRoomGateway(
    private val opener: TogetherChannelOpener,
    private val scope: CoroutineScope,
    private val localNowMillis: () -> Long = ::currentTimeMillis,
) : TogetherRoomGateway {
    override suspend fun join(room: TogetherRoomId, memberId: String, self: TogetherSelfReport, asHost: Boolean): TogetherRoom {
        val selfId = memberId
        val connection = opener.connect(room, scope) {
            TogetherMessageCodec.encode(TogetherMessage.Bye(selfId, dropped = true))
        }
        val channel = connection.channel
        val inbox = Channel<TogetherMessage>(Channel.UNLIMITED)
        // 先接上收消息再发招呼, 房主的回应才不会漏掉
        val pump = scope.launch(CoroutineName("TogetherRoom.inbox"), start = CoroutineStart.UNDISPATCHED) {
            channel.incoming.collect { bytes ->
                TogetherMessageCodec.decode(bytes)?.takeIf { it.sender != selfId }?.let { inbox.send(it) }
            }
        }
        try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { channel.links.first { it.online } }
                ?: throw TogetherJoinException("连不上一起看的服务")

            val clock = TogetherRoomClock(localNowMillis)
            val opened = if (asHost) {
                // 开自己的房间: 当场当房主。之前开着这个房间的自己 (应用重开前) 留下的成员看到新快照里没有自己, 会再打招呼
                channel.answersStrangers = true
                val hostState = TogetherRoomHost(selfId, localNowMillis)
                val snapshot = hostState.open(self)
                channel.send(TogetherMessageCodec.encode(TogetherMessage.Snapshot(selfId, snapshot)))
                logger.info { "[一起看] 开了自己的房间" }
                ChannelTogetherRoom(
                    selfId, connection, pump, inbox, localNowMillis, clock,
                    hostState = hostState, initialSnapshot = snapshot, created = true, self = self, helloSentAt = localNowMillis(),
                )
            } else {
                val greeting = coroutineScope {
                    val linkUpdates = Channel<TogetherLinks>(Channel.CONFLATED)
                    val rejections = Channel<Unit>(Channel.CONFLATED)
                    // 当场接上: 招呼发出去之前就要在听, 房主的回执才不会漏掉
                    val watchers = listOf(
                        launch(start = CoroutineStart.UNDISPATCHED) { channel.links.collect { linkUpdates.send(it) } },
                        launch(start = CoroutineStart.UNDISPATCHED) { channel.rejections.collect { rejections.send(it) } },
                    )
                    try {
                        greet(channel, inbox, linkUpdates, rejections, selfId, self)
                    } finally {
                        watchers.forEach { it.cancel() }
                    }
                }
                val accepted = greeting.welcome
                val observed = greeting.seen
                val helloSentAt = greeting.lastHelloAt
                when {
                    accepted != null -> {
                        clock.recordSample(accepted.snapshot.roomTime, accepted.echoSentAt, localNowMillis())
                        logger.info { "[一起看] 进了房间, 房间里 ${accepted.snapshot.members.size} 人" }
                        ChannelTogetherRoom(
                            selfId, connection, pump, inbox, localNowMillis, clock,
                            hostState = null, initialSnapshot = accepted.snapshot, created = false, self = self, helloSentAt = helloSentAt,
                        )
                    }

                    observed != null && observed.status == TogetherRoomStatus.OPEN -> {
                        // 房主在, 但一直没回应这边的招呼: 先按看到的快照当成员, 之后发现名单里没有自己会再打招呼
                        logger.info { "[一起看] 进了房间 (房主还没回应招呼)" }
                        ChannelTogetherRoom(
                            selfId, connection, pump, inbox, localNowMillis, clock,
                            hostState = null, initialSnapshot = observed, created = false, self = self, helloSentAt = helloSentAt,
                        )
                    }

                    else -> {
                        logger.info { "[一起看] 房间里没有房主" }
                        throw TogetherRoomEmptyException()
                    }
                }
            }
            scope.launch(CoroutineName("TogetherRoom")) { opened.run() }
            return opened
        } catch (e: Throwable) {
            pump.cancel()
            withContext(NonCancellable) { connection.close() }
            throw e
        }
    }

    /**
     * 进房时与房主一问一答的结果。
     *
     * @param welcome 房主对这边某一次招呼的回应; 没等到时为 `null`
     * @param seen 没等到回应、但途中看到过的房主快照
     * @param lastHelloAt 最后一次招呼的发出时刻
     */
    private class Greeting(val welcome: TogetherMessage.Welcome?, val seen: TogetherRoomSnapshot?, val lastHelloAt: Long)

    /**
     * 打招呼, 等房主回应, 每轮 [HELLO_WAIT_MILLIS]。
     *
     * - 一轮里没等到回应但看到了房主的快照 = 房间里有房主, 只是招呼没送到: 再打一次招呼、多等一轮 (至多 [HELLO_ROUNDS] 轮)。
     * - 什么都没看到也再打一次招呼、多等一轮 (共 [EMPTY_ROUNDS] 轮), 之后才算房间里没有房主。
     * - 几家代理不是同时连上的, 而房主可能只在后连上的那一家上 (他那边别家连不通): 等回应期间每多连上一家就再打一次招呼,
     *   并从那一刻起至少再等 [LINK_ANSWER_MILLIS]。房主回应这次进房的哪一次招呼都算数。
     * - 房主说解不开这边的招呼 ([TogetherChannel.rejections]): 密码不对, 不再等。
     *
     * @throws TogetherWrongPasswordException 房主说密码不对
     */
    private suspend fun greet(
        channel: TogetherChannel,
        inbox: ReceiveChannel<TogetherMessage>,
        linkUpdates: ReceiveChannel<TogetherLinks>,
        rejections: ReceiveChannel<Unit>,
        selfId: String,
        self: TogetherSelfReport,
    ): Greeting {
        val hellos = HashSet<Long>()
        var lastHelloAt = 0L
        var connected = channel.links.value.connected
        var seen: TogetherRoomSnapshot? = null
        for (round in 1..HELLO_ROUNDS) {
            lastHelloAt = sendHello(channel, selfId, self).also { hellos += it }
            var deadline = localNowMillis() + HELLO_WAIT_MILLIS
            while (true) {
                val remaining = deadline - localNowMillis()
                if (remaining <= 0) break
                val next = withTimeoutOrNull(remaining) {
                    select<Any> {
                        inbox.onReceive { it }
                        linkUpdates.onReceive { it }
                        rejections.onReceive { it }
                    }
                } ?: break
                when (next) {
                    Unit -> throw TogetherWrongPasswordException()

                    is TogetherLinks -> {
                        if (next.connected > connected) {
                            lastHelloAt = sendHello(channel, selfId, self).also { hellos += it }
                            deadline = maxOf(deadline, localNowMillis() + LINK_ANSWER_MILLIS)
                        }
                        connected = next.connected
                    }

                    is TogetherMessage.Welcome -> when {
                        next.to == selfId && next.echoSentAt in hellos -> return Greeting(next, seen, lastHelloAt)
                        next.sender == next.snapshot.hostId -> seen = next.snapshot
                    }

                    is TogetherMessage.Snapshot -> if (next.sender == next.snapshot.hostId) seen = next.snapshot

                    // 房主在问这边还在不在 (上一次留下的连接的遗嘱到了): 再打一次招呼
                    is TogetherMessage.Ping ->
                        if (next.to == selfId) lastHelloAt = sendHello(channel, selfId, self).also { hellos += it }

                    else -> Unit
                }
            }
            if (seen == null && round >= EMPTY_ROUNDS) return Greeting(null, null, lastHelloAt)
        }
        return Greeting(null, seen, lastHelloAt)
    }

    private suspend fun sendHello(channel: TogetherChannel, selfId: String, self: TogetherSelfReport): Long {
        val sentAt = localNowMillis()
        channel.send(TogetherMessageCodec.encode(TogetherMessage.Hello(selfId, self, sentAt)))
        return sentAt
    }

    private companion object {
        private val logger = logger<ChannelTogetherRoomGateway>()
        const val CONNECT_TIMEOUT_MILLIS = 20_000L

        /** 发出招呼后每一轮等房主回应多久 */
        const val HELLO_WAIT_MILLIS = 3_000L
        const val HELLO_ROUNDS = 3

        /** 什么都没看到时等几轮才算房间里没有房主 */
        const val EMPTY_ROUNDS = 2

        /** 等回应期间又连上一家代理时, 从那一刻起至少再等多久 */
        const val LINK_ANSWER_MILLIS = 1_500L
    }
}

/**
 * 一个房间的处理循环。成员与房主各走各的分支 ([hostState] 非空的是房主):
 *
 * - **成员**只听当前房主的快照; 房主让位 ([TogetherMessage.Handover]) 时改听新的; 房主掉线后别的房主出现也改听它。
 *   快照的名单里没有自己 (房主重开过应用) 就再打一次招呼。
 * - **房主**把招呼、状态、离开记进 [TogetherRoomHost] 再把快照发回房间。看到另一个房主时: 在线成员多的留下, 一样多时编号小的留下;
 *   自己该让就发 [TogetherMessage.Handover] 后以 [TogetherRoomEvent.HostChanged] 结束, 不该让就把自己的快照再发一遍。
 * - **掉线**: 遗嘱到了不马上算数 (可能只断了几家代理里的一家), [DROP_GRACE_MILLIS] 内没有那个人的任何消息才算。
 *   自己这边断了一家时主动发一条消息, 让对面知道人还在。遗嘱可能来得很晚 (断网后重连上了, 代理过几十秒才认定旧连接死了),
 *   所以怀疑谁掉线了就问一次: 房主发 [TogetherMessage.Ping], 成员再打一次招呼; 人在就会回应。
 * - **房主一直不应**: 成员每次打招呼都开始等房主的消息, [DROP_GRACE_MILLIS] 内一条都没有就当他掉线了。
 *   成员离线期间房主解散了房间、或者走了, 这边收不到那条消息, 靠这个才知道。还有代理没连上时房主可能只在那一家上,
 *   等到 [PARTIAL_LINKS_GRACE_MILLIS]; 自己不在线时听不到任何人, 不下结论, 连回来之后重新计时。
 * - **多连上一家代理**: 房间里可能有人只在这一家上 (他那边别家连不通), 往那里也说一遍: 房主发快照, 成员重新打招呼。
 * - **让房主**: 房主请某个成员来当 ([TogetherMessage.TransferHost]; 那条消息不补发, 等的途中每个节拍再说一遍, 最多等 [TRANSFER_TIMEOUT_MILLIS])。
 *   那个成员的这个房间到此结束, 由上一层以房主身份重开; 房主看到他的快照就让位 (不比人数), 别的成员跟着 [TogetherMessage.Handover] 改听他的。
 */
private class ChannelTogetherRoom(
    override val selfId: String,
    private val connection: TogetherConnection,
    private val pump: Job,
    private val inbox: ReceiveChannel<TogetherMessage>,
    private val localNow: () -> Long,
    override val clock: TogetherRoomClock,
    private val hostState: TogetherRoomHost?,
    override val initialSnapshot: TogetherRoomSnapshot,
    override val created: Boolean,
    private var self: TogetherSelfReport,
    private var helloSentAt: Long,
) : TogetherRoom {
    override val isHost: Boolean get() = hostState != null

    private sealed interface Input {
        class Message(val message: TogetherMessage) : Input
        class Links(val links: TogetherLinks) : Input
        data object Tick : Input
        class SelfReport(val report: TogetherSelfReport) : Input
        class Leave(val reason: TogetherCloseReason) : Input
        data object Rejected : Input
        class TransferHost(val memberId: String) : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val eventChannel = Channel<TogetherRoomEvent>(Channel.UNLIMITED)
    override val events: Flow<TogetherRoomEvent> = eventChannel.receiveAsFlow()
    private val finished = CompletableDeferred<Unit>()

    // 以下只在处理循环里读写。进房时至少连着一家; 之后的变化都经 onLinks
    private var links = connection.channel.links.value.let { it.copy(connected = maxOf(it.connected, 1)) }
    private var ended = false

    // 成员
    private var hostId = initialSnapshot.hostId
    private var latest = initialSnapshot
    /** 在等房主的消息: 从什么时候起、是不是因为收到了他的遗嘱 (而不只是招呼没人应)。有他的消息就清掉 */
    private var hostSuspicion: HostSuspicion? = null

    /** 这一刻之前不下「房主掉线」的结论: 刚多连上一家代理, 给那一家上的回应留出时间 */
    private var noVerdictBefore = 0L

    /** 这一轮对表还要问几次 (每次处理循环的节拍问一次); 进房时一轮, 之后每 [TIME_ROUND_INTERVAL_MILLIS] 一轮 */
    private var timeQueriesLeft = TIME_QUERIES_PER_ROUND

    /** 上一次写进日志的时钟差, 见 [logClock] */
    private var loggedClockOffset: Long? = null
    private var lastTimeRoundAt = helloSentAt
    private var hostGone = false
    private var lastHelloAt = helloSentAt

    // 房主: 遗嘱到了、还在等消息的成员 → 遗嘱到的时刻
    private val suspects = HashMap<String, Long>()

    /** 房主: 请了谁来当房主、等到什么时候 (见 [transferHost]) */
    private var transfer: PendingTransfer? = null

    private class PendingTransfer(val memberId: String, val deadline: Long)

    override suspend fun report(report: TogetherSelfReport) {
        inputs.trySend(Input.SelfReport(report))
    }

    override suspend fun transferHost(memberId: String) {
        inputs.trySend(Input.TransferHost(memberId))
    }

    override suspend fun leave(reason: TogetherCloseReason) {
        inputs.trySend(Input.Leave(reason))
        finished.await()
    }

    suspend fun run() {
        try {
            coroutineScope {
                launch { for (message in inbox) inputs.send(Input.Message(message)) }
                launch { connection.channel.links.collect { inputs.send(Input.Links(it)) } }
                launch { connection.channel.rejections.collect { inputs.send(Input.Rejected) } }
                launch {
                    while (true) {
                        delay(TICK_MILLIS)
                        inputs.send(Input.Tick)
                    }
                }
                for (input in inputs) {
                    when (input) {
                        is Input.Message -> if (hostState != null) onMessageAsHost(hostState, input.message) else onMessageAsMember(input.message)
                        is Input.Links -> onLinks(input.links)
                        Input.Tick -> if (hostState != null) onTickAsHost(hostState) else onTickAsMember()
                        is Input.SelfReport -> onSelfReport(input.report)
                        is Input.Leave -> {
                            if (hostState != null) broadcast(hostState.close(input.reason)) else send(TogetherMessage.Bye(selfId))
                            ended = true
                        }

                        // 房主说解不开这边的消息: 他换过密码。房主自己收到回执 (另一台用着同一个房间号的设备) 不理会
                        Input.Rejected -> if (hostState == null) {
                            logger.info { "[一起看] 房主说密码不对, 离开房间" }
                            emit(TogetherRoomEvent.Rejected)
                            ended = true
                        }

                        is Input.TransferHost -> if (hostState != null && input.memberId != selfId && hostState.contains(input.memberId)) {
                            logger.info { "[一起看] 请 ${input.memberId.take(8)} 来当房主" }
                            transfer = PendingTransfer(input.memberId, localNow() + TRANSFER_TIMEOUT_MILLIS)
                            send(TogetherMessage.TransferHost(selfId, to = input.memberId))
                        }
                    }
                    if (ended) break
                }
                coroutineContext.cancelChildren()
            }
        } finally {
            pump.cancel()
            withContext(NonCancellable) { connection.close() }
            eventChannel.close()
            finished.complete(Unit)
        }
    }

    private suspend fun onLinks(now: TogetherLinks) {
        val before = links
        links = now
        when {
            before.online && !now.online -> emit(TogetherRoomEvent.Connectivity(false))
            !before.online && now.online -> {
                emit(TogetherRoomEvent.Connectivity(true))
                // 断线期间的消息都没收到: 房主把快照再发一遍, 成员重新打招呼拿一份
                if (hostState != null) {
                    broadcast(hostState.snapshot())
                } else {
                    // 不在线的那一阵听不到任何人: 等房主回应的时间从现在重新算
                    hostSuspicion = hostSuspicion?.let { HostSuspicion(localNow(), it.byWill) }
                    hello()
                }
            }

            // 断了一家、还连着别家: 那家会替这边发遗嘱, 主动说一声人还在
            now.online && now.connected < before.connected ->
                if (hostState != null) broadcast(hostState.snapshot()) else send(TogetherMessage.Report(selfId, self))

            // 多连上一家: 只在这一家上的人还没听到过这边
            now.connected > before.connected -> if (hostState != null) {
                broadcast(hostState.snapshot())
            } else {
                noVerdictBefore = localNow() + LINK_ANSWER_MILLIS
                hello()
            }
        }
    }

    private suspend fun onSelfReport(report: TogetherSelfReport) {
        self = report
        if (hostState != null) {
            hostState.onReport(selfId, report)?.let { broadcast(it) }
        } else {
            send(TogetherMessage.Report(selfId, report))
        }
    }

    // ---- 成员 ----

    private suspend fun onMessageAsMember(message: TogetherMessage) {
        when (message) {
            is TogetherMessage.Welcome -> {
                // 只拿当前房主的回应对表: 房间里同时有两个房主时, 另一个也会回应这边的招呼
                if (message.sender == hostId && message.to == selfId && message.echoSentAt == helloSentAt) {
                    clock.recordSample(message.snapshot.roomTime, message.echoSentAt, localNow())
                }
                onSnapshotAsMember(message.sender, message.snapshot)
            }

            is TogetherMessage.Snapshot -> onSnapshotAsMember(message.sender, message.snapshot)
            is TogetherMessage.Handover -> if (message.sender == hostId && message.to != selfId) {
                hostId = message.to
                hostSuspicion = null
                hostGone = false
                latest = latest.copy(version = Long.MIN_VALUE)
                hello()
            }

            // 房主请自己来当房主: 这个房间到此为止 (不说再见, 房主看到自己重开的房间就会让位), 由上一层以房主身份重开
            is TogetherMessage.TransferHost -> if (message.sender == hostId && message.to == selfId) {
                logger.info { "[一起看] 房主请这边来当房主" }
                emit(TogetherRoomEvent.PromotedToHost)
                ended = true
            }

            // 房主正常离开发的是 CLOSED 的快照; 这里只有遗嘱
            is TogetherMessage.Bye -> if (message.sender == hostId && message.dropped) {
                hostSuspicion = HostSuspicion(hostSuspicion?.since ?: localNow(), byWill = true)
            }

            // 房主在问这边还在不在
            is TogetherMessage.Ping -> if (message.to == selfId) send(TogetherMessage.Report(selfId, self))
            is TogetherMessage.TimeReply -> if (message.sender == hostId && message.to == selfId) {
                if (clock.recordSample(message.roomTime, message.echoSentAt, localNow())) logClock()
            }
            is TogetherMessage.Hello, is TogetherMessage.Report, is TogetherMessage.TimeQuery -> Unit
        }
    }

    private suspend fun onSnapshotAsMember(sender: String, snapshot: TogetherRoomSnapshot) {
        if (sender != snapshot.hostId) return
        if (snapshot.hostId != hostId) {
            // 别的房主: 只有当前房主掉线了才改听它
            if (!hostGone) return
            hostId = snapshot.hostId
            latest = latest.copy(version = Long.MIN_VALUE)
        }
        // 房主有消息 = 人还在
        hostSuspicion = null
        hostGone = false
        if (snapshot.version < latest.version) return
        latest = snapshot
        emit(TogetherRoomEvent.Snapshot(snapshot))
        if (snapshot.status == TogetherRoomStatus.CLOSED) {
            ended = true
            return
        }
        // 名单里没有自己, 或自己被标成了掉线 (房主问的那一声、这边回的那一声丢了): 再打招呼
        val missing = snapshot.members.none { it.memberId == selfId && it.state != TogetherMemberState.DISCONNECTED }
        // 快照是刚发出的: 它带的房间时间跟这边推算的差得多 = 有一边的时钟被系统拨动过 (或者进房时没量成), 重新量一次
        val skewed = abs(clock.now() - snapshot.roomTime) > CLOCK_SKEW_MILLIS
        if ((missing || skewed) && localNow() - lastHelloAt >= REHELLO_INTERVAL_MILLIS) {
            // 时钟被拨过: 之前量的都不作数, 招呼的回应直接用, 再接着量一轮
            if (skewed) startTimeRound(fresh = true)
            hello()
        }
    }

    private suspend fun onTickAsMember() {
        syncClock()
        val suspicion = hostSuspicion ?: return
        val now = localNow()
        // 自己不在线时听不到任何人, 不下结论
        if (hostGone || !links.online || now < noVerdictBefore) return
        // 只是招呼没人应、而还有代理没连上: 房主可能只在那一家上, 多等一阵
        val grace = if (suspicion.byWill || links.connected >= links.total) DROP_GRACE_MILLIS else PARTIAL_LINKS_GRACE_MILLIS
        if (now - suspicion.since >= grace) {
            hostGone = true
            latest = latest.copy(
                members = latest.members.map { if (it.memberId == hostId) it.copy(state = TogetherMemberState.DISCONNECTED) else it },
            )
            emit(TogetherRoomEvent.Snapshot(latest))
        } else if (now - lastHelloAt >= REHELLO_INTERVAL_MILLIS) {
            // 遗嘱到了、或上一次招呼没人应: 再问一次, 他在的话会回应
            hello()
        }
    }

    /**
     * 对表 (见 [TogetherRoomClock]): 招呼的回应只量得到一次, 进房后再问房主几次, 取来回最快的那次; 之后隔一阵再量一轮。
     * 房主掉线、自己不在线时不问。
     */
    private suspend fun syncClock() {
        if (hostGone || !links.online) return
        val now = localNow()
        if (timeQueriesLeft == 0 && now - lastTimeRoundAt >= TIME_ROUND_INTERVAL_MILLIS) startTimeRound(fresh = true)
        if (timeQueriesLeft == 0) return
        timeQueriesLeft--
        send(TogetherMessage.TimeQuery(selfId, sentAt = now))
    }

    /**
     * 对表的结果写进日志: 房间时间就是房主的系统时间, 几台设备的日志按这个差对齐才比得上;
     * 往返的时间是消息一来一回要多久。变了不少才再写。
     */
    private fun logClock() {
        val offset = clock.currentOffsetMillis()
        val logged = loggedClockOffset
        if (logged != null && abs(offset - logged) < CLOCK_LOG_STEP_MILLIS) return
        loggedClockOffset = offset
        logger.info {
            "[一起看] 对表: 房主的系统时钟比本机${if (offset >= 0) "快" else "慢"} ${abs(offset)} ms, 消息往返 ${clock.currentRoundTripMillis()} ms"
        }
    }

    /** @param fresh 之前量的不再拿来比 (隔了一阵、或时钟被拨过) */
    private fun startTimeRound(fresh: Boolean) {
        lastTimeRoundAt = localNow()
        timeQueriesLeft = TIME_QUERIES_PER_ROUND
        if (fresh) clock.startRound()
    }

    private suspend fun hello() {
        lastHelloAt = localNow()
        helloSentAt = lastHelloAt
        // 从这一刻起等房主的消息; 一直没有就当他掉线了 (见 onTickAsMember)
        if (hostSuspicion == null) hostSuspicion = HostSuspicion(lastHelloAt, byWill = false)
        send(TogetherMessage.Hello(selfId, self, helloSentAt))
    }

    // ---- 房主 ----

    private suspend fun onMessageAsHost(host: TogetherRoomHost, message: TogetherMessage) {
        when (message) {
            is TogetherMessage.Hello -> {
                suspects.remove(message.sender)
                val snapshot = host.onHello(message.sender, message.report)
                send(TogetherMessage.Welcome(selfId, to = message.sender, echoSentAt = message.sentAt, snapshot = snapshot))
                emit(TogetherRoomEvent.Snapshot(snapshot))
            }

            is TogetherMessage.Report -> {
                suspects.remove(message.sender)
                host.onReport(message.sender, message.report)?.let { broadcast(it) }
            }

            is TogetherMessage.Bye -> if (message.dropped) {
                if (host.contains(message.sender) && message.sender !in suspects) {
                    suspects[message.sender] = localNow()
                    // 遗嘱可能是他一条早就不用的连接留下的: 问一声, 人在就会回应
                    send(TogetherMessage.Ping(selfId, to = message.sender))
                }
            } else {
                suspects.remove(message.sender)
                host.onLeft(message.sender)?.let { broadcast(it) }
            }

            is TogetherMessage.Welcome -> onRivalHost(host, message.sender, message.snapshot)
            is TogetherMessage.Snapshot -> onRivalHost(host, message.sender, message.snapshot)
            // 对表: 当场回, 带上此刻的房间时间
            is TogetherMessage.TimeQuery -> if (host.contains(message.sender)) {
                send(TogetherMessage.TimeReply(selfId, to = message.sender, echoSentAt = message.sentAt, roomTime = clock.now()))
            }
            is TogetherMessage.Handover, is TogetherMessage.Ping, is TogetherMessage.TimeReply, is TogetherMessage.TransferHost -> Unit
        }
    }

    private suspend fun onRivalHost(host: TogetherRoomHost, sender: String, theirs: TogetherRoomSnapshot) {
        if (sender != theirs.hostId || theirs.status == TogetherRoomStatus.CLOSED) return
        val mine = host.snapshot()
        val myOnline = mine.members.count { it.state != TogetherMemberState.DISCONNECTED }
        val theirOnline = theirs.members.count { it.state != TogetherMemberState.DISCONNECTED }
        // 请来当房主的人重开了房间: 让给他 (他那边名单里只有自己, 按人数比的话会让回来)
        val invited = transfer?.memberId == sender
        val giveWay = invited || theirOnline > myOnline || (theirOnline == myOnline && sender < selfId)
        if (giveWay) {
            logger.info { if (invited) "[一起看] 请来的房主接手了, 让位" else "[一起看] 房间里有另一个房主, 让位" }
            send(TogetherMessage.Handover(selfId, to = sender))
            emit(TogetherRoomEvent.HostChanged)
            ended = true
        } else {
            broadcast(mine)
        }
    }

    private suspend fun onTickAsHost(host: TogetherRoomHost) {
        val now = localNow()
        transfer?.let { pending ->
            when {
                // 等不到 (对方是不认得这条消息的旧版本、掉线了、开不了房间): 作罢, 还是自己当房主
                now >= pending.deadline || !host.contains(pending.memberId) -> {
                    logger.info { "[一起看] ${pending.memberId.take(8)} 没有接手房主" }
                    transfer = null
                    emit(TogetherRoomEvent.TransferFailed(pending.memberId))
                }
                // 那条消息不补发, 丢了就没了: 等的途中每个节拍再说一遍; 他已经在重开的路上时收到的不理会
                else -> send(TogetherMessage.TransferHost(selfId, to = pending.memberId))
            }
        }
        val dropped = suspects.filterValues { now - it >= DROP_GRACE_MILLIS }.keys.toList()
        for (memberId in dropped) {
            suspects.remove(memberId)
            host.onDropped(memberId)?.let { broadcast(it) }
        }
        // 问过还没回应的再问: 那一问一答走的是不补发的消息, 丢一条就成了误判
        for (memberId in suspects.keys) send(TogetherMessage.Ping(selfId, to = memberId))
        host.pruneDisconnected(DISCONNECTED_KEEP_MILLIS)?.let { broadcast(it) }
    }

    private suspend fun broadcast(snapshot: TogetherRoomSnapshot) {
        send(TogetherMessage.Snapshot(selfId, snapshot))
        emit(TogetherRoomEvent.Snapshot(snapshot))
    }

    private suspend fun send(message: TogetherMessage) {
        connection.channel.send(TogetherMessageCodec.encode(message))
    }

    private fun emit(event: TogetherRoomEvent) {
        eventChannel.trySend(event)
    }

    private class HostSuspicion(val since: Long, val byWill: Boolean)

    private companion object {
        private val logger = logger<ChannelTogetherRoom>()
        const val TICK_MILLIS = 2_000L

        /** 多连上一家代理之后, 这么久之内不下「房主掉线」的结论 */
        const val LINK_ANSWER_MILLIS = 1_500L

        /** 遗嘱到了之后多久没有那个人的消息才算掉线 */
        const val DROP_GRACE_MILLIS = 10_000L

        /** 招呼没人应、而还有代理没连上时, 等多久才算房主掉线; 比一条连接重连的最长间隔 (30 秒) 长 */
        const val PARTIAL_LINKS_GRACE_MILLIS = 45_000L

        /** 掉线的成员在名单里留多久 */
        const val DISCONNECTED_KEEP_MILLIS = 5 * 60_000L

        /** 两次招呼至少隔多久 (名单里没有自己、怀疑房主掉线、时钟对不上时会再打招呼) */
        const val REHELLO_INTERVAL_MILLIS = 5_000L

        /** 快照带的房间时间与本机推算的差多少以上算时钟对不上: 比消息在路上的时间宽, 与成员纠正位置的门槛一样 (再小的差不影响跟随) */
        const val CLOCK_SKEW_MILLIS = 3_000L

        /** 一轮对表问几次 (每个节拍一次) */
        const val TIME_QUERIES_PER_ROUND = 4

        /** 隔多久重新量一轮: 两边的时钟会慢慢走偏 */
        const val TIME_ROUND_INTERVAL_MILLIS = 5 * 60_000L

        /** 时钟差变了这么多以上才再写一次日志 */
        const val CLOCK_LOG_STEP_MILLIS = 200L

        /** 请成员来当房主后最多等他这么久 */
        const val TRANSFER_TIMEOUT_MILLIS = 15_000L
    }
}
