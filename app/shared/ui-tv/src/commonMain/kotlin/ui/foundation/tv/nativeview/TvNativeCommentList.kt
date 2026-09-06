/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.TvNativeImages
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade

/** 原生评论列表的一条 (见 [TvNativeCommentListView]): 字都已经备好 (日期已格式化、正文已转成纯文本). */
@Immutable
data class TvNativeComment(
    /** 稳定标识: 展开状态与焦点按它认. */
    val key: String,
    val avatarUrl: String?,
    val name: String,
    val date: String,
    /** 评分 (1~10); null = 不画星. */
    val rating: Int?,
    val text: String,
)

/**
 * 原生评论列表的尺寸 (px) 与配色, 由 Compose 那侧按主题算好 (取值的出处是 ui-subject 的 CommentsList.kt, 两套实现共用), 原生视图只认像素.
 */
@Immutable
data class TvNativeCommentStyle(
    val columns: Int,
    val columnGapPx: Int,
    val sideInsetPx: Int,
    /** 聚焦那条滚进来时离上下边至少这么远 (上下渐隐的那一截, 由 Compose 那侧画). */
    val fadePx: Int,
    val paddingHorizontalPx: Int,
    val paddingVerticalPx: Int,
    val outerGapPx: Int,
    val cornerPx: Float,
    val ringWidthPx: Int,
    val ringColor: Int,
    val focusedColor: Int,
    val avatarPx: Int,
    val avatarGapPx: Int,
    val avatarPlaceholderColor: Int,
    val nameDateGapPx: Int,
    val headerGapPx: Int,
    val name: TvNativeTextStyle,
    val date: TvNativeTextStyle,
    val body: TvNativeTextStyle,
    val collapsedLines: Int,
    val starPx: Int,
    val starsGapPx: Int,
    /** 相邻两颗星叠进去多少 (同 Compose 版 FiveRatingStars 的负间距). */
    val starOverlapPx: Int,
    val starFull: Bitmap,
    val starHalf: Bitmap,
    val starEmpty: Bitmap,
    val dividerColor: Int,
    val dividerPx: Int,
    val crossfade: Boolean,
)

/**
 * 「查看全部」评论的原生列表: 多列瀑布流 (StaggeredGridLayoutManager, 每列各排各的, 左右不按行对齐), 外观同 Compose 版 (CommentsList).
 * - 上下只在聚焦那条所在的列里走: 交给系统按位置找的话, 同一列的下一条还没排出来时 (连按、按住) 会落到另一列先排出来的那条上,
 *   焦点在两列之间乱跳. 按住连发、滚动还在追时, 同一列的下一条还没露出来就先吞掉这一下, 等滚动跟上 —— 焦点只在看得见的条目里走:
 *   聚焦框一直在屏幕上, 也不会落到没排出来、只能临时排在外面的那条上 (下一次重排被回收, 见 [TvNativeCommentListFrame]);
 *   停着时还没排出来 (展开的长评论比多排的那截还长) 才由 StaggeredGridLayoutManager.onFocusSearchFailed 排出来 (它先在同一列里找).
 *   左右按位置跨列. 长按连发限速同纵向列表 / 横滑行. 到边了吞掉, 只有最上面一行按上交给外面
 *   (由 Compose 按位置找, 落到弹窗标题行的「写评价」); 新按下的那一下才算, 按住一路走到顶时余下的连发不送出去.
 * - 滚动只跟着焦点按需挪: 聚焦那条刚好整条露出来, 离上下边留出 [TvNativeCommentStyle.fadePx], 不落在渐隐里. 曲线同海报墙
 *   ([TvNativeSpringScroll]): 连按接上一段的速度, 不会每一下重新起步、一顿一顿. 按住连发时往前多留 [TV_COMMENT_HOLD_LEAD_FRACTION] 屏
 *   (列表先跑起来, 下面的条目早露出来, 焦点不用停下来等).
 *   外面来的「把这块滚进来」一律不理 (同 leanback 网格): 条目拿到焦点时系统 (View.onFocusChanged) 会按它露出来的那一块再要一次,
 *   露一半时给的是裁掉的那一小块 (照办就滚不全), 整条在屏幕外时给的是整窗坐标 (照办会滚过头、把持焦那条回收掉, 焦点就丢了).
 * - 确认键 (触屏上点一下) 展开 / 收起全文: 原地改行数, 不通知适配器 —— 通知了瀑布流会把这条以后的条目全部重新分列 (整片左右换位,
 *   焦点跟着乱跳); 原地改只有这条所在那一列往下推, 持焦时排完按需挪. 触屏上条目在触摸模式下不可聚焦, 点即确认、没有聚焦效果.
 * - 打开时落在第 [initialFocusIndex] 条: 送焦进来时那条还没排出来, 列表先接住焦点, 排完交给它 (同 [TvNativeGalleryGridView]).
 */
