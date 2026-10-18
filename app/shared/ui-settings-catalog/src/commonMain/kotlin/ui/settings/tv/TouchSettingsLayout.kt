/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsApplications
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.adaptive.AniTopAppBar
import me.him188.ani.app.ui.adaptive.AniTopAppBarDefaults
import me.him188.ani.app.ui.foundation.lan.QrCodeImage
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.widgets.BackNavigationIconButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings
import me.him188.ani.app.ui.lang.settings_media_source_done
import me.him188.ani.app.ui.lang.tv_settings_legacy_open
import me.him188.ani.app.ui.lang.tv_settings_on_phone
import me.him188.ani.app.ui.settings.rendering.P2p
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 两栏 (宽屏): 左栏分类 (分组小标题 + 分类), 右栏选中那一类的设置项, 进了一层 (单选、排序、组) 时是那一层.
 * 窄屏一次一栏: 点分类进去, 返回回到分类列表. [openedAtCategory] = 从别处直接跳到某一类 (窄屏时直接进那一类).
 */
@Composable
internal fun TouchSettingsLayout(content: TvSettingsContent, state: TvSettingsState, openedAtCategory: Boolean) {
    val navigator = LocalNavigator.current
    val nav by state.nav.collectAsState()
    val drilled = nav.drill.isNotEmpty()
    LaunchedEffect(content.railKey) {
        content.railKey?.let(TvSettingsRowIds::parseCategory)?.let { TouchSettingsLastPosition.categoryId = it }
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val wide = maxWidth >= TOUCH_SETTINGS_TWO_PANE_MIN_WIDTH
        var inCategory by rememberSaveable { mutableStateOf(openedAtCategory) }
        BackHandler(enabled = drilled || (!wide && inCategory)) {
            if (!state.backInRight()) inCategory = false
        }
        when {
            wide -> Row(Modifier.fillMaxSize()) {
                TouchSettingsRail(
                    content, highlightSelected = true, onBack = navigator::popBackStack,
                    onSelect = state::onRailFocused,
                    modifier = Modifier.width(TOUCH_SETTINGS_RAIL_WIDTH).fillMaxHeight(),
                )
                TouchSettingsDetail(
                    content, state, showBack = drilled, onBack = { state.backInRight() },
                    modifier = Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }

            inCategory -> TouchSettingsDetail(
                content, state, showBack = true, onBack = { if (!state.backInRight()) inCategory = false },
                modifier = Modifier.fillMaxSize(),
            )

            else -> TouchSettingsRail(
                content, highlightSelected = false, onBack = navigator::popBackStack,
                onSelect = { id ->
                    state.onRailFocused(id)
                    inCategory = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 左栏: 分组小标题与分类 (同原来设置页的导航列表). */
@Composable
private fun TouchSettingsRail(
    content: TvSettingsContent,
    highlightSelected: Boolean,
    onBack: () -> Unit,
    onSelect: (categoryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        AniTopAppBar(
            title = { AniTopAppBarDefaults.Title(stringResource(Lang.settings)) },
            navigationIcon = { BackNavigationIconButton(onBack) },
            colors = AniThemeDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
        ) {
            items(content.rail, key = { it.id }) { row ->
                val categoryId = TvSettingsRowIds.parseCategory(row.id)
                if (row.kind == TvSettingsRowKind.Header || categoryId == null) {
                    Text(
                        row.title,
                        Modifier.padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 12.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    val tab = SettingsTab.entries.firstOrNull { it.name == categoryId }
                    NavigationDrawerItem(
                        label = { Text(row.title) },
                        selected = highlightSelected && row.id == content.railKey,
                        onClick = { onSelect(categoryId) },
                        icon = tab?.let { { Icon(touchSettingsTabIcon(it), contentDescription = null) } },
                    )
                }
            }
        }
    }
}

/**
 * 右栏: 这一屏的设置项. 点一行 = 电视上按确定 ([TvSettingsState.onRowClicked]), 同时选中它 —— 选中的行起它的焦点任务 (如网盘扫码登录要码)、
 * 画它的二维码与会话; 可挪顺序的行长按进入挪动, 用上下箭头挪.
 */
@Composable
private fun TouchSettingsDetail(
    content: TvSettingsContent,
    state: TvSettingsState,
    showBack: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screen = content.right
    val categoryTitle = content.rail.firstOrNull { it.id == content.railKey }?.title.orEmpty()
    val tab = content.railKey?.let(TvSettingsRowIds::parseCategory)?.let { id -> SettingsTab.entries.firstOrNull { it.name == id } }
    val openLegacy: (() -> Unit)? = tab?.let { { state.overlay.value = TvSettingsOverlay.Legacy(it) } }
    var selectedRow by remember(screen.key) { mutableStateOf<String?>(null) }
    var movingRow by remember(screen.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(screen.key, selectedRow) { state.onRowFocused(selectedRow) }
    DisposableEffect(state) { onDispose { state.onRowFocused(null) } }
    val onPhone = stringResource(Lang.tv_settings_on_phone)

    Column(modifier) {
        AniTopAppBar(
            title = { AniTopAppBarDefaults.Title(screen.title ?: categoryTitle) },
            navigationIcon = { if (showBack) BackNavigationIconButton(onBack) },
            colors = AniThemeDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        // 进了单选 / 排序: 这一项的说明 (各选项共用的那段) 写在最上面
        val screenDescription = screen.rows.firstNotNullOfOrNull { row ->
            content.info[row.id]?.takeIf { row.kind == TvSettingsRowKind.Option && it.title != row.title }?.body
        }?.takeIf { it.isNotEmpty() }
        key(screen.key) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(
                    start = 8.dp, end = 8.dp,
                    bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding() + 24.dp,
                ),
            ) {
                screenDescription?.let { description ->
                    item(key = "description") {
                        Text(
                            description,
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = TOUCH_SETTINGS_CONTENT_MAX_WIDTH),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ListItemDefaults.colors().supportingContentColor,
                        )
                    }
                }
                // 这一类在这台设备上一项都没有 (如没有日志功能的平台): 只给打开原来设置页的入口
                if (screen.rows.isEmpty() && openLegacy != null) {
                    item(key = "legacy") {
                        TextButton(onClick = openLegacy, Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(Lang.tv_settings_legacy_open))
                        }
                    }
                }
                items(screen.rows, key = { it.id }) { row ->
                    val info = content.info[row.id]
                    val group = row.moveGroup
                    TouchSettingsRowView(
                        row = row,
                        info = info,
                        selected = row.id == selectedRow,
                        moving = row.id == movingRow,
                        hideValue = info?.qr?.let { it.console != null && it.legacy } == true && row.value == onPhone,
                        onClick = {
                            movingRow = null
                            selectedRow = row.id
                            state.onRowClicked(row.id)
                        },
                        onLongClick = group?.let { { movingRow = row.id } },
                        onOpenLegacy = openLegacy,
                        onMove = group?.let { g ->
                            { delta: Int ->
                                val order = screen.rows.filter { it.moveGroup == g }.map { it.id }.toMutableList()
                                val from = order.indexOf(row.id)
                                val to = from + delta
                                if (from >= 0 && to in order.indices) {
                                    order.add(to, order.removeAt(from))
                                    state.onRowsReordered(row.id, order)
                                }
                            }
                        },
                        onMoveDone = { movingRow = null },
                    )
                }
            }
        }
    }
}

/** 一行: 标题、说明 (写在下面)、行尾的当前值 / 开关 / 勾, 选中时画二维码与会话气泡, 挪动时出上下箭头. */
@Composable
private fun TouchSettingsRowView(
    row: TvSettingsRow,
    info: TvSettingsInfo?,
    selected: Boolean,
    moving: Boolean,
    hideValue: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onOpenLegacy: (() -> Unit)?,
    onMove: ((Int) -> Unit)?,
    onMoveDone: () -> Unit,
) {
    if (row.kind == TvSettingsRowKind.Header) {
        Text(
            row.title,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        return
    }
    val sorted = TvSettingsRowIds.parseSorted(row.id) != null
    // 选项各自的说明 (标题就是这一档) 写在下面; 共用的那段在一屏的最上面
    val description = info?.body?.takeIf {
        it.isNotEmpty() && !sorted && (row.kind != TvSettingsRowKind.Option || info.title == row.title)
    }
    val showBubbles = selected && info != null && info.bubbles.isNotEmpty()
    val hasSupporting = description != null || info?.qr != null || moving || showBubbles
    ListItem(
        headlineContent = { Text(row.title) },
        modifier = Modifier
            .widthIn(max = TOUCH_SETTINGS_CONTENT_MAX_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        supportingContent = if (!hasSupporting) null else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    description?.let { Text(it) }
                    info?.qr?.let { qr -> TouchSettingsQrBlock(qr, selected, onOpenLegacy) }
                    if (showBubbles) TouchSettingsBubbles(info.bubbles)
                    if (moving && onMove != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onMove(-1) }) { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null) }
                            IconButton(onClick = { onMove(1) }) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null) }
                            TextButton(onClick = onMoveDone) { Text(stringResource(Lang.settings_media_source_done)) }
                        }
                    }
                }
            }
        },
        leadingContent = touchSettingsRowLeading(row, info),
        trailingContent = {
            when {
                row.toggle != null -> Switch(checked = row.toggle == true, onCheckedChange = { onClick() })
                sorted -> Checkbox(checked = row.checked, onCheckedChange = { onClick() })
                row.kind == TvSettingsRowKind.Option -> if (row.checked) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }

                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.value.isNotEmpty() && !hideValue) {
                        Text(
                            row.value,
                            Modifier.widthIn(max = TOUCH_SETTINGS_VALUE_MAX_WIDTH),
                            style = MaterialTheme.typography.labelLarge,
                            color = ListItemDefaults.colors().supportingContentColor,
                            maxLines = 2,
                        )
                    }
                    if (row.chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (moving) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
    )
}

