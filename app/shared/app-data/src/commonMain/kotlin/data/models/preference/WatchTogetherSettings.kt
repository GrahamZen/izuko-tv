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

@Immutable
@Serializable
data class WatchTogetherSettings(
    /**
     * 上游默认关: 那时入口是一颗**飘在画面上可拖的悬浮气泡**, 不用的人会觉得挡视线,
     * 所以默认不出现比较合适.
     *
     * fork 改成默认开: 遥控器形态上气泡是用不了的 (焦点跳进去出不来, 也没法拖), 入口改成了
     * 长按返回那个动作面板里的一颗图标 —— 它平时根本不在视野里, 不用的人**看不见也碰不到**,
     * 于是"默认关"只剩下"想用的人得先去设置里找开关"这一个效果。
     *
     * 关掉仍然有意义, 所以开关本身保留: 它是整个子系统的总闸 (见 WatchTogetherManager.start),
     * 关掉会退出房间、清掉记住的会话, 并让跟随模式那套对播放器的干预 (自动暂停让位、
     * 换集导航守卫) 完全不参与.
     */
    val enabled: Boolean = true,
    val followHost: Boolean = true,
    val lastRoomName: String = "",
    val rememberedSession: RememberedRoomSession? = null,
) {
    companion object {
        @Stable
        val Default = WatchTogetherSettings()
    }
}

@Immutable
@Serializable
data class RememberedRoomSession(
    val roomName: String,
    val password: String,
    val joinedAt: Long,
)
