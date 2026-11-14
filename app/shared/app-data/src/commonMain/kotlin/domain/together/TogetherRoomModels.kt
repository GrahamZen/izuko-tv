/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.media.ExternalLink
import me.him188.ani.app.domain.media.continuation.PlaybackLineage
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * 一个人正在看什么、看到哪。时刻都是**房间时间** (房主设备的时钟, 成员按进房时量出的差值换算, 见 [TogetherRoomClock])。
 */
@Serializable
data class TogetherWatchingInfo(
    val subjectId: Int,
    val episodeId: Int,
    val subjectName: String,
    val episodeSort: String,
    val episodeName: String,
    /** [positionAtMillis] 那一刻的播放位置 */
    val positionMillis: Long,
    val positionAtMillis: Long,
    /** 片长; 还不知道时为 0 */
    val durationMillis: Long,
    /** 播放意图: 用户按了暂停。缓冲中画面不动但不算暂停 */
    val paused: Boolean,
    val buffering: Boolean = false,
    /** 片源还没装进播放器、或还不知道片长 */
    val loading: Boolean = false,
    val playbackRate: Float = 1f,
    /**
     * 这一集的播放页在不在眼前。只在后台准备着这一集 (动作面板「在后台准备」, 或退出播放页后会话留在后台) 时为 `false`:
     * 房主这样时, 不在播放页的成员也只在后台准备, 房主进了播放页再进。
     */
    val onScreen: Boolean = true,
    /**
     * 正在播的地址, 给片源还没装好的人直接播 (见 [TogetherSourceHints]); 还没装好、不能交给别人的 (网盘、BT、本机文件…) 为 `null`
     */
    val link: ExternalLink? = null,
    /**
     * 正在播的资源的播放线索, 给还没选上源的人在自己的同名数据源里找同一个资源; 还没装好、BT、自己网盘里的为 `null`
     */
    val lineage: PlaybackLineage? = null,
) {
    /** 画面此刻不往前走: 用户暂停了, 或卡在缓冲 / 加载上 */
    val holding: Boolean get() = paused || buffering || loading

    /** [nowMillis] (房间时间) 时应该播到哪: 暂停、缓冲、加载中不往前走, 否则按倍速往前推 */
    fun positionAt(nowMillis: Long): Long {
        val elapsed = (nowMillis - positionAtMillis).coerceAtLeast(0L)
        val extrapolated = if (holding) positionMillis else positionMillis + (elapsed * playbackRate).roundToLong()
        return if (durationMillis > 0L) extrapolated.coerceIn(0L, durationMillis) else extrapolated.coerceAtLeast(0L)
    }
}

/** 房主的播放状态, 跟随的成员照它对齐 */
@Serializable
data class TogetherPlayback(
    val info: TogetherWatchingInfo,
)

@Serializable
enum class TogetherMemberState {
    /** 在房间里, 没在播放页 */
    IDLE,
    WATCHING,

    /** 连接异常断开, 还没回来 */
    DISCONNECTED,
}

@Serializable
data class TogetherMember(
    val memberId: String,
    val nickname: String,
    val isHost: Boolean,
    /** 「跟随房主播放」开着; 关着是自由观看 */
    val following: Boolean,
    val state: TogetherMemberState,
    /** 房主最后一次收到他消息的时刻 (房间时间) */
    val lastSeenAt: Long,
    val watching: TogetherWatchingInfo? = null,
)

@Serializable
enum class TogetherRoomStatus { OPEN, CLOSED }

/** 房间为什么关了 */
@Serializable
enum class TogetherCloseReason {
    /** 房主解散了房间 */
    DISBANDED,

    /** 房主换了密码: 房间里的人要用新密码重新加入 */
    PASSWORD_CHANGED,
}

/**
 * 房间的全貌, 由房主汇总后发给房间。[version] 每变一次加一 (从建房时刻的毫秒数起步), 成员只认同一个房主发来的、不比手上旧的。
 */
@Serializable
data class TogetherRoomSnapshot(
    val version: Long,
    val status: TogetherRoomStatus,
    val hostId: String,
    /** 发出这份快照时的房间时间 */
    val roomTime: Long,
    val members: List<TogetherMember>,
    /** 房主没在播放页时为 `null` */
    val playback: TogetherPlayback? = null,
    /** [status] 为 CLOSED 时: 为什么关的 */
    val closeReason: TogetherCloseReason? = null,
)

