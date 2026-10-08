/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.compose.runtime.Immutable
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.LONG_PRESS_MIN_HOLD
import me.him188.ani.app.ui.foundation.TvNativeImages
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_NATIVE_FAST_OUT_SLOW_IN
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeSpringScroll
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextView
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeIsInside
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeLerpColor
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeTypeface
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeWithAlpha
import kotlin.math.ceil
import kotlin.math.max

/**
 * 原生选源面板的尺寸 (px) 与配色 (ARGB), 由 rememberTvSourcePanelStyle 按界面缩放与视觉效果档位算好. 配色固定是播放器那一套黑白
 * (与控制层的胶囊同一种玻璃底, 背后不铺暗底): 未聚焦是半透明白底白字, 聚焦是近白实底黑字 —— 一眼就能找到焦点.
 */
@Immutable
data class TvSourcePanelStyle(
    /**
     * 右栏宽度的下限按这两行字量 (「完成验证」那一行的标题与说明): 内容再短也放得下它们, 说明行不至于折成一长条; 不小于 [rightMinWidthPx].
     * 实际宽度按这一屏的内容定 (每行的标题 / 信息刚好放下), 不超过 [maxRightWidthPx]; 放不下的聚焦时跑马灯.
     */
    val rightSampleTitle: String,
    val rightSampleMeta: String,
    val rightMinWidthPx: Int,
    val rightSlackPx: Int,
    /** 按内容定宽的上限; 宽一档的分支 ([TvSourceRight.wide], BT 资源与下载) 固定用这个宽度. */
    val maxRightWidthPx: Int,
    val paddingStartPx: Int,
    val paddingEndPx: Int,
    val paddingTopPx: Int,
    val paddingBottomPx: Int,
    val headerGapPx: Int,
    val railWidthPx: Int,
    val columnGapPx: Int,
    val rowGapPx: Int,
    val dividerGapPx: Int,
    val dividerColor: Int,
    val cornerPx: Float,
    val rowPaddingHPx: Int,
    val iconSizePx: Int,
    val iconGapPx: Int,
    val trailingGapPx: Int,
    val spinnerSizePx: Int,
    val railRowHeightPx: Int,
    val lineRowHeightPx: Int,
    val resourceRowHeightPx: Int,
    /** 文件行 ([TvSourceRowStyle.File]) 上下的留白; 行高按内容, 不矮于 [lineRowHeightPx]. */
    val fileRowPaddingVPx: Int,
    val optionRowHeightPx: Int,
    val cellHeightPx: Int,
    val statusPaddingVPx: Int,
    val textGapPx: Int,
    val pillHeightPx: Int,
    val pillPaddingHPx: Int,
    val pillGapPx: Int,
    val pillsBottomGapPx: Int,
    val drillTitleBottomGapPx: Int,
    /** 列表左右多留的一截: 聚焦行放大后不被列表的边界裁掉. */
    val focusBleedPx: Int,
    /** 顶上那张状态卡片 (「已查询 22 个数据源」) 与右栏小标题的字. */
    val status: TvNativeTextStyle,
    val drillTitle: TvNativeTextStyle,
    val railTitle: TvNativeTextStyle,
    val rowTitle: TvNativeTextStyle,
    val rowMeta: TvNativeTextStyle,
    val trailing: TvNativeTextStyle,
    val pill: TvNativeTextStyle,
    val cell: TvNativeTextStyle,
    val idleBg: Int,
    /**
     * 选中但没聚焦: 左栏里右栏正在显示的那一项 (焦点在右栏时)、正在播放的、当前生效的筛选. 底比未聚焦的深一档再描一圈细白边 ——
     * 背后没有暗底, 用更浅的底的话白字在亮画面上就糊了.
     */
    val activeBg: Int,
    val activeBorderPx: Float,
    val activeBorderColor: Int,
    val focusedBg: Int,
    val text: Int,
    val textSecondary: Int,
    val focusedText: Int,
    val focusedTextSecondary: Int,
    val error: Int,
    val focusedError: Int,
    val dimmedAlpha: Float,
    /** 状态卡片 / 右栏小标题的内边距. */
    val chipPaddingHPx: Int,
    val chipPaddingVPx: Int,
    /** 右栏换屏时新内容从左栏那边滑进来的距离. */
    val branchShiftPx: Int,
    val branchFadeMillis: Long,
    val focusScale: Float,
    /** 一行放不下的字聚焦一会儿后跑马灯的圈数: 0 = 不跑 (流畅档), -1 = 一直跑. BT 资源的标题不跑 (三行, 全文在详情里). */
    val marqueeRepeat: Int,
    val focusInMillis: Long,
    val focusOutMillis: Long,
    val animated: Boolean,
    val icons: Map<TvSourceRowIcon, Bitmap>,
)

/** 面板报给宿主的事件. 都在主线程. */
interface TvSourcePanelListener {
    /** 左栏焦点换到 [key] (右栏要跟着换). */
    fun onRailFocused(key: String)

    /** 焦点进出右栏 (进右栏时宿主钉住行序, 见 applyFrozenOrder). */
    fun onRightFocusChanged(inRight: Boolean)

    fun onAction(action: TvSourceAction)

    /** 右栏里按返回: 宿主退出进去的那一层就返回 true; false = 面板把焦点送回左栏. */
    fun onBackInRight(): Boolean

    /** 要关面板: 在左栏按了左键, 或触屏点了面板外面 (画面). */
    fun onCloseRequested()
}

/**
 * 选源面板 (原生 View): 铺满播放器, 像一棵从屏幕左边缘伸出来的树 —— 左栏 (数据源这一组) 贴边, 右栏 (那一项下面的线路 / 资源) 是从左栏那一行
 * 伸出来的分支, 顶边与那一行对齐 (放不下时往上让), 背后不压暗, 只有一张张玻璃卡片. 两栏都是 leanback 的 [VerticalGridView] —— 它从不回收持焦的
 * 那一行, 列表在搜索途中整表更新时焦点不丢.
 *
 * **方向键全部在这里判, 一个都不放给 Compose**: 视图挂在 Compose 的 AndroidView 里, 没被消费的方向键会交给 Compose 按屏幕位置找下一个焦点
 * (原生列表自己的焦点搜索根本走不到), 会跳到底下被藏起来的播放器控件上. 到边的键也吞掉. 确定键 (短按 / 长按) 同样自己判, 只认见过按下的那一次
 * (打开面板的那次确定键抬起落在这里时不算).
 *
 * 数据走 [submit]: 宿主在内容变化时把整份 [TvSourcePanelContent] 交过来, 两栏按行 id 做差异更新 (后台线程算). 右栏换了一屏 (右栏 key 变了)
 * 而焦点在右栏时, 焦点先停在面板自己身上, 新内容排好再交给落点 —— 不停的话持焦的行一被删, 系统会把焦点塞给随便哪个视图.
 */
