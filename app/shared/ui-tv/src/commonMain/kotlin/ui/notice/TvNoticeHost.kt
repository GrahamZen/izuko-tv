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
import androidx.compose.runtime.CompositionLocalProvider
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
import me.him188.ani.app.ui.foundation.dialogs.LocalStackedDialog
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_banner_hint
import me.him188.ani.app.ui.lang.tv_notice_banner_hint_more
import me.him188.ani.app.ui.lang.tv_notice_banner_hint_quick
import me.him188.ani.app.ui.lang.tv_notice_banner_hint_quick_more
import me.him188.ani.app.ui.onboarding.TvOnboardingLogin
import me.him188.ani.app.ui.profile.TvUserProfilePicker
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.whatsnew.TvWhatsNew
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 通知弹窗与通知气泡的宿主 (见 docs/contributing/code/notices.md), 挂在 TV 根组合上:
 * - 通知横幅: 进应用的弹窗走完后 ([TvNotices.startBubbles]) 有这一批还没弹过的没看过的通知 ([tvNoticesToBubble]), 等用户在有侧边栏的页面
 *   (首页、搜索结果、条目详情页的首屏: [onBannerPage] 是这几种页面, 那一页的横幅位 [TvNoticeBubbles.canShow] 挂着)、
 *   上面没有别的层 (引导、选人、本版新功能、控制台二维码、通知弹窗) 时在右上角弹 ([TvNoticeBubbles.show], 由 [TvNoticeBubbleHost] 画);
 *   短按确定做第一条能一按就做的那个操作 ([TvShownNotice.quick], 这一条算读过; 没有的打开通知弹窗), 长按打开通知弹窗 (开到哪见 [openBubbleItems]),
 *   横幅都收起; 按返回、10 秒没动作收起. 收起都记下这一批弹过了 (不算看过);
 * - 一条的操作 (只有一个时在列表里按确定键, 几个时在右栏挑): 弹窗的叠在通知弹窗上 (通知弹窗不收起; 小窗不再压暗一层、实底, 关掉回到下面, 焦点还在打开它的那一行或那颗按钮上); 页面的跳过去,
 *   回到打开通知时的那一页 (一般是首页) 时还有通知就重新打开列表; 当场做完的在这里跑, 留在列表;
 */