/** 一个人向房间报告的自己的状态 */
@Serializable
data class TogetherSelfReport(
    val nickname: String,
    val following: Boolean,
    val watching: TogetherWatchingInfo? = null,
)

/**
 * 房间里的消息。成员只跟房主对话: 成员发 [Hello] / [Report] / [Bye] / [TimeQuery], 房主发 [Welcome] / [Snapshot] / [Handover] / [Ping] / [TimeReply]。
 */
@Serializable
sealed interface TogetherMessage {
    val sender: String

    /** 进房间或断线后回来。房主回一条 [Welcome]; 没有房主回应 = 房间是空的, 发的人自己当房主 */
    @Serializable
    @SerialName("hello")
    data class Hello(
        override val sender: String,
        val report: TogetherSelfReport,
        /** 发出时刻 (发的人自己的时钟), 房主原样带回, 用来量两边时钟的差 */
        val sentAt: Long,
    ) : TogetherMessage

    /** 房主给刚进来的那个人 [to] 的回应; 房间里其他人把它当一份 [Snapshot] 用 */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        override val sender: String,
        val to: String,
        val echoSentAt: Long,
        val snapshot: TogetherRoomSnapshot,
    ) : TogetherMessage

    /** 成员的状态变了 */
    @Serializable
    @SerialName("report")
    data class Report(
        override val sender: String,
        val report: TogetherSelfReport,
    ) : TogetherMessage

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        override val sender: String,
        val snapshot: TogetherRoomSnapshot,
    ) : TogetherMessage

    /** 离开房间。[dropped] 为真的是留给代理的遗嘱: 连接异常断开时由代理代发, 人未必真走了 */
    @Serializable
    @SerialName("bye")
    data class Bye(
        override val sender: String,
        val dropped: Boolean = false,
    ) : TogetherMessage

    /**
     * 房主问 [to]: 还在吗。收到的人回一条 [Report]。房主收到某人的遗嘱时问 —— 遗嘱可能是一条早就不用的连接留下的
     * (断网后重连上了, 代理过几十秒才认定旧连接死了), 人其实一直在。
     */
    @Serializable
    @SerialName("ping")
    data class Ping(
        override val sender: String,
        val to: String,
    ) : TogetherMessage

    /** 成员对表: 问房主现在几点。[sentAt] 是发出时刻 (成员自己的时钟), 房主原样带回 */
    @Serializable
    @SerialName("time-query")
    data class TimeQuery(
        override val sender: String,
        val sentAt: Long,
    ) : TogetherMessage

    /** 房主回 [to] 的对表: 带回他问的时刻 [echoSentAt] 与房主此刻的房间时间 [roomTime] */
    @Serializable
    @SerialName("time-reply")
    data class TimeReply(
        override val sender: String,
        val to: String,
        val echoSentAt: Long,
        val roomTime: Long,
    ) : TogetherMessage

    /** 房主让位: 房间里出现了两个房主时, 让位的那个告诉自己的成员以后听 [to] 的 */
    @Serializable
    @SerialName("handover")
    data class Handover(
        override val sender: String,
        val to: String,
    ) : TogetherMessage

    /**
     * 房主请成员 [to] 来当房主 (用户在名单上选的)。[to] 以房主身份重开这个房间, 房主看到他的快照就让位 ([Handover]),
     * 别的成员跟着改听他的。认不出这条消息的旧版本不理会, 房主等不到就作罢。
     */
    @Serializable
    @SerialName("transfer-host")
    data class TransferHost(
        override val sender: String,
        val to: String,
    ) : TogetherMessage
}

internal object TogetherMessageCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "t"
    }

    fun encode(message: TogetherMessage): ByteArray = json.encodeToString(TogetherMessage.serializer(), message).encodeToByteArray()

    /** 认不出的 (别的版本的消息类型、不是 JSON) 为 `null` */
    fun decode(bytes: ByteArray): TogetherMessage? =
        try {
            json.decodeFromString(TogetherMessage.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            null
        }
}
