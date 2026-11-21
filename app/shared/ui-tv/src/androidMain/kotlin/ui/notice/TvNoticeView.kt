/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeIsInside
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 通知弹窗 (原生, [TvNoticeView]) 的尺寸 (px)、字与配色 (ARGB), 由 rememberTvNoticeStyle 按主题、界面缩放与触屏算好. 取值同 Compose 版
 * (TvNoticeDialog.kt / TvNoticeTab.kt), 两边画出来一个样子.
 */
@Immutable
internal data class TvNoticeStyle(
    val touch: Boolean,
    // 顶栏
    val tabGapPx: Int,
    val tabPaddingPx: Int,
    val tabUnderlineWidthPx: Int,
    val tabUnderlineHeightPx: Int,
    val tabUnderlineGapPx: Int,
    val tabUnderlineCornerPx: Float,
    val tabDotPx: Int,
    val tabDotOffsetPx: Int,
    val headerGapTabsPx: Int,
    val headerGapTitlePx: Int,
    val refreshIconPx: Int,
    val refreshIconGapPx: Int,
    val refreshPaddingPx: Int,
    val refreshStatusGapPx: Int,
    val refreshStatusBottomPx: Int,
    val refreshCoolingAlpha: Float,
    val refreshSpinMillis: Long,
    // 两栏
    val bodyHeightPx: Int,
    val listWidthPx: Int,
    val listMaxFraction: Float,
    val columnGapPx: Int,
    val dividerWidthPx: Int,
    val fadePx: Int,
    val rowGapPx: Int,
    val rowPaddingHPx: Int,
    val rowPaddingVPx: Int,
    val rowCornerPx: Float,
    val rowIconPx: Int,
    val rowIconGapPx: Int,
    val rowLineGapPx: Int,
    val rowTimeGapPx: Int,
    val rowSecondaryAlpha: Float,
    val dotPx: Int,
    val dotOffsetPx: Int,
    val sectionTopPx: Int,
    val sectionStartPx: Int,
    val emptyPaddingVPx: Int,
    // 右栏
    val chipPaddingHPx: Int,
    val chipPaddingVPx: Int,
    val chipCornerPx: Float,
    val chipGapPx: Int,
    val metaStartPx: Int,
    val paneTitleTopPx: Int,
    val subjectTopPx: Int,
    val bodyTopPx: Int,
    val blockGapPx: Int,
    val actionsTopPx: Int,
    val rowActions: Int,
    val actionHeightPx: Int,
    val actionCornerPx: Float,
    val actionPaddingCenterPx: Int,
    val actionPaddingStartPx: Int,
    val actionPaddingVPx: Int,
    val actionRowGapPx: Int,
    val actionColumnGapPx: Int,
    val actionColumnWidthPx: Int,
    val pageFraction: Float,
    // 正文块
    val touchNameLines: Int,
    val bulletPx: Int,
    val bulletTopPx: Int,
    val bulletGapPx: Int,
    val paragraphTitleGapPx: Int,
    val bubbleMaxFraction: Float,
    val bubblePaddingHPx: Int,
    val bubblePaddingVPx: Int,
    val bubbleCornerPx: Float,
    val bubbleLineGapPx: Int,
    val bubbleDotGapPx: Int,
    val cardPaddingHPx: Int,
    val cardPaddingVPx: Int,
    val cardCornerPx: Float,
    val cardIconPx: Int,
    val cardIconGapPx: Int,
    val cardGapPx: Int,
    val progressHeightPx: Int,
    val qrSizePx: Int,
    val qrQuietPx: Int,
    val linkQrSizePx: Int,
    val linkQrQuietPx: Int,
    val qrCornerPx: Float,
    val qrGapPx: Int,
    val qrTopPx: Int,
    val qrCaptionGapPx: Int,
    val qrTitleGapPx: Int,
    val qrStatusGapPx: Int,
    val qrUnavailablePaddingPx: Int,
    val statusDotPx: Int,
    val statusDotGapPx: Int,
    // 字
    val pageTitle: TvNativeTextStyle,
    val tab: TvNativeTextStyle,
    val tabSelected: TvNativeTextStyle,
    val refreshStatus: TvNativeTextStyle,
    val refreshLabel: TvNativeTextStyle,
    val section: TvNativeTextStyle,
    val rowTitle: TvNativeTextStyle,
    val rowTime: TvNativeTextStyle,
    val rowSummary: TvNativeTextStyle,
    val empty: TvNativeTextStyle,
    val chip: TvNativeTextStyle,
    val meta: TvNativeTextStyle,
    val paneTitle: TvNativeTextStyle,
    val subject: TvNativeTextStyle,
    val paragraph: TvNativeTextStyle,
    val paragraphSmall: TvNativeTextStyle,
    val paragraphTitle: TvNativeTextStyle,
    val bubbleMeta: TvNativeTextStyle,
    val bubbleText: TvNativeTextStyle,
    val cardTitle: TvNativeTextStyle,
    val cardStatus: TvNativeTextStyle,
    val cardPercent: TvNativeTextStyle,
    val qrTitle: TvNativeTextStyle,
    val qrCaption: TvNativeTextStyle,
    val qrCaptionSmall: TvNativeTextStyle,
    val qrUnavailable: TvNativeTextStyle,
    val status: TvNativeTextStyle,
    val action: TvNativeTextStyle,
    // 色
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val error: Int,
    val outline: Int,
    val outlineVariant: Int,
    val ok: Int,
    val focusBg: Int,
    val focusFg: Int,
    val itemBg: Int,
    val selectedBg: Int,
    val selectedFg: Int,
    val pressedOverlay: Int,
    val chipBg: Int,
    val accentChipBg: Int,
    val dividerColor: Int,
    val mineBubbleBg: Int,
    val otherBubbleBg: Int,
    val cardBg: Int,
    val qrBg: Int,
    val qrFg: Int,
    val progressTrack: Int,
) {
    /** 状态行与非平常色调的一段话的颜色 (同 Compose 版的 statusColor). */
    fun toneColor(tone: TvNoticeTone): Int = when (tone) {
        TvNoticeTone.Normal, TvNoticeTone.Secondary -> onSurfaceVariant
        TvNoticeTone.Emphasis -> primary
        TvNoticeTone.Ok -> ok
        TvNoticeTone.Error -> error
    }
}

