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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_action_open
import me.him188.ani.app.ui.lang.tv_notice_bubble_more
import me.him188.ani.app.ui.lang.tv_notice_bubble_title
import me.him188.ani.app.ui.onboarding.TvOnboardingLogin
import me.him188.ani.app.ui.profile.TvUserProfilePicker
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.update.CornerBubble
import me.him188.ani.app.ui.update.UpdatePromptState
import me.him188.ani.app.ui.whatsnew.TvWhatsNew
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 通知弹窗与通知气泡的宿主 (见 docs/contributing/code/notices.md), 挂在 TV 根组合上:
 * - 通知气泡: 进应用的弹窗走完后 ([TvNotices.startBubbles]) 有这一批还没弹过的没看过的通知 ([tvNoticesToBubble]), 等用户在首页 ([onMainPage])、
 *   上面没有别的层 (引导、选人、本版新功能、控制台二维码、通知弹窗、下载新版本的卡) 时在右下角弹 ([UpdatePromptState.showBubble]);
 *   按「查看」打开通知弹窗、气泡不关 (关掉通知弹窗回到气泡); 按关闭、返回、20 秒没动作关掉, 记下这一批弹过了;
 * - 一条的操作 (只有一个时在列表里按确定键, 几个时在右栏挑): 弹窗的叠在列表上 (列表先不画, 关掉回到列表、焦点落回那条, 在右栏挑的落回那颗按钮); 页面的跳过去,
 *   回到打开通知时的那一页 (首页, 或设置的账号页) 时还有通知就重新打开列表; 当场做完的在这里跑, 留在列表;
 * - 从别处处理一条 ([TvNotices.handle]): 只有一个操作的直接做 (弹窗直接叠在当时的页面上), 几个的打开列表、焦点在它的第一个操作上.
 */
