/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.him188.ani.app.ui.foundation.LocalSketch
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeBubbleStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeButtonGroupStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeButtonStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeGlassTabStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativePanelStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.foundation.widgets.ANI_SCROLL_FADE
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_TITLE_GAP
import me.him188.ani.app.ui.foundation.widgets.aniPanelTitleStyle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.notification_empty
import me.him188.ani.app.ui.lang.notification_title
import me.him188.ani.app.ui.lang.search_tv_remote_unavailable
import me.him188.ani.app.ui.remote.CONNECTED_GREEN_DARK
import me.him188.ani.app.ui.remote.CONNECTED_GREEN_LIGHT
import me.him188.ani.utils.platform.currentTimeMillis
import org.jetbrains.compose.resources.stringResource

/**
 * 通知弹窗的内容, 原生视图画 ([TvNoticeView]; 电视与安卓平板). 内容同 Compose 画法 ([TvNoticeDialogBody]): 「通知」签与 [tabs] 各签的行在这里取好、
 * 换成字 (时间) 交给视图, 焦点与按键全在视图里, 换签、一条的操作、读过、刷新由视图报回来.
 * 刷新那几个字每秒变, 单独在 [TvNoticeNativeRefresh] 里取, 不连带重算整份内容.
 */
@Composable
internal fun TvNoticeNativeBody(
    items: List<TvShownNotice>,
    state: TvNoticeDialogState,
    tabs: List<TvNoticeTab>,
    onAction: (index: Int, item: TvNoticeColumnItem, action: TvNoticeAction) -> Unit,
    onRead: (key: String) -> Unit,
    onSelectTab: (id: String, intoList: Boolean, focusKey: String?) -> Unit,
    refreshUi: @Composable () -> TvNoticeRefreshUi,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = rememberTvNoticeStyle()
    val timeTexts = rememberTvNoticeTimeTexts()
    // 列表上的时间按弹窗打开那一刻算
    val now = remember { currentTimeMillis() }
    val noticeItems = rememberTvNoticeColumnItems(items, state)
    val noticeRows = remember(noticeItems, now, timeTexts) { tvNoticeViewRows(noticeItems, now, timeTexts) }
    // 各签的行都在这里取 (弹窗开着时一直备着, 换过去时已经在了)
    val tabItems = tabs.map { tab -> key(tab.id) { tab.rememberItems() } }
    val viewTabs = buildList {
        add(
            TvNoticeViewTab(
                id = TvNoticeTab.NOTICES,
                label = stringResource(Lang.notification_title),
                fresh = items.any { it.kind.listed && !it.entry.read },
                rows = noticeRows,
                emptyText = stringResource(Lang.notification_empty),
                bodyStartsAtEnd = false,
                buttonsForSingleAction = false,
                reportsRead = true,
            ),
        )
        tabs.forEachIndexed { index, tab ->
            key(tab.id) {
                val fresh by remember(tab) { tab.hasFresh }.collectAsState(false)
                val list = tabItems[index]
                val rows = remember(list, now, timeTexts) { list?.let { tvNoticeViewRows(it, now, timeTexts) } }
                add(
                    TvNoticeViewTab(
                        id = tab.id,
                        label = stringResource(tab.label),
                        fresh = fresh,
                        rows = rows,
                        emptyText = tab.emptyText(),
                        bodyStartsAtEnd = tab.bodyStartsAtEnd,
                        buttonsForSingleAction = tab.buttonsForSingleAction,
                        reportsRead = false,
                    ),
                )
            }
        }
    }
    val icons = rememberTvNoticeIcons(viewTabs)
    val sketch = LocalSketch.current
    val content = TvNoticeViewContent(
        tabs = viewTabs,
        title = stringResource(Lang.notification_title),
        opening = state,
        selected = tabs.firstOrNull { it.id == state.tab }?.id ?: TvNoticeTab.NOTICES,
        focusKey = state.focusKey,
        focusIndex = state.focusIndex,
        focusAction = state.focusAction,
        focusOnTabs = state.focusOnTabs,
        qrUnavailable = stringResource(Lang.search_tv_remote_unavailable),
        icons = icons,
        refreshIcon = icons[Icons.Rounded.Refresh],
        sketch = sketch,
    )

    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnRead by rememberUpdatedState(onRead)
    val currentOnSelectTab by rememberUpdatedState(onSelectTab)
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val currentTabs by rememberUpdatedState(tabs)
    val uriHandler by rememberUpdatedState(LocalUriHandler.current)
    val holder = remember { arrayOfNulls<TvNoticeView>(1) }
    AndroidView(
        factory = { context ->
            noticeViewWarmed = true
            TvNoticeView(context, style).also { view ->
                holder[0] = view
                view.listener = object : TvNoticeViewListener {
                    override fun onAction(tab: String, index: Int, item: TvNoticeColumnItem, action: TvNoticeAction) =
                        currentOnAction(index, item, action)

                    override fun onSelectTab(id: String, intoList: Boolean, focusKey: String?) = currentOnSelectTab(id, intoList, focusKey)
                    override fun onRead(key: String) = currentOnRead(key)
                    override fun onShown(tab: String, key: String) {
                        currentTabs.firstOrNull { it.id == tab }?.onShown(key)
                    }

                    override fun onTabShown(tab: String) {
                        currentTabs.firstOrNull { it.id == tab }?.onTabShown()
                    }

                    override fun onRefresh() = currentOnRefresh()
                    override fun onOpenUrl(url: String) {
                        runCatching { uriHandler.openUri(url) }
                    }
                }
            }
        },
        // 单独一层: 原生树每次失效只重录这一层
        modifier = modifier.fillMaxWidth().graphicsLayer(),
        update = { view ->
            holder[0] = view
            view.applyStyle(style)
            view.submit(content)
        },
    )
    TvNoticeNativeRefresh(holder, refreshUi)
}

