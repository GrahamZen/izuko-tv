/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.domain.together.TogetherInvite
import me.him188.ani.app.domain.together.TogetherJoinException
import me.him188.ani.app.domain.together.TogetherNoBrokersException
import me.him188.ani.app.domain.together.TogetherManager
import me.him188.ani.app.domain.together.TogetherRoomEmptyException
import me.him188.ani.app.domain.together.TogetherRoomId
import me.him188.ani.app.domain.together.TogetherState
import me.him188.ani.app.domain.together.TogetherWatchingInfo
import me.him188.ani.app.domain.together.TogetherWrongPasswordException
import me.him188.ani.app.domain.together.activity
import me.him188.ani.app.domain.together.formatTogetherRoomNumber
import me.him188.ani.app.domain.together.formatTogetherRoomPassword
import me.him188.ani.app.domain.together.togetherSituation
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds

/**
 * 控制台的一起看 (docs/contributing/code/together.md):
 *
 * - `GET api/together`: 在不在房间里、是不是房主、房间号与密码、发给朋友的邀请 (`invite`)、跟不跟随、连接状态、成员名单、
 *   房主播到哪 (`positionNow` 是按房间时间推到此刻的位置)。
 * - `POST api/together/create`: 打开电视自己的房间 (房间号是电视的设备号)。
 * - `POST api/together/join` `number=<房间号> password=<密码>` 或 `invite=<朋友发来的一段话>`: 加入别人的房间, 当成员。
 *   房间里没有房主、密码不对时失败, 不会自己当房主。等到进去或失败才回。
 * - `POST api/together/password`: 电视自己的房间换一个随机密码; 开着房间时房间里的人要用新密码重新加入。
 * - `POST api/together/leave`: 离开; 房主离开 = 解散。
 * - `POST api/together/follow` `value=1|0`: 「跟随房主播放」。
 * - `POST api/together/transfer` `member=<成员编号>`: 电视是房主时把房主转让给这个成员。当场回 (状态里 `transferring` 是在等谁接手),
 *   成没成看之后的状态 (`host`) 与电视上的提示。
 * - 状态里的 `recent` 是最近进过的别人的房间 (房间号、房主的名字与编号, 不带密码);
 *   `POST api/together/recent/join` `number=<房间号>` 用电视记下的密码再进, `POST api/together/recent/forget` `number=<房间号>` 去掉一个。
 */
internal object RemoteTogether {
    private val manager: TogetherManager get() = KoinPlatform.getKoin().get()

    fun handle(request: LanHttpRequest): JsonObject? {
        val post = request.method == "POST"
        return when (request.path) {
            "api/together" -> state()
            "api/together/create" -> if (post) create() else null
            "api/together/join" -> if (post) join(request.formFields()) else null
            "api/together/password" -> if (post) changePassword() else null
            "api/together/leave" -> if (post) leave() else null
            "api/together/follow" -> if (post) follow(request.formFields()["value"] == "1") else null
            "api/together/transfer" -> if (post) transfer(request.formFields()["member"].orEmpty()) else null
            "api/together/recent/join" -> if (post) joinRecent(request.formFields()["number"].orEmpty()) else null
            "api/together/recent/forget" -> if (post) forgetRecent(request.formFields()["number"].orEmpty()) else null
            else -> null
        }
    }

    /** 发给朋友的一段话: 房间号与密码都在里面, 朋友在电视上输入或粘贴到自己电视的控制台就能加入 */
    fun inviteText(room: TogetherRoomId): String =
        tr(INVITE_TEMPLATE, formatTogetherRoomNumber(room.number), formatTogetherRoomPassword(room.password))

    private fun create(): JsonObject = outcome(runBlocking { withTimeoutOrNull(JOIN_TIMEOUT) { manager.createRoom().map { } } })