@SuppressLint("ViewConstructor")
class TvNativeCommentListView(context: Context, private val style: TvNativeCommentStyle) : RecyclerView(context) {
    private var lastRepeatMove = 0L
    private var initialFocusPending = true
    private val scroll = TvNativeSpringScroll()

    /** 刚展开 / 收起的那条 (持焦时): 排完按需挪 (见 [revealAfterLayout]). */
    private var pendingReveal: View? = null

    /** 按住连发换焦点时往前多留多少 (见 [revealFocused]): 正 = 往下, 负 = 往上, 0 = 单按. */
    private var revealLead = 0

    /** 动画滚动 (视觉效果流畅档关: 一步到位). */
    var animatedScroll: Boolean
        get() = scroll.animated
        set(value) {
            scroll.animated = value
        }

    /** 打开时落在第几条; 只在第一次送焦进来时用. */
    var initialFocusIndex: Int = 0

    /** 滚动位置可能变了 (滚动中每帧、每次布局完成): Compose 那侧据此刷新上下渐隐. */
    var onScrollChanged: (() -> Unit)? = null

    /**
     * 上下各藏着多高 (见 [TvNativeCommentListFrame]): 设成上下内边距, 瀑布流照内边距之外也排 (clipToPadding = false).
     * 按屏幕高定死, 不跟着量出来的高变: 变了已排好的条目不跟着挪, 会被推进藏着的那截里.
     */
    internal val hiddenPx: Int = (resources.displayMetrics.heightPixels * TV_COMMENT_HIDDEN_LAYOUT_FRACTION).toInt()