/** 顶栏右边的刷新 (每秒变): 自己一层重组, 只把刷新那几个字交给视图. */
@Composable
private fun TvNoticeNativeRefresh(holder: Array<TvNoticeView?>, refreshUi: @Composable () -> TvNoticeRefreshUi) {
    val ui = refreshUi()
    SideEffect { holder[0]?.setRefresh(ui) }
}

/** 一个签的行配上列表上写的时间 (按 [now] 算). */
internal fun tvNoticeViewRows(items: List<TvNoticeColumnItem>, now: Long, texts: TvNoticeTimeTexts): List<TvNoticeViewRow> =
    items.map { item -> TvNoticeViewRow(item, item.atMillis?.let { formatNoticeTime(it, now, texts) }?.takeIf { it.isNotEmpty() }) }

/** 各签用到的图标 (行的类别、卡片) 与刷新的图标, 画成白色位图 (视图里按颜色着色). */
@Composable
private fun rememberTvNoticeIcons(tabs: List<TvNoticeViewTab>): Map<ImageVector, Bitmap> {
    val vectors = LinkedHashSet<ImageVector>()
    vectors += Icons.Rounded.Refresh
    for (tab in tabs) {
        for (row in tab.rows.orEmpty()) {
            vectors += row.item.icon
            for (block in row.item.blocks) if (block is TvNoticeCard) vectors += block.icon
        }
    }
    val px = with(LocalDensity.current) { TV_NOTICE_ICON_SIZE.roundToPx() }
    val icons = HashMap<ImageVector, Bitmap>()
    for (vector in vectors) {
        // 画过的留着 (进程里就那几个图标): 每次打开弹窗不重画
        icons[vector] = noticeIcons.getOrElse(vector to px) {
            key(vector) { rememberTvNativeIcon(vector, TV_NOTICE_ICON_SIZE) }.also { noticeIcons[vector to px] = it }
        }
    }
    return icons
}

/** 画好的图标 (矢量图与边长 px → 白色位图). 只在主线程上用. */
private val noticeIcons = HashMap<Pair<ImageVector, Int>, Bitmap>()

/** 进程里建过通知弹窗的原生视图了 (类加载、第一次排版的开销付过). 只在主线程上读写. */
private var noticeViewWarmed = false

/**
 * 进程里第一次打开通知弹窗多花约 200ms (类加载、第一次排版; Shield 上冷的一帧 350ms、之后 150ms), 都在主线程上. 进应用一会儿、
 * [canPrewarmNow] 为 true (在首页、上面没有别的层) 时先建一个样子差不多的视图按弹窗的宽排一遍再丢掉, 第一次打开就接近之后几次.
 * 建、交内容、排版分在三帧里做, 一帧不长. 打开过通知弹窗的不再做.
 */