/**
 * 交给视图的整份内容: 各个签 ([tabs], 第一个是「通知」) 与这一次打开 ([opening] 换了对象就是重新打开: 开在 [selected] 签上, 焦点落在
 * [focusKey] 那一行 (没有就第 [focusIndex] 行), 给了 [focusAction] 落在那一行的那颗按钮上, [focusOnTabs] 时落在签上).
 */
internal class TvNoticeViewContent(
    val tabs: List<TvNoticeViewTab>,
    val title: String,
    val opening: Any,
    val selected: String,
    val focusKey: String?,
    val focusIndex: Int,
    val focusAction: Int?,
    val focusOnTabs: Boolean,
    /** 二维码还没有 (没连上局域网) 时码的位置写的那句. */
    val qrUnavailable: String,
    val icons: Map<ImageVector, Bitmap>,
    val refreshIcon: Bitmap?,
)

/**
 * 一个签: 签上的字与红点、列的行 ([rows], 还在取为 null)、一行都没有时写的字, 与这个签的两个开关 (见 [TvNoticeTab]).
 * [reportsSeen]: 一行露出一半以上时报看过 (「通知」签).
 */
internal class TvNoticeViewTab(
    val id: String,
    val label: String,
    val fresh: Boolean,
    val rows: List<TvNoticeViewRow>?,
    val emptyText: String,
    val bodyStartsAtEnd: Boolean,
    val buttonsForSingleAction: Boolean,
    val reportsSeen: Boolean,
)

/** 列的一行与它在列表上写的时间 (已经换成字; 不写时间为 null). */
internal class TvNoticeViewRow(val item: TvNoticeColumnItem, val time: String?)

/** 视图报给宿主的事件, 都在主线程. */
internal interface TvNoticeViewListener {
    /** 签 [tab] 里第 [index] 行的一个操作 (行上按确定键、右栏的按钮、触屏点行). */
    fun onAction(tab: String, index: Int, item: TvNoticeColumnItem, action: TvNoticeAction)

    /** 换到了签 [id] (视图已经换过去了, 宿主记下来): [intoList] = 从列表左右换的, 焦点落在 [focusKey] 那一行. */
    fun onSelectTab(id: String, intoList: Boolean, focusKey: String?)

    /** 「通知」签里 [key] 那一行露出一半以上了. */
    fun onSeen(key: String)

    /** 签 [tab] 的右栏换成了 [key] 那一行. */
    fun onShown(tab: String, key: String)

    /** 签 [tab] 成了选中的那个 (换签、打开时就在它). */
    fun onTabShown(tab: String)

    fun onRefresh()

    /** 触屏上点了正文里的外部链接. */
    fun onOpenUrl(url: String)
}

/**
 * 通知弹窗的内容 (原生 View, 电视与安卓平板): 顶栏左边是签 (只有「通知」一个时写标题), 右边是刷新; 下面左边列表、中间一条竖线、右栏说明与操作.
 * 规则同 Compose 版 (见 docs/contributing/code/notices.md 5.4): 列表里上下走、左右键换签 (右边没有签可换时右键同确定键进右栏)、第一行按上键到刷新;
 * 签那一排只显示选中的是哪个, 焦点不落上去 (触屏上点签换签); 刷新上下键回到列表那一行, 左右键不动 (这个签一行都没有时左右键换签);
 * 右栏横排按钮上下键翻正文, 竖排上下键换按钮, 最左一颗 (竖排时每一颗) 按左键或按返回键回到那一行. 没有操作的行正文放不下时,
 * 按确定键 (右边没有签可换时右键也行) 焦点进正文, 上下键翻, 左键或返回键回到那一行.
 *
 * **方向键全部在这里判**: 视图挂在 Compose 的 AndroidView 里, 没被消费的方向键会交给 Compose 按屏幕位置找焦点. 确定键只认在这里按下的那一次
 * (打开弹窗的那次确定键的抬起不算).
 *
 * 各签的列表各是一个 leanback 竖列表, 显示过的同时排着 (不显示的藏起来, 照样排版): 换签只是换哪一个显示, 不重新排; 没显示过的第一次换过去时
 * 在同一次排版里排出来、给焦点, 第一帧就是新签的行.
 * 持焦的行在内容变化 (差异更新) 时不回收、焦点不丢; 右栏说的那一行没了时焦点先停在视图自己身上, 新的行排好再落到原来位置上的那一行
 * (不停的话持焦的行一被删, 系统会把焦点塞给随便哪个视图).
 */
@SuppressLint("ViewConstructor")
internal class TvNoticeView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context) {
    var listener: TvNoticeViewListener? = null

    private val titleView = tvNoticeText(context, style.pageTitle)
    private val tabViews = ArrayList<TvNoticeTabView>()
    private val refreshStatusView = tvNoticeText(context, style.refreshStatus)
    private val refreshView = TvNoticeRefreshView(context, style)
    private val emptyView = tvNoticeText(context, style.empty, maxLines = 3)
    private val lists = LinkedHashMap<String, TabList>()
    private val pane = TvNoticePaneView(context, style)
    private val dividerPaint = Paint()

    private var content: TvNoticeViewContent? = null
    private var refreshUi: TvNoticeRefreshUi? = null
    private var env = TvNoticeBlockEnv(style, emptyMap(), "") { listener?.onOpenUrl(it) }

    /** 签那一排选中的; [shownTab] 是两栏此刻画的 (新签的行还没取到时还是旧签的). */
    private var selectedTab = TvNoticeTab.NOTICES
    private var shownTab = TvNoticeTab.NOTICES

    /** 右栏此刻说的那一行与它所在的签. */
    private var paneTab: String? = null
    private var paneRow: TvNoticeViewRow? = null

    /** 各签上次右栏说的那一行与它的位置: 换回这个签时落回它, 它没了落在原来的位置上. */
    private val lastRow = HashMap<String, String>()
    private val lastIndex = HashMap<String, Int>()

    /** 焦点在右栏的按钮上 (列表里那一行画成「右栏说的是它」). */
    private var inPane = false

    private var opening: Any? = null

