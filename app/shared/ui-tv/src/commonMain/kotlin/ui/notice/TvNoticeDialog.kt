/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusSelectableSurface
import me.him188.ani.app.ui.foundation.widgets.ProvideScrollFadeFocusMargin
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
import me.him188.ani.app.ui.lang.tv_notice_footer
import me.him188.ani.app.ui.lang.tv_notice_hint_back
import me.him188.ani.app.ui.lang.tv_notice_hint_choose_action
import me.him188.ani.app.ui.lang.tv_notice_hint_press_ok
import me.him188.ani.app.ui.lang.tv_notice_section_earlier
import me.him188.ani.app.ui.lang.tv_notice_section_new
import me.him188.ani.app.ui.remote.RemoteQrCode
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 通知弹窗 (见 docs/contributing/code/notices.md): 左边一条一行 —— 类别图标 (没看过的图标右上角一个红点)、标题与时间、标题下面一行摘要;
 * 右栏写焦点所在那一条: 类别签与时间、标题、说明与操作 ([TvShownNotice.detail] / [TvShownNotice.actions]). 两栏一样高, 高固定 (焦点换一条时弹窗不变高变矮),
 * 列表在上面、末行提示钉在下面, 正文放不下时自己滚.
 *
 * - 只有一个操作: 右栏不画按钮, 最后一行写「按确定键」接这个操作; 在列表里按确定键直接交给 [onAction].
 * - 几个操作: 两颗横排 (主操作在左)、三颗以上一颗一行竖排; 在列表里按确定键或右键焦点到第一颗, 列表里那一条铺浅底表示右栏说的是它;
 *   在右栏按左键 (最左一颗) 或返回键回到那一条.
 * - 触屏上 (平板) 一个操作也画按钮, 不写按键提示.
 *
 * 一打开焦点在 [state] 指定的那条 (没有就第一条); 指定了操作 ([TvNoticeDialogState.focusAction]) 且那一条有几个操作时在右栏那颗按钮上. 按返回 [onDismiss].
 * 一行在屏幕上露出一半以上时报 [onSeen] (每条一次): 显示到屏幕上的才算看过, 一页放不下的翻到才算.
 */
@Composable
internal fun TvNoticeDialog(
    items: List<TvShownNotice>,
    state: TvNoticeDialogState,
    onAction: (index: Int, item: TvShownNotice, action: TvNoticeAction) -> Unit,
    onSeen: (key: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AniCenteredPanelDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.notification_title)) },
        heightFraction = null,
        maxWidth = TV_NOTICE_DIALOG_MAX_WIDTH,
    ) {
        TvNoticeContent(items, state, onAction, onSeen, onDismiss)
    }
}

