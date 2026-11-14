/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

/**
 * 一个成员此刻在做什么, 界面上照它写状态 (电视弹窗的成员卡片与控制台的成员行同一套, 见 [activity])。
 */
enum class TogetherMemberActivity {
    /** 掉线了, 还没回来 */
    OFFLINE,

    /** 自由观看 (不跟随房主): 播什么、播到哪都是他自己的事, 不细分 */
    FREE,

    /** 没有打开任何一集 */
    IDLE,

    /** 打开了一集, 片源还没装好, 或只在后台准备着 (播放页不在眼前) */
    PREPARING,

    /** 想播, 卡在缓冲上 */
    BUFFERING,

    PAUSED,
    PLAYING,
}

/** 有播放会话不等于在看: 加载中、后台准备、缓冲、暂停各是各的 */
fun TogetherMember.activity(): TogetherMemberActivity {
    val watching = watching
    return when {
        state == TogetherMemberState.DISCONNECTED -> TogetherMemberActivity.OFFLINE
        !isHost && !following -> TogetherMemberActivity.FREE
        watching == null -> TogetherMemberActivity.IDLE
        watching.loading || !watching.onScreen -> TogetherMemberActivity.PREPARING
        watching.paused -> TogetherMemberActivity.PAUSED
        watching.buffering -> TogetherMemberActivity.BUFFERING
        else -> TogetherMemberActivity.PLAYING
    }
}

/**
 * 这台设备在房间里的处境, 标题与说明都照它写 (电视弹窗与控制台卡片同一套, 见 [togetherSituation])。
 */
enum class TogetherSituation {
    /** 自己是房主, 还没打开任何一集 */
    HOST_IDLE,
    HOST_WATCHING,

    /** 成员, 关着「跟随房主播放」 */
    MEMBER_FREE,

    /** 跟随中, 房主掉线了 */
    MEMBER_HOST_OFFLINE,

    /** 跟随中, 房主还没打开任何一集 */
    MEMBER_WAITING,
    MEMBER_FOLLOWING,
}

/**
 * 先看模式, 再看房主在不在: 自由观看的人不等房主, 房主掉线时他的处境还是自由观看。
 *
 * @param following 自己开没开「跟随房主播放」; 房主自己的这一项不起作用
 */
fun togetherSituation(snapshot: TogetherRoomSnapshot, isHost: Boolean, following: Boolean): TogetherSituation {
    val hostPlaying = snapshot.playback != null
    return when {
        isHost -> if (hostPlaying) TogetherSituation.HOST_WATCHING else TogetherSituation.HOST_IDLE
        !following -> TogetherSituation.MEMBER_FREE
        snapshot.members.firstOrNull { it.isHost }?.state == TogetherMemberState.DISCONNECTED -> TogetherSituation.MEMBER_HOST_OFFLINE
        !hostPlaying -> TogetherSituation.MEMBER_WAITING
        else -> TogetherSituation.MEMBER_FOLLOWING
    }
}