    init {
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        clipToPadding = false
        clipChildren = false
        setPadding(style.sideInsetPx, hiddenPx, style.sideInsetPx, hiddenPx)
        itemAnimator = null
        layoutManager = object : StaggeredGridLayoutManager(style.columns, VERTICAL) {
            // 聚焦那条自己按需挪 (见 revealFocused), 不走 RecyclerView 默认的「把这块滚进来」
            override fun onRequestChildFocus(parent: RecyclerView, state: State, child: View, focused: View?): Boolean {
                if (!parent.isComputingLayout) revealFocused(child)
                return true
            }

            // 上下只在聚焦那条所在的列里找 (见类注释): 同一列的下一条没排出来时系统找不到, 交给 onFocusSearchFailed 排出来 (它先在同一列里找)
            override fun onAddFocusables(recyclerView: RecyclerView, views: ArrayList<View>, direction: Int, focusableMode: Int): Boolean {
                if (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN) return false
                val focused = recyclerView.focusedChild ?: return false
                val span = (focused.layoutParams as StaggeredGridLayoutManager.LayoutParams).spanIndex
                for (i in 0 until childCount) {
                    val child = getChildAt(i) ?: continue
                    if ((child.layoutParams as StaggeredGridLayoutManager.LayoutParams).spanIndex == span) {
                        child.addFocusables(views, direction, focusableMode)
                    }
                }
                return true
            }

            // 外面来的「把这块滚进来」一律不理 (见类注释)
            override fun requestChildRectangleOnScreen(
                parent: RecyclerView,
                child: View,
                rect: Rect,
                immediate: Boolean,
                focusedChildVisible: Boolean,
            ): Boolean = false
        }.apply {
            // 往回滚时不在列之间挪条目 (挪了会看到条目换列跳一下)
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
        addItemDecoration(TvNativeCommentDecoration(style))
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onScrollChanged?.invoke()
            }
        })
    }

    /** 按需挪 (见 [tvRevealScrollAmount]), 按住连发时往前多留 [revealLead]; 到了列表两头自然滚不动, 不会留空. */
    private fun revealFocused(child: View) {
        val viewportTop = paddingTop + maxOf(0, -revealLead)
        val viewportBottom = height - paddingBottom - maxOf(0, revealLead)
        val dy = tvRevealScrollAmount(child.top, child.bottom, viewportTop, viewportBottom, style.fadePx)
        if (dy == 0) return
        if (!scroll.animated) {
            stopScroll()
            scroll.reset()
            scrollBy(0, dy)
            return
        }
        val plan = scroll.plan(dy.toFloat())
        smoothScrollBy(0, dy, plan.interpolator, plan.durationMillis)
    }

    /** 同一列里 [child] 的下一条 ([down]) / 上一条露出来了没有 (至少一截在看得见的范围里). */
    private fun hasVisibleNeighbor(child: View, down: Boolean): Boolean {
        val span = (child.layoutParams as StaggeredGridLayoutManager.LayoutParams).spanIndex
        for (i in 0 until childCount) {
            val other = getChildAt(i)
            if (other === child || (other.layoutParams as StaggeredGridLayoutManager.LayoutParams).spanIndex != span) continue
            val visible = if (down) {
                other.top >= child.bottom && other.top < height - paddingBottom
            } else {
                other.bottom <= child.top && other.bottom > paddingTop
            }
            if (visible) return true
        }
        return false
    }

    /** [child] 刚原地改了高 (展开 / 收起): 持焦的话排完按需挪, 长出视口的部分滚进来. */
    internal fun revealAfterLayout(child: View) {
        if (child.hasFocus()) pendingReveal = child
    }

    /**
     * 方向键列表自己走、一律吃掉: 没吃掉的方向键会被外面的 Compose 接过去按它的规则挪焦点, 焦点就丢了 (同 [TvNativeGalleryGridView]).
     * 下一条按系统的规则找 ([focusSearch]: 同一列上下、左右跨列, 没排出来的先排出来).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val direction = TV_COMMENT_DIRECTIONS[code]
        if (direction == null || event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val focused = findFocus() ?: return super.dispatchKeyEvent(event)
        // 列表自己接着焦点 (送焦时条目还没排出来): 等排完交给条目, 这一下吞掉
        if (focused === this) return true
        val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.repeatCount > 0) {
            val limit = if (vertical) TV_NATIVE_VERTICAL_REPEAT_MILLIS else TV_NATIVE_HORIZONTAL_REPEAT_MILLIS
            val now = SystemClock.uptimeMillis()
            if (now - lastRepeatMove < limit) return true
            lastRepeatMove = now
        }
        // 滚动还在追、同一列的下一条还没露出来: 这一下吞掉, 等滚动跟上 (见类注释)
        val down = code == KeyEvent.KEYCODE_DPAD_DOWN
        val item = focusedChild
        if (vertical && item != null && scrollState != SCROLL_STATE_IDLE && !hasVisibleNeighbor(item, down)) return true
        val next = focusSearch(focused, direction)
        if (next != null && next !== focused) {
            revealLead = if (vertical && event.repeatCount > 0) {
                val lead = ((height - paddingTop - paddingBottom) * TV_COMMENT_HOLD_LEAD_FRACTION).toInt()
                if (down) lead else -lead
            } else {
                0
            }
            next.requestFocus(direction)
            revealLead = 0
            return true
        }
        // 到边了 (见类注释): 最上面一行新按下的上键不吃, 交给外面; 别的吞掉
        return !(code == KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0)
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        // 找不到或找到列表外面时原地不动: 送不送出去由 dispatchKeyEvent 定 (见类注释)
        if (focused != null && (next == null || !isAncestorOf(next))) return focused
        return next
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (initialFocusPending && (adapter?.itemCount ?: 0) > 0) {
            val index = initialFocusIndex.coerceIn(0, adapter!!.itemCount - 1)
            findViewHolderForAdapterPosition(index)?.itemView?.let { view ->
                initialFocusPending = false
                return view.requestFocus()
            }
            // 那条还没排出来: 滚过去, 列表自己先接住焦点 (返回 false 后由 ViewGroup.requestFocus 落到列表本身), 排完交给它 (见 onLayout)
            scrollToPosition(index)
            isFocusable = true
            isFocusableInTouchMode = true
            return false
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        pendingReveal?.let { child ->
            pendingReveal = null
            if (child.parent === this && child.hasFocus()) revealFocused(child)
        }
        if (isFocused && childCount > 0) {
            val index = initialFocusIndex.coerceIn(0, (adapter?.itemCount ?: 1) - 1)
            val view = findViewHolderForAdapterPosition(index)?.itemView ?: getChildAt(0)
            initialFocusPending = false
            view.requestFocus()
        }
        onScrollChanged?.invoke()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        // 条目一拿到焦点, 列表就撤回可聚焦 (只为送焦时接一下)
        if (!gainFocus && isFocusable) {
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }

    private fun isAncestorOf(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }
}

/**
 * [TvNativeCommentListView] 的外框: 列表比框上下各高出 [TvNativeCommentListView.hiddenPx] (藏在内边距里, 由框裁掉), 瀑布流照列表的全高排条目,
 * 看得见的上下各多排这么一截 (StaggeredGridLayoutManager 没有「多排一截」的接口). 连按 / 按住时焦点要去的下一条已经排好; 不然那条只能临时排在屏幕外 (onFocusSearchFailed),
 * 下一次整体重排只排看得见的范围, 持焦那条被回收, 焦点被系统送回列表里第一条 (按住往下时焦点往回跳).
 */
