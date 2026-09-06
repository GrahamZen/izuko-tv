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
import kotlin.math.abs
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
    /** 面板下缘离画面底部的距离: 源那一栏与列表都到这里, 再长就在面板里滚. */
    val paddingBottomPx: Int,
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
     * 不占满宽度的内容 (线路式的在线源、查询失败、手动查找…, 见 [TvSourceRight.wide]) 按这一屏的行定宽, 不窄于 [branchMinWidthPx],
     * 最宽同占满宽度的 (BT 那么宽), 再放不下的聚焦时跑马灯.
     */
    val branchMinWidthPx: Int,
    /** 源那一栏 (在线源的组里竖排各个源) 的宽度与行高, 与右边内容之间的间距. */
    val columnWidthPx: Int,
    val columnRowHeightPx: Int,
    val columnGapPx: Int,
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
     * 选中但没聚焦: 胶囊行 / 源那一栏里下面正在显示的那一项、当前生效的筛选. 底比未聚焦的深一档再描一圈细白边 ——
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

    /** 源那一栏选中的换成 [groupKey] 这一组的 [sourceKey] (右边的内容跟着换). */
    fun onSourceFocused(groupKey: String, sourceKey: String)

    /**
     * 焦点停到胶囊 [railKey] / 源那一栏的 [sourceKey] 上, 而内容本来就是它 (打开时按落点规则落上来的, 不用换): 宿主把它记成选中的 ——
     * 不记的话别的组先出了结果、落点规则换了答案, 下面换成别的组, 焦点还停在这颗.
     */
    fun onSelectionHeld(railKey: String, sourceKey: String?) {}

    /** 焦点进出下面的内容 (列表与它顶上的小胶囊; 进去时宿主钉住行序, 见 applyFrozenOrder). */
    fun onRightFocusChanged(inRight: Boolean)

    fun onAction(action: TvSourceAction)

    /** 内容里按返回: 宿主退出进去的那一层就返回 true; false = 该关面板了. */
    fun onBackInRight(): Boolean

    /** 要关面板: 触屏点了面板下面 (画面). */
    fun onCloseRequested()
}

/**
 * 选源面板 (原生 View): 铺满播放器, 从屏幕上沿垂下来, 背后不压暗, 只有一张张玻璃卡片. 自上而下:
 * - 表头: 左边状态卡片与「正在播放」(确定跳到在播的那一行), 右边命令按钮 (筛选、完整搜索; 重新搜索、修改搜索词只画图标, 排在最后 ——
 *   内容里 rail 的筛选与操作项, 见 [isCommandKey]). 打开面板先停在「正在播放」, 没有在播的落胶囊行选中的那一组;
 * - 胶囊行: 下载、BT 与缓存、在线源的各组 (网盘 / 在线 / 直链…)、查询失败、手动查找;
 * - 内容: 选中的是有几个源的组时, 左边贴边距一栏竖排组里的源 ([TvSourcePanelContent.sources]), 选中的源的条目排在它右边, 顶边对着那个源那一行
 *   (放不下往上让, 最多到这一栏的顶上); 其余的 (BT、下载、只有一个源的组…) 小标题 / 小胶囊 / 列表直接在胶囊行下面. 标题长的 (BT、下载、网盘类,
 *   见 [TvSourceRight.wide]) 占满宽度, 其余按内容定宽. 面板下到画面底部 (留 [TvSourcePanelStyle.paddingBottomPx]), 再长就滚, 被截住的那一头渐隐.
 * 胶囊行、源那一栏与列表都是 leanback 的列表 ([HorizontalGridView] / [VerticalGridView]) —— 它从不回收持焦的那一项, 搜索途中整表更新时焦点不丢.
 *
 * 胶囊行沿用「上面胶囊、下面内容」的选中规则 (同追番页标签、时间表日期行): 单按松手就换下面的内容, 按住左右只挪胶囊, 松手换一次. 源那一栏同理
 * (按住上下只挪高亮, 松手换右边的条目). 有这一栏时列表里按左回到这一栏, 上下走到头跨到这一栏里相邻的有条目的源 (紧接着按反方向回到原来那一行).
 * 左右走到头 (源那一栏按左、条目按右、源进不去时按右、小胶囊到头、没有源那一栏的列表按左右) 换到胶囊行相邻的那一项: 进去过就回到上次离开时
 * 停的地方 (源那一栏那个源或条目那一行, 不在了落第一个), 没去过或从胶囊上离开的停在胶囊上 (按住连走只挪胶囊, 松手才落);
 * 胶囊上按下回到内容里上次停的地方.
 * 焦点在表头上时胶囊行下面都收着; 在分类胶囊上展开那一项 (焦点留在胶囊上): 有源那一栏的只出这一栏, 右边的条目进去了才出.
 * 选中的胶囊靠左停, 到了最后几颗才往右走.
 * 关面板只有返回键 (表头按上不收, 免得往上多按一下就误关).
 * 「筛选」是弹窗 (TvSourceFilterDialog).
 *
 * **方向键全部在这里判, 一个都不放给 Compose**: 视图挂在 Compose 的 AndroidView 里, 没被消费的方向键会交给 Compose 按屏幕位置找下一个焦点
 * (原生列表自己的焦点搜索根本走不到), 会跳到底下被藏起来的播放器控件上. 到边的键也吞掉. 确定键 (短按 / 长按) 同样自己判, 只认见过按下的那一次
 * (打开面板的那次确定键抬起落在这里时不算).
 *
 * 数据走 [submit]: 宿主在内容变化时把整份 [TvSourcePanelContent] 交过来, 各栏按行 id 做差异更新 (后台线程算). 下面的内容换了一屏
 * (key 变了) 而焦点在内容里时, 焦点先停在面板自己身上, 新内容排好再交给落点 —— 不停的话持焦的行一被删, 系统会把焦点塞给随便哪个视图.
 */
