/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Immutable
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_NATIVE_FAST_OUT_SLOW_IN
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeSpringScroll
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextView
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeIsInside
import me.him188.ani.app.ui.foundation.lan.QrCodeMatrix
import me.him188.ani.app.ui.foundation.lan.encodeQrCode
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeLerpColor

/**
 * 电视设置页 (原生) 的尺寸 (px) 与配色 (ARGB), 由 rememberTvSettingsStyle 按主题与界面缩放算好. 配色跟应用的深浅色走:
 * 行平时不铺底, 聚焦是主题色 tone 40 的实底 (同弹窗菜单), 左栏里中栏正在显示的那一类铺一层浅底.
 */
@Immutable
data class TvSettingsStyle(
    val paddingStartPx: Int,
    val paddingEndPx: Int,
    val paddingTopPx: Int,
    val paddingBottomPx: Int,
    /** 页面标题与下面三栏的间距. */
    val titleGapPx: Int,
    val railWidthPx: Int,
    /** 说明栏占内容宽度的比例. */
    val infoWidthFraction: Float,
    val columnGapPx: Int,
    val rowGapPx: Int,
    val rowHeightPx: Int,
    val headerTopGapPx: Int,
    val headerBottomGapPx: Int,
    val cornerPx: Float,
    val rowPaddingHPx: Int,
    val trailingGapPx: Int,
    val swatchSizePx: Int,
    val swatchGapPx: Int,
    val drillTitleBottomGapPx: Int,
    val infoTitleGapPx: Int,
    /** 列表左右多留的一截: 聚焦行放大后不被列表的边界裁掉. */
    val focusBleedPx: Int,
    /** 说明栏二维码的边长 (含四周留白; 说明栏窄时按栏宽), 与上下字的间距. */
    val qrSizePx: Int,
    val qrGapPx: Int,
    /** 说明栏标题上面那张图 (头像、标志) 的边长. */
    val infoImageSizePx: Int,
    val pageTitle: TvNativeTextStyle,
    val railTitle: TvNativeTextStyle,
    val rowTitle: TvNativeTextStyle,
    val rowValue: TvNativeTextStyle,
    val header: TvNativeTextStyle,
    val drillTitle: TvNativeTextStyle,
    val infoTitle: TvNativeTextStyle,
    val infoBody: TvNativeTextStyle,
    val activeBg: Int,
    val focusedBg: Int,
    /** 二维码的底与模块 (浅底深码, 老扫码器也认). */
    val qrBg: Int,
    val qrFg: Int,
    val text: Int,
    val textSecondary: Int,
    val focusedText: Int,
    val focusedTextSecondary: Int,
    val focusScale: Float,
    /** 一行放不下的标题聚焦后跑马灯的圈数: 0 = 不跑, -1 = 一直跑. */
    val marqueeRepeat: Int,
    val focusInMillis: Long,
    val focusOutMillis: Long,
    val animated: Boolean,
)

/** 视图报给宿主的事件. 都在主线程. */
interface TvSettingsViewListener {
    /** 左栏焦点换到 [categoryId] (中栏要跟着换). */
    fun onRailFocused(categoryId: String)

    /** 中栏某一行按了确定. */
    fun onRowClicked(rowId: String)

    /** 中栏按返回 / 左键: 宿主退出进去的那一层就返回 true; false = 视图把焦点送回左栏. */
    fun onBackInRight(): Boolean

    /** 焦点停的位置变了 (左栏那一类, 中栏那一行; 在左栏时行为 null), 宿主记下来, 下次打开设置页落回去. */
    fun onPositionChanged(categoryId: String, rowId: String?)

    /** 挪完顺序放下了 (顺序真变了才报): [rowId] 是挪的那一行, [order] 是它那一组 ([TvSettingsRow.moveGroup]) 现在的先后. */
    fun onRowsReordered(rowId: String, order: List<String>)
}

/**
 * 电视设置页 (原生 View): 三栏 —— 左栏分类, 中栏这一类的设置项 (进一层时换成选项), 右边是焦点那一行的说明. 两栏都是 leanback 的
 * [VerticalGridView] (从不回收持焦的那一行, 值变了整表差异更新焦点不丢).
 *
 * **方向键全部在这里判**: 视图挂在 Compose 的 AndroidView 里, 没被消费的方向键会交给 Compose 按屏幕位置找焦点. 左栏上下换类、按右或确定进中栏;
 * 中栏上下走、到头停住, 按左回左栏 (进了一层时退一层); 确定键只认在这里按下的那一次 (从别处进来的那次抬起不算).
 *
 * 数据走 [submit]: 宿主在内容变化时把整页 [TvSettingsContent] 交过来. 中栏换了一屏 (进一层 / 退出来) 而焦点在中栏时, 焦点先停在视图自己身上,
 * 新的一屏排好再落到它的落点 —— 不停的话持焦的行一被删, 系统会把焦点塞给随便哪个视图. 同一屏里只是值变了 (开关) 时原地更新.
 *
 * 挪顺序 (行带 [TvSettingsRow.moveGroup]): 长按确定拿起那一行, 上下键在同一组里挪, 确定 / 返回 / 左键放下, 放下时报
 * [TvSettingsViewListener.onRowsReordered]. 拿着的时候交来的内容先存着, 放下再用; 放下后到新顺序写回之前, 交来的内容按挪好的顺序排
 * (不然会先跳回旧顺序再跳过去).
 */