@Composable
fun TvNoticeHost(navigator: AniNavigator, onMainPage: () -> Boolean) {
    LaunchedEffect(Unit) { TvNotices.install() }
    val currentOnMainPage by rememberUpdatedState(onMainPage)

    // 从后台回到前台 (第一次 ON_START 是启动, 进应用的弹窗走完后由 TvNotices.startBubbles 管)
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

    // 通知气泡: 这一批还没弹过的没看过的通知 (在手机上看过了的不再算)
    val bubbleSource by TvNotices.shown.collectAsState()
    val bubbleArmed by TvNotices.bubbleArmed.collectAsState()
    val bubbled by TvNotices.bubbled.collectAsState()
    val bubbleEnabled by TvNotices.bubbleEnabled.collectAsState(initial = false)
    val dialogOpen = TvNotices.dialog.collectAsState().value != null
    val toBubble = if (bubbleArmed && bubbleEnabled) tvNoticesToBubble(bubbleSource.orEmpty(), bubbled) else emptyList()
    // 气泡上写的那几条: 弹出来以后一直在, 直到按关闭、返回或 20 秒没动作. 按「查看」打开通知弹窗时不关, 只是弹窗开着的时候不画,
    // 关掉回到气泡; 在弹窗里看过了也照旧留着, 用着时又来了新的才换成新的这一批
    var bubbleItems by remember { mutableStateOf<List<TvShownNotice>>(emptyList()) }
    val currentToBubble by rememberUpdatedState(toBubble)
    LaunchedEffect(toBubble.isNotEmpty() && bubbleItems.isEmpty()) {
        if (toBubble.isEmpty() || bubbleItems.isNotEmpty()) return@LaunchedEffect
        // 等到首页、上面没有别的层 (通知弹窗也不开着) 时弹
        while (!(currentOnMainPage() && nothingCovering() && TvNotices.dialog.value == null)) delay(POLL_INTERVAL)
        bubbleItems = currentToBubble
    }
    LaunchedEffect(toBubble) {
        if (bubbleItems.isNotEmpty() && toBubble.isNotEmpty()) bubbleItems = toBubble
    }
    // 关掉通知弹窗回到气泡: 换一个新的气泡, 焦点重新落在「查看」上、20 秒重新计
    var bubbleRound by remember { mutableIntStateOf(0) }
    var dialogWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) {
        if (dialogWasOpen && !dialogOpen) bubbleRound++
        dialogWasOpen = dialogOpen
    }
    if (bubbleItems.isNotEmpty()) {
        val items = bubbleItems
        val title = stringResource(Lang.tv_notice_bubble_title, items.size)
        val actionLabel = stringResource(Lang.tv_notice_action_open)
        // 没看过的那几条的那句话 (列出来的已经排好先后), 至多 TV_NOTICE_BUBBLE_LINES 条, 再多写还有几条
        val more = items.size - TV_NOTICE_BUBBLE_LINES
        val moreLine = if (more > 0) stringResource(Lang.tv_notice_bubble_more, more) else null
        val lines = items.take(TV_NOTICE_BUBBLE_LINES).map { it.title } + listOfNotNull(moreLine)
        val keys = items.map(::tvNoticeBubbleKey).toSet()
        val dismiss = {
            TvNotices.bubbled.value += keys
            bubbleItems = emptyList()
        }
        // 从通知里跳去了别的页面: 气泡收起 (算处理过), 不在那一页上抢焦点. 通知弹窗开着 (叠在页面上) 时不看, 关掉后再看
        LaunchedEffect(keys, dialogOpen) {
            if (dialogOpen) return@LaunchedEffect
            while (currentOnMainPage()) delay(POLL_INTERVAL)
            dismiss()
        }
        // 通知弹窗开着时不画 (面板半透明, 气泡的字会透在后面): 气泡还在 (bubbleItems 不变), 关掉弹窗照旧出来
        if (!dialogOpen) DisposableEffect(title, lines, keys, bubbleRound) {
            val bubble = CornerBubble(
                title = title,
                lines = lines,
                actionLabel = actionLabel,
                onAction = { TvNotices.open() },
                onDismiss = dismiss,
            )
            UpdatePromptState.showBubble(bubble)
            onDispose { UpdatePromptState.hideBubble(bubble) }
        }
    }

    // 跳去处页面时通知弹窗开在哪一页上: 首页, 或设置的账号页 (从「通知」那一行打开的)
    var returnOrigin by remember { mutableStateOf<NavRoutes?>(null) }
    val returning by TvNotices.returnTo.collectAsState()
    LaunchedEffect(returning) {
        val target = returning ?: return@LaunchedEffect
        val origin = returnOrigin?.takeUnless { it is NavRoutes.Main }
        fun stack() = runCatching { navigator.backStack }.getOrDefault(emptyList())
        fun atOrigin() = if (origin == null) currentOnMainPage() else stack().lastOrNull() === origin
        // 先等离开那一页 (跳过去了), 再等回到它; 那一页已经不在返回栈里了 (从别处离开了) 就不再打开
        withTimeoutOrNull(LEAVE_WAIT) { while (atOrigin()) delay(POLL_INTERVAL) }
        while (!(atOrigin() && nothingCovering())) {
            if (origin != null && stack().none { it === origin }) {
                TvNotices.returnTo.value = null
                return@LaunchedEffect
            }
            delay(POLL_INTERVAL)
        }
        TvNotices.returnTo.value = null
        // 刚处理掉的是最后一条: 不再打开一个空列表
        if (TvNotices.shown.value.orEmpty().isNotEmpty()) TvNotices.open(target.focusKey, target.focusIndex, fresh = target.fresh)
    }

    val state by TvNotices.dialog.collectAsState()
    val items by TvNotices.shown.collectAsState()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    // 叠在列表上的弹窗: 哪一条的哪个操作 (位置、键与右栏第几颗, 关掉后焦点落回它) 与开的哪个
    var overlay by remember { mutableStateOf<TvNoticeOverlay?>(null) }
    // 从别处处理一条时直接开的弹窗 (不开列表, 关掉回到原处)
    var direct by remember { mutableStateOf<TvNoticeDestination.Dialog?>(null) }

    // 从别处处理一条 (TvNotices.handle): 只有一个操作的直接做, 几个的打开列表、焦点在它的第一个操作上
    val handleRequest by TvNotices.handleRequest.collectAsState()
    LaunchedEffect(handleRequest, items) {
        val key = handleRequest ?: return@LaunchedEffect
        val list = items ?: return@LaunchedEffect
        TvNotices.handleRequest.value = null
        val index = list.indexOfFirst { it.key == key }
        val item = list.getOrNull(index)
        val single = item?.actions?.singleOrNull()
        if (single == null) {
            // 几个操作的进右栏第一颗; 这一条已经不在了 (刚处理掉) 打开列表, 焦点照常
            TvNotices.open(focusKey = key, focusIndex = index.coerceAtLeast(0), focusAction = if (item != null && item.actions.size > 1) 0 else null)
            return@LaunchedEffect
        }
        TvNotices.close()
        when (val destination = single.destination) {
            is TvNoticeDestination.Dialog -> direct = destination
            is TvNoticeDestination.Page -> destination.navigate(navigator)
            is TvNoticeDestination.Run -> scope.launch { destination.run(toaster) }
        }
    }
    val shownDirect = direct
    if (shownDirect != null) {
        shownDirect.content { direct = null }
        return
    }

    val current = state
    if (current == null) {
        if (overlay != null) LaunchedEffect(Unit) { overlay = null }
        return
    }
    val shownOverlay = overlay
    if (shownOverlay != null) {
        shownOverlay.dialog.content {
            overlay = null
            TvNotices.refocus(shownOverlay.key, shownOverlay.index, shownOverlay.action)
        }
        return
    }
    TvNoticeDialog(
        items.orEmpty(),
        current,
        onAction = { index, item, action ->
            when (val destination = action.destination) {
                is TvNoticeDestination.Dialog ->
                    overlay = TvNoticeOverlay(index, item.key, item.actions.indexOf(action).takeIf { item.actions.size > 1 && it >= 0 }, destination)
                is TvNoticeDestination.Page -> {
                    TvNotices.close()
                    returnOrigin = runCatching { navigator.backStack.lastOrNull() }.getOrNull()
                    TvNotices.returnTo.value = TvNoticeDialogState(current.fresh, item.key, index)
                    destination.navigate(navigator)
                }

                // 当场做完, 留在列表 (做完弹的提示由它自己给)
                is TvNoticeDestination.Run -> scope.launch { destination.run(toaster) }
            }
        },
        onSeen = { key -> TvNotices.markSeenKeys(listOf(key)) },
        onDismiss = { TvNotices.close() },
    )
}

/** 叠在通知列表上的弹窗: 列表里第 [index] 条 (键 [key]) 的一个操作开的; 那一条有几个操作时 [action] 是右栏里第几颗 (关掉后焦点落回它) */
private class TvNoticeOverlay(val index: Int, val key: String, val action: Int?, val dialog: TvNoticeDestination.Dialog)

private fun nothingCovering(): Boolean =
    TvOnboardingLogin.request.value == null && !TvUserProfilePicker.visible.value &&
            TvWhatsNew.launchContent.value == null && !TvRemoteControl.dialogVisible.value && !UpdatePromptState.cardShown.value

private val POLL_INTERVAL = 300.milliseconds

/** 通知气泡上至多列几条标题 */
private const val TV_NOTICE_BUBBLE_LINES = 4

/** 跳去处页面后最多等这么久离开首页; 跳不过去 (导航没生效) 就当已经回来了. */
private val LEAVE_WAIT = 3.seconds