@SuppressLint("ViewConstructor")
class TvNativeCommentListFrame(context: Context, val list: TvNativeCommentListView) : FrameLayout(context) {
    init {
        addView(list)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        list.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height + list.hiddenPx * 2, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        list.layout(0, -list.hiddenPx, right - left, bottom - top + list.hiddenPx)
    }
}

/**
 * 按需挪要滚多少 (正数往下): 条目 [[top], [bottom]) 刚好整条露出来, 离可见区 [[viewportTop], [viewportBottom]) 上下边各留 [margin];
 * 已经在里面就不动. 条目比可见区还高时顶边对齐 (先看开头).
 */
internal fun tvRevealScrollAmount(top: Int, bottom: Int, viewportTop: Int, viewportBottom: Int, margin: Int): Int {
    val above = top - margin - viewportTop
    val below = bottom + margin - viewportBottom
    return when {
        above < 0 -> above
        below > 0 -> minOf(below, above)
        else -> 0
    }
}

/** 评论列表看得见的上下各多排多少 (占屏幕高), 见 [TvNativeCommentListView.hiddenPx]. */
private const val TV_COMMENT_HIDDEN_LAYOUT_FRACTION = 0.5f

/** 评论列表按住连发时滚动往前多留多少 (占可见高), 见 [TvNativeCommentListView]. */
private const val TV_COMMENT_HOLD_LEAD_FRACTION = 1f / 3

private val TV_COMMENT_DIRECTIONS = mapOf(
    KeyEvent.KEYCODE_DPAD_LEFT to View.FOCUS_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT to View.FOCUS_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP to View.FOCUS_UP,
    KeyEvent.KEYCODE_DPAD_DOWN to View.FOCUS_DOWN,
)

/**
 * 条目之间的空隙与细线: 列间留 [TvNativeCommentStyle.columnGapPx], 上下各留 [TvNativeCommentStyle.outerGapPx]; 每列除了顶上那条,
 * 上面画一道细线 (左右缩到同正文对齐). 头几条依次排在各列顶上 (瀑布流先把各列填上一条), 所以按下标判「每列顶上那条」.
 */
private class TvNativeCommentDecoration(private val style: TvNativeCommentStyle) : RecyclerView.ItemDecoration() {
    // 不在 Paint 的 apply 里读 style: Paint 自己也有 style 属性, 会被它遮住
    private val paint = Paint().also { it.color = style.dividerColor }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val span = (view.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.spanIndex ?: 0
        val half = style.columnGapPx / 2
        outRect.left = if (span == 0) 0 else half
        outRect.right = if (span == style.columns - 1) 0 else half
        val divider = if (parent.getChildAdapterPosition(view) >= style.columns) style.dividerPx else 0
        outRect.top = style.outerGapPx + divider
        outRect.bottom = style.outerGapPx
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (parent.getChildAdapterPosition(child) < style.columns) continue
            val bottom = child.top + child.translationY - style.outerGapPx
            canvas.drawRect(
                child.left + style.paddingHorizontalPx.toFloat(),
                bottom - style.dividerPx,
                child.right - style.paddingHorizontalPx.toFloat(),
                bottom,
                paint,
            )
        }
    }
}

/**
 * 一条评论: 头像 + 昵称 / 日期竖排、评分星靠右 (与头像那一块竖直居中), 正文在下面, 折叠时最多 [TvNativeCommentStyle.collapsedLines] 行.
 * 聚焦时画底板 + 主题色描边 (按 drawable 状态切, 不重排).
 */