@SuppressLint("ViewConstructor")
class TvSourcePanelView(
    context: Context,
    private var style: TvSourcePanelStyle,
    private val sketch: Sketch,
) : FrameLayout(context) {
    var listener: TvSourcePanelListener? = null

    /** 面板正在退场: 吞掉全部按键 (焦点交还播放器之前那一两帧里, 确定键会点中淡出中的行). */
    var leaving: Boolean = false

    private val panel = PanelLayout(context)
    private val statusView = TvNativeTextView(context)
    private val rail = createGrid(context)
    private val railAdapter = TvSourceRowAdapter(sketch, style)
    private val drillTitleView = TvNativeTextView(context)
    private val pills = TvSourcePillFlow(context, sketch, style)
    private val list = createGrid(context)
    private val listAdapter = TvSourceRowAdapter(sketch, style)

    /** 右栏宽度的下限 (px), 随样式重算 (见 [TvSourcePanelStyle.rightSampleTitle]). */
    private var rightWidthPx = 0

    /** 分支按这一屏 ([branchWidthKey]) 定的宽度, 见 [updateBranchWidth]; 0 = 还没有内容. */
    private var branchFitWidthPx = 0
    private var branchWidthKey: String? = null

    /** 分支此刻的宽度. */
    private val branchWidthPx: Int
        get() = if (branchFitWidthPx > 0) branchFitWidthPx else rightWidthPx

    private var content: TvSourcePanelContent? = null

    /** 右栏列表里此刻排着的是哪一屏 (差异更新提交之后才换); [incomingRightKey] 是最新交来的那一屏. */
    private var rightKey: String? = null
    private var incomingRightKey: String? = null

    /** 右栏一行几个 (leanback 的网格没有 getter, 记一份), 与此刻设给它的列宽. */
    private var listColumns = 1
    private var listColumnWidth = -1

    /** 每一屏右栏上次聚焦的行 (id; 胶囊带 `pill:` 前缀): 退出一层、切回某一项时落回原处. */
    private val focusMemory = HashMap<String, String>()

    /** 等内容到了 / 排好了再送的焦点. */
    private var pendingEntry = false
    private var pendingEnterRight = false
    private var pendingRightFocus = false

    private var press: Press? = null
    private var lastInRight = false

    /** 左栏此刻聚焦的那一项 (内容里的 railKey 要等状态拼完才跟上). */
    private var focusedRailKey: String? = null

    /**
     * 右栏这一屏进来时列表还没有能聚焦的行 (还在加载), 落点临时给了胶囊: 行到了而用户还没动过, 就把焦点挪到行上 ——
     * 不然手动查找进条目时焦点停在「返回结果」上, 一按确定就退回去了. 值是那一屏的 key, 用户一按方向键就作废.
     */
    private var pillFallbackScreen: String? = null

    /** 右栏此刻显示的那一屏属于左栏哪一项: 分支从那一行伸出来 (见 [updateBranch]). 随右栏换屏的提交更新. */
    private var branchRailKey: String? = null

    /** 分支往下挪了多少 (px, 跟着左栏那一行). */
    private var branchOffset = 0f

    /** 右栏换了一屏、还没排出来: 排好后在新位置淡入. */
    private var branchEntering = false

    /** 分支此刻藏着 (换屏时先藏, 等新的一屏提交). */
    private var branchHidden = false

    /** [moveFocusTo] 时那一行还没排出来: 跳过去之后, 等那一栏排好再把焦点给这一行 (见 [resolvePendingFocus]). */
    private var pendingFocusGrid: VerticalGridView? = null
    private var pendingFocusId: String? = null

    /** 这次左键是在左栏按下的: 抬起时关面板 (从右栏按左回到左栏的那一下, 抬起落在左栏上, 不算). */
    private var leftDownInRail = false

    /**
     * 右栏上下走出了这一项的头 / 尾, 正跨到左栏相邻的数据源: +1 = 往下 (落新一屏的第一行), -1 = 往上 (落最后一行); 0 = 没在跨.
     * 见 [crossRail].
     */
    private var crossing = 0

    private class Press(val rowId: String, val downAt: Long) {
        var fired = false
    }

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        statusView.maxLines = 1
        drillTitleView.maxLines = 2
        drillTitleView.visibility = GONE

        rail.adapter = railAdapter
        list.adapter = listAdapter
        rail.addItemDecoration(DividerDecoration(railAdapter))
        list.addItemDecoration(DividerDecoration(listAdapter))
        railAdapter.onRowFocused = { _, row -> onRailRowFocused(row) }
        listAdapter.onRowFocused = { _, row ->
            rightKey?.let {
                focusMemory[it] = row.id
                focusMemory[it + LIST_SUFFIX] = row.id
            }
            reportRegion()
        }
        pills.onPillFocused = { row -> rightKey?.let { focusMemory[it] = PILL_PREFIX + row.id }; reportRegion() }
        // 触屏: 左栏点一下 = 换到那一项 (有动作的直接做), 右栏点一下 = 做那一行的动作, 长按 = 长按动作
        railAdapter.onRowClicked = { row, long ->
            val longAction = if (long) longActionOf(row, inRail = true) else null
            if (longAction != null) {
                listener?.onAction(longAction)
            } else if (row.action != null) {
                listener?.onAction(row.action)
            } else {
                railAdapter.activeId = row.id
                listener?.onRailFocused(row.id)
            }
        }
        listAdapter.onRowClicked = { row, long ->
            (if (long) longActionOf(row, inRail = false) ?: row.action else row.action)?.let { listener?.onAction(it) }
        }
        pills.onPillClicked = { row -> row.action?.let { listener?.onAction(it) } }
        rail.addOnLayoutCompletedListener {
            resolvePendingFocus(rail)
            resolvePending()
            updateBranch()
        }
        list.addOnLayoutCompletedListener {
            resolvePendingFocus(list)
            resolvePending()
            updateBranch()
        }
        // 左栏滚动时分支跟着那一行走
        rail.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateBranch()
            },
        )

        // 状态卡片最后加 = 最后画: 压在两栏上面
        panel.addView(rail)
        panel.addView(list)
        panel.addView(drillTitleView)
        panel.addView(pills)
        panel.addView(statusView)
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyStyle(style)
    }

    fun applyStyle(style: TvSourcePanelStyle) {
        val changed = this.style != style || rightWidthPx == 0
        this.style = style
        if (changed) {
            rightWidthPx = measureRightWidth(style)
            branchWidthKey = null
            content?.right?.let { updateBranchWidth(it) }
        }
        style.status.applyTo(statusView)
        style.drillTitle.applyTo(drillTitleView)
        for (chip in arrayOf(statusView, drillTitleView)) {
            chip.background = GradientDrawable().apply {
                setColor(style.idleBg)
                cornerRadius = style.cornerPx
            }
            chip.setPadding(style.chipPaddingHPx, style.chipPaddingVPx, style.chipPaddingHPx, style.chipPaddingVPx)
        }
        rail.verticalSpacing = style.rowGapPx
        list.verticalSpacing = style.rowGapPx
        list.horizontalSpacing = style.rowGapPx
        railAdapter.style = style
        listAdapter.style = style
        pills.style = style
        if (changed) {
            requestLayout()
            invalidate()
        }
    }

    /** 新内容. 见类注释. */
    fun submit(content: TvSourcePanelContent) {
        val previous = this.content
        this.content = content
        statusView.text = content.status

        railAdapter.activeId = content.railKey
        railAdapter.submitList(content.rail) {
            // 持焦的那一项没了 (查询失败挪进了「查询失败」): 落到左栏落点
            if (regionOf(findFocus()) == Region.NONE && previous != null && lastRegion == Region.RAIL) {
                focusRail(content.railKey)
            }
            // 焦点不在左栏 (打开时直接落回右栏那一行): 左栏直接对到右栏所属的那一项 —— 否则左栏还停在顶上, 按左回去时整栏一跳
            if (regionOf(findFocus()) != Region.RAIL) {
                val index = content.rail.indexOfFirst { it.id == content.railKey }
                if (index >= 0 && rail.selectedPosition != index) rail.setSelectedPosition(index)
            }
            resolvePending()
        }

        val right = content.right
        val newScreen = right.key != incomingRightKey
        incomingRightKey = right.key
        val focusInRight = regionOf(findFocus()).let { it == Region.LIST || it == Region.PILLS }
        if (newScreen && focusInRight) {
            park()
            pendingRightFocus = true
        }
        if (newScreen && style.animated) {
            // 新的一屏要等列表排好才知道分支落在哪儿: 先藏起来, 排好后在新位置淡入 (见 updateBranch)
            branchAnimator?.cancel()
            for (view in branchViews) view.alpha = 0f
            branchHidden = true
        }
        drillTitleView.text = right.title.orEmpty()
        drillTitleView.visibility = if (right.title.isNullOrEmpty()) GONE else VISIBLE
        pills.submit(right.pills)
        if (listColumns != right.columns) {
            listColumns = right.columns
            list.setNumColumns(right.columns)
            requestLayout()
        }
        // 此刻停在顶上 (第一行排着): 新结果插到前面时要把它们露出来; 往下翻过的就不动
        val listAtTop = listAdapter.itemCount == 0 || list.findViewHolderForAdapterPosition(0) != null
        listAdapter.submitList(right.rows) {
            val inRight = regionOf(findFocus()).let { it == Region.LIST || it == Region.PILLS }
            updateBranchWidth(right)
            branchRailKey = content.railKey
            if (rightKey != right.key) {
                rightKey = right.key
                branchEntering = true
                list.scrollToPosition(0)
            } else if (branchHidden) {
                // 换屏又换回来 (A → B → A): 中间那次提交被差异计算丢掉了, 这一屏还是原来的 key, 照样要淡入
                branchEntering = true
            } else if (!inRight && !isFocused && listAtTop) {
                // 用户没在右栏里、列表停在顶上: 搜索途中新结果插到前面时列表会保持原来的首行, 新来的那几条被顶到视口上方看不见 —— 回到顶上.
                // 用户往下翻过 (比如从 BT 中间按左回了左栏) 就留在原处, 不然再按右进来, 记住的那一行已经被滚到很远
                list.scrollToPosition(0)
            }
            if (pillFallbackScreen == right.key && regionOf(findFocus()) == Region.PILLS && right.rows.any { it.focusable }) {
                pillFallbackScreen = null
                // 胶囊得焦时记成了这一屏停的位置, 不清掉的话落点又按记忆回到胶囊上
                focusMemory.remove(right.key)
                // 行刚提交还没排出来: 排好时 (布局完成回调) 再送
                if (focusRightTarget(content) == FocusResult.Pending) pendingRightFocus = true
            }
            resolvePending()
        }
        resolvePending()
    }

    /**
     * 分支定宽 (行提交时): 宽一档的 (BT 资源与下载) 固定用上限; 其余按这一屏的内容, 短的刚好放下, 长的放宽到上限为止, 不窄于下限.
     * 同一屏里只放宽不收窄 —— 查询途中结果陆续到、展开又收起时, 框不来回伸缩; 换一屏重新量.
     */
    private fun updateBranchWidth(right: TvSourceRight) {
        val cap = max(rightWidthPx, style.maxRightWidthPx)
        val width = if (right.wide) {
            cap
        } else {
            val measured = measureBranchContentWidth(right, style).coerceIn(rightWidthPx, cap)
            if (branchWidthKey == right.key) max(branchFitWidthPx, measured) else measured
        }
        branchWidthKey = right.key
        if (width != branchFitWidthPx) {
            branchFitWidthPx = width
            panel.requestLayout()
        }
    }

    /** 打开面板 / 焦点不在面板里时: 落左栏 (正在播放的资源所在的那一项, 它下面正在播的那一条在分支里亮着). 内容没到就等内容到了再送. */
    fun requestEntryFocus() {
        pendingEntry = true
        resolvePending()
    }

    /**
     * 返回键. true = 面板自己处理了 (退出进去的那一层 / 手动查找的条目页); false = 该关面板了 —— 右栏本身与左栏上按的都是,
     * 宿主记下焦点停在哪 ([focusPosition]), 再打开时落回去.
     */
    fun handleBack(): Boolean = when (regionOf(findFocus())) {
        Region.LIST, Region.PILLS -> listener?.onBackInRight() == true
        else -> false
    }

    /** 焦点在右栏 (左栏那一项本身那一屏, 不是进去的那一层) 的哪一行: (左栏项, 行 id); 不在右栏为 null. */
    fun rightRowPosition(): Pair<String, String>? {
        val focused = findFocus() as? TvSourceRowView ?: return null
        if (regionOf(focused) != Region.LIST) return null
        val railKey = content?.railKey ?: return null
        if (rightKey != railKey) return null
        return railKey to (focused.row?.id ?: return null)
    }

    /**
     * 焦点停在哪: (左栏项, 右栏那一行的 id); 在左栏时行 id 为 null (左栏那一项), 在右栏的胶囊上同样只记左栏那一项. 不在面板里为 null.
     */
    fun focusPosition(): Pair<String, String?>? {
        rightRowPosition()?.let { return it }
        val focused = findFocus() as? TvSourceRowView ?: return null
        return when (regionOf(focused)) {
            Region.RAIL -> focused.row?.id?.let { it to null }
            Region.PILLS -> content?.railKey?.let { it to null }
            else -> null
        }
    }

    /** 打开时要落回的右栏那一行 (上次按返回隐藏时停的, 见 [focusPosition]); 落过一次 (或那一行没了) 就作废, 照常落左栏. */
    var resumeRow: Pair<String, String>? = null

    /** 面板里有没有焦点 (根路由据此判断要不要先把焦点送进来). */
    fun hasPanelFocus(): Boolean = hasFocus()

    // ---- 焦点 ----

    private enum class Region { NONE, RAIL, PILLS, LIST }

    private var lastRegion = Region.NONE

    private fun regionOf(view: View?): Region = when {
        view == null -> Region.NONE
        tvNativeIsInside(view, rail) -> Region.RAIL
        tvNativeIsInside(view, pills) -> Region.PILLS
        tvNativeIsInside(view, list) -> Region.LIST
        else -> Region.NONE
    }

    private fun reportRegion() {
        val region = regionOf(findFocus())
        if (region != Region.NONE) lastRegion = region
        val inRight = region == Region.LIST || region == Region.PILLS
        if (inRight != lastInRight) {
            lastInRight = inRight
            listener?.onRightFocusChanged(inRight)
        }
    }

    private fun onRailRowFocused(row: TvSourceRow) {
        crossing = 0
        focusedRailKey = row.id
        reportRegion()
        if (content?.railKey != row.id) {
            railAdapter.activeId = row.id
            listener?.onRailFocused(row.id)
        }
    }

    private fun resolvePending() {
        val content = content ?: return
        if (pendingRightFocus) {
            // 新的一屏还没提交到列表: 等提交回调
            if (rightKey != incomingRightKey) return
            when (focusRightTarget(content)) {
                FocusResult.Focused -> {
                    pendingRightFocus = false
                    unpark()
                    // 焦点一直在右栏 (没经过左栏), 宿主没收到「进右栏」: 给新的一屏钉住行序
                    listener?.onRightFocusChanged(true)
                }

                FocusResult.Pending -> {} // 落点还没排出来: 等排好 (布局完成回调)
                FocusResult.None -> {
                    val direction = crossing
                    crossing = 0
                    if (direction != 0) {
                        // 跨过来的源右边暂时没有能选的 (还在查 / 限流 / 没结果): 接着往下一个有右栏的跨, 焦点还停在面板上
                        val next = content.railKey?.let { nextCrossable(it, direction) }
                        if (next != null) {
                            crossing = direction
                            rearmParkWatchdog()
                            followRail(next.id)
                            return
                        }
                        // 再往下没有有右栏的了: 回左栏落在紧挨着的那一项上
                        pendingRightFocus = false
                        val neighbor = content.railKey?.let { railNeighbor(it, direction) }
                        focusRail(neighbor?.id ?: content.railKey)
                        unpark()
                        return
                    }
                    // 新的一屏没有能聚焦的: 回左栏
                    pendingRightFocus = false
                    unpark()
                    focusRail(content.railKey)
                }
            }
            return
        }
        if (pendingEnterRight) {
            // 左栏刚换项, 右栏还是上一项的内容: 等对得上再进
            if (!rightMatchesRail(content) || rightKey != incomingRightKey) return
            if (focusRightTarget(content) != FocusResult.Pending) pendingEnterRight = false
            return
        }
        if (pendingEntry && isAttachedToWindow) {
            val resume = resumeRow
            if (resume != null && content.railKey == resume.first) {
                // 等这一项的右栏那一屏提交、排出来
                if (rightKey != incomingRightKey || !rightMatchesRail(content)) return
                val index = listAdapter.currentList.indexOfFirst { it.id == resume.second && it.focusable }
                if (index >= 0) {
                    if (moveFocusTo(list, index)) {
                        resumeRow = null
                        pendingEntry = false
                    }
                    return
                }
            }
            resumeRow = null
            if (focusRail(content.railKey)) pendingEntry = false
        }
    }

    private enum class FocusResult { Focused, Pending, None }

    private fun rightMatchesRail(content: TvSourcePanelContent): Boolean {
        val railKey = content.railKey ?: return false
        // 用户刚在左栏换了项, 内容还是上一项的
        if (focusedRailKey != null && focusedRailKey != railKey) return false
        val key = rightKey ?: return false
        return key == railKey || key.startsWith("$railKey/") || (railKey == TvSourceRailKeys.MANUAL && key.startsWith(TvSourceRailKeys.MANUAL))
    }

    /**
     * 右栏的落点: 这一屏上次停的 → 内容给的落点 (正在播放 / 当前值) → 第一条能聚焦的行 → 第一个胶囊.
     * [FocusResult.Pending] = 落点那一行还没排出来 (已选中, 排到时 leanback 给焦点; 调用方等布局完成再试).
     */
    private fun focusRightTarget(content: TvSourcePanelContent): FocusResult {
        val right = content.right
        if (crossing != 0) {
            val rows = listAdapter.currentList
            val index = if (crossing > 0) rows.indexOfFirst { it.focusable } else rows.indexOfLast { it.focusable }
            if (index >= 0) {
                if (!moveFocusTo(list, index)) return FocusResult.Pending
                crossing = 0
                return FocusResult.Focused
            }
            // 内容里这一屏有能选的行, 列表还没换过来: 等它提交 (不当成「没有」, 否则快速往下走时会跳过有结果的源)
            if (right.rows.any { it.focusable }) return FocusResult.Pending
            // 没有行: 往上跨进来的有胶囊就停在胶囊上, 否则交给调用方接着跨
            if (crossing < 0 && pills.focusPill(null)) {
                crossing = 0
                return FocusResult.Focused
            }
            return FocusResult.None
        }
        val remembered = rightKey?.let { focusMemory[it] }
        if (remembered != null && remembered.startsWith(PILL_PREFIX)) {
            if (pills.focusPill(remembered.removePrefix(PILL_PREFIX))) return FocusResult.Focused
        }
        val rows = listAdapter.currentList
        val index = sequenceOf(remembered, right.focusId)
            .filterNotNull()
            .map { id -> rows.indexOfFirst { it.id == id && it.focusable } }
            .firstOrNull { it >= 0 }
            ?: rows.indexOfFirst { it.focusable }
        if (index >= 0) return if (moveFocusTo(list, index)) FocusResult.Focused else FocusResult.Pending
        if (!pills.focusPill(null)) return FocusResult.None
        pillFallbackScreen = rightKey
        return FocusResult.Focused
    }

    private fun focusRail(key: String?): Boolean {
        val rows = railAdapter.currentList
        val index = rows.indexOfFirst { it.id == key }.takeIf { it >= 0 } ?: if (rows.isEmpty()) -1 else 0
        if (index < 0) return false
        return moveFocusTo(rail, index)
    }

    /**
     * 排出来了当场给 (leanback 按窗口对齐平滑滚过去). 没排出来 (远处的行、刚换的一屏): 直接跳到那个位置, 排好时 (布局完成回调,
     * [resolvePendingFocus]) 再把焦点给它, 返回 false. 不平滑滚过去: 平滑滚动不触发布局完成回调, 而栏里没有焦点时 leanback 滚到了也不给焦点 ——
     * 焦点就一直等不到, 要再按一次才过去. 也不对整栏 requestFocus: 换屏途中它会把焦点给马上要被移走的旧行, 旧行一移走焦点就丢了.
     */
    private fun moveFocusTo(grid: VerticalGridView, index: Int): Boolean {
        pendingFocusGrid = null
        pendingFocusId = null
        val view = grid.findViewHolderForAdapterPosition(index)?.itemView
        if (view != null && view.isFocusable && view.requestFocus()) return true
        val adapter = if (grid === rail) railAdapter else listAdapter
        pendingFocusId = adapter.currentList.getOrNull(index)?.id ?: return false
        pendingFocusGrid = grid
        grid.setSelectedPosition(index)
        return false
    }

    private fun resolvePendingFocus(grid: VerticalGridView) {
        if (pendingFocusGrid !== grid) return
        val id = pendingFocusId
        pendingFocusGrid = null
        pendingFocusId = null
        val adapter = if (grid === rail) railAdapter else listAdapter
        val index = adapter.currentList.indexOfFirst { it.id == id && it.focusable }
        if (index < 0) return
        grid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
    }

    /** 焦点暂停在面板自己身上 (右栏换屏期间). */
    private fun park() {
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        isFocusable = true
        requestFocus()
        removeCallbacks(parkWatchdog)
        postDelayed(parkWatchdog, PARK_TIMEOUT_MILLIS)
    }

    /**
     * 停在面板自己身上太久 (新的一屏 / 落点一直没等到): 停着的时候方向键全不理, 用户看到的就是「焦点没了、按什么都没反应」.
     * 保险: 放弃这次落点, 焦点回左栏那一项. 正常流程到不了这里 (新的一屏几十毫秒就到), 到了就记一笔.
     */
    private val parkWatchdog = Runnable {
        if (!isFocused) return@Runnable
        Log.w(
            LOG_TAG,
            "focus parked for ${PARK_TIMEOUT_MILLIS}ms, releasing: pendingRightFocus=$pendingRightFocus crossing=$crossing " +
                "rightKey=$rightKey incomingRightKey=$incomingRightKey railKey=${content?.railKey} pendingFocusId=$pendingFocusId",
        )
        crossing = 0
        pendingRightFocus = false
        focusRail(content?.railKey)
        unpark()
    }

    /** 停着等的又换了一屏 (跨数据源时跳过空的源): 重新计时. */
    private fun rearmParkWatchdog() {
        if (!isFocused) return
        removeCallbacks(parkWatchdog)
        postDelayed(parkWatchdog, PARK_TIMEOUT_MILLIS)
    }

    private fun unpark() {
        removeCallbacks(parkWatchdog)
        if (!isFocusable) return
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        val focusedSelf = isFocused
        isFocusable = false
        if (focusedSelf && !hasFocus()) {
            // 落点没给出去: 回左栏, 不让焦点空着
            focusRail(content?.railKey)
        }
    }

    /** 新排出来的行不往上报: 系统会拿它当「有可聚焦的视图了」把焦点塞过去, 抢在面板自己的落点之前. */
    override fun focusableViewAvailable(v: View?) = Unit

    // ---- 按键 ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!dpad && !confirm) return super.dispatchKeyEvent(event)
        if (leaving) return true
        if (dpad) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    leftDownInRail = !isFocused && regionOf(findFocus()) == Region.RAIL
                } else if (event.action == KeyEvent.ACTION_UP) {
                    // 抬起时才关: 按下就关的话, 抬起落到纯视频态那一档会被当成「左键快退」
                    val close = leftDownInRail && regionOf(findFocus()) == Region.RAIL
                    leftDownInRail = false
                    if (close) listener?.onCloseRequested()
                }
            }
            if (event.action == KeyEvent.ACTION_DOWN) navigate(code)
            return true
        }
        handleConfirm(event)
        return true
    }

    private fun navigate(code: Int) {
        // 焦点正停在面板自己身上 (右栏换屏中): 落点马上会给出去, 这一下不动
        if (isFocused) return
        pillFallbackScreen = null
        pendingFocusGrid = null
        pendingFocusId = null
        val focused = findFocus()
        when (regionOf(focused)) {
            Region.NONE -> requestEntryFocus()
            Region.RAIL -> {
                val index = rail.getChildAdapterPosition(rowViewOf(focused, rail) ?: return)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> stepVertical(rail, railAdapter, index, -1, 1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> stepVertical(rail, railAdapter, index, 1, 1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> enterRight()
                }
            }

            Region.PILLS -> {
                val index = pills.indexOfPill(focused)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> crossRail(-1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) pills.focusPillAt(index - 1) else focusRail(content?.railKey)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (index < pills.pillCount - 1) pills.focusPillAt(index + 1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val rows = listAdapter.currentList
                        val remembered = rightKey?.let { focusMemory["$it$LIST_SUFFIX"] }
                        val target = rows.indexOfFirst { it.id == remembered && it.focusable }.takeIf { it >= 0 }
                            ?: rows.indexOfFirst { it.focusable }
                        if (target >= 0) moveFocusTo(list, target)
                    }
                }
            }

            Region.LIST -> {
                val view = rowViewOf(focused, list) ?: return
                val index = list.getChildAdapterPosition(view)
                if (index < 0) return
                val cols = listColumns.coerceAtLeast(1)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (!stepVertical(list, listAdapter, index, -1, cols)) {
                        if (pills.pillCount > 0) {
                            rightKey?.let { focusMemory["$it$LIST_SUFFIX"] = listAdapter.currentList[index].id }
                            pills.focusPill(null)
                        } else {
                            crossRail(-1)
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> if (!stepVertical(list, listAdapter, index, 1, cols)) crossRail(1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (cols > 1 && index % cols > 0) {
                        moveFocusTo(list, index - 1)
                    } else {
                        focusRail(content?.railKey)
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (cols > 1 && index % cols < cols - 1 && index + 1 < listAdapter.itemCount) {
                        moveFocusTo(list, index + 1)
                    }
                }
            }
        }
    }

    /**
     * 竖着走一格 ([cols] > 1 时是一行): 跳过不能聚焦的说明行. 网格往下没有整行时落到最后一个. 返回 false = 到头了
     * (那一行还没排出来也算走了, 排好时焦点过去, 见 [moveFocusTo]).
     */
    private fun stepVertical(grid: VerticalGridView, adapter: TvSourceRowAdapter, index: Int, direction: Int, cols: Int): Boolean {
        val rows = adapter.currentList
        if (cols > 1) {
            val target = index + direction * cols
            val clamped = when {
                target in rows.indices -> target
                direction > 0 && index / cols < (rows.size - 1) / cols -> rows.size - 1
                else -> return false
            }
            moveFocusTo(grid, clamped)
            return true
        }
        var i = index + direction
        while (i in rows.indices && !rows[i].focusable) i += direction
        if (i !in rows.indices) return false
        moveFocusTo(grid, i)
        return true
    }

    /**
     * 右栏上下走出了这一项的头 / 尾: 跨到左栏紧挨着的那一项, 落在它右栏的第一行 (往下) 或最后一行 (往上). 焦点不回左栏 —— 左栏那一项只是
     * 跟着亮起来、滚到眼前, 焦点在面板上停一下等新的一屏 (同右栏换屏). 本来就没有右栏的 (手动查找、几个操作) 跳过, 跨到下一个有右栏的
     * (如最后一个在线源往下跨到「查询失败」); 跨到的源右边暂时没有能选的 (还在查 / 限流 / 没结果) 就接着往下一个跨 (见 resolvePending).
     * 往这个方向再也没有有右栏的了, 焦点回左栏落在紧挨着的那一项上 (同追番页标签: 内容到头就回标签).
     * 左栏顶上的「筛选」不在这一串里: 它的取值走到头就停, BT 往上走到头停在胶囊上 (不跨进筛选, 也不回左栏落到它上面).
     * 进去的那一层 (BT 胶囊的取值) 与手动查找里不跨. 返回 false = 到头了.
     */
    private fun crossRail(direction: Int): Boolean {
        val content = content ?: return false
        val from = content.railKey ?: return false
        if (!crossable(from) || rightKey != from) return false
        val target = nextCrossable(from, direction)
        if (target == null) {
            val neighbor = railNeighbor(from, direction)?.takeIf { it.id != TvSourceRailKeys.FILTER } ?: return false
            focusRail(neighbor.id)
            return true
        }
        crossing = direction
        followRail(target.id)
        return true
    }

    /** 从 [key] 往 [direction] 走的下一个有右栏的项 (跳过手动查找、几个操作); 没有了为 null. */
    private fun nextCrossable(key: String, direction: Int): TvSourceRow? {
        val rows = railAdapter.currentList
        var i = rows.indexOfFirst { it.id == key }
        if (i < 0) return null
        i += direction
        while (i in rows.indices && !crossable(rows[i].id)) i += direction
        return rows.getOrNull(i)
    }

    private fun railNeighbor(key: String, direction: Int): TvSourceRow? {
        val rows = railAdapter.currentList
        val index = rows.indexOfFirst { it.id == key }
        return if (index < 0) null else rows.getOrNull(index + direction)
    }

    private fun crossable(key: String) =
        key == TvSourceRailKeys.BT || key == TvSourceRailKeys.DOWNLOADS || key.startsWith(TvSourceRailKeys.WEB_PREFIX) ||
            key == TvSourceRailKeys.FAILED

    /** 右栏换成左栏 [key] 那一项, 焦点留在右栏 (不经过左栏): 左栏那一项亮起来、滚到眼前, 新的一屏到了由 submit 那边接着落焦点. */
    private fun followRail(key: String) {
        val index = railAdapter.currentList.indexOfFirst { it.id == key }
        if (index < 0) return
        focusedRailKey = key
        railAdapter.activeId = key
        rail.setSelectedPositionSmooth(index)
        listener?.onRailFocused(key)
    }

    private fun enterRight() {
        val content = content ?: return
        if (!rightMatchesRail(content) || rightKey != incomingRightKey) {
            pendingEnterRight = true
            return
        }
        if (focusRightTarget(content) == FocusResult.Pending) pendingEnterRight = true
    }

    private fun handleConfirm(event: KeyEvent) {
        val focused = findFocus()
        val rowView = focused as? TvSourceRowView
        val row = rowView?.row
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (row == null) return
                if (event.repeatCount == 0) {
                    press = Press(row.id, SystemClock.uptimeMillis())
                    rowView.isPressed = true
                    return
                }
                val p = press ?: return
                val long = longActionOf(row, inRail = regionOf(focused) == Region.RAIL)
                if (!p.fired && p.rowId == row.id && long != null &&
                    SystemClock.uptimeMillis() - p.downAt >= LONG_PRESS_MIN_HOLD.inWholeMilliseconds
                ) {
                    p.fired = true
                    rowView.isPressed = false
                    listener?.onAction(long)
                }
            }

            KeyEvent.ACTION_UP -> {
                val p = press ?: return // 按下不是在这里 (打开面板的那次确定键): 不算
                press = null
                rowView?.isPressed = false
                if (p.fired || row == null || p.rowId != row.id) return
                when (regionOf(focused)) {
                    Region.RAIL -> if (row.action != null) listener?.onAction(row.action) else enterRight()
                    else -> row.action?.let { listener?.onAction(it) }
                }
            }
        }
    }

    /** 长按的动作: 行自己给的; 没给但有详情就开详情弹窗. 右栏的行带上它属于左栏哪一项 (右栏此刻显示的就是 content.railKey 那一屏). */
    private fun longActionOf(row: TvSourceRow, inRail: Boolean): TvSourceAction? =
        row.longAction ?: row.details?.let { TvSourceAction.ShowDetails(row.id, inRail, railKey = if (inRail) null else content?.railKey) }

    /**
     * 把焦点送到一行: 左栏 ([inRail]) 的 [id], 或右栏里左栏 [railKey] 那一项下的 [id] —— 详情弹窗换条时背后的面板跟着走、关掉后落在最后看的那一条.
     * 是左栏另一项下的: 右栏换成那一项 (焦点在右栏就不经过左栏, 见 [followRail]), 新的一屏到了再落到那一行 (落点按这一屏的记忆给).
     * 那一行不在了就不动.
     */
    fun focusRow(railKey: String?, id: String, inRail: Boolean) {
        if (inRail) {
            val index = railAdapter.currentList.indexOfFirst { it.id == id && it.focusable }
            if (index >= 0) moveFocusTo(rail, index)
            return
        }
        val content = content ?: return
        if (railKey == null || (content.railKey == railKey && rightMatchesRail(content) && rightKey == incomingRightKey)) {
            val index = listAdapter.currentList.indexOfFirst { it.id == id && it.focusable }
            if (index >= 0) moveFocusTo(list, index)
            return
        }
        val railIndex = railAdapter.currentList.indexOfFirst { it.id == railKey }
        if (railIndex < 0) return
        focusMemory[railKey] = id
        focusMemory[railKey + LIST_SUFFIX] = id
        pillFallbackScreen = null
        crossing = 0
        if (regionOf(findFocus()).let { it == Region.LIST || it == Region.PILLS }) {
            followRail(railKey)
        } else {
            moveFocusTo(rail, railIndex)
            pendingEnterRight = true
            resolvePending()
        }
    }

    private fun rowViewOf(view: View?, grid: RecyclerView): View? {
        var v: View? = view
        while (v != null && v.parent !== grid) v = v.parent as? View
        return v
    }

    // ---- 触屏 ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 落在面板之外 (画面上): 收起
        if (event.x > panel.contentRight) {
            if (event.actionMasked == MotionEvent.ACTION_UP) listener?.onCloseRequested()
            return true
        }
        return super.onTouchEvent(event)
    }

    // ---- 分支 ----

    private val branchViews: Array<View> get() = arrayOf(drillTitleView, pills, list)
    private var branchAnimator: ValueAnimator? = null

    /**
     * 分支 (右栏) 跟着左栏里它那一项走: 顶边与那一行的顶边对齐, 内容放不下时往上让 (长列表如 BT 就从顶上开始, 撑满整列). 左栏滚动、
     * 两栏排完时调. 右栏刚换了一屏 ([branchEntering]): 直接摆到新位置, 再从左栏那边滑一小段淡入.
     */
    private fun updateBranch() {
        // 那一行此刻不在屏上: 留在原处 (换屏的照样淡入, 不一直藏着)
        val target = branchTargetOffset() ?: if (branchEntering) branchOffset else return
        if (branchEntering) {
            if (listAdapter.itemCount > 0 && list.childCount == 0) return // 行还没排出来
            branchEntering = false
            branchHidden = false
            branchOffset = target
            branchAnimator?.cancel()
            for (view in branchViews) view.translationY = target
            if (!style.animated) {
                for (view in branchViews) {
                    view.alpha = 1f
                    view.translationX = 0f
                }
                return
            }
            branchAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = style.branchFadeMillis
                interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
                addUpdateListener {
                    val f = it.animatedValue as Float
                    for (view in branchViews) {
                        view.alpha = f
                        view.translationX = -style.branchShiftPx * (1f - f)
                    }
                }
                start()
            }
            return
        }
        if (target != branchOffset) {
            branchOffset = target
            for (view in branchViews) view.translationY = target
        }
    }

    /** 分支该往下挪多少; null = 它那一行此刻不在屏上 (滚出去了), 留在原处. */
    private fun branchTargetOffset(): Float? {
        val key = branchRailKey ?: return 0f
        val index = railAdapter.currentList.indexOfFirst { it.id == key }
        if (index < 0) return 0f
        val anchor = rail.findViewHolderForAdapterPosition(index)?.itemView ?: return null
        val available = rail.height - rail.paddingTop - rail.paddingBottom
        val room = (available - branchContentHeight()).coerceAtLeast(0)
        return (anchor.top + anchor.translationY - rail.paddingTop).coerceIn(0f, room.toFloat())
    }

    /** 分支内容的高度 (小标题 + 胶囊 + 列表的行); 列表的行没全排出来 = 比一屏长. */
    private fun branchContentHeight(): Int {
        val s = style
        var height = 0
        if (drillTitleView.visibility != GONE) height += drillTitleView.measuredHeight + s.drillTitleBottomGapPx
        if (pills.pillCount > 0) height += pills.measuredHeight - s.focusBleedPx * 2 + s.pillsBottomGapPx
        val count = listAdapter.itemCount
        if (count > 0) {
            val first = list.findViewHolderForAdapterPosition(0)?.itemView
            val last = list.findViewHolderForAdapterPosition(count - 1)?.itemView
            height += if (first == null || last == null) list.height else last.bottom - first.top
        }
        return height
    }

    // ---- 布局 ----

    /**
     * 面板内容: 左上角是状态卡片, 下面左栏贴着左边距, 分支 (右栏小标题 / 胶囊 / 列表) 排在左栏右边, 基准位置与左栏顶边齐平, 再由
     * [updateBranch] 按 translationY 挪到它那一行旁边. 分支宽度见 [TvSourcePanelStyle.rightSampleTitle] 与 [updateBranchWidth].
     *
     * 裁剪靠本层的 clipChildren: 它让每个子视图只画在自己的框里, 两栏滚上去的行不压到状态卡片上、不越过底边 (框四周留的 focusBleedPx
     * 内边距给聚焦行放大用). 不给列表设 clipBounds: Android 11 (Shield) 上实测不生效.
     */
    private inner class PanelLayout(context: Context) : ViewGroup(context) {
        /** 面板内容的右缘 (分支的右边; 触屏点在它右边 = 点在画面上). */
        var contentRight = 0
            private set

        init {
            clipChildren = true
            clipToPadding = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val s = style
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            // 宽一档的分支也不越过右边距
            val rightWidth = branchWidthPx.coerceAtMost(w - s.paddingStartPx - s.railWidthPx - s.columnGapPx - s.paddingEndPx)
            statusView.measure(MeasureSpec.makeMeasureSpec(s.railWidthPx + s.columnGapPx + rightWidth, MeasureSpec.AT_MOST), unspecified)
            val bleed = s.focusBleedPx
            val top = s.paddingTopPx + statusView.measuredHeight + s.headerGapPx
            val columnHeight = (h - s.paddingBottomPx - top).coerceAtLeast(0)
            rail.measure(
                MeasureSpec.makeMeasureSpec(s.railWidthPx + bleed * 2, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(columnHeight + bleed * 2, MeasureSpec.EXACTLY),
            )
            drillTitleView.measure(MeasureSpec.makeMeasureSpec(rightWidth, MeasureSpec.AT_MOST), unspecified)
            pills.measure(MeasureSpec.makeMeasureSpec(rightWidth + bleed * 2, MeasureSpec.EXACTLY), unspecified)
            var used = 0
            if (drillTitleView.visibility != GONE) used += drillTitleView.measuredHeight + s.drillTitleBottomGapPx
            if (pills.pillCount > 0) used += pills.measuredHeight - bleed * 2 + s.pillsBottomGapPx
            val cols = listColumns.coerceAtLeast(1)
            val columnWidth = ((rightWidth - s.rowGapPx * (cols - 1)) / cols).coerceAtLeast(1)
            if (listColumnWidth != columnWidth) {
                listColumnWidth = columnWidth
                list.setColumnWidth(columnWidth)
            }
            list.measure(
                MeasureSpec.makeMeasureSpec(rightWidth + bleed * 2, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((columnHeight - used).coerceAtLeast(0) + bleed * 2, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val s = style
            val left = s.paddingStartPx
            val bleed = s.focusBleedPx
            statusView.layout(left, s.paddingTopPx, left + statusView.measuredWidth, s.paddingTopPx + statusView.measuredHeight)
            val y = s.paddingTopPx + statusView.measuredHeight + s.headerGapPx
            rail.layout(left - bleed, y - bleed, left - bleed + rail.measuredWidth, y - bleed + rail.measuredHeight)
            val rightLeft = left + s.railWidthPx + s.columnGapPx
            var ry = y
            if (drillTitleView.visibility != GONE) {
                drillTitleView.layout(rightLeft, ry, rightLeft + drillTitleView.measuredWidth, ry + drillTitleView.measuredHeight)
                ry += drillTitleView.measuredHeight + s.drillTitleBottomGapPx
            }
            if (pills.pillCount > 0) {
                pills.layout(rightLeft - bleed, ry - bleed, rightLeft - bleed + pills.measuredWidth, ry - bleed + pills.measuredHeight)
                ry += pills.measuredHeight - bleed * 2 + s.pillsBottomGapPx
            } else {
                pills.layout(rightLeft - bleed, ry - bleed, rightLeft - bleed + pills.measuredWidth, ry - bleed)
            }
            list.layout(rightLeft - bleed, ry - bleed, rightLeft - bleed + list.measuredWidth, ry - bleed + list.measuredHeight)
            contentRight = rightLeft + list.measuredWidth - bleed
            updateBranch()
        }
    }

    private fun createGrid(context: Context): VerticalGridView = VerticalGridView(context).apply {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        itemAnimator = null
        // 聚焦行停在离顶 1/3 处, 两头贴边 (列表短时不滚)
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        windowAlignmentOffsetPercent = 33f
        itemAlignmentOffsetPercent = 50f
        setSmoothScrollByBehavior(TvNativeSpringScroll())
        val bleed = style.focusBleedPx
        setPadding(bleed, bleed, bleed, bleed)
    }

    /** 分隔线: [TvSourceRow.dividerAbove] 的行上方多留一截, 中间画一条细线. */
    private inner class DividerDecoration(private val adapter: TvSourceRowAdapter) : RecyclerView.ItemDecoration() {
        private val paint = Paint()

        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            val row = adapter.currentList.getOrNull(position) ?: return
            if (row.dividerAbove && position > 0) outRect.top = style.dividerGapPx
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            paint.color = style.dividerColor
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val position = parent.getChildAdapterPosition(child)
                val row = adapter.currentList.getOrNull(position) ?: continue
                if (!row.dividerAbove || position <= 0) continue
                val y = child.top + child.translationY - style.dividerGapPx / 2f - style.rowGapPx / 2f
                c.drawRect(child.left.toFloat() + style.rowPaddingHPx, y, child.right.toFloat() - style.rowPaddingHPx, y + 1f, paint)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "TvSourcePanel"

        /** 焦点停在面板自己身上最多多久, 见 [parkWatchdog]. */
        const val PARK_TIMEOUT_MILLIS = 800L

        const val PILL_PREFIX = "pill:"

        /** 右栏列表 (不含胶囊) 上次停的行, 从胶囊按下时落回去. */
        const val LIST_SUFFIX = "#list"
    }
}

/**
 * 一行 (两栏的行、筛选胶囊、剧集方块共用): 自己画圆角底, 自己摆图标、标题、信息、右端字与转圈 —— 不嵌套布局, 列表滚动时测量便宜.
 * 聚焦程度 [focusProgress] 0..1 驱动底色、字色与放大, 失焦比得焦快 (同播放器胶囊).
 */
internal class TvSourceRowView(context: Context, private val sketch: Sketch, var style: TvSourcePanelStyle) : ViewGroup(context) {
    var row: TvSourceRow? = null
        private set

    /** 左栏里右栏正在显示的那一项 (焦点不在它身上时也亮一档). */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyColors()
        }

    private val icon = ImageView(context)
    private val title = TvNativeTextView(context)
    private val meta = TvNativeTextView(context)
    private val trailing = TvNativeTextView(context)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
    private val bgRect = RectF()
    private var loadedIconUrl: String? = null

    private var focusProgress = 0f
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        title.ellipsize = TextUtils.TruncateAt.END
        meta.ellipsize = TextUtils.TruncateAt.END
        meta.maxLines = 1
        trailing.maxLines = 1
        trailing.gravity = Gravity.END
        addView(icon)
        addView(title)
        addView(meta)
        addView(trailing)
        addView(spinner)
    }

    fun bind(row: TvSourceRow, style: TvSourcePanelStyle) {
        val restyled = this.style != style
        this.style = style
        this.row = row
        isFocusable = row.focusable
        isClickable = row.focusable
        when (row.style) {
            TvSourceRowStyle.Rail -> style.railTitle.applyTo(title)
            TvSourceRowStyle.Pill -> style.pill.applyTo(title)
            TvSourceRowStyle.Cell -> style.cell.applyTo(title)
            TvSourceRowStyle.Status -> style.rowMeta.applyTo(title)
            else -> style.rowTitle.applyTo(title)
        }
        style.rowMeta.applyTo(meta)
        style.trailing.applyTo(trailing)
        if (marqueeCapable(row.style)) {
            // 单行 (横向滚得动, 跑马灯要它)
            title.setSingleLine(true)
            meta.setSingleLine(true)
        } else {
            title.setSingleLine(false)
            title.maxLines = when (row.style) {
                TvSourceRowStyle.Resource -> 3
                TvSourceRowStyle.File -> 2
                else -> 6
            }
        }
        title.gravity = if (row.style == TvSourceRowStyle.Cell) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        title.text = row.title
        meta.text = row.meta
        meta.visibility = if (showsMeta(row)) VISIBLE else GONE
        trailing.text = row.trailing
        trailing.visibility = if (row.trailing.isEmpty() || row.loading) GONE else VISIBLE
        spinner.visibility = if (row.loading) VISIBLE else GONE

        icon.visibility = if (showsIcon(row)) VISIBLE else if (reservesIconSlot(row)) INVISIBLE else GONE
        if (row.icon == TvSourceRowIcon.Source && row.iconUrl != null) {
            if (loadedIconUrl != row.iconUrl) {
                loadedIconUrl = row.iconUrl
                icon.colorFilter = null
                TvNativeImages.loadIcon(sketch, icon, row.iconUrl, style.iconSizePx)
            }
        } else {
            if (loadedIconUrl != null) {
                TvNativeImages.clear(icon)
                loadedIconUrl = null
            }
            icon.setImageBitmap(style.icons[row.icon])
        }
        if (restyled) requestLayout()
        applyColors()
        setMarquee(isFocused)
        requestLayout()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animateFocus(if (gainFocus) 1f else 0f)
        setMarquee(gainFocus)
    }

    /** 单行的字 (BT 资源以外) 放不下时, 聚焦一会儿后跑马灯 (TextView 自带的延迟约 1.2 秒); 失焦回到开头省略. */
    private fun setMarquee(on: Boolean) {
        val row = row ?: return
        val allowed = on && style.marqueeRepeat != 0 && marqueeCapable(row.style)
        for (text in arrayOf(title, meta)) {
            if (allowed) {
                text.marqueeRepeatLimit = style.marqueeRepeat
                text.ellipsize = TextUtils.TruncateAt.MARQUEE
                text.isSelected = true
            } else if (text.isSelected || text.ellipsize != TextUtils.TruncateAt.END) {
                text.isSelected = false
                text.ellipsize = TextUtils.TruncateAt.END
            }
        }
    }

    /** 说明行 (整句) 与 BT 资源的标题 (三行) 会折行, 其余的行字都只占一行. */
    private fun marqueeCapable(style: TvSourceRowStyle) =
        style != TvSourceRowStyle.Resource && style != TvSourceRowStyle.File && style != TvSourceRowStyle.Status

    private fun animateFocus(target: Float) {
        animator?.cancel()
        if (!style.animated || !isAttachedToWindow) {
            setFocusProgress(target)
            return
        }
        animator = ValueAnimator.ofFloat(focusProgress, target).apply {
            duration = if (target > focusProgress) style.focusInMillis else style.focusOutMillis
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener { setFocusProgress(it.animatedValue as Float) }
            start()
        }
    }

    private fun setFocusProgress(value: Float) {
        focusProgress = value
        val scale = 1f + (style.focusScale - 1f) * value
        scaleX = scale
        scaleY = scale
        applyColors()
    }

    /** 复用的行换了内容: 聚焦程度按此刻的焦点归位, 不从上一行的中间值接着动. */
    fun resetFocusLook() {
        animator?.cancel()
        setFocusProgress(if (isFocused) 1f else 0f)
    }

    private fun applyColors() {
        val row = row ?: return
        val s = style
        val p = focusProgress
        val dim = if (row.dimmed) s.dimmedAlpha else 1f
        val primary = tvNativeLerpColor(tvNativeWithAlpha(s.text, dim), s.focusedText, p)
        val secondary = tvNativeLerpColor(tvNativeWithAlpha(s.textSecondary, dim), s.focusedTextSecondary, p)
        val error = tvNativeLerpColor(tvNativeWithAlpha(s.error, dim), s.focusedError, p)
        title.setTextColor(if (row.style == TvSourceRowStyle.Status) secondary else primary)
        meta.setTextColor(if (row.metaAccent == TvSourceAccent.Error) error else secondary)
        trailing.setTextColor(
            when (row.accent) {
                TvSourceAccent.Error -> error
                TvSourceAccent.Attention -> primary
                TvSourceAccent.None -> secondary
            },
        )
        if (loadedIconUrl == null) icon.colorFilter = PorterDuffColorFilter(primary, PorterDuff.Mode.SRC_IN)
        spinner.indeterminateTintList = ColorStateList.valueOf(secondary)
        val chosen = row.selected || active
        val rest = if (chosen) s.activeBg else s.idleBg
        bgPaint.color = tvNativeLerpColor(rest, s.focusedBg, p)
        // 拿着焦点的不描边 (左栏换项时新的那一行同时被标成选中, 聚焦动画开头几帧若照选中画, 会先闪一个框再变白);
        // 失焦后还是选中的 (进了分支的左栏那一行) 边随底色变暗淡回来
        borderPaint.color = tvNativeWithAlpha(s.activeBorderColor, if (chosen && !isFocused) 1f - p else 0f)
        borderPaint.strokeWidth = s.activeBorderPx
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val row = row
        val s = style
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        if (row?.style == TvSourceRowStyle.Pill) {
            title.measure(unspecified, unspecified)
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec).takeIf { MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED }
            var w = title.measuredWidth + s.pillPaddingHPx * 2
            if (maxWidth != null && w > maxWidth) {
                w = maxWidth
                title.measure(MeasureSpec.makeMeasureSpec(w - s.pillPaddingHPx * 2, MeasureSpec.EXACTLY), unspecified)
            }
            setMeasuredDimension(w, s.pillHeightPx)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val iconSlot = if (icon.visibility != GONE) s.iconSizePx + s.iconGapPx else 0
        val iconSpec = MeasureSpec.makeMeasureSpec(s.iconSizePx, MeasureSpec.EXACTLY)
        icon.measure(iconSpec, iconSpec)
        val spinnerSpec = MeasureSpec.makeMeasureSpec(s.spinnerSizePx, MeasureSpec.EXACTLY)
        spinner.measure(spinnerSpec, spinnerSpec)
        var trailingWidth = 0
        if (trailing.visibility != GONE) {
            trailing.measure(MeasureSpec.makeMeasureSpec(width / 2, MeasureSpec.AT_MOST), unspecified)
            trailingWidth = trailing.measuredWidth + s.trailingGapPx
        } else if (spinner.visibility != GONE) {
            trailingWidth = s.spinnerSizePx + s.trailingGapPx
        }
        val textWidth = (width - s.rowPaddingHPx * 2 - iconSlot - trailingWidth).coerceAtLeast(0)
        val textSpec = MeasureSpec.makeMeasureSpec(textWidth, if (row?.style == TvSourceRowStyle.Cell) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST)
        title.measure(textSpec, unspecified)
        if (meta.visibility != GONE) meta.measure(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.AT_MOST), unspecified)
        val height = when (row?.style) {
            TvSourceRowStyle.Rail -> s.railRowHeightPx
            TvSourceRowStyle.Line -> s.lineRowHeightPx
            TvSourceRowStyle.Resource -> s.resourceRowHeightPx
            TvSourceRowStyle.File -> {
                val metaHeight = if (meta.visibility != GONE) meta.measuredHeight + s.textGapPx else 0
                max(s.lineRowHeightPx, title.measuredHeight + metaHeight + s.fileRowPaddingVPx * 2)
            }
            TvSourceRowStyle.Option -> s.optionRowHeightPx
            TvSourceRowStyle.Cell -> s.cellHeightPx
            TvSourceRowStyle.Status, null -> title.measuredHeight + s.statusPaddingVPx * 2
            TvSourceRowStyle.Pill -> s.pillHeightPx
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        val w = r - l
        val h = b - t
        if (row?.style == TvSourceRowStyle.Pill) {
            val ty = (h - title.measuredHeight) / 2
            title.layout(s.pillPaddingHPx, ty, s.pillPaddingHPx + title.measuredWidth, ty + title.measuredHeight)
            return
        }
        var x = s.rowPaddingHPx
        if (icon.visibility != GONE) {
            val iy = (h - s.iconSizePx) / 2
            icon.layout(x, iy, x + s.iconSizePx, iy + s.iconSizePx)
            x += s.iconSizePx + s.iconGapPx
        }
        var right = w - s.rowPaddingHPx
        if (trailing.visibility != GONE) {
            val ty = (h - trailing.measuredHeight) / 2
            trailing.layout(right - trailing.measuredWidth, ty, right, ty + trailing.measuredHeight)
            right -= trailing.measuredWidth + s.trailingGapPx
        } else if (spinner.visibility != GONE) {
            val sy = (h - s.spinnerSizePx) / 2
            spinner.layout(right - s.spinnerSizePx, sy, right, sy + s.spinnerSizePx)
            right -= s.spinnerSizePx + s.trailingGapPx
        }
        val metaHeight = if (meta.visibility != GONE) meta.measuredHeight + s.textGapPx else 0
        val blockHeight = title.measuredHeight + metaHeight
        var y = (h - blockHeight) / 2
        val titleLeft = if (row?.style == TvSourceRowStyle.Cell) (w - title.measuredWidth) / 2 else x
        title.layout(titleLeft, y, titleLeft + title.measuredWidth, y + title.measuredHeight)
        y += title.measuredHeight + s.textGapPx
        if (meta.visibility != GONE) meta.layout(x, y, x + meta.measuredWidth, y + meta.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        // 说明行 (正在查询 / 没有结果) 也垫一张玻璃卡片: 背后没有暗底, 与上下的行连成一列
        val corner = if (row?.style == TvSourceRowStyle.Pill) height / 2f else style.cornerPx
        bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bgRect, corner, corner, bgPaint)
        if (borderPaint.alpha > 0 && style.activeBorderPx > 0f) {
            val inset = style.activeBorderPx / 2f
            bgRect.inset(inset, inset)
            canvas.drawRoundRect(bgRect, corner - inset, corner - inset, borderPaint)
        }
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/** 两栏的列表适配器: 按行 id 做差异更新 (后台线程), 行 id 换成稳定的长整数 id. */
internal class TvSourceRowAdapter(
    private val sketch: Sketch,
    var style: TvSourcePanelStyle,
) : ListAdapter<TvSourceRow, TvSourceRowAdapter.Holder>(Diff) {
    var onRowFocused: ((Int, TvSourceRow) -> Unit)? = null
    var onRowClicked: ((TvSourceRow, Boolean) -> Unit)? = null

    /** 左栏里右栏正在显示的那一项. */
    var activeId: String? = null
        set(value) {
            if (field == value) return
            field = value
            refreshActive()
        }

    private var recycler: RecyclerView? = null
    private val stableIds = HashMap<String, Long>()
    private var nextStableId = 1L

    init {
        setHasStableIds(true)
    }

    class Holder(val view: TvSourceRowView) : RecyclerView.ViewHolder(view)

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    override fun getItemId(position: Int): Long = stableIds.getOrPut(getItem(position).id) { nextStableId++ }

    override fun getItemViewType(position: Int): Int = getItem(position).style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = TvSourceRowView(parent.context, sketch, style)
        view.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        val holder = Holder(view)
        // 回调里现取位置与适配器 (同原生卡片墙: 视图会被复用到别的位置)
        view.setOnFocusChangeListener { _, hasFocus ->
            val position = holder.bindingAdapterPosition
            if (hasFocus && position != RecyclerView.NO_POSITION) onRowFocused?.invoke(position, getItem(position))
        }
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onRowClicked?.invoke(getItem(position), false)
        }
        view.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onRowClicked?.invoke(getItem(position), true)
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.view.bind(row, style)
        holder.view.active = row.id == activeId
        holder.view.resetFocusLook()
    }

    private fun refreshActive() {
        val recycler = recycler ?: return
        for (i in 0 until recycler.childCount) {
            val view = recycler.getChildAt(i) as? TvSourceRowView ?: continue
            view.active = view.row?.id == activeId
        }
    }

    private object Diff : DiffUtil.ItemCallback<TvSourceRow>() {
        override fun areItemsTheSame(oldItem: TvSourceRow, newItem: TvSourceRow) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TvSourceRow, newItem: TvSourceRow) = oldItem == newItem
    }
}