    /** 自己换签后交给宿主、还没回来的那几次 (回来的那份内容不再挪焦点). */
    private val ownSelects = ArrayList<OwnSelect>()

    /** 要落焦点的那一行 (行还没取到、列表还没排好、差异更新还没提交时等着). */
    private var pending: Pending? = null

    /** 等列表排好再给焦点的那一行. */
    private var waitingGrid: TabList? = null
    private var waitingKey: String? = null

    /** 这一次打开里报过看过的. */
    private val seen = HashSet<String>()

    private var pressed: View? = null
    private var backInPane = false
    private var pageTarget = 0
    private var pageUntil = 0L

    /** 窗口拿到过焦点; 之后又没了 = 叠在上面的小窗拿走了焦点 ([covered]). */
    private var hadWindowFocus = false

    /**
     * 窗口被叠在上面的小窗拿走了焦点 (通知弹窗里一条的操作开的小窗): 焦点还在这里 (小窗关掉接着用), 但聚焦的样子不画 —— 不然小窗里的按钮与
     * 下面这颗都画着焦点, 看着两颗都聚焦. 聚焦的那一行画成「右栏说的是它」的浅底. 刚打开、窗口还没拿到焦点的那一两帧不算.
     */
    private var covered = false
        set(value) {
            if (field == value) return
            field = value
            refreshView.covered = value
            pane.actions.covered = value
            for (list in lists.values) list.adapter.covered = value
        }