@SuppressLint("ViewConstructor")
private class TvNativeCommentView(context: Context, private val style: TvNativeCommentStyle) : LinearLayout(context) {
    private val avatar = TvNativeAvatarView(context)
    private val name = TvNativeTextView(context)
    private val date = TvNativeTextView(context)
    private val stars = TvNativeStarsView(context, style)
    private val body = TvNativeTextView(context)

    init {
        orientation = VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        setPadding(style.paddingHorizontalPx, style.paddingVerticalPx, style.paddingHorizontalPx, style.paddingVerticalPx)
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply {
                    cornerRadius = style.cornerPx
                    setColor(style.focusedColor)
                    setStroke(style.ringWidthPx, style.ringColor)
                },
            )
        }

        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        avatar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        avatar.clipToOutline = true
        avatar.setBackgroundColor(style.avatarPlaceholderColor)

        val names = LinearLayout(context).apply { orientation = VERTICAL }
        names.addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        names.addView(date, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = style.nameDateGapPx })
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(avatar, LayoutParams(style.avatarPx, style.avatarPx))
        header.addView(names, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = style.avatarGapPx })
        header.addView(stars, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = style.starsGapPx })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = style.headerGapPx })

        style.name.applyTo(name)
        style.date.applyTo(date)
        style.body.applyTo(body)
        name.maxLines = 1
        date.maxLines = 1
    }

    /** 现在绑的是哪条. */
    var item: TvNativeComment? = null
        private set

    /** 绑定一条; null = 分页还没到的那条 (空着, 照样可聚焦). */
    fun bind(item: TvNativeComment?, expanded: Boolean, sketch: Sketch) {
        this.item = item
        contentDescription = item?.name
        name.text = item?.name.orEmpty()
        date.text = item?.date.orEmpty()
        stars.score = item?.rating ?: 0
        stars.visibility = if (item?.rating != null) VISIBLE else GONE
        setExpanded(expanded)
        body.text = item?.text.orEmpty()
        val url = item?.avatarUrl?.takeIf { it.isNotBlank() }
        if (url == null) {
            TvNativeImages.clear(avatar)
        } else {
            TvNativeImages.loadAvatar(sketch, avatar, url, style.avatarPx, style.crossfade)
        }
    }

    /** 展开 (全文) / 收起 (最多 [TvNativeCommentStyle.collapsedLines] 行). */
    fun setExpanded(expanded: Boolean) {
        body.maxLines = if (expanded) Int.MAX_VALUE else style.collapsedLines
    }
}

/**
 * 评论头像: 定尺寸, 加载完换图不请求重排 —— 请求了整个列表连同装它的 AndroidView 都要重排一遍 (按住连翻时头像一张张到, 每张一次).
 */
private class TvNativeAvatarView(context: Context) : ImageView(context) {
    override fun requestLayout() {
        if (!isLaidOut) super.requestLayout()
    }
}

/** 五颗星 (评分 1~10, 单数是半颗), 同 Compose 版 FiveRatingStars. */
@SuppressLint("ViewConstructor")
private class TvNativeStarsView(context: Context, private val style: TvNativeCommentStyle) : View(context) {
    var score: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(style.starPx * 5 - style.starOverlapPx * 4, style.starPx)
    }

    override fun onDraw(canvas: Canvas) {
        var x = 0f
        for (i in 0 until 5) {
            val bitmap = when {
                score >= (i + 1) * 2 -> style.starFull
                score == i * 2 + 1 -> style.starHalf
                else -> style.starEmpty
            }
            canvas.drawBitmap(bitmap, x, 0f, null)
            x += style.starPx - style.starOverlapPx
        }
    }
}

private class TvNativeCommentHolder(val view: TvNativeCommentView) : RecyclerView.ViewHolder(view)

/**
 * 评论条目的适配器. 展开状态按条目的 key 记, 换数据 (分页插入) 不丢; 稳定 id 让焦点跟着条目走.
 * 不通知「某条变了」(展开 / 收起、内容换了都原地重绑): 通知了瀑布流会把那条以后的条目重新分列 (见 [TvNativeCommentListView]).
 */