/** 右栏顶上那排筛选胶囊: 一行放不下就折到下一行. 左右键按顺序走, 下键进列表 (面板判). */
internal class TvSourcePillFlow(
    context: Context,
    private val sketch: Sketch,
    style: TvSourcePanelStyle,
) : ViewGroup(context) {
    var style: TvSourcePanelStyle = style
        set(value) {
            if (field == value) return
            field = value
            for (i in 0 until childCount) (getChildAt(i) as TvSourceRowView).row?.let { (getChildAt(i) as TvSourceRowView).bind(it, value) }
            requestLayout()
        }

    var onPillFocused: ((TvSourceRow) -> Unit)? = null
    var onPillClicked: ((TvSourceRow) -> Unit)? = null

    val pillCount: Int get() = childCount

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** 换一组胶囊: 按位置复用已有的视图 (同一屏里只是取值变了时, 持焦的那颗原地换字, 焦点不动). */
    fun submit(rows: List<TvSourceRow>) {
        rows.forEachIndexed { index, row ->
            val view = getChildAt(index) as? TvSourceRowView ?: TvSourceRowView(context, sketch, style).also { v ->
                v.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) v.row?.let { onPillFocused?.invoke(it) } }
                v.setOnClickListener { v.row?.let { onPillClicked?.invoke(it) } }
                addView(v)
            }
            if (view.row != row) view.bind(row, style)
        }
        while (childCount > rows.size) removeViewAt(childCount - 1)
        requestLayout()
    }

    fun indexOfPill(view: View?): Int {
        var v: View? = view
        while (v != null && v.parent !== this) v = v.parent as? View
        return if (v == null) -1 else indexOfChild(v)
    }

    fun focusPillAt(index: Int): Boolean = getChildAt(index)?.requestFocus() == true

    /** [id] 那一颗; null 或没有 = 第一颗. */
    fun focusPill(id: String?): Boolean {
        if (childCount == 0) return false
        if (id != null) {
            for (i in 0 until childCount) {
                val view = getChildAt(i) as TvSourceRowView
                if (view.row?.id == id) return view.requestFocus()
            }
        }
        return getChildAt(0).requestFocus()
    }

    // 四周留 focusBleedPx 的内边距 (聚焦放大不被裁, 见 PanelLayout): 量出来的宽高含这圈边, 胶囊排在里面
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bleed = style.focusBleedPx
        val outer = MeasureSpec.getSize(widthMeasureSpec)
        val width = (outer - bleed * 2).coerceAtLeast(0)
        val childSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        var x = 0
        var lines = if (childCount == 0) 0 else 1
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > width) {
                lines++
                x = 0
            }
            x += child.measuredWidth + style.pillGapPx
        }
        val height = if (lines == 0) 0 else lines * style.pillHeightPx + (lines - 1) * style.pillGapPx
        setMeasuredDimension(outer, height + bleed * 2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val bleed = style.focusBleedPx
        val width = r - l - bleed * 2
        var x = 0
        var y = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x > 0 && x + child.measuredWidth > width) {
                x = 0
                y += style.pillHeightPx + style.pillGapPx
            }
            child.layout(bleed + x, bleed + y, bleed + x + child.measuredWidth, bleed + y + child.measuredHeight)
            x += child.measuredWidth + style.pillGapPx
        }
    }
}

