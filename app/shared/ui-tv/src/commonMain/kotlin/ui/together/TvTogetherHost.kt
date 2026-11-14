/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.together

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.media.player.PlaybackActivity
import me.him188.ani.app.domain.together.TogetherEffect
import me.him188.ani.app.domain.together.TogetherManager
import me.him188.ani.app.domain.together.TogetherMember
import me.him188.ani.app.domain.together.TogetherMemberChange
import me.him188.ani.app.domain.together.formatTogetherRoomNumber
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.EpisodeNavigationGuardRegistry
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.navigation.findLast
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionEntry
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.Toaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_together_toast_became_host
import me.him188.ani.app.ui.lang.tv_together_toast_created
import me.him188.ani.app.ui.lang.tv_together_toast_falling_behind
import me.him188.ani.app.ui.lang.tv_together_toast_following
import me.him188.ani.app.ui.lang.tv_together_toast_host_changed
import me.him188.ani.app.ui.lang.tv_together_toast_member_dropped
import me.him188.ani.app.ui.lang.tv_together_toast_member_joined
import me.him188.ani.app.ui.lang.tv_together_toast_member_left
import me.him188.ani.app.ui.lang.tv_together_toast_member_returned
import me.him188.ani.app.ui.lang.tv_together_toast_navigation_blocked
import me.him188.ani.app.ui.lang.tv_together_toast_preparing
import me.him188.ani.app.ui.lang.tv_together_toast_rejoined
import me.him188.ani.app.ui.lang.tv_together_toast_resynced_backward
import me.him188.ani.app.ui.lang.tv_together_toast_resynced_forward
import me.him188.ani.app.ui.lang.tv_together_toast_room_closed
import me.him188.ani.app.ui.lang.tv_together_toast_room_rekeyed
import me.him188.ani.app.ui.lang.tv_together_toast_transfer_failed
import me.him188.ani.app.ui.lang.tv_together_toast_waiting_host
import me.him188.ani.app.ui.lang.tv_together_toast_waiting_members
import me.him188.ani.app.ui.lang.tv_together_toast_waiting_members_seek
import me.him188.ani.app.ui.lang.tv_together_viewer
import me.him188.ani.app.ui.lang.watch_together_title
import org.koin.mp.KoinPlatform
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「一起看」弹窗的开关。弹窗本体画在应用根部 ([TvTogetherHost]), 各入口 (长按返回的动作面板、播放器按钮行) 只调 [open]。
 */
object TvTogetherEntry {
    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    fun open() {
        _dialogVisible.value = true
    }

    fun dismiss() {
        _dialogVisible.value = false
    }
}

/**
 * 一起看在应用根部的那一份 (docs/contributing/code/together.md): 画弹窗, 并执行 [TogetherManager] 要界面做的事。
 *
 * - 跟随房主时被带到房主那一集: 栈里已经有播放页 (别的番的, 或人已经离开、会话留在后台的) 就先退掉它再打开, 不往上再叠一层。
 *   房主只在后台准备着那一集时, 这边也只用 [playback] 在后台准备 (不进播放页), 房主进了播放页再带进去。
 *   导航带 `force`, 因为跟随中导航守卫只放行房主那一集, 而这里去的正是那一集 (房主刚换集时守卫手上可能还是上一集)。
 *   攒了一阵才取到的指示先问总管还作不作数 (房主可能早就换了集); 返回栈就位之后才开始取, 这之前指示留在总管那里。
 * - 各种提示: 被带去哪一集、房间解散、回到上次的房间、被拉回房主的位置、停下来等房主、有人进出、跟随中想自己进播放页被拦下、
 *   房主换了人 / 自己成了房主 / 转让房主没成。
 *   应用在后台时不弹 (安卓上这些提示是系统 Toast, 会盖在别的应用上), 留下最后一条, 回到前台补上。
 *   文案用 [getStringOffMain] 读: 这里的协程跑在主线程上, 直接读会跟界面同步读文案抢同一把锁, 把界面卡死。
 */
