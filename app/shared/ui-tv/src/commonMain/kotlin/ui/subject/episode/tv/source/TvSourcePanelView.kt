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
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
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
import kotlin.math.min

/**
 * 原生选源面板的尺寸 (px) 与配色 (ARGB), 由 rememberTvSourcePanelStyle 按界面缩放与视觉效果档位算好. 配色固定是播放器那一套黑白
 * (与控制层的胶囊同一种玻璃底, 背后不铺暗底): 未聚焦是半透明黑底白字, 聚焦是近白实底黑字 —— 一眼就能找到焦点.
 */
@Immutable
data class TvSourcePanelStyle(
    val paddingStartPx: Int,
    val paddingEndPx: Int,
    val paddingTopPx: Int,
    /** 面板下缘最低到屏幕高度的几成: 下面留给画面与底部字幕, 列表再长就在面板里滚. */
    val maxBottomFraction: Float,
    /** 表头 (状态卡片与命令按钮) 与胶囊行之间的间距. */
    val headerGapPx: Int,
    /** 胶囊行与下面的内容之间的间距. */
    val railGapPx: Int,
    val railPillHeightPx: Int,
    val railPillGapPx: Int,
    /** 胶囊行一颗最宽多少, 名字再长聚焦时跑马灯. */
    val railPillMaxWidthPx: Int,
    /**
     * 渐隐的长度: 胶囊行两头 (滚出去的胶囊在左右边距里渐隐, 不大于 [paddingStartPx] / [paddingEndPx]) 与列表上下 (被面板截住的那一头).
     */
    val fadeLengthPx: Int,
    val rowGapPx: Int,
    val dividerGapPx: Int,
    val dividerColor: Int,
    val cornerPx: Float,
    val rowPaddingHPx: Int,
    val iconSizePx: Int,
    val iconGapPx: Int,
    val trailingGapPx: Int,
    val spinnerSizePx: Int,
    /** 线路 / 资源 / 文件行的最低高度; 行高按内容 (标题折两行时更高). */
    val lineRowHeightPx: Int,
    /** 线路 / 资源 / 文件行上下的留白. */
    val fileRowPaddingVPx: Int,
    /** 标题与靠右的信息 (分辨率、大小、日期…) 之间至少空这么多. */
    val metaGapPx: Int,
    /** 靠右的信息排一行比这宽就断成上下两行 (见 [splitTvSourceMeta]). */
    val metaSplitWidthPx: Int,
    /**
     * 不占满宽度的内容 (线路式的在线源、查询失败、手动查找…, 见 [TvSourceRight.wide]) 按这一屏的行定宽, 不窄于 [branchMinWidthPx] 与它的胶囊、
     * 最宽同占满宽度的 (BT 那么宽), 再放不下的聚焦时跑马灯.
     */
    val branchMinWidthPx: Int,
    val optionRowHeightPx: Int,
    val cellHeightPx: Int,
    val statusPaddingVPx: Int,
    /** 表头的命令按钮与内容顶上的小胶囊的高度. */
    val pillHeightPx: Int,
    val pillPaddingHPx: Int,
    val pillGapPx: Int,
    val pillsBottomGapPx: Int,
    val drillTitleBottomGapPx: Int,
    /** 列表四周多留的一截: 聚焦行放大后不被列表的边界裁掉. */
    val focusBleedPx: Int,
    /** 表头的状态卡片 (「已查询 22 个数据源」) 与内容小标题的字. */
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
     * 选中但没聚焦: 胶囊行里下面正在显示的那个源、正在播放的、当前生效的筛选. 底比未聚焦的深一档再描一圈细白边 ——
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
    /** 状态卡片 / 内容小标题的内边距. */
    val chipPaddingHPx: Int,
    val chipPaddingVPx: Int,
    /** 下面的内容换屏时新内容原地淡入的时长. */
    val branchFadeMillis: Long,
    val focusScale: Float,
    /** 一行放不下的字聚焦一会儿后跑马灯的圈数: 0 = 不跑 (流畅档), -1 = 一直跑. BT 资源的标题不跑 (两行, 全文在详情里). */
    val marqueeRepeat: Int,
    val focusInMillis: Long,
    val focusOutMillis: Long,
    val animated: Boolean,
    val icons: Map<TvSourceRowIcon, Bitmap>,
)

/** 面板报给宿主的事件. 都在主线程. */
interface TvSourcePanelListener {
    /** 胶囊行选中的换成 [key] (下面的内容跟着换). */
    fun onRailFocused(key: String)

    /** 焦点进出下面的内容 (列表与它顶上的小胶囊; 进去时宿主钉住行序, 见 applyFrozenOrder). */
    fun onRightFocusChanged(inRight: Boolean)

    fun onAction(action: TvSourceAction)

    /** 内容里按返回: 宿主退出进去的那一层就返回 true; false = 该关面板了. */
    fun onBackInRight(): Boolean

    /** 要关面板: 触屏点了面板下面 (画面). */
    fun onCloseRequested()
}