@SuppressLint("ViewConstructor")
class TvSourcePanelView(
    context: Context,
    private var style: TvSourcePanelStyle,
    private val sketch: Sketch,
) : FrameLayout(context) {
    var listener: TvSourcePanelListener? = null

    /**
     * 面板正在退场: 吞掉全部按键 (焦点交还播放器之前那一两帧里, 确定键会点中淡出中的行). 抬起也吞掉, 所以开始退场时把按着的状态清掉,
     * 淡出途中又打开时不带着上一次没等到抬起的按住.
     */
    var leaving: Boolean = false
        set(value) {
            if (value && !field) {
                horizontalHeld = false
                verticalHeld = false
                groupCross = null
            }
            field = value
        }

    private val panel = PanelLayout(context)
    private val statusView = TvNativeTextView(context)

    /** 表头左边状态卡片后面的「正在播放」(见 [TvSourceNowPlaying]); 没有在播的就是空的. */
    private val nowPlayingView = TvSourcePillFlow(context, sketch, style, singleLine = true)
    private val header = TvSourcePillFlow(context, sketch, style, singleLine = true)
    private val rail = createRail(context)
    private val railAdapter = TvSourceRowAdapter(sketch, style, horizontal = true)
    private val column = createVerticalList(context)
    private val columnAdapter = TvSourceRowAdapter(sketch, style)
    private val drillTitleView = TvNativeTextView(context)
    private val pills = TvSourcePillFlow(context, sketch, style)
    private val list = createVerticalList(context)
    private val listAdapter = TvSourceRowAdapter(sketch, style)

    private var content: TvSourcePanelContent? = null

    /** 列表里此刻排着的是哪一屏 (差异更新提交之后才换); [incomingRightKey] 是最新交来的那一屏. */
    private var rightKey: String? = null
    private var incomingRightKey: String? = null

    /** 源那一栏此刻排着的是哪一组 (提交之后才换; 没有这一栏为 null); [incomingColumnKey] 是最新交来的. */
    private var columnKey: String? = null
    private var incomingColumnKey: String? = null

    /** 列表一行几个 (leanback 的网格没有 getter, 记一份), 与此刻设给它的列宽. */
    private var listColumns = 1
    private var listColumnWidth = -1

    /** 每一屏上次聚焦的行 (id; 小胶囊带 `pill:` 前缀): 退出一层、切回某个源时落回原处. */
    private val focusMemory = HashMap<String, String>()

    /** 等内容到了 / 排好了再送的焦点. */
    private var pendingEntry = false
    private var pendingEnterContent = false
    private var pendingEnterRight = false

    /** 这次进内容是从胶囊上按下来的 ([enterFromRail]): 列表顶上有小胶囊时先落它 (见 [focusRightTarget]). */
    private var enterFromAbove = false
    private var pendingRightFocus = false

    private var press: Press? = null
    private var lastInRight = false

    /**
     * 胶囊行此刻选中的那一项: 焦点停的那一颗, 或在列表里按左右挪到的那一颗. 内容里的 railKey 要等换过去 ([commitRail])、状态拼完才跟上.
     */
    private var focusedRailKey: String? = null

    /** [focusedRailKey] 还没换过去: 左右键按住期间只挪胶囊, 松手再换. */
    private var railCommitPending = false

    /** 左右键正按着. */
    private var horizontalHeld = false

    /** 正在处理的这一发方向键是连发 (按住不放的第二发起), 见 [moveToGroup]. */
    private var keyRepeating = false

    /** 最后一次请宿主换成的那一项 (见 [requestRail]); 交来的内容换到它了就清掉. */
    private var requestedRailKey: String? = null

    /** 源那一栏此刻选中的源 (焦点停的那一行); 同 [focusedRailKey], 换组时清掉. */
    private var focusedSourceKey: String? = null

    /** [focusedSourceKey] 还没换过去: 上下键按住期间只挪高亮, 松手再换. */
    private var sourceCommitPending = false

    /** 上下键正按着. */
    private var verticalHeld = false

    /** 最后一次请宿主换成的源 (见 [requestSource]). */
    private var requestedSourceKey: String? = null

    /**
     * 这一屏进来时列表还没有能聚焦的行 (还在加载), 落点临时给了小胶囊: 行到了而用户还没动过, 就把焦点挪到行上 ——
     * 不然手动查找进条目时焦点停在「返回结果」上, 一按确定就退回去了. 值是那一屏的 key, 用户一按方向键就作废.
     */
    private var pillFallbackScreen: String? = null

    /** 下面的内容换了一屏、还没排出来: 排好后淡入 (见 [revealBranch]). */
    private var branchEntering = false

    /** 下面的内容此刻藏着 (换屏时先藏, 等新的一屏提交). */
    private var branchHidden = false

    /** 源那一栏换了一组、还没排出来: 排好后淡入 (见 [revealColumn]). */
    private var columnEntering = false

    /** 源那一栏换组时先藏了 (透明度 0), 还没淡入 (同 [branchHidden]: 换过去又换回来时回来的那一版据此照样淡入). */
    private var columnHidden = false

    /** 这一屏按内容定的宽度 (见 [updateBranchWidth]); 0 = 占满宽度. [branchWidthKey] = 量的是哪一屏. */
    private var branchFitWidthPx = 0
    private var branchWidthKey: String? = null

    /** 量宽度时这一屏有几行: 行变少了就允许收窄 (见 [updateBranchWidth]). */
    private var branchRowCount = 0

    /** 有源那一栏时右边的内容往下挪了多少 (px, 顶边对着那个源那一行, 见 [branchTargetOffset]); 排版时算. */
    private var branchOffsetY = 0

    /** 条目上下走到头跨到了相邻的源 (见 [crossSource]): +1 = 落新一屏的第一行, -1 = 落最后一行; 0 = 没在跨. */
    private var crossing = 0

    /**
     * 刚刚那一下跨出去了 (见 [moveToGroup] / [crossSource]): 停在 [arrival] 里紧接着按 [reverseKey] 就回去 —— 横着跨到胶囊上的回 [position]
     * 那一项离开时停的地方 (见 [groupMemory]), 竖着跨到相邻的源的回 [position] 那一行. 按别的键作废.
     */
    private class ReturnPoint(val reverseKey: Int, val arrival: Region, val position: TvSourceFocusPosition)

    private var returnPoint: ReturnPoint? = null

    /**
     * 各项 (胶囊行的一颗) 焦点最后停的地方: 它的胶囊 (源与行 id 都为 null), 源那一栏那个源, 或条目那一行 (BT 顶上的小胶囊行 id 带
     * [PILL_PREFIX]). 左右换到这一项时回到这里 (见 [moveToGroup]): 离开前停在哪就回哪, 从胶囊上离开的回胶囊; 没去过的停在胶囊上.
     */
    private val groupMemory = HashMap<String, TvSourceFocusPosition>()

    /** 各项的内容里 (源那一栏 / 条目) 焦点最后停的地方: 胶囊上按下时回到这里 (见 [enterFromRail]; 再打开面板时由 [resumeAt] 带回来). */
    private val contentMemory = HashMap<String, TvSourceFocusPosition>()

    /**
     * 内容里左右走到头, 正跨到胶囊行的另一项 (见 [moveToGroup]): 按着时焦点留在原处、只挪胶囊行选中的那颗 ([target] 是它的位置,
     * 走了 [steps] 格), 松手再落过去 ([finishGroupCross]).
     */
    private class GroupCross(val origin: String, val direction: Int) {
        var target = -1
        var steps = 0
    }

    private var groupCross: GroupCross? = null

    /**
     * 胶囊行下面收着哪些 (见 [updateBranchCollapse]): 焦点在表头上 (刚打开时停在「正在播放」) 时都收着, 只露胶囊行 —— 少挡画面;
     * 在分类胶囊上展开那一项, 焦点留在胶囊上: 没有源那一栏的出它的列表, 有的只出源那一栏 (右边的条目收着, 这一栏也不标显示的是哪个源),
     * 进了这一栏或条目全部淡入.
     */
    private var branchCollapsed = false
    private var columnCollapsed = false

    /** 正在落到 [groupMemory] 记的地方 (见 [landAt]): 焦点停在面板自己身上, 内容到了落到下面三个之一 (不在了落第一个). */
    private var pendingGroupLanding = false
    private var pendingLandRow: String? = null
    private var pendingColumnFocus: String? = null
    private var pendingPillFocus: String? = null

    /** [moveFocusTo] 时那一项还没排出来: 跳过去之后, 等那一栏排好再把焦点给它 (见 [resolvePendingFocus]). */
    private var pendingFocusGrid: BaseGridView? = null
    private var pendingFocusId: String? = null

    /** 表头上次聚焦的按钮: 从胶囊行按上回到它. */
    /** 从胶囊行这一颗按上进的表头、还没在表头里左右走过: 按下回到它 (见 [focusRailBelow]). */
    private var headerEntryPill: String? = null

    /** 从表头这一颗按下进的胶囊行、还没在胶囊行上左右走过: 按上回到它 (见 [focusHeaderAbove]). */
    private var railEntryHeader: String? = null

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
        column.visibility = GONE

        rail.adapter = railAdapter
        column.adapter = columnAdapter
        list.adapter = listAdapter
        rail.addItemDecoration(DividerDecoration(railAdapter, horizontal = true))
        list.addItemDecoration(DividerDecoration(listAdapter, horizontal = false))
        railAdapter.onRowFocused = { _, row -> onRailRowFocused(row) }
        columnAdapter.onRowFocused = { _, row -> onColumnRowFocused(row) }
        listAdapter.onRowFocused = { _, row ->
            rightKey?.let {
                focusMemory[it] = row.id
                focusMemory[it + LIST_SUFFIX] = row.id
            }
            rememberInContent(row.id)
            reportRegion()
        }
        pills.onPillFocused = { row ->
            rightKey?.let { focusMemory[it] = PILL_PREFIX + row.id }
            rememberInContent(PILL_PREFIX + row.id)
            reportRegion()
        }
        header.onPillFocused = { reportRegion() }
        nowPlayingView.onPillFocused = { reportRegion() }
        nowPlayingView.onPillClicked = { jumpToPlaying() }
        // 触屏: 胶囊点一下 = 换到那一项, 源那一栏点一下 = 换到那个源, 表头点一下 = 那个命令, 列表点一下 = 做那一行的动作, 长按 = 长按动作
        railAdapter.onRowClicked = { row, _ ->
            if (row.action != null) listener?.onAction(row.action) else followRail(row.id)
        }
        columnAdapter.onRowClicked = { row, long ->
            val longAction = if (long) longActionOf(row, inRail = true) else null
            when {
                longAction != null -> listener?.onAction(longAction)
                row.action != null -> listener?.onAction(row.action)
                else -> followSource(row.id)
            }
        }
        header.onPillClicked = { row -> row.action?.let { listener?.onAction(it) } }
        listAdapter.onRowClicked = { row, long ->
            (if (long) longActionOf(row, inRail = false) ?: row.action else row.action)?.let { performListAction(it) }
        }
        pills.onPillClicked = { row -> row.action?.let { listener?.onAction(it) } }
        rail.addOnLayoutCompletedListener {
            resolvePendingFocus(rail)
            resolvePending()
        }
        column.addOnLayoutCompletedListener {
            resolvePendingFocus(column)
            resolvePending()
            revealColumn()
        }
        list.addOnLayoutCompletedListener {
            resolvePendingFocus(list)
            resolvePending()
            revealBranch()
        }

        // 状态卡片最后加 = 最后画
        panel.addView(list)
        panel.addView(drillTitleView)
        panel.addView(pills)
        panel.addView(column)
        panel.addView(rail)
        panel.addView(header)
        panel.addView(nowPlayingView)
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
        // 左右的内边距就是渐隐带: 胶囊行伸进左右边距, 停着时第一颗的左缘与下面的内容对齐, 滚出去的在边距里渐隐
        rail.setPadding(style.fadeLengthPx, style.focusBleedPx, style.fadeLengthPx, style.focusBleedPx)
        rail.windowAlignmentOffset = style.fadeLengthPx
        rail.fadingLeftEdgeLength = style.fadeLengthPx
        rail.fadingRightEdgeLength = style.fadeLengthPx
        for (grid in arrayOf(column, list)) {
            grid.verticalSpacing = style.rowGapPx
            grid.horizontalSpacing = style.rowGapPx
            grid.setFadingEdgeLength(style.fadeLengthPx)
            grid.setPadding(style.focusBleedPx, style.focusBleedPx, style.focusBleedPx, style.focusBleedPx)
        }
        column.setColumnWidth(style.columnWidthPx)
        railAdapter.style = style
        columnAdapter.style = style
        listAdapter.style = style
        pills.style = style
        header.style = style
        nowPlayingView.style = style
        if (changed) {
            measuredRows = null
            requestLayout()
            invalidate()
        }
    }

    /** 新内容. 见类注释. */
    fun submit(content: TvSourcePanelContent) {
        val previous = this.content
        this.content = content
        // 要过的到了就清掉; 要过的那一项 / 那个源已经没了 (落点规则落到了别处) 也清掉, 不然一直当成「还在换」
        if (requestedRailKey == content.railKey || content.rail.none { it.id == requestedRailKey }) requestedRailKey = null
        requestedSourceKey?.let { if (it == content.sourceKey || it !in content.sourceGroups) requestedSourceKey = null }
        statusView.text = content.status

        val (commands, sources) = content.rail.partition { isCommandKey(it.id) }
        // 带字的在前, 只画图标的 (见 isCompactCommand) 一起排在最后
        header.submit(commands.sortedBy { isCompactCommand(it.id) })
        nowPlayingView.submit(listOfNotNull(content.nowPlaying?.row))
        if (!railCommitPending && groupCross == null) railAdapter.activeId = content.railKey
        railAdapter.submitList(sources) {
            // 持焦的那颗没了 (那一组的源都挪进了「查询失败」): 落到选中的那颗
            if (regionOf(findFocus()) == Region.NONE && previous != null && lastRegion == Region.RAIL) {
                focusRail(selectedRailKey())
            }
            // 焦点不在胶囊行 (打开时直接落进内容、在列表里换一项): 胶囊行对到选中的那颗, 滚到眼前
            if (regionOf(findFocus()) != Region.RAIL) {
                val key = selectedRailKey()
                val index = sources.indexOfFirst { it.id == key }
                if (index >= 0 && rail.selectedPosition != index) rail.setSelectedPosition(index)
            }
            resolvePending()
        }

        submitColumn(content)

        val right = content.right
        val newScreen = right.key != incomingRightKey
        incomingRightKey = right.key
        val focusInRight = regionOf(findFocus()).let { it == Region.LIST || it == Region.PILLS }
        if (newScreen && focusInRight) {
            park()
            pendingRightFocus = true
        }
        if (newScreen && style.animated) {
            // 新的一屏排好再淡入 (见 revealBranch): 先藏起来, 不让旧的一屏在新位置上闪一下
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
        listAdapter.reserveIconSlot = right.rows.any { showsIcon(it) }
        listAdapter.submitList(right.rows) {
            val inRight = regionOf(findFocus()).let { it == Region.LIST || it == Region.PILLS }
            updateBranchWidth(right)
            // 行变了: 右边的内容顶边按新的行高重新算 (见 branchTargetOffset)
            if (column.visibility == VISIBLE) panel.requestLayout()
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

    /** 源那一栏: 换了一组 (或这一栏出现 / 消失) 时先藏起来, 排好后淡入, 滚到选中的源; 同一组里只是行变了就原地更新. */
    private fun submitColumn(content: TvSourcePanelContent) {
        val key = content.railKey.takeIf { content.sources.isNotEmpty() }
        if (key != incomingColumnKey) {
            incomingColumnKey = key
            focusedSourceKey = null
            sourceCommitPending = false
            requestedSourceKey = null
            column.visibility = if (key == null) GONE else VISIBLE
            if (key != null && style.animated) {
                columnAnimator?.cancel()
                column.alpha = 0f
                columnHidden = true
            }
            panel.requestLayout()
            updateBranchCollapse(regionOf(findFocus()))
        }
        if (!sourceCommitPending) columnAdapter.activeId = content.sourceKey.takeUnless { branchCollapsed }
        columnAdapter.submitList(content.sources) {
            val index = content.sources.indexOfFirst { it.id == selectedSourceKey() }
            if (columnKey != key) {
                columnKey = key
                columnEntering = key != null
                // 新的一组: 选中的源滚到眼前 (不平滑, 这一栏还藏着)
                if (index >= 0) column.setSelectedPosition(index) else column.scrollToPosition(0)
            } else if (regionOf(findFocus()) != Region.COLUMN && index >= 0 && column.selectedPosition != index) {
                // 焦点不在这一栏 (在条目里 / 胶囊上): 这一栏对到选中的源
                column.setSelectedPosition(index)
            }
            // 换组又换回来 (A → B → A): 中间那次提交被差异计算丢掉了, 这一组还是原来的 key, 照样要淡入
            if (columnHidden && key != null) columnEntering = true
            // 持焦的源没了 (挪进了「查询失败」): 落到选中的那个源
            if (regionOf(findFocus()) == Region.NONE && lastRegion == Region.COLUMN) focusColumn(selectedSourceKey())
            panel.requestLayout()
            resolvePending()
            revealColumn()
        }
    }

    /**
     * 这一屏的宽度 (行提交时): 占满宽度的 ([TvSourceRight.wide]、多列的方块) 为 0; 其余按内容, 不窄于 [TvSourcePanelStyle.branchMinWidthPx],
     * 最宽到占满宽度 (排版时按可用宽度截). 同一屏里行只多不少时只放宽不收窄 —— 查询途中结果陆续到, 框不来回伸缩; 行变少了
     * (收起「其余 N 条」) 或换了一屏就重新量.
     */
    private fun updateBranchWidth(right: TvSourceRight) {
        val width = if (right.wide || right.columns > 1) {
            0
        } else {
            val measured = measureBranchContentWidth(right, style).coerceAtLeast(style.branchMinWidthPx)
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
     * 有源那一栏时右边的内容往下挪多少: 顶边对着它的源 (列表此刻那一屏是哪个源的, [rightKey]) 那一行停稳后的位置 (见 [columnSettledTop]),
     * 放不下往上让, 最多到这一栏的顶上 —— 内容比可用的高度还高就从顶上开始, 在里面滚. [available] = 这一栏的高度, [headerHeight] = 列表上面的
     * 小标题与小胶囊. 按停稳后的位置算: 这一栏还在滚时新的一屏也一出来就在最后的位置, 不跟着它动.
     */
    private fun branchTargetOffset(available: Int, headerHeight: Int, listWidth: Int): Int {
        val key = rightKey ?: return 0
        val rows = columnAdapter.currentList
        val index = rows.indexOfFirst { it.id == key }
        if (index < 0) return 0
        val anchor = columnSettledTop(index, rows.size, available + style.focusBleedPx * 2) - style.focusBleedPx
        val room = available - headerHeight - listContentHeight(listWidth, limit = available)
        return anchor.coerceIn(0, max(0, room))
    }

    /**
     * 源那一栏停稳以后第 [index] 行 (共 [count] 行) 的顶边, 相对这一栏的上缘 ([height] 含四周的出血边). 这一栏按 leanback 的窗口对齐滚
     * (聚焦行的中线停在离顶 [LIST_WINDOW_ALIGN_PERCENT] 处, 两头贴边, 见 [createVerticalList]), 行高都是 [TvSourcePanelStyle.columnRowHeightPx],
     * 照它的算法算出来, 不等它滚完.
     */
    private fun columnSettledTop(index: Int, count: Int, height: Int): Int {
        val s = style
        val pad = s.focusBleedPx
        val step = s.columnRowHeightPx + s.rowGapPx
        val top = pad + index * step
        val center = top + (s.columnRowHeightPx * LIST_ITEM_ALIGN_PERCENT / 100f).toInt()
        val keyline = (height * LIST_WINDOW_ALIGN_PERCENT / 100f).toInt()
        val end = pad + count * step - s.rowGapPx
        val scroll = (center - keyline).coerceIn(0, max(0, end - (height - pad)))
        return top - scroll
    }

    /** 源那一栏停稳以后 [key] 那个源那一行的顶边 (面板坐标, 见 [columnSettledTop]); 设备测试核对算的位置用. */
    @VisibleForTesting
    internal fun settledSourceTop(key: String): Int? {
        val rows = columnAdapter.currentList
        val index = rows.indexOfFirst { it.id == key }.takeIf { it >= 0 } ?: return null
        return column.top + columnSettledTop(index, rows.size, column.height)
    }

    /**
     * 列表里的行排出来有多高 (第一行顶到最后一行底, 含行距与分隔线的空当): 拿一个不挂上去的行视图按 [width] 逐行量 —— 不等列表排完, 排版时
     * 就能算出右边的内容摆在哪. 超过 [limit] 就不往下量了 (反正放不下, 从顶上开始). 同一份行、同一宽度只量一次.
     */
    private fun listContentHeight(width: Int, limit: Int): Int {
        val rows = listAdapter.currentList
        measuredRows?.let { (key, height) -> if (key.first === rows && key.second == width) return height }
        val probe = measureProbe ?: TvSourceRowView(context, sketch, style).also { measureProbe = it }
        probe.reserveIconSlot = listAdapter.reserveIconSlot
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var total = 0
        for ((i, row) in rows.withIndex()) {
            if (i > 0) total += style.rowGapPx + if (row.dividerAbove) style.dividerGapPx else 0
            // 只量尺寸: 不加载数据源图标
            probe.bind(row.copy(iconUrl = null), style)
            probe.measure(widthSpec, heightSpec)
            total += probe.measuredHeight
            if (total > limit) break
        }
        measuredRows = (rows to width) to total
        return total
    }

    private var measureProbe: TvSourceRowView? = null
    private var measuredRows: Pair<Pair<List<TvSourceRow>, Int>, Int>? = null

    /**
     * 打开面板 / 焦点不在面板里时: 落进列表 (正在播的那一行, 见 [focusRightTarget]); 列表里没有能选的行就落源那一栏选中的源 / 胶囊行选中的那颗.
     * 内容没到就等内容到了再送.
     */
    fun requestEntryFocus() {
        pendingEntry = true
        resolvePending()
    }

    /**
     * 返回键: 退一层 —— 进去的那一层 (筛选取值、手动查找的条目页) 先退出来, 条目、源那一栏与内容顶上的小胶囊回分类胶囊
     * (条目回源那一栏是左键的事). 返回 false = 已经在分类胶囊 / 表头上 (或焦点不在面板里), 该关面板了 —— 宿主记下关时停的地方
     * ([resumePoint]), 再打开时原样回去. 长按返回由播放器根部的长按处理直接关, 不记位置, 下次打开照常先停在「正在播放」.
     */
    fun handleBack(): Boolean {
        val region = regionOf(findFocus())
        if (region == Region.LIST || region == Region.PILLS) {
            if (listener?.onBackInRight() == true) return true
        }
        cancelPendingFocus()
        returnPoint = null
        return when (region) {
            // 条目 (有没有源那一栏都一样)、源那一栏、内容顶上的小胶囊: 回分类胶囊 —— 回源那一栏按一下左就到, 返回不重复这一步
            Region.LIST, Region.PILLS, Region.COLUMN -> {
                focusRail(selectedRailKey())
                true
            }

            else -> false
        }
    }

    /**
     * 关面板时停的地方 (宿主按返回关面板前取, 见 TvSourcePanelController.rememberPosition): 关只发生在分类胶囊 / 表头上 (内容里按返回先回胶囊),
     * 记下那一颗与那一项内容里最后停的, 再打开时原样回去 ([resumeAt]) —— 焦点回那一颗、下面收着, 按下进去落回内容里停的地方.
     * null = 焦点不在面板里, 不记.
     */
    fun resumePoint(): TvSourceResumePoint? {
        val focused = findFocus() as? TvSourceRowView ?: return null
        val id = focused.row?.id ?: return null
        return when (regionOf(focused)) {
            Region.RAIL -> TvSourceResumePoint(id, null, contentMemory[id])
            Region.HEADER -> {
                val key = selectedRailKey()
                TvSourceResumePoint(key, id, key?.let { contentMemory[it] })
            }

            else -> null
        }
    }

    /**
     * 再打开时回到 [point] (上次按返回关时停的, 见 [resumePoint]); null = 照常落. 在 [requestEntryFocus] 之前调.
     * 内容里停的地方记成那一项的 [contentMemory], 焦点落那一颗胶囊 / 表头那一颗.
     */
    fun resumeAt(point: TvSourceResumePoint?) {
        resumeHeaderId = point?.headerId
        resumeRow = point?.takeIf { it.headerId == null }?.railKey?.let { TvSourceFocusPosition(it, null, null) }
        point?.content?.let { contentMemory[it.railKey] = it }
    }

    /** 打开时落表头里的这一颗 (上次在表头上关的, 见 [resumeAt]). 落过一次就作废. */
    private var resumeHeaderId: String? = null

    /**
     * 焦点停在哪 (见 [TvSourceFocusPosition]): 列表里只认那一项 / 那个源本身那一屏 (不是进去的那一层); 内容顶上的小胶囊算那一项的胶囊.
     * 在表头、进去的那一层里、不在面板里为 null.
     */
    fun focusPosition(): TvSourceFocusPosition? {
        val content = content ?: return null
        val focused = findFocus() as? TvSourceRowView ?: return null
        val railKey = content.railKey ?: return null
        return when (regionOf(focused)) {
            Region.LIST -> {
                if (rightKey != paneKeyOf(content)) return null
                TvSourceFocusPosition(railKey, content.sourceKey, focused.row?.id ?: return null)
            }

            Region.COLUMN -> TvSourceFocusPosition(railKey, focused.row?.id ?: return null, null)
            Region.RAIL -> focused.row?.id?.let { TvSourceFocusPosition(it, null, null) }
            Region.PILLS -> TvSourceFocusPosition(railKey, null, null)
            else -> null
        }
    }

    /**
     * 打开时要落的位置 (胶囊、源那一栏那个源或列表那一行, 见 [TvSourceFocusPosition]; 按返回关的由 [resumeAt] 给). 落过一次 (或那一行没了)
     * 就作废, 照常落.
     */
    var resumeRow: TvSourceFocusPosition? = null

    /** 面板里有没有焦点 (根路由据此判断要不要先把焦点送进来). */
    fun hasPanelFocus(): Boolean = hasFocus()

    // ---- 焦点 ----

    private enum class Region { NONE, HEADER, RAIL, COLUMN, PILLS, LIST }

    private var lastRegion = Region.NONE

    private fun regionOf(view: View?): Region = when {
        view == null -> Region.NONE
        tvNativeIsInside(view, header) || tvNativeIsInside(view, nowPlayingView) -> Region.HEADER
        tvNativeIsInside(view, rail) -> Region.RAIL
        tvNativeIsInside(view, column) -> Region.COLUMN
        tvNativeIsInside(view, pills) -> Region.PILLS
        tvNativeIsInside(view, list) -> Region.LIST
        else -> Region.NONE
    }

    private fun reportRegion() {
        val region = regionOf(findFocus())
        if (region != Region.NONE) lastRegion = region
        updateBranchCollapse(region)
        val inRight = region == Region.LIST || region == Region.PILLS
        if (inRight != lastInRight) {
            lastInRight = inRight
            listener?.onRightFocusChanged(inRight)
        }
    }

    /** 列表那一行 / 小胶囊 [id] 得了焦点: 是那一项本身那一屏 (不是进去的那一层) 就记进 [groupMemory]. */
    private fun rememberInContent(id: String) {
        val content = content ?: return
        val railKey = content.railKey ?: return
        if (rightKey != paneKeyOf(content)) return
        rememberContent(TvSourceFocusPosition(railKey, content.sourceKey, id))
    }

    private fun rememberContent(position: TvSourceFocusPosition) {
        groupMemory[position.railKey] = position
        contentMemory[position.railKey] = position
    }

    private fun onRailRowFocused(row: TvSourceRow) {
        groupMemory[row.id] = TvSourceFocusPosition(row.id, null, null)
        focusedRailKey = row.id
        // 选中的描边跟着焦点走 (下面的内容松手才换, 但胶囊上本来就收着): 刚离开的那颗不在松手前带着一圈边
        railAdapter.activeId = row.id
        reportRegion()
        railCommitPending = true
        if (!horizontalHeld) commitRail()
    }

    private fun onColumnRowFocused(row: TvSourceRow) {
        // 最后那一行「没有结果的 N 个」不是源: 右边照旧是上一个源的
        if (row.id == TvSourceRailKeys.EMPTY_SOURCES) {
            reportRegion()
            return
        }
        columnKey?.let { rememberContent(TvSourceFocusPosition(it, row.id, null)) }
        focusedSourceKey = row.id
        reportRegion()
        sourceCommitPending = true
        if (!verticalHeld) commitSource()
    }

    /** 把胶囊行选中的那颗 ([focusedRailKey]) 换过去: 下面的内容跟着换. 左右键按着时等松手再调. */
    private fun commitRail() {
        if (!railCommitPending) return
        railCommitPending = false
        val key = focusedRailKey ?: return
        railAdapter.activeId = key
        if (!requestRail(key)) listener?.onSelectionHeld(key, null)
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

    /** 把源那一栏选中的那个源 ([focusedSourceKey]) 换过去: 右边的条目跟着换. 上下键按着时等松手再调. */
    private fun commitSource() {
        if (!sourceCommitPending) return
        sourceCommitPending = false
        val key = focusedSourceKey ?: return
        columnAdapter.activeId = key
        if (!requestSource(key)) columnKey?.let { listener?.onSelectionHeld(it, key) }
    }

    /** 请宿主把右边换成这一组的源 [key] (同 [requestRail], 跟最后要过的比). */
    private fun requestSource(key: String): Boolean {
        val group = columnKey ?: return false
        if ((requestedSourceKey ?: content?.sourceKey) == key) return false
        requestedSourceKey = key
        listener?.onSourceFocused(group, key)
        return true
    }

    /** 胶囊行选中的那一项: 还没换过去的那颗, 否则是内容里的. */
    private fun selectedRailKey(): String? = when {
        groupCross != null -> groupCross?.let { railAdapter.currentList.getOrNull(it.target)?.id }
        railCommitPending -> focusedRailKey
        else -> content?.railKey
    }

    /** 源那一栏选中的源: 还没换过去的那个, 否则是内容里的. */
    private fun selectedSourceKey(): String? = if (sourceCommitPending) focusedSourceKey else content?.sourceKey

    /** 右边那一屏属于谁: 在线源的组里是那个源, 其余是胶囊那一项. */
    private fun paneKeyOf(content: TvSourcePanelContent): String? = content.sourceKey ?: content.railKey

    private fun resolvePending() {
        val content = content ?: return
        if (pendingGroupLanding) {
            // 落到那一项上次到过的地方 (见 landAt): 等那一项 (与那个源) 的内容、源那一栏到了
            if (requestedRailKey != null || requestedSourceKey != null || content.railKey != focusedRailKey || !columnReady(content)) return
            pendingColumnFocus?.let { key ->
                if (content.sources.isNotEmpty()) {
                    if (!focusColumn(key)) return // 那一行还没排出来: 排好再来
                } else {
                    focusRail(content.railKey)
                }
                finishLanding()
                return
            }
            if (rightKey != incomingRightKey || !rightMatchesSelection(content)) return
            pendingPillFocus?.let { id ->
                if (!pills.focusPill(id) && focusRightTarget(content) == FocusResult.None) focusOutsideList()
                finishLanding()
                return
            }
            when (focusRightTarget(content)) {
                FocusResult.Focused -> finishLanding()
                FocusResult.Pending -> {} // 落点还没排出来: 等排好 (布局完成回调)
                FocusResult.None -> {
                    finishLanding()
                    focusOutsideList()
                }
            }
            return
        }
        if (pendingRightFocus) {
            // 新的一屏还没提交到列表 / 源那一栏: 等提交回调 (列表里落不下时要落到这一栏)
            if (rightKey != incomingRightKey || !columnReady(content)) return
            when (focusRightTarget(content)) {
                FocusResult.Focused -> {
                    pendingRightFocus = false
                    unpark()
                    // 焦点一直在内容里 (没经过胶囊行), 宿主没收到「进内容」: 给新的一屏钉住行序
                    listener?.onRightFocusChanged(true)
                }

                FocusResult.Pending -> {} // 落点还没排出来: 等排好 (布局完成回调)
                FocusResult.None -> {
                    // 新的一屏没有能聚焦的 (还在查 / 限流 / 没结果): 回源那一栏 / 胶囊行
                    pendingRightFocus = false
                    unpark()
                    focusOutsideList()
                }
            }
            return
        }
        if (pendingEnterContent) {
            // 胶囊行刚换了一项, 内容还是上一项的: 等对得上再进 (有源那一栏的进这一栏, 没有的进列表)
            if (requestedRailKey != null || content.railKey != (focusedRailKey ?: content.railKey)) return
            if (content.sources.isNotEmpty()) {
                if (!columnReady(content)) return
                pendingEnterContent = false
                focusColumn(selectedSourceKey())
                return
            }
            pendingEnterContent = false
            pendingEnterRight = true
        }
        if (pendingEnterRight) {
            if (!rightMatchesSelection(content) || rightKey != incomingRightKey) return
            if (focusRightTarget(content, fromAbove = enterFromAbove) != FocusResult.Pending) {
                pendingEnterRight = false
                enterFromAbove = false
            }
            return
        }
        if (pendingEntry && isAttachedToWindow) {
            if (railAdapter.currentList.isEmpty() && content.rail.any { !isCommandKey(it.id) }) return // 胶囊行还没提交
            if (!columnReady(content)) return
            resumeHeaderId?.let { id ->
                // 上次在表头上关的: 回那一颗 (没了落「正在播放」), 下面收着
                resumeHeaderId = null
                resumeRow = null
                pendingEntry = false
                focusHeader(id)
                return
            }
            val resume = resumeRow
            if (resume != null && content.railKey == resume.railKey && (resume.sourceKey == null || resume.sourceKey == content.sourceKey)) {
                val rowId = resume.rowId
                when {
                    rowId != null -> {
                        // 等这一屏提交、排出来
                        if (rightKey != incomingRightKey || !rightMatchesSelection(content)) return
                        val index = listAdapter.currentList.indexOfFirst { it.id == rowId && it.focusable }
                        if (index >= 0) {
                            if (moveFocusTo(list, index)) {
                                resumeRow = null
                                pendingEntry = false
                            }
                            return
                        }
                    }

                    resume.sourceKey != null && content.sources.isNotEmpty() -> {
                        resumeRow = null
                        pendingEntry = false
                        focusColumn(resume.sourceKey)
                        return
                    }

                    else -> {
                        resumeRow = null
                        enterRail(resume.railKey)
                        return
                    }
                }
            }
            resumeRow = null
            // 没有要落回的: 先停在「正在播放」 (想换同一个源里的另一条, 确定就跳回那一行; 想换别的, 往下就是胶囊行),
            // 没有在播的落胶囊行选中的那一组
            pendingEntry = false
            // 在播的那一条播不了了: 反正要换, 直接落胶囊行
            if (content.nowPlaying?.failed == true || !nowPlayingView.focusPill(null)) enterRail(content.railKey)
        }
    }

    /** 打开时落胶囊行 [key] 那颗 (胶囊行是空的就落表头). 那一颗还没排出来时等排好再给 (见 [moveFocusTo]), 这次打开的落点就算定了. */
    private fun enterRail(key: String?) {
        pendingEntry = false
        if (railAdapter.itemCount == 0) focusHeader(null) else focusRail(key)
    }

    /**
     * 表头往下: 刚从胶囊行那一颗上来、还没在表头里左右走过的, 回到那一颗; 否则落正下方最近的那一颗 (横向中线离得最近的, 只看排着的) ——
     * 不跳到远处选中的那一组.
     */
    private fun focusRailBelow(from: View) {
        railEntryHeader = (from as? TvSourceRowView)?.row?.id
        headerEntryPill?.let { key ->
            headerEntryPill = null
            val index = railAdapter.currentList.indexOfFirst { it.id == key }
            if (index >= 0 && moveFocusTo(rail, index)) return
        }
        val pills = (0 until rail.childCount).map { rail.getChildAt(it) }.filter { rail.getChildAdapterPosition(it) >= 0 }
        val nearest = nearestByCenterX(from, pills)
        if (nearest != null) moveFocusTo(rail, rail.getChildAdapterPosition(nearest)) else focusRail(selectedRailKey())
    }

    /** 胶囊行往上: 刚从表头那一颗下来、还没在胶囊行上左右走过的, 回到那一颗; 否则落正上方最近的那一颗 (「正在播放」与命令按钮里). */
    private fun focusHeaderAbove(from: View) {
        railEntryHeader?.let { id ->
            railEntryHeader = null
            if (focusHeader(id)) return
        }
        val items = (0 until nowPlayingView.childCount).map { nowPlayingView.getChildAt(it) } +
            (0 until header.childCount).map { header.getChildAt(it) }
        nearestByCenterX(from, items)?.requestFocus() ?: focusHeader(null)
    }

    /** [candidates] 里横向中线离 [from] 最近的 (屏幕坐标); 没有为 null. */
    private fun nearestByCenterX(from: View, candidates: List<View>): View? {
        val location = IntArray(2)
        fun centerX(v: View): Int {
            v.getLocationOnScreen(location)
            return location[0] + v.width / 2
        }
        val x = centerX(from)
        return candidates.minByOrNull { abs(centerX(it) - x) }
    }

    /** 表头里 [id] 那一颗; 没有 (或 null) 时落「正在播放」, 没有它落第一个命令. */
    private fun focusHeader(id: String?): Boolean {
        if (id != null && id != TvSourceRailKeys.NOW_PLAYING && header.focusPill(id)) return true
        if (nowPlayingView.focusPill(null)) return true
        return header.focusPill(null)
    }

    /** 「正在播放」上确定: 跳到在播的那一行 (那一组、那个源; 找不到那一行落那个源 / 那一组的胶囊), 同 [landAt]. */
    private fun jumpToPlaying() {
        val target = content?.nowPlaying?.target
        if (target == null) {
            focusRail(selectedRailKey())
            return
        }
        cancelPendingFocus()
        returnPoint = null
        landAt(target)
    }

    /** 源那一栏排的已经是 [content] 这一组 (没有这一栏的不用等). 两栏各自在后台算差异, 提交的先后不定. */
    private fun columnReady(content: TvSourcePanelContent): Boolean = content.sources.isEmpty() || columnKey == content.railKey

    /** 列表里落不下 (没有能选的行): 有源那一栏落选中的源, 否则落胶囊行选中的那颗. */
    private fun focusOutsideList() {
        if (column.visibility == VISIBLE && columnAdapter.itemCount > 0) focusColumn(selectedSourceKey()) else focusRail(selectedRailKey())
    }

    private enum class FocusResult { Focused, Pending, None }

    private fun rightMatchesSelection(content: TvSourcePanelContent): Boolean {
        val railKey = content.railKey ?: return false
        // 用户刚在胶囊行 / 源那一栏换了一项, 内容还是上一个的
        if (focusedRailKey != null && focusedRailKey != railKey) return false
        if (content.sources.isNotEmpty() && focusedSourceKey != null && focusedSourceKey != content.sourceKey) return false
        val pane = paneKeyOf(content) ?: return false
        val key = rightKey ?: return false
        return key == pane || key.startsWith("$pane/")
    }

    /**
     * 进内容的落点: 这一屏上次停的 → 从胶囊上按下来的 ([fromAbove]) 先落顶上的小胶囊 (就在胶囊行下面) → 内容给的落点 (选项列表的当前值)
     * → 第一条能聚焦的行 → 第一个小胶囊.
     * [FocusResult.Pending] = 落点那一行还没排出来 (已选中, 排到时 leanback 给焦点; 调用方等布局完成再试).
     */
    private fun focusRightTarget(content: TvSourcePanelContent, fromAbove: Boolean = false): FocusResult {
        val right = content.right
        val rows = listAdapter.currentList
        pendingLandRow?.let { id ->
            // 跨出去又回来: 落回原来那一行, 不在了落第一行
            val index = rows.indexOfFirst { it.id == id && it.focusable }.takeIf { it >= 0 } ?: rows.indexOfFirst { it.focusable }
            if (index < 0) {
                pendingLandRow = null
                return FocusResult.None
            }
            if (!moveFocusTo(list, index)) return FocusResult.Pending
            pendingLandRow = null
            return FocusResult.Focused
        }
        if (crossing != 0) {
            val index = if (crossing > 0) rows.indexOfFirst { it.focusable } else rows.indexOfLast { it.focusable }
            if (index < 0) {
                crossing = 0
                return FocusResult.None
            }
            if (!moveFocusTo(list, index)) return FocusResult.Pending
            crossing = 0
            return FocusResult.Focused
        }
        val remembered = rightKey?.let { focusMemory[it] }
        if (remembered != null && remembered.startsWith(PILL_PREFIX)) {
            if (pills.focusPill(remembered.removePrefix(PILL_PREFIX))) return FocusResult.Focused
        }
        if (fromAbove && remembered == null && pills.focusPill(null)) return FocusResult.Focused
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

    private fun focusColumn(key: String?): Boolean {
        val rows = columnAdapter.currentList
        val index = rows.indexOfFirst { it.id == key }.takeIf { it >= 0 } ?: if (rows.isEmpty()) -1 else 0
        if (index < 0) return false
        return moveFocusTo(column, index)
    }

    private fun adapterOf(grid: BaseGridView): TvSourceRowAdapter = when {
        grid === rail -> railAdapter
        grid === column -> columnAdapter
        else -> listAdapter
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
        pendingFocusId = adapterOf(grid).currentList.getOrNull(index)?.id ?: return false
        pendingFocusGrid = grid
        grid.setSelectedPosition(index)
        return false
    }

    private fun resolvePendingFocus(grid: BaseGridView) {
        if (pendingFocusGrid !== grid) return
        val id = pendingFocusId
        pendingFocusGrid = null
        pendingFocusId = null
        val index = adapterOf(grid).currentList.indexOfFirst { it.id == id && it.focusable }
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
                "rightKey=$rightKey incomingRightKey=$incomingRightKey railKey=${content?.railKey} " +
                "sourceKey=${content?.sourceKey} pendingFocusId=$pendingFocusId",
        )
        pendingRightFocus = false
        crossing = 0
        pendingGroupLanding = false
        pendingLandRow = null
        pendingColumnFocus = null
        pendingPillFocus = null
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
        val horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!horizontal && !vertical && !confirm) return super.dispatchKeyEvent(event)
        if (leaving) return true
        if (horizontal) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> horizontalHeld = true
                KeyEvent.ACTION_UP -> {
                    horizontalHeld = false
                    finishGroupCross()
                    commitRail()
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_DOWN && (railCommitPending || groupCross != null)) {
            // 按着左右时按了别的键: 先把胶囊行选中的换过去, 这一下不动 (下面的内容马上要换)
            finishGroupCross()
            commitRail()
            return true
        }
        if (vertical) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> verticalHeld = true
                KeyEvent.ACTION_UP -> {
                    verticalHeld = false
                    commitSource()
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_DOWN && sourceCommitPending) {
            // 按着上下时按了别的键: 先把源那一栏选中的换过去, 这一下不动
            commitSource()
            return true
        }
        if (!confirm) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                keyRepeating = event.repeatCount > 0
                navigate(code)
            }
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
        cancelPendingFocus()
        // 刚跨出去就按反方向: 回到跨出去之前那一行
        val back = returnPoint
        returnPoint = null
        if (back != null && back.reverseKey == code && back.arrival == region) {
            returnTo(back)
            return
        }
        when (region) {
            Region.NONE -> {}
            Region.HEADER -> {
                // 「正在播放」与命令按钮排成一行: 「正在播放」在最左
                val playing = nowPlayingView.pillCount
                val index = if (tvNativeIsInside(focused!!, nowPlayingView)) 0 else playing + header.indexOfPill(focused)
                fun focusAt(i: Int) {
                    if (i < playing) nowPlayingView.focusPillAt(i) else header.focusPillAt(i - playing)
                }
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) {
                        headerEntryPill = null
                        focusAt(index - 1)
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (index < playing + header.pillCount - 1) {
                        headerEntryPill = null
                        focusAt(index + 1)
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> focusRailBelow(focused)
                }
            }

            Region.RAIL -> {
                val index = rail.getChildAdapterPosition(rowViewOf(focused, rail) ?: return)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) {
                        railEntryHeader = null
                        moveFocusTo(rail, index - 1)
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (index < railAdapter.itemCount - 1) {
                        railEntryHeader = null
                        moveFocusTo(rail, index + 1)
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        headerEntryPill = (focused as? TvSourceRowView)?.row?.id
                        focusHeaderAbove(focused)
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> enterFromRail()
                }
            }

            Region.COLUMN -> {
                val index = column.getChildAdapterPosition(rowViewOf(focused, column) ?: return)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (index > 0) moveFocusTo(column, index - 1) else focusRail(selectedRailKey())
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (index < columnAdapter.itemCount - 1) moveFocusTo(column, index + 1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> moveToGroup(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // 这个源进不去 (没有能选的行): 换到右边的一项
                        val key = (focused as? TvSourceRowView)?.row?.id
                        if (content?.enterableSources?.contains(key) == false) moveToGroup(1) else enterRight()
                    }
                }
            }

            Region.PILLS -> {
                val index = pills.indexOfPill(focused)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> focusRail(selectedRailKey())
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) pills.focusPillAt(index - 1) else moveToGroup(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (index < pills.pillCount - 1) pills.focusPillAt(index + 1) else moveToGroup(1)
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
                val beside = column.visibility == VISIBLE
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (!stepVertical(index, -1, cols)) {
                        when {
                            pills.pillCount > 0 -> {
                                rightKey?.let { focusMemory["$it$LIST_SUFFIX"] = listAdapter.currentList[index].id }
                                pills.focusPill(null)
                            }
                            // 有源那一栏: 跨到上面一个有条目的源; 上面没有了回胶囊
                            beside && crossSource(-1) -> {}
                            else -> focusRail(selectedRailKey())
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> if (!stepVertical(index, 1, cols) && beside) crossSource(1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> when {
                        cols > 1 && index % cols > 0 -> moveFocusTo(list, index - 1)
                        // 有源那一栏: 回到这一栏里条目所属的那个源
                        beside -> focusColumn(content?.sourceKey)
                        inDrill() -> {}
                        else -> moveToGroup(-1)
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                        cols > 1 && index % cols < cols - 1 && index + 1 < listAdapter.itemCount -> moveFocusTo(list, index + 1)
                        !beside && inDrill() -> {}
                        else -> moveToGroup(1)
                    }
                }
            }
        }
    }

    /** 用户动过了: 打开时还没落定的落点、还没进去的内容、还没落下的换组作废, 不再把焦点拽回去. */
    private fun cancelPendingFocus() {
        pendingEntry = false
        pendingEnterContent = false
        pendingEnterRight = false
        enterFromAbove = false
        pendingGroupLanding = false
        pendingColumnFocus = null
        pendingPillFocus = null
        pendingLandRow = null
        pillFallbackScreen = null
        pendingFocusGrid = null
        pendingFocusId = null
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

    /** 列表里此刻是进去的那一层 (BT 小胶囊的取值、手动查找的换源 / 换线路 / 条目页): 左右不换到别的项. */
    private fun inDrill(): Boolean {
        val content = content ?: return true
        val key = rightKey ?: return true
        return key != paneKeyOf(content) && !key.startsWith(TvSourceRailKeys.MANUAL + "/search/")
    }

    /**
     * 左右走到头 (源那一栏按左、有源那一栏时条目按右、源进不去时按右、小胶囊到头、没有源那一栏的列表按左右): 换到胶囊行相邻的那一项.
     * 按着时焦点留在原处, 松手再落过去 (见 [finishGroupCross]). 按住连走时 (连发起) 胶囊行选中的那颗一格格挪、描着边, 看得出要去哪一颗;
     * 单按一下不先描边 —— 松手就落过去, 先描边再变高亮看着像闪了一下.
     */
    private fun moveToGroup(direction: Int) {
        val content = content ?: return
        val rows = railAdapter.currentList
        val cross = groupCross ?: GroupCross(content.railKey ?: return, direction)
        // 按着期间胶囊行变短了 (搜索途中一组没了): 从新的最后一颗算
        if (cross.target > rows.lastIndex) cross.target = rows.lastIndex
        val from = if (cross.target >= 0) cross.target else rows.indexOfFirst { it.id == selectedRailKey() }
        if (from < 0) return
        if (from + direction in rows.indices) {
            cross.target = from + direction
            cross.steps++
        } else if (cross.target < 0) {
            return
        }
        groupCross = cross
        if (keyRepeating) {
            railAdapter.activeId = rows.getOrNull(cross.target)?.id ?: return
            rail.setSelectedPositionSmooth(cross.target)
        }
        if (!horizontalHeld) finishGroupCross()
    }

    /**
     * 跨组松手: 落到那一项上次离开时停的地方 (源那一栏 / 条目, 见 [groupMemory]); 没去过或上次从胶囊上离开的停在它的胶囊上 (下面的内容跟着换).
     * 只挪了一格的, 紧接着按反方向回到出发的这一项离开时停的地方.
     */
    private fun finishGroupCross() {
        val cross = groupCross ?: return
        groupCross = null
        val key = railAdapter.currentList.getOrNull(cross.target)?.id ?: return
        val memory = groupMemory[key]
        if (memory != null && !memory.isPill) {
            landAt(memory)
            return
        }
        moveFocusTo(rail, cross.target)
        if (cross.steps == 1) {
            returnPoint = ReturnPoint(reverseOf(cross.direction, horizontal = true), Region.RAIL, TvSourceFocusPosition(cross.origin, null, null))
        }
    }

    /**
     * 落到 [position] (一项上次离开时停的地方, 见 [groupMemory]): 焦点先停在面板自己身上, 胶囊行 / 源那一栏换过去, 内容到了落进去
     * (那一行 / 那个源不在了落第一个, 见 resolvePending).
     */
    private fun landAt(position: TvSourceFocusPosition) {
        val rowId = position.rowId
        crossing = 0
        pendingLandRow = null
        pendingColumnFocus = null
        pendingPillFocus = null
        when {
            rowId == null -> pendingColumnFocus = position.sourceKey.orEmpty()
            rowId.startsWith(PILL_PREFIX) -> pendingPillFocus = rowId.removePrefix(PILL_PREFIX)
            else -> pendingLandRow = rowId
        }
        pendingGroupLanding = true
        park()
        selectPosition(position.railKey, position.sourceKey)
        resolvePending()
    }

    private fun finishLanding() {
        pendingGroupLanding = false
        pendingLandRow = null
        pendingColumnFocus = null
        pendingPillFocus = null
        unpark()
    }

    /**
     * 有源那一栏时条目上下走到头: 跨到这一栏里相邻的那个源, 不跳过 —— 它有能选的行 (对不上的结果、继续查也算, 见
     * [TvSourcePanelContent.enterableSources]) 就留在条目里, 往下落新一屏的第一行, 往上落最后一行 ([crossing]); 没有 (还在查、限流)
     * 落到源那一栏的它. 「没有结果的 N 个」不是源, 到它就是到头. 记下出发的那一行, 紧接着按反方向回到它. 那个方向没有了返回 false.
     */
    private fun crossSource(direction: Int): Boolean {
        val content = content ?: return false
        val railKey = content.railKey ?: return false
        val from = content.sourceKey ?: return false
        if (rightKey != from) return false
        val rows = columnAdapter.currentList
        val i = rows.indexOfFirst { it.id == from }
        if (i < 0) return false
        val target = rows.getOrNull(i + direction)?.takeIf { it.id != TvSourceRailKeys.EMPTY_SOURCES } ?: return false
        val focused = (findFocus() as? TvSourceRowView)?.row?.id
        val back = TvSourceFocusPosition(railKey, from, focused)
        if (target.id in content.enterableSources) {
            crossing = direction
            followSource(target.id)
            returnPoint = ReturnPoint(reverseOf(direction, horizontal = false), Region.LIST, back)
        } else {
            focusColumn(target.id)
            returnPoint = ReturnPoint(reverseOf(direction, horizontal = false), Region.COLUMN, back)
        }
        return true
    }

    /**
     * 跨出去后紧接着按了反方向 (见 [ReturnPoint]): 从胶囊上回去的落到出发那一项离开时停的地方; 竖着跨源的落回出发的那一行 (不在了落第一行).
     */
    private fun returnTo(back: ReturnPoint) {
        val p = back.position
        if (back.arrival == Region.RAIL) {
            val memory = groupMemory[p.railKey]
            if (memory != null && !memory.isPill) {
                landAt(memory)
            } else {
                railAdapter.currentList.indexOfFirst { it.id == p.railKey }.takeIf { it >= 0 }?.let { moveFocusTo(rail, it) }
            }
            return
        }
        val changed = selectPosition(p.railKey, p.sourceKey)
        pendingLandRow = p.rowId.orEmpty()
        crossing = 0
        // 换了一屏: 焦点在条目里的, 新的一屏到了由 submit 那边落 (pendingRightFocus); 没换、或焦点在源那一栏 (跨过去落在了那个源上), 等这一屏到了进去
        if (!changed || back.arrival == Region.COLUMN) pendingEnterRight = true
        resolvePending()
    }

    /** 胶囊行 (与源那一栏) 换到 [railKey] / [sourceKey], 焦点不动. 返回 false = 本来就是它 (不会有新的一屏). */
    private fun selectPosition(railKey: String, sourceKey: String?): Boolean {
        val content = content ?: return false
        val index = railAdapter.currentList.indexOfFirst { it.id == railKey }
        if (index < 0) return false
        focusedRailKey = railKey
        railCommitPending = false
        railAdapter.activeId = railKey
        if (rail.selectedPosition != index) rail.setSelectedPositionSmooth(index)
        // 那个源不在这一组里了: 只换组, 源按落点规则落
        if (sourceKey == null || content.sourceGroups[sourceKey] != railKey) return requestRail(railKey)
        val sourceIndex = columnAdapter.currentList.indexOfFirst { it.id == sourceKey }
        if (railKey == (requestedRailKey ?: content.railKey) && columnKey == railKey && sourceIndex >= 0) {
            // 同一组: 只换源
            focusedSourceKey = sourceKey
            sourceCommitPending = false
            columnAdapter.activeId = sourceKey
            if (column.selectedPosition != sourceIndex) column.setSelectedPositionSmooth(sourceIndex)
            return requestSource(sourceKey)
        }
        if ((requestedRailKey ?: content.railKey) == railKey && (requestedSourceKey ?: content.sourceKey) == sourceKey) return false
        requestedRailKey = railKey
        requestedSourceKey = sourceKey
        listener?.onSourceFocused(railKey, sourceKey)
        return true
    }

    private fun reverseOf(direction: Int, horizontal: Boolean): Int = when {
        horizontal -> if (direction > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        else -> if (direction > 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
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

    /** 源那一栏换到 [key] (焦点不动): 那一行亮起来、滚到眼前, 右边的条目当场换. */
    private fun followSource(key: String) {
        val index = columnAdapter.currentList.indexOfFirst { it.id == key }
        if (index < 0) return
        focusedSourceKey = key
        sourceCommitPending = true
        column.setSelectedPositionSmooth(index)
        commitSource()
    }

    /**
     * 胶囊上按下 / 确定: 进去过就回到上次离开时停的地方 (见 [groupMemory]); 没进去过的, 有源那一栏的进这一栏 (选中的源), 没有的进列表.
     * 内容还是上一项的就等它到.
     */
    private fun enterFromRail() {
        commitRail()
        val memory = (focusedRailKey ?: content?.railKey)?.let { contentMemory[it] }
        if (memory != null) {
            landAt(memory)
            return
        }
        pendingEnterContent = true
        enterFromAbove = true
        resolvePending()
    }

    private fun enterRight() {
        enterFromAbove = false
        commitRail()
        commitSource()
        val content = content ?: return
        if (!rightMatchesSelection(content) || rightKey != incomingRightKey) {
            pendingEnterRight = true
            return
        }
        if (focusRightTarget(content) == FocusResult.Pending) pendingEnterRight = true
    }

    private fun handleConfirm(event: KeyEvent) {
        val focused = findFocus()
        val rowView = focused as? TvSourceRowView
        val row = rowView?.row
        val region = regionOf(focused)
        val inRail = region == Region.RAIL || region == Region.COLUMN
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                returnPoint = null
                if (row == null) return
                if (event.repeatCount == 0) {
                    press = Press(row.id, SystemClock.uptimeMillis())
                    rowView.isPressed = true
                    return
                }
                val p = press ?: return
                val long = longActionOf(row, inRail)
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
                when (region) {
                    Region.RAIL -> if (row.action != null) listener?.onAction(row.action) else enterFromRail()
                    Region.HEADER -> if (row.id == TvSourceRailKeys.NOW_PLAYING) jumpToPlaying() else row.action?.let { listener?.onAction(it) }
                    Region.COLUMN -> if (row.action != null) listener?.onAction(row.action) else enterRight()
                    else -> row.action?.let { performListAction(it) }
                }
            }
        }
    }

    /**
     * 右边列表里按了一行 (确定键或触屏). 「继续查这个数据源」「重试」那一行马上换成查询中、再换成结果: 先把焦点送回源那一栏的
     * 这个源再交给宿主, 不然焦点跟着那一行没了, 兜底落到别处 (看着是别的一项亮了一下).
     */
    private fun performListAction(action: TvSourceAction) {
        if (action is TvSourceAction.RestartSource) {
            content?.sourceKey?.takeIf { it == TvSourceRailKeys.web(action.instanceId) }?.let { focusRow(null, it, inRail = true) }
        }
        listener?.onAction(action)
    }

    /**
     * 长按的动作: 行自己给的; 没给但有详情就开详情弹窗. 列表的行带上它属于谁 (列表此刻显示的就是那个源 / 那一项的一屏);
     * 源那一栏的行 ([inRail]) 不用带.
     */
    private fun longActionOf(row: TvSourceRow, inRail: Boolean): TvSourceAction? =
        row.longAction ?: row.details?.let {
            TvSourceAction.ShowDetails(row.id, inRail, railKey = if (inRail) null else content?.let { c -> paneKeyOf(c) })
        }

    /**
     * 把焦点送到一行: 源那一栏 ([inRail]) 的 [id], 或 [railKey] (一个在线源 / 胶囊那一项) 那一屏列表里的 [id] —— 详情弹窗换条时背后的面板跟着走、
     * 关掉后落在最后看的那一条. 是别的源 / 别的一项的: 换过去 (焦点在内容里就留在内容里, 见 [followRail] / [followSource]),
     * 新的一屏到了再落到那一行 (落点按这一屏的记忆给). 那一行不在了就不动.
     */
    fun focusRow(railKey: String?, id: String, inRail: Boolean) {
        returnPoint = null
        if (inRail) {
            val index = columnAdapter.currentList.indexOfFirst { it.id == id && it.focusable }
            if (index >= 0) moveFocusTo(column, index)
            return
        }
        val content = content ?: return
        if (railKey == null || (paneKeyOf(content) == railKey && rightMatchesSelection(content) && rightKey == incomingRightKey)) {
            val index = listAdapter.currentList.indexOfFirst { it.id == id && it.focusable }
            if (index >= 0) moveFocusTo(list, index)
            return
        }
        focusMemory[railKey] = id
        focusMemory[railKey + LIST_SUFFIX] = id
        pillFallbackScreen = null
        val inContent = regionOf(findFocus()).let { it == Region.LIST || it == Region.PILLS }
        val group = content.sourceGroups[railKey]
        when {
            // 同一组里的另一个源
            group != null && group == content.railKey && columnAdapter.currentList.any { it.id == railKey } -> followSource(railKey)
            // 别的组里的源: 胶囊行与源那一栏一起换过去
            group != null -> {
                val index = railAdapter.currentList.indexOfFirst { it.id == group }
                if (index < 0) return
                focusedRailKey = group
                railAdapter.activeId = group
                rail.setSelectedPositionSmooth(index)
                requestedRailKey = group
                requestedSourceKey = railKey
                listener?.onSourceFocused(group, railKey)
            }

            else -> {
                if (railAdapter.currentList.none { it.id == railKey }) return
                followRail(railKey)
            }
        }
        if (!inContent) {
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
    private var columnAnimator: ValueAnimator? = null

    /**
     * 下面的内容换了一屏 ([branchEntering]) 且排出来了: 原地淡入 —— 不滑. 有源那一栏时排版就按那个源那一行停稳后的位置摆好
     * (见 [branchTargetOffset]), 一出来就在最后的位置.
     */
    private fun revealBranch() {
        if (!branchEntering) return
        if (listAdapter.itemCount > 0 && list.childCount == 0) return // 行还没排出来
        branchEntering = false
        branchHidden = false
        branchAnimator?.cancel()
        // 收着 (焦点在表头上, 或在有源那一栏的胶囊上): 排好了也不出来, 进了这一栏或条目再淡入 (见 updateBranchCollapse)
        if (branchCollapsed) return
        branchAnimator = fadeIn(branchViews)
    }

    /** 见 [branchCollapsed]. [region] 是焦点此刻所在 (停在面板自己身上时不变). */
    private fun updateBranchCollapse(region: Region) {
        if (region == Region.NONE) return
        val columnShown = column.visibility == VISIBLE
        val collapseColumn = columnShown && region == Region.HEADER
        if (collapseColumn != columnCollapsed) {
            columnCollapsed = collapseColumn
            columnAnimator?.cancel()
            if (collapseColumn) {
                column.alpha = 0f
            } else if (!columnEntering) {
                columnHidden = false
                columnAnimator = fadeIn(arrayOf(column))
            }
        }
        val collapse = region == Region.HEADER || (columnShown && region == Region.RAIL)
        if (collapse == branchCollapsed) return
        branchCollapsed = collapse
        columnAdapter.activeId = if (collapse) null else selectedSourceKey()
        branchAnimator?.cancel()
        if (collapse) {
            for (view in branchViews) view.alpha = 0f
        } else if (!branchEntering && !branchHidden) {
            branchAnimator = fadeIn(branchViews)
        }
    }

    /** 源那一栏换了一组 ([columnEntering]) 且排出来了: 原地淡入. */
    private fun revealColumn() {
        if (!columnEntering) return
        if (columnAdapter.itemCount > 0 && column.childCount == 0) return
        columnEntering = false
        columnHidden = false
        columnAnimator?.cancel()
        // 焦点在表头上 (收着): 排好了也不出来, 下到胶囊行再淡入 (见 updateBranchCollapse)
        if (columnCollapsed) return
        columnAnimator = fadeIn(arrayOf(column))
    }

    private fun fadeIn(views: Array<View>): ValueAnimator? {
        if (!style.animated) {
            for (view in views) view.alpha = 1f
            return null
        }
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = style.branchFadeMillis
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener {
                val f = it.animatedValue as Float
                for (view in views) view.alpha = f
            }
            start()
        }
    }

    // ---- 布局 ----

    /**
     * 面板内容, 自上而下: 表头 (状态卡片靠左、命令按钮靠右), 胶囊行 (占满左右边距之间), 下面的内容: 有源那一栏时它贴左边距、占满到面板下缘,
     * 小标题 / 小胶囊 / 列表排在它右边 (往下挪 [branchOffsetY], 顶边对着那个源); 没有时直接排在胶囊行下面. 列表占满可用的宽度, 或按内容定宽;
     * 源那一栏与列表的下缘离画面底部 [TvSourcePanelStyle.paddingBottomPx].
     *
     * 裁剪靠本层的 clipChildren: 它让每个子视图只画在自己的框里, 列表滚上去的行不压到胶囊行上 (框四周留的 focusBleedPx 内边距给聚焦行放大用,
     * 胶囊行左右的内边距是渐隐带). 不给列表设 clipBounds: Android 11 (Shield) 上实测不生效.
     */
    private inner class PanelLayout(context: Context) : ViewGroup(context) {
        /** 面板内容的下缘 (列表的底边; 触屏点在它下面 = 点在画面上). */
        var contentBottom = 0
            private set

        /** 右边的内容 (小标题 / 小胶囊 / 列表) 的左缘. */
        private var branchLeft = 0

        init {
            clipChildren = true
            clipToPadding = false
        }

        private fun headerHeight() = max(statusView.measuredHeight, style.pillHeightPx)

        private fun contentTop() = style.paddingTopPx + headerHeight() + style.headerGapPx + style.railPillHeightPx + style.railGapPx

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
            // 「正在播放」占状态卡片与命令按钮之间剩下的, 放不下的字聚焦时跑马灯
            val playingRoom = (contentWidth - headerWidth - statusView.measuredWidth - s.headerGapPx).coerceAtLeast(0)
            nowPlayingView.measure(MeasureSpec.makeMeasureSpec(playingRoom + bleed * 2, MeasureSpec.AT_MOST), unspecified)
            rail.measure(
                MeasureSpec.makeMeasureSpec(contentWidth + s.fadeLengthPx * 2, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(s.railPillHeightPx + bleed * 2, MeasureSpec.EXACTLY),
            )
            val top = contentTop()
            val bottom = h - s.paddingBottomPx
            val available = (bottom - top).coerceAtLeast(0)
            val beside = column.visibility != GONE
            if (beside) {
                column.measure(
                    MeasureSpec.makeMeasureSpec(s.columnWidthPx + bleed * 2, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(available + bleed * 2, MeasureSpec.EXACTLY),
                )
            }
            branchLeft = s.paddingStartPx + if (beside) s.columnWidthPx + s.columnGapPx else 0
            val branchRoom = (w - s.paddingEndPx - branchLeft).coerceAtLeast(0)
            // 右边的内容: 占满可用的宽度, 或按内容定宽
            val branchWidth = if (branchFitWidthPx > 0) min(branchFitWidthPx, branchRoom) else branchRoom
            drillTitleView.measure(MeasureSpec.makeMeasureSpec(branchWidth, MeasureSpec.AT_MOST), unspecified)
            pills.measure(MeasureSpec.makeMeasureSpec(branchWidth + bleed * 2, MeasureSpec.EXACTLY), unspecified)
            var headerPart = 0
            if (drillTitleView.visibility != GONE) headerPart += drillTitleView.measuredHeight + s.drillTitleBottomGapPx
            if (pills.pillCount > 0) headerPart += pills.measuredHeight - bleed * 2 + s.pillsBottomGapPx
            val cols = listColumns.coerceAtLeast(1)
            val columnWidth = ((branchWidth - s.rowGapPx * (cols - 1)) / cols).coerceAtLeast(1)
            if (listColumnWidth != columnWidth) {
                listColumnWidth = columnWidth
                list.setColumnWidth(columnWidth)
            }
            branchOffsetY = if (beside) branchTargetOffset(available, headerPart, columnWidth) else 0
            list.measure(
                MeasureSpec.makeMeasureSpec(branchWidth + bleed * 2, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((available - branchOffsetY - headerPart).coerceAtLeast(0) + bleed * 2, MeasureSpec.EXACTLY),
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
            val playingLeft = left + statusView.measuredWidth + s.headerGapPx - bleed
            nowPlayingView.layout(playingLeft, headerTop, playingLeft + nowPlayingView.measuredWidth, headerTop + nowPlayingView.measuredHeight)
            y += headerHeight + s.headerGapPx
            rail.layout(left - s.fadeLengthPx, y - bleed, left - s.fadeLengthPx + rail.measuredWidth, y - bleed + rail.measuredHeight)
            y = contentTop()
            if (column.visibility != GONE) {
                column.layout(left - bleed, y - bleed, left - bleed + column.measuredWidth, y - bleed + column.measuredHeight)
            }
            val x = branchLeft
            y += branchOffsetY
            if (drillTitleView.visibility != GONE) {
                drillTitleView.layout(x, y, x + drillTitleView.measuredWidth, y + drillTitleView.measuredHeight)
                y += drillTitleView.measuredHeight + s.drillTitleBottomGapPx
            }
            if (pills.pillCount > 0) {
                pills.layout(x - bleed, y - bleed, x - bleed + pills.measuredWidth, y - bleed + pills.measuredHeight)
                y += pills.measuredHeight - bleed * 2 + s.pillsBottomGapPx
            } else {
                pills.layout(x - bleed, y - bleed, x - bleed + pills.measuredWidth, y - bleed)
            }
            list.layout(x - bleed, y - bleed, x - bleed + list.measuredWidth, y - bleed + list.measuredHeight)
            contentBottom = max(list.bottom, if (column.visibility != GONE) column.bottom else 0) - bleed
            revealBranch()
            revealColumn()
        }
    }

    private fun createRail(context: Context): HorizontalGridView = HorizontalGridView(context).apply {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        itemAnimator = null
        // 选中的那颗靠左停 (对齐线在左内边距处, 见 applyStyle); 两头贴边: 最后几颗不再往左滚, 选中的往右走
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        windowAlignmentOffsetPercent = 0f
        itemAlignmentOffsetPercent = 0f
        setSmoothScrollByBehavior(TvNativeSpringScroll())
        fadingLeftEdge = true
        fadingRightEdge = true
    }

    /** 源那一栏与列表: 聚焦行的中线停在离顶 1/3 处, 两头贴边 (短的不滚). 源那一栏停稳的位置照这个算 (见 [columnSettledTop]). */
    private fun createVerticalList(context: Context): VerticalGridView = TvSourceListGridView(context).apply {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        itemAnimator = null
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        windowAlignmentOffsetPercent = LIST_WINDOW_ALIGN_PERCENT
        itemAlignmentOffsetPercent = LIST_ITEM_ALIGN_PERCENT
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

        /** 源那一栏与列表的窗口对齐: 聚焦行的中线 ([LIST_ITEM_ALIGN_PERCENT]) 停在离顶几成处. */
        const val LIST_WINDOW_ALIGN_PERCENT = 33f
        const val LIST_ITEM_ALIGN_PERCENT = 50f
    }
}

/** 内容里 rail 的这一项在表头 (命令按钮), 不在胶囊行: 「筛选」与几个操作. */
internal fun isCommandKey(key: String): Boolean = key == TvSourceRailKeys.FILTER || key.startsWith(TvSourceRailKeys.ACTION_PREFIX)

/**
 * 表头里只画图标的命令 (名字不画: 修改搜索词确定后的弹窗里有标题, 重新搜索的刷新图标与数据源设置的齿轮一看就懂), 给「正在播放」腾地方.
 * 「筛选」(带当前取值) 与「完整搜索」(带开 / 关) 照常写字.
 */
internal fun isCompactCommand(key: String): Boolean =
    key == TvSourceRailKeys.ACTION_REFRESH || key == TvSourceRailKeys.ACTION_KEYWORDS || key == TvSourceRailKeys.ACTION_SETTINGS

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
 * 一行 (胶囊行的一项、表头的按钮、源那一栏的源、列表的行、小胶囊、剧集方块共用): 自己画圆角底, 自己摆图标、标题、信息、右端字与转圈 —— 不嵌套布局, 列表滚动时测量便宜.
 * 聚焦程度 [focusProgress] 0..1 驱动底色、字色与放大, 失焦比得焦快 (同播放器胶囊).
 *
 * 胶囊行与表头的 [TvSourceRowStyle.Rail] 行宽度按内容 (宽度不是 EXACTLY 时: 图标 + 名字 + 右端字, 不超过给的上限 / [TvSourcePanelStyle.railPillMaxWidthPx]),
 * 高度是 EXACTLY 时用给的; 源那一栏的 Rail 行宽度是这一栏的宽, 高度是 [TvSourcePanelStyle.columnRowHeightPx].
 */
internal class TvSourceRowView(context: Context, private val sketch: Sketch, var style: TvSourcePanelStyle) : ViewGroup(context) {
    var row: TvSourceRow? = null
        private set

    /** 胶囊行 / 源那一栏里下面正在显示的那一项 (焦点不在它身上时也亮一档). */
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

    /** 此刻描着选中的细白边 (见 applyColors); 设备测试核对用. */
    @get:VisibleForTesting
    internal val outlined: Boolean get() = borderPaint.alpha > 0

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

    /** 右端的图标 ([TvSourceRow.trailingIcon]): 与转圈同一个位置、同样大小, 颜色同右端的字. */
    private val trailingIcon = ImageView(context)
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
        trailingIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(icon)
        addView(title)
        addView(meta)
        addView(trailing)
        addView(spinner)
        addView(trailingIcon)
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
        title.visibility = if (compact) GONE else VISIBLE
        val metaText = if (marqueeCapable(row.style)) row.meta else splitMeta(row.meta)
        meta.maxLines = if ('\n' in metaText) 2 else 1
        meta.text = metaText
        meta.visibility = if (showsMeta(row)) VISIBLE else GONE
        trailing.text = row.trailing
        trailing.visibility = if (row.trailing.isEmpty() || row.loading) GONE else VISIBLE
        spinner.visibility = if (row.loading) VISIBLE else GONE
        val trailingBitmap = row.trailingIcon?.takeIf { row.trailing.isEmpty() && !row.loading }?.let { style.icons[it] }
        trailingIcon.setImageBitmap(trailingBitmap)
        trailingIcon.visibility = if (trailingBitmap != null) VISIBLE else GONE

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

    /** 表头里只画图标的命令 (见 isCompactCommand): 名字不画. */
    private val compact: Boolean get() = row?.let { it.style == TvSourceRowStyle.Rail && isCompactCommand(it.id) } == true

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
        trailingIcon.colorFilter = PorterDuffColorFilter(secondary, PorterDuff.Mode.SRC_IN)
        // 描边只标「下面 / 右边显示的是它」与筛选的当前值; 正在播放的靠图标与右端的字 (见 TvSourceRow.playing)
        val chosen = (row.selected && !row.playing) || active
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
        // 只画图标的命令: 图标后面没有名字, 不留图标与名字之间的空
        val iconSlot = if (icon.visibility == GONE) 0 else if (compact) s.iconSizePx else s.iconSizePx + s.iconGapPx
        val iconSpec = MeasureSpec.makeMeasureSpec(s.iconSizePx, MeasureSpec.EXACTLY)
        icon.measure(iconSpec, iconSpec)
        val spinnerSpec = MeasureSpec.makeMeasureSpec(s.spinnerSizePx, MeasureSpec.EXACTLY)
        spinner.measure(spinnerSpec, spinnerSpec)
        trailingIcon.measure(spinnerSpec, spinnerSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val width = if (row?.style == TvSourceRowStyle.Rail && widthMode != MeasureSpec.EXACTLY) {
            // 按内容: 名字与右端字都排得下的宽度
            title.measure(unspecified, unspecified)
            var natural = s.rowPaddingHPx * 2 + iconSlot + if (compact) 0 else title.measuredWidth
            if (trailing.visibility != GONE) {
                trailing.measure(unspecified, unspecified)
                natural += trailing.measuredWidth + s.trailingGapPx
            } else if (spinner.visibility != GONE || trailingIcon.visibility != GONE) {
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
        } else if (spinner.visibility != GONE || trailingIcon.visibility != GONE) {
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
            TvSourceRowStyle.Rail -> when {
                MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
                capsule -> s.railPillHeightPx
                else -> s.columnRowHeightPx // 源那一栏
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
        } else if (trailingIcon.visibility != GONE) {
            val iy = (h - s.spinnerSizePx) / 2
            trailingIcon.layout(right - s.spinnerSizePx, iy, right, iy + s.spinnerSizePx)
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
 * 胶囊行、源那一栏与列表的适配器: 按行 id 做差异更新 (后台线程), 行 id 换成稳定的长整数 id. [horizontal] = 胶囊行 (行按内容定宽、画成胶囊).
 */
internal class TvSourceRowAdapter(
    private val sketch: Sketch,
    var style: TvSourcePanelStyle,
    private val horizontal: Boolean = false,
) : ListAdapter<TvSourceRow, TvSourceRowAdapter.Holder>(Diff) {
    var onRowFocused: ((Int, TvSourceRow) -> Unit)? = null
    var onRowClicked: ((TvSourceRow, Boolean) -> Unit)? = null

    /** 胶囊行 / 源那一栏里下面正在显示的那一项. */
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
            row.trailingIcon != null -> style.spinnerSizePx + style.trailingGapPx
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

/** 停在胶囊上 (不是源那一栏或条目里). */
private val TvSourceFocusPosition.isPill: Boolean get() = sourceKey == null && rowId == null