@SuppressLint("ViewConstructor")
class TvSettingsView(
    context: Context,
    private var style: TvSettingsStyle,
    pageTitle: String,
) : FrameLayout(context) {
    var listener: TvSettingsViewListener? = null

    private val titleView = TvNativeTextView(context)
    private val rail = createGrid(context)
    private val railAdapter = TvSettingsRowAdapter(style)
    private val drillTitleView = TvNativeTextView(context)
    private val list = createGrid(context)
    private val listAdapter = TvSettingsRowAdapter(style)
    private val infoTitleView = TvNativeTextView(context)
    private val infoBodyView = TvNativeTextView(context)

    /** 说明栏的二维码与它上下的字 (见 [TvSettingsQr]): 状态 (码上面) / 码 / 码下面的一行. */
    private val infoQrStatusView = TvNativeTextView(context)
    private val infoQrView = TvSettingsQrView(context)
    private val infoQrCaptionView = TvNativeTextView(context)
    private var infoQr: TvSettingsQr? = null

    /** 说明栏标题上面的图 (见 [TvImage]): 由 [imageLoader] 解码, 解完时焦点还在要它的那一行才放上. */
    private val infoImageView = TvSettingsImageView(context)
    private var infoImage: TvImage? = null

    /** 解码说明栏的图 (页面给, 可以当场回调); 没给时不画图. */
    var imageLoader: ((TvImage, (Bitmap?) -> Unit) -> Unit)? = null

    private var content: TvSettingsContent? = null

    /** 中栏此刻排着的是哪一屏 (差异更新提交之后才换); [incomingRightKey] 是最新交来的那一屏. */
    private var rightKey: String? = null
    private var incomingRightKey: String? = null

    /** 交给列表的行还没提交 (差异更新在后台算): 这时列表里还是旧的行, 不能按它落焦点. */
    private var rowsPending = false

    /** 每一屏中栏上次聚焦的行: 退出一层、切回某一类时落回原处. */
    private val focusMemory = HashMap<String, String>()

    private var pendingEntry = false
    private var pendingEnterRight = false
    private var pendingRightFocus = false

    /** 左栏此刻聚焦的那一类 (内容里的 railKey 要等状态建完才跟上). */
    private var focusedRailKey: String? = null

    private var pendingFocusGrid: VerticalGridView? = null
    private var pendingFocusId: String? = null

    /** 确定键按下时焦点所在那一行 (抬起时还在它上面才算一次点击). */
    private var pressedRowId: String? = null

    /** 这一次按住已经当长按用掉了 (拿起了一行): 抬起不算点击. */
    private var longPressConsumed = false

    /** 正拿着挪的那一行与它那一组此刻的排法 (中栏全部行); null = 没在挪. */
    private var moving: TvSettingsRow? = null
    private var moveRows: MutableList<TvSettingsRow> = ArrayList()
    private var moveOrigin: List<String> = emptyList()

    /** 拿着时交来的内容, 放下后再用. */
    private var deferred: TvSettingsContent? = null

    /** 放下后、写回生效前: 这一组按这个顺序排 (组, 行 id 的先后) 与它的期限. */
    private var settledOrder: Pair<String, List<String>>? = null
    private var settledUntil = 0L

    /** 打开时要落回的位置 (上次停的分类与中栏那一行); 落过一次就作废. */
    var resumeRow: Pair<String, String>? = null

    /** 打开时直接进中栏 (落在这一屏的落点上), 而不是停在左栏: 从别处直接跳到某一类时用 (如「去登录」到账号那一类). */
    var enterListOnOpen: Boolean = false

    /** 盖着原来的设置页时: 藏起来, 焦点进不来. */
    var suspended: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            visibility = if (value) INVISIBLE else VISIBLE
            descendantFocusability = if (value) FOCUS_BLOCK_DESCENDANTS else FOCUS_AFTER_DESCENDANTS
        }

    init {
        // 每一栏只画在自己的框里 (框四周留了 focusBleedPx 给聚焦行放大): 滚上去的行不压到页面标题上、不越过底边
        clipChildren = true
        clipToPadding = false
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        titleView.text = pageTitle
        titleView.maxLines = 1
        drillTitleView.maxLines = 1
        drillTitleView.ellipsize = TextUtils.TruncateAt.END
        drillTitleView.visibility = GONE
        infoTitleView.maxLines = 2
        infoTitleView.ellipsize = TextUtils.TruncateAt.END
        infoBodyView.ellipsize = TextUtils.TruncateAt.END
        infoQrStatusView.maxLines = 3
        infoQrCaptionView.maxLines = 2
        for (v in arrayOf(infoQrStatusView, infoQrView, infoQrCaptionView, infoImageView)) v.visibility = GONE

        rail.adapter = railAdapter
        list.adapter = listAdapter
        railAdapter.onRowFocused = { row -> onRailRowFocused(row) }
        listAdapter.onRowFocused = { row ->
            rightKey?.let { focusMemory[it] = row.id }
            val held = moving
            if (held != null) showMoveHint(held) else showInfo(row.id)
            reportPosition()
        }
        // 触屏: 左栏点一下 = 换到那一类并进中栏, 中栏点一下 = 确定
        railAdapter.onRowClicked = { row ->
            TvSettingsRowIds.parseCategory(row.id)?.let { listener?.onRailFocused(it) }
            enterRight()
        }
        listAdapter.onRowClicked = { row -> listener?.onRowClicked(row.id) }
        rail.addOnLayoutCompletedListener {
            resolvePendingFocus(rail)
            resolvePending()
        }
        list.addOnLayoutCompletedListener {
            resolvePendingFocus(list)
            resolvePending()
        }

        // 字的框按内容定高 (WRAP_CONTENT): 默认的 MATCH_PARENT 下换字不重新测量, 说明从一行变两行时第二行被截掉
        for (text in arrayOf(titleView, drillTitleView, infoTitleView, infoBodyView, infoQrStatusView, infoQrCaptionView)) {
            text.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        addView(titleView)
        addView(rail)
        addView(drillTitleView)
        addView(list)
        addView(infoImageView)
        addView(infoTitleView)
        addView(infoBodyView)
        addView(infoQrStatusView)
        addView(infoQrView)
        addView(infoQrCaptionView)
        applyStyle(style)
    }

    fun applyStyle(style: TvSettingsStyle) {
        val changed = this.style != style
        this.style = style
        style.pageTitle.applyTo(titleView)
        style.drillTitle.applyTo(drillTitleView)
        style.infoTitle.applyTo(infoTitleView)
        style.infoBody.applyTo(infoBodyView)
        style.infoBody.applyTo(infoQrStatusView)
        infoQrStatusView.setTextColor(style.text)
        style.infoBody.applyTo(infoQrCaptionView)
        infoQrView.setColors(style.qrBg, style.qrFg, style.cornerPx)
        rail.verticalSpacing = style.rowGapPx
        list.verticalSpacing = style.rowGapPx
        val bleed = style.focusBleedPx
        rail.setPadding(bleed, bleed, bleed, bleed)
        list.setPadding(bleed, bleed, bleed, bleed)
        railAdapter.style = style
        listAdapter.style = style
        if (changed) {
            railAdapter.notifyItemRangeChanged(0, railAdapter.itemCount)
            listAdapter.notifyItemRangeChanged(0, listAdapter.itemCount)
            requestLayout()
        }
    }

    /** 新内容. 见类注释. */
    fun submit(content: TvSettingsContent) {
        if (moving != null) {
            deferred = content
            return
        }
        val previous = this.content
        this.content = content

        railAdapter.activeId = content.railKey
        railAdapter.submitList(content.rail) {
            // 焦点不在左栏 (落回中栏那一行): 左栏直接对到中栏所属的那一类 —— 否则按左回去时整栏一跳
            if (regionOf(findFocus()) != Region.RAIL) {
                val index = content.rail.indexOfFirst { it.id == content.railKey }
                if (index >= 0 && rail.selectedPosition != index) rail.setSelectedPosition(index)
            }
            resolvePending()
        }

        val right = content.right
        val newScreen = right.key != incomingRightKey
        incomingRightKey = right.key
        // 换了一屏, 或同一屏里持焦的那一行没了 (如登录成功后登录那几行换成账号信息): 焦点先停在自己身上, 排好再落到落点.
        // 不停的话持焦的行一被删, 系统会把焦点塞给随便哪个视图
        val focusedGone = focusedRowId()?.let { id -> right.rows.none { it.id == id } } == true
        if ((newScreen || focusedGone) && regionOf(findFocus()) == Region.LIST) {
            park()
            pendingRightFocus = true
        }
        drillTitleView.text = right.title?.let { "‹  $it" }.orEmpty()
        val drillVisible = if (right.title.isNullOrEmpty()) GONE else VISIBLE
        if (drillTitleView.visibility != drillVisible) {
            drillTitleView.visibility = drillVisible
            requestLayout()
        }
        rowsPending = true
        listAdapter.submitList(withSettledOrder(right.rows)) {
            rowsPending = false
            if (rightKey != right.key) {
                rightKey = right.key
                list.scrollToPosition(0)
            }
            showInfo(focusedRowId())
            resolvePending()
        }
        if (previous == null) showInfo(focusedRowId())
        resolvePending()
    }

    /** 打开页面 / 焦点不在视图里时: 落左栏选中的那一类 (有 [resumeRow] 时落回中栏那一行). 内容没到就等内容到了再送. */
    fun requestEntryFocus() {
        pendingEntry = true
        resolvePending()
    }

    /** 盖着的整页关掉后: 焦点回到打开它的那一行 (中栏这一屏上次停的行); 中栏没有能停的行时落左栏. */
    fun requestReturnFocus() {
        val content = content
        if (content == null || content.right.rows.none { it.focusable }) {
            requestEntryFocus()
            return
        }
        enterRight()
    }

    /**
     * 返回键. true = 视图处理了 (退出进去的那一层 / 从中栏回左栏); false = 焦点在左栏, 该退出设置页了.
     */
    fun handleBack(): Boolean = when {
        moving != null -> {
            drop()
            true
        }

        else -> handleBackNotMoving()
    }

    private fun handleBackNotMoving(): Boolean = when (regionOf(findFocus())) {
        Region.LIST -> {
            if (listener?.onBackInRight() != true) focusRail(content?.railKey)
            true
        }

        Region.NONE -> isFocused // 停着等新的一屏: 这一下吞掉
        Region.RAIL -> false
    }

    // ---- 焦点 ----

    private enum class Region { NONE, RAIL, LIST }

    private fun regionOf(view: View?): Region = when {
        view == null -> Region.NONE
        tvNativeIsInside(view, rail) -> Region.RAIL
        tvNativeIsInside(view, list) -> Region.LIST
        else -> Region.NONE
    }

    private fun focusedRowId(): String? = (findFocus() as? TvSettingsRowView)?.row?.id

    private fun onRailRowFocused(row: TvSettingsRow) {
        focusedRailKey = row.id
        showInfo(row.id)
        val categoryId = TvSettingsRowIds.parseCategory(row.id) ?: return
        if (content?.railKey != row.id) {
            railAdapter.activeId = row.id
            listener?.onRailFocused(categoryId)
        }
        reportPosition()
    }

    private fun reportPosition() {
        val focused = findFocus() as? TvSettingsRowView ?: return
        val railKey = content?.railKey ?: return
        val categoryId = TvSettingsRowIds.parseCategory(railKey) ?: return
        when (regionOf(focused)) {
            Region.RAIL -> TvSettingsRowIds.parseCategory(focused.row?.id ?: return)?.let { listener?.onPositionChanged(it, null) }
            Region.LIST -> if (rightKey == railKey) listener?.onPositionChanged(categoryId, focused.row?.id)
            Region.NONE -> {}
        }
    }

    /**
     * 说明栏换成 [rowId] 那一行的说明. null (焦点正停在视图自己身上, 换屏中) 时不动, 免得一闪而空.
     */
    private fun showInfo(rowId: String?) {
        if (rowId == null) return
        val info = content?.info?.get(rowId)
        setInfo(info?.title.orEmpty(), info?.body.orEmpty(), info?.qr, info?.image)
    }

    /**
     * 换说明栏的字与二维码. 焦点常在布局途中落下 (布局完成回调里送焦): 那时字视图要重新测量的标记会在这一轮排到它时被清掉,
     * 下一轮按旧的测量结果排 (说明的框还是上一段字的高度, 多出的行被截掉) —— 排到下一帧, 直接对字视图再要一次布局.
     */
    private fun setInfo(title: String, body: String, qr: TvSettingsQr? = null, image: TvImage? = null) {
        if (infoTitleView.text.toString() == title && infoBodyView.text.toString() == body && infoQr == qr && infoImage == image) return
        if (infoImage != image) {
            infoImage = image
            infoImageView.setImage(null, round = false)
            infoImageView.visibility = if (image != null && imageLoader != null) VISIBLE else GONE
            if (image != null) imageLoader?.invoke(image) { bitmap -> if (infoImage == image) infoImageView.setImage(bitmap, image.round) }
        }
        infoTitleView.text = title
        infoBodyView.text = body
        infoQr = qr
        infoQrStatusView.text = qr?.status.orEmpty()
        infoQrStatusView.visibility = if (qr?.status.isNullOrEmpty()) GONE else VISIBLE
        infoQrView.setContent(qr?.content)
        infoQrView.visibility = if (qr?.content == null) GONE else VISIBLE
        infoQrCaptionView.text = qr?.caption.orEmpty()
        infoQrCaptionView.visibility = if (qr?.content == null || qr.caption.isEmpty()) GONE else VISIBLE
        val relayout = {
            for (v in arrayOf(infoImageView, infoTitleView, infoBodyView, infoQrStatusView, infoQrView, infoQrCaptionView)) v.requestLayout()
        }
        if (isInLayout || isLayoutRequested) post(relayout) else relayout()
    }

    private fun resolvePending() {
        val content = content ?: return
        if (pendingRightFocus) {
            if (rightKey != incomingRightKey || rowsPending) return
            when (focusRightTarget(content)) {
                FocusResult.Focused -> {
                    pendingRightFocus = false
                    unpark()
                }

                FocusResult.Pending -> {}
                FocusResult.None -> {
                    pendingRightFocus = false
                    unpark()
                    focusRail(content.railKey)
                }
            }
            return
        }
        if (pendingEnterRight) {
            if (!rightMatchesRail(content) || rightKey != incomingRightKey) return
            if (focusRightTarget(content) != FocusResult.Pending) pendingEnterRight = false
            return
        }
        if (pendingEntry && isAttachedToWindow && !suspended) {
            val resume = resumeRow
            if (resume != null && content.railKey == TvSettingsRowIds.category(resume.first)) {
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
            if (enterListOnOpen) {
                if (rightKey != incomingRightKey || !rightMatchesRail(content)) return
                when (focusRightTarget(content)) {
                    FocusResult.Focused -> {
                        enterListOnOpen = false
                        pendingEntry = false
                    }

                    FocusResult.Pending -> {}
                    // 这一屏还没有能停的行 (值还没到): 等下一次内容, 等太久就落左栏
                    FocusResult.None -> {
                        removeCallbacks(enterListFallback)
                        postDelayed(enterListFallback, ENTER_LIST_TIMEOUT_MILLIS)
                    }
                }
                return
            }
            if (focusRail(content.railKey)) pendingEntry = false
        }
    }

    private enum class FocusResult { Focused, Pending, None }

    private fun rightMatchesRail(content: TvSettingsContent): Boolean {
        val railKey = content.railKey ?: return false
        if (focusedRailKey != null && focusedRailKey != railKey && regionOf(findFocus()) == Region.RAIL) return false
        val key = rightKey ?: return false
        return key == railKey || key.startsWith("$railKey/")
    }

    /** 中栏的落点: 这一屏上次停的 → 内容给的落点 (当前值) → 第一条能聚焦的行. */
    private fun focusRightTarget(content: TvSettingsContent): FocusResult {
        val rows = listAdapter.currentList
        val remembered = rightKey?.let { focusMemory[it] }
        val index = sequenceOf(remembered, content.right.focusId)
            .filterNotNull()
            .map { id -> rows.indexOfFirst { it.id == id && it.focusable } }
            .firstOrNull { it >= 0 }
            ?: rows.indexOfFirst { it.focusable }
        if (index < 0) return FocusResult.None
        return if (moveFocusTo(list, index)) FocusResult.Focused else FocusResult.Pending
    }

    private fun focusRail(key: String?): Boolean {
        val rows = railAdapter.currentList
        val index = rows.indexOfFirst { it.id == key }.takeIf { it >= 0 } ?: rows.indexOfFirst { it.focusable }
        if (index < 0) return false
        return moveFocusTo(rail, index)
    }

    /**
     * 排出来了当场给; 没排出来 (远处的行、刚换的一屏) 就直接跳到那个位置, 排好时 (布局完成回调) 再给, 返回 false.
     * 不平滑滚过去: 平滑滚动不触发布局完成回调, 栏里没有焦点时 leanback 滚到了也不给焦点 (同选源面板).
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

    /** 焦点暂停在视图自己身上 (中栏换屏期间). */
    private fun park() {
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        isFocusable = true
        requestFocus()
        removeCallbacks(parkWatchdog)
        postDelayed(parkWatchdog, PARK_TIMEOUT_MILLIS)
    }

    private val enterListFallback = Runnable {
        if (!enterListOnOpen) return@Runnable
        enterListOnOpen = false
        resolvePending()
    }

    /** 停太久 (新的一屏 / 落点一直没等到): 放弃这次落点, 焦点回左栏, 记一笔. 正常流程到不了这里. */
    private val parkWatchdog = Runnable {
        if (!isFocused) return@Runnable
        Log.w(LOG_TAG, "focus parked for ${PARK_TIMEOUT_MILLIS}ms, releasing: rightKey=$rightKey incoming=$incomingRightKey")
        pendingRightFocus = false
        focusRail(content?.railKey)
        unpark()
    }

    private fun unpark() {
        removeCallbacks(parkWatchdog)
        if (!isFocusable) return
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        val focusedSelf = isFocused
        isFocusable = false
        if (focusedSelf && !hasFocus()) focusRail(content?.railKey)
    }

    /** 新排出来的行不往上报: 系统会拿它当「有可聚焦的视图了」把焦点塞过去, 抢在视图自己的落点之前. */
    override fun focusableViewAvailable(v: View?) = Unit

    // ---- 按键 ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (suspended) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!dpad && !confirm) return super.dispatchKeyEvent(event)
        if (dpad) {
            if (event.action == KeyEvent.ACTION_DOWN) navigate(code)
            return true
        }
        handleConfirm(event)
        return true
    }

    private fun navigate(code: Int) {
        // 焦点正停在视图自己身上 (中栏换屏中): 落点马上会给出去, 这一下不动
        if (isFocused) return
        if (moving != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> moveStep(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveStep(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> drop()
            }
            return
        }
        pendingFocusGrid = null
        pendingFocusId = null
        val focused = findFocus()
        when (regionOf(focused)) {
            Region.NONE -> requestEntryFocus()
            Region.RAIL -> {
                val index = rail.getChildAdapterPosition(rowViewOf(focused, rail) ?: return)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> stepVertical(rail, railAdapter, index, -1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> stepVertical(rail, railAdapter, index, 1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> enterRight()
                }
            }

            Region.LIST -> {
                val view = rowViewOf(focused, list) ?: return
                val index = list.getChildAdapterPosition(view)
                if (index < 0) return
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> stepVertical(list, listAdapter, index, -1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> stepVertical(list, listAdapter, index, 1)
                    // 左键 = 退一层: 进了选项时退回设置项, 否则回左栏
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (listener?.onBackInRight() != true) focusRail(content?.railKey)
                }
            }
        }
    }

    /** 竖着走一格, 跳过组标题. 到头了不动, 返回 false. */
    private fun stepVertical(grid: VerticalGridView, adapter: TvSettingsRowAdapter, index: Int, direction: Int): Boolean {
        val rows = adapter.currentList
        var i = index + direction
        while (i in rows.indices && !rows[i].focusable) i += direction
        if (i !in rows.indices) {
            // 往上到顶时把上面的组标题露出来
            if (direction < 0 && index > 0) grid.smoothScrollToPosition(0)
            return false
        }
        moveFocusTo(grid, i)
        return true
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
        val rowView = findFocus() as? TvSettingsRowView
        val row = rowView?.row
        when (event.action) {
            KeyEvent.ACTION_DOWN -> when {
                event.repeatCount == 0 -> if (row != null) {
                    pressedRowId = row.id
                    longPressConsumed = false
                    rowView.isPressed = true
                }

                // 按住不放 (按键重复): 能挪的行拿起来
                !longPressConsumed && moving == null && row != null && pressedRowId == row.id &&
                    row.moveGroup != null && regionOf(rowView) == Region.LIST -> {
                    longPressConsumed = true
                    rowView.isPressed = false
                    pickUp(row)
                }
            }

            KeyEvent.ACTION_UP -> {
                val pressed = pressedRowId ?: return // 按下不是在这里 (从别处进来的那次确定键): 不算
                pressedRowId = null
                rowView?.isPressed = false
                if (longPressConsumed) {
                    longPressConsumed = false
                    return
                }
                if (moving != null) {
                    drop()
                    return
                }
                if (row == null || pressed != row.id) return
                when (regionOf(rowView)) {
                    Region.RAIL -> enterRight()
                    Region.LIST -> listener?.onRowClicked(row.id)
                    Region.NONE -> {}
                }
            }
        }
    }

    // ---- 挪顺序 ----

    private fun pickUp(row: TvSettingsRow) {
        moving = row
        moveRows = listAdapter.currentList.toMutableList()
        moveOrigin = moveRows.filter { it.moveGroup == row.moveGroup }.map { it.id }
        listAdapter.movingId = row.id
        showMoveHint(row)
    }

    /** 拿着的那一行往上 / 下挪一格 (只在同一组里); 焦点跟着它. */
    private fun moveStep(direction: Int) {
        val row = moving ?: return
        val index = moveRows.indexOfFirst { it.id == row.id }
        val target = index + direction
        if (index < 0 || target !in moveRows.indices || moveRows[target].moveGroup != row.moveGroup) return
        moveRows[index] = moveRows[target].also { moveRows[target] = moveRows[index] }
        listAdapter.submitList(moveRows.toList()) {
            if (moving?.id != row.id) return@submitList
            val now = moveRows.indexOfFirst { it.id == row.id }
            if (focusedRowId() != row.id) moveFocusTo(list, now) else list.setSelectedPosition(now)
        }
    }

    /** 放下: 顺序变了就报给宿主, 并在写回生效前按挪好的顺序排; 拿着时存下的内容这时再用. */
    private fun drop() {
        val row = moving ?: return
        moving = null
        listAdapter.movingId = null
        val group = row.moveGroup
        val order = moveRows.filter { it.moveGroup == group }.map { it.id }
        if (group != null && order != moveOrigin) {
            settledOrder = group to order
            settledUntil = System.currentTimeMillis() + SETTLE_TIMEOUT_MILLIS
            listener?.onRowsReordered(row.id, order)
        }
        showInfo(row.id)
        val pending = deferred
        deferred = null
        if (pending != null) submit(pending)
    }

    /** 放下后、写回生效前交来的行: 那一组按挪好的顺序排 (行对得上才排); 交来的已经是这个顺序或过了期限就不再管. */
    private fun withSettledOrder(rows: List<TvSettingsRow>): List<TvSettingsRow> {
        val (group, order) = settledOrder ?: return rows
        val groupRows = rows.filter { it.moveGroup == group }
        if (groupRows.map { it.id } == order || System.currentTimeMillis() > settledUntil ||
            groupRows.map { it.id }.toSet() != order.toSet()
        ) {
            settledOrder = null
            return rows
        }
        val byId = groupRows.associateBy { it.id }
        val sorted = order.map { byId.getValue(it) }.iterator()
        return rows.map { if (it.moveGroup == group) sorted.next() else it }
    }

    private fun showMoveHint(row: TvSettingsRow) {
        setInfo(row.title, content?.moveHint.orEmpty())
    }

    private fun rowViewOf(view: View?, grid: RecyclerView): View? {
        var v: View? = view
        while (v != null && v.parent !== grid) v = v.parent as? View
        return v
    }

    // ---- 布局 ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val s = style
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val contentWidth = (w - s.paddingStartPx - s.paddingEndPx).coerceAtLeast(0)
        val infoWidth = (contentWidth * s.infoWidthFraction).toInt()
        val listWidth = (contentWidth - s.railWidthPx - infoWidth - s.columnGapPx * 2).coerceAtLeast(0)
        // 说明栏的字常在布局途中换 (见 setInfo): 每次都重新量, 不用旧字的测量结果
        for (text in arrayOf(drillTitleView, infoTitleView, infoBodyView, infoQrStatusView, infoQrCaptionView)) text.forceLayout()
        titleView.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.AT_MOST), unspecified)
        val top = s.paddingTopPx + titleView.measuredHeight + s.titleGapPx
        val columnHeight = (h - s.paddingBottomPx - top).coerceAtLeast(0)
        val bleed = s.focusBleedPx
        rail.measure(
            MeasureSpec.makeMeasureSpec(s.railWidthPx + bleed * 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(columnHeight + bleed * 2, MeasureSpec.EXACTLY),
        )
        var listTop = 0
        if (drillTitleView.visibility != GONE) {
            drillTitleView.measure(MeasureSpec.makeMeasureSpec(listWidth, MeasureSpec.AT_MOST), unspecified)
            listTop = drillTitleTakes()
        }
        list.measure(
            MeasureSpec.makeMeasureSpec(listWidth + bleed * 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((columnHeight - listTop).coerceAtLeast(0) + bleed * 2, MeasureSpec.EXACTLY),
        )
        var imageTakes = 0
        if (infoImageView.visibility != GONE) {
            val side = minOf(s.infoImageSizePx, infoWidth)
            infoImageView.measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
            imageTakes = side + s.qrGapPx
        }
        infoTitleView.measure(MeasureSpec.makeMeasureSpec(infoWidth, MeasureSpec.EXACTLY), unspecified)
        // 图、二维码与它上下的字先占位, 说明正文用剩下的高度
        var qrTakes = imageTakes
        if (infoQrStatusView.visibility != GONE) {
            infoQrStatusView.measure(MeasureSpec.makeMeasureSpec(infoWidth, MeasureSpec.EXACTLY), unspecified)
            qrTakes += infoQrStatusView.measuredHeight + s.qrGapPx
        }
        if (infoQrCaptionView.visibility != GONE) {
            infoQrCaptionView.measure(MeasureSpec.makeMeasureSpec(infoWidth, MeasureSpec.EXACTLY), unspecified)
            qrTakes += infoQrCaptionView.measuredHeight + s.qrGapPx
        }
        val lineHeight = (if (s.infoBody.lineHeightPx > 0) s.infoBody.lineHeightPx else infoBodyView.lineHeight).coerceAtLeast(1)
        if (infoQrView.visibility != GONE) {
            // 码照常画满; 连图带码放不下时缩小, 给说明留出 INFO_BODY_MIN_LINES 行 (至少画到原来的 2/3, 再小不好扫)
            val room = columnHeight - infoTitleView.measuredHeight - s.infoTitleGapPx - qrTakes - s.qrGapPx - INFO_BODY_MIN_LINES * lineHeight
            val side = minOf(s.qrSizePx, infoWidth, maxOf(room, s.qrSizePx * 2 / 3))
            infoQrView.measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
            qrTakes += side + s.qrGapPx
        }
        val bodyHeight = (columnHeight - infoTitleView.measuredHeight - s.infoTitleGapPx - qrTakes).coerceAtLeast(0)
        // 说明放不下时按整行截断 (不露半行). 行数要在测量之前设: 测完再改会被当成下一轮的事, 这一轮按旧的行数排
        val lines = (bodyHeight / lineHeight).coerceAtLeast(1)
        if (infoBodyView.maxLines != lines) infoBodyView.maxLines = lines
        infoBodyView.measure(
            MeasureSpec.makeMeasureSpec(infoWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bodyHeight, MeasureSpec.AT_MOST),
        )
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        val w = r - l
        val left = s.paddingStartPx
        titleView.layout(left, s.paddingTopPx, left + titleView.measuredWidth, s.paddingTopPx + titleView.measuredHeight)
        val top = s.paddingTopPx + titleView.measuredHeight + s.titleGapPx
        val bleed = s.focusBleedPx
        rail.layout(left - bleed, top - bleed, left - bleed + rail.measuredWidth, top - bleed + rail.measuredHeight)
        val listLeft = left + s.railWidthPx + s.columnGapPx
        var listTop = top
        if (drillTitleView.visibility != GONE) {
            drillTitleView.layout(
                listLeft + s.rowPaddingHPx, top,
                listLeft + s.rowPaddingHPx + drillTitleView.measuredWidth, top + drillTitleView.measuredHeight,
            )
            listTop += drillTitleTakes()
        }
        list.layout(listLeft - bleed, listTop - bleed, listLeft - bleed + list.measuredWidth, listTop - bleed + list.measuredHeight)
        val infoLeft = w - s.paddingEndPx - infoTitleView.measuredWidth
        var infoTop = top + s.focusBleedPx / 2
        if (infoImageView.visibility != GONE) {
            infoImageView.layout(infoLeft, infoTop, infoLeft + infoImageView.measuredWidth, infoTop + infoImageView.measuredHeight)
            infoTop += infoImageView.measuredHeight + s.qrGapPx
        }
        infoTitleView.layout(infoLeft, infoTop, infoLeft + infoTitleView.measuredWidth, infoTop + infoTitleView.measuredHeight)
        val bodyTop = infoTop + infoTitleView.measuredHeight + s.infoTitleGapPx
        infoBodyView.layout(infoLeft, bodyTop, infoLeft + infoBodyView.measuredWidth, bodyTop + infoBodyView.measuredHeight)
        var y = bodyTop + infoBodyView.measuredHeight + s.qrGapPx
        for (v in arrayOf(infoQrStatusView, infoQrView, infoQrCaptionView)) {
            if (v.visibility == GONE) continue
            v.layout(infoLeft, y, infoLeft + v.measuredWidth, y + v.measuredHeight)
            y += v.measuredHeight + s.qrGapPx
        }
    }

    /** 小标题占掉的高度: 列表上方多留的那截 (focusBleedPx, 给聚焦行放大) 也让开, 滚上去的行不压到小标题上. */
    private fun drillTitleTakes(): Int = drillTitleView.measuredHeight + style.drillTitleBottomGapPx + style.focusBleedPx

    /**
     * 两栏的列表. 四周留了 focusBleedPx 给聚焦行放大 (不裁), 但上下只放行放大溢出的那一点: 滚出去的行不在留白里露出一截字.
     */
    private fun createGrid(context: Context): VerticalGridView = object : VerticalGridView(context) {
        override fun dispatchDraw(canvas: Canvas) {
            val overflow = (style.rowHeightPx * (style.focusScale - 1f) / 2f).toInt() + 1
            val save = canvas.save()
            canvas.clipRect(0, paddingTop - overflow, width, height - paddingBottom + overflow)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        }
    }.apply {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        itemAnimator = null
        // 聚焦行停在离顶 1/3 处, 两头贴边 (列表短时不滚)
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        windowAlignmentOffsetPercent = 33f
        itemAlignmentOffsetPercent = 50f
        setSmoothScrollByBehavior(TvNativeSpringScroll())
    }

    private companion object {
        const val LOG_TAG = "TvSettings"

        /** 焦点停在视图自己身上最多多久, 见 [parkWatchdog]. */
        const val PARK_TIMEOUT_MILLIS = 800L

        /** 打开时要进中栏但那一屏一直没有能停的行: 最多等这么久, 之后落左栏 (见 [enterListOnOpen]). */
        const val ENTER_LIST_TIMEOUT_MILLIS = 1500L

        /** 放下后最多等多久新顺序写回 (见 [withSettledOrder]). */
        const val SETTLE_TIMEOUT_MILLIS = 3000L

        /** 说明栏有二维码时至少给说明留的行数 (不够时缩小二维码). */
        const val INFO_BODY_MIN_LINES = 5
    }
}

/**
 * 说明栏的二维码: 圆角底上画模块, 四周留 4 个模块宽的空白 (扫码器靠它定位). 模块边长取整像素, 相邻模块之间不出灰缝.
 */
internal class TvSettingsQrView(context: Context) : View(context) {
    private var content: String? = null
    private var matrix: QrCodeMatrix? = null
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modulePaint = Paint()
    private val bgRect = RectF()
    private var cornerPx = 0f

    fun setColors(bg: Int, fg: Int, corner: Float) {
        bgPaint.color = bg
        modulePaint.color = fg
        cornerPx = corner
        invalidate()
    }

    fun setContent(text: String?) {
        if (text == content) return
        content = text
        matrix = text?.let { encodeQrCode(it) }
        invalidate()
    }

    /** 此刻画的内容 (测试用). */
    val shownContent: String? get() = content

    override fun onDraw(canvas: Canvas) {
        val m = matrix ?: return
        val side = minOf(width, height)
        bgRect.set(0f, 0f, side.toFloat(), side.toFloat())
        canvas.drawRoundRect(bgRect, cornerPx, cornerPx, bgPaint)
        val cell = side / (m.size + QUIET_MODULES * 2)
        if (cell <= 0) return
        val offset = (side - cell * m.size) / 2f
        for (y in 0 until m.size) {
            for (x in 0 until m.size) {
                if (!m[x, y]) continue
                val left = offset + x * cell
                val top = offset + y * cell
                canvas.drawRect(left, top, left + cell, top + cell, modulePaint)
            }
        }
    }

    private companion object {
        /** 四周留白的模块数 (规范至少 4). */
        const val QUIET_MODULES = 4
    }
}

/** 说明栏标题上面的图: 按边长缩放画满, [setImage] 的 round 为真时裁成圆形 (头像), 否则原样 (标志). */
internal class TvSettingsImageView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var round = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
    private val dst = RectF()

    fun setImage(bitmap: Bitmap?, round: Boolean) {
        if (this.bitmap === bitmap && this.round == round) return
        this.bitmap = bitmap
        this.round = round
        paint.shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidate()
    }

    /** 此刻画着图 (测试用). */
    val hasImage: Boolean get() = bitmap != null

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        if (round) {
            shaderMatrix.setScale(side / b.width, side / b.height)
            paint.shader?.setLocalMatrix(shaderMatrix)
            canvas.drawCircle(side / 2, side / 2, side / 2, paint)
        } else {
            dst.set(0f, 0f, side, side)
            canvas.drawBitmap(b, null, dst, paint)
        }
    }
}