@Composable
fun TvNoticeHost(navigator: AniNavigator, onMainPage: () -> Boolean, onBannerPage: () -> Boolean = onMainPage) {
    LaunchedEffect(Unit) { TvNotices.install() }
    PrewarmTvNoticeView { onMainPage() && nothingCovering() && TvNotices.dialog.value == null }
    val currentOnMainPage by rememberUpdatedState(onMainPage)
    val currentOnBannerPage by rememberUpdatedState(onBannerPage)
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    // 直接做一条的操作时开的弹窗 (横幅上短按): 叠在当时的页面上, 不开列表, 关掉回到原处
    var direct by remember { mutableStateOf<TvNoticeDestination.Dialog?>(null) }

    // 直接做一个操作 (不经通知弹窗): 弹窗的叠在当时的页面上, 页面的跳过去, 当场做完的在这里跑 (提示由它自己给)
    fun perform(destination: TvNoticeDestination) {
        when (destination) {
            is TvNoticeDestination.Dialog -> direct = destination
            is TvNoticeDestination.Page -> destination.navigate(navigator)
            is TvNoticeDestination.Run -> scope.launch { destination.run(toaster) }
        }
    }
    // 眼前这一页画得出横幅: 是有侧边栏的那几种页面, 而且它的横幅位挂着 (侧边栏在; 搜索页不在挑关键词)
    val canBubble = { currentOnBannerPage() && TvNoticeBubbles.canShow }

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
    // 横幅说的那几条: 弹出来以后一直在, 直到按确定、返回或 10 秒没动作. 通知弹窗从别处打开时 (手机控制台) 先不画, 关掉回到横幅;
    // 用着时又来了新的换成新的这一批
    var bubbleItems by remember { mutableStateOf<List<TvShownNotice>>(emptyList()) }
    val currentToBubble by rememberUpdatedState(toBubble)
    LaunchedEffect(toBubble.isNotEmpty() && bubbleItems.isEmpty()) {
        if (toBubble.isEmpty() || bubbleItems.isNotEmpty()) return@LaunchedEffect
        // 等到有侧边栏的页面、上面没有别的层 (通知弹窗也不开着) 时弹
        while (!(canBubble() && nothingCovering() && TvNotices.dialog.value == null)) delay(POLL_INTERVAL)
        bubbleItems = currentToBubble
    }
    LaunchedEffect(toBubble) {
        if (bubbleItems.isNotEmpty() && toBubble.isNotEmpty()) bubbleItems = toBubble
    }
    // 关掉通知弹窗回到横幅: 换一个新的横幅, 焦点重新落在它身上、10 秒重新计
    var bubbleRound by remember { mutableIntStateOf(0) }
    var dialogWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) {
        if (dialogWasOpen && !dialogOpen) bubbleRound++
        dialogWasOpen = dialogOpen
    }
    // 别处收起了这一批 (TvNotices.dismissBubble): 都已经在「弹过」里, 不再画
    val bubbleHandled = bubbleItems.isNotEmpty() && bubbleItems.all { tvNoticeBubbleKey(it) in bubbled }
    if (bubbleHandled) LaunchedEffect(Unit) { bubbleItems = emptyList() }
    if (bubbleItems.isNotEmpty() && !bubbleHandled) {
        val items = bubbleItems
        // 写第一条 (列出来的已经排好先后) 的图标与标题, 说的是哪一部作品时第一行写作品名、标题挪到第二行; 几条时第二行写还有几条
        val first = items.first()
        val icon = first.kind.category.icon
        val nsfw = rememberNsfwPolicy()
        val cover = first.cover?.imageFor(nsfw)
        val title = first.title
        val lead = first.cover?.name?.ifBlank { null }
        // 第一条有能一按就做的操作时, 提示写短按做什么、长按看更多; 没有的短按、长按都打开通知弹窗, 只写按确定键查看
        val quickLabel = first.quick?.quick
        val more = items.size - 1
        val hint = when {
            quickLabel != null && more == 0 -> stringResource(Lang.tv_notice_banner_hint_quick, quickLabel)
            quickLabel != null -> stringResource(Lang.tv_notice_banner_hint_quick_more, quickLabel, more)
            more == 0 -> stringResource(Lang.tv_notice_banner_hint)
            else -> stringResource(Lang.tv_notice_banner_hint_more, more)
        }
        // 按下去时做的是此刻的那一条 (列出来的通知重算时操作对象会换新, 横幅不跟着重建)
        val latestItems by rememberUpdatedState(items)
        val keys = items.map(::tvNoticeBubbleKey).toSet()
        val dismiss = {
            TvNotices.bubbled.value += keys
            bubbleItems = emptyList()
        }
        // 去了画不出横幅的页面 (播放页、设置; 从通知里跳过去的也是): 横幅收起 (算处理过), 不在那一页上抢焦点. 通知弹窗开着 (叠在页面上)
        // 时不看, 关掉后再看. 两个有侧边栏的页面之间换页的那一下, 新一页的横幅位还没挂上: 连着几次都画不了才算
        LaunchedEffect(keys, dialogOpen) {
            if (dialogOpen) return@LaunchedEffect
            var misses = 0
            while (misses < BANNER_GONE_POLLS) {
                delay(POLL_INTERVAL)
                misses = if (canBubble()) 0 else misses + 1
            }
            dismiss()
        }
        // 通知弹窗开着时不画 (面板半透明, 横幅的字会透在后面): 横幅还在 (bubbleItems 不变), 关掉弹窗照旧出来
        if (!dialogOpen) DisposableEffect(icon, cover, title, lead, hint, keys, bubbleRound) {
            val bubble = TvNoticeBubble(
                icon = icon,
                title = title,
                hint = hint,
                onAction = {
                    val target = latestItems.first()
                    val action = target.quick
                    if (action != null) {
                        // 不经通知弹窗做了这一条的事: 算读过 (也就看过)
                        TvNotices.markReadKeys(listOf(target.key))
                        perform(action.destination)
                    } else {
                        openBubbleItems(latestItems)
                    }
                    dismiss()
                },
                onLongAction = {
                    openBubbleItems(latestItems)
                    dismiss()
                },
                onDismiss = dismiss,
                cover = cover,
                lead = lead,
            )
            TvNoticeBubbles.show(bubble)
            onDispose { TvNoticeBubbles.hide(bubble) }
        }
    }

    // 跳去处页面时通知弹窗开在哪一页上 (一般是首页)
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
        // 刚处理掉的是最后一条通知: 不再打开一个空列表 (别的签照开)
        if (target.tab != TvNoticeTab.NOTICES || TvNotices.shown.value.orEmpty().isNotEmpty()) {
            TvNotices.open(target.focusKey, target.focusIndex, fresh = target.fresh, tab = target.tab)
        }
    }

    val state by TvNotices.dialog.collectAsState()
    val items by TvNotices.shown.collectAsState()
    // 叠在通知弹窗上的弹窗 (一条的操作开的) 与开它的那一条 (列表里第几条、键)
    var overlay by remember { mutableStateOf<TvNoticeDestination.Dialog?>(null) }
    var overlayFrom by remember { mutableStateOf<Pair<Int, String>?>(null) }

    val shownDirect = direct
    if (shownDirect != null) {
        // 直接开的弹窗里再跳页面: 关掉它跳过去 (没开着通知弹窗, 回来不用重新打开)
        val directHost = remember(shownDirect) {
            TvNoticeOverlayHost(scope) { page ->
                direct = null
                page.navigate(navigator)
            }
        }
        CompositionLocalProvider(LocalTvNoticeOverlayHost provides directHost) {
            shownDirect.content { direct = null }
        }
        return
    }

    val current = state
    if (current == null) {
        if (overlay != null) LaunchedEffect(Unit) { overlay = null }
        return
    }
    // 从通知弹窗跳一个页面: 弹窗收起, 记下回来时开到哪一条 (回到打开通知时的那一页时重新打开)
    fun openPageFromDialog(destination: TvNoticeDestination.Page, key: String, index: Int) {
        TvNotices.close()
        returnOrigin = runCatching { navigator.backStack.lastOrNull() }.getOrNull()
        TvNotices.returnTo.value = TvNoticeDialogState(current.fresh, key, index, tab = current.tab)
        destination.navigate(navigator)
    }
    TvNoticeDialog(
        items.orEmpty(),
        current,
        tabs = TvNotices.tabs,
        onAction = { index, item, action ->
            // 在这一条上做了操作: 算读过
            TvNotices.markReadKeys(listOf(item.key))
            when (val destination = action.destination) {
                is TvNoticeDestination.Dialog -> {
                    overlay = destination
                    overlayFrom = index to item.key
                }

                is TvNoticeDestination.Page -> openPageFromDialog(destination, item.key, index)

                // 当场做完, 留在列表 (做完弹的提示由它自己给)
                is TvNoticeDestination.Run -> scope.launch { destination.run(toaster) }
            }
        },
        onRead = { key -> TvNotices.markReadKeys(listOf(key)) },
        onSelectTab = { id, intoList, focusKey -> TvNotices.selectTab(id, intoList, focusKey) },
        onDismiss = { TvNotices.close() },
    )
    // 叠在通知弹窗上面 (另一个窗口, 后建的在上): 通知弹窗不收起, 焦点留在打开它的那一行或那颗按钮上, 关掉就回到下面接着用.
    // 下面那层已经压暗过, 这一层不再压暗; 实底, 下面的字不透上来
    // 里面再跳页面同操作本身是页面的: 回来时通知弹窗重新打开、停在打开这个弹窗的那一条
    overlay?.let { shown ->
        val from = overlayFrom
        val overlayHost = remember(shown, from, current) {
            TvNoticeOverlayHost(scope) { page ->
                overlay = null
                if (from != null) {
                    openPageFromDialog(page, from.second, from.first)
                } else {
                    TvNotices.close()
                    page.navigate(navigator)
                }
            }
        }
        CompositionLocalProvider(LocalStackedDialog provides true, LocalTvNoticeOverlayHost provides overlayHost) {
            shown.content { overlay = null }
        }
    }
}

