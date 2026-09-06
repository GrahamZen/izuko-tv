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
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.TvNativeImages
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/** 原生评论列表的一楼 (见 [TvNativeCommentListView]): 字都已经备好 (时间已格式化、正文已压成文字 + 行内表情). */
@Immutable
data class TvNativeComment(
    /** 稳定标识: 焦点按它认. */
    val key: String,
    val avatarUrl: String?,
    val name: String,
    /** 个人签名, 跟在昵称后面的灰字; null = 没有. */
    val sign: String?,
    /** 楼层与时间 (`#3  10-25 12:00`). */
    val meta: String,
    /** 评分 (1~10); null = 不画星. */
    val rating: Int?,
    val body: TvNativeInlineText,
    /** 已被删除: 正文处写一句「删除了回复」. */
    val deleted: Boolean,
    /** 别人贴的表情 (回应): 表情 + 人数排成一行; 空 = 没有. */
    val reactions: TvNativeInlineText,
    /** 楼下摆出来的前几条回复. */
    val replies: List<TvNativeCommentReply>,
    /** 这一楼一共多少条回复; 比 [replies] 多时末尾写「共 N 条回复」. */
    val replyCount: Int,
)

/** 楼下摆出来的一条回复. */
@Immutable
data class TvNativeCommentReply(
    val key: String,
    val name: String,
    val meta: String,
    /** 非空 = 回复的是同层某条回复, 写明回复谁. */
    val replyToName: String?,
    val body: TvNativeInlineText,
    val deleted: Boolean,
)

/** 原生文字: 表情处是一个占位符 ([TV_NATIVE_STICKER_PLACEHOLDER]), 由视图取图画上去. */
@Immutable
data class TvNativeInlineText(val text: String, val stickers: List<TvNativeSticker>) {
    companion object {
        val Empty = TvNativeInlineText("", emptyList())
    }
}

/** 一枚行内表情: [index] 是占位符在文字里的位置. */
@Immutable
data class TvNativeSticker(val index: Int, val url: String)

/** 列表到边时按的方向 (见 [TvNativeCommentListView.onEdge]). */
enum class TvNativeCommentEdge { UP, DOWN, LEFT, RIGHT }

/** 见 [TvNativeCommentListController.entryIndex]. */
const val TV_NATIVE_COMMENT_ENTRY_NEAREST_BOTTOM = -1

/** 行内表情的占位符 (Unicode 的「对象替换字符」). */
const val TV_NATIVE_STICKER_PLACEHOLDER = '￼'

/**
 * 原生评论列表的尺寸 (px)、配色与文案, 由 Compose 那侧按主题算好 (列表卡的定义在 ui-subject 的 CommentsList.kt, 两套实现共用), 原生视图只认像素.
 * 弹窗里与播放器里是两套配色 (主题色 / 画面上的半透明黑底白字), 排法同一套.
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
    /** 条目上下各留的空 (两条之间是两倍, 有分隔线时再加线宽). */
    val outerGapPx: Int,
    val cornerPx: Float,
    val ringWidthPx: Int,
    val ringColor: Int,
    /** 没聚焦时的底色; 0 = 透明. */
    val itemColor: Int,
    val focusedColor: Int,
    val avatarPx: Int,
    val avatarGapPx: Int,
    val avatarPlaceholderColor: Int,
    val nameDateGapPx: Int,
    val headerGapPx: Int,
    val name: TvNativeTextStyle,
    val date: TvNativeTextStyle,
    val body: TvNativeTextStyle,
    /** 签名 (昵称后面的灰字) 的颜色. */
    val secondaryColor: Int,
    val collapsedLines: Int,
    val starPx: Int,
    val starsGapPx: Int,
    /** 相邻两颗星叠进去多少 (同 Compose 版 FiveRatingStars 的负间距). */
    val starOverlapPx: Int,
    val starFull: Bitmap,
    val starHalf: Bitmap,
    val starEmpty: Bitmap,
    /** 同一列里条与条之间画分隔线 (弹窗里); 播放器里的卡各自有底, 不画. */
    val dividers: Boolean,
    val dividerColor: Int,
    val dividerPx: Int,
    /** 倒着排 (第 0 条在最下面, 往上越来越后): 播放器面板贴着胶囊行往上长. */
    val reverseLayout: Boolean,
    val replyName: TvNativeTextStyle,
    /** 回复的楼层时间、「回复 @某人」、「共 N 条回复」. */
    val replyMeta: TvNativeTextStyle,
    val replyBody: TvNativeTextStyle,
    val replyBodyLines: Int,
    /** 正文与第一条回复、回复与回复之间的空. */
    val replyGapPx: Int,
    /** 回复的左缩进 (竖线画在缩进槽里) 与竖线的位置、粗细、颜色, 同整楼视图. */
    val replyIndentPx: Int,
    val threadLineInsetPx: Int,
    val threadLineWidthPx: Int,
    val threadLineColor: Int,
    /** 正文 / 回复 / 回应那一行里表情的边长. */
    val stickerPx: Int,
    val replyStickerPx: Int,
    val reactionStickerPx: Int,
    val deletedText: String,
    /** 「回复 @某人」, 名字处是 [TV_NATIVE_TEXT_ARG]. */
    val replyToText: String,
    /** 「共 N 条回复」, 数字处是 [TV_NATIVE_TEXT_ARG]. */
    val moreRepliesText: String,
    val crossfade: Boolean,
)

