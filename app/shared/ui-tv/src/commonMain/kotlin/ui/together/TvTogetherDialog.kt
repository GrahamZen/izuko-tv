/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.together

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.TogetherRecentRoom
import me.him188.ani.app.domain.together.TogetherConnectionState
import me.him188.ani.app.domain.together.TogetherInvite
import me.him188.ani.app.domain.together.TogetherManager
import me.him188.ani.app.domain.together.TogetherMember
import me.him188.ani.app.domain.together.TogetherMemberActivity
import me.him188.ani.app.domain.together.TogetherMemberState
import me.him188.ani.app.domain.together.TogetherNoBrokersException
import me.him188.ani.app.domain.together.TogetherRoomEmptyException
import me.him188.ani.app.domain.together.TogetherRoomId
import me.him188.ani.app.domain.together.TogetherSession
import me.him188.ani.app.domain.together.TogetherSituation
import me.him188.ani.app.domain.together.TogetherState
import me.him188.ani.app.domain.together.TogetherWrongPasswordException
import me.him188.ani.app.domain.together.activity
import me.him188.ani.app.domain.together.formatTogetherRoomNumber
import me.him188.ani.app.domain.together.formatTogetherRoomPassword
import me.him188.ani.app.domain.together.togetherSituation
import me.him188.ani.app.ui.foundation.aniClickable
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.setClipEntryText
import me.him188.ani.app.ui.foundation.tv.AniOutlinedTextField
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.tvWebConsoleAvailable
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.foundation.widgets.AniFocusChip
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.ProvideScrollFadeFocusMargin
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_together_cancel
import me.him188.ani.app.ui.lang.tv_together_change_password
import me.him188.ani.app.ui.lang.tv_together_change_password_action
import me.him188.ani.app.ui.lang.tv_together_change_password_confirm
import me.him188.ani.app.ui.lang.tv_together_copy_invite_hint
import me.him188.ani.app.ui.lang.tv_together_create
import me.him188.ani.app.ui.lang.tv_together_disband
import me.him188.ani.app.ui.lang.tv_together_disband_confirm
import me.him188.ani.app.ui.lang.tv_together_disband_confirm_action
import me.him188.ani.app.ui.lang.tv_together_follow_host
import me.him188.ani.app.ui.lang.tv_together_headline_host_idle
import me.him188.ani.app.ui.lang.tv_together_headline_host_offline
import me.him188.ani.app.ui.lang.tv_together_headline_waiting
import me.him188.ani.app.ui.lang.tv_together_headline_watching
import me.him188.ani.app.ui.lang.tv_together_host_hint
import me.him188.ani.app.ui.lang.tv_together_host_idle_hint
import me.him188.ani.app.ui.lang.tv_together_host_not_playing
import me.him188.ani.app.ui.lang.tv_together_host_offline
import me.him188.ani.app.ui.lang.tv_together_intro
import me.him188.ani.app.ui.lang.tv_together_invite_copied
import me.him188.ani.app.ui.lang.tv_together_invite_heading
import me.him188.ani.app.ui.lang.tv_together_invite_text
import me.him188.ani.app.ui.lang.tv_together_join
import me.him188.ani.app.ui.lang.tv_together_join_by_invite
import me.him188.ani.app.ui.lang.tv_together_join_by_phone
import me.him188.ani.app.ui.lang.tv_together_join_empty
import me.him188.ani.app.ui.lang.tv_together_join_failed
import me.him188.ani.app.ui.lang.tv_together_join_no_servers
import me.him188.ani.app.ui.lang.tv_together_join_wrong_password
import me.him188.ani.app.ui.lang.tv_together_leave
import me.him188.ani.app.ui.lang.tv_together_member_following
import me.him188.ani.app.ui.lang.tv_together_member_free
import me.him188.ani.app.ui.lang.tv_together_members
import me.him188.ani.app.ui.lang.tv_together_password
import me.him188.ani.app.ui.lang.tv_together_password_placeholder
import me.him188.ani.app.ui.lang.tv_together_paste_invite_hint
import me.him188.ani.app.ui.lang.tv_together_qr_caption
import me.him188.ani.app.ui.lang.tv_together_qr_caption_join
import me.him188.ani.app.ui.lang.tv_together_qr_caption_join_touch
import me.him188.ani.app.ui.lang.tv_together_recent
import me.him188.ani.app.ui.lang.tv_together_reconnecting
import me.him188.ani.app.ui.lang.tv_together_role_host
import me.him188.ani.app.ui.lang.tv_together_role_member
import me.him188.ani.app.ui.lang.tv_together_room_number
import me.him188.ani.app.ui.lang.tv_together_room_number_placeholder
import me.him188.ani.app.ui.lang.tv_together_share_hint
import me.him188.ani.app.ui.lang.tv_together_state_free
import me.him188.ani.app.ui.lang.tv_together_state_idle
import me.him188.ani.app.ui.lang.tv_together_state_offline
import me.him188.ani.app.ui.lang.tv_together_state_buffering
import me.him188.ani.app.ui.lang.tv_together_state_paused
import me.him188.ani.app.ui.lang.tv_together_state_playing
import me.him188.ani.app.ui.lang.tv_together_state_preparing
import me.him188.ani.app.ui.lang.tv_together_status_joined
import me.him188.ani.app.ui.lang.tv_together_status_open
import me.him188.ani.app.ui.lang.tv_together_switching_host
import me.him188.ani.app.ui.lang.tv_together_toast_created
import me.him188.ani.app.ui.lang.tv_together_toast_disbanded
import me.him188.ani.app.ui.lang.tv_together_toast_joined
import me.him188.ani.app.ui.lang.tv_together_toast_left
import me.him188.ani.app.ui.lang.tv_together_toast_password_changed
import me.him188.ani.app.ui.lang.tv_together_toast_transferring
import me.him188.ani.app.ui.lang.tv_together_transfer_action
import me.him188.ani.app.ui.lang.tv_together_transfer_confirm
import me.him188.ani.app.ui.lang.tv_together_transfer_hint
import me.him188.ani.app.ui.lang.tv_together_viewer
import me.him188.ani.app.ui.lang.tv_together_you
import me.him188.ani.app.ui.lang.watch_together_title
import me.him188.ani.app.ui.remote.RemoteQrCode
import me.him188.ani.app.ui.remote.TvRemoteControl
import org.jetbrains.compose.resources.stringResource

