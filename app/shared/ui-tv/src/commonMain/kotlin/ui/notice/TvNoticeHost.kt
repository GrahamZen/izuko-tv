/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.ui.onboarding.TvOnboardingLogin
import me.him188.ani.app.ui.profile.TvUserProfilePicker
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.update.UpdatePromptState
import me.him188.ani.app.ui.whatsnew.TvWhatsNew
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 通知弹窗的宿主 (见 docs/contributing/code/notices.md), 挂在 TV 根组合上:
 * - 进应用时有新的通知 ([TvNotices.claimLaunch])、从后台回到前台时有新的: 等用户在首页 ([onMainPage])、上面没有别的层 (引导、选人、新版本提示卡、
 *   本版新功能、控制台二维码) 时打开;
 * - 按确定一条: 去处是弹窗的叠在列表上 (列表先不画, 关掉回到列表、焦点落回那条); 去处是页面的跳过去, 回到首页时还有通知就重新打开列表.
 */
@Composable
fun TvNoticeHost(navigator: AniNavigator, onMainPage: () -> Boolean) {
    LaunchedEffect(Unit) { TvNotices.install() }
    val currentOnMainPage by rememberUpdatedState(onMainPage)

    // 从后台回到前台 (第一次 ON_START 是启动, 由 claimLaunch 管)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var started = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (started) TvNotices.onReturnToForeground()
                started = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val autoOpen by TvNotices.autoOpenPending.collectAsState()
    LaunchedEffect(autoOpen) {
        if (!autoOpen) return@LaunchedEffect
        while (!(currentOnMainPage() && nothingCovering())) delay(POLL_INTERVAL)
        TvNotices.autoOpenPending.value = false
        // 等的时候在别处 (手机控制台) 看过了: 不弹
        if (TvNotices.dialog.value == null && TvNotices.shown.value.orEmpty().any { !it.entry.seen }) TvNotices.open()
    }

    val returning by TvNotices.returnTo.collectAsState()
    LaunchedEffect(returning) {
        val target = returning ?: return@LaunchedEffect
        // 先等离开首页 (跳过去了), 再等回到首页
        withTimeoutOrNull(LEAVE_WAIT) { while (currentOnMainPage()) delay(POLL_INTERVAL) }
        while (!(currentOnMainPage() && nothingCovering())) delay(POLL_INTERVAL)
        TvNotices.returnTo.value = null
        // 刚处理掉的是最后一条: 不再打开一个空列表
        if (TvNotices.shown.value.orEmpty().isNotEmpty()) TvNotices.open(target.focusKey, target.focusIndex, fresh = target.fresh)
    }

    val state by TvNotices.dialog.collectAsState()
    val items by TvNotices.shown.collectAsState()
    var overlay by remember { mutableStateOf<Pair<Int, TvShownNotice>?>(null) }
    val current = state
    if (current == null) {
        if (overlay != null) LaunchedEffect(Unit) { overlay = null }
        return
    }
    // 手机控制台上点了「在电视上打开」: 那一条的弹窗叠上去
    val overlayRequest by TvNotices.overlayRequest.collectAsState()
    LaunchedEffect(overlayRequest, items) {
        val key = overlayRequest ?: return@LaunchedEffect
        val list = items ?: return@LaunchedEffect
        TvNotices.overlayRequest.value = null
        val index = list.indexOfFirst { it.key == key }
        if (index >= 0 && list[index].destination is TvNoticeDestination.Dialog) overlay = index to list[index]
    }
    val shownOverlay = overlay
    val overlayDestination = shownOverlay?.second?.destination as? TvNoticeDestination.Dialog
    if (shownOverlay != null && overlayDestination != null) {
        overlayDestination.content {
            overlay = null
            TvNotices.refocus(shownOverlay.second.key, shownOverlay.first)
        }
        return
    }
    TvNoticeDialog(
        items.orEmpty(),
        current,
        onPick = { index, item ->
            when (val destination = item.destination) {
                null -> Unit
                is TvNoticeDestination.Dialog -> overlay = index to item
                is TvNoticeDestination.Page -> {
                    TvNotices.close()
                    TvNotices.returnTo.value = TvNoticeDialogState(current.fresh, item.key, index)
                    destination.navigate(navigator)
                }
            }
        },
        onSeen = { key -> TvNotices.markSeenKeys(listOf(key)) },
        onDismiss = { TvNotices.close() },
    )
}

private fun nothingCovering(): Boolean =
    TvOnboardingLogin.request.value == null && !TvUserProfilePicker.visible.value &&
            TvWhatsNew.launchContent.value == null && !TvRemoteControl.dialogVisible.value && !UpdatePromptState.cardShown.value

private val POLL_INTERVAL = 300.milliseconds

/** 跳去处页面后最多等这么久离开首页; 跳不过去 (导航没生效) 就当已经回来了. */
private val LEAVE_WAIT = 3.seconds