/** 通知弹窗里的内容: 左边列表、右栏的说明与操作 (见 [TvNoticeDialog]). */
@Composable
internal fun TvNoticeContent(
    items: List<TvShownNotice>,
    state: TvNoticeDialogState,
    onAction: (index: Int, item: TvShownNotice, action: TvNoticeAction) -> Unit,
    onSeen: (key: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 照打开那一刻排: 打开就算看过, 照此刻的「新不新」排的话, 列表一打开就重排一遍、行在焦点底下跳
    val items = remember(items, state) {
        items.sortedWith(
            compareBy<TvShownNotice> { it.key !in state.fresh }
                .thenBy { !it.entry.pending }
                .thenByDescending { it.notice.atMillis },
        )
    }
    val initialKey = remember(state) {
        items.firstOrNull { it.key == state.focusKey }?.key
            ?: items.getOrNull(state.focusIndex.coerceAtMost(items.lastIndex))?.key
            ?: items.firstOrNull()?.key
    }
    val now = remember(state) { Clock.System.now().toEpochMilliseconds() }
    // 已经报过看过的 (同一次打开里每条只报一次)
    val reported = remember(state) { HashSet<String>() }
    // 指定了操作 (state.focusAction: 从别处处理一条、叠在上面的弹窗关掉): 一打开焦点在右栏那颗按钮上, 不在行上. 只这一次: 之后回到这一条照常落在行上
    val focusActionIndex = remember(state) {
        val count = items.firstOrNull { it.key == initialKey }?.actions?.size ?: 0
        state.focusAction?.takeIf { count > 1 }?.coerceIn(0, count - 1)
    }
    val actionFirst = focusActionIndex != null
    var actionFocusPending by remember(state) { mutableStateOf(actionFirst) }
    // 右栏说的是哪一条: 最近聚焦的那一行; 焦点在右栏时 [inPane], 那一行铺浅底
    var activeKey by remember(state) { mutableStateOf(initialKey) }
    var inPane by remember(state) { mutableStateOf(actionFirst) }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    val firstAction = remember { FocusRequester() }
    val touch = LocalTvTouchInputEnabled.current
    val scrollState = rememberScrollState()
    val activeIndex = items.indexOfFirst { it.key == activeKey }
    val active = items.getOrNull(activeIndex)
    val lastIndex = remember { intArrayOf(0) }
    if (activeIndex >= 0) lastIndex[0] = activeIndex
    // 右栏说的那条处理掉了 (当场做完的操作、别处处理的): 焦点落到原来位置上的那一条
    LaunchedEffect(items) {
        if (activeKey == null || items.any { it.key == activeKey }) return@LaunchedEffect
        val next = items.getOrNull(lastIndex[0].coerceAtMost(items.lastIndex)) ?: return@LaunchedEffect
        activeKey = next.key
        inPane = false
        rowFocus[next.key]?.let { runCatching { it.requestFocus() } }
    }
    ProvideScrollFadeFocusMargin {
        TvNoticeColumns(
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
                                stringResource(Lang.notification_empty),
                                Modifier.padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 新的与看过的都有时分两段, 各一行小标题 (同常见的「新 / 更早」); 只有一种时不写
                        val sectioned = items.any { it.key in state.fresh } && items.any { it.key !in state.fresh }
                        items.forEachIndexed { index, item ->
                            if (sectioned && (index == 0 || (item.key !in state.fresh && items[index - 1].key in state.fresh))) {
                                TvNoticeSectionHeader(
                                    stringResource(if (item.key in state.fresh) Lang.tv_notice_section_new else Lang.tv_notice_section_earlier),
                                    first = index == 0,
                                )
                            }
                            key(item.key) {
                                val requester = rowFocus.getOrPut(item.key) { FocusRequester() }
                                val many = item.actions.size > 1
                                TvNoticeRow(
                                    item,
                                    fresh = item.key in state.fresh,
                                    now = now,
                                    selected = inPane && item.key == activeKey,
                                    onClick = {
                                        activeKey = item.key
                                        when {
                                            many -> runCatching { firstAction.requestFocus() }
                                            else -> item.actions.singleOrNull()?.let { onAction(index, item, it) }
                                        }
                                    },
                                    modifier = (if (item.key == initialKey && !actionFirst) Modifier.tvWindowInitialFocus() else Modifier)
                                        .focusRequester(requester)
                                        // 右键: 几个操作的进右栏第一颗, 别的不动 (右栏没有能聚焦的)
                                        .focusProperties { right = if (many) firstAction else FocusRequester.Cancel }
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                activeKey = item.key
                                                inPane = false
                                                actionFocusPending = false
                                            }
                                        }
                                        .onGloballyPositioned { coordinates ->
                                            // 滚动区之外被裁掉的部分不算: 窗口里的边界已按各层父节点裁过
                                            if (item.key in reported || coordinates.size.height <= 0) return@onGloballyPositioned
                                            if (coordinates.boundsInWindow().height >= coordinates.size.height * TV_NOTICE_SEEN_FRACTION) {
                                                reported += item.key
                                                onSeen(item.key)
                                            }
                                        },
                                )
                            }
                        }
                    }
                    if (items.isNotEmpty()) {
                        Text(
                            stringResource(Lang.tv_notice_footer),
                            Modifier.padding(top = 8.dp, start = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                            firstAction = firstAction,
                            initialFocusAction = focusActionIndex?.takeIf { actionFocusPending && active.key == initialKey },
                            rowFocus = rowFocus[active.key],
                            onPaneFocus = { focused ->
                                inPane = focused
                                if (focused) actionFocusPending = false
                            },
                            onAction = { action ->
                                // 当场做完的留在列表: 焦点先回那一条 (按钮可能随它处理掉一起没了)
                                if (action.destination is TvNoticeDestination.Run) rowFocus[active.key]?.let { runCatching { it.requestFocus() } }
                                onAction(activeIndex, active, action)
                            },
                        )
                    }
                }
            },
        )
    }
}