/**
 * 「一起看」弹窗 (docs/contributing/code/together.md): 不在房间里时开自己的房间, 输入房间号与密码加入别人的, 或在「最近的房间」里选一个再进;
 * 在房间里时看此刻在做什么、成员、房间号与密码, 开关「跟随房主播放」, 房主可以在成员卡片上按确定转让房主、更换密码 (只有房间的主人), 离开或解散。
 * 两种都是左右两栏: 左边是这台电视的事, 右边是手机上的事 (扫码打开控制台的「一起看」卡片)。没有「关闭」按钮, 按返回关。
 * 触屏 (平板) 上邀请还能在设备自己身上复制 (长按房间号与密码) 与粘贴 (粘贴进输入框直接加入), 二维码照样在。
 * 入口在长按返回的动作面板与播放器按钮行, 见 [TvTogetherEntry]。
 *
 * 弹窗开着时同步照常进行 (它是独立窗口, 底下的页面活着): 成员可能在弹窗开着时被带进房主那一集, 弹窗不用管。
 */
@Composable
internal fun TvTogetherDialog(manager: TogetherManager, onDismissRequest: () -> Unit) {
    val state by manager.state.collectAsState()
    // 弹窗这一层的作用域: 里面的内容随房间状态换 (加入成功、离开), 动作与它之后的提示不能跟着被换掉的那一块一起取消
    val scope = rememberCoroutineScope()
    val session = (state as? TogetherState.InRoom)?.session
    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Lang.watch_together_title), Modifier.weight(1f))
                if (session != null) RoomStatus(session)
            }
        },
        heightFraction = null,
        maxWidth = DIALOG_MAX_WIDTH,
    ) {
        when (val current = state) {
            is TogetherState.InRoom -> RoomContent(manager, current.session, scope)
            // 房主换了人, 正以新的身份回同一个房间 (要重新连代理, 慢的设备上几秒): 不显示成没在房间里
            is TogetherState.Joining if current.handoff -> HandoffContent()
            // 同一处调用: 空闲 → 正在加入时内容不重建, 输入的房间号、密码与焦点都留着
            else -> EntryContent(manager, scope, joiningNumber = (current as? TogetherState.Joining)?.number)
        }
    }
}

