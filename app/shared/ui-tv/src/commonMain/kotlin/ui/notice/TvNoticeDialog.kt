/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.ProvideRingOnlyFocus
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusSelectableSurface
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_TITLE_GAP
import me.him188.ani.app.ui.foundation.widgets.ProvideScrollFadeFocusMargin
import me.him188.ani.app.ui.foundation.widgets.aniFocusContainerColor
import me.him188.ani.app.ui.foundation.widgets.aniFocusContentColor
import me.him188.ani.app.ui.foundation.widgets.aniPanelItemColor
import me.him188.ani.app.ui.foundation.widgets.aniPanelTitleStyle
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.notification_empty
import me.him188.ani.app.ui.lang.notification_time_date
import me.him188.ani.app.ui.lang.notification_time_hours_ago
import me.him188.ani.app.ui.lang.notification_time_just_now
import me.him188.ani.app.ui.lang.notification_time_minutes_ago
import me.him188.ani.app.ui.lang.notification_time_month_day
import me.him188.ani.app.ui.lang.notification_time_yesterday
import me.him188.ani.app.ui.lang.notification_title
import me.him188.ani.app.ui.lang.tv_notice_section_earlier
import me.him188.ani.app.ui.lang.tv_notice_section_new
import me.him188.ani.app.ui.remote.CONNECTED_GREEN_DARK
import me.him188.ani.app.ui.remote.CONNECTED_GREEN_LIGHT
import me.him188.ani.app.ui.remote.RemoteQrCode
import org.jetbrains.compose.resources.stringResource

/**
 * 通知弹窗 (见 docs/contributing/code/notices.md): 顶栏左边是签 (有别的签 [tabs] 时, 「通知」在最前, 见 [TvNoticeTabRow]) 或标题,
 * 右边是刷新 (TvNoticeRefresh: 左边一行小字、按钮). 签里是左右两栏 ([TvNoticeColumns]): 「通知」签列通知 ([TvNoticeContent]),
 * 别的签列它给的 ([TvNoticeTab.rememberItems]). 换签由 [onSelectTab] 交给 [TvNotices] (记在 [TvNoticeDialogState.tab] 里,
 * 叠在上面的弹窗关掉、从页面回来都还在那个签).
 */