/**
 * 一行 (左栏分类、组标题、设置项、选项): 自己画圆角底与色块, 摆标题与行尾的字 —— 不嵌套布局.
 * 聚焦程度 0..1 驱动底色、字色与放大, 失焦比得焦快.
 */
internal class TvSettingsRowView(context: Context, var style: TvSettingsStyle) : ViewGroup(context) {
    var row: TvSettingsRow? = null
        private set

    /** 左栏里中栏正在显示的那一类 (焦点不在它身上时铺一层浅底). */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyColors()
        }

    /** 正被拿着挪顺序: 行尾换成上下箭头. */
    var moving: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            row?.let { updateTrailing(it) }
        }

    private val title = TvNativeTextView(context)
    private val trailing = TvNativeTextView(context)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()
    private var focusProgress = 0f
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        trailing.maxLines = 1
        trailing.gravity = Gravity.END
        addView(title)
        addView(trailing)
    }

    fun bind(row: TvSettingsRow, style: TvSettingsStyle) {
        val restyled = this.style != style
        this.style = style
        this.row = row
        isFocusable = row.focusable
        isClickable = row.focusable
        when (row.kind) {
            TvSettingsRowKind.Category -> style.railTitle.applyTo(title)
            TvSettingsRowKind.Header -> style.header.applyTo(title)
            else -> style.rowTitle.applyTo(title)
        }
        style.rowValue.applyTo(trailing)
        title.text = row.title
        updateTrailing(row)
        swatchPaint.color = row.swatch ?: 0
        if (restyled) requestLayout()
        applyColors()
        setMarquee(isFocused)
        requestLayout()
    }

    private fun updateTrailing(row: TvSettingsRow) {
        val mark = when {
            moving -> "↑↓"
            row.checked -> "✓"
            row.chevron -> "›"
            else -> ""
        }
        val text = when {
            row.value.isNotEmpty() && mark.isNotEmpty() -> "${row.value}  $mark"
            row.value.isNotEmpty() -> row.value
            else -> mark
        }
        if (trailing.text.toString() != text) {
            trailing.text = text
            requestLayout()
        }
        trailing.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animateFocus(if (gainFocus) 1f else 0f)
        setMarquee(gainFocus)
    }

    /** 放不下的标题聚焦一会儿后跑马灯; 失焦回到开头省略. */
    private fun setMarquee(on: Boolean) {
        if (on && style.marqueeRepeat != 0 && row?.kind != TvSettingsRowKind.Header) {
            title.marqueeRepeatLimit = style.marqueeRepeat
            title.ellipsize = TextUtils.TruncateAt.MARQUEE
            title.isSelected = true
        } else if (title.isSelected || title.ellipsize != TextUtils.TruncateAt.END) {
            title.isSelected = false
            title.ellipsize = TextUtils.TruncateAt.END
        }
    }

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

    /** 复用的行换了内容: 聚焦程度按此刻的焦点归位. */
    fun resetFocusLook() {
        animator?.cancel()
        setFocusProgress(if (isFocused) 1f else 0f)
    }

    private fun applyColors() {
        val row = row ?: return
        val s = style
        val p = focusProgress
        if (row.kind == TvSettingsRowKind.Header) {
            title.setTextColor(s.header.color)
            bgPaint.color = 0
            invalidate()
            return
        }
        title.setTextColor(tvNativeLerpColor(s.text, s.focusedText, p))
        trailing.setTextColor(tvNativeLerpColor(s.textSecondary, s.focusedTextSecondary, p))
        val rest = if (active) s.activeBg else 0
        bgPaint.color = tvNativeLerpColor(rest, s.focusedBg, p)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val row = row
        // 字常在列表布局途中换 (差异更新时重新绑定): 那时字视图要重新测量的标记会被这一轮清掉, 按旧字的宽度排就被截成省略号 —— 每次都重新量
        trailing.forceLayout()
        title.forceLayout()
        var trailingWidth = 0
        if (trailing.visibility != GONE) {
            // 先按一行量出要多宽, 再多给一点按定宽排: 按 AT_MOST 量出的宽度有时比排版要的少零点几像素 (行尾的 › 来自回落字体),
            // 最后一个字被挤到第二行、整行截成省略号
            trailing.measure(unspecified, unspecified)
            val want = (trailing.measuredWidth + TRAILING_SLACK_PX).coerceAtMost(width / 2)
            trailing.measure(MeasureSpec.makeMeasureSpec(want, MeasureSpec.EXACTLY), unspecified)
            trailingWidth = trailing.measuredWidth + s.trailingGapPx
        }
        val swatchWidth = if (row?.swatch != null) s.swatchSizePx + s.swatchGapPx else 0
        val textWidth = (width - s.rowPaddingHPx * 2 - trailingWidth - swatchWidth).coerceAtLeast(0)
        title.measure(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.AT_MOST), unspecified)
        val height = if (row?.kind == TvSettingsRowKind.Header) {
            title.measuredHeight + s.headerTopGapPx + s.headerBottomGapPx
        } else {
            s.rowHeightPx
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        val w = r - l
        val h = b - t
        var x = s.rowPaddingHPx
        if (row?.swatch != null) x += s.swatchSizePx + s.swatchGapPx
        val ty = if (row?.kind == TvSettingsRowKind.Header) {
            h - s.headerBottomGapPx - title.measuredHeight
        } else {
            (h - title.measuredHeight) / 2
        }
        title.layout(x, ty, x + title.measuredWidth, ty + title.measuredHeight)
        if (trailing.visibility != GONE) {
            val right = w - s.rowPaddingHPx
            val vy = (h - trailing.measuredHeight) / 2
            trailing.layout(right - trailing.measuredWidth, vy, right, vy + trailing.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (bgPaint.alpha > 0) {
            bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(bgRect, style.cornerPx, style.cornerPx, bgPaint)
        }
        if (row?.swatch != null) {
            val radius = style.swatchSizePx / 2f
            canvas.drawCircle(style.rowPaddingHPx + radius, height / 2f, radius, swatchPaint)
        }
    }

    override fun hasOverlappingRendering(): Boolean = false

    private companion object {
        /** 行尾的字多给的宽度 (px), 见 onMeasure. */
        const val TRAILING_SLACK_PX = 2
    }
}

/** 两栏的列表适配器: 按行 id 做差异更新 (后台线程), 行 id 换成稳定的长整数 id. */
internal class TvSettingsRowAdapter(var style: TvSettingsStyle) : ListAdapter<TvSettingsRow, TvSettingsRowAdapter.Holder>(Diff) {
    var onRowFocused: ((TvSettingsRow) -> Unit)? = null
    var onRowClicked: ((TvSettingsRow) -> Unit)? = null

    /** 左栏里中栏正在显示的那一类. */
    var activeId: String? = null
        set(value) {
            if (field == value) return
            field = value
            refreshActive()
        }

    /** 正拿着挪顺序的那一行. */
    var movingId: String? = null
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

    class Holder(val view: TvSettingsRowView) : RecyclerView.ViewHolder(view)

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    override fun getItemId(position: Int): Long = stableIds.getOrPut(getItem(position).id) { nextStableId++ }

    override fun getItemViewType(position: Int): Int = getItem(position).kind.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = TvSettingsRowView(parent.context, style)
        view.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        val holder = Holder(view)
        // 回调里现取位置 (视图会被复用到别的位置)
        view.setOnFocusChangeListener { _, hasFocus ->
            val position = holder.bindingAdapterPosition
            if (hasFocus && position != RecyclerView.NO_POSITION) onRowFocused?.invoke(getItem(position))
        }
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onRowClicked?.invoke(getItem(position))
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.view.moving = row.id == movingId
        holder.view.bind(row, style)
        holder.view.active = row.id == activeId
        holder.view.resetFocusLook()
    }

    /** 从缓存里拿回来的行不重新绑定: 挂回列表时按此刻的标记对一次 (不在屏上时换过当前类, 不然留着上一类的浅底). */
    override fun onViewAttachedToWindow(holder: Holder) {
        syncState(holder.view)
    }

    private fun refreshActive() {
        val recycler = recycler ?: return
        syncChildren(recycler)
        // 布局途中 (长按连跳时列表一直在排) 有的行被暂时摘下, 不在子视图里, 上面那一圈够不着: 排完再对一遍
        recycler.post { syncChildren(recycler) }
    }

    private fun syncChildren(recycler: RecyclerView) {
        for (i in 0 until recycler.childCount) {
            syncState(recycler.getChildAt(i) as? TvSettingsRowView ?: continue)
        }
    }

    private fun syncState(view: TvSettingsRowView) {
        view.active = view.row?.id == activeId
        view.moving = view.row?.id == movingId
    }

    private object Diff : DiffUtil.ItemCallback<TvSettingsRow>() {
        override fun areItemsTheSame(oldItem: TvSettingsRow, newItem: TvSettingsRow) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TvSettingsRow, newItem: TvSettingsRow) = oldItem == newItem
    }
}