/** 行首: 说明里的图 (头像、标志) 或调色板的色块. */
private fun touchSettingsRowLeading(row: TvSettingsRow, info: TvSettingsInfo?): (@Composable () -> Unit)? {
    val image = info?.image
    val swatch = row.swatch
    return when {
        image != null -> {
            {
                Image(
                    painterResource(image.res),
                    contentDescription = null,
                    Modifier.size(40.dp).clip(if (image.round) CircleShape else RoundedCornerShape(6.dp)),
                )
            }
        }

        swatch != null -> {
            { Spacer(Modifier.size(24.dp).clip(CircleShape).background(Color(swatch))) }
        }

        else -> null
    }
}

/**
 * 一行的二维码: 原来的设置页里也有的项 ([TvSettingsQr.legacy]), 控制台的码换成「打开这一类的设置」(那里能打字); 网页的码不画 (点这一行就在浏览器里打开);
 * 要另一台设备扫的 (网盘登录、传日志、只有控制台才有的项) 在选中这一行后画出来, 码上下写状态与说明.
 */
@Composable
private fun TouchSettingsQrBlock(qr: TvSettingsQr, selected: Boolean, onOpenLegacy: (() -> Unit)?) {
    when {
        qr.console != null && qr.legacy -> if (onOpenLegacy != null) {
            TextButton(onClick = onOpenLegacy, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(Lang.tv_settings_legacy_open))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
        }

        qr.link || !selected -> {}
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (qr.status.isNotEmpty()) Text(qr.status, color = MaterialTheme.colorScheme.primary)
            qr.content?.let { code ->
                QrCodeImage(
                    code,
                    Modifier.size(TOUCH_SETTINGS_QR_SIZE),
                    quietZone = 16.dp,
                    foreground = Color.Black,
                    background = Color.White,
                )
            }
            if (qr.caption.isNotEmpty()) Text(qr.caption)
        }
    }
}