@Composable
internal fun TvNoticeDialog(
    items: List<TvShownNotice>,
    state: TvNoticeDialogState,
    tabs: List<TvNoticeTab>,
    onAction: (index: Int, item: TvNoticeColumnItem, action: TvNoticeAction) -> Unit,
    onRead: (key: String) -> Unit,
    onSelectTab: (id: String, intoList: Boolean, focusKey: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val refresh = rememberTvNoticeRefresh()
    AniCenteredPanelDialog(
        onDismissRequest = onDismiss,
        // 标题 (或签) 与刷新同在内容最上面一行
        title = null,
        heightFraction = null,
        maxWidth = TV_NOTICE_DIALOG_MAX_WIDTH,
    ) {
        // 安卓 (电视与平板) 上是原生视图, iOS 上是 Compose 画法 TvNoticeDialogBody, 照同一份数据画 (见 TvPlatformNotices)
        TvNoticePlatformBody(items, state, tabs, onAction, onRead, onSelectTab, onDismiss, refreshUi = { rememberTvNoticeRefreshUi() }, onRefresh = refresh)
    }
}

/**
 * 通知弹窗里的内容 (见 [TvNoticeDialog]): 顶栏 (签或标题, 右边刷新), 下面是选中那个签的两栏.
 * [refreshUi] 给刷新按钮的样子 (在顶栏里组合, 它每秒变时只重画顶栏); null = 不放刷新.
 *
 * 焦点同电视上的原生版 (TvNoticeView): 签能聚焦, 聚焦即选中, 左右键在签之间走, 最右的签再按右键到刷新, 刷新上按左键回到选中的签;
 * 签与刷新上按下键进这个签的第一行; 列表第一行按上键到选中的签 (没有签时到刷新); 列表里右键进右栏, 左键不动.
 * 返回键一次退一层: 右栏回到那一行, 列表、签与刷新上关掉弹窗. 换签由 [onSelectTab] 交出去 (焦点留在签上); 触屏上点签换签.
 */
@Composable
internal fun TvNoticeDialogBody(
    items: List<TvShownNotice>,
    state: TvNoticeDialogState,
    tabs: List<TvNoticeTab>,
    onAction: (index: Int, item: TvNoticeColumnItem, action: TvNoticeAction) -> Unit,
    onRead: (key: String) -> Unit,
    onSelectTab: (id: String, intoList: Boolean, focusKey: String?) -> Unit,
    onDismiss: () -> Unit,
    refreshUi: (@Composable () -> TvNoticeRefreshUi)? = null,
    onRefresh: () -> Unit = {},
) {
    val selected = tabs.firstOrNull { it.id == state.tab }?.id ?: TvNoticeTab.NOTICES
    val refreshFocus = remember { FocusRequester() }
    // 选中的那个签: 列表第一行按上、刷新上按左落到它
    val tabFocus = remember { FocusRequester() }
    // 从列表换签、新签的两栏还没画出来时焦点停的地方 (看不见): 旧签的列表马上拿掉, 不停的话焦点丢到弹窗里第一个能聚焦的地方
    val parkFocus = remember { FocusRequester() }
    val columnsFocus = remember { TvNoticeColumnsFocus() }
    // 刚在签上换的签 (还没重组出那个签的两栏时就按了下键的, 不进旧签的列表)
    val selectedNow = remember { arrayOf(selected) }
    selectedNow[0] = selected
    Column {
        Row(
            Modifier.fillMaxWidth().padding(bottom = if (tabs.isEmpty()) CENTERED_PANEL_TITLE_GAP else TV_NOTICE_TABS_GAP),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (tabs.isNotEmpty()) {
                val labels = listOf(TvNoticeTabLabel(TvNoticeTab.NOTICES, stringResource(Lang.notification_title), items.any { it.kind.listed && !it.entry.read })) +
                    tabs.map { extra ->
                        val fresh by remember(extra.id) { extra.hasFresh }.collectAsState(false)
                        TvNoticeTabLabel(extra.id, stringResource(extra.label), fresh)
                    }
                TvNoticeTabRow(
                    labels,
                    selected = selected,
                    onSelect = { id ->
                        selectedNow[0] = id
                        onSelectTab(id, false, null)
                    },
                    selectedFocus = tabFocus,
                    onEnter = { columnsFocus.enterList(selectedNow[0]) },
                    rightOfLast = if (refreshUi != null) refreshFocus else null,
                )
            } else {
                Text(stringResource(Lang.notification_title), style = aniPanelTitleStyle)
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(1.dp)
                    .focusRequester(parkFocus)
                    .focusProperties {
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }
                    .focusable(),
            )
            if (refreshUi != null) {
                TvNoticeRefreshHeader(
                    refreshUi,
                    onRefresh,
                    focusRequester = refreshFocus,
                    onDown = { columnsFocus.enterList(selectedNow[0]) },
                    // 左键回到选中的签 (同原生版)
                    onSideways = { direction -> if (direction < 0 && tabs.isNotEmpty()) runCatching { tabFocus.requestFocus() } },
                )
            }
        }
        // 各签的行都在这里取 (位置固定, 换签时不从头取): 弹窗开着时各签的行一直备着, 换过去时多半已经在了
        val tabItems = tabs.map { tab -> key(tab.id) { tab.rememberItems() } }
        // 选中了哪个签 (换签、打开时就在它) 告诉那个签
        LaunchedEffect(selected) { tabs.firstOrNull { it.id == selected }?.onTabShown() }
        fun ready(id: String): Boolean = id == TvNoticeTab.NOTICES || tabs.indexOfFirst { it.id == id }.let { it >= 0 && tabItems[it] != null }
        // 换签: 从列表换的先把焦点停在看不见的 parkFocus 上, 新签的行取到了 (下一帧起) 再换两栏.
        // 焦点在旧签的列表里时直接换的话, 聚焦的那一行随旧签的两栏一起拿掉, 焦点丢到弹窗里第一个能聚焦的地方, 新签那一行的初始焦点也可能被它盖掉;
        // 新签的行还没取到就换的话, 两栏先空一下
        var shownTab by remember { mutableStateOf(selected) }
        val lastShown = remember { arrayOf(state) }
        if (selected == shownTab) lastShown[0] = state
        if (selected != shownTab) {
            LaunchedEffect(selected) {
                if (!state.focusOnTabs) runCatching { parkFocus.requestFocus() }
            }
            if (ready(selected)) {
                LaunchedEffect(selected) { shownTab = selected }
            }
        }
        val shownState = lastShown[0]
        // 列表第一行上面是选中的签 (没有签时是刷新)
        val up = if (tabs.isNotEmpty()) tabFocus else if (refreshUi != null) refreshFocus else null
        key(shownTab) {
            val shownIndex = tabs.indexOfFirst { it.id == shownTab }
            if (shownIndex < 0) {
                TvNoticeContent(
                    items,
                    shownState,
                    onAction,
                    onRead,
                    onDismiss,
                    tabFocus = up,
                    columnsFocus = columnsFocus,
                )
            } else {
                TvNoticeTabContent(
                    tabs[shownIndex],
                    tabItems[shownIndex],
                    shownState,
                    onAction,
                    onDismiss,
                    tabFocus = up,
                    columnsFocus = columnsFocus,
                )
            }
        }
    }
}

/**
 * 顶栏右边: 一行小字 ([TvNoticeRefreshUi.status], 刷完有新通知时用主色) 与刷新按钮. 按钮上按下键 [onDown] (回列表), 左右键交给 [onSideways]
 * (只在画着的签一行都没有时换签), 上键不动.
 */
@Composable
private fun TvNoticeRefreshHeader(
    refreshUi: @Composable () -> TvNoticeRefreshUi,
    onRefresh: () -> Unit,
    focusRequester: FocusRequester,
    onDown: () -> Unit,
    onSideways: (direction: Int) -> Unit,
) {
    val ui = refreshUi()
    ui.status?.let {
        Text(
            it,
            Modifier.padding(end = TV_NOTICE_REFRESH_STATUS_GAP, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (ui.statusHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    TvNoticeRefreshButton(
        ui,
        onRefresh,
        Modifier
            .focusRequester(focusRequester)
            .focusProperties {
                left = FocusRequester.Cancel
                up = FocusRequester.Cancel
                right = FocusRequester.Cancel
                down = FocusRequester.Cancel
            }
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                when (event.key) {
                    Key.DirectionDown -> if (down) onDown()
                    Key.DirectionLeft -> if (down) onSideways(-1)
                    Key.DirectionRight -> if (down) onSideways(1)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
    )
}

/**
 * 「通知」签: 一条通知一行 —— 类别图标 (没读过的图标右上角一个红点)、标题与时间、标题下面一行摘要; 右栏写焦点所在那一条:
 * 类别签与时间、标题、说明与操作 ([TvShownNotice.detail] / [TvShownNotice.actions]). 新的与之前的都有时分两段.
 * 在列表里按确定键进右栏 (操作都画成按钮, 落在第一颗). 右栏说一条没读过的说满一会儿时报 [onRead].
 * 焦点与按键见 [TvNoticeColumns].
 */
@Composable
internal fun TvNoticeContent(
    items: List<TvShownNotice>,
    state: TvNoticeDialogState,
    onAction: (index: Int, item: TvNoticeColumnItem, action: TvNoticeAction) -> Unit,
    onRead: (key: String) -> Unit,
    onDismiss: () -> Unit,
    tabFocus: FocusRequester? = null,
    columnsFocus: TvNoticeColumnsFocus? = null,
    onShown: (key: String) -> Unit = {},
) {
    val columnItems = rememberTvNoticeColumnItems(items, state)
    TvNoticeColumns(
        columnItems,
        opening = state,
        focusKey = state.focusKey,
        focusIndex = state.focusIndex,
        focusAction = state.focusAction,
        takeInitialFocus = !state.focusOnTabs,
        emptyText = stringResource(Lang.notification_empty),
        onAction = onAction,
        onDismiss = onDismiss,
        tabFocus = tabFocus,
        columnsFocus = columnsFocus,
        onRead = onRead,
        onShown = onShown,
    )
}

/**
 * 「通知」签列的行: 一条通知一行 (在别的签里看的那几类不列, 见 [TvNoticeKind.tab]). 「新」那一段是打开 ([state]) 那一刻没读过的与开着时新来的
 * ([TvNotices.arrivedWhileOpen]), 照它排 —— 照此刻读没读过排的话, 读了一条它就在焦点底下挪走. 前面的点照此刻的: 读了就灭.
 * 新的与之前的都有时分两段, 各一行小标题 (同常见的「新 / 更早」); 只有一种时不写. 没有说明的种类正文写摘要, 外部链接 (码) 在正文后面.
 */
@Composable
internal fun rememberTvNoticeColumnItems(items: List<TvShownNotice>, state: TvNoticeDialogState): List<TvNoticeColumnItem> {
    // 只看打开那一刻没读过的与开着时新来的 (换签、读了一条时不重排)
    val arrived by remember { TvNotices.arrivedWhileOpen }.collectAsState()
    val fresh = state.fresh + arrived
    val sorted = remember(items, fresh) {
        items.filter { it.kind.listed }.sortedWith(
            compareBy<TvShownNotice> { it.key !in fresh }
                .thenBy { !it.entry.pending }
                .thenByDescending { it.notice.atMillis },
        )
    }
    val sectioned = sorted.any { it.key in fresh } && sorted.any { it.key !in fresh }
    val newLabel = stringResource(Lang.tv_notice_section_new)
    val earlierLabel = stringResource(Lang.tv_notice_section_earlier)
    val categories = TvNoticeCategory.entries.associateWith { stringResource(it.label) }
    val nsfw = rememberNsfwPolicy()
    return remember(sorted, fresh, newLabel, earlierLabel, categories, nsfw.snapshot) {
        sorted.map { item ->
            val isFresh = item.key in fresh
            val category = categories.getValue(item.kind.category)
            val detail = item.detail
            TvNoticeColumnItem(
                key = item.key,
                icon = item.kind.category.icon,
                iconLabel = category,
                title = item.title,
                atMillis = item.notice.atMillis,
                summary = item.summary,
                fresh = isFresh && !item.entry.read,
                section = if (!sectioned) null else if (isFresh) newLabel else earlierLabel,
                chips = listOf(TvNoticeChip(category)),
                subject = detail?.subject,
                actions = item.actions,
                blocks = (detail?.body ?: listOfNotNull(item.summary).map { TvNoticeParagraph(it) }) + listOfNotNull(detail?.qr),
                cover = item.cover?.imageFor(nsfw),
            )
        }
    }
}

/** 别的签 ([TvNoticeTab]) 里的两栏: 列它给的那几行 [items] (见 [TvNoticeTab.rememberItems]), 还在取 (null) 时空着. */
@Composable
private fun TvNoticeTabContent(
    tab: TvNoticeTab,
    items: List<TvNoticeColumnItem>?,
    state: TvNoticeDialogState,
    onAction: (index: Int, item: TvNoticeColumnItem, action: TvNoticeAction) -> Unit,
    onDismiss: () -> Unit,
    tabFocus: FocusRequester?,
    columnsFocus: TvNoticeColumnsFocus?,
) {
    if (items == null) {
        // 还在取: 先占住两栏的高, 取到了再列 (这时才定焦点落在哪一行)
        TvNoticeColumnsLayout(Modifier.tvOverlayWindowKeys(onDismiss), list = {}, pane = {})
        return
    }
    TvNoticeColumns(
        items,
        opening = state,
        focusKey = state.focusKey,
        focusIndex = state.focusIndex,
        focusAction = state.focusAction,
        takeInitialFocus = !state.focusOnTabs,
        emptyText = tab.emptyText(),
        onAction = onAction,
        onDismiss = onDismiss,
        tabFocus = tabFocus,
        columnsFocus = columnsFocus,
        tabId = tab.id,
        bodyStartsAtEnd = tab.bodyStartsAtEnd,
        buttonsForSingleAction = tab.buttonsForSingleAction,
        onShown = tab::onShown,
    )
}

/**
 * 签与两栏之间的焦点: 签上按下键时进列表 ([enterList]: 落在右栏正在说的那一行). 由此刻画着的那个签的 [TvNoticeColumns] 登记 ([register]);
 * 刚换了签、新签的两栏还没画出来时按的下键不做 (不然焦点进了马上要拿掉的旧签列表, 丢到弹窗第一个能聚焦的地方).
 */
internal class TvNoticeColumnsFocus {
    private var tab: String? = null
    private var enter: () -> Unit = {}

    internal fun register(tab: String, enter: () -> Unit) {
        this.tab = tab
        this.enter = enter
    }

    internal fun enterList(tab: String) {
        if (tab == this.tab) enter()
    }
}

/**
 * 左边列表、右栏说明与操作的两栏 (通知弹窗里每个签都是它). 两栏一样高, 高固定 (焦点换一行时弹窗不变高变矮).
 * 右栏写焦点所在那一行 ([TvNoticeColumnItem]): 签与时间、大标题、[TvNoticeColumnItem.subject]、正文 (放不下时自己滚)、操作与最后一行按键提示.
 *
 * - 操作都画成按钮 (只有一个也画): 列表里按确定键是选中、进右栏, 在按钮上才做 ([onAction]); 行本身就是一件事的 ([TvNoticeColumnItem.rowAction]) 直接做.
 *   没有操作的只是告知.
 * - 画了按钮的: 在列表里按确定键焦点到第一颗, 列表里那一行铺浅底表示右栏说的是它; 进了右栏左右键在按钮之间走,
 *   最左一颗按左键或按返回键回到那一行. 按钮排一行 (主操作在左), 放不下时那一行左右滚到持焦的那颗; 上下键翻右栏正文、按钮钉在底部不动.
 * - 列表里右键同确定键进右栏 (画了按钮的行到第一颗, 没按钮而正文放不下的进正文), 左键不动; 换签在签上 (同原生版).
 * - [bodyStartsAtEnd]: 右栏正文一打开停在末尾 (最新的写在最下面).
 * - 有签 ([tabFocus]) 时列表第一行按上键到签上; 签上按下键回到列表 ([columnsFocus], 这一栏是签 [tabId] 的).
 *
 * 一打开 ([opening] 换了就是重新打开) 焦点在 [focusKey] 那一行 (没有就原来位置 [focusIndex] 上那一行, 再没有第一行); [takeInitialFocus] = false 时不抢焦点 (焦点在签上).
 * 指定了操作 ([focusAction]) 且那一行画了按钮时落在那颗按钮上. 右栏换成哪一行报 [onShown]; 右栏说一条带点的 ([TvNoticeColumnItem.fresh])
 * 说满 [TV_NOTICE_READ_AFTER] 报 [onRead].
 */
@Composable
internal fun TvNoticeColumns(
    items: List<TvNoticeColumnItem>,
    opening: Any,
    focusKey: String?,
    focusIndex: Int,
    focusAction: Int?,
    takeInitialFocus: Boolean,
    emptyText: String,
    onAction: (index: Int, item: TvNoticeColumnItem, action: TvNoticeAction) -> Unit,
    onDismiss: () -> Unit,
    tabFocus: FocusRequester? = null,
    columnsFocus: TvNoticeColumnsFocus? = null,
    tabId: String = TvNoticeTab.NOTICES,
    bodyStartsAtEnd: Boolean = false,
    buttonsForSingleAction: Boolean = false,
    onRead: (key: String) -> Unit = {},
    onShown: (key: String) -> Unit = {},
) {
    val touch = LocalTvTouchInputEnabled.current
    fun hasButtons(item: TvNoticeColumnItem) = item.rowAction == null && item.actions.isNotEmpty()
    val initialKey = remember(opening) {
        items.firstOrNull { it.key == focusKey }?.key
            ?: items.getOrNull(focusIndex.coerceAtMost(items.lastIndex))?.key
            ?: items.firstOrNull()?.key
    }
    val now = remember(opening) { Clock.System.now().toEpochMilliseconds() }
    // 指定了操作: 一打开焦点在右栏那颗按钮上, 不在行上. 只这一次: 之后回到这一行照常落在行上
    val focusActionIndex = remember(opening) {
        val item = items.firstOrNull { it.key == initialKey }
        val count = item?.actions?.size ?: 0
        focusAction?.takeIf { item != null && hasButtons(item) }?.coerceIn(0, count - 1)
    }
    val actionFirst = focusActionIndex != null && takeInitialFocus
    var actionFocusPending by remember(opening) { mutableStateOf(actionFirst) }
    // 右栏说的是哪一行: 最近聚焦的那一行; 焦点在右栏时 [inPane], 那一行铺浅底
    var activeKey by remember(opening) { mutableStateOf(initialKey) }
    var inPane by remember(opening) { mutableStateOf(actionFirst) }
    // 一打开 (含换签) 焦点要落的那一行: 焦点真落上之前 (初始焦点要等一两帧才送到) 先画成聚焦的样子,
    // 免得新的两栏刚出来那一两帧哪一行都没亮, 看着像闪一下. 焦点落进这两栏、或过了 [TV_NOTICE_PENDING_FOCUS_LOOK] 就不再这样画
    var focusLanded by remember(opening) { mutableStateOf(!takeInitialFocus || actionFirst) }
    if (!focusLanded) {
        LaunchedEffect(opening) {
            delay(TV_NOTICE_PENDING_FOCUS_LOOK)
            focusLanded = true
        }
    }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    val firstAction = remember { FocusRequester() }
    // 没有操作的行正文放不下时进正文翻 (见 TvNoticePane): 正文的焦点与它此刻放不放得下
    val bodyFocus = remember { FocusRequester() }
    val bodyScrollable = remember { booleanArrayOf(false) }
    fun canEnterBody(item: TvNoticeColumnItem) = !touch && item.actions.isEmpty() && item.rowAction == null && bodyScrollable[0]
    val scrollState = rememberScrollState()
    val activeIndex = items.indexOfFirst { it.key == activeKey }
    val active = items.getOrNull(activeIndex)
    val lastIndex = remember { intArrayOf(0) }
    if (activeIndex >= 0) lastIndex[0] = activeIndex
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(active?.key) { active?.key?.let { currentOnShown(it) } }
    // 右栏说一条没读过的说满 [TV_NOTICE_READ_AFTER]: 算读过 (同点开看了); 一按就换走的不算
    val currentOnRead by rememberUpdatedState(onRead)
    val reading = active?.takeIf { it.fresh }?.key
    LaunchedEffect(reading) {
        if (reading == null) return@LaunchedEffect
        delay(TV_NOTICE_READ_AFTER)
        currentOnRead(reading)
    }
    val currentItems by rememberUpdatedState(items)
    SideEffect {
        // 签上按下键: 落在第一行 (不回到刚才那一行, 同原生版)
        columnsFocus?.register(tabId) {
            currentItems.firstOrNull()?.key?.let { rowFocus[it] }?.let { runCatching { it.requestFocus() } }
        }
    }
    // 右栏说的那一行没了 (当场做完的操作、别处处理的): 焦点落到原来位置上的那一行
    val keys = items.map { it.key }
    LaunchedEffect(keys) {
        if (activeKey == null || keys.contains(activeKey)) return@LaunchedEffect
        val next = items.getOrNull(lastIndex[0].coerceAtMost(items.lastIndex)) ?: return@LaunchedEffect
        activeKey = next.key
        inPane = false
        rowFocus[next.key]?.let { runCatching { it.requestFocus() } }
    }
    // 一行都没有: 有签时焦点在签上 (弹窗里没有别的能聚焦的)
    if (items.isEmpty() && takeInitialFocus && tabFocus != null) {
        LaunchedEffect(opening) { runCatching { tabFocus.requestFocus() } }
    }
    // 焦点导航形态下行与按钮不叠 M3 的焦点态层: 它在焦点到达后淡入 45ms、离开后淡出 150ms, 底色却是同一帧换的 ——
    // 焦点在左右两栏之间挪时, 刚离开的那一行 (按钮) 还罩着一层渐隐的浅色, 看着闪一下. 示焦只靠聚焦实底 (同 ProvideRingOnlyFocus 的其他用处)
    ProvideRingOnlyFocus {
        ProvideScrollFadeFocusMargin {
            TvNoticeColumnsLayout(
                Modifier.tvOverlayWindowKeys(onDismiss),
                list = {
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .weight(1f)
                                .aniVerticalScrollFade(scrollState)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (items.isEmpty()) {
                                Text(
                                    emptyText,
                                    Modifier.padding(vertical = 16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val coverColumn = tvNoticeCoverColumn(items)
                            items.forEachIndexed { index, item ->
                                val section = item.section
                                if (section != null && (index == 0 || items[index - 1].section != section)) {
                                    TvNoticeSectionHeader(section, first = index == 0)
                                }
                                key(item.key) {
                                    val requester = rowFocus.getOrPut(item.key) { FocusRequester() }
                                    val buttons = hasButtons(item)
                                    TvNoticeRow(
                                        item,
                                        now = now,
                                        coverColumn = coverColumn,
                                        selected = (inPane || touch) && item.key == activeKey,
                                        focusLook = !focusLanded && !touch && item.key == initialKey,
                                        onClick = {
                                            activeKey = item.key
                                            val rowAction = item.rowAction
                                            when {
                                                rowAction != null -> onAction(index, item, rowAction)
                                                buttons -> runCatching { firstAction.requestFocus() }
                                                item.actions.isEmpty() -> if (canEnterBody(item)) runCatching { bodyFocus.requestFocus() }
                                            }
                                        },
                                        modifier = Modifier
                                            .tvWindowInitialFocus(enabled = takeInitialFocus && item.key == initialKey && !actionFirst)
                                            .focusRequester(requester)
                                            // 右键进右栏 (同确定键), 左键不动; 第一行按上键到签上
                                            .focusProperties {
                                                left = FocusRequester.Cancel
                                                right = FocusRequester.Cancel
                                                if (index == 0 && tabFocus != null) up = tabFocus
                                            }
                                            .onPreviewKeyEvent { event ->
                                                val direction = when (event.key) {
                                                    Key.DirectionLeft -> -1
                                                    Key.DirectionRight -> 1
                                                    else -> return@onPreviewKeyEvent false
                                                }
                                                if (event.type == KeyEventType.KeyDown && direction > 0) {
                                                    activeKey = item.key
                                                    when {
                                                        buttons -> runCatching { firstAction.requestFocus() }
                                                        canEnterBody(item) -> runCatching { bodyFocus.requestFocus() }
                                                    }
                                                }
                                                true
                                            }
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    focusLanded = true
                                                    activeKey = item.key
                                                    inPane = false
                                                    actionFocusPending = false
                                                }
                                            },
                                    )
                                }
                            }
                        }
                    }
                },
                pane = {
                    if (active != null) {
                        key(active.key) {
                            TvNoticePane(
                                active,
                                now = now,
                                touch = touch,
                                buttons = hasButtons(active),
                                bodyStartsAtEnd = active.bodyStartsAtEnd ?: bodyStartsAtEnd,
                                firstAction = firstAction,
                                bodyFocus = bodyFocus,
                                onBodyScrollable = { bodyScrollable[0] = it },
                                initialFocusAction = focusActionIndex?.takeIf { actionFocusPending && active.key == initialKey },
                                rowFocus = rowFocus[active.key],
                                onPaneFocus = { focused ->
                                    if (focused) focusLanded = true
                                    inPane = focused
                                    if (focused) actionFocusPending = false
                                },
                                onAction = { action ->
                                    // 当场做完的留在列表: 焦点先回那一行 (按钮可能随它处理掉一起没了). 一个操作也画按钮的签里那一行留着, 焦点留在按钮上
                                    if (action.destination is TvNoticeDestination.Run && !buttonsForSingleAction) {
                                        rowFocus[active.key]?.let { runCatching { it.requestFocus() } }
                                    }
                                    onAction(activeIndex, active, action)
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * 左边列表、右边一栏, 中间一条竖线: 两栏一样高, 高固定 [TV_NOTICE_BODY_HEIGHT] (焦点换一行时弹窗不跟着变高变矮), 超出的各自滚.
 * 列表宽 [TV_NOTICE_LIST_WIDTH], 窗口窄时按 [TV_NOTICE_LIST_MAX_FRACTION] 收窄, 右栏占剩下的.
 */
@Composable
private fun TvNoticeColumnsLayout(
    modifier: Modifier,
    list: @Composable () -> Unit,
    pane: @Composable () -> Unit,
) {
    val dividerColor = LocalContentColor.current.copy(alpha = TV_NOTICE_DIVIDER_ALPHA)
    Layout(
        contents = listOf(
            { Box(propagateMinConstraints = true) { list() } },
            { Box(Modifier.background(dividerColor)) },
            { Box(propagateMinConstraints = true) { pane() } },
        ),
        modifier = modifier,
    ) { (listMeasurables, dividerMeasurables, paneMeasurables), constraints ->
        val fixed = TV_NOTICE_BODY_HEIGHT.roundToPx()
        val height = if (constraints.hasBoundedHeight) fixed.coerceAtMost(constraints.maxHeight) else fixed
        val gap = TV_NOTICE_COLUMN_GAP.roundToPx()
        val dividerWidth = TV_NOTICE_DIVIDER_WIDTH.roundToPx()
        val listWidth = TV_NOTICE_LIST_WIDTH.roundToPx().coerceAtMost((constraints.maxWidth * TV_NOTICE_LIST_MAX_FRACTION).roundToInt())
        val paneWidth = (constraints.maxWidth - listWidth - dividerWidth - gap * 2).coerceAtLeast(0)
        val listPlaceable = listMeasurables.first().measure(Constraints.fixed(listWidth, height))
        val dividerPlaceable = dividerMeasurables.first().measure(Constraints.fixed(dividerWidth, height))
        val panePlaceable = paneMeasurables.first().measure(Constraints.fixed(paneWidth, height))
        layout(constraints.maxWidth, height) {
            listPlaceable.place(0, 0)
            dividerPlaceable.place(listWidth + gap, 0)
            panePlaceable.place(listWidth + gap * 2 + dividerWidth, 0)
        }
    }
}

/**
 * 右栏: 签与时间 (或 [TvNoticeColumnItem.meta])、大标题、哪一个、正文 (放不下时自己滚; [bodyStartsAtEnd] 时一打开停在末尾), 再是操作.
 * 焦点进出按钮这一栏报 [onPaneFocus]; 在最左一颗按左键或按返回键回到 [rowFocus] (列表里那一行); 横排时上下键翻正文.
 * 正文能拿焦点 ([bodyFocus]; 没有操作的行正文放不下时列表那边送进来): 上下键翻, 左键或返回键回到那一行; 放不放得下报 [onBodyScrollable].
 */
@Composable
private fun TvNoticePane(
    item: TvNoticeColumnItem,
    now: Long,
    touch: Boolean,
    buttons: Boolean,
    bodyStartsAtEnd: Boolean,
    firstAction: FocusRequester,
    bodyFocus: FocusRequester,
    onBodyScrollable: (Boolean) -> Unit,
    initialFocusAction: Int?,
    rowFocus: FocusRequester?,
    onPaneFocus: (Boolean) -> Unit,
    onAction: (TvNoticeAction) -> Unit,
) {
    val actions = item.actions
    // 停在末尾的: 从最大值起, 第一次量出正文高时就落在末尾 (ScrollState 量出最大值时把超出的收回去), 第一帧就到位, 不先在顶上画一帧
    val bodyScroll = rememberScrollState(if (bodyStartsAtEnd) Int.MAX_VALUE else 0)
    var bodyHeight by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // 翻页: 一下翻正文可见高度的 [TV_NOTICE_PAGE_FRACTION]; 连按时接着上一下的目标往下算, 不从动画半路算
    var pageTarget by remember { mutableIntStateOf(0) }
    val page: (Int) -> Unit = { direction ->
        val from = if (bodyScroll.isScrollInProgress) pageTarget else bodyScroll.value
        val target = (from + direction * (bodyHeight * TV_NOTICE_PAGE_FRACTION).roundToInt()).coerceIn(0, bodyScroll.maxValue)
        if (target != bodyScroll.value) {
            pageTarget = target
            scope.launch { bodyScroll.animateScrollTo(target) }
        }
    }
    val scrollable = bodyScroll.maxValue in 1 until Int.MAX_VALUE
    SideEffect { onBodyScrollable(scrollable) }
    Column(Modifier.fillMaxSize().testTag(TV_NOTICE_PANE_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.chips.forEach { TvNoticeChipText(it) }
            (item.meta ?: item.atMillis?.let { noticeTimeText(it, now) })?.let { meta ->
                Text(
                    meta,
                    Modifier.padding(start = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            item.title,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.subject?.let {
            Text(
                it,
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            Modifier
                .padding(top = 12.dp)
                .weight(1f)
                .onSizeChanged { bodyHeight = it.height }
                .focusRequester(bodyFocus)
                .focusProperties {
                    canFocus = !touch
                    left = rowFocus ?: FocusRequester.Cancel
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                }
                .onFocusChanged { onPaneFocus(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when (event.key) {
                        Key.DirectionUp -> if (down) page(-1)
                        Key.DirectionDown -> if (down) page(1)
                        // 返回键回到列表里那一行, 不关弹窗 (按下与抬起都吃掉)
                        Key.Back, Key.Escape -> if (!down) rowFocus?.let { runCatching { it.requestFocus() } }
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .focusable()
                .aniVerticalScrollFade(bodyScroll)
                .verticalScroll(bodyScroll)
                .testTag(TV_NOTICE_BODY_TAG),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvNoticeBlocks(item.blocks, touch)
        }
        if (buttons) {
            TvNoticeActions(
                actions,
                firstAction = firstAction,
                initialFocusAction = initialFocusAction,
                rowFocus = rowFocus,
                onPage = page,
                onAction = onAction,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .onFocusChanged { onPaneFocus(it.hasFocus) }
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                        // 返回键回到列表里那一行, 不关弹窗 (按下与抬起都吃掉)
                        if (event.type == KeyEventType.KeyUp) rowFocus?.let { runCatching { it.requestFocus() } }
                        true
                    },
            )
        }
    }
}

/** 右栏大标题上面的一个签 ([TvNoticeChip]). */
@Composable
internal fun TvNoticeChipText(chip: TvNoticeChip) {
    val accent = chip.accent
    Text(
        chip.text,
        Modifier
            .background(
                if (accent) MaterialTheme.colorScheme.primary.copy(alpha = TV_NOTICE_ACCENT_CHIP_ALPHA) else LocalContentColor.current.copy(alpha = TV_NOTICE_CHIP_ALPHA),
                RoundedCornerShape(7.dp),
            )
            .padding(horizontal = 9.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}

/** 正文的一段: 有小标题的小标题在上、说明在下 (要点前面画一个点), 没有小标题的就是一段话. */
@Composable
internal fun TvNoticeParagraphText(paragraph: TvNoticeParagraph, touch: Boolean) {
    // 触屏上操作画成按钮, 资源名这类长串写全会把按钮挤出右栏: 截到几行; 电视上只有一行按键提示, 照常写全.
    // 摘要类的说明 (maxLines) 两边都截
    val nameLines = if (touch && paragraph.longName) TV_NOTICE_TOUCH_NAME_LINES else Int.MAX_VALUE
    val maxLines = minOf(nameLines, paragraph.maxLines)
    val overflow = if (maxLines != Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip
    val plain = paragraph.title == null && !paragraph.bullet
    val color = when (paragraph.tone) {
        TvNoticeTone.Normal -> if (plain) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
        else -> paragraph.tone.statusColor()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (paragraph.bullet) {
            Box(Modifier.padding(top = 9.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            paragraph.title?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                paragraph.text,
                style = if (plain) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = color,
                maxLines = maxLines,
                overflow = overflow,
            )
        }
    }
}

/**
 * 右栏的操作按钮: 排一行, 各占自己的宽 (主操作在左: 从列表按确定键进来先到最左一颗); 一行放不下时这一行左右滚, 焦点到哪颗滚到哪颗.
 * 上下键交给 [onPage] 翻正文; 左键从最左一颗回到 [rowFocus]; [initialFocusAction] 是一打开就有焦点的那一颗.
 */
@Composable
private fun TvNoticeActions(
    actions: List<TvNoticeAction>,
    firstAction: FocusRequester,
    initialFocusAction: Int?,
    rowFocus: FocusRequester?,
    onPage: (direction: Int) -> Unit,
    onAction: (TvNoticeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .onPreviewKeyEvent { event ->
                // 上下键翻正文, 焦点不动
                val direction = when (event.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) onPage(direction)
                true
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        actions.forEachIndexed { index, action ->
            TvNoticeActionButton(
                action.label,
                { onAction(action) },
                Modifier
                    .heightIn(min = TV_NOTICE_ACTION_HEIGHT)
                    .tvWindowInitialFocus(enabled = index == initialFocusAction)
                    .then(if (index == 0) Modifier.focusRequester(firstAction) else Modifier)
                    .focusProperties {
                        if (index == 0) left = rowFocus ?: FocusRequester.Cancel
                        if (index == actions.lastIndex) right = FocusRequester.Cancel
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }
                    .testTag(TV_NOTICE_ACTION_TAG),
            )
        }
    }
}

@Composable
private fun TvNoticeActionButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    AniFocusSelectableSurface(
        onClick = onClick,
        selected = false,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Box(
            Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 右栏正文 [blocks] 的 Compose 画法 (iPad 用; 安卓上电视与平板都是原生视图照同一份数据画, 见 TvNoticeView): 一块一块往下排,
 * 外面那一栏竖排、间距 10dp, 放不下时自己滚.
 */
@Composable
internal fun TvNoticeBlocks(blocks: List<TvNoticeBlock>, touch: Boolean) {
    blocks.forEach { block ->
        when (block) {
            is TvNoticeParagraph -> TvNoticeParagraphText(block, touch)
            is TvNoticeMessage -> TvNoticeMessageBubble(block)
            is TvNoticeCard -> TvNoticeCardBlock(block)
            is TvNoticeQr -> if (tvNoticeHasConsole || !block.console) TvNoticeQrBlock(block, touch)
        }
    }
}

/** 来往记录里的一条 ([TvNoticeMessage]): 自己的靠右、主色淡底, 别人的靠左、中性淡底; 最宽占栏宽的 [TV_NOTICE_BUBBLE_MAX_FRACTION]. */
@Composable
private fun TvNoticeMessageBubble(message: TvNoticeMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .fillMaxWidth(TV_NOTICE_BUBBLE_MAX_FRACTION)
                .wrapContentWidth(if (message.mine) Alignment.End else Alignment.Start)
                .background(
                    if (message.mine) MaterialTheme.colorScheme.primary.copy(alpha = TV_NOTICE_MINE_BUBBLE_ALPHA) else LocalContentColor.current.copy(alpha = TV_NOTICE_CHIP_ALPHA),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(message.meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (message.fresh) Box(Modifier.size(TV_NOTICE_DOT_SIZE).background(MaterialTheme.colorScheme.error, CircleShape))
            }
            message.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** 一张卡片 ([TvNoticeCard]): 图标、标题 (长的截到两行)、状态一行, 有进度时标题行最右写百分比、下面一条进度. */
@Composable
private fun TvNoticeCardBlock(card: TvNoticeCard) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.onSurface.copy(alpha = TV_NOTICE_CARD_ALPHA), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val progress = card.progress?.coerceIn(0f, 1f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(card.icon, contentDescription = null, Modifier.size(22.dp), tint = scheme.primary)
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                card.status?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = card.statusTone.statusColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (progress != null) {
                Text(noticePercentText(progress), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
            }
        }
        when {
            progress != null -> LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(4.dp))
            card.indeterminate -> LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
        }
    }
}

/**
 * 正文里的二维码 ([TvNoticeQr]): 有标题的码在左、字在右; 没标题的码下写一行. 外部链接 ([TvNoticeQr.openOnTouch]) 在触屏上是一颗在浏览器里打开的按钮.
 * 还没有码 (url 为 null) 时只写字.
 */
@Composable
private fun TvNoticeQrBlock(qr: TvNoticeQr, touch: Boolean) {
    val url = qr.url
    if (touch && qr.openOnTouch && url != null) {
        val uriHandler = LocalUriHandler.current
        AniFocusSelectableSurface(
            onClick = { runCatching { uriHandler.openUri(url) } },
            selected = false,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = TV_NOTICE_ACTION_HEIGHT).testTag(TV_NOTICE_LINK_TAG),
        ) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                Text(qr.caption ?: url, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    if (qr.title == null) {
        Column(Modifier.padding(top = 8.dp).testTag(TV_NOTICE_LINK_TAG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (url != null) RemoteQrCode(url, TV_NOTICE_LINK_QR_SIZE, TV_NOTICE_LINK_QR_QUIET_ZONE, openOnTap = false)
            qr.caption?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            qr.status?.let { TvNoticeStatusLine(it, qr.statusTone) }
        }
        return
    }
    Row(Modifier.testTag(TV_NOTICE_LINK_TAG), verticalAlignment = Alignment.CenterVertically) {
        RemoteQrCode(url, TV_NOTICE_QR_SIZE, TV_NOTICE_QR_QUIET_ZONE, openOnTap = false)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(qr.title, style = MaterialTheme.typography.titleMedium)
            qr.caption?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            qr.status?.let {
                Spacer(Modifier.height(10.dp))
                TvNoticeStatusLine(it, qr.statusTone)
            }
        }
    }
}

/** 码旁的状态: 点 + 字, 颜色照 [tone] (平常的点用 outline、字淡一档). */
@Composable
private fun TvNoticeStatusLine(text: String, tone: TvNoticeTone) {
    val color = tone.statusColor()
    val plain = tone == TvNoticeTone.Normal || tone == TvNoticeTone.Secondary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (plain) MaterialTheme.colorScheme.outline else color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** 状态行与非平常色调的一段话的颜色 (见 [TvNoticeTone]). */
@Composable
internal fun TvNoticeTone.statusColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        TvNoticeTone.Normal, TvNoticeTone.Secondary -> scheme.onSurfaceVariant
        TvNoticeTone.Emphasis -> scheme.primary
        TvNoticeTone.Ok -> if (scheme.surface.luminance() < 0.5f) CONNECTED_GREEN_DARK else CONNECTED_GREEN_LIGHT
        TvNoticeTone.Error -> scheme.error
    }
}

/** 进度的百分比 (卡片标题行最右那个字). */
internal fun noticePercentText(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%"

/** 一段的小标题: 「新通知」「之前的」「处理中」. 不能聚焦. */
@Composable
private fun TvNoticeSectionHeader(text: String, first: Boolean) {
    Text(
        text,
        Modifier.padding(start = 4.dp, top = if (first) 0.dp else 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun TvNoticeRow(
    item: TvNoticeColumnItem,
    now: Long,
    coverColumn: Boolean,
    selected: Boolean,
    focusLook: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // [focusLook]: 焦点还没送到、马上要落在这一行, 先照聚焦的样子画 (同 AniFocusSelectableSurface 聚焦时的底色与字色)
    AniFocusSelectableSurface(
        onClick = onClick,
        selected = selected,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().testTag(TV_NOTICE_ROW_TAG),
        unselectedColor = if (focusLook) aniFocusContainerColor() else aniPanelItemColor(MaterialTheme.colorScheme.surfaceContainerHighest),
        unselectedContentColor = if (focusLook) aniFocusContentColor() else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (coverColumn) TV_NOTICE_COVER_START else 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (coverColumn) TV_NOTICE_COVER_GAP else 12.dp),
        ) {
            // 左边那一栏: 说的是哪一部作品的画它的封面 (竖的框, 取不到时还是类别图标), 别的画类别图标;
            // 列表里有带封面的行时这一栏都是封面那么宽 (字的起点对齐), 离行的左边、离文字收一点 (见 TV_NOTICE_COVER_START)
            val cover = item.cover
            Box(Modifier.width(if (coverColumn) TV_NOTICE_COVER_WIDTH else TV_NOTICE_ICON_SIZE), contentAlignment = Alignment.Center) {
                Box(
                    if (cover != null) Modifier.size(TV_NOTICE_COVER_WIDTH, TV_NOTICE_COVER_HEIGHT) else Modifier.size(TV_NOTICE_ICON_SIZE),
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = @Composable { Icon(item.icon, contentDescription = item.iconLabel, Modifier.size(TV_NOTICE_ICON_SIZE)) }
                    if (cover != null) {
                        TvNoticeCoverBox(cover, Modifier.fillMaxSize(), RoundedCornerShape(TV_NOTICE_COVER_CORNER), icon)
                    } else {
                        icon()
                    }
                    // 没读过的: 封面 (图标) 右上角一个红点
                    if (item.fresh) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 3.dp, y = (-3).dp)
                                .size(TV_NOTICE_DOT_SIZE)
                                .background(MaterialTheme.colorScheme.error, CircleShape),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        item.title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.atMillis?.let { at ->
                        Text(
                            noticeTimeText(at, now),
                            style = MaterialTheme.typography.labelMedium,
                            // 跟着行的内容色压淡: 聚焦时行铺主色, 固定的灰字在上面看不清
                            color = LocalContentColor.current.copy(alpha = TV_NOTICE_TIME_ALPHA),
                            maxLines = 1,
                        )
                    }
                }
                item.summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = TV_NOTICE_TIME_ALPHA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 今天的写「刚刚 / N 分钟前 / N 小时前」, 昨天写「昨天」, 今年的写月日, 更早的写年月日 (见 [formatNoticeTime]). */
@Composable
internal fun noticeTimeText(atMillis: Long, nowMillis: Long): String = formatNoticeTime(atMillis, nowMillis, rememberTvNoticeTimeTexts())

/** 年、月、日与月份的英文缩写 (英文的格式写「Nov 20, 2026」用第 4 个参数, 中文的用前 3 个); [sameYear] 时不写年. */
@Composable
internal fun noticeDateText(date: LocalDate, sameYear: Boolean): String = formatNoticeDate(date, sameYear, rememberTvNoticeTimeTexts())

/** 时间那几种写法的文案模板 (还没填参数的 `%1$s`), 由 [rememberTvNoticeTimeTexts] 取; 填参数见 [fillNoticeTemplate]. */
@Immutable
internal class TvNoticeTimeTexts(
    val justNow: String,
    val minutesAgo: String,
    val hoursAgo: String,
    val yesterday: String,
    val monthDay: String,
    val date: String,
)

@Composable
internal fun rememberTvNoticeTimeTexts(): TvNoticeTimeTexts {
    val justNow = stringResource(Lang.notification_time_just_now)
    val minutesAgo = stringResource(Lang.notification_time_minutes_ago)
    val hoursAgo = stringResource(Lang.notification_time_hours_ago)
    val yesterday = stringResource(Lang.notification_time_yesterday)
    val monthDay = stringResource(Lang.notification_time_month_day)
    val date = stringResource(Lang.notification_time_date)
    return remember(justNow, minutesAgo, hoursAgo, yesterday, monthDay, date) {
        TvNoticeTimeTexts(justNow, minutesAgo, hoursAgo, yesterday, monthDay, date)
    }
}

/** 见 [noticeTimeText]; [atMillis] ≤ 0 (没有时间) 为空字. 不在组合里 (原生视图的行在组合外换字时用). */
internal fun formatNoticeTime(atMillis: Long, nowMillis: Long, texts: TvNoticeTimeTexts): String {
    if (atMillis <= 0) return ""
    val zone = TimeZone.currentSystemDefault()
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val diff = (nowMillis - atMillis).coerceAtLeast(0)
    return when {
        diff < MINUTE -> texts.justNow
        diff < HOUR -> fillNoticeTemplate(texts.minutesAgo, (diff / MINUTE).toString())
        at.date == today -> fillNoticeTemplate(texts.hoursAgo, (diff / HOUR).toString())
        at.date == today.minus(1, DateTimeUnit.DAY) -> texts.yesterday
        else -> formatNoticeDate(at.date, sameYear = at.date.year == today.year, texts)
    }
}

private fun formatNoticeDate(date: LocalDate, sameYear: Boolean, texts: TvNoticeTimeTexts): String {
    val month = MONTH_ABBREVIATIONS[date.month.number - 1]
    return fillNoticeTemplate(
        if (sameYear) texts.monthDay else texts.date,
        date.year.toString(), date.month.number.toString(), date.day.toString(), month,
    )
}

/** 文案模板按位置填参数 (`%1$s` / `%1$d`), 同 Compose 资源带参数取字的格式. */
internal fun fillNoticeTemplate(template: String, vararg args: String): String =
    NOTICE_TEMPLATE_ARG.replace(template) { match -> args.getOrNull(match.groupValues[1].toInt() - 1) ?: match.value }

private val NOTICE_TEMPLATE_ARG = Regex("""%(\d+)\$[ds]""")

private val MONTH_ABBREVIATIONS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private const val MINUTE = 60_000L
private const val HOUR = 3_600_000L

internal const val TV_NOTICE_ROW_TAG = "tv-notice-row"
internal const val TV_NOTICE_PANE_TAG = "tv-notice-pane"
internal const val TV_NOTICE_BODY_TAG = "tv-notice-body"
internal const val TV_NOTICE_ACTION_TAG = "tv-notice-action"
internal const val TV_NOTICE_LINK_TAG = "tv-notice-link"

internal val TV_NOTICE_ICON_SIZE = 24.dp
internal val TV_NOTICE_DOT_SIZE = 8.dp

/** 一打开焦点要落的那一行先画成聚焦样子的最长时间 (焦点一般一两帧就送到; 被别处拿走了也不一直这么画). */
internal val TV_NOTICE_PENDING_FOCUS_LOOK = 600.milliseconds

/** 右栏说一条通知说满多久算读过 (同点开看了; 按着方向键一行行划过去的不算). */
internal val TV_NOTICE_READ_AFTER = 1.seconds

/** 行里的时间与摘要那几个字相对行的内容色的不透明度. */
internal const val TV_NOTICE_TIME_ALPHA = 0.75f

/** 弹窗宽: 列表 + 竖线 + 右栏. */
internal val TV_NOTICE_DIALOG_MAX_WIDTH = 880.dp

/** 签那一排与下面两栏之间的空 (签的字下面还有一条横线, 比标题与正文之间的空小一点). */
internal val TV_NOTICE_TABS_GAP = 8.dp

/** 刷新左边那行小字与按钮之间的空 (按钮聚焦时放大, 不压到字). */
internal val TV_NOTICE_REFRESH_STATUS_GAP = 16.dp

/** 两栏的高 (固定), 列表宽与它占弹窗内容宽的上限 (窄窗口按比例收), 两栏之间竖线两边的空. */
internal val TV_NOTICE_BODY_HEIGHT = 396.dp
internal val TV_NOTICE_LIST_WIDTH = 340.dp
internal const val TV_NOTICE_LIST_MAX_FRACTION = 0.42f
internal val TV_NOTICE_COLUMN_GAP = 20.dp

/** 来往记录的气泡最宽占栏宽多少; 自己那条的主色底、卡片的底相对颜色的不透明度. */
internal const val TV_NOTICE_BUBBLE_MAX_FRACTION = 0.86f
internal const val TV_NOTICE_MINE_BUBBLE_ALPHA = 0.18f
internal const val TV_NOTICE_CARD_ALPHA = 0.06f

/** 竖线与右栏签的底, 相对内容色 (主色签相对主色) 的不透明度; 竖线宽. */
internal const val TV_NOTICE_DIVIDER_ALPHA = 0.14f
internal const val TV_NOTICE_CHIP_ALPHA = 0.10f
internal const val TV_NOTICE_ACCENT_CHIP_ALPHA = 0.22f
internal val TV_NOTICE_DIVIDER_WIDTH = 1.dp

/** 右栏一颗操作的高. */
internal val TV_NOTICE_ACTION_HEIGHT = 40.dp

/** 横排按钮上按一下上下键, 正文翻可见高度的几分之几. */
internal const val TV_NOTICE_PAGE_FRACTION = 0.66f

/** 触屏上右栏里资源名这类长串 ([TvNoticeParagraph.longName]) 最多几行. */
internal const val TV_NOTICE_TOUCH_NAME_LINES = 3

/** 正文里的二维码 (边长与静区): 有标题的 (码在左、字在右) 与外部链接那种 (码下写字). */
internal val TV_NOTICE_QR_SIZE = 160.dp
internal val TV_NOTICE_QR_QUIET_ZONE = 12.dp
internal val TV_NOTICE_LINK_QR_SIZE = 112.dp
internal val TV_NOTICE_LINK_QR_QUIET_ZONE = 10.dp