    private var headerRowHeight = 0
    private var columnsTop = 0
    private var bodyHeight = 0
    private var listWidth = 0

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(titleView)
        addView(refreshStatusView)
        addView(refreshView)
        addView(emptyView)
        addView(pane)
        refreshView.setOnClickListener { listener?.onRefresh() }
        pane.actions.onClick = { actionClicked(it) }
        pane.actions.onFocus = { _, _ -> onPaneFocusChanged() }
        pane.body.setOnFocusChangeListener { _, _ -> onPaneFocusChanged() }
    }

    private inner class TabList(val id: String) {
        val grid = TvNoticeGrid(context)
        val adapter = TvNoticeRowAdapter(style)
        var tab: TvNoticeViewTab? = null
        var diffing = false

        /** 显示过 (排过版): 之后藏起来时照样排版 (INVISIBLE), 换回来不用重排; 没显示过的不排 (GONE), 打开弹窗时不白建别的签的行. */
        var everShown = false
    }

    private class Pending(val tab: String, val key: String?, val index: Int, val action: Int?) {
        var attempts = 0
    }

    private data class OwnSelect(val id: String, val intoList: Boolean, val focusKey: String?)

    fun applyStyle(style: TvNoticeStyle) {
        if (this.style == style) return
        this.style = style
        env = TvNoticeBlockEnv(style, env.icons, env.qrUnavailable) { listener?.onOpenUrl(it) }
        style.pageTitle.applyTo(titleView)
        style.refreshStatus.applyTo(refreshStatusView)
        style.empty.applyTo(emptyView)
        refreshUi?.let { refreshView.bind(it, content?.refreshIcon, style) }
        for (list in lists.values) {
            list.adapter.style = style
            list.grid.verticalSpacing = style.rowGapPx
            list.grid.setFadingEdgeLength(style.fadePx)
            list.adapter.notifyItemRangeChanged(0, list.adapter.itemCount)
        }
        content?.let { syncTabViews(it) }
        val row = paneRow
        val tab = paneTab
        paneRow = null
        if (tab != null) bindPane(tab, row)
        requestLayout()
    }

    /** 顶栏右边的刷新 (每秒变): 只换刷新那几个字与按钮. */
    fun setRefresh(ui: TvNoticeRefreshUi) {
        refreshUi = ui
        val status = ui.status
        refreshStatusView.visibility = if (status.isNullOrEmpty()) GONE else VISIBLE
        refreshStatusView.setTextIfChanged(status.orEmpty())
        refreshStatusView.setTextColor(if (ui.statusHighlight) style.primary else style.onSurfaceVariant)
        refreshView.bind(ui, content?.refreshIcon, style)
    }

    /** 新内容. 见类注释. */
    fun submit(c: TvNoticeViewContent) {
        val first = content == null
        content = c
        env = TvNoticeBlockEnv(style, c.icons, c.qrUnavailable) { listener?.onOpenUrl(it) }
        titleView.setTextIfChanged(c.title)
        refreshUi?.let { refreshView.bind(it, c.refreshIcon, style) }
        if (c.opening !== opening) {
            opening = c.opening
            if (!consumeOwnSelect(c)) applyOpening(c, first)
        }
        syncTabViews(c)
        for (tab in c.tabs) updateTab(tab, c)
        updateEmpty()
        resolvePending()
    }

    // ---- 内容 ----

    private fun consumeOwnSelect(c: TvNoticeViewContent): Boolean {
        if (c.focusAction != null || c.focusIndex != 0) return false
        val index = ownSelects.indexOfFirst { it.id == c.selected && it.intoList == !c.focusOnTabs && it.focusKey == c.focusKey }
        if (index < 0) return false
        repeat(index + 1) { ownSelects.removeAt(0) }
        return true
    }

    private fun applyOpening(c: TvNoticeViewContent, first: Boolean) {
        seen.clear()
        pending = null
        selectedTab = c.selected
        listener?.onTabShown(c.selected)
        val rows = lists[c.selected]?.tab?.rows ?: c.tabs.firstOrNull { it.id == c.selected }?.rows
        // 一打开就画选中的签 (行还没取到时两栏空着); 弹窗开着时换签, 新签的行取到了再换两栏
        if (first || rows != null) showColumns(c.selected, bind = c.focusOnTabs || rows.isNullOrEmpty())
        // 在签那一排换的 (触屏上点签): 焦点不挪
        if (c.focusOnTabs) return
        if (hasFocus()) park()
        pending = Pending(c.selected, c.focusKey, c.focusIndex, c.focusAction)
    }

    private fun syncTabViews(c: TvNoticeViewContent) {
        val show = c.tabs.size > 1
        if (tabViews.map { it.tabId } != c.tabs.map { it.id }) {
            tabViews.forEach { removeView(it) }
            tabViews.clear()
            for (tab in c.tabs) {
                val view = TvNoticeTabView(context, style)
                view.setOnClickListener { selectTab(view.tabId, fromList = false) }
                tabViews += view
                addView(view)
            }
        }
        c.tabs.forEachIndexed { i, tab ->
            val view = tabViews[i]
            view.bind(tab.id, tab.label, tab.fresh, tab.id == selectedTab, style)
            view.visibility = if (show) VISIBLE else GONE
        }
        titleView.visibility = if (show) GONE else VISIBLE
    }

    private fun updateTab(tab: TvNoticeViewTab, c: TvNoticeViewContent) {
        val list = lists[tab.id] ?: createList(tab.id)
        val old = list.tab
        list.tab = tab
        list.adapter.icons = c.icons
        val rows = tab.rows ?: return
        if (rows !== old?.rows) submitRows(list, rows)
    }

    private fun createList(id: String): TabList {
        val list = TabList(id)
        list.adapter.onRowFocused = { entry -> onRowFocused(id, entry) }
        list.adapter.covered = covered
        list.adapter.onRowClicked = { entry -> rowClicked(id, currentRow(id, entry.id) ?: entry, fromKey = false) }
        list.grid.adapter = list.adapter
        list.grid.verticalSpacing = style.rowGapPx
        list.grid.setFadingEdgeLength(style.fadePx)
        list.grid.addOnLayoutCompletedListener { onGridLaidOut(list) }
        list.grid.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = reportSeen(list)
            },
        )
        list.everShown = id == shownTab
        list.grid.visibility = if (id == shownTab) VISIBLE else GONE
        list.grid.descendantFocusability = if (id == shownTab) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
        lists[id] = list
        addView(list.grid)
        return list
    }

    private fun submitRows(list: TabList, rows: List<TvNoticeViewRow>) {
        val activeKey = if (paneTab == list.id) paneRow?.item?.key else null
        val activeRow = activeKey?.let { key -> rows.firstOrNull { it.item.key == key } }
        if (activeKey != null && activeRow == null) {
            // 右栏说的那一行没了 (当场做完的操作、别处处理的): 焦点在它上面时先停住, 新的行排好落到原来位置上的那一行
            if (focusedKeyIn(list.id) != null) {
                park()
                pending = Pending(list.id, null, lastIndex[list.id] ?: 0, null)
            }
        } else if (activeRow != null) {
            // 同一行换了内容 (状态、回复): 右栏当场换, 正文停在原处
            bindPane(list.id, activeRow)
        }
        list.diffing = true
        list.adapter.submitList(tvNoticeListEntries(rows)) {
            list.diffing = false
            // 在签那一排换到的签, 行这时才取到: 两栏换过去
            if (list.id == selectedTab && shownTab != selectedTab && pending == null) showColumns(list.id)
            if (list.id == shownTab) {
                val key = paneRow?.takeIf { paneTab == list.id }?.item?.key
                if (pending == null && (key == null || rows.none { it.item.key == key })) {
                    val index = (lastIndex[list.id] ?: 0).coerceAtMost(rows.lastIndex)
                    bindPane(list.id, rows.firstOrNull { it.item.key == lastRow[list.id] } ?: rows.getOrNull(index))
                }
                updateEmpty()
            }
            resolvePending()
            reportSeen(list)
        }
    }

    /**
     * 两栏换成签 [id] 的: 它的列表显示出来 (别的藏起来, 照样排版). [bind] 时右栏说它上次说的那一行 (没有就第一行); 马上要落到别的行上时
     * 不说 (不然那一行白报一次右栏换成了它).
     */
    private fun showColumns(id: String, bind: Boolean = true) {
        shownTab = id
        for (list in lists.values) {
            if (list.id == id) list.everShown = true
            list.grid.visibility = when {
                list.id == id -> VISIBLE
                list.everShown -> INVISIBLE
                else -> GONE
            }
            // 藏起来的列表焦点进不去 (系统按方向找焦点、别处要焦点都不会落进去); 持焦的那一列先放开焦点再藏 (换签时已经停在自己身上)
            if (list.id == id || !list.grid.hasFocus()) {
                list.grid.descendantFocusability = if (list.id == id) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
            }
        }
        val rows = lists[id]?.tab?.rows.orEmpty()
        if (bind) bindPane(id, rows.firstOrNull { it.item.key == lastRow[id] } ?: rows.firstOrNull())
        updateEmpty()
        lists[id]?.let { reportSeen(it) }
    }

    private fun updateEmpty() {
        val tab = lists[shownTab]?.tab
        val rows = tab?.rows
        emptyView.visibility = if (rows != null && rows.isEmpty()) VISIBLE else GONE
        emptyView.setTextIfChanged(tab?.emptyText.orEmpty())
        requestLayout()
    }

    private fun hasButtons(item: TvNoticeColumnItem, tab: TvNoticeViewTab?): Boolean =
        item.rowAction == null && (item.actions.size > 1 || ((style.touch || tab?.buttonsForSingleAction == true) && item.actions.isNotEmpty()))

    /** 右栏画 [row] (签 [tabId] 的); null = 空着. 同一行换了内容时原地换. */
    private fun bindPane(tabId: String, row: TvNoticeViewRow?) {
        if (row == null) {
            if (paneRow != null || paneTab != tabId) {
                paneRow = null
                paneTab = tabId
                pane.clear()
                syncSelectedRow()
            }
            return
        }
        val sameRow = paneTab == tabId && paneRow?.item?.key == row.item.key
        if (sameRow && paneRow === row) return
        val tab = lists[tabId]?.tab ?: return
        val buttons = hasButtons(row.item, tab)
        val labels = if (buttons) row.item.actions.size else 0
        // 持焦的那颗按钮这一回没了: 焦点先回那一行, 再拿掉按钮 (那一行还没排出来时先停在自己身上)
        if (sameRow && pane.actions.focusedIndex() >= labels) {
            val list = lists[tabId]
            if (list == null || !focusRow(list, row.item.key)) {
                park()
                pending = Pending(tabId, row.item.key, lastIndex[tabId] ?: 0, null)
            }
        }
        paneRow = row
        paneTab = tabId
        pane.bind(row, buttons, startAtEnd = row.item.bodyStartsAtEnd ?: tab.bodyStartsAtEnd, sameRow = sameRow, env = env)
        val index = tab.rows?.indexOfFirst { it.item.key == row.item.key } ?: -1
        if (index >= 0) lastIndex[tabId] = index
        if (!sameRow) {
            lastRow[tabId] = row.item.key
            listener?.onShown(tabId, row.item.key)
        }
        syncSelectedRow()
    }

    private fun syncSelectedRow() {
        for (list in lists.values) {
            list.adapter.selectedKey = if (list.id == paneTab && (inPane || style.touch)) paneRow?.item?.key else null
        }
    }

    /**
     * 签 [tabId] 里键为 [key] 的那一行此刻的数据与位置. 列表行上记的 (TvNoticeRowView.entry) 可能是旧的: 只是右栏的内容、操作变了时
     * 差异更新不重新绑定那一行, 位置在前面的行删掉后也不变.
     */
    private fun currentRow(tabId: String, key: String): TvNoticeRowEntry? {
        val rows = lists[tabId]?.tab?.rows ?: return null
        val index = rows.indexOfFirst { it.item.key == key }
        return if (index < 0) null else TvNoticeRowEntry(rows[index], index)
    }

    private fun onRowFocused(tabId: String, focused: TvNoticeRowEntry) {
        if (tabId != shownTab) {
            // 不该发生: 藏起来的签的列表挡着焦点. 记一笔, 焦点回到画着的签
            Log.w(LOG_TAG, "row ${focused.id} of hidden tab $tabId got focus (shown $shownTab, selected $selectedTab)")
            post {
                requestEntry()
                resolvePending()
            }
            return
        }
        val entry = currentRow(tabId, focused.id) ?: focused
        inPane = false
        bindPane(tabId, entry.row)
        syncSelectedRow()
    }

    private fun onPaneFocusChanged() {
        inPane = pane.actions.hasFocus() || pane.body.isFocused
        syncSelectedRow()
    }

    /**
     * 这一行能不能进正文翻: 没有操作 (只是告知、电视上的「写新反馈」这种) 且正文放不下 —— 不然多出来的那截看不到. 有操作的进按钮那里翻
     * (横排按钮上下键); 只有一个操作、不画按钮的与有 [TvNoticeColumnItem.rowAction] 的按确定键直接做, 不进.
     */
    private fun canEnterBody(item: TvNoticeColumnItem): Boolean =
        !style.touch && item.actions.isEmpty() && item.rowAction == null && pane.body.maxScroll() > 0

    private fun focusBody() {
        pane.body.requestFocus()
    }

    /** 右边没有签可换 (最右的签, 或没有别的签): 列表里右键同确定键进右栏. */
    private fun rightEnters(): Boolean {
        val tabs = content?.tabs ?: return true
        return tabs.indexOfFirst { it.id == shownTab } >= tabs.lastIndex
    }

    // ---- 焦点 ----

    private enum class Region { NONE, REFRESH, LIST, PANE, BODY }

    private fun regionOf(view: View?): Region = when {
        view == null -> Region.NONE
        view === refreshView -> Region.REFRESH
        lists[shownTab]?.let { tvNativeIsInside(view, it.grid) } == true -> Region.LIST
        tvNativeIsInside(view, pane.actions) -> Region.PANE
        view === pane.body -> Region.BODY
        else -> Region.NONE
    }

    private fun showTabs(): Boolean = (content?.tabs?.size ?: 0) > 1

    /** 签 [tabId] 里焦点所在那一行的键 (焦点在行上, 或在右栏说它的按钮上); 不在这个签的两栏里为 null. */
    private fun focusedKeyIn(tabId: String): String? {
        if (tabId != shownTab) return null
        val focused = findFocus()
        return when (regionOf(focused)) {
            Region.LIST -> (rowViewOf(focused, lists[tabId]?.grid) as? TvNoticeRowView)?.entry?.id
            Region.PANE -> paneRow?.item?.key
            else -> null
        }
    }

    private fun rowViewOf(view: View?, grid: RecyclerView?): View? {
        if (grid == null) return null
        var v: View? = view
        while (v != null && v.parent !== grid) v = v.parent as? View
        return v
    }

    /**
     * 落 [pending]: 先把两栏换成那个签、右栏换成那一行 (不用等排版), 再给焦点 —— 行没排出来时跳到那个位置, 排好时 (排版完成回调) 再给.
     * 行还没取到、差异更新还没提交时等着.
     */
    private fun resolvePending() {
        val p = pending ?: return
        val list = lists[p.tab] ?: return
        val tab = list.tab ?: return
        val rows = tab.rows ?: return
        if (list.diffing) return
        if (shownTab != p.tab) showColumns(p.tab, bind = rows.isEmpty())
        if (rows.isEmpty()) {
            pending = null
            if (isAttachedToWindow) focusFallback()
            unpark()
            return
        }
        val index = p.key?.let { key -> rows.indexOfFirst { it.item.key == key } }?.takeIf { it >= 0 } ?: p.index.coerceIn(0, rows.lastIndex)
        val row = rows[index]
        bindPane(p.tab, row)
        if (!isAttachedToWindow) return
        if (style.touch) {
            pending = null
            unpark()
            return
        }
        val action = p.action
        if (action != null && hasButtons(row.item, tab)) {
            val buttons = pane.actions.buttons
            if (buttons.getOrNull(action.coerceIn(0, buttons.lastIndex))?.requestFocus() == true) {
                pending = null
                unpark()
                return
            }
        }
        when {
            focusRow(list, row.item.key) -> {
                pending = null
                unpark()
            }

            ++p.attempts > MAX_FOCUS_ATTEMPTS -> {
                Log.w(LOG_TAG, "giving up focusing row ${row.item.key} in ${p.tab}")
                pending = null
                focusFallback()
                unpark()
            }
        }
    }

    /** 排出来了当场给 (true); 没排出来就直接跳到那个位置, 排好时再给. */
    private fun focusRow(list: TabList, key: String): Boolean {
        val position = list.adapter.positionOf(key)
        if (position < 0) return false
        val view = list.grid.findViewHolderForAdapterPosition(position)?.itemView
        if (view != null && view.requestFocus()) {
            waitingGrid = null
            waitingKey = null
            return true
        }
        if (waitingGrid !== list || waitingKey != key) {
            waitingGrid = list
            waitingKey = key
            list.grid.setSelectedPosition(position)
        }
        return false
    }

    private fun onGridLaidOut(list: TabList) {
        if (waitingGrid === list) {
            waitingGrid = null
            waitingKey = null
        }
        resolvePending()
        reportSeen(list)
    }

    /** 两栏里没有能停的行: 落在刷新上. */
    private fun focusFallback() {
        if (style.touch) return
        refreshView.requestFocus()
    }

    /** 焦点落到签 [tab] 里键为 [key] 的那一行 (没有就第 [index] 行), 必要时等它排出来. */
    private fun focusRowLater(tab: String, key: String?, index: Int) {
        pending = Pending(tab, key, index, null)
        resolvePending()
    }

    private fun focusPaneRow() {
        val key = paneRow?.item?.key ?: return
        focusRowLater(paneTab ?: shownTab, key, lastIndex[paneTab ?: shownTab] ?: 0)
    }

    /** 焦点暂停在视图自己身上 (焦点所在的东西马上要拿掉, 新的还没排好). */
    private fun park() {
        if (isFocused) return
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        isFocusable = true
        requestFocus()
        removeCallbacks(parkWatchdog)
        postDelayed(parkWatchdog, PARK_TIMEOUT_MILLIS)
    }

    private fun unpark() {
        removeCallbacks(parkWatchdog)
        if (!isFocusable) return
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        val focusedSelf = isFocused
        isFocusable = false
        if (focusedSelf && !hasFocus()) focusFallback()
    }

    /** 停太久 (要落的那一行一直没等到): 放弃这次落点, 焦点回到签上 (或刷新), 记一笔. 正常流程到不了这里. */
    private val parkWatchdog = Runnable {
        if (!isFocused) return@Runnable
        Log.w(LOG_TAG, "focus parked for ${PARK_TIMEOUT_MILLIS}ms, releasing: pending=${pending?.tab}/${pending?.key}")
        pending = null
        unpark()
        if (!hasFocus()) focusFallback()
    }

    /** 新排出来的行不往上报: 系统会拿它当「有可聚焦的视图了」把焦点塞过去, 抢在视图自己的落点之前. */
    override fun focusableViewAvailable(v: View?) = Unit

    /**
     * 窗口要给这里焦点 (打开弹窗时系统给默认焦点; 叠在上面的小窗关掉、窗口拿回焦点时 Compose 再要一次): 里面已经有焦点的不动 (还在打开小窗的那颗按钮上);
     * 没有的落到该落的那一行, 还落不了时先停在自己身上.
     */
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (hasFocus() && !isFocused) return true
        if (pending == null) requestEntry()
        resolvePending()
        if (hasFocus()) return true
        park()
        return isFocused
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolvePending()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) hadWindowFocus = true
        covered = !hasWindowFocus && hadWindowFocus
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(parkWatchdog)
        super.onDetachedFromWindow()
    }

    /** 焦点不在视图里时按了键: 落回右栏说的那一行 (没有就这个签的第一行). */
    private fun requestEntry() {
        val tab = shownTab
        pending = Pending(tab, paneRow?.takeIf { paneTab == tab }?.item?.key ?: lastRow[tab], lastIndex[tab] ?: 0, null)
    }

    // ---- 按键 ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) navigate(event.keyCode)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                handleConfirm(event)
                return true
            }

            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (handleBack(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun navigate(code: Int) {
        // 焦点正停在视图自己身上: 落点马上会给出去, 这一下不动
        if (isFocused) return
        val focused = findFocus()
        when (regionOf(focused)) {
            Region.NONE -> {
                requestEntry()
                resolvePending()
            }

            Region.REFRESH -> when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> enterList()
                // 这个签一行都没有 (焦点没有别处可去): 左右键照列表里一样换签
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (lists[shownTab]?.tab?.rows?.isEmpty() == true) {
                    switchFromList(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                }
            }

            Region.LIST -> {
                val list = lists[shownTab] ?: return
                val key = (rowViewOf(focused, list.grid) as? TvNoticeRowView)?.entry?.id ?: return
                val entry = currentRow(list.id, key) ?: return
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (!stepRow(list, entry, -1)) focusAboveList()
                    KeyEvent.KEYCODE_DPAD_DOWN -> stepRow(list, entry, 1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> switchFromList(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (!switchFromList(1) && rightEnters()) {
                        bindPane(list.id, entry.row)
                        when {
                            hasButtons(entry.row.item, list.tab) -> focusAction(0)
                            canEnterBody(entry.row.item) -> focusBody()
                        }
                    }
                }
            }

            Region.BODY -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> page(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> page(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> focusPaneRow()
            }

            Region.PANE -> {
                val index = pane.actions.focusedIndex()
                val count = pane.actions.buttons.size
                if (count <= style.rowActions) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) focusAction(index - 1) else focusPaneRow()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (index < count - 1) focusAction(index + 1)
                        KeyEvent.KEYCODE_DPAD_UP -> page(-1)
                        KeyEvent.KEYCODE_DPAD_DOWN -> page(1)
                    }
                } else {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> focusPaneRow()
                        KeyEvent.KEYCODE_DPAD_UP -> if (index > 0) focusAction(index - 1)
                        KeyEvent.KEYCODE_DPAD_DOWN -> if (index < count - 1) focusAction(index + 1)
                    }
                }
            }
        }
    }

    /** 列表里上下走一行; 到头了不动, 返回 false. */
    private fun stepRow(list: TabList, entry: TvNoticeRowEntry, direction: Int): Boolean {
        val rows = list.tab?.rows ?: return false
        val next = rows.getOrNull(entry.index + direction) ?: return false
        focusRowLater(list.id, next.item.key, entry.index + direction)
        return true
    }

    /** 列表第一行上面: 刷新 (签那一排不能聚焦). */
    private fun focusAboveList() {
        refreshView.requestFocus()
    }

    private fun focusAction(index: Int) {
        pane.actions.buttons.getOrNull(index)?.requestFocus()
    }

    /** 刷新上按下键进列表: 落在右栏说的那一行. 刚换的签还没取到行 (两栏还是旧签的) 时不动. */
    private fun enterList() {
        if (shownTab != selectedTab) return
        val rows = lists[shownTab]?.tab?.rows ?: return
        if (rows.isEmpty()) return
        val key = paneRow?.takeIf { paneTab == shownTab }?.item?.key ?: rows.first().item.key
        focusRowLater(shownTab, key, lastIndex[shownTab] ?: 0)
    }

    /** 列表里左右键换签; 那一头没有签了 (最左 / 最右) 返回 false. */
    private fun switchFromList(direction: Int): Boolean {
        val tabs = content?.tabs ?: return false
        if (tabs.size <= 1) return false
        val next = tabs.getOrNull(tabs.indexOfFirst { it.id == selectedTab } + direction) ?: return false
        selectTab(next.id, fromList = true)
        return true
    }

    /**
     * 换到签 [id]. 触屏上点签换的 ([fromList] 为 false): 两栏当场换 (行还没取到时等取到再换). 从列表换的: 焦点进那个签的列表,
     * 落在它上次说的那一行; 行还没取到时焦点先停在视图自己身上等着, 一行都没有时落在刷新上.
     */
    private fun selectTab(id: String, fromList: Boolean) {
        val c = content ?: return
        if (c.tabs.none { it.id == id }) return
        pending = null
        selectedTab = id
        syncTabViews(c)
        listener?.onTabShown(id)
        val rows = lists[id]?.tab?.rows
        if (!fromList) {
            if (rows != null && shownTab != id) showColumns(id)
            ownSelects += OwnSelect(id, intoList = false, focusKey = null)
            listener?.onSelectTab(id, false, null)
            return
        }
        val key = lastRow[id]
        ownSelects += OwnSelect(id, intoList = true, focusKey = key)
        listener?.onSelectTab(id, true, key)
        when {
            rows == null -> {
                park()
                pending = Pending(id, key, 0, null)
            }

            rows.isEmpty() -> {
                showColumns(id)
                refreshView.requestFocus()
            }

            else -> {
                // 焦点所在的列表马上藏起来: 先停在自己身上, 新签的那一行排好再给
                park()
                showColumns(id, bind = false)
                focusRowLater(id, key, 0)
            }
        }
    }

    /** 横排按钮上按上下键: 翻正文可见高度的 [TvNoticeStyle.pageFraction]; 连按时接着上一下的目标往下算. */
    private fun page(direction: Int) {
        val body = pane.body
        val step = (body.height * style.pageFraction).roundToInt()
        val now = SystemClock.uptimeMillis()
        val from = if (now < pageUntil) pageTarget else body.scrollY
        val target = (from + direction * step).coerceIn(0, body.maxScroll())
        if (target == body.scrollY) return
        pageTarget = target
        pageUntil = now + PAGE_SETTLE_MILLIS
        body.smoothScrollTo(0, target)
    }

    private fun handleConfirm(event: KeyEvent) {
        val focused = findFocus()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                pressed = focused
            }

            KeyEvent.ACTION_UP -> {
                val p = pressed
                pressed = null
                // 按下不是在这里 (打开弹窗的那次确定键): 不算
                if (p == null || p !== focused) return
                click(p)
            }
        }
    }

    private fun click(view: View) {
        when {
            view === refreshView -> listener?.onRefresh()
            view is TvNoticeRowView -> view.entry?.let { rowClicked(shownTab, currentRow(shownTab, it.id) ?: it, fromKey = true) }
            view is TvNoticeButtonView && view.parent === pane.actions -> actionClicked(view.index)
        }
    }

    /** 返回键: 焦点在右栏的按钮上时回到那一行, 不关弹窗 (按下与抬起都吃掉); 别处交给弹窗 (关掉). */
    private fun handleBack(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val region = regionOf(findFocus())
                if (region != Region.PANE && region != Region.BODY) return false
                backInPane = true
                return true
            }

            KeyEvent.ACTION_UP -> {
                if (!backInPane) return false
                backInPane = false
                focusPaneRow()
                return true
            }
        }
        return false
    }

    /**
     * 一行上按确定键 (触屏上点一下): 右栏换成它; 有 [TvNoticeColumnItem.rowAction] 的直接做; 画了按钮的焦点到第一颗 (触屏上只是选中它);
     * 没有操作、正文放不下的焦点进正文 (上下键翻); 只有一个操作的直接做.
     */
    private fun rowClicked(tabId: String, entry: TvNoticeRowEntry, fromKey: Boolean) {
        val tab = lists[tabId]?.tab ?: return
        val item = entry.row.item
        bindPane(tabId, entry.row)
        val rowAction = item.rowAction
        when {
            rowAction != null -> listener?.onAction(tabId, entry.index, item, rowAction)
            hasButtons(item, tab) -> if (fromKey) focusAction(0)
            item.actions.isEmpty() -> if (fromKey && canEnterBody(item)) focusBody()
            else -> item.actions.singleOrNull()?.let { listener?.onAction(tabId, entry.index, item, it) }
        }
    }

    /** 右栏第 [index] 颗按钮. 当场做完的 (Run) 焦点先回那一行 (按钮可能随它处理掉一起没了); 一个操作也画按钮的签里焦点留在按钮上. */
    private fun actionClicked(index: Int) {
        val row = paneRow ?: return
        val tabId = paneTab ?: return
        val tab = lists[tabId]?.tab ?: return
        val action = row.item.actions.getOrNull(index) ?: return
        val rowIndex = tab.rows?.indexOfFirst { it.item.key == row.item.key }?.coerceAtLeast(0) ?: 0
        if (action.destination is TvNoticeDestination.Run && !tab.buttonsForSingleAction && !style.touch) focusPaneRow()
        listener?.onAction(tabId, rowIndex, row.item, action)
    }

    /** 「通知」签里露出一半以上的行报看过 (每行一次). */
    private fun reportSeen(list: TabList) {
        if (list.tab?.reportsSeen != true || list.grid.visibility != VISIBLE || !isAttachedToWindow) return
        val grid = list.grid
        val top = grid.paddingTop
        val bottom = grid.height - grid.paddingBottom
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i) as? TvNoticeRowView ?: continue
            val key = child.entry?.id ?: continue
            if (key in seen || child.height <= 0) continue
            val visible = min(child.bottom + child.translationY.toInt(), bottom) - max(child.top + child.translationY.toInt(), top)
            if (visible >= child.height * SEEN_FRACTION) {
                seen += key
                listener?.onSeen(key)
            }
        }
    }

    // ---- 布局 ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (text in arrayOf(titleView, refreshStatusView, emptyView)) text.forceLayout()
        refreshView.measure(unspecified, unspecified)
        var headerRow = refreshView.measuredHeight
        var leftWidth = 0
        if (showTabs()) {
            tabViews.forEachIndexed { i, tab ->
                tab.measure(unspecified, unspecified)
                leftWidth += tab.measuredWidth + if (i > 0) s.tabGapPx else 0
                headerRow = max(headerRow, tab.measuredHeight)
            }
        } else {
            titleView.measureWithin(width - refreshView.measuredWidth)
            leftWidth = titleView.measuredWidth
            headerRow = max(headerRow, titleView.measuredHeight + titleView.overflowPad * 2)
        }
        if (refreshStatusView.visibility != GONE) {
            refreshStatusView.measureWithin(width - leftWidth - refreshView.measuredWidth - s.refreshStatusGapPx)
            headerRow = max(headerRow, refreshStatusView.measuredHeight + s.refreshStatusBottomPx)
        }
        headerRowHeight = headerRow
        columnsTop = headerRow + if (showTabs()) s.headerGapTabsPx else s.headerGapTitlePx
        // 两栏一样高, 高固定 (焦点换一行时弹窗不变高变矮), 窗口放不下时收
        var body = s.bodyHeightPx
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            body = min(body, MeasureSpec.getSize(heightMeasureSpec) - columnsTop).coerceAtLeast(0)
        }
        bodyHeight = body
        listWidth = min(s.listWidthPx, (width * s.listMaxFraction).roundToInt())
        val paneWidth = (width - listWidth - s.dividerWidthPx - s.columnGapPx * 2).coerceAtLeast(0)
        for (list in lists.values) list.grid.measure(tvNoticeExactly(listWidth), tvNoticeExactly(body))
        if (emptyView.visibility != GONE) emptyView.measureWithin(listWidth)
        pane.measure(tvNoticeExactly(paneWidth), tvNoticeExactly(body))
        setMeasuredDimension(width, columnsTop + body)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        val width = r - l
        val rowBottom = headerRowHeight
        if (showTabs()) {
            var x = 0
            tabViews.forEach {
                it.tvNoticeLayoutAt(x, rowBottom - it.measuredHeight)
                x += it.measuredWidth + s.tabGapPx
            }
        } else {
            titleView.tvNoticeLayoutAt(0, rowBottom - titleView.overflowPad - titleView.measuredHeight)
        }
        refreshView.tvNoticeLayoutAt(width - refreshView.measuredWidth, rowBottom - refreshView.measuredHeight)
        if (refreshStatusView.visibility != GONE) {
            refreshStatusView.tvNoticeLayoutAt(
                refreshView.left - s.refreshStatusGapPx - refreshStatusView.measuredWidth,
                rowBottom - s.refreshStatusBottomPx - refreshStatusView.measuredHeight,
            )
        }
        val top = columnsTop
        for (list in lists.values) list.grid.tvNoticeLayoutAt(0, top)
        if (emptyView.visibility != GONE) emptyView.tvNoticeLayoutAt(0, top + s.emptyPaddingVPx)
        pane.tvNoticeLayoutAt(listWidth + s.columnGapPx * 2 + s.dividerWidthPx, top)
    }

    override fun onDraw(canvas: Canvas) {
        val s = style
        dividerPaint.color = s.dividerColor
        val x = (listWidth + s.columnGapPx).toFloat()
        canvas.drawRect(x, columnsTop.toFloat(), x + s.dividerWidthPx, (columnsTop + bodyHeight).toFloat(), dividerPaint)
    }

    // ---- 测试用 ----

    /** 此刻聚焦的东西的字 (行的标题、按钮与签上的字、刷新的「刷新」); 停在自己身上或没有焦点为 null. */
    internal fun focusedText(): String? = when (val focused = findFocus()) {
        is TvNoticeRowView -> focused.entry?.row?.item?.title
        is TvNoticeButtonView -> focused.text
        refreshView -> refreshUi?.label
        else -> null
    }

    /** 右栏此刻说的那一行的键. */
    internal val shownKey: String? get() = paneRow?.item?.key

    /** 两栏此刻画的签. */
    internal val shownTabId: String get() = shownTab

    /** 右栏正文滚到哪、最多能滚到哪 (px). */
    internal fun bodyScroll(): Pair<Int, Int> = pane.body.scrollY to pane.body.maxScroll()

    /** 视图已经照着落过焦点的那一次打开 (宿主交来的 [TvNoticeViewContent.opening]). */
    internal val appliedOpening: Any? get() = opening

    /** 签 [tab] 的行取到了没有. */
    internal fun rowsLoaded(tab: String): Boolean = lists[tab]?.tab?.rows != null

    /** 窗口被叠在上面的小窗拿走了焦点、聚焦的样子不画 (见 [covered]). */
    internal val coveredForTest: Boolean get() = covered

    /** 焦点在右栏正文上 (没有操作的行进来翻正文). */
    internal val bodyFocused: Boolean get() = pane.body.isFocused

    /** 右栏此刻画着几颗按钮. */
    internal val actionCount: Int get() = if (pane.actions.visibility == VISIBLE) pane.actions.buttons.size else 0

    private companion object {
        const val LOG_TAG = "TvNotice"

        /** 焦点停在视图自己身上最多多久, 见 [parkWatchdog]. */
        const val PARK_TIMEOUT_MILLIS = 800L

        /** 一行等排版落焦点最多试几次 (每次排版完成试一次). */
        const val MAX_FOCUS_ATTEMPTS = 4

        /** 翻页动画在这段时间里连按时接着上一下的目标算. */
        const val PAGE_SETTLE_MILLIS = 300L

        /** 一行露出多少算显示到屏幕上了 (算看过). */
        const val SEEN_FRACTION = 0.5f
    }
}
