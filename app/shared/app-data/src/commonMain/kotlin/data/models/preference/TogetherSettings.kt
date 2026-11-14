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
    /** 上次加入的别人的房间, 加入时先填上 */
    val lastJoined: TogetherRoomRef? = null,
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