@Composable
internal fun PrewarmTvNoticeView(canPrewarmNow: () -> Boolean) {
    val context = LocalContext.current
    val style = rememberTvNoticeStyle()
    val density = LocalDensity.current
    val currentCanPrewarm by rememberUpdatedState(canPrewarmNow)
    LaunchedEffect(Unit) {
        if (noticeViewWarmed) return@LaunchedEffect
        delay(NOTICE_PREWARM_DELAY)
        while (!currentCanPrewarm()) delay(1.seconds)
        if (noticeViewWarmed) return@LaunchedEffect
        noticeViewWarmed = true
        val view = TvNoticeView(context, style)
        withFrameNanos { }
        view.submit(prewarmContent())
        withFrameNanos { }
        val width = with(density) { TV_NOTICE_PREWARM_WIDTH.roundToPx() }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}

/** 预建用的内容: 两个签, 每种正文块都有一块 (码要编一次). */
private fun prewarmContent(): TvNoticeViewContent {
    val item = TvNoticeColumnItem(
        key = "prewarm",
        icon = Icons.Rounded.Refresh,
        iconLabel = "",
        title = "通知 Notice",
        atMillis = null,
        summary = "摘要 Summary",
        chips = listOf(TvNoticeChip("签"), TvNoticeChip("签", accent = true)),
        meta = "#1",
        actions = listOf(TvNoticeAction("一", TvNoticeDestination.Run {}), TvNoticeAction("二", TvNoticeDestination.Run {})),
        blocks = listOf(
            TvNoticeParagraph("正文 Body", title = "标题", bullet = true),
            TvNoticeMessage("谁 · 时间", "回复 Reply", mine = true),
            TvNoticeCard(Icons.Rounded.Refresh, "卡片 Card", "状态", progress = 0.5f),
            TvNoticeQr("http://192.168.0.1:8080/#settings/feedback", "说明", title = "扫码", status = "状态"),
        ),
    )
    val rows = listOf(TvNoticeViewRow(item, "刚刚"))
    fun tab(id: String) = TvNoticeViewTab(id, id, fresh = true, rows = rows, emptyText = "", bodyStartsAtEnd = false, buttonsForSingleAction = false, reportsRead = false)
    return TvNoticeViewContent(
        tabs = listOf(tab(TvNoticeTab.NOTICES), tab("prewarm")),
        title = "",
        opening = Any(),
        selected = TvNoticeTab.NOTICES,
        focusKey = null,
        focusIndex = 0,
        focusAction = null,
        focusOnTabs = false,
        qrUnavailable = "",
        icons = emptyMap(),
        refreshIcon = null,
    )
}

/** 进应用后等这么久再预建: 避开进应用时那一阵 (首页的图、进应用的弹窗). */
private val NOTICE_PREWARM_DELAY = 10.seconds

/** 预建时按这个宽排 (同弹窗最宽). */
private val TV_NOTICE_PREWARM_WIDTH = 832.dp

/**
 * 通知弹窗原生视图的尺寸、字与配色 (见 [TvNoticeStyle]): 跟应用主题、界面缩放与触屏走, 数值同 Compose 版; 面板共用的配色取 [rememberTvNativePanelStyle].
 * 要在通知弹窗的面板里取 (面板里的淡底).
 */
@Composable
internal fun rememberTvNoticeStyle(): TvNoticeStyle {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val panel = rememberTvNativePanelStyle()
    val panelTitle = aniPanelTitleStyle
    // 签那一排同追番页的玻璃标签栏 (原生公共件), 红点用 error 色
    val tabs = rememberTvNativeGlassTabStyle(colors.error)
    return remember(density, typography, colors, panel, panelTitle, tabs) {
        with(density) {
            val onSurface = colors.onSurface
            val variant = colors.onSurfaceVariant
            val dark = colors.surface.luminance() < 0.5f
            TvNoticeStyle(
                panel = panel,
                tabs = tabs,
                headerGapTabsPx = TV_NOTICE_TABS_GAP.roundToPx(),
                headerGapTitlePx = CENTERED_PANEL_TITLE_GAP.roundToPx(),
                refreshIconPx = TV_NOTICE_REFRESH_ICON_SIZE.roundToPx(),
                refreshIconGapPx = 6.dp.roundToPx(),
                refreshPaddingPx = 4.dp.roundToPx(),
                refreshStatusGapPx = TV_NOTICE_REFRESH_STATUS_GAP.roundToPx(),
                refreshStatusBottomPx = 8.dp.roundToPx(),
                refreshCoolingAlpha = TV_NOTICE_REFRESH_COOLING_ALPHA,
                refreshSpinMillis = TV_NOTICE_REFRESH_SPIN_MILLIS.toLong(),
                bodyHeightPx = TV_NOTICE_BODY_HEIGHT.roundToPx(),
                listWidthPx = TV_NOTICE_LIST_WIDTH.roundToPx(),
                listMaxFraction = TV_NOTICE_LIST_MAX_FRACTION,
                columnGapPx = TV_NOTICE_COLUMN_GAP.roundToPx(),
                dividerWidthPx = TV_NOTICE_DIVIDER_WIDTH.roundToPx().coerceAtLeast(1),
                fadePx = ANI_SCROLL_FADE.roundToPx(),
                rowGapPx = 8.dp.roundToPx(),
                rowPaddingHPx = 14.dp.roundToPx(),
                rowPaddingVPx = 10.dp.roundToPx(),
                rowCornerPx = 12.dp.toPx(),
                rowIconPx = TV_NOTICE_ICON_SIZE.roundToPx(),
                rowIconGapPx = 12.dp.roundToPx(),
                rowCoverWidthPx = TV_NOTICE_COVER_WIDTH.roundToPx(),
                rowCoverHeightPx = TV_NOTICE_COVER_HEIGHT.roundToPx(),
                rowCoverCornerPx = TV_NOTICE_COVER_CORNER.toPx(),
                rowCoverStartPx = TV_NOTICE_COVER_START.roundToPx(),
                rowCoverGapPx = TV_NOTICE_COVER_GAP.roundToPx(),
                rowLineGapPx = 2.dp.roundToPx(),
                rowTimeGapPx = 8.dp.roundToPx(),
                rowSecondaryAlpha = TV_NOTICE_TIME_ALPHA,
                dotPx = TV_NOTICE_DOT_SIZE.roundToPx(),
                dotOffsetPx = 3.dp.roundToPx(),
                sectionTopPx = 8.dp.roundToPx(),
                sectionStartPx = 4.dp.roundToPx(),
                emptyPaddingVPx = 16.dp.roundToPx(),
                chipPaddingHPx = 9.dp.roundToPx(),
                chipPaddingVPx = 2.dp.roundToPx(),
                chipCornerPx = 7.dp.toPx(),
                chipGapPx = 8.dp.roundToPx(),
                metaStartPx = 2.dp.roundToPx(),
                paneTitleTopPx = 8.dp.roundToPx(),
                subjectTopPx = 2.dp.roundToPx(),
                bodyTopPx = 12.dp.roundToPx(),
                blockGapPx = 10.dp.roundToPx(),
                actionsTopPx = 12.dp.roundToPx(),
                buttons = TvNativeButtonGroupStyle(
                    button = TvNativeButtonStyle(
                        label = typography.labelLarge.toTvNativeTextStyle(density, onSurface),
                        heightPx = TV_NOTICE_ACTION_HEIGHT.roundToPx(),
                        cornerPx = 12.dp.toPx(),
                        paddingCenterPx = 24.dp.roundToPx(),
                        paddingStartPx = 16.dp.roundToPx(),
                        paddingVPx = 10.dp.roundToPx(),
                        bg = panel.itemBg,
                        fg = panel.onSurface,
                        focusBg = panel.focusBg,
                        focusFg = panel.focusFg,
                        pressedOverlay = panel.pressedOverlay,
                        touch = panel.touch,
                    ),
                    gapPx = 12.dp.roundToPx(),
                    fadePx = ANI_SCROLL_FADE.roundToPx(),
                ),
                pageFraction = TV_NOTICE_PAGE_FRACTION,
                touchNameLines = TV_NOTICE_TOUCH_NAME_LINES,
                bulletPx = 6.dp.roundToPx(),
                bulletTopPx = 9.dp.roundToPx(),
                bulletGapPx = 10.dp.roundToPx(),
                paragraphTitleGapPx = 2.dp.roundToPx(),
                bubble = TvNativeBubbleStyle(
                    meta = typography.labelMedium.toTvNativeTextStyle(density, variant),
                    text = typography.bodyMedium.toTvNativeTextStyle(density, onSurface),
                    maxWidthFraction = TV_NOTICE_BUBBLE_MAX_FRACTION,
                    paddingHPx = 12.dp.roundToPx(),
                    paddingVPx = 8.dp.roundToPx(),
                    lineGapPx = 2.dp.roundToPx(),
                    cornerPx = 12.dp.toPx(),
                    mineBg = colors.primary.copy(alpha = TV_NOTICE_MINE_BUBBLE_ALPHA).toArgb(),
                    otherBg = onSurface.copy(alpha = TV_NOTICE_CHIP_ALPHA).toArgb(),
                    dotPx = TV_NOTICE_DOT_SIZE.roundToPx(),
                    dotGapPx = 6.dp.roundToPx(),
                    dotColor = panel.error,
                ),
                cardPaddingHPx = 14.dp.roundToPx(),
                cardPaddingVPx = 10.dp.roundToPx(),
                cardCornerPx = 12.dp.toPx(),
                cardIconPx = 22.dp.roundToPx(),
                cardIconGapPx = 12.dp.roundToPx(),
                cardGapPx = 6.dp.roundToPx(),
                progressHeightPx = 4.dp.roundToPx(),
                qrSizePx = TV_NOTICE_QR_SIZE.roundToPx(),
                qrQuietPx = TV_NOTICE_QR_QUIET_ZONE.roundToPx(),
                linkQrSizePx = TV_NOTICE_LINK_QR_SIZE.roundToPx(),
                linkQrQuietPx = TV_NOTICE_LINK_QR_QUIET_ZONE.roundToPx(),
                qrCornerPx = 12.dp.toPx(),
                qrGapPx = 20.dp.roundToPx(),
                qrTopPx = 8.dp.roundToPx(),
                qrCaptionGapPx = 6.dp.roundToPx(),
                qrTitleGapPx = 4.dp.roundToPx(),
                qrStatusGapPx = 10.dp.roundToPx(),
                qrUnavailablePaddingPx = 16.dp.roundToPx(),
                statusDotPx = 8.dp.roundToPx(),
                statusDotGapPx = 6.dp.roundToPx(),
                pageTitle = panelTitle.toTvNativeTextStyle(density, onSurface),
                refreshStatus = typography.labelMedium.toTvNativeTextStyle(density, variant),
                refreshLabel = typography.titleMedium.toTvNativeTextStyle(density, variant),
                section = typography.labelLarge.toTvNativeTextStyle(density, variant),
                rowTitle = typography.bodyLarge.copy(fontWeight = FontWeight.Medium).toTvNativeTextStyle(density, onSurface),
                rowTime = typography.labelMedium.toTvNativeTextStyle(density, onSurface),
                rowSummary = typography.bodyMedium.toTvNativeTextStyle(density, onSurface),
                empty = typography.bodyLarge.toTvNativeTextStyle(density, variant),
                chip = typography.labelMedium.toTvNativeTextStyle(density, onSurface),
                meta = typography.labelMedium.toTvNativeTextStyle(density, variant),
                paneTitle = typography.titleLarge.copy(fontWeight = FontWeight.Bold).toTvNativeTextStyle(density, onSurface),
                subject = typography.bodyMedium.toTvNativeTextStyle(density, variant),
                paragraph = typography.bodyLarge.toTvNativeTextStyle(density, onSurface),
                paragraphSmall = typography.bodyMedium.toTvNativeTextStyle(density, variant),
                paragraphTitle = typography.bodyLarge.copy(fontWeight = FontWeight.Medium).toTvNativeTextStyle(density, onSurface),
                cardTitle = typography.titleSmall.toTvNativeTextStyle(density, onSurface),
                cardStatus = typography.labelMedium.toTvNativeTextStyle(density, variant),
                cardPercent = typography.titleSmall.toTvNativeTextStyle(density, colors.primary),
                qrTitle = typography.titleMedium.toTvNativeTextStyle(density, onSurface),
                qrCaption = typography.bodyMedium.toTvNativeTextStyle(density, onSurface),
                qrCaptionSmall = typography.labelMedium.toTvNativeTextStyle(density, variant),
                qrUnavailable = typography.bodySmall.toTvNativeTextStyle(density, variant),
                status = typography.labelLarge.toTvNativeTextStyle(density, variant),
                ok = (if (dark) CONNECTED_GREEN_DARK else CONNECTED_GREEN_LIGHT).toArgb(),
                chipBg = onSurface.copy(alpha = TV_NOTICE_CHIP_ALPHA).toArgb(),
                accentChipBg = colors.primary.copy(alpha = TV_NOTICE_ACCENT_CHIP_ALPHA).toArgb(),
                dividerColor = onSurface.copy(alpha = TV_NOTICE_DIVIDER_ALPHA).toArgb(),
                cardBg = onSurface.copy(alpha = TV_NOTICE_CARD_ALPHA).toArgb(),
            )
        }
    }
}