    private fun join(form: Map<String, String>): JsonObject {
        val room = TogetherRoomId.of(form["number"].orEmpty(), form["password"].orEmpty())
            ?: form["invite"]?.let { TogetherInvite.parse(it) }
            ?: return result(false, tr("请输入 8 位房间号和 6 位密码"))
        return outcome(runBlocking { withTimeoutOrNull(JOIN_TIMEOUT) { manager.join(room) } })
    }

    private fun joinRecent(number: String): JsonObject = outcome(runBlocking { withTimeoutOrNull(JOIN_TIMEOUT) { manager.joinRecent(number) } })

    private fun forgetRecent(number: String): JsonObject {
        runBlocking { manager.forgetRecentRoom(number) }
        return state()
    }

    private fun changePassword(): JsonObject = outcome(runBlocking { withTimeoutOrNull(JOIN_TIMEOUT) { manager.changePassword().map { } } })

    private fun outcome(done: Result<Unit>?): JsonObject {
        done ?: return result(false, tr("连接超时，请重试"))
        val failure = done.exceptionOrNull() ?: return state()
        return result(
            false,
            when (failure) {
                is TogetherJoinException -> tr("无法连接服务，请检查网络后重试。")
                is TogetherNoBrokersException -> tr("暂时无法获取一起看的服务器列表，请检查网络后重试。")
                is TogetherRoomEmptyException -> tr("未能连接到房主。请检查房间号，并确认房主已开启房间。")
                is TogetherWrongPasswordException -> tr("密码不正确，请重新输入。")
                // 总管只在空闲时接新的进房请求: 电视那边正在进, 或已经在房间里了
                is IllegalStateException ->
                    if (manager.state.value is TogetherState.Joining) tr("正在加入房间，请稍候。") else tr("已在房间中，请先离开当前房间。")

                else -> {
                    // 具体原因留在日志里, 界面上不露出异常的文字与类名
                    logger.warn(failure) { "A together request from the console failed" }
                    tr("无法完成操作，请重试。")
                }
            },
        )
    }

    private fun leave(): JsonObject {
        runBlocking { withTimeoutOrNull(LEAVE_TIMEOUT) { manager.leave() } }
        return state()
    }

    private fun follow(following: Boolean): JsonObject {
        runBlocking { manager.setFollowing(following) }
        return state()
    }

    private fun transfer(memberId: String): JsonObject {
        runBlocking { manager.transferHost(memberId) }
        return state()
    }

    private fun state(): JsonObject = buildJsonObject {
        put("ok", true)
        when (val state = manager.state.value) {
            TogetherState.Idle -> put("state", "idle")
            is TogetherState.Joining -> {
                put("state", "joining")
                put("number", state.number)
                // 正在开自己的房间, 还是在加入别人的 (按钮上的字分开写)
                put("opening", state.number == runBlocking { manager.ownRoom().number })
                // 房主换了人, 正以新的身份回同一个房间: 卡片上说「正在切换房主…」, 不显示进房的表单
                put("handoff", state.handoff)
            }

            is TogetherState.InRoom -> {
                val session = state.session
                val snapshot = session.snapshot.value
                val now = session.clock.now()
                put("state", "in_room")
                put("number", session.roomId.number)
                put("password", session.roomId.password)
                put("invite", inviteText(session.roomId))
                put("host", session.isHost)
                // 房间号是这台电视的设备号: 只有它能更换密码 (被请来临时当房主的不能)
                put("owner", session.ownsRoom)
                session.pendingTransfer.value?.let { put("transferring", it) }
                // 这台电视在成员名单里是哪一个 (卡片上标「此电视」)
                put("self", session.selfId)
                put("following", session.following.value)
                // 这台电视的处境, 标题与说明照它写 (与电视弹窗同一套, 见 togetherSituation)
                put("situation", togetherSituation(snapshot, session.isHost, session.following.value).name.lowercase())
                put("connection", session.connection.value.name.lowercase())
                put("version", snapshot.version)
                putJsonArray("members") {
                    for (member in snapshot.members) {
                        addJsonObject {
                            put("id", member.memberId)
                            put("nickname", member.nickname)
                            put("host", member.isHost)
                            put("following", member.following)
                            put("state", member.state.name.lowercase())
                            // 此刻在做什么 (见 TogetherMember.activity): 有播放会话不等于在看
                            put("activity", member.activity().name.lowercase())
                            member.watching?.let { putJsonObject("watching") { watching(it, now) } }
                        }
                    }
                }
                snapshot.playback?.let { putJsonObject("playback") { watching(it.info, now) } }
            }
        }
        // 最近的房间: 不带密码 (再进时由电视用记下的密码进)
        putJsonArray("recent") {
            for (room in manager.recentRooms.value) {
                addJsonObject {
                    put("number", room.number)
                    put("nickname", room.hostName)
                    put("hostId", room.hostMemberId)
                }
            }
        }
    }