private val tvNativeCommentLogger = logger("TvNativeComments")

/** 文案模板里填参数的地方 (Compose 那侧按它取模板, 原生侧换成实际的名字 / 数字). */
const val TV_NATIVE_TEXT_ARG = "\u0001"

/**
 * 原生列表的控制柄: 让外面指定焦点进来时落在哪一条、滚到哪一条 (播放器评论面板: 从胶囊进来落在最下面那条, 从整楼退回来落在点开的那条).
 * 不给时 (「查看全部」) 第一次进来落在 initialFocusIndex, 之后按系统的规则找.
 */
@Stable
class TvNativeCommentListController {
    internal var view: TvNativeCommentListView? = null

    /** 焦点从外面进来时落在第几条; [TV_NATIVE_COMMENT_ENTRY_NEAREST_BOTTOM] = 看得见的里面最靠下的那条 (离下面的胶囊最近). */
    var entryIndex: Int = 0
        set(value) {
            field = value
            view?.entryIndex = value
        }

    /**
     * 不让焦点进来 (安卓视图那层): 列表藏在别的东西底下时 (「查看全部」里点开的整楼), 系统拿没人要的方向键在窗口里找下一个焦点,
     * 会绕过 Compose 的规则找到这些看不见的条目. 只在焦点已经不在列表里时设.
     */
    var focusBlocked: Boolean = false
        set(value) {
            field = value
            view?.focusBlocked = value
        }

    /** 滚到第 [index] 条 (排好之前也行, 排完生效); 焦点不动. */
    fun scrollTo(index: Int) {
        view?.let { if (index >= 0 && index < (it.adapter?.itemCount ?: 0)) it.scrollToPosition(index) }
    }

    /**
     * 像焦点走到了第 [index] 条那样把它滚进来 (同按方向键时的按需挪、同样的滚动曲线), 焦点不动: 焦点在别处翻着这些条目时 (整楼里左右翻楼)
     * 背后的列表跟着走, 焦点回来时那条已经在该在的地方, 不用再挪.
     */
    fun reveal(index: Int) {
        view?.revealPosition(index)
    }
}

