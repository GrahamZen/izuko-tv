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
import me.him188.ani.utils.platform.currentTimeMillis

/**
 * 房主这一侧的房间名单: 应用没有服务器, 房间的全貌由房主的设备汇总。成员把自己的状态发给房主, 房主记进名单、版本加一,
 * 再把整份快照发回房间。
 *
 * 只管名单与快照, 不碰网络与计时 (谁来了、谁走了、多久算掉线由调用方判断后告诉它), 不是线程安全的。
 *
 * @param now 房间时间, 也就是房主设备的时钟
 */
internal class TogetherRoomHost(
    private val selfId: String,
    private val now: () -> Long = ::currentTimeMillis,
) {
    private val members = LinkedHashMap<String, TogetherMember>()
    private var closeReason: TogetherCloseReason? = null

    /** 标成掉线的成员 → 标的那一刻 */
    private val droppedAt = HashMap<String, Long>()
    private var version = 0L
    private var closed = false

    /** 当前名单的快照; [TogetherRoomSnapshot.roomTime] 是取的这一刻 */
    fun snapshot(): TogetherRoomSnapshot = TogetherRoomSnapshot(
        version = version,
        status = if (closed) TogetherRoomStatus.CLOSED else TogetherRoomStatus.OPEN,
        hostId = selfId,
        roomTime = now(),
        members = members.values.toList(),
        playback = members[selfId]?.watching?.let { TogetherPlayback(it) },
        closeReason = closeReason.takeIf { closed },
    )

    /**
     * 建房间: 名单里只有房主自己。版本号从这一刻的毫秒数起步, 所以房主的应用重开、重新建房后发出的版本一定比之前发过的大,
     * 还留在房间里的成员照样认。
     */
    fun open(self: TogetherSelfReport): TogetherRoomSnapshot {
        members.clear()
        droppedAt.clear()
        closed = false
        closeReason = null
        version = now() - 1
        put(selfId, self)
        return bump()
    }

    /** 有人进来, 或掉线的人回来了 */
    fun onHello(memberId: String, report: TogetherSelfReport): TogetherRoomSnapshot {
        put(memberId, report)
        return bump()
    }

    /**
     * 某个人 (也可以是房主自己) 的状态变了。没打过招呼的人也收进名单。
     *
     * @return 名单没变 (跟上次报的一样) 时为 `null`, 不用再发快照
     */
    fun onReport(memberId: String, report: TogetherSelfReport): TogetherRoomSnapshot? {
        val before = members[memberId]
        put(memberId, report)
        val after = members.getValue(memberId)
        return if (before != null && before.copy(lastSeenAt = after.lastSeenAt) == after) null else bump()
    }

    /** 成员主动离开。不在名单里、或说的是房主自己时为 `null` */
    fun onLeft(memberId: String): TogetherRoomSnapshot? {
        if (memberId == selfId || members.remove(memberId) == null) return null
        droppedAt.remove(memberId)
        return bump()
    }

    /** 成员的连接异常断开后一直没回来: 留在名单里, 标成掉线 */
    fun onDropped(memberId: String): TogetherRoomSnapshot? {
        val member = members[memberId] ?: return null
        if (memberId == selfId || member.state == TogetherMemberState.DISCONNECTED) return null
        members[memberId] = member.copy(state = TogetherMemberState.DISCONNECTED)
        droppedAt[memberId] = now()
        return bump()
    }

    /**
     * 标成掉线后 [olderThanMillis] 毫秒还没回来的成员从名单里去掉。从标成掉线那一刻算, 不是从他最后一条消息算:
     * 成员只在状态变化时说话, 安静地看了半集再掉线的人, 最后一条消息早就是很久以前的了。
     */
    fun pruneDisconnected(olderThanMillis: Long): TogetherRoomSnapshot? {
        val deadline = now() - olderThanMillis
        val gone = members.values.filter {
            it.state == TogetherMemberState.DISCONNECTED && (droppedAt[it.memberId] ?: it.lastSeenAt) <= deadline
        }
        if (gone.isEmpty()) return null
        gone.forEach {
            members.remove(it.memberId)
            droppedAt.remove(it.memberId)
        }
        return bump()
    }

    /** 房主关掉房间: 解散, 或者换了密码 */
    fun close(reason: TogetherCloseReason = TogetherCloseReason.DISBANDED): TogetherRoomSnapshot {
        closed = true
        closeReason = reason
        return bump()
    }

    fun contains(memberId: String): Boolean = memberId in members

    private fun put(memberId: String, report: TogetherSelfReport) {
        val time = now()
        droppedAt.remove(memberId)
        members[memberId] = TogetherMember(
            memberId = memberId,
            nickname = report.nickname,
            isHost = memberId == selfId,
            following = report.following,
            state = if (report.watching != null) TogetherMemberState.WATCHING else TogetherMemberState.IDLE,
            lastSeenAt = time,
            watching = report.watching,
        )
    }

    private fun bump(): TogetherRoomSnapshot {
        version++
        return snapshot()
    }
}

/**
 * 房间时间: 房主设备的时钟。成员靠一来一回量出自己的时钟与它的差: 发出的时刻、房主回应里带的房间时间、收到回应的时刻, 取来回的中点。
 * 房主自己的差是 0。
 *
 * 取中点的误差最多是来回时间的一半 (去程与回程不一样快), 公共代理上来回几百毫秒; 这个误差会成为成员与房主之间的固定偏差,
 * 所以量几次取来回最快的那次 (误差最小): 一轮里来回更快的才替换。来回超过 [MAX_SAMPLE_RTT_MILLIS] 的那次不准, 已经有一个差值时不用它。
 * 两边的时钟会慢慢走偏、也会被系统对时拨动: 隔一阵重新量一轮 ([startRound]), 新一轮的第一次直接替换旧的。
 */
class TogetherRoomClock(
    private val localNowMillis: () -> Long = ::currentTimeMillis,
) {
    private class Sample(val offsetMillis: Long, val roundTripMillis: Long)

    private val best = atomic<Sample?>(null)
    private val freshRound = atomic(false)

    fun now(): Long = localNowMillis() + (best.value?.offsetMillis ?: 0L)

    /** @return 这次量的被采用了 */
    fun recordSample(roomTimeMillis: Long, sentAtMillis: Long, receivedAtMillis: Long): Boolean {
        val roundTrip = receivedAtMillis - sentAtMillis
        if (roundTrip < 0) return false
        val current = best.value
        if (current != null && roundTrip > MAX_SAMPLE_RTT_MILLIS) return false
        if (current != null && !freshRound.value && roundTrip > current.roundTripMillis) return false
        freshRound.value = false
        best.value = Sample(roomTimeMillis - (sentAtMillis + roundTrip / 2), roundTrip)
        return true
    }

    /** 开始新一轮对表: 之前量的不再拿来比, 这一轮的第一次直接用, 之后取来回最快的 */
    fun startRound() {
        freshRound.value = true
    }

    internal fun currentOffsetMillis(): Long = best.value?.offsetMillis ?: 0L

    /** 采用的那一次对表消息一来一回用了多久; 还没对过时为 null */
    internal fun currentRoundTripMillis(): Long? = best.value?.roundTripMillis

    private companion object {
        const val MAX_SAMPLE_RTT_MILLIS = 2_000L
    }
}