/** 标题右边的房间状态: 绿点 = 连着, 黄点 = 正在重新连接 */
@Composable
private fun RoomStatus(session: TogetherSession) {
    val connection by session.connection.collectAsState()
    val reconnecting = connection == TogetherConnectionState.RECONNECTING
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (reconnecting) STATUS_RECONNECTING else STATUS_CONNECTED))
        Text(
            stringResource(
                when {
                    reconnecting -> Lang.tv_together_reconnecting
                    session.isHost -> Lang.tv_together_status_open
                    else -> Lang.tv_together_status_joined
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 左右两栏, 中间一条竖线; 右栏定宽 */
@Composable
private fun TwoColumns(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), content = left)
        Box(
            Modifier.padding(horizontal = COLUMN_GAP).width(1.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        )
        Column(Modifier.width(RIGHT_COLUMN_WIDTH), verticalArrangement = Arrangement.spacedBy(10.dp), content = right)
    }
}

/** 换房主的那几秒: 正以新的身份回同一个房间, 回去了就换成房间里的样子 */
@Composable
private fun HandoffContent() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = HANDOFF_MIN_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(Lang.tv_together_switching_host), style = MaterialTheme.typography.titleMedium)
    }
}

/** 加入为什么没成 */
private enum class JoinFailure { UNREACHABLE, NO_SERVERS, EMPTY, WRONG_PASSWORD }

/** 开房间、加入失败的原因 */
private fun joinFailureOf(throwable: Throwable): JoinFailure = when (throwable) {
    is TogetherNoBrokersException -> JoinFailure.NO_SERVERS
    is TogetherRoomEmptyException -> JoinFailure.EMPTY
    is TogetherWrongPasswordException -> JoinFailure.WRONG_PASSWORD
    else -> JoinFailure.UNREACHABLE
}

/** 不在房间里时从哪一处进房间 (转圈画在它上面) */
private enum class EntryAction {
    /** 「开启房间」 */
    CREATE,

    /** 在「最近的房间」里选了一个 */
    RECENT,

    /** 输入框旁的「加入」 */
    JOIN,
}

/**
 * 不在房间里: 开自己的房间, 输入房间号与密码加入, 或在「最近的房间」(另一个弹窗) 里选一个再进。
 *
 * @param joiningNumber 正在进的房间号; 不在进房间时为 null
 */