/**
 * 选源面板 (原生 View): 铺满播放器, 从屏幕上沿垂下来, 背后不压暗, 只有一张张玻璃卡片. 自上而下三层:
 * - 表头: 左边状态卡片, 右边命令按钮 (筛选、重新搜索、完整搜索、修改搜索词 —— 内容里 rail 的筛选与操作项, 见 [isCommandKey]);
 * - 胶囊行: 数据源横排 (BT 与缓存、下载、各在线源、查询失败、手动查找), 滚出去的在左右边距里渐隐;
 * - 内容: 胶囊行选中的那个源的小标题 / 小胶囊 / 列表. 标题长的 (BT、下载、网盘类, 见 [TvSourceRight.wide]) 占满宽度, 其余按内容定宽、
 *   对齐到它的胶囊下面, 跟着胶囊行走; 面板下缘不低于屏幕高度的 [TvSourcePanelStyle.maxBottomFraction],
 *   列表再长就滚, 被截住的那一头渐隐.
 * 胶囊行与列表都是 leanback 的列表 ([HorizontalGridView] / [VerticalGridView]) —— 它从不回收持焦的那一项, 搜索途中整表更新时焦点不丢.
 *
 * 胶囊行沿用「上面胶囊、下面内容」的选中规则 (同追番页标签、时间表日期行): 单按松手就换下面的内容, 按住左右只挪胶囊, 松手换一次.
 * 列表里按左右 = 换到相邻的数据源 (同样松手才换), 焦点留在列表里, 落在新那一屏的第一行. 选中的胶囊靠左停 (下面的内容跟着靠左,
 * 少挡画面), 到了最后几颗才往右走. 关面板只有返回键 (表头按上不收, 免得往上多按一下就误关). 「筛选」是弹窗 (TvSourceFilterDialog).
 *
 * **方向键全部在这里判, 一个都不放给 Compose**: 视图挂在 Compose 的 AndroidView 里, 没被消费的方向键会交给 Compose 按屏幕位置找下一个焦点
 * (原生列表自己的焦点搜索根本走不到), 会跳到底下被藏起来的播放器控件上. 到边的键也吞掉. 确定键 (短按 / 长按) 同样自己判, 只认见过按下的那一次
 * (打开面板的那次确定键抬起落在这里时不算).
 *
 * 数据走 [submit]: 宿主在内容变化时把整份 [TvSourcePanelContent] 交过来, 胶囊行与列表按行 id 做差异更新 (后台线程算). 下面的内容换了一屏
 * (key 变了) 而焦点在内容里时, 焦点先停在面板自己身上, 新内容排好再交给落点 —— 不停的话持焦的行一被删, 系统会把焦点塞给随便哪个视图.
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
    private val header = TvSourcePillFlow(context, sketch, style, singleLine = true)
    private val rail = createRail(context)
    private val railAdapter = TvSourceRowAdapter(sketch, style, horizontal = true)
    private val drillTitleView = TvNativeTextView(context)
    private val pills = TvSourcePillFlow(context, sketch, style)
    private val list = createList(context)
    private val listAdapter = TvSourceRowAdapter(sketch, style)

    private var content: TvSourcePanelContent? = null

    /** 列表里此刻排着的是哪一屏 (差异更新提交之后才换); [incomingRightKey] 是最新交来的那一屏. */
    private var rightKey: String? = null
    private var incomingRightKey: String? = null

    /** 列表一行几个 (leanback 的网格没有 getter, 记一份), 与此刻设给它的列宽. */
    private var listColumns = 1
    private var listColumnWidth = -1

    /** 每一屏上次聚焦的行 (id; 小胶囊带 `pill:` 前缀): 退出一层、切回某个源时落回原处. */
    private val focusMemory = HashMap<String, String>()

    /** 等内容到了 / 排好了再送的焦点. */
    private var pendingEntry = false
    private var pendingEnterRight = false
    private var pendingRightFocus = false

    private var press: Press? = null
    private var lastInRight = false

    /**
     * 胶囊行此刻选中的那个源: 焦点停的那一颗, 或在列表里按左右挪到的那一颗. 内容里的 railKey 要等换过去 ([commitRail])、状态拼完才跟上.
     */
    private var focusedRailKey: String? = null

    /** [focusedRailKey] 还没换过去: 左右键按住期间只挪胶囊, 松手再换. */
    private var railCommitPending = false

    /** 左右键正按着. */
    private var horizontalHeld = false

    /** 最后一次请宿主换成的那一项 (见 [requestRail]); 交来的内容换到它了就清掉. */
    private var requestedRailKey: String? = null

    /**
     * 这一屏进来时列表还没有能聚焦的行 (还在加载), 落点临时给了小胶囊: 行到了而用户还没动过, 就把焦点挪到行上 ——
     * 不然手动查找进条目时焦点停在「返回结果」上, 一按确定就退回去了. 值是那一屏的 key, 用户一按方向键就作废.
     */
    private var pillFallbackScreen: String? = null

    /** 下面的内容换了一屏、还没排出来: 排好后淡入 (见 [revealBranch]). */
    private var branchEntering = false

    /** 下面的内容此刻藏着 (换屏时先藏, 等新的一屏提交). */
    private var branchHidden = false

    /** 这一屏按内容定的宽度 (见 [updateBranchWidth]); 0 = 占满宽度. [branchWidthKey] = 量的是哪一屏. */
    private var branchFitWidthPx = 0
    private var branchWidthKey: String? = null

    /** 量宽度时这一屏有几行: 行变少了就允许收窄 (见 [updateBranchWidth]). */
    private var branchRowCount = 0

    /** 下面的内容往右挪了多少 (px), 对齐到它的胶囊下面 (见 [updateBranchOffset]). */
    private var branchOffsetX = 0f

    /** 列表上按左右换的源: 新的一屏到了落在第一行 (不按这一屏的记忆、正在播放). */
    private var landFirstRow = false

    /** [moveFocusTo] 时那一项还没排出来: 跳过去之后, 等那一栏排好再把焦点给它 (见 [resolvePendingFocus]). */
    private var pendingFocusGrid: BaseGridView? = null
    private var pendingFocusId: String? = null

    /** 表头上次聚焦的按钮: 从胶囊行按上回到它. */
    private var headerFocusId: String? = null

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
        rail.addItemDecoration(DividerDecoration(railAdapter, horizontal = true))
        list.addItemDecoration(DividerDecoration(listAdapter, horizontal = false))
        railAdapter.onRowFocused = { _, row -> onRailRowFocused(row) }
        listAdapter.onRowFocused = { _, row ->
            rightKey?.let {
                focusMemory[it] = row.id
                focusMemory[it + LIST_SUFFIX] = row.id
            }
            reportRegion()
        }
        pills.onPillFocused = { row -> rightKey?.let { focusMemory[it] = PILL_PREFIX + row.id }; reportRegion() }
        header.onPillFocused = { row ->
            headerFocusId = row.id
            reportRegion()
        }
        // 触屏: 胶囊点一下 = 换到那个源, 表头点一下 = 那个命令, 列表点一下 = 做那一行的动作, 长按 = 长按动作
        railAdapter.onRowClicked = { row, long ->
            val longAction = if (long) longActionOf(row, inRail = true) else null
            if (longAction != null) {
                listener?.onAction(longAction)
            } else if (row.action != null) {
                listener?.onAction(row.action)
            } else {
                followRail(row.id)
            }
        }
        header.onPillClicked = { row -> row.action?.let { listener?.onAction(it) } }
        listAdapter.onRowClicked = { row, long ->
            (if (long) longActionOf(row, inRail = false) ?: row.action else row.action)?.let { listener?.onAction(it) }
        }
        pills.onPillClicked = { row -> row.action?.let { listener?.onAction(it) } }
        rail.addOnLayoutCompletedListener {
            resolvePendingFocus(rail)
            resolvePending()
            // 量这一屏的宽度时胶囊还没排出来: 排出来了再撑到不窄于它
            val anchorWidth = branchAnchor()?.width ?: 0
            if (branchFitWidthPx in 1 until anchorWidth) {
                branchFitWidthPx = anchorWidth
                panel.requestLayout()
            }
            updateBranchOffset()
        }
        list.addOnLayoutCompletedListener {
            resolvePendingFocus(list)
            resolvePending()
            revealBranch()
        }
        // 胶囊行滚动途中下面的内容不跟着动 (换胶囊时会先动一下再停): 新的一屏一出来就摆在停稳后的位置, 停稳时再对一下
        rail.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) onRailSettled()
                }
            },
        )

        // 状态卡片最后加 = 最后画
        panel.addView(list)
        panel.addView(drillTitleView)
        panel.addView(pills)
        panel.addView(rail)
        panel.addView(header)
        panel.addView(statusView)
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyStyle(style)
    }

    fun applyStyle(style: TvSourcePanelStyle) {
        val changed = this.style != style
        this.style = style
        style.status.applyTo(statusView)
        style.drillTitle.applyTo(drillTitleView)
        // 状态卡片与表头的按钮同一排, 同样画成胶囊; 内容小标题会折两行, 用圆角矩形
        statusView.background = chipBackground(style, style.pillHeightPx.toFloat())
        drillTitleView.background = chipBackground(style, style.cornerPx)
        for (chip in arrayOf(statusView, drillTitleView)) {
            chip.setPadding(style.chipPaddingHPx, style.chipPaddingVPx, style.chipPaddingHPx, style.chipPaddingVPx)
        }
        rail.horizontalSpacing = style.railPillGapPx
        rail.setRowHeight(style.railPillHeightPx)
        // 左右的内边距就是渐隐带: 胶囊行伸进左右边距, 停着时第一颗的左缘与下面的列表对齐, 滚出去的在边距里渐隐
        rail.setPadding(style.fadeLengthPx, style.focusBleedPx, style.fadeLengthPx, style.focusBleedPx)
        rail.windowAlignmentOffset = style.fadeLengthPx
        rail.fadingLeftEdgeLength = style.fadeLengthPx
        rail.fadingRightEdgeLength = style.fadeLengthPx
        list.verticalSpacing = style.rowGapPx
        list.horizontalSpacing = style.rowGapPx
        list.setFadingEdgeLength(style.fadeLengthPx)
        list.setPadding(style.focusBleedPx, style.focusBleedPx, style.focusBleedPx, style.focusBleedPx)
        railAdapter.style = style
        listAdapter.style = style
        pills.style = style
        header.style = style
        if (changed) {
            requestLayout()
            invalidate()
        }
    }

    /** 新内容. 见类注释. */
    fun submit(content: TvSourcePanelContent) {
        val previous = this.content
        this.content = content
        if (requestedRailKey == content.railKey) requestedRailKey = null
        statusView.text = content.status

        val (commands, sources) = content.rail.partition { isCommandKey(it.id) }
        header.submit(commands)
        if (!railCommitPending) railAdapter.activeId = content.railKey
        railAdapter.submitList(sources) {
            // 持焦的那颗没了 (查询失败挪进了「查询失败」): 落到选中的那颗
            if (regionOf(findFocus()) == Region.NONE && previous != null && lastRegion == Region.RAIL) {
                focusRail(selectedRailKey())
            }
            // 焦点不在胶囊行 (打开时直接落进列表、在列表里换源): 胶囊行对到选中的那颗, 滚到眼前
            if (regionOf(findFocus()) != Region.RAIL) {
                val key = selectedRailKey()
                val index = sources.indexOfFirst { it.id == key }
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
        if (newScreen) {
            if (style.animated) {
                // 新的一屏排好再淡入 (见 revealBranch): 先藏起来, 不让旧的一屏在新位置上闪一下
                branchAnimator?.cancel()
                for (view in branchViews) view.alpha = 0f
                branchHidden = true
            }
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
        listAdapter.reserveIconSlot = right.rows.any { showsIcon(it) }
        listAdapter.submitList(right.rows) {
            val inRight = regionOf(findFocus()).let { it == Region.LIST || it == Region.PILLS }
            updateBranchWidth(right)
            if (rightKey != right.key) {
                rightKey = right.key
                branchEntering = true
                list.scrollToPosition(0)
            } else if (branchHidden) {
                // 换屏又换回来 (A → B → A): 中间那次提交被差异计算丢掉了, 这一屏还是原来的 key, 照样要淡入
                branchEntering = true
            } else if (!inRight && !isFocused && listAtTop) {
                // 用户没在列表里、列表停在顶上: 搜索途中新结果插到前面时列表会保持原来的首行, 新来的那几条被顶到视口上方看不见 —— 回到顶上.
                // 用户往下翻过 (比如从 BT 中间按上回了胶囊行) 就留在原处, 不然再按下进来, 记住的那一行已经被滚到很远
                list.scrollToPosition(0)
            }
            if (pillFallbackScreen == right.key && regionOf(findFocus()) == Region.PILLS && right.rows.any { it.focusable }) {
                pillFallbackScreen = null
                // 小胶囊得焦时记成了这一屏停的位置, 不清掉的话落点又按记忆回到小胶囊上
                focusMemory.remove(right.key)
                // 行刚提交还没排出来: 排好时 (布局完成回调) 再送
                if (focusRightTarget(content) == FocusResult.Pending) pendingRightFocus = true
            }
            resolvePending()
            revealBranch()
        }
        resolvePending()
    }

    /**
     * 这一屏的宽度 (行提交时): 占满宽度的 ([TvSourceRight.wide]、多列的方块) 为 0; 其余按内容, 不窄于 [TvSourcePanelStyle.branchMinWidthPx]
     * 与它的胶囊 (看着像从胶囊垂下来), 最宽到占满宽度 (排版时按屏宽截). 同一屏里行只多不少时只放宽不收窄 —— 查询途中结果陆续到,
     * 框不来回伸缩; 行变少了 (收起「其余 N 条」) 或换了一屏就重新量.
     */
    private fun updateBranchWidth(right: TvSourceRight) {
        val width = if (right.wide || right.columns > 1) {
            0
        } else {
            val anchorWidth = branchAnchor()?.width
                ?: railAdapter.currentList.firstOrNull { it.id == content?.railKey }?.let { railPillWidth(it) } ?: 0
            val min = max(style.branchMinWidthPx, anchorWidth)
            val measured = measureBranchContentWidth(right, style).coerceAtLeast(min)
            val growing = branchWidthKey == right.key && right.rows.size >= branchRowCount
            if (growing && branchFitWidthPx > 0) max(branchFitWidthPx, measured) else measured
        }
        branchWidthKey = right.key
        branchRowCount = right.rows.size
        if (width != branchFitWidthPx) {
            branchFitWidthPx = width
            panel.requestLayout()
        }
    }

    /**
     * 不占满宽度的内容对齐到它的胶囊下面 (左缘对齐, 放不下往左让, 见 [branchAnchor]). 胶囊行滚动、两边排完时调.
     * 那一颗此刻不在屏上 (滚出去了) 就留在原处.
     */
    private fun updateBranchOffset() {
        // 胶囊行正在滚: 等停稳 (见 onRailSettled)
        if (rail.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val target = branchTargetOffset() ?: return
        if (target != branchOffsetX) {
            branchOffsetX = target
            applyBranchX()
        }
    }

    /** 胶囊行停稳了: 还没出来的那一屏在胶囊下面淡入; 已经出来的对不上它的胶囊 (估的位置差了一点、挪了又挪回来) 就滑过去. */
    private fun onRailSettled() {
        if (branchEntering) {
            revealBranch()
            return
        }
        val target = branchTargetOffset() ?: return
        if (target == branchOffsetX) return
        branchOffsetAnimator?.cancel()
        if (!style.animated) {
            branchOffsetX = target
            applyBranchX()
            return
        }
        branchOffsetAnimator = ValueAnimator.ofFloat(branchOffsetX, target).apply {
            duration = style.branchFadeMillis
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener {
                branchOffsetX = it.animatedValue as Float
                applyBranchX()
            }
            start()
        }
    }

    private var branchOffsetAnimator: ValueAnimator? = null

    /** 下面的内容对齐的那一颗: 胶囊行里 content.railKey 那一颗; 此刻没排出来为 null. */
    private fun branchAnchor(): View? {
        val key = content?.railKey ?: return null
        val index = railAdapter.currentList.indexOfFirst { it.id == key }
        if (index < 0) return null
        return rail.findViewHolderForAdapterPosition(index)?.itemView
    }

    /**
     * 对齐到它的胶囊下面该往右挪多少; [settled] = 按胶囊行停稳以后那一颗的位置算 (还在滚的时候用, 见 [railSettledLeft]),
     * 否则按它此刻的位置. 那一颗此刻没排出来 (按此刻的位置算时) 为 null.
     */
    private fun branchTargetOffset(settled: Boolean = false): Float? {
        val fit = branchFitWidthPx
        if (fit <= 0) return 0f
        val anchorLeft = if (settled) {
            val index = railAdapter.currentList.indexOfFirst { it.id == content?.railKey }
            if (index < 0) return 0f
            railSettledLeft(index)
        } else {
            val anchor = branchAnchor() ?: return null
            (anchor.parent as View).left + anchor.left + anchor.translationX
        }
        val room = (panel.width - style.paddingStartPx - style.paddingEndPx - fit).coerceAtLeast(0)
        return (anchorLeft - style.paddingStartPx).coerceIn(0f, room.toFloat())
    }

    /**
     * 胶囊行滚完以后 [index] 那一颗的左缘 (面板坐标). 选中的胶囊停在左内边距处的对齐线上 (见 createRail), 到头的不再滚 ——
     * 开头几颗留在原处, 末尾几颗右边贴边. 宽度用排出来的胶囊的, 没排出来的按字量 (见 [railPillWidth]).
     */
    private fun railSettledLeft(index: Int): Float {
        val rows = railAdapter.currentList
        fun width(i: Int) = rail.findViewHolderForAdapterPosition(i)?.itemView?.width ?: railPillWidth(rows[i])
        // 第 i 颗与前一颗之间: 间距, 带分隔线的多一截 (见 DividerDecoration)
        fun lead(i: Int) = style.railPillGapPx + if (rows[i].dividerAbove) style.dividerGapPx else 0
        var before = 0
        for (i in 0 until index) before += width(i) + lead(i + 1)
        var total = before + width(index)
        for (i in index + 1 until rows.size) total += lead(i) + width(i)
        val visible = rail.width - rail.paddingLeft - rail.paddingRight
        val scroll = before.coerceIn(0, max(0, total - visible))
        return (rail.left + rail.paddingLeft + before - scroll).toFloat()
    }

    /** 胶囊行停稳以后 [key] 那一颗的左缘 (面板坐标, 见 [railSettledLeft]); 设备测试核对估的位置用. */
    @VisibleForTesting
    internal fun settledPillLeft(key: String): Float? =
        railAdapter.currentList.indexOfFirst { it.id == key }.takeIf { it >= 0 }?.let { railSettledLeft(it) }

    /** 胶囊的宽度按字量 (同 [TvSourceRowView] 按内容定宽: 图标 + 名字 + 右端的数 / 转圈, 不超过 [TvSourcePanelStyle.railPillMaxWidthPx]). */
    private fun railPillWidth(row: TvSourceRow): Int {
        val s = style
        val paints = railPaints?.takeIf { it.first == s } ?: (s to (textPaint(s.railTitle) to textPaint(s.trailing))).also { railPaints = it }
        val (titlePaint, trailingPaint) = paints.second
        var width = s.rowPaddingHPx * 2 + s.iconSizePx + s.iconGapPx + ceil(titlePaint.measureText(row.title)).toInt()
        if (row.loading) {
            width += s.spinnerSizePx + s.trailingGapPx
        } else if (row.trailing.isNotEmpty()) {
            width += ceil(trailingPaint.measureText(row.trailing)).toInt() + s.trailingGapPx
        }
        return min(width, s.railPillMaxWidthPx)
    }

    private var railPaints: Pair<TvSourcePanelStyle, Pair<Paint, Paint>>? = null

    private fun applyBranchX() {
        for (view in branchViews) view.translationX = branchOffsetX
    }

    /**
     * 打开面板 / 焦点不在面板里时: 落进列表 (正在播的那一行, 见 [focusRightTarget]); 列表里没有能选的行就落胶囊行选中的那颗.
     * 内容没到就等内容到了再送.
     */
    fun requestEntryFocus() {
        pendingEntry = true
        resolvePending()
    }

    /**
     * 返回键. true = 面板自己处理了 (进去的那一层 / 手动查找的条目页); false = 该关面板了 —— 宿主记下焦点停在哪
     * ([focusPosition]), 再打开时落回去.
     */
    fun handleBack(): Boolean {
        return when (regionOf(findFocus())) {
            Region.LIST, Region.PILLS -> listener?.onBackInRight() == true
            else -> false
        }
    }

    /** 焦点在列表 (那个源本身那一屏, 不是进去的那一层) 的哪一行: (源, 行 id); 不在列表里为 null. */
    fun rightRowPosition(): Pair<String, String>? {
        val focused = findFocus() as? TvSourceRowView ?: return null
        if (regionOf(focused) != Region.LIST) return null
        val railKey = content?.railKey ?: return null
        if (rightKey != railKey) return null
        return railKey to (focused.row?.id ?: return null)
    }

    /**
     * 焦点停在哪: (源, 列表那一行的 id); 在胶囊行与内容顶上的小胶囊上时行 id 为 null (那个源本身). 在表头、进去的那一层里、不在面板里为 null.
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

    /**
     * 打开时要落回的位置 (上次按返回隐藏时停的, 见 [focusPosition]): (源, 行 id), 行 id 为 null = 那个源的胶囊.
     * 落过一次 (或那一行没了) 就作废, 照常落.
     */
    var resumeRow: Pair<String, String?>? = null

    /** 面板里有没有焦点 (根路由据此判断要不要先把焦点送进来). */
    fun hasPanelFocus(): Boolean = hasFocus()

    // ---- 焦点 ----

    private enum class Region { NONE, HEADER, RAIL, PILLS, LIST }

    private var lastRegion = Region.NONE

    private fun regionOf(view: View?): Region = when {
        view == null -> Region.NONE
        tvNativeIsInside(view, header) -> Region.HEADER
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
        focusedRailKey = row.id
        landFirstRow = false
        reportRegion()
        railCommitPending = true
        if (!horizontalHeld) commitRail()
    }

    /** 把胶囊行选中的那颗 ([focusedRailKey]) 换过去: 下面的内容跟着换. 左右键按着时等松手再调. */
    private fun commitRail() {
        if (!railCommitPending) return
        railCommitPending = false
        val key = focusedRailKey ?: return
        railAdapter.activeId = key
        // 不用换 (挪了又挪回原来那颗): 不会有新的一屏, 「落第一行」作废
        if (!requestRail(key)) landFirstRow = false
    }

    /**
     * 请宿主把内容换成 [key]. 跟「最后要过的」比, 不跟此刻的内容比: 快速右、左两下时第一次要的内容还没到, 按此刻的内容 (还是原来那个)
     * 会把换回来的那一下当成不用换, 结果胶囊停在原来那颗、下面换成了右边那个.
     */
    private fun requestRail(key: String): Boolean {
        if ((requestedRailKey ?: content?.railKey) == key) return false
        requestedRailKey = key
        listener?.onRailFocused(key)
        return true
    }

    /** 胶囊行选中的那个源: 还没换过去的那颗, 否则是内容里的. */
    private fun selectedRailKey(): String? = if (railCommitPending) focusedRailKey else content?.railKey

    private fun resolvePending() {
        val content = content ?: return
        if (pendingRightFocus) {
            // 新的一屏还没提交到列表: 等提交回调
            if (rightKey != incomingRightKey) return
            when (focusRightTarget(content)) {
                FocusResult.Focused -> {
                    pendingRightFocus = false
                    unpark()
                    // 焦点一直在内容里 (没经过胶囊行), 宿主没收到「进内容」: 给新的一屏钉住行序
                    listener?.onRightFocusChanged(true)
                }

                FocusResult.Pending -> {} // 落点还没排出来: 等排好 (布局完成回调)
                FocusResult.None -> {
                    // 新的一屏没有能聚焦的 (还在查 / 限流 / 没结果): 回胶囊行那个源上
                    pendingRightFocus = false
                    unpark()
                    focusRail(selectedRailKey())
                }
            }
            return
        }
        if (pendingEnterRight) {
            // 胶囊行刚换了源, 列表还是上一个的内容: 等对得上再进
            if (!rightMatchesRail(content) || rightKey != incomingRightKey) return
            if (focusRightTarget(content) != FocusResult.Pending) pendingEnterRight = false
            return
        }
        if (pendingEntry && isAttachedToWindow) {
            if (railAdapter.currentList.isEmpty() && content.rail.any { !isCommandKey(it.id) }) return // 胶囊行还没提交
            val resume = resumeRow
            if (resume != null && content.railKey == resume.first) {
                val rowId = resume.second
                if (rowId == null) {
                    resumeRow = null
                    enterRail(resume.first)
                    return
                }
                // 等这个源的那一屏提交、排出来
                if (rightKey != incomingRightKey || !rightMatchesRail(content)) return
                val index = listAdapter.currentList.indexOfFirst { it.id == rowId && it.focusable }
                if (index >= 0) {
                    if (moveFocusTo(list, index)) {
                        resumeRow = null
                        pendingEntry = false
                    }
                    return
                }
            }
            resumeRow = null
            if (content.railKey == null) {
                enterRail(content.railKey)
                return
            }
            if (rightKey != incomingRightKey || !rightMatchesRail(content)) return
            when (focusRightTarget(content)) {
                FocusResult.Focused -> pendingEntry = false
                FocusResult.Pending -> {}
                FocusResult.None -> enterRail(content.railKey)
            }
        }
    }

    /** 打开时落胶囊行 [key] 那颗 (胶囊行是空的就落表头). 那一颗还没排出来时等排好再给 (见 [moveFocusTo]), 这次打开的落点就算定了. */
    private fun enterRail(key: String?) {
        pendingEntry = false
        if (railAdapter.itemCount == 0) header.focusPill(null) else focusRail(key)
    }

    private enum class FocusResult { Focused, Pending, None }

    private fun rightMatchesRail(content: TvSourcePanelContent): Boolean {
        val railKey = content.railKey ?: return false
        // 用户刚在胶囊行换了源, 内容还是上一个的
        if (focusedRailKey != null && focusedRailKey != railKey) return false
        val key = rightKey ?: return false
        return key == railKey || key.startsWith("$railKey/")
    }

    /**
     * 进内容的落点: 这一屏上次停的 → 内容给的落点 (正在播放 / 当前值) → 第一条能聚焦的行 → 第一个小胶囊.
     * [FocusResult.Pending] = 落点那一行还没排出来 (已选中, 排到时 leanback 给焦点; 调用方等布局完成再试).
     */
    private fun focusRightTarget(content: TvSourcePanelContent): FocusResult {
        val right = content.right
        if (landFirstRow) {
            // 列表上按左右换过来的: 落第一行 (没有能选的行交给调用方, 回胶囊行那颗). 快速连按时中途那几屏也落第一行,
            // 等最后要的那一屏到了 (requestedRailKey 清掉) 才算落完
            val first = listAdapter.currentList.indexOfFirst { it.focusable }
            if (first < 0) {
                if (requestedRailKey == null) landFirstRow = false
                return FocusResult.None
            }
            if (!moveFocusTo(list, first)) return FocusResult.Pending
            if (requestedRailKey == null) landFirstRow = false
            return FocusResult.Focused
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
     * 排出来了当场给 (leanback 按窗口对齐平滑滚过去). 没排出来 (远处的那一项、刚换的一屏): 直接跳到那个位置, 排好时 (布局完成回调,
     * [resolvePendingFocus]) 再把焦点给它, 返回 false. 不平滑滚过去: 平滑滚动不触发布局完成回调, 而栏里没有焦点时 leanback 滚到了也不给焦点 ——
     * 焦点就一直等不到, 要再按一次才过去. 也不对整栏 requestFocus: 换屏途中它会把焦点给马上要被移走的旧行, 旧行一移走焦点就丢了.
     */
    private fun moveFocusTo(grid: BaseGridView, index: Int): Boolean {
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

    private fun resolvePendingFocus(grid: BaseGridView) {
        if (pendingFocusGrid !== grid) return
        val id = pendingFocusId
        pendingFocusGrid = null
        pendingFocusId = null
        val adapter = if (grid === rail) railAdapter else listAdapter
        val index = adapter.currentList.indexOfFirst { it.id == id && it.focusable }
        if (index < 0) return
        grid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
    }

    /** 焦点暂停在面板自己身上 (下面的内容换屏期间). */
    private fun park() {
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        isFocusable = true
        requestFocus()
        removeCallbacks(parkWatchdog)
        postDelayed(parkWatchdog, PARK_TIMEOUT_MILLIS)
    }

    /**
     * 停在面板自己身上太久 (新的一屏 / 落点一直没等到): 停着的时候方向键全不理, 用户看到的就是「焦点没了、按什么都没反应」.
     * 保险: 放弃这次落点, 焦点回胶囊行选中的那颗. 正常流程到不了这里 (新的一屏几十毫秒就到), 到了就记一笔.
     */
    private val parkWatchdog = Runnable {
        if (!isFocused) return@Runnable
        Log.w(
            LOG_TAG,
            "focus parked for ${PARK_TIMEOUT_MILLIS}ms, releasing: pendingRightFocus=$pendingRightFocus " +
                "rightKey=$rightKey incomingRightKey=$incomingRightKey railKey=${content?.railKey} pendingFocusId=$pendingFocusId",
        )
        pendingRightFocus = false
        landFirstRow = false
        focusRail(selectedRailKey())
        unpark()
    }

    private fun unpark() {
        removeCallbacks(parkWatchdog)
        if (!isFocusable) return
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        val focusedSelf = isFocused
        isFocusable = false
        if (focusedSelf && !hasFocus()) {
            // 落点没给出去: 回胶囊行, 不让焦点空着
            focusRail(selectedRailKey())
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
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> horizontalHeld = true
                KeyEvent.ACTION_UP -> {
                    horizontalHeld = false
                    commitRail()
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_DOWN && railCommitPending) {
            // 按着左右时按了别的键: 先把胶囊行选中的换过去, 这一下不动 (下面的内容马上要换)
            commitRail()
            return true
        }
        if (dpad) {
            if (event.action == KeyEvent.ACTION_DOWN) navigate(code)
            return true
        }
        handleConfirm(event)
        return true
    }

    private fun navigate(code: Int) {
        // 焦点正停在面板自己身上 (下面的内容换屏中): 落点马上会给出去, 这一下不动
        if (isFocused) return
        val focused = findFocus()
        val region = regionOf(focused)
        if (region == Region.NONE) {
            requestEntryFocus()
            return
        }
        // 用户动过了: 打开时还没落定的落点作废, 不再把焦点拽回去
        pendingEntry = false
        pillFallbackScreen = null
        pendingFocusGrid = null
        pendingFocusId = null
        when (region) {
            Region.NONE -> {}
            Region.HEADER -> {
                val index = header.indexOfPill(focused)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) header.focusPillAt(index - 1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (index < header.pillCount - 1) header.focusPillAt(index + 1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusRail(selectedRailKey())
                }
            }

            Region.RAIL -> {
                val index = rail.getChildAdapterPosition(rowViewOf(focused, rail) ?: return)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) moveFocusTo(rail, index - 1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (index < railAdapter.itemCount - 1) moveFocusTo(rail, index + 1)
                    KeyEvent.KEYCODE_DPAD_UP -> header.focusPill(headerFocusId)
                    KeyEvent.KEYCODE_DPAD_DOWN -> enterRight()
                }
            }

            Region.PILLS -> {
                val index = pills.indexOfPill(focused)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> leaveContentUp()
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) pills.focusPillAt(index - 1)
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
                    KeyEvent.KEYCODE_DPAD_UP -> if (!stepVertical(index, -1, cols)) {
                        if (pills.pillCount > 0) {
                            rightKey?.let { focusMemory["$it$LIST_SUFFIX"] = listAdapter.currentList[index].id }
                            pills.focusPill(null)
                        } else {
                            leaveContentUp()
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> stepVertical(index, 1, cols)
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (cols > 1 && index % cols > 0) {
                        moveFocusTo(list, index - 1)
                    } else {
                        switchSource(-1)
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (cols > 1 && index % cols < cols - 1 && index + 1 < listAdapter.itemCount) {
                        moveFocusTo(list, index + 1)
                    } else {
                        switchSource(1)
                    }
                }
            }
        }
    }

    /**
     * 列表里竖着走一格 ([cols] > 1 时是一行): 跳过不能聚焦的说明行. 网格往下没有整行时落到最后一个. 返回 false = 到头了
     * (那一行还没排出来也算走了, 排好时焦点过去, 见 [moveFocusTo]).
     */
    private fun stepVertical(index: Int, direction: Int, cols: Int): Boolean {
        val rows = listAdapter.currentList
        if (cols > 1) {
            val target = index + direction * cols
            val clamped = when {
                target in rows.indices -> target
                direction > 0 && index / cols < (rows.size - 1) / cols -> rows.size - 1
                else -> return false
            }
            moveFocusTo(list, clamped)
            return true
        }
        var i = index + direction
        while (i in rows.indices && !rows[i].focusable) i += direction
        if (i !in rows.indices) return false
        moveFocusTo(list, i)
        return true
    }

    /** 从内容的最上面往上走: 回胶囊行选中的那颗. */
    private fun leaveContentUp() {
        focusRail(selectedRailKey())
    }

    /**
     * 列表里按左右: 胶囊行选中的挪到相邻的数据源, 焦点留在列表上, 松手时换过去 ([commitRail]); 新的一屏到了焦点落在第一行
     * ([landFirstRow]), 没有能选的行就回胶囊行那颗. 进去的那一层 (BT 小胶囊的取值、手动查找的换源 / 换线路 / 条目页) 里不换.
     */
    private fun switchSource(direction: Int) {
        val content = content ?: return
        val railKey = content.railKey ?: return
        val key = rightKey ?: return
        if (key != railKey && !key.startsWith(TvSourceRailKeys.MANUAL + "/search/")) return
        val rows = railAdapter.currentList
        val from = rows.indexOfFirst { it.id == (if (railCommitPending) focusedRailKey else railKey) }
        if (from < 0) return
        val target = rows.getOrNull(from + direction) ?: return
        focusedRailKey = target.id
        railCommitPending = true
        landFirstRow = true
        railAdapter.activeId = target.id
        rail.setSelectedPositionSmooth(from + direction)
        if (!horizontalHeld) commitRail()
    }

    /** 胶囊行换到 [key] (焦点不动): 那一颗亮起来、滚到眼前, 下面的内容当场换. */
    private fun followRail(key: String) {
        val index = railAdapter.currentList.indexOfFirst { it.id == key }
        if (index < 0) return
        focusedRailKey = key
        railCommitPending = true
        rail.setSelectedPositionSmooth(index)
        commitRail()
    }

    private fun enterRight() {
        commitRail()
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

    /** 长按的动作: 行自己给的; 没给但有详情就开详情弹窗. 列表的行带上它属于哪个源 (列表此刻显示的就是 content.railKey 那一屏). */
    private fun longActionOf(row: TvSourceRow, inRail: Boolean): TvSourceAction? =
        row.longAction ?: row.details?.let { TvSourceAction.ShowDetails(row.id, inRail, railKey = if (inRail) null else content?.railKey) }

    /**
     * 把焦点送到一行: 胶囊行 ([inRail]) 的 [id], 或源 [railKey] 下面列表里的 [id] —— 详情弹窗换条时背后的面板跟着走、关掉后落在最后看的那一条.
     * 是另一个源下的: 胶囊行换过去 (焦点在内容里就留在内容里, 见 [followRail]), 新的一屏到了再落到那一行 (落点按这一屏的记忆给).
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
        // 落在面板下面 (画面上): 收起
        if (event.y > panel.contentBottom) {
            if (event.actionMasked == MotionEvent.ACTION_UP) listener?.onCloseRequested()
            return true
        }
        return super.onTouchEvent(event)
    }

    // ---- 换屏 ----

    private val branchViews: Array<View> get() = arrayOf(drillTitleView, pills, list)
    private var branchAnimator: ValueAnimator? = null

    /**
     * 下面的内容换了一屏 ([branchEntering]) 且排出来了: 摆到它的胶囊下面 (胶囊行还在滚就按停稳后的位置) 原地淡入 —— 不滑、不等胶囊行停,
     * 一出来就在最后的位置 (滑一小段、跟着胶囊行走都看着像条目动了一下才停).
     */
    private fun revealBranch() {
        if (!branchEntering) return
        if (listAdapter.itemCount > 0 && list.childCount == 0) return // 行还没排出来
        branchOffsetAnimator?.cancel()
        branchEntering = false
        branchHidden = false
        branchAnimator?.cancel()
        // 胶囊行还在滚: 直接摆到那一颗停稳以后的位置下面 (不等它停, 也不跟着它动); 停稳时对不上的再轻轻对齐 (见 onRailSettled)
        val scrolling = rail.scrollState != RecyclerView.SCROLL_STATE_IDLE
        (branchTargetOffset(settled = scrolling) ?: branchTargetOffset(settled = true))?.let {
            branchOffsetX = it
            applyBranchX()
        }
        if (!style.animated) {
            for (view in branchViews) view.alpha = 1f
            return
        }
        branchAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = style.branchFadeMillis
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener {
                val f = it.animatedValue as Float
                for (view in branchViews) view.alpha = f
            }
            start()
        }
    }

    // ---- 布局 ----

    /**
     * 面板内容, 自上而下: 表头 (状态卡片靠左、命令按钮靠右), 胶囊行 (占满左右边距之间), 下面的内容 (小标题 / 小胶囊 / 列表: 占满宽度,
     * 或按内容定宽、由 [updateBranchOffset] 挪到它的胶囊下面);
     * 列表的下缘在屏幕高度的 [TvSourcePanelStyle.maxBottomFraction] 处.
     *
     * 裁剪靠本层的 clipChildren: 它让每个子视图只画在自己的框里, 列表滚上去的行不压到胶囊行上 (框四周留的 focusBleedPx 内边距给聚焦行放大用,
     * 胶囊行左右的内边距是渐隐带). 不给列表设 clipBounds: Android 11 (Shield) 上实测不生效.
     */
    private inner class PanelLayout(context: Context) : ViewGroup(context) {
        /** 面板内容的下缘 (列表的底边; 触屏点在它下面 = 点在画面上). */
        var contentBottom = 0
            private set

        init {
            clipChildren = true
            clipToPadding = false
        }

        private fun headerHeight() = max(statusView.measuredHeight, style.pillHeightPx)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val s = style
            val bleed = s.focusBleedPx
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val contentWidth = (w - s.paddingStartPx - s.paddingEndPx).coerceAtLeast(0)
            header.measure(MeasureSpec.makeMeasureSpec(contentWidth + bleed * 2, MeasureSpec.AT_MOST), unspecified)
            val headerWidth = if (header.pillCount == 0) 0 else header.measuredWidth - bleed * 2 + s.headerGapPx
            statusView.measure(MeasureSpec.makeMeasureSpec((contentWidth - headerWidth).coerceAtLeast(0), MeasureSpec.AT_MOST), unspecified)
            rail.measure(
                MeasureSpec.makeMeasureSpec(contentWidth + s.fadeLengthPx * 2, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(s.railPillHeightPx + bleed * 2, MeasureSpec.EXACTLY),
            )
            // 下面的内容: 占满宽度, 或按内容定宽 (对齐到胶囊下面靠 translationX, 见 updateBranchOffset)
            val branchWidth = if (branchFitWidthPx > 0) min(branchFitWidthPx, contentWidth) else contentWidth
            drillTitleView.measure(MeasureSpec.makeMeasureSpec(branchWidth, MeasureSpec.AT_MOST), unspecified)
            pills.measure(MeasureSpec.makeMeasureSpec(branchWidth + bleed * 2, MeasureSpec.EXACTLY), unspecified)
            var top = s.paddingTopPx + headerHeight() + s.headerGapPx + s.railPillHeightPx + s.railGapPx
            if (drillTitleView.visibility != GONE) top += drillTitleView.measuredHeight + s.drillTitleBottomGapPx
            if (pills.pillCount > 0) top += pills.measuredHeight - bleed * 2 + s.pillsBottomGapPx
            val cols = listColumns.coerceAtLeast(1)
            val columnWidth = ((branchWidth - s.rowGapPx * (cols - 1)) / cols).coerceAtLeast(1)
            if (listColumnWidth != columnWidth) {
                listColumnWidth = columnWidth
                list.setColumnWidth(columnWidth)
            }
            val bottom = (h * s.maxBottomFraction).toInt()
            list.measure(
                MeasureSpec.makeMeasureSpec(branchWidth + bleed * 2, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((bottom - top).coerceAtLeast(0) + bleed * 2, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val s = style
            val left = s.paddingStartPx
            val right = r - l - s.paddingEndPx
            val bleed = s.focusBleedPx
            var y = s.paddingTopPx
            val headerHeight = headerHeight()
            val statusTop = y + (headerHeight - statusView.measuredHeight) / 2
            statusView.layout(left, statusTop, left + statusView.measuredWidth, statusTop + statusView.measuredHeight)
            val headerTop = y + (headerHeight - s.pillHeightPx) / 2 - bleed
            header.layout(right + bleed - header.measuredWidth, headerTop, right + bleed, headerTop + header.measuredHeight)
            y += headerHeight + s.headerGapPx
            rail.layout(left - s.fadeLengthPx, y - bleed, left - s.fadeLengthPx + rail.measuredWidth, y - bleed + rail.measuredHeight)
            y += s.railPillHeightPx + s.railGapPx
            if (drillTitleView.visibility != GONE) {
                drillTitleView.layout(left, y, left + drillTitleView.measuredWidth, y + drillTitleView.measuredHeight)
                y += drillTitleView.measuredHeight + s.drillTitleBottomGapPx
            }
            if (pills.pillCount > 0) {
                pills.layout(left - bleed, y - bleed, left - bleed + pills.measuredWidth, y - bleed + pills.measuredHeight)
                y += pills.measuredHeight - bleed * 2 + s.pillsBottomGapPx
            } else {
                pills.layout(left - bleed, y - bleed, left - bleed + pills.measuredWidth, y - bleed)
            }
            list.layout(left - bleed, y - bleed, left - bleed + list.measuredWidth, y - bleed + list.measuredHeight)
            contentBottom = list.bottom - bleed
            updateBranchOffset()
            revealBranch()
        }
    }

    private fun createRail(context: Context): HorizontalGridView = HorizontalGridView(context).apply {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        itemAnimator = null
        // 选中的那颗靠左停 (对齐线在左内边距处, 见 applyStyle), 下面对齐在它下面的内容也就靠左、少挡画面; 两头贴边: 最后几颗不再往左滚,
        // 选中的往右走
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        windowAlignmentOffsetPercent = 0f
        itemAlignmentOffsetPercent = 0f
        setSmoothScrollByBehavior(TvNativeSpringScroll())
        fadingLeftEdge = true
        fadingRightEdge = true
    }

    private fun createList(context: Context): VerticalGridView = TvSourceListGridView(context).apply {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        itemAnimator = null
        // 聚焦行停在离顶 1/3 处, 两头贴边 (列表短时不滚)
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        windowAlignmentOffsetPercent = 33f
        itemAlignmentOffsetPercent = 50f
        setSmoothScrollByBehavior(TvNativeSpringScroll())
        isVerticalFadingEdgeEnabled = true
    }

    /** 分隔线: [TvSourceRow.dividerAbove] 的那一项前面多留一截, 中间画一条细线 (列表里横线, 胶囊行里竖线). */
    private inner class DividerDecoration(
        private val adapter: TvSourceRowAdapter,
        private val horizontal: Boolean,
    ) : RecyclerView.ItemDecoration() {
        private val paint = Paint()

        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            val row = adapter.currentList.getOrNull(position) ?: return
            if (!row.dividerAbove || position <= 0) return
            if (horizontal) outRect.left = style.dividerGapPx else outRect.top = style.dividerGapPx
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            paint.color = style.dividerColor
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val position = parent.getChildAdapterPosition(child)
                val row = adapter.currentList.getOrNull(position) ?: continue
                if (!row.dividerAbove || position <= 0) continue
                if (horizontal) {
                    val x = child.left + child.translationX - style.dividerGapPx / 2f - style.railPillGapPx / 2f
                    val half = child.height * DIVIDER_HEIGHT_FRACTION / 2f
                    val cy = (child.top + child.bottom) / 2f
                    c.drawRect(x, cy - half, x + 1f, cy + half, paint)
                } else {
                    val y = child.top + child.translationY - style.dividerGapPx / 2f - style.rowGapPx / 2f
                    c.drawRect(child.left.toFloat() + style.rowPaddingHPx, y, child.right.toFloat() - style.rowPaddingHPx, y + 1f, paint)
                }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "TvSourcePanel"

        /** 焦点停在面板自己身上最多多久, 见 [parkWatchdog]. */
        const val PARK_TIMEOUT_MILLIS = 800L

        const val PILL_PREFIX = "pill:"

        /** 列表 (不含小胶囊) 上次停的行, 从小胶囊按下时落回去. */
        const val LIST_SUFFIX = "#list"

        /** 胶囊行里竖分隔线占胶囊高的几成. */
        const val DIVIDER_HEIGHT_FRACTION = 0.55f
    }
}

/** 内容里 rail 的这一项在表头 (命令按钮), 不在胶囊行: 「筛选」与几个操作. */
internal fun isCommandKey(key: String): Boolean = key == TvSourceRailKeys.FILTER || key.startsWith(TvSourceRailKeys.ACTION_PREFIX)

private fun chipBackground(style: TvSourcePanelStyle, corner: Float) = GradientDrawable().apply {
    setColor(style.idleBg)
    cornerRadius = corner
}

/**
 * 列表: 比面板留给它的高度长时, 被截住的那一头渐隐 (看得出还能往那边走, 也不留一条看不见的裁切线). leanback 的竖列表没有渐隐, 借 View 自带的
 * 渐隐边: 强度按头尾那一行露没露全自己算 (leanback 的布局管理器不报滚动范围), 渐隐带画到列表的外框上 (内边距是给聚焦放大留的那一圈).
 */
private class TvSourceListGridView(context: Context) : VerticalGridView(context) {
    override fun getTopFadingEdgeStrength(): Float {
        if (childCount == 0) return 0f
        val first = findViewHolderForAdapterPosition(0)?.itemView ?: return 1f
        return if (first.top + first.translationY < paddingTop - 1) 1f else 0f
    }

    override fun getBottomFadingEdgeStrength(): Float {
        val count = adapter?.itemCount ?: 0
        if (count == 0 || childCount == 0) return 0f
        val last = findViewHolderForAdapterPosition(count - 1)?.itemView ?: return 1f
        return if (last.bottom + last.translationY > height - paddingBottom + 1) 1f else 0f
    }

    override fun isPaddingOffsetRequired(): Boolean = true
    override fun getLeftPaddingOffset(): Int = -paddingLeft
    override fun getRightPaddingOffset(): Int = paddingRight
    override fun getTopPaddingOffset(): Int = -paddingTop
    override fun getBottomPaddingOffset(): Int = paddingBottom
}

/**
 * 一行 (胶囊行的源、表头的按钮、列表的行、小胶囊、剧集方块共用): 自己画圆角底, 自己摆图标、标题、信息、右端字与转圈 —— 不嵌套布局, 列表滚动时测量便宜.
 * 聚焦程度 [focusProgress] 0..1 驱动底色、字色与放大, 失焦比得焦快 (同播放器胶囊).
 *
 * 胶囊行与表头的 [TvSourceRowStyle.Rail] 行宽度按内容 (宽度不是 EXACTLY 时: 图标 + 名字 + 右端字, 不超过给的上限 / [TvSourcePanelStyle.railPillMaxWidthPx]),
 * 高度是 EXACTLY 时用给的.
 */
internal class TvSourceRowView(context: Context, private val sketch: Sketch, var style: TvSourcePanelStyle) : ViewGroup(context) {
    var row: TvSourceRow? = null
        private set

    /** 胶囊行里下面正在显示的那个源 (焦点不在它身上时也亮一档). */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyColors()
        }

    /** 没有图标的行留不留图标位 (见 TvSourceRowAdapter.reserveIconSlot). */
    var reserveIconSlot: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            row?.let {
                applyIconVisibility(it)
                requestLayout()
            }
        }

    private fun applyIconVisibility(row: TvSourceRow) {
        icon.visibility = if (showsIcon(row)) VISIBLE else if (reserveIconSlot && reservesIconSlot(row)) INVISIBLE else GONE
    }

    /** 画成胶囊 (圆角 = 高的一半): 胶囊行与表头的按钮. */
    var capsule: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
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
        meta.gravity = Gravity.END
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
            meta.setSingleLine(false)
            title.maxLines = when (row.style) {
                TvSourceRowStyle.Resource, TvSourceRowStyle.File -> 2
                else -> 6
            }
        }
        title.gravity = if (row.style == TvSourceRowStyle.Cell) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        title.text = row.title
        val metaText = if (marqueeCapable(row.style)) row.meta else splitMeta(row.meta)
        meta.maxLines = if ('\n' in metaText) 2 else 1
        meta.text = metaText
        meta.visibility = if (showsMeta(row)) VISIBLE else GONE
        trailing.text = row.trailing
        trailing.visibility = if (row.trailing.isEmpty() || row.loading) GONE else VISIBLE
        spinner.visibility = if (row.loading) VISIBLE else GONE

        applyIconVisibility(row)
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

    private fun splitMeta(text: String): String = splitTvSourceMeta(text, meta.paint, style.metaSplitWidthPx)

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

    /** 说明行 (整句) 与资源 / 文件的标题 (两行) 会折行, 其余的行字都只占一行. */
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
        // 占满宽度的行按比例放大会越过四周留的出血边、圆角被裁掉: 放大后多出来的不超过出血边
        val maxScale = if (width > 0) min(style.focusScale, 1f + style.focusBleedPx * 2f / width) else style.focusScale
        val scale = 1f + (maxScale - 1f) * value
        scaleX = scale
        scaleY = scale
        applyColors()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 放大的上限按宽度算 (见 setFocusProgress)
        setFocusProgress(focusProgress)
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
        // 拿着焦点的不描边 (胶囊行换源时新的那一颗同时被标成选中, 聚焦动画开头几帧若照选中画, 会先闪一个框再变白);
        // 失焦后还是选中的 (进了列表时胶囊行那一颗) 边随底色变暗淡回来
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
        val iconSlot = if (icon.visibility != GONE) s.iconSizePx + s.iconGapPx else 0
        val iconSpec = MeasureSpec.makeMeasureSpec(s.iconSizePx, MeasureSpec.EXACTLY)
        icon.measure(iconSpec, iconSpec)
        val spinnerSpec = MeasureSpec.makeMeasureSpec(s.spinnerSizePx, MeasureSpec.EXACTLY)
        spinner.measure(spinnerSpec, spinnerSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val width = if (row?.style == TvSourceRowStyle.Rail && widthMode != MeasureSpec.EXACTLY) {
            // 按内容: 名字与右端字都排得下的宽度
            title.measure(unspecified, unspecified)
            var natural = s.rowPaddingHPx * 2 + iconSlot + title.measuredWidth
            if (trailing.visibility != GONE) {
                trailing.measure(unspecified, unspecified)
                natural += trailing.measuredWidth + s.trailingGapPx
            } else if (spinner.visibility != GONE) {
                natural += s.spinnerSizePx + s.trailingGapPx
            }
            min(natural, if (widthMode == MeasureSpec.AT_MOST) MeasureSpec.getSize(widthMeasureSpec) else s.railPillMaxWidthPx)
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        var trailingWidth = 0
        if (trailing.visibility != GONE) {
            trailing.measure(MeasureSpec.makeMeasureSpec(width / 2, MeasureSpec.AT_MOST), unspecified)
            trailingWidth = trailing.measuredWidth + s.trailingGapPx
        } else if (spinner.visibility != GONE) {
            trailingWidth = s.spinnerSizePx + s.trailingGapPx
        }
        var textWidth = (width - s.rowPaddingHPx * 2 - iconSlot - trailingWidth).coerceAtLeast(0)
        if (meta.visibility != GONE) {
            // 信息靠右: 标题用不完的都可以给它, 标题长时至少也有字区的一小半; 标题用剩下的
            title.measure(unspecified, unspecified)
            val metaCap = max((textWidth * META_MIN_FRACTION).toInt(), textWidth - title.measuredWidth - s.metaGapPx)
            meta.measure(MeasureSpec.makeMeasureSpec(metaCap.coerceAtLeast(0), MeasureSpec.AT_MOST), unspecified)
            textWidth = (textWidth - meta.measuredWidth - s.metaGapPx).coerceAtLeast(0)
        }
        val textSpec = MeasureSpec.makeMeasureSpec(textWidth, if (row?.style == TvSourceRowStyle.Cell) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST)
        title.measure(textSpec, unspecified)
        val height = when (row?.style) {
            TvSourceRowStyle.Rail -> if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                MeasureSpec.getSize(heightMeasureSpec)
            } else {
                s.railPillHeightPx
            }
            TvSourceRowStyle.Line, TvSourceRowStyle.Resource, TvSourceRowStyle.File -> {
                val metaHeight = if (meta.visibility != GONE) meta.measuredHeight else 0
                max(s.lineRowHeightPx, max(title.measuredHeight, metaHeight) + s.fileRowPaddingVPx * 2)
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
        if (meta.visibility != GONE) {
            val my = (h - meta.measuredHeight) / 2
            meta.layout(right - meta.measuredWidth, my, right, my + meta.measuredHeight)
        }
        val ty = (h - title.measuredHeight) / 2
        val titleLeft = if (row?.style == TvSourceRowStyle.Cell) (w - title.measuredWidth) / 2 else x
        title.layout(titleLeft, ty, titleLeft + title.measuredWidth, ty + title.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        // 说明行 (正在查询 / 没有结果) 也垫一张玻璃卡片: 背后没有暗底, 与上下的行连成一列
        val corner = if (capsule || row?.style == TvSourceRowStyle.Pill) height / 2f else style.cornerPx
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

/**
 * 胶囊行与列表的适配器: 按行 id 做差异更新 (后台线程), 行 id 换成稳定的长整数 id. [horizontal] = 胶囊行 (行按内容定宽、画成胶囊).
 */
internal class TvSourceRowAdapter(
    private val sketch: Sketch,
    var style: TvSourcePanelStyle,
    private val horizontal: Boolean = false,
) : ListAdapter<TvSourceRow, TvSourceRowAdapter.Holder>(Diff) {
    var onRowFocused: ((Int, TvSourceRow) -> Unit)? = null
    var onRowClicked: ((TvSourceRow, Boolean) -> Unit)? = null

    /** 胶囊行里下面正在显示的那个源. */
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
        view.layoutParams = if (horizontal) {
            RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.MATCH_PARENT)
        } else {
            RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        }
        view.capsule = horizontal
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

    /** 这一屏有没有带图标的行: 有才给没图标的行留图标位 (字的左缘对齐), 都没有就不留 (短的列表不白占一截). */
    var reserveIconSlot: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            val recycler = recycler ?: return
            for (i in 0 until recycler.childCount) (recycler.getChildAt(i) as? TvSourceRowView)?.reserveIconSlot = value
        }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.view.reserveIconSlot = reserveIconSlot
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

/**
 * 一排胶囊 (方向键由面板判): 内容顶上的小胶囊 (一行放不下就折到下一行), 或 [singleLine] 的表头命令按钮 (排一行, 宽度按内容).
 * 胶囊的高度都是 [TvSourcePanelStyle.pillHeightPx].
 */
internal class TvSourcePillFlow(
    context: Context,
    private val sketch: Sketch,
    style: TvSourcePanelStyle,
    private val singleLine: Boolean = false,
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
                v.capsule = true
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
        val heightSpec = MeasureSpec.makeMeasureSpec(style.pillHeightPx, MeasureSpec.EXACTLY)
        if (singleLine) {
            var used = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(MeasureSpec.makeMeasureSpec((width - used).coerceAtLeast(0), MeasureSpec.AT_MOST), heightSpec)
                used += child.measuredWidth + style.pillGapPx
            }
            if (childCount == 0) setMeasuredDimension(0, 0) else setMeasuredDimension(used - style.pillGapPx + bleed * 2, style.pillHeightPx + bleed * 2)
            return
        }
        val childSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        var x = 0
        var lines = if (childCount == 0) 0 else 1
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(childSpec, heightSpec)
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
            if (!singleLine && x > 0 && x + child.measuredWidth > width) {
                x = 0
                y += style.pillHeightPx + style.pillGapPx
            }
            child.layout(bleed + x, bleed + y, bleed + x + child.measuredWidth, bleed + y + child.measuredHeight)
            x += child.measuredWidth + style.pillGapPx
        }
    }
}

/** 标题长时靠右的信息至少能占一行字区 (去掉图标与右端字) 的几成. */
private const val META_MIN_FRACTION = 0.45f

/** 信息里各项之间的分隔 (模型拼信息时用的同一个), 断两行时在它这里断. */
private const val META_SEPARATOR = " · "

/**
 * 靠右的信息一行比 [splitWidthPx] 宽时, 在某个「 · 」处断成上下两行 (两行里较宽的那行尽量窄): 字小、两行叠起来比一长行省横向地方,
 * 标题能多占一截. 只有一段 (一句提示) 或不够宽的原样.
 */
internal fun splitTvSourceMeta(text: String, paint: Paint, splitWidthPx: Int): String {
    val parts = text.split(META_SEPARATOR)
    if (parts.size < 2) return text
    if (paint.measureText(text) <= splitWidthPx) return text
    fun line(from: Int, to: Int) = parts.subList(from, to).joinToString(META_SEPARATOR)
    val k = (1 until parts.size).minBy { k -> max(paint.measureText(line(0, k)), paint.measureText(line(k, parts.size))) }
    return line(0, k) + "\n" + line(k, parts.size)
}

/**
 * 不占满宽度的那一屏要多宽: 每一行 (同 [TvSourceRowView] 的摆法: 图标位 + 标题 + 靠右的信息 (断两行的按较宽那行) + 右端字), 小胶囊排成一排,
 * 小标题; 取最宽的. 会折行的说明行不算.
 */
private fun measureBranchContentWidth(right: TvSourceRight, style: TvSourcePanelStyle): Int {
    val paints = HashMap<TvNativeTextStyle, Paint>()
    fun paint(textStyle: TvNativeTextStyle) = paints.getOrPut(textStyle) { textPaint(textStyle) }
    fun width(textStyle: TvNativeTextStyle, text: String): Int =
        if (text.isEmpty()) 0 else text.lines().maxOf { ceil(paint(textStyle).measureText(it)).toInt() }

    var result = 0
    val reserveIcon = right.rows.any { showsIcon(it) }
    for (row in right.rows) {
        if (row.style == TvSourceRowStyle.Status) continue
        val titleStyle = when (row.style) {
            TvSourceRowStyle.Rail -> style.railTitle
            TvSourceRowStyle.Cell -> style.cell
            else -> style.rowTitle
        }
        val iconSlot = if (showsIcon(row) || (reserveIcon && reservesIconSlot(row))) style.iconSizePx + style.iconGapPx else 0
        val trailing = when {
            row.loading -> style.spinnerSizePx + style.trailingGapPx
            row.trailing.isNotEmpty() -> width(style.trailing, row.trailing) + style.trailingGapPx
            else -> 0
        }
        val meta = if (showsMeta(row)) {
            width(style.rowMeta, splitTvSourceMeta(row.meta, paint(style.rowMeta), style.metaSplitWidthPx)) + style.metaGapPx
        } else {
            0
        }
        result = max(result, style.rowPaddingHPx * 2 + iconSlot + width(titleStyle, row.title) + meta + trailing)
    }
    if (right.pills.isNotEmpty()) {
        val pills = right.pills.sumOf { width(style.pill, it.title) + style.pillPaddingHPx * 2 } + style.pillGapPx * (right.pills.size - 1)
        result = max(result, pills)
    }
    right.title?.let { result = max(result, width(style.drillTitle, it) + style.chipPaddingHPx * 2) }
    // 量出来的与 TextView 排版的宽度可能差一两个像素的舍入, 差了就会省略 / 跑马灯
    return result + TEXT_ROUNDING_SLACK_PX
}

private const val TEXT_ROUNDING_SLACK_PX = 2

private fun textPaint(style: TvNativeTextStyle): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = style.sizePx
    letterSpacing = style.letterSpacingEm
    typeface = tvNativeTypeface(style.weight)
}

private fun showsIcon(row: TvSourceRow) = row.icon != TvSourceRowIcon.None && row.style != TvSourceRowStyle.Pill &&
        row.style != TvSourceRowStyle.Cell && row.style != TvSourceRowStyle.Status

/** 胶囊行与菜单类的行都留着图标位, 字左缘对齐. */
private fun reservesIconSlot(row: TvSourceRow) =
    row.style == TvSourceRowStyle.Rail || row.style == TvSourceRowStyle.Option || row.style == TvSourceRowStyle.Line ||
            row.style == TvSourceRowStyle.Resource || row.style == TvSourceRowStyle.File

private fun showsMeta(row: TvSourceRow) = row.meta.isNotEmpty() && row.style != TvSourceRowStyle.Rail &&
        row.style != TvSourceRowStyle.Option && row.style != TvSourceRowStyle.Pill && row.style != TvSourceRowStyle.Cell