/** 说明里的会话 (如反馈的回复): 自己的靠右. */
@Composable
private fun TouchSettingsBubbles(bubbles: List<TvSettingsBubble>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (bubble in bubbles) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (bubble.mine) Arrangement.End else Arrangement.Start) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (bubble.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.widthIn(max = TOUCH_SETTINGS_BUBBLE_MAX_WIDTH),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(bubble.meta, style = MaterialTheme.typography.labelSmall)
                        if (bubble.text.isNotEmpty()) Text(bubble.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 分类的图标 (同原来的设置页). */
private fun touchSettingsTabIcon(tab: SettingsTab): ImageVector = when (tab) {
    SettingsTab.PROFILE -> Icons.Outlined.AccountCircle
    SettingsTab.APPEARANCE -> Icons.Outlined.SettingsApplications
    SettingsTab.THEME -> Icons.Outlined.Palette
    SettingsTab.UPDATE -> Icons.Outlined.Update
    SettingsTab.PLAYER -> Icons.Outlined.SmartDisplay
    SettingsTab.MEDIA_SOURCE -> Icons.Outlined.Subscriptions
    SettingsTab.MEDIA_SELECTOR -> Icons.Outlined.FilterList
    SettingsTab.PROXY -> Icons.Outlined.VpnKey
    SettingsTab.BT -> Icons.Filled.P2p
    SettingsTab.STORAGE -> Icons.Outlined.Storage
    SettingsTab.SETTINGS_BACKUP -> Icons.Outlined.Settings
    SettingsTab.ABOUT -> Icons.Outlined.Info
    SettingsTab.LOG -> Icons.Outlined.Feedback
    SettingsTab.DEBUG -> Icons.Outlined.Science
}

/** 宽度够两栏的门槛、左栏宽、右栏内容的最大宽 (再宽读着累)、行尾值的最大宽、二维码边长、气泡最大宽. */
private val TOUCH_SETTINGS_TWO_PANE_MIN_WIDTH = 600.dp
private val TOUCH_SETTINGS_RAIL_WIDTH = 300.dp
private val TOUCH_SETTINGS_CONTENT_MAX_WIDTH = 840.dp
private val TOUCH_SETTINGS_VALUE_MAX_WIDTH = 220.dp
private val TOUCH_SETTINGS_QR_SIZE = 200.dp
private val TOUCH_SETTINGS_BUBBLE_MAX_WIDTH = 480.dp