private class TvNativeCommentAdapter(
    private val style: TvNativeCommentStyle,
    private val sketch: Sketch,
) : RecyclerView.Adapter<TvNativeCommentHolder>() {
    private var items: List<TvNativeComment?> = emptyList()
    private val expanded = HashSet<String>()
    private var list: TvNativeCommentListView? = null

    /** 第几条被绑定 (分页的访问提示). */
    var onBind: (index: Int) -> Unit = {}

    /** 触屏: 条目在触摸模式下不可聚焦 (点即确认, 没有聚焦效果). */
    var touchTaps = false

    init {
        setHasStableIds(true)
    }

    fun submit(newItems: List<TvNativeComment?>) {
        val old = items
        items = newItems
        // 换了一批 (刷新、换排序): 整个重排
        if (old.size > newItems.size || old.indices.any { old[it] != null && old[it]?.key != newItems[it]?.key }) {
            notifyDataSetChanged()
            return
        }
        // 原来那些位置上还是那几条 (占位的到了也算): 内容变了的原地重绑 (挂着的在这里, 缓存里的挂回来时补, 见 onViewAttachedToWindow)
        for (i in old.indices) {
            if (old[i] != newItems[i]) (list?.findViewHolderForAdapterPosition(i) as? TvNativeCommentHolder)?.let { bind(it, i) }
        }
        if (newItems.size > old.size) notifyItemRangeInserted(old.size, newItems.size - old.size)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        list = recyclerView as? TvNativeCommentListView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        list = null
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position]?.key?.hashCode()?.toLong() ?: (Long.MIN_VALUE + position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TvNativeCommentHolder {
        val view = TvNativeCommentView(parent.context, style)
        view.layoutParams = StaggeredGridLayoutManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val holder = TvNativeCommentHolder(view)
        view.setOnClickListener {
            val key = view.item?.key ?: return@setOnClickListener
            val expand = expanded.add(key)
            if (!expand) expanded.remove(key)
            view.setExpanded(expand)
            list?.revealAfterLayout(view)
        }
        return holder
    }

    override fun onBindViewHolder(holder: TvNativeCommentHolder, position: Int) {
        bind(holder, position)
    }

    override fun onViewAttachedToWindow(holder: TvNativeCommentHolder) {
        // 缓存里的条目挂回来时不重绑: submit 里原地换过内容的在这里补上 (挂上之后才量尺寸, 量的是新内容)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION && holder.view.item != items.getOrNull(position)) bind(holder, position)
    }

    private fun bind(holder: TvNativeCommentHolder, position: Int) {
        val item = items[position]
        holder.view.isFocusableInTouchMode = !touchTaps
        holder.view.bind(item, item != null && item.key in expanded, sketch)
        onBind(position)
    }
}

/**
 * 原生评论列表 ([TvNativeCommentListView]) 的 Compose 宿主: 铺满给的区域, 上下滚不完时渐隐 (同 Compose 版).
 *
 * @param items 列表里的 null 是分页还没到的那一条.
 * @param onBind 第几条被绑定 (分页的访问提示).
 * @param touchTaps 触屏: 点一下就是展开 / 收起, 没有聚焦效果.
 * @param modifier 打开时的落点挂在这上面 (焦点送进列表时落在第 [initialFocusIndex] 条).
 */
@Composable
fun TvNativeCommentList(
    items: List<TvNativeComment?>,
    style: TvNativeCommentStyle,
    initialFocusIndex: Int,
    touchTaps: Boolean,
    onBind: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sketch = LocalSketch.current
    var canScrollUp by remember { mutableStateOf(false) }
    var canScrollDown by remember { mutableStateOf(false) }
    val currentOnBind by rememberUpdatedState(onBind)
    val fade = with(LocalDensity.current) { style.fadePx.toDp() }
    val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
    TvNativeHost(
        factory = { context ->
            val list = TvNativeCommentListView(context, style).also { list ->
                list.adapter = TvNativeCommentAdapter(style, sketch)
                list.initialFocusIndex = initialFocusIndex
                list.onScrollChanged = {
                    canScrollUp = list.canScrollVertically(-1)
                    canScrollDown = list.canScrollVertically(1)
                }
            }
            TvNativeCommentListFrame(context, list)
        },
        update = { frame ->
            frame.list.animatedScroll = animatedScroll
            val adapter = frame.list.adapter as TvNativeCommentAdapter
            adapter.touchTaps = touchTaps
            adapter.onBind = { currentOnBind(it) }
            adapter.submit(items)
        },
        modifier = modifier.aniVerticalScrollFade({ canScrollUp }, { canScrollDown }, fade = fade),
        // 条目尺寸与配色在建条目时定死: 样式变了就整个重建
        rebuildKey = style,
    )
}