@Composable
fun TvTogetherHost(navigator: AniNavigator, playback: PlaybackSessionEntry = PlaybackSessionEntry.None) {
    val manager = remember { KoinPlatform.getKoin().getOrNull<TogetherManager>() } ?: return
    val playbackActivity = remember { KoinPlatform.getKoin().getOrNull<PlaybackActivity>() }
    val appToaster = LocalToaster.current
    val toaster = remember(appToaster, playbackActivity) {
        ForegroundToaster(appToaster) { playbackActivity?.appForeground?.value != false }
    }

    val context = LocalContext.current
    val currentPlayback by rememberUpdatedState(playback)

    LaunchedEffect(manager, navigator) {
        launch {
            EpisodeNavigationGuardRegistry.denialEvents.collect { reason ->
                if (reason == TogetherManager.NAVIGATION_DENIED_MESSAGE) {
                    toaster.toast(getStringOffMain(Lang.tv_together_toast_navigation_blocked, getStringOffMain(Lang.watch_together_title)))
                }
            }
        }
        if (playbackActivity != null) launch { playbackActivity.appForeground.collect { foreground -> if (foreground) toaster.flush() } }
        launch {
            navigator.awaitBackStack()
            manager.navigations.collect { target ->
                if (!manager.shouldStillNavigate(target.subjectId, target.episodeId)) return@collect
                if (target.inBackground) {
                    // 不支持后台会话的形态什么都不做, 等房主进了播放页再带进去
                    val started = currentPlayback.startInBackground(target.subjectId, target.episodeId, context)
                    val subjectName = target.subjectName
                    val episodeSort = target.episodeSort
                    if (started && subjectName != null && episodeSort != null) {
                        toaster.toast(getStringOffMain(Lang.tv_together_toast_preparing, subjectName, episodeSort))
                    }
                    return@collect
                }
                navigator.findLast<NavRoutes.EpisodeDetail>()?.let { navigator.popBackStack(it, inclusive = true) }
                navigator.navigateEpisodeDetails(target.subjectId, target.episodeId, force = true)
                val subjectName = target.subjectName
                val episodeSort = target.episodeSort
                if (subjectName != null && episodeSort != null) {
                    toaster.toast(getStringOffMain(Lang.tv_together_toast_following, subjectName, episodeSort))
                }
            }
        }
        manager.effects.collect { effect ->
            when (effect) {
                // 导航走 manager.navigations
                is TogetherEffect.Navigate -> Unit
                is TogetherEffect.RoomClosed -> toaster.toast(
                    getStringOffMain(if (effect.passwordChanged) Lang.tv_together_toast_room_rekeyed else Lang.tv_together_toast_room_closed),
                )

                // 回到自己的房间是重新开启, 回到别人的房间是重新加入
                is TogetherEffect.Rejoined -> toaster.toast(
                    getStringOffMain(
                        if (effect.own) Lang.tv_together_toast_created else Lang.tv_together_toast_rejoined,
                        formatTogetherRoomNumber(effect.room.number),
                    ),
                )

                is TogetherEffect.ResyncedWithHost -> {
                    val seconds = (abs(effect.deltaMillis) / 1000.0).roundToInt().coerceAtLeast(1)
                    toaster.toast(
                        getStringOffMain(
                            if (effect.deltaMillis >= 0) Lang.tv_together_toast_resynced_forward else Lang.tv_together_toast_resynced_backward,
                            seconds,
                        ),
                    )
                }

                TogetherEffect.WaitingForHost -> toaster.toast(getStringOffMain(Lang.tv_together_toast_waiting_host))
                is TogetherEffect.WaitingForMembers -> toaster.toast(
                    getStringOffMain(if (effect.afterSeek) Lang.tv_together_toast_waiting_members_seek else Lang.tv_together_toast_waiting_members),
                )
                TogetherEffect.FallingBehind -> toaster.toast(getStringOffMain(Lang.tv_together_toast_falling_behind))
                is TogetherEffect.HostChanged -> toaster.toast(getStringOffMain(Lang.tv_together_toast_host_changed, memberNameOffMain(effect.host)))
                TogetherEffect.BecameHost -> toaster.toast(getStringOffMain(Lang.tv_together_toast_became_host))
                is TogetherEffect.TransferFailed -> toaster.toast(
                    getStringOffMain(Lang.tv_together_toast_transfer_failed, effect.member?.let { memberNameOffMain(it) } ?: viewerNameOffMain(effect.memberId)),
                )

                is TogetherEffect.MembersChanged -> for (change in effect.changes) {
                    val name = memberNameOffMain(change.member)
                    toaster.toast(
                        getStringOffMain(
                            when (change.kind) {
                                TogetherMemberChange.Kind.JOINED -> Lang.tv_together_toast_member_joined
                                TogetherMemberChange.Kind.LEFT -> Lang.tv_together_toast_member_left
                                TogetherMemberChange.Kind.DROPPED -> Lang.tv_together_toast_member_dropped
                                TogetherMemberChange.Kind.RETURNED -> Lang.tv_together_toast_member_returned
                            },
                            name,
                        ),
                    )
                }
            }
        }
    }

    val dialogVisible by TvTogetherEntry.dialogVisible.collectAsState()
    if (dialogVisible) TvTogetherDialog(manager, onDismissRequest = TvTogetherEntry::dismiss)
}

/** 提示里成员的名字: 没有名字的 (没登录 Bangumi) 是「观众 + 编号头几位」, 同弹窗里的卡片 */
private suspend fun memberNameOffMain(member: TogetherMember): String = member.nickname.ifBlank { viewerNameOffMain(member.memberId) }

private suspend fun viewerNameOffMain(memberId: String): String = getStringOffMain(Lang.tv_together_viewer) + " " + memberId.take(VIEWER_ID_CHARS)

/** 应用在前台才弹的提示; 在后台时留下最后一条, [flush] 时补上 */
private class ForegroundToaster(private val toaster: Toaster, private val foreground: () -> Boolean) {
    private var pending: String? = null

    fun toast(text: String) {
        if (foreground()) toaster.toast(text) else pending = text
    }

    fun flush() {
        pending?.let { toaster.toast(it) }
        pending = null
    }
}