/**
 * 评论的原生列表: 瀑布流 (StaggeredGridLayoutManager, 每列各排各的, 左右不按行对齐; 一列时就是竖着一条), 一楼一张卡 —— 主楼 + 楼下前几条回复
 * (竖线串着) + 「共 N 条回复」, 整张一个焦点, 确认键 (触屏上点一下) 交给 [TvNativeCommentAdapter.onClick] (外面开那一整楼).
 * - 上下只在聚焦那条所在的列里走: 交给系统按位置找的话, 同一列的下一条还没排出来时 (连按、按住) 会落到另一列先排出来的那条上,
 *   焦点在两列之间乱跳. 按住连发、滚动还在追时, 同一列的下一条还没露出来就先吞掉这一下, 等滚动跟上 —— 焦点只在看得见的条目里走:
 *   聚焦框一直在屏幕上, 也不会落到没排出来、只能临时排在外面的那条上 (下一次重排被回收, 见 [TvNativeCommentListFrame]);
 *   停着时还没排出来才由 StaggeredGridLayoutManager.onFocusSearchFailed 排出来 (它先在同一列里找).
 *   左右按位置跨列. 长按连发限速同纵向列表 / 横滑行. 到边了交给 [onEdge] (没给时吞掉, 只有最上面一行新按下的上键交给外面:
 *   弹窗里由 Compose 按位置找, 落到标题行的「写评价」).
 * - 滚动只跟着焦点按需挪: 聚焦那条刚好整条露出来, 离上下边留出 [TvNativeCommentStyle.fadePx], 不落在渐隐里. 曲线同海报墙
 *   ([TvNativeSpringScroll]): 连按接上一段的速度, 不会每一下重新起步、一顿一顿. 按住连发时往前多留 [TV_COMMENT_HOLD_LEAD_FRACTION] 屏
 *   (列表先跑起来, 下面的条目早露出来, 焦点不用停下来等).
 *   外面来的「把这块滚进来」一律不理 (同 leanback 网格): 条目拿到焦点时系统 (View.onFocusChanged) 会按它露出来的那一块再要一次,
 *   露一半时给的是裁掉的那一小块 (照办就滚不全), 整条在屏幕外时给的是整窗坐标 (照办会滚过头、把持焦那条回收掉, 焦点就丢了).
 * - 焦点从外面进来落在 [entryIndex] (还没排出来时列表先接住焦点, 排完交给它, 同 [TvNativeGalleryGridView]); [entryOnce] 时只第一次这样
 *   (「查看全部」打开时落在点开的那一条), 之后按系统的规则找.
 */
@SuppressLint("ViewConstructor")
class TvNativeCommentListView(context: Context, private val style: TvNativeCommentStyle) : RecyclerView(context) {
    private var lastRepeatMove = 0L
    private val scroll = TvNativeSpringScroll()

    /** 按住连发换焦点时往前多留多少 (见 [revealFocused]): 正 = 往下, 负 = 往上, 0 = 单按. */
    private var revealLead = 0

    /** 焦点是外面送进来的 (进列表、从整楼退回来): 那条直接滚到位, 不放滚动动画. */
    private var instantReveal = false

    /** 动画滚动 (视觉效果流畅档关: 一步到位). */
    var animatedScroll: Boolean
        get() = scroll.animated
        set(value) {
            scroll.animated = value
        }

    /** 焦点从外面进来时落在第几条 (见类注释); [TV_NATIVE_COMMENT_ENTRY_NEAREST_BOTTOM] = 看得见的里面最靠下的那条. */
    var entryIndex: Int = 0

    /** 只有第一次进来落在 [entryIndex]. */
    var entryOnce: Boolean = true
    private var entryPending = true

    /**
     * 到边了 (这个方向上列表里没有下一条): 给了就交给它, 返回 true = 吃掉这一下; [newPress] = 新按下的那一下 (不是按住的连发).
     * 没给时见类注释.
     */
    var onEdge: ((edge: TvNativeCommentEdge, newPress: Boolean) -> Boolean)? = null

    /** 焦点落到了第几条. */
    var onFocusedPosition: ((Int) -> Unit)? = null