    private fun JsonObjectBuilder.watching(info: TogetherWatchingInfo, roomNow: Long) {
        put("subjectId", info.subjectId)
        put("episodeId", info.episodeId)
        put("subject", info.subjectName)
        put("sort", info.episodeSort)
        put("position", info.positionMillis)
        put("positionNow", info.positionAt(roomNow))
        put("duration", info.durationMillis)
        put("paused", info.paused)
        put("buffering", info.buffering)
        put("loading", info.loading)
        put("speed", info.playbackRate)
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    /** 邀请的原文 (译文在 [togetherTexts]) */
    internal const val INVITE_TEMPLATE =
        "Izuko TV 一起看：房间号 {0}，密码 {1}。在电视的「一起看」中输入房间号和密码，或在自己电视的 Web 控制台「一起看」中粘贴此邀请。"

    private val logger = logger<RemoteTogether>()

    private val JOIN_TIMEOUT = 40.seconds
    private val LEAVE_TIMEOUT = 5.seconds
}

internal fun togetherTexts() = listOf(
    RemoteText("一起看", "Watch Together", "一齊睇", "一起看"),
    RemoteText("已离线", "Offline", "已離線", "已離線"),
    RemoteText("自由观看", "Watching independently", "自由觀看", "自由觀看"),
    RemoteText("准备中 · 第 {0} 话", "Preparing · Ep. {0}", "準備中 · 第 {0} 話", "準備中 · 第 {0} 話"),
    RemoteText("缓冲中 · 第 {0} 话", "Buffering · Ep. {0}", "緩衝中 · 第 {0} 話", "緩衝中 · 第 {0} 話"),
    RemoteText("已暂停 · 第 {0} 话", "Paused · Ep. {0}", "已暫停 · 第 {0} 話", "已暫停 · 第 {0} 話"),
    RemoteText("播放中 · 第 {0} 话", "Playing · Ep. {0}", "播放中 · 第 {0} 話", "播放中 · 第 {0} 話"),
    RemoteText("未播放", "Not playing", "未播放", "未播放"),
    RemoteText("电视正在重新连接房间…", "The TV is reconnecting to the room…", "電視正在重新連接房間…", "電視正在重新連線到房間…"),
    RemoteText("此电视是房主。已开启跟随的成员将同步播放。", "This TV is the host. Members who follow it play in sync.", "此電視是房主。已開啟跟隨的成員將同步播放。", "此電視是房主。已開啟跟隨的成員將同步播放。"),
    RemoteText("电视正在跟随房主播放。", "The TV is following the host.", "電視正在跟隨房主播放。", "電視正在跟隨房主播放。"),
    RemoteText("房主已离线，重新连接后将恢复同步。", "The host is offline. Sync resumes when the host reconnects.", "房主已離線，重新連接後將恢復同步。", "房主已離線，重新連線後將恢復同步。"),
    RemoteText("房主开始观看后，将自动跟随播放。", "Playback will follow automatically once the host starts watching.", "房主開始觀看後，將自動跟隨播放。", "房主開始觀看後，將自動跟隨播放。"),
    RemoteText("电视正在自由观看，播放不随房主变化。", "The TV is watching independently. Playback does not follow the host.", "電視正在自由觀看，播放不隨房主變化。", "電視正在自由觀看，播放不隨房主變化。"),
    RemoteText("正在加载房间信息…", "Loading the room…", "正在載入房間資訊…", "正在載入房間資訊…"),
    RemoteText("成员 · {0}", "Members · {0}", "成員 · {0}", "成員 · {0}"),
    RemoteText("房间已开启", "Room open", "房間已開啟", "房間已開啟"),
    RemoteText("已加入房间", "Joined", "已加入房間", "已加入房間"),
    RemoteText("正在重新连接…", "Reconnecting…", "正在重新連接…", "正在重新連線…"),
    RemoteText("选择剧集，开始一起看", "Pick an episode to start watching together", "選擇劇集，開始一齊睇", "選擇集數，開始一起看"),
    RemoteText("正在一起看《{0}》第 {1} 话", "Watching {0}, Ep. {1} together", "正在一齊睇《{0}》第 {1} 話", "正在一起看《{0}》第 {1} 話"),
    RemoteText("等待房主开始播放", "Waiting for the host to start", "等待房主開始播放", "等待房主開始播放"),
    RemoteText("等待房主重新连接", "Waiting for the host to reconnect", "等待房主重新連接", "等待房主重新連線"),
    RemoteText("此电视", "This TV", "此電視", "此電視"),
    RemoteText("成员", "Member", "成員", "成員"),
    // 播放卡上的数据源名: 正在播别处交来的地址 (见 ExternalLinkMedia)
    RemoteText("房间成员共享", "Shared by a room member", "房間成員共享", "房間成員共享"),
    RemoteText("外部链接", "External link", "外部連結", "外部連結"),
    RemoteText("房间号", "Room number", "房間號", "房間號"),
    RemoteText("8 位数字", "8 digits", "8 位數字", "8 位數字"),
    RemoteText("6 位数字", "6 digits", "6 位數字", "6 位數字"),
    RemoteText("观众", "Viewer", "觀眾", "觀眾"),
    RemoteText("房主", "Host", "房主", "房主"),
    RemoteText("转让房主", "Transfer host", "轉讓房主", "轉讓房主"),
    RemoteText("正在转让…", "Transferring…", "正在轉讓…", "正在轉讓…"),
    RemoteText("正在切换房主…", "Switching host…", "正在切換房主…", "正在切換房主…"),
    RemoteText(
        "将房主转让给 {0}？转让后此电视将成为成员。", "Make {0} the host? This TV will become a member.",
        "將房主轉讓給 {0}？轉讓後此電視將成為成員。", "將房主轉讓給 {0}？轉讓後此電視將成為成員。",
    ),
    RemoteText("正在将房主转让给 {0}…", "Transferring host to {0}…", "正在將房主轉讓給 {0}…", "正在將房主轉讓給 {0}…"),
    RemoteText("改为自由观看", "Watch independently", "改為自由觀看", "改為自由觀看"),
    RemoteText("跟随房主播放", "Follow the host", "跟隨房主播放", "跟隨房主播放"),
    RemoteText("解散房间", "Disband room", "解散房間", "解散房間"),
    RemoteText("离开房间", "Leave room", "離開房間", "離開房間"),
    RemoteText("与朋友同步观看同一集，由房主控制播放。", "Watch the same episode in sync with friends. The host controls playback.", "與朋友同步觀看同一集，由房主控制播放。", "與朋友同步觀看同一集，由房主控制播放。"),
    RemoteText("将邀请粘贴到任一输入框，即可加入房间。", "Paste the invite into either box to join the room.", "將邀請貼上到任一輸入框，即可加入房間。", "將邀請貼上到任一輸入框，即可加入房間。"),
    RemoteText("加入房间", "Join room", "加入房間", "加入房間"),
    RemoteText("正在开启房间…", "Opening the room…", "正在開啟房間…", "正在開啟房間…"),
    RemoteText("正在加入房间…", "Joining the room…", "正在加入房間…", "正在加入房間…"),
    RemoteText("开启房间", "Open room", "開啟房間", "開啟房間"),
    RemoteText("最近的房间", "Recent rooms", "最近的房間", "最近的房間"),
    RemoteText("加入", "Join", "加入", "加入"),
    RemoteText("正在加入…", "Joining…", "正在加入…", "正在加入…"),
    RemoteText("无法完成操作，请重试。", "Unable to complete the action. Please try again.", "無法完成操作，請重試。", "無法完成操作，請重試。"),
    RemoteText("请输入 8 位房间号和 6 位密码", "Enter the 8-digit room number and the 6-digit password", "請輸入 8 位房間號和 6 位密碼", "請輸入 8 位房間號和 6 位密碼"),
    RemoteText("电视已加入房间", "The TV joined the room", "電視已加入房間", "電視已加入房間"),
    RemoteText("房间 {0} 已开启", "Room {0} is open", "房間 {0} 已開啟", "房間 {0} 已開啟"),
    RemoteText("解散后所有成员都会退出房间。", "Disbanding the room removes every member from it.", "解散後所有成員都會退出房間。", "解散後所有成員都會退出房間。"),
    RemoteText("已解散房间", "Room disbanded", "已解散房間", "已解散房間"),
    RemoteText("电视已离开房间", "The TV left the room", "電視已離開房間", "電視已離開房間"),
    RemoteText("复制邀请", "Copy invite", "複製邀請", "複製邀請"),
    RemoteText("已复制邀请", "Invite copied", "已複製邀請", "已複製邀請"),
    RemoteText("更换密码", "Change password", "更換密碼", "更換密碼"),
    RemoteText("更换密码后，其他成员需要使用新密码重新加入。", "After the password changes, other members have to join again with the new password.", "更換密碼後，其他成員需要使用新密碼重新加入。", "更換密碼後，其他成員需要使用新密碼重新加入。"),
    RemoteText("已更换密码", "Password changed", "已更換密碼", "已更換密碼"),
    RemoteText("无法连接服务，请检查网络后重试。", "Can’t reach the service. Check your network and try again.", "無法連接服務，請檢查網絡後重試。", "無法連線到服務，請檢查網路後重試。"),
    RemoteText("暂时无法获取一起看的服务器列表，请检查网络后重试。", "Can’t get the server list right now. Check your network and try again.", "暫時無法取得一齊睇的伺服器列表，請檢查網絡後重試。", "暫時無法取得一起看的伺服器清單，請檢查網路後重試。"),
    RemoteText("未能连接到房主。请检查房间号，并确认房主已开启房间。", "Couldn’t reach the host. Check the room number and make sure the host has opened the room.", "未能連接到房主。請檢查房間號，並確認房主已開啟房間。", "未能連線到房主。請檢查房間號，並確認房主已開啟房間。"),
    RemoteText("密码不正确，请重新输入。", "The password is incorrect. Please try again.", "密碼不正確，請重新輸入。", "密碼不正確，請重新輸入。"),
    RemoteText("已在房间中，请先离开当前房间。", "Already in a room. Leave the current room first.", "已在房間中，請先離開目前房間。", "已在房間中，請先離開目前房間。"),
    RemoteText("正在加入房间，请稍候。", "Joining a room. Please wait.", "正在加入房間，請稍候。", "正在加入房間，請稍候。"),
    RemoteText(
        RemoteTogether.INVITE_TEMPLATE,
        "Izuko TV Watch Together: room {0}, password {1}. Enter the room number and password in “Watch Together” on your TV, or paste this invite into “Watch Together” in your own TV’s Web console.",
        "Izuko TV 一齊睇：房間號 {0}，密碼 {1}。在電視的「一齊睇」中輸入房間號和密碼，或在自己電視的 Web 控制台「一齊睇」中貼上此邀請。",
        "Izuko TV 一起看：房間號 {0}，密碼 {1}。在電視的「一起看」中輸入房間號和密碼，或在自己電視的 Web 控制台「一起看」中貼上此邀請。",
    ),
)