/**
 * 横幅上按确定: 这一批都在同一个签里看 (只有一条, 或都是反馈回复这种在别的签里看的, 见 [TvNoticeKind.tab]) 时开到那个签、焦点在第一条那一行;
 * 混着的开「通知」签, 焦点在它里面列的第一条上.
 */
private fun openBubbleItems(items: List<TvShownNotice>) {
    val tabs = items.map { it.kind.tab }.distinct()
    val tab = tabs.singleOrNull()
    when {
        tabs.size == 1 && tab != null -> TvNotices.open(focusKey = items.first().key, tab = tab)
        else -> TvNotices.open(focusKey = items.firstOrNull { it.kind.listed }?.key)
    }
}

private fun nothingCovering(): Boolean =
    TvOnboardingLogin.request.value == null && !TvUserProfilePicker.visible.value &&
            TvWhatsNew.launchContent.value == null && !TvRemoteControl.dialogVisible.value

private val POLL_INTERVAL = 300.milliseconds

/** 横幅在场时, 连着这么多次 ([POLL_INTERVAL] 一次) 眼前都画不出它, 才算去了别的页面. */
private const val BANNER_GONE_POLLS = 2

/**
 * 返回栈顶的这一页是不是有侧边栏的那几种 (首页、搜索、条目详情): 通知横幅只在这几种页面上弹, 而且要那一页的横幅位挂着
 * (见 [TvNoticeBubbleHost]: 搜索页挑关键词时、详情页翻离首屏后不挂).
 */
fun tvNoticeBannerRoute(route: Any?): Boolean =
    route is NavRoutes.Main || route is NavRoutes.SubjectSearch || route is NavRoutes.SubjectDetail

/** 跳去处页面后最多等这么久离开首页; 跳不过去 (导航没生效) 就当已经回来了. */
private val LEAVE_WAIT = 3.seconds