@Composable
private fun EntryContent(manager: TogetherManager, scope: CoroutineScope, joiningNumber: String?) {
    val toaster = LocalToaster.current
    var number by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var failure by remember { mutableStateOf<JoinFailure?>(null) }
    val recentRooms by manager.recentRooms.collectAsState()
    var showRecent by remember { mutableStateOf(false) }
    val ownNumber by produceState<String?>(null) { value = manager.ownRoom().number }
    val joining = joiningNumber != null
    // 这里按下的那一处; 进完 (成没成) 清掉
    var pressed by remember { mutableStateOf<EntryAction?>(null) }
    // 转圈画在发起的那一处: 这里按的照按的算; 别处 (控制台、自动回房间) 发起的, 自己的房间号算「开启房间」, 别的算「加入」
    val busyOn: EntryAction? = when {
        joiningNumber == null -> null
        pressed != null -> pressed
        joiningNumber == ownNumber -> EntryAction.CREATE
        else -> EntryAction.JOIN
    }
    val createdText = stringResource(Lang.tv_together_toast_created)
    val joinedText = stringResource(Lang.tv_together_toast_joined)
    val room = TogetherRoomId.of(number, password)

    /** 加入 [target]。从最近的房间里选的, 房主换过密码时清空密码, 输入新密码再加入 (进成了, 记下的密码跟着换) */
    fun joinRoom(target: TogetherRoomId, from: EntryAction) {
        if (joining) return
        failure = null
        pressed = from
        scope.launch {
            manager.join(target)
                .onSuccess {
                    // 填的是自己的房间号与密码: 打开的是自己的房间, 自己是房主
                    val hosting = (manager.state.value as? TogetherState.InRoom)?.session?.isHost == true
                    toaster.toast(if (hosting) createdText.replace("%1\$s", formatTogetherRoomNumber(target.number)) else joinedText)
                }
                .onFailure {
                    failure = joinFailureOf(it)
                    if (from == EntryAction.RECENT && it is TogetherWrongPasswordException) password = ""
                }
            pressed = null
        }
    }

    // 粘贴进输入框的是朋友发来的整段邀请 (认得出房间号与密码): 两个框都填好直接加入, 与控制台的粘贴邀请一样
    fun acceptInvite(text: String): Boolean {
        val target = TogetherInvite.parse(text) ?: return false
        number = target.number
        password = target.password
        joinRoom(target, EntryAction.JOIN)
        return true
    }

    TwoColumns(
        left = {
            Text(
                stringResource(Lang.tv_together_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 「打开我的房间」排在输入框前面: 弹窗打开时系统先把焦点给第一个能聚焦的, 默认落点就是它, 不用再从输入框上挪过来。
            // 「最近的房间」进过别人的房间才有, 列表在另一个弹窗里: 放在这里的话这个弹窗要长高, 它已经快顶满屏
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AniFocusActionButton(
                    onClick = {
                        if (joining) return@AniFocusActionButton
                        failure = null
                        pressed = EntryAction.CREATE
                        scope.launch {
                            manager.createRoom()
                                .onSuccess { toaster.toast(createdText.replace("%1\$s", formatTogetherRoomNumber(it.number))) }
                                .onFailure { failure = joinFailureOf(it) }
                            pressed = null
                        }
                    },
                    modifier = Modifier.tvWindowInitialFocus(),
                    loading = busyOn == EntryAction.CREATE,
                    enabled = !joining || busyOn == EntryAction.CREATE,
                ) { Text(stringResource(Lang.tv_together_create)) }
                if (recentRooms.isNotEmpty()) {
                    AniFocusActionButton(
                        onClick = { if (!joining) showRecent = true },
                        loading = busyOn == EntryAction.RECENT,
                        enabled = !joining || busyOn == EntryAction.RECENT,
                    ) { Text(stringResource(Lang.tv_together_recent)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AniOutlinedTextField(
                    value = number,
                    onValueChange = {
                        if (!acceptInvite(it)) {
                            number = it
                            failure = null
                        }
                    },
                    modifier = Modifier.weight(1.2f),
                    enabled = !joining,
                    label = { Text(stringResource(Lang.tv_together_room_number)) },
                    placeholder = { Text(stringResource(Lang.tv_together_room_number_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                AniOutlinedTextField(
                    value = password,
                    onValueChange = {
                        if (!acceptInvite(it)) {
                            password = it
                            failure = null
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !joining,
                    label = { Text(stringResource(Lang.tv_together_password)) },
                    placeholder = { Text(stringResource(Lang.tv_together_password_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                AniFocusActionButton(
                    onClick = { room?.let { joinRoom(it, EntryAction.JOIN) } },
                    enabled = room != null && (!joining || busyOn == EntryAction.JOIN),
                    loading = busyOn == EntryAction.JOIN,
                ) { Text(stringResource(Lang.tv_together_join)) }
            }
            failure?.let {
                Text(
                    stringResource(
                        when (it) {
                            JoinFailure.UNREACHABLE -> Lang.tv_together_join_failed
                            JoinFailure.NO_SERVERS -> Lang.tv_together_join_no_servers
                            JoinFailure.EMPTY -> Lang.tv_together_join_empty
                            JoinFailure.WRONG_PASSWORD -> Lang.tv_together_join_wrong_password
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        right = {
            if (LocalTvTouchInputEnabled.current) {
                // 触屏 (平板): 邀请多半就在这台设备上, 粘贴进左边的输入框就加入; 在手机上的照样扫码去控制台粘贴
                ColumnHeading(stringResource(Lang.tv_together_join_by_invite))
                HintText(stringResource(Lang.tv_together_paste_invite_hint))
                ConsoleQrCode(stringResource(Lang.tv_together_qr_caption_join_touch))
            } else {
                // 朋友发来的邀请在手机上: 扫码打开自己电视的控制台, 粘贴进去就加入, 不用拿遥控器输 14 位数字
                ColumnHeading(stringResource(Lang.tv_together_join_by_phone))
                ConsoleQrCode(stringResource(Lang.tv_together_qr_caption_join))
            }
        },
    )

    if (showRecent && recentRooms.isNotEmpty()) {
        RecentRoomsDialog(recentRooms, onDismissRequest = { showRecent = false }) { recent ->
            showRecent = false
            TogetherRoomId.of(recent.number, recent.password)?.let { target ->
                // 选的房间填进输入框再进: 看得到进的是哪个房间, 密码不对时改了密码就能再试
                number = recent.number
                password = recent.password
                joinRoom(target, EntryAction.RECENT)
            }
        }
    }
}

/** 「最近的房间」: 一个房间一张卡 (房主的名字与房间号), 按确定选它, 按返回关。见 TogetherRecentRooms.kt */
@Composable
private fun RecentRoomsDialog(rooms: List<TogetherRecentRoom>, onDismissRequest: () -> Unit, onPick: (TogetherRecentRoom) -> Unit) {
    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Lang.tv_together_recent)) },
        heightFraction = null,
        maxWidth = RECENT_DIALOG_MAX_WIDTH,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(CARD_SPACING)) {
            rooms.forEachIndexed { index, room ->
                RecentRoomCard(room, modifier = if (index == 0) Modifier.tvWindowInitialFocus() else Modifier, onClick = { onPick(room) })
            }
        }
    }
}

/** 房间里有一处要先确认的操作 */
private sealed interface Confirming {
    data object Disband : Confirming
    data object ChangePassword : Confirming

    /** 把房主转让给 [member] (房主在成员卡片上按了确定) */
    data class Transfer(val member: TogetherMember) : Confirming
}

/** 在房间里: 左边此刻在做什么与成员, 右边邀请朋友 (二维码、房间号、密码), 底下跟随开关、更换密码、离开 / 解散 */
@Composable
private fun RoomContent(manager: TogetherManager, session: TogetherSession, scope: CoroutineScope) {
    val toaster = LocalToaster.current
    val snapshot by session.snapshot.collectAsState()
    val following by session.following.collectAsState()
    var confirming by remember { mutableStateOf<Confirming?>(null) }
    val leftText = stringResource(Lang.tv_together_toast_left)
    val disbandedText = stringResource(Lang.tv_together_toast_disbanded)
    val passwordChangedText = stringResource(Lang.tv_together_toast_password_changed)
    val joinFailedText = stringResource(Lang.tv_together_join_failed)
    val transferringText = stringResource(Lang.tv_together_toast_transferring)
    // 换密码要关掉房间再用新密码重开; 重开没成 (连不上服务) 时人已经不在房间里了, 说一声
    val changePassword: () -> Unit = {
        scope.launch {
            manager.changePassword()
                .onSuccess { toaster.toast(passwordChangedText) }
                .onFailure { toaster.toast(joinFailedText) }
        }
    }

    val playing = snapshot.playback?.info
    val watchingHeadline = playing?.let { stringResource(Lang.tv_together_headline_watching, it.subjectName, it.episodeSort) }.orEmpty()
    // 标题与说明都照这台设备的处境写 (先看模式, 再看房主在不在; 控制台卡片同一套)
    val (headline, detail) = when (togetherSituation(snapshot, session.isHost, following)) {
        TogetherSituation.HOST_IDLE -> stringResource(Lang.tv_together_headline_host_idle) to stringResource(Lang.tv_together_host_idle_hint)
        TogetherSituation.HOST_WATCHING -> watchingHeadline to stringResource(Lang.tv_together_host_hint)
        TogetherSituation.MEMBER_FREE -> stringResource(Lang.tv_together_state_free) to stringResource(Lang.tv_together_member_free)
        TogetherSituation.MEMBER_HOST_OFFLINE ->
            stringResource(Lang.tv_together_headline_host_offline) to stringResource(Lang.tv_together_host_offline)

        TogetherSituation.MEMBER_WAITING -> stringResource(Lang.tv_together_headline_waiting) to stringResource(Lang.tv_together_host_not_playing)
        TogetherSituation.MEMBER_FOLLOWING -> watchingHeadline to stringResource(Lang.tv_together_member_following)
    }

    TwoColumns(
        left = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            // 房主在别人的卡片上按确定可以转让房主 (掉线的不行); 说明放在标题同一行, 弹窗不长高
            fun transferable(member: TogetherMember) =
                session.isHost && member.memberId != session.selfId && member.state != TogetherMemberState.DISCONNECTED
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Lang.tv_together_members, snapshot.members.size),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshot.members.any { transferable(it) }) {
                    Text(
                        stringResource(Lang.tv_together_transfer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 人多时放不下: 列表定高可滚, 卡片吃焦点, 遥控器上下就能翻; 边上露出一截的淡出, 看得出还有
            val memberScroll = rememberScrollState()
            ProvideScrollFadeFocusMargin {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = MEMBER_LIST_MAX_HEIGHT)
                        .aniVerticalScrollFade(memberScroll)
                        .verticalScroll(memberScroll),
                    verticalArrangement = Arrangement.spacedBy(CARD_SPACING),
                ) {
                    for (member in snapshot.members) {
                        MemberCard(
                            member,
                            isSelf = member.memberId == session.selfId,
                            onClick = if (transferable(member)) ({ confirming = Confirming.Transfer(member) }) else null,
                        )
                    }
                }
            }
        },
        right = {
            // 扫码在手机上打开控制台的这张卡片, 复制邀请发给朋友; 触屏 (平板) 上还能长按房间号与密码那一块直接复制邀请
            val touch = LocalTvTouchInputEnabled.current
            ColumnHeading(stringResource(Lang.tv_together_invite_heading))
            ConsoleQrCode(stringResource(Lang.tv_together_qr_caption))
            Column(
                Modifier.padding(top = 4.dp).then(if (touch) Modifier.longPressCopyInvite(session.roomId) else Modifier),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LabeledValue(stringResource(Lang.tv_together_room_number), formatTogetherRoomNumber(session.roomId.number))
                LabeledValue(stringResource(Lang.tv_together_password), formatTogetherRoomPassword(session.roomId.password))
            }
            HintText(
                if (touch) {
                    stringResource(Lang.tv_together_copy_invite_hint)
                } else {
                    stringResource(Lang.tv_together_share_hint, stringResource(Lang.watch_together_title))
                },
            )
        },
    )

    Spacer(Modifier.height(20.dp))
    val pending = confirming
    if (pending != null) {
        val transferTo = (pending as? Confirming.Transfer)?.member?.let { memberName(it) }
        // 那句说明与两颗按钮同一行: 另起一行的话弹窗会长高, 它本来就快顶满屏, 居中着往上下撑, 标题会被顶出去
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AniFocusActionButton(onClick = { confirming = null }, modifier = Modifier.tvWindowInitialFocus()) {
                Text(stringResource(Lang.tv_together_cancel))
            }
            AniFocusActionButton(
                onClick = {
                    confirming = null
                    when (pending) {
                        Confirming.Disband -> scope.launch {
                            manager.leave()
                            toaster.toast(disbandedText)
                        }

                        Confirming.ChangePassword -> changePassword()
                        is Confirming.Transfer -> scope.launch {
                            toaster.toast(transferringText.replace("%1\$s", transferTo.orEmpty()))
                            manager.transferHost(pending.member.memberId)
                        }
                    }
                },
                // 转让不丢东西, 不用警示色
                accentColor = if (pending is Confirming.Transfer) Color.Unspecified else MaterialTheme.colorScheme.error,
            ) {
                Text(
                    stringResource(
                        when (pending) {
                            Confirming.Disband -> Lang.tv_together_disband_confirm_action
                            Confirming.ChangePassword -> Lang.tv_together_change_password_action
                            is Confirming.Transfer -> Lang.tv_together_transfer_action
                        },
                    ),
                )
            }
            Text(
                when (pending) {
                    Confirming.Disband -> stringResource(Lang.tv_together_disband_confirm)
                    Confirming.ChangePassword -> stringResource(Lang.tv_together_change_password_confirm)
                    is Confirming.Transfer -> stringResource(Lang.tv_together_transfer_confirm, transferTo.orEmpty())
                },
                Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        // 没有「关闭」: 按返回关。默认焦点落在第一颗 (房主: 更换密码, 房间里有别人时先确认; 成员: 跟随开关, 再按一次就切回来)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (session.isHost) {
                // 被请来临时当房主的看不到「更换密码」: 房间是别人的, 密码只有房间的主人能换
                if (session.ownsRoom) {
                    AniFocusActionButton(
                        onClick = {
                            // 房间里有别人时先确认: 换了密码他们要重新加入
                            val othersOnline = snapshot.members.any { it.memberId != session.selfId && it.state != TogetherMemberState.DISCONNECTED }
                            if (othersOnline) confirming = Confirming.ChangePassword else changePassword()
                        },
                        modifier = Modifier.tvWindowInitialFocus(),
                    ) { Text(stringResource(Lang.tv_together_change_password)) }
                }
                AniFocusActionButton(
                    onClick = { confirming = Confirming.Disband },
                    modifier = if (session.ownsRoom) Modifier else Modifier.tvWindowInitialFocus(),
                    accentColor = MaterialTheme.colorScheme.error,
                ) {
                    Text(stringResource(Lang.tv_together_disband))
                }
            } else {
                AniFocusChip(
                    text = stringResource(Lang.tv_together_follow_host),
                    selected = following,
                    onClick = { scope.launch { manager.setFollowing(!following) } },
                    modifier = Modifier.tvWindowInitialFocus(),
                )
                AniFocusActionButton(
                    onClick = {
                        scope.launch {
                            manager.leave()
                            toaster.toast(leftText)
                        }
                    },
                    accentColor = MaterialTheme.colorScheme.error,
                ) { Text(stringResource(Lang.tv_together_leave)) }
            }
        }
    }
}

@Composable
private fun ColumnHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 长按把 [room] 的邀请 (房间号与密码都在里面的一段话) 放进剪贴板, 震一下并提示已复制; 朋友粘贴到「一起看」的输入框就能加入 */
@Composable
private fun Modifier.longPressCopyInvite(room: TogetherRoomId): Modifier {
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val invite = stringResource(Lang.tv_together_invite_text, formatTogetherRoomNumber(room.number), formatTogetherRoomPassword(room.password))
    val copied = stringResource(Lang.tv_together_invite_copied)
    return pointerInput(invite) {
        detectTapGestures(
            onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    clipboard.setClipEntryText(invite)
                    toaster.toast(copied)
                }
            },
        )
    }
}

/**
 * 控制台「一起看」卡片的二维码 ([CONSOLE_TOGETHER_ANCHOR]): 手机扫了直接打开那张卡片。
 * 邀请在手机上复制、粘贴, 码里放的是控制台地址, 不是邀请本身。
 */
@Composable
private fun ConsoleQrCode(caption: String) {
    if (!tvWebConsoleAvailable) return // 没有 Web 控制台的端 (iPad): 邀请只在设备上复制、粘贴
    val url by TvRemoteControl.url.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RemoteQrCode(url?.let { it + CONSOLE_TOGETHER_ANCHOR }, size = QR_SIZE, quietZone = QR_QUIET_ZONE)
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.width(LABEL_WIDTH),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 成员的名字; 没有名字的 (没登录 Bangumi) 是「观众 + 编号头几位」 */
@Composable
private fun memberName(member: TogetherMember): String =
    member.nickname.ifBlank { stringResource(Lang.tv_together_viewer) + " " + member.memberId.take(VIEWER_ID_CHARS) }

/**
 * 一个人一张卡: 头像、名字与身份 (房主 / 成员, 是不是自己)、此刻在看什么。
 *
 * @param onClick 按确定时 (房主在别人的卡片上: 转让房主); 为 null 时卡片只为能用遥控器翻列表才吃焦点
 */
@Composable
private fun MemberCard(member: TogetherMember, isSelf: Boolean, onClick: (() -> Unit)? = null) {
    CardRow(onClick) {
        PersonAvatar()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(memberName(member), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val role = stringResource(if (member.isHost) Lang.tv_together_role_host else Lang.tv_together_role_member)
            Text(
                if (isSelf) role + " · " + stringResource(Lang.tv_together_you) else role,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        val episode = member.watching?.episodeSort.orEmpty()
        Text(
            // 有播放会话不等于在看: 照实际在做什么写 (见 TogetherMember.activity)
            when (member.activity()) {
                TogetherMemberActivity.OFFLINE -> stringResource(Lang.tv_together_state_offline)
                TogetherMemberActivity.FREE -> stringResource(Lang.tv_together_state_free)
                TogetherMemberActivity.IDLE -> stringResource(Lang.tv_together_state_idle)
                TogetherMemberActivity.PREPARING -> stringResource(Lang.tv_together_state_preparing, episode)
                TogetherMemberActivity.BUFFERING -> stringResource(Lang.tv_together_state_buffering, episode)
                TogetherMemberActivity.PAUSED -> stringResource(Lang.tv_together_state_paused, episode)
                TogetherMemberActivity.PLAYING -> stringResource(Lang.tv_together_state_playing, episode)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 最近的房间里的一张卡: 房主的名字 (没看到过房主时是房间号) 与房间号 */
@Composable
private fun RecentRoomCard(room: TogetherRecentRoom, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val number = formatTogetherRoomNumber(room.number)
    val name = room.hostName.ifBlank {
        if (room.hostMemberId.isEmpty()) "" else stringResource(Lang.tv_together_viewer) + " " + room.hostMemberId.take(VIEWER_ID_CHARS)
    }
    CardRow(onClick, modifier) {
        PersonAvatar()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name.ifEmpty { number }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (name.isNotEmpty()) {
                Text(
                    stringResource(Lang.tv_together_room_number) + " " + number,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 成员、最近的房间共用的卡片底: 聚焦时只画框, 不叠焦点态层。
 *
 * @param onClick 按确定时; 为 null 时卡片只为能用遥控器翻列表才吃焦点
 */
@Composable
private fun CardRow(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (onClick != null) Modifier.aniClickable(indication = null, onClick = onClick) else Modifier.focusable())
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, CARD_SHAPE)
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PersonAvatar() {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Person, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

private val DIALOG_MAX_WIDTH = 800.dp
private val HANDOFF_MIN_HEIGHT = 160.dp
private val RIGHT_COLUMN_WIDTH = 280.dp
private val COLUMN_GAP = 28.dp

/** 房间号、密码的标签列: 放得下英文的「Room number」, 两行的号码左边对齐 */
private val LABEL_WIDTH = 104.dp
private val QR_SIZE = 128.dp
private val QR_QUIET_ZONE = 10.dp

/** 成员卡片列表最高三张; 再多就在里面上下翻 */
private val MEMBER_LIST_MAX_HEIGHT = 196.dp

private val RECENT_DIALOG_MAX_WIDTH = 560.dp
private val CARD_SPACING = 8.dp
private val CARD_SHAPE = RoundedCornerShape(14.dp)
private val STATUS_CONNECTED = Color(0xFF34C759)
private val STATUS_RECONNECTING = Color(0xFFFFB020)

/** 控制台页面认的锚点: 打开播放器标签、展开「一起看」卡片 (见 RemoteTogetherPage) */
internal const val CONSOLE_TOGETHER_ANCHOR = "#player/together"

/** 没有名字的成员显示成「观众 xxxx」, 取编号的头几位好分清谁是谁 */
internal const val VIEWER_ID_CHARS = 4