private fun showsIcon(row: TvSourceRow) = row.icon != TvSourceRowIcon.None && row.style != TvSourceRowStyle.Pill &&
        row.style != TvSourceRowStyle.Cell && row.style != TvSourceRowStyle.Status

/** 左栏与菜单类的行都留着图标位, 字左缘对齐. */
private fun reservesIconSlot(row: TvSourceRow) =
    row.style == TvSourceRowStyle.Rail || row.style == TvSourceRowStyle.Option || row.style == TvSourceRowStyle.Line ||
            row.style == TvSourceRowStyle.Resource || row.style == TvSourceRowStyle.File

private fun showsMeta(row: TvSourceRow) = row.meta.isNotEmpty() && row.style != TvSourceRowStyle.Rail &&
        row.style != TvSourceRowStyle.Option && row.style != TvSourceRowStyle.Pill && row.style != TvSourceRowStyle.Cell

/**
 * 这一屏的内容要多宽才都放得下: 每一行 (同 [TvSourceRowView] 的摆法: 图标位 + 标题 / 信息里较长的那个 + 右端字), 胶囊排成一排, 小标题;
 * 取最宽的. 会折行的 (说明行、BT 资源) 不算; 多列的 (选集方块) 不按内容, 返回 0 (= 用下限).
 */