    /** 见 [TvNativeCommentListController.focusBlocked]. */
    var focusBlocked: Boolean = false
        set(value) {
            field = value
            descendantFocusability = if (value) FOCUS_BLOCK_DESCENDANTS else FOCUS_AFTER_DESCENDANTS
        }

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
            reverseLayout = style.reverseLayout
        }
        addItemDecoration(TvNativeCommentDecoration(style))
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onScrollChanged?.invoke()
            }
        })
    }

    /**
     * 按需挪 (见 [tvRevealScrollAmount]), 按住连发时往前多留 [revealLead]; 到了列表两头自然滚不动, 不会留空.
     * 倒着排时 (播放器面板) 只有顶边渐隐, 底边贴着胶囊不留那一截.
     */
    private fun revealFocused(child: View) {
        val viewportTop = paddingTop + maxOf(0, -revealLead)
        val bottomFade = if (style.reverseLayout) style.fadePx else 0
        val viewportBottom = height - paddingBottom - maxOf(0, revealLead) + bottomFade
        val dy = tvRevealScrollAmount(child.top, child.bottom, viewportTop, viewportBottom, style.fadePx)
        if (dy == 0) return
        if (!scroll.animated || instantReveal) {
            stopScroll()
            scroll.reset()
            scrollBy(0, dy)
            return
        }
        val plan = scroll.plan(dy.toFloat())
        smoothScrollBy(0, dy, plan.interpolator, plan.durationMillis)
    }

    /** 见 [TvNativeCommentListController.reveal]; 那条还没排出来时先滚过去, 排完再按需挪 (见 onLayout). */
    internal fun revealPosition(index: Int) {
        if (index < 0 || index >= (adapter?.itemCount ?: 0)) return
        val child = findViewHolderForAdapterPosition(index)?.itemView
        if (child != null) {
            revealFocused(child)
        } else {
            pendingRevealPosition = index
            scrollToPosition(index)
        }
    }

    /** 见 [revealPosition]. */
    private var pendingRevealPosition = NO_POSITION

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
        // 按住连发、滚动还在追、同一列的下一条还没露出来: 这一下吞掉, 等滚动跟上 (见类注释). 一下一下按的照走 —— 下一条已经多排好了
        // (见 TvNativeCommentListFrame), 滚动接着上一段的速度追; 吞了的话边上没有渐隐那一截时 (播放器面板贴着胶囊的底边) 每一下都要等滚停
        val down = code == KeyEvent.KEYCODE_DPAD_DOWN
        val item = focusedChild
        if (vertical && event.repeatCount > 0 && item != null && scrollState != SCROLL_STATE_IDLE && !hasVisibleNeighbor(item, down)) return true
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
        // 到边了 (见类注释)
        val newPress = event.repeatCount == 0
        onEdge?.let { return it(TV_COMMENT_EDGES.getValue(code), newPress) }
        return !(code == KeyEvent.KEYCODE_DPAD_UP && newPress)
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        // 找不到或找到列表外面时原地不动: 送不送出去由 dispatchKeyEvent 定 (见类注释)
        if (focused != null && (next == null || !isAncestorOf(next))) return focused
        return next
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val count = adapter?.itemCount ?: 0
        if (entryPending && count > 0) {
            val index = resolveEntryIndex(count)
            findViewHolderForAdapterPosition(index)?.itemView?.let { view ->
                if (entryOnce) entryPending = false
                return requestFocusInstantly(view)
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
        if (pendingRevealPosition != NO_POSITION) {
            val child = findViewHolderForAdapterPosition(pendingRevealPosition)?.itemView
            pendingRevealPosition = NO_POSITION
            child?.let { revealFocused(it) }
        }
        if (isFocused && childCount > 0) {
            val index = resolveEntryIndex(adapter?.itemCount ?: 1)
            val view = findViewHolderForAdapterPosition(index)?.itemView ?: getChildAt(0)
            if (entryOnce) entryPending = false
            requestFocusInstantly(view)
        }
        onScrollChanged?.invoke()
    }

    /** [entryIndex] 落到哪一条: 「最靠下的那条」是看得见的条目里顶边最低的 (顶边要露出渐隐之外), 一条都没排出来时是第一条. */
    private fun resolveEntryIndex(count: Int): Int {
        if (entryIndex != TV_NATIVE_COMMENT_ENTRY_NEAREST_BOTTOM) return entryIndex.coerceIn(0, count - 1)
        val viewportBottom = height - paddingBottom - style.fadePx
        var best = -1
        var bestTop = Int.MIN_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.bottom <= paddingTop || child.top >= viewportBottom || child.top <= bestTop) continue
            val position = getChildAdapterPosition(child)
            if (position == NO_POSITION) continue
            best = position
            bestTop = child.top
        }
        return if (best >= 0) best else 0
    }

    private fun requestFocusInstantly(view: View): Boolean {
        instantReveal = true
        try {
            return view.requestFocus()
        } finally {
            instantReveal = false
        }
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (child != null) {
            val position = getChildAdapterPosition(child)
            if (position != NO_POSITION) onFocusedPosition?.invoke(position)
        }
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

    /**
     * 列表里找不到下一条时 (到边了, 或下一条还没排出来) RecyclerView 会一路往上问, 问到 Compose: Compose 找焦点时会调外面的 onExit,
     * 有的 onExit 当场就挪焦点 (播放器叠层往下出边 = 回胶囊), 只是找一下焦点就被挪走了. 焦点出不出列表只由列表的到边处理定
     * ([TvNativeCommentListView.onEdge]), 这里不往上问.
     */
    override fun focusSearch(focused: View?, direction: Int): View? = null
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

private val TV_COMMENT_EDGES = mapOf(
    KeyEvent.KEYCODE_DPAD_LEFT to TvNativeCommentEdge.LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT to TvNativeCommentEdge.RIGHT,
    KeyEvent.KEYCODE_DPAD_UP to TvNativeCommentEdge.UP,
    KeyEvent.KEYCODE_DPAD_DOWN to TvNativeCommentEdge.DOWN,
)

private val TV_COMMENT_DIRECTIONS = mapOf(
    KeyEvent.KEYCODE_DPAD_LEFT to View.FOCUS_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT to View.FOCUS_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP to View.FOCUS_UP,
    KeyEvent.KEYCODE_DPAD_DOWN to View.FOCUS_DOWN,
)

/**
 * 条目之间的空隙与细线: 列间留 [TvNativeCommentStyle.columnGapPx], 上下各留 [TvNativeCommentStyle.outerGapPx]; [TvNativeCommentStyle.dividers] 时
 * 每列除了顶上那条, 上面画一道细线 (左右缩到同正文对齐). 头几条依次排在各列顶上 (瀑布流先把各列填上一条), 所以按下标判「每列顶上那条」.
 */
private class TvNativeCommentDecoration(private val style: TvNativeCommentStyle) : RecyclerView.ItemDecoration() {
    // 不在 Paint 的 apply 里读 style: Paint 自己也有 style 属性, 会被它遮住
    private val paint = Paint().also { it.color = style.dividerColor }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val span = (view.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.spanIndex ?: 0
        val half = style.columnGapPx / 2
        outRect.left = if (span == 0) 0 else half
        outRect.right = if (span == style.columns - 1) 0 else half
        val divider = if (style.dividers && parent.getChildAdapterPosition(view) >= style.columns) style.dividerPx else 0
        outRect.top = style.outerGapPx + divider
        outRect.bottom = style.outerGapPx
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (!style.dividers) return
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
 * 一楼: 头像 + 昵称 (签名灰字) / 楼层时间竖排、评分星靠右 (与头像那一块竖直居中), 正文在下面 (最多 [TvNativeCommentStyle.collapsedLines] 行),
 * 再下面是楼下的前几条回复 ([TvNativeCommentRepliesView]). 聚焦时画底板 + 描边 (按 drawable 状态切, 不重排).
 */
@SuppressLint("ViewConstructor")
private class TvNativeCommentView(context: Context, private val style: TvNativeCommentStyle) : LinearLayout(context) {
    private val avatar = TvNativeAvatarView(context)
    private val name = TvNativeTextView(context)
    private val date = TvNativeTextView(context)
    private val stars = TvNativeStarsView(context, style)
    private val body = TvNativeTextView(context)
    private val reactions = TvNativeTextView(context)
    private val replies = TvNativeCommentRepliesView(context, style)

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
            if (style.itemColor != 0) {
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        cornerRadius = style.cornerPx
                        setColor(style.itemColor)
                    },
                )
            }
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
        addView(reactions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = style.headerGapPx })
        addView(replies, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = style.replyGapPx })

        style.name.applyTo(name)
        style.date.applyTo(date)
        style.body.applyTo(body)
        style.replyMeta.applyTo(reactions)
        name.maxLines = 1
        date.maxLines = 1
        body.maxLines = style.collapsedLines
        reactions.maxLines = 1
    }

    /** 现在绑的是哪条. */
    var item: TvNativeComment? = null
        private set

    /** 绑定一条; null = 分页还没到的那条 (空着, 照样可聚焦). */
    fun bind(item: TvNativeComment?, sketch: Sketch) {
        this.item = item
        contentDescription = item?.name
        name.text = item?.let { tvNativeNameWithSign(it.name, it.sign, style.secondaryColor) } ?: ""
        date.text = item?.meta.orEmpty()
        date.visibility = if (item?.meta.isNullOrEmpty()) GONE else VISIBLE
        stars.score = item?.rating ?: 0
        stars.visibility = if (item?.rating != null) VISIBLE else GONE
        bindCommentText(body, item?.body, item?.deleted == true, style.stickerPx, style, sketch)
        val reactionText = item?.reactions?.takeIf { it.text.isNotEmpty() }
        reactions.visibility = if (reactionText != null) VISIBLE else GONE
        bindCommentText(reactions, reactionText, deleted = false, style.reactionStickerPx, style, sketch)
        replies.bind(item, sketch)
        val url = item?.avatarUrl?.takeIf { it.isNotBlank() }
        if (url == null) {
            TvNativeImages.clear(avatar)
        } else {
            TvNativeImages.loadAvatar(sketch, avatar, url, style.avatarPx, style.crossfade)
        }
    }
}