/**
 * 左边列表、右边一栏, 中间一条竖线: 两栏一样高, 高固定 [TV_NOTICE_BODY_HEIGHT] (焦点换一条时弹窗不跟着变高变矮), 超出的各自滚.
 * 列表宽 [TV_NOTICE_LIST_WIDTH], 窗口窄时按 [TV_NOTICE_LIST_MAX_FRACTION] 收窄, 右栏占剩下的.
 */
@Composable
private fun TvNoticeColumns(
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
 * 右栏: 类别签与时间、标题、哪一个 (作品、反馈)、正文几段 (放不下时自己滚) 与外部链接, 再是操作与最后一行按键提示. 没有说明的种类正文写摘要.
 * 焦点进出按钮这一栏报 [onPaneFocus]; 在按钮上按左键 (最左一颗) 或返回键回到 [rowFocus] (列表里那一条).
 */
@Composable
private fun TvNoticePane(
    item: TvShownNotice,
    now: Long,
    touch: Boolean,
    firstAction: FocusRequester,
    initialFocusAction: Int?,
    rowFocus: FocusRequester?,
    onPaneFocus: (Boolean) -> Unit,
    onAction: (TvNoticeAction) -> Unit,
) {
    val detail = item.detail
    val actions = item.actions
    val buttons = actions.size > 1 || (touch && actions.isNotEmpty())
    var focusedAction by remember { mutableIntStateOf(-1) }
    var buttonsFocused by remember { mutableStateOf(false) }
    val bodyScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().testTag(TV_NOTICE_PANE_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvNoticeCategoryChip(item.kind.category)
            Text(
                noticeTimeText(item.notice.atMillis, now),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            item.title,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.subject?.let {
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
                .aniVerticalScrollFade(bodyScroll)
                .verticalScroll(bodyScroll),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val paragraphs = detail?.body ?: listOfNotNull(item.summary).map { TvNoticeParagraph(it) }
            paragraphs.forEach { TvNoticeParagraphText(it, touch) }
            detail?.qr?.let { TvNoticeExternalLink(it, touch) }
        }
        if (buttons) {
            TvNoticeActions(
                actions,
                firstAction = firstAction,
                initialFocusAction = initialFocusAction,
                rowFocus = rowFocus,
                onFocusIndex = { focusedAction = it },
                onAction = onAction,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .onFocusChanged {
                        buttonsFocused = it.hasFocus
                        onPaneFocus(it.hasFocus)
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                        // 返回键回到列表里那一条, 不关弹窗 (按下与抬起都吃掉)
                        if (event.type == KeyEventType.KeyUp) rowFocus?.let { runCatching { it.requestFocus() } }
                        true
                    },
            )
        }
        if (!touch && actions.isNotEmpty()) {
            val focused = actions.getOrNull(focusedAction)?.takeIf { buttonsFocused }
            val hint = when {
                actions.size == 1 -> stringResource(Lang.tv_notice_hint_press_ok, actions.single().label)
                focused != null -> stringResource(Lang.tv_notice_hint_back, focused.label)
                else -> stringResource(Lang.tv_notice_hint_choose_action)
            }
            Text(
                hint,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 右栏标题上面的类别签. */
@Composable
private fun TvNoticeCategoryChip(category: TvNoticeCategory) {
    Text(
        stringResource(category.label),
        Modifier
            .background(LocalContentColor.current.copy(alpha = TV_NOTICE_CHIP_ALPHA), RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
    )
}

/** 正文的一段: 有小标题的小标题在上、说明在下 (要点前面画一个点), 没有小标题的就是一段话. */
@Composable
private fun TvNoticeParagraphText(paragraph: TvNoticeParagraph, touch: Boolean) {
    // 触屏上操作画成按钮, 资源名这类长串写全会把按钮挤出右栏: 截到几行; 电视上只有一行按键提示, 照常写全.
    // 摘要类的说明 (maxLines) 两边都截
    val nameLines = if (touch && paragraph.longName) TV_NOTICE_TOUCH_NAME_LINES else Int.MAX_VALUE
    val maxLines = minOf(nameLines, paragraph.maxLines)
    val overflow = if (maxLines != Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip
    val plain = paragraph.title == null && !paragraph.bullet
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
                color = if (plain) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = overflow,
            )
        }
    }
}

/**
 * 右栏的操作按钮: 至多 [TV_NOTICE_ROW_ACTIONS] 颗横排 (主操作在左: 从列表按右键进来先到最左一颗), 更多的一颗一行竖着排.
 * 左键从最左一颗 (竖排时每一颗) 回到 [rowFocus]; [initialFocusAction] 是一打开就有焦点的那一颗.
 */
@Composable
private fun TvNoticeActions(
    actions: List<TvNoticeAction>,
    firstAction: FocusRequester,
    initialFocusAction: Int?,
    rowFocus: FocusRequester?,
    onFocusIndex: (Int) -> Unit,
    onAction: (TvNoticeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inRow = actions.size <= TV_NOTICE_ROW_ACTIONS
    @Composable
    fun buttonModifier(index: Int): Modifier = Modifier
        .heightIn(min = TV_NOTICE_ACTION_HEIGHT)
        .tvWindowInitialFocus(enabled = index == initialFocusAction)
        .then(if (index == 0) Modifier.focusRequester(firstAction) else Modifier)
        .focusProperties {
            if (inRow) {
                if (index == 0) rowFocus?.let { left = it }
                if (index == actions.lastIndex) right = FocusRequester.Cancel
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
            } else {
                rowFocus?.let { left = it }
                if (index == 0) up = FocusRequester.Cancel
                if (index == actions.lastIndex) down = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
        }
        .onFocusChanged { if (it.isFocused) onFocusIndex(index) }
        .testTag(TV_NOTICE_ACTION_TAG)
    if (inRow) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            actions.forEachIndexed { index, action ->
                TvNoticeActionButton(action.label, center = true, { onAction(action) }, Modifier.weight(1f, fill = false).then(buttonModifier(index)))
            }
        }
    } else {
        Column(modifier.width(TV_NOTICE_ACTIONS_COLUMN_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEachIndexed { index, action ->
                TvNoticeActionButton(action.label, center = false, { onAction(action) }, Modifier.fillMaxWidth().then(buttonModifier(index)))
            }
        }
    }
}

@Composable
private fun TvNoticeActionButton(label: String, center: Boolean, onClick: () -> Unit, modifier: Modifier) {
    AniFocusSelectableSurface(
        onClick = onClick,
        selected = false,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Box(
            Modifier.padding(horizontal = if (center) 24.dp else 16.dp, vertical = 10.dp),
            contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 说明里的外部链接 (见 [TvNoticeQr]): 电视上画二维码, 码下写链接上的字; 触屏上是一颗在浏览器里打开的按钮. */
@Composable
private fun TvNoticeExternalLink(qr: TvNoticeQr, touch: Boolean) {
    if (touch) {
        val uriHandler = LocalUriHandler.current
        AniFocusSelectableSurface(
            onClick = { runCatching { uriHandler.openUri(qr.url) } },
            selected = false,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = TV_NOTICE_ACTION_HEIGHT).testTag(TV_NOTICE_LINK_TAG),
        ) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                Text(qr.caption, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else {
        Column(Modifier.padding(top = 8.dp).testTag(TV_NOTICE_LINK_TAG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RemoteQrCode(qr.url, TV_NOTICE_QR_SIZE, TV_NOTICE_QR_QUIET_ZONE)
            Text(qr.caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** 一段的小标题: 「新通知」「之前的」. 不能聚焦. */
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
    item: TvShownNotice,
    fresh: Boolean,
    now: Long,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AniFocusSelectableSurface(
        onClick = onClick,
        selected = selected,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().testTag(TV_NOTICE_ROW_TAG),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(TV_NOTICE_ICON_SIZE)) {
                Icon(item.kind.category.icon, contentDescription = stringResource(item.kind.category.label), Modifier.size(TV_NOTICE_ICON_SIZE))
                // 没看过的: 图标右上角一个红点 (同侧边栏头像上的)
                if (fresh) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 3.dp, y = (-3).dp)
                            .size(TV_NOTICE_DOT_SIZE)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                    )
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
                    Text(
                        noticeTimeText(item.notice.atMillis, now),
                        style = MaterialTheme.typography.labelMedium,
                        // 跟着行的内容色压淡: 聚焦时行铺主色, 固定的灰字在上面看不清
                        color = LocalContentColor.current.copy(alpha = TV_NOTICE_TIME_ALPHA),
                        maxLines = 1,
                    )
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

/** 今天的写「刚刚 / N 分钟前 / N 小时前」, 昨天写「昨天」, 今年的写月日, 更早的写年月日. */
@Composable
private fun noticeTimeText(atMillis: Long, nowMillis: Long): String {
    val zone = TimeZone.currentSystemDefault()
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val diff = (nowMillis - atMillis).coerceAtLeast(0)
    return when {
        diff < MINUTE -> stringResource(Lang.notification_time_just_now)
        diff < HOUR -> stringResource(Lang.notification_time_minutes_ago, (diff / MINUTE).toString())
        at.date == today -> stringResource(Lang.notification_time_hours_ago, (diff / HOUR).toString())
        at.date == today.minus(1, DateTimeUnit.DAY) -> stringResource(Lang.notification_time_yesterday)
        else -> dateText(at.date, sameYear = at.date.year == today.year)
    }
}

/** 年、月、日与月份的英文缩写 (英文的格式写「Nov 20, 2026」用第 4 个参数, 中文的用前 3 个). */
@Composable
private fun dateText(date: LocalDate, sameYear: Boolean): String {
    val y = date.year.toString()
    val m = date.month.number.toString()
    val d = date.day.toString()
    val month = MONTH_ABBREVIATIONS[date.month.number - 1]
    return if (sameYear) stringResource(Lang.notification_time_month_day, y, m, d, month) else stringResource(Lang.notification_time_date, y, m, d, month)
}

private val MONTH_ABBREVIATIONS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private const val MINUTE = 60_000L
private const val HOUR = 3_600_000L

internal const val TV_NOTICE_ROW_TAG = "tv-notice-row"
internal const val TV_NOTICE_PANE_TAG = "tv-notice-pane"
internal const val TV_NOTICE_ACTION_TAG = "tv-notice-action"
internal const val TV_NOTICE_LINK_TAG = "tv-notice-link"



private val TV_NOTICE_ICON_SIZE = 24.dp
private val TV_NOTICE_DOT_SIZE = 8.dp

/** 一行露出多少算显示到屏幕上了 (算看过). */
private const val TV_NOTICE_SEEN_FRACTION = 0.5f

/** 行里的时间与摘要那几个字相对行的内容色的不透明度. */
private const val TV_NOTICE_TIME_ALPHA = 0.75f

/** 弹窗宽: 列表 + 竖线 + 右栏. */
private val TV_NOTICE_DIALOG_MAX_WIDTH = 880.dp

/** 两栏的高 (固定), 列表宽与它占弹窗内容宽的上限 (窄窗口按比例收), 两栏之间竖线两边的空. */
private val TV_NOTICE_BODY_HEIGHT = 396.dp
private val TV_NOTICE_LIST_WIDTH = 340.dp
private const val TV_NOTICE_LIST_MAX_FRACTION = 0.42f
private val TV_NOTICE_COLUMN_GAP = 20.dp

/** 竖线与右栏类别签的底, 相对内容色的不透明度; 竖线宽. */
private const val TV_NOTICE_DIVIDER_ALPHA = 0.14f
private const val TV_NOTICE_CHIP_ALPHA = 0.10f
private val TV_NOTICE_DIVIDER_WIDTH = 1.dp

/** 至多几颗操作横排; 竖排时一颗的宽. */
private const val TV_NOTICE_ROW_ACTIONS = 2
private val TV_NOTICE_ACTIONS_COLUMN_WIDTH = 260.dp

/** 右栏一颗操作的高. */
private val TV_NOTICE_ACTION_HEIGHT = 40.dp

/** 触屏上右栏里资源名这类长串 ([TvNoticeParagraph.longName]) 最多几行. */
private const val TV_NOTICE_TOUCH_NAME_LINES = 3

/** 说明里外部链接的二维码 (边长与静区). */
private val TV_NOTICE_QR_SIZE = 112.dp
private val TV_NOTICE_QR_QUIET_ZONE = 10.dp
