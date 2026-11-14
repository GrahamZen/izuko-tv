/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import me.him188.ani.app.data.models.preference.TogetherRecentRoom

/**
 * 「最近的房间」 (docs/contributing/code/together.md「最近的房间」): 进过的别人的房间记在本机设置里, 弹窗与控制台按一下用记下的密码再进。
 * 只在本机, 不经代理, 不发给任何人。最近的在前, 最多 [TOGETHER_RECENT_ROOMS_MAX] 个, 同一个房间号只占一个。
 */
const val TOGETHER_RECENT_ROOMS_MAX = 5

/** 进了 [room]: 挪到最前, 密码换成这次用的; 已经记着的房主名字留着 */
internal fun List<TogetherRecentRoom>.withJoined(room: TogetherRoomId, nowMillis: Long): List<TogetherRecentRoom> {
    val known = firstOrNull { it.number == room.number }
    val entry = (known ?: TogetherRecentRoom(room.number, room.password)).copy(password = room.password, joinedAt = nowMillis)
    return (listOf(entry) + filter { it.number != room.number }).take(TOGETHER_RECENT_ROOMS_MAX)
}

/** 记下在房间 [number] 里看到的房主 (名字晚到、改了名, 或换了房主), 顺序不变 */
internal fun List<TogetherRecentRoom>.withHost(number: String, host: TogetherMember): List<TogetherRecentRoom> =
    map { if (it.number == number) it.copy(hostName = host.nickname, hostMemberId = host.memberId) else it }

internal fun List<TogetherRecentRoom>.without(number: String): List<TogetherRecentRoom> = filter { it.number != number }

/** 这个房间此刻的房主, 不算自己 (自己被请去当房主时名单上的房主是自己, 不是房间的主人) */
internal fun TogetherRoomSnapshot.recentHost(selfId: String): TogetherMember? =
    members.firstOrNull { it.memberId == hostId && it.memberId != selfId }