/**
 * 楼下的前几条回复: 一条一块 (「回复 @某人」/ 名字 + 楼层时间 / 正文), 回复多于摆出来的时末尾写「共 N 条回复」; 左边缩进槽里一道竖线串起来
 * (同整楼视图). 没有回复时整块不占地方.
 */
@SuppressLint("ViewConstructor")
private class TvNativeCommentRepliesView(context: Context, private val style: TvNativeCommentStyle) : LinearLayout(context) {
    private class ReplyViews(val block: LinearLayout, val replyTo: TvNativeTextView, val header: TvNativeTextView, val body: TvNativeTextView)

    private val rows = ArrayList<ReplyViews>()
    private val more = TvNativeTextView(context)
    private val linePaint = Paint().also {
        it.color = style.threadLineColor
        it.strokeWidth = style.threadLineWidthPx.toFloat()
    }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding(style.replyIndentPx, 0, 0, 0)
        style.replyMeta.applyTo(more)
        more.maxLines = 1
    }

    private fun row(index: Int): ReplyViews {
        while (rows.size <= index) {
            val block = LinearLayout(context).apply { orientation = VERTICAL }
            val replyTo = TvNativeTextView(context).also { style.replyMeta.applyTo(it); it.maxLines = 1 }
            val header = TvNativeTextView(context).also { style.replyName.applyTo(it); it.maxLines = 1 }
            val body = TvNativeTextView(context).also { style.replyBody.applyTo(it); it.maxLines = style.replyBodyLines }
            block.addView(replyTo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            block.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            block.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = style.nameDateGapPx })
            // 「共 N 条回复」一直在最后
            addView(block, rows.size, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (rows.isNotEmpty()) topMargin = style.replyGapPx })
            rows += ReplyViews(block, replyTo, header, body)
        }
        return rows[index]
    }

    fun bind(item: TvNativeComment?, sketch: Sketch) {
        val replies = item?.replies.orEmpty()
        val remaining = (item?.replyCount ?: 0) > replies.size
        visibility = if (replies.isEmpty() && !remaining) GONE else VISIBLE
        for (i in replies.indices) {
            val reply = replies[i]
            val views = row(i)
            views.block.visibility = VISIBLE
            views.replyTo.visibility = if (reply.replyToName != null) VISIBLE else GONE
            views.replyTo.text = reply.replyToName?.let { style.replyToText.replace(TV_NATIVE_TEXT_ARG, it) }.orEmpty()
            views.header.text = tvNativeNameWithMeta(reply.name, reply.meta, style)
            bindCommentText(views.body, reply.body, reply.deleted, style.replyStickerPx, style, sketch)
        }
        for (i in replies.size until rows.size) rows[i].block.visibility = GONE
        if (remaining) {
            if (more.parent == null) addView(more, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            (more.layoutParams as LayoutParams).topMargin = if (replies.isEmpty()) 0 else style.replyGapPx
            more.text = style.moreRepliesText.replace(TV_NATIVE_TEXT_ARG, item!!.replyCount.toString())
            more.visibility = VISIBLE
        } else {
            more.visibility = GONE
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = style.threadLineInsetPx.toFloat()
        canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
    }
}

/** 昵称 + 灰字签名 `(签名)`. */
private fun tvNativeNameWithSign(name: String, sign: String?, signColor: Int): CharSequence {
    if (sign.isNullOrBlank()) return name
    return SpannableStringBuilder(name).apply {
        val start = length
        append("  (").append(sign).append(')')
        setSpan(ForegroundColorSpan(signColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** 回复那一行: 名字 + 小一号的灰字楼层时间. */
private fun tvNativeNameWithMeta(name: String, meta: String, style: TvNativeCommentStyle): CharSequence {
    if (meta.isEmpty()) return name
    return SpannableStringBuilder(name).apply {
        val start = length
        append("  ").append(meta)
        setSpan(ForegroundColorSpan(style.replyMeta.color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(AbsoluteSizeSpan(style.replyMeta.sizePx.toInt()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** 正文: 文字 + 行内表情 (见 [TvNativeStickerSpan]); 删除了的写一句 (灰字). */
private fun bindCommentText(
    view: TvNativeTextView,
    text: TvNativeInlineText?,
    deleted: Boolean,
    stickerPx: Int,
    style: TvNativeCommentStyle,
    sketch: Sketch,
) {
    if (deleted) {
        view.text = SpannableString(style.deletedText).apply {
            setSpan(ForegroundColorSpan(style.secondaryColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return
    }
    if (text == null || text.stickers.isEmpty()) {
        view.text = text?.text.orEmpty()
        return
    }
    val spannable = SpannableString(text.text)
    for (sticker in text.stickers) {
        if (sticker.index >= spannable.length) continue
        spannable.setSpan(TvNativeStickerSpan(sticker.url, stickerPx), sticker.index, sticker.index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        TvNativeStickers.request(sketch, view, sticker.url, stickerPx)
    }
    view.text = spannable
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
 * 评论条目的适配器. 稳定 id 让焦点跟着条目走.
 * 不通知「某条变了」(内容换了原地重绑): 通知了瀑布流会把那条以后的条目重新分列 (整片左右换位, 焦点跟着乱跳).
 */
private class TvNativeCommentAdapter(
    private val style: TvNativeCommentStyle,
    private val sketch: Sketch,
) : RecyclerView.Adapter<TvNativeCommentHolder>() {
    private var items: List<TvNativeComment?> = emptyList()
    private var list: TvNativeCommentListView? = null

    /** 第几条被绑定 (分页的访问提示). */
    var onBind: (index: Int) -> Unit = {}

    /** 第几条按了确认键 / 被点了. */
    var onClick: (index: Int) -> Unit = {}

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
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION && view.item != null) onClick(position)
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
        holder.view.bind(item, sketch)
        onBind(position)
    }
}

/**
 * 原生评论列表 ([TvNativeCommentListView]) 的 Compose 宿主: 铺满给的区域, 上下滚不完时渐隐 (同 Compose 版).
 *
 * @param items 列表里的 null 是分页还没到的那一条.
 * @param onClick 第几条按了确认键 (触屏上点了): 外面开那一整楼.
 * @param onBind 第几条被绑定 (分页的访问提示).
 * @param touchTaps 触屏: 点一下就是确认, 没有聚焦效果.
 * @param fadeBottom 底边滚不完时也渐隐 (播放器面板贴着胶囊, 底边不隐).
 * @param controller 外面指定焦点进来时的落点、让列表滚动 (见 [TvNativeCommentListController]); null = 只有第一次进来落在 [initialFocusIndex].
 * @param onEdge 见 [TvNativeCommentListView.onEdge].
 * @param modifier 打开时的落点挂在这上面 (焦点送进列表时落在第 [initialFocusIndex] 条).
 */
@Composable
fun TvNativeCommentList(
    items: List<TvNativeComment?>,
    style: TvNativeCommentStyle,
    initialFocusIndex: Int,
    touchTaps: Boolean,
    onClick: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    fadeBottom: Boolean = true,
    controller: TvNativeCommentListController? = null,
    onEdge: ((edge: TvNativeCommentEdge, newPress: Boolean) -> Boolean)? = null,
    onFocusedPosition: ((Int) -> Unit)? = null,
) {
    val sketch = LocalSketch.current
    var canScrollUp by remember { mutableStateOf(false) }
    var canScrollDown by remember { mutableStateOf(false) }
    val currentOnBind by rememberUpdatedState(onBind)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnEdge by rememberUpdatedState(onEdge)
    val currentOnFocusedPosition by rememberUpdatedState(onFocusedPosition)
    val fade = with(LocalDensity.current) { style.fadePx.toDp() }
    val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
    val listView = remember { arrayOfNulls<TvNativeCommentListView>(1) }
    // 上一次 Compose 报的: 这块里有没有焦点 (只在焦点刚从外面进来时补, 见下)
    val hadFocus = remember { BooleanArray(1) }
    TvNativeHost(
        factory = { context ->
            val list = TvNativeCommentListView(context, style).also { list ->
                listView[0] = list
                list.adapter = TvNativeCommentAdapter(style, sketch)
                if (controller != null) {
                    controller.view = list
                    list.entryIndex = controller.entryIndex
                    list.focusBlocked = controller.focusBlocked
                    list.entryOnce = false
                } else {
                    list.entryIndex = initialFocusIndex
                }
                list.onScrollChanged = {
                    canScrollUp = list.canScrollVertically(-1)
                    canScrollDown = list.canScrollVertically(1)
                }
            }
            TvNativeCommentListFrame(context, list)
        },
        update = { frame ->
            val list = frame.list
            list.animatedScroll = animatedScroll
            list.onEdge = currentOnEdge?.let { { edge, newPress -> currentOnEdge?.invoke(edge, newPress) ?: true } }
            list.onFocusedPosition = { currentOnFocusedPosition?.invoke(it) }
            val adapter = list.adapter as TvNativeCommentAdapter
            adapter.touchTaps = touchTaps
            adapter.onBind = { currentOnBind(it) }
            adapter.onClick = { currentOnClick(it) }
            adapter.submit(items)
        },
        modifier = modifier
            // 焦点刚从外面进来 (Compose 按 FocusRequester 送进来的, 不是按方向键找过来的) 而安卓视图那层的焦点没跟着进来时补一次:
            // 不然 Compose 认为焦点在这儿、条目上却没有焦点, 要按一下键才落进去. 只补刚进来的那一下: 焦点本来就在里面、
            // 被 Compose 挪到这块本身 (返回键在 Compose 里是「退出一层」) 时不拉回去, 不然返回键永远退不出去
            .onFocusChanged { state ->
                val entered = state.hasFocus && !hadFocus[0]
                hadFocus[0] = state.hasFocus
                val list = listView[0] ?: return@onFocusChanged
                if (!entered || list.hasFocus()) return@onFocusChanged
                list.post {
                    if (list.hasFocus() || !list.isAttachedToWindow) return@post
                    val taken = list.requestFocus()
                    tvNativeCommentLogger.info { "[NativeComments] focus handed in from Compose: entry=${list.entryIndex} taken=$taken" }
                }
            }
            .aniVerticalScrollFade({ canScrollUp }, { fadeBottom && canScrollDown }, fade = fade),
        // 条目尺寸与配色在建条目时定死: 样式变了就整个重建
        rebuildKey = style,
    )
}
