/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

/** 一起看 (docs/contributing/code/together.md) */
@Immutable
@Serializable
data class TogetherSettings(
    /** 「跟随房主播放」 */
    val followHost: Boolean = true,
    /** 1.0.9-alpha04 及以前只记上次加入的一个房间; 读的时候当作 [recentRooms] 里唯一的一个 (见 [recentRoomList]), 记新的房间时清掉 */
    val lastJoined: TogetherRoomRef? = null,
    /** 最近进过的别人的房间, 最近的在前 (domain/together/TogetherRecentRooms.kt) */
    val recentRooms: List<TogetherRecentRoom> = emptyList(),
    /** 还没离开的房间; 应用重开后自动回去 */
    val rememberedRoom: RememberedTogetherRoom? = null,
    /**
     * 这台设备在房间里的编号, 第一次用一起看时随机生成, 之后不变: 房主的应用重开后成员靠它认出还是原来的房主。
     * 只出现在房间里加密的消息里。
     */
    val memberId: String = "",
    /** 这台设备的设备号 = 自己房间的房间号, 第一次用一起看时随机生成, 之后不变 */
    val deviceNumber: String = "",
    /** 自己房间现在的密码, 第一次用一起看时随机生成; 房主换密码时换掉 */
    val roomPassword: String = "",
) {
    /** 最近进过的别人的房间; 旧版本只记了 [lastJoined] 的, 当作唯一的一个 */
    val recentRoomList: List<TogetherRecentRoom>
        get() = recentRooms.ifEmpty { listOfNotNull(lastJoined?.let { TogetherRecentRoom(it.number, it.password) }) }

    companion object {
        @Stable
        val Default = TogetherSettings()
    }
}

/** 一个房间的房间号与密码 */
@Immutable
@Serializable
data class TogetherRoomRef(
    val number: String,
    val password: String,
)

@Immutable
@Serializable
data class RememberedTogetherRoom(
    val number: String = "",
    val password: String = "",
    /** 是自己的房间 (回去时当房主重新开); 否则是加入的别人的房间 */
    val own: Boolean = false,
    val joinedAt: Long = 0,
)

/** 最近进过的一个别人的房间: 弹窗与控制台里按一下就用记下的密码再进 */
@Immutable
@Serializable
data class TogetherRecentRoom(
    val number: String,
    val password: String,
    /** 房主的名字, 在房间里时看到的; 没登录 Bangumi 的为空 */
    val hostName: String = "",
    /** 房主的成员编号: 没有名字时显示成「观众 + 编号头几位」; 还没看到过房主时为空 */
    val hostMemberId: String = "",
    /** 最近一次进这个房间的时刻 */
    val joinedAt: Long = 0,
)