private fun measureBranchContentWidth(right: TvSourceRight, style: TvSourcePanelStyle): Int {
    if (right.columns > 1) return 0
    val paints = HashMap<TvNativeTextStyle, Paint>()
    fun width(textStyle: TvNativeTextStyle, text: String): Int =
        if (text.isEmpty()) 0 else ceil(paints.getOrPut(textStyle) { textPaint(textStyle) }.measureText(text)).toInt()

    var result = 0
    for (row in right.rows) {
        if (row.style == TvSourceRowStyle.Status || row.style == TvSourceRowStyle.Resource || row.style == TvSourceRowStyle.File) continue
        val titleStyle = when (row.style) {
            TvSourceRowStyle.Rail -> style.railTitle
            TvSourceRowStyle.Cell -> style.cell
            else -> style.rowTitle
        }
        val iconSlot = if (showsIcon(row) || reservesIconSlot(row)) style.iconSizePx + style.iconGapPx else 0
        val trailing = when {
            row.loading -> style.spinnerSizePx + style.trailingGapPx
            row.trailing.isNotEmpty() -> width(style.trailing, row.trailing) + style.trailingGapPx
            else -> 0
        }
        val text = max(width(titleStyle, row.title), if (showsMeta(row)) width(style.rowMeta, row.meta) else 0)
        result = max(result, style.rowPaddingHPx * 2 + iconSlot + text + trailing)
    }
    if (right.pills.isNotEmpty()) {
        val pills = right.pills.sumOf { width(style.pill, it.title) + style.pillPaddingHPx * 2 } + style.pillGapPx * (right.pills.size - 1)
        result = max(result, pills)
    }
    right.title?.let { result = max(result, width(style.drillTitle, it) + style.chipPaddingHPx * 2) }
    // 量出来的与 TextView 排版的宽度可能差一两个像素的舍入, 差了就会省略 / 跑马灯
    return if (result > 0) result + TEXT_ROUNDING_SLACK_PX else 0
}

private const val TEXT_ROUNDING_SLACK_PX = 2

/** 右栏宽度的下限: 刚好放下「完成验证」那一行 (行首图标位 + 标题 / 说明里较长的那个), 见 [TvSourcePanelStyle.rightSampleTitle]. */
private fun measureRightWidth(style: TvSourcePanelStyle): Int {
    val text = max(measureTextWidth(style.rowTitle, style.rightSampleTitle), measureTextWidth(style.rowMeta, style.rightSampleMeta))
    val width = style.rowPaddingHPx * 2 + style.iconSizePx + style.iconGapPx + ceil(text).toInt() + style.rightSlackPx
    return max(style.rightMinWidthPx, width)
}

private fun measureTextWidth(style: TvNativeTextStyle, text: String): Float = textPaint(style).measureText(text)

private fun textPaint(style: TvNativeTextStyle): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = style.sizePx
    letterSpacing = style.letterSpacingEm
    typeface = tvNativeTypeface(style.weight)
}
