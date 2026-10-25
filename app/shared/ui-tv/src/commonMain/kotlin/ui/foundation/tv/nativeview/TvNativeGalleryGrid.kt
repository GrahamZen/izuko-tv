/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.tvGridBleed
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import kotlin.math.abs

/**
 * 「查看全部」弹窗里的原生画廊网格 (人物圆头像 / 作品海报, 外观的定义在 ui-subject 的 ViewAllGalleries.kt): leanback VerticalGridView,
 * 定列数, 格子定宽定高.
 * - 焦点滚动对齐 (同海报墙 TvNativeGridView): 聚焦那一行的中线对准视口中线, 两头贴边 (开头几行不滚, 末行底边停在下让出线);
 *   顶上让出 [topPx]、底下让出 [bottomPx] (滚不完时内容在上下渐隐, 由 Compose 那侧画). 上下各多排一屏 (setExtraLayoutSpace), 连按时下一行已经排好,
 *   ([lazyUntilFocused] 时焦点进来之前只排看得见的格);
 *   每一下都是同一种滚法. 按需挪 (FOCUS_SCROLL_ITEM) 不行: 只排看得见的行, 连按到底边那行时下一行还没排出来, 走 leanback 自带的
 *   「平滑滚到选中项」(按居中对齐), 与按需挪的停位不是一回事, 一会儿不滚一会儿滚一大截, 分页插数据重排时还往回闪 (Shield 实测一秒两下必现).
 * - 方向键网格自己走 (见 [tvGalleryGridTarget]): 焦点不出网格 (弹窗里没有别的去处, 放出去会被系统按屏幕位置送到垫在下面的页面上);
 *   上面有按钮的给 [releaseUpAtTop], 第一行按上交给外面; 最下一行再按下交给 [onExitDown]. 长按连发限速同横滑行 / 纵向列表.
 * - 网格本身不可聚焦, 焦点只落在格上; 有格却一张都还没排出来时被送焦, 网格先接住, 排完交给选中的那格 (同 [TvNativeStripView]).
 * - 触屏 ([touchTaps]): 格子在触摸模式下不可聚焦, 点一下就是点击 (不先聚焦、不画聚焦放大), 长按派发 [onTouchLongPress]; 拖动照常滚.
 *   触屏用按需挪: 对齐策略下每次排版 (拖到底时分页插数据) 都把选中那格滚回停位, 手指拖下去的网格会跳回原处 (同海报墙触屏拖动时的切换).
 */
@SuppressLint("ViewConstructor")
class TvNativeGalleryGridView(
    context: Context,
    private val columns: Int,
    columnSpacingPx: Int,
    rowSpacingPx: Int,
    sidePx: Int,
    topPx: Int,
    bottomPx: Int,
) : VerticalGridView(context) {
    private val scroll = TvNativeSpringScroll()
    private var lastRepeatMove = 0L

    /** 上一次按左 / 右 (连发也算) 是哪个键、什么时候: 到边上那一下送不送出去看它 (见 [TV_GALLERY_EXIT_SIDE_PAUSE_MILLIS]). */
    private var lastSideKey = 0
    private var lastSideKeyAt = 0L

    /** 到边上那一下没送出去时那一格顶一下的动画 (见 [bump]). */
    private var bumpAnimator: ObjectAnimator? = null

    /** 动画滚动 (视觉效果流畅档关: 一步到位). */
    var animatedScroll: Boolean
        get() = scroll.animated
        set(value) {
            scroll.animated = value
        }

    /** 滚动位置可能变了 (滚动中每帧、每次布局完成): Compose 那侧据此刷新上下渐隐. */
    var onScrollChanged: (() -> Unit)? = null

    /** 触屏: 点一下就是点击, 长按派发 [onTouchLongPress]. 在绑定之前定 (格子上屏时按它设). 触屏用按需挪滚动 (见类注释). */
    var touchTaps: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyFocusScrollStrategy()
        }

    /** 触屏长按第几格; null = 长按不做事. */
    var onTouchLongPress: ((index: Int) -> Unit)? = null

    /**
     * 第一行新按下的上键不吃, 交给外面的 Compose 按位置找 (网格上面有按钮的弹窗, 如全部选集的标题行); 按住一路走到顶时余下的连发照样吞掉,
     * 不冲出去. false = 吞掉 (画廊: 上面没有别的去处).
     */
    var releaseUpAtTop: Boolean = false

    /** 焦点在最下一行再按下时调 (播放器胶囊上方的叠层用它把焦点还给胶囊); null = 吞掉不动. */
    var onExitDown: (() -> Unit)? = null

    /**
     * 焦点在最左列按左 / 最右边那格按右时调 (toRight, 焦点那格中线的窗口纵坐标): 播放器胶囊上方叠层里的画廊用它跨到相邻那颗胶囊
     * (见 [TvGalleryGridHandle]、[LocalTvGalleryExitSide]); null = 吞掉不动.
     */
    internal var onExitSide: ((toRight: Boolean, windowY: Float) -> Unit)? = null

    /**
     * 同方向刚按过的那一下不调 [onExitSide], 格子顶一下 (见 [TV_GALLERY_EXIT_SIDE_PAUSE_MILLIS]). 给跨走就回不来的画廊 (人物页叠出来的
     * 「查看全部」, 跨走丢掉那一摞人物页); 胶囊的画廊之间误跨了反方向一按就回到原来那格, 不拦.
     */
    internal var guardHurriedSideExit: Boolean = false

    /** 一开始要选中的那一格 (见 [applyPendingSelection]); -1 = 没有. */
    internal var pendingSelection: Int = -1

    /**
     * 焦点进来过之前只排看得见的格, 不上下各多排一屏 (播放器胶囊聚焦时就显示的画廊, 多半只是扫一眼, 见 [TvGalleryGridHandle]);
     * 焦点一进来就照常多排, 第一次按方向键之前那几行已经排好.
     */
    internal var lazyUntilFocused: Boolean = false
        set(value) {
            field = value
            applyExtraLayoutSpace()
        }

    /** 焦点第一次进来时调 (见 [lazyUntilFocused]). */
    internal var onEntered: (() -> Unit)? = null

    private var entered = false

    /**
     * 数据到了 (那一格已在适配器里) 就选中 [pendingSelection]: 网格排到它那一行, 焦点落在它上面 (已经有焦点就挪过去). 两个时机都不行:
     * 建视图时适配器还是空的, 选中位置被布局夹回第一格; 换数据的同一刻选也不行 —— 分页数据常是「先空、后整批插在 0 处」, 这次插入要到下一趟布局才交给
     * 布局管理器, 它按插入把先设好的选中位置往后推, 落到别处. 所以等这一趟布局完再选.
     */
    internal fun applyPendingSelection() {
        val index = pendingSelection
        if (index < 0 || index >= (adapter?.itemCount ?: 0)) return
        pendingSelection = -1
        if (index > 0) post { if (index < (adapter?.itemCount ?: 0)) selectedPosition = index }
    }

    init {
        clipChildren = false
        clipToPadding = false
        // 网格本身不可聚焦 (RecyclerView 默认连触摸模式都可聚焦): 焦点不会停在没有聚焦效果的网格上
        isFocusable = false
        setNumColumns(columns)
        horizontalSpacing = columnSpacingPx
        verticalSpacing = rowSpacingPx
        setPadding(sidePx, topPx, sidePx, bottomPx)
        // 聚焦那一行的中线对准视口中线, 两头贴边 (上下让出得不一样多时视口中线比视图中线偏一点, 差的是让出量差的一半, 看不出)
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        windowAlignmentOffsetPercent = 50f
        itemAlignmentOffsetPercent = 50f
        applyFocusScrollStrategy()
        setSmoothScrollByBehavior(scroll)
        itemAnimator = null
        layoutManager?.isItemPrefetchEnabled = true
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onScrollChanged?.invoke()
            }
        })
        addOnLayoutCompletedListener { onScrollChanged?.invoke() }
        addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (!touchTaps) return
                view.isFocusableInTouchMode = false
                view.setOnLongClickListener { cell ->
                    val index = getChildAdapterPosition(cell)
                    val handler = onTouchLongPress
                    if (index != NO_POSITION && handler != null) handler(index)
                    handler != null
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
    }

    /**
     * 上面还有没露出来的 (首格没排出来, 或顶边越过了上让出线). 不用 canScrollVertically: leanback 的布局管理器不报滚动范围, 那个恒为 false.
     */
    fun hasMoreAbove(): Boolean {
        if (childCount == 0) return false
        val first = findViewHolderForAdapterPosition(0)?.itemView ?: return true
        return first.top < paddingTop - 1
    }

    /** 下面还有没露出来的 (末格没排出来, 或底边越过了下让出线); 见 [hasMoreAbove]. */
    fun hasMoreBelow(): Boolean {
        val count = adapter?.itemCount ?: 0
        if (childCount == 0 || count == 0) return false
        val last = findViewHolderForAdapterPosition(count - 1)?.itemView ?: return true
        return last.bottom > height - paddingBottom + 1
    }

    /**
     * 手指拖动只滚竖向 (同海报墙的 TvNativeGridView): 多列的 VerticalGridView 横向也能滚 (leanback 的 canScrollHorizontally 在列数大于 1 时为 true),
     * 手指一横拖整片网格就歪出面板, 没有什么会把它拉回来. 触摸拖动的横向量在这里全部吃掉, 甩出去的横向速度清零; 方向键的滚动不经过这两处.
     */
    override fun dispatchNestedPreScroll(dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?, type: Int): Boolean {
        val handled = super.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)
        if (type != ViewCompat.TYPE_TOUCH || dx == 0 || consumed == null) return handled
        consumed[0] = dx
        return true
    }

    override fun fling(velocityX: Int, velocityY: Int): Boolean = super.fling(0, velocityY)

    /** 遥控器对齐、触屏按需挪 (见类注释). FOCUS_SCROLL_ITEM 是受限 API (leanback-grid 1.0.0 仍实现, 之后的版本标成不支持). */
    @SuppressLint("RestrictedApi")
    private fun applyFocusScrollStrategy() {
        setFocusScrollStrategy(if (touchTaps) BaseGridView.FOCUS_SCROLL_ITEM else BaseGridView.FOCUS_SCROLL_ALIGNED)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) applyExtraLayoutSpace()
    }

    /** 上下各多排一屏: 连按时下一行已经排好 (见类注释); 焦点进来之前按 [lazyUntilFocused]. */
    private fun applyExtraLayoutSpace() {
        setExtraLayoutSpace(if (lazyUntilFocused && !entered) 0 else height)
    }

    private fun markEntered() {
        if (entered) return
        entered = true
        applyExtraLayoutSpace()
        onEntered?.invoke()
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        markEntered()
    }

    /**
     * 选中第一屏 (滚到顶时看得见的那几行) 里最右列 ([rightColumn]) / 最左列、纵向离 [windowY] (窗口坐标) 最近的那一行的那格: 网格回到顶上,
     * 焦点随后送进网格时落在它上面. 一格都还没排出来时不动, 返回 false.
     */
    internal fun selectEdgeNearest(rightColumn: Boolean, windowY: Float): Boolean {
        val count = adapter?.itemCount ?: 0
        if (count == 0 || childCount == 0) return false
        val cellHeight = getChildAt(0).height
        val rowStep = cellHeight + verticalSpacing
        if (rowStep <= 0) return false
        val rows = (count + columns - 1) / columns
        val visible = height - paddingTop - paddingBottom
        val firstScreenRows = ((visible + verticalSpacing) / rowStep).coerceIn(1, rows)
        val location = IntArray(2)
        getLocationInWindow(location)
        val top = location[1] + paddingTop
        val row = (0 until firstScreenRows).minBy { r -> abs(top + r * rowStep + cellHeight / 2f - windowY) }
        val index = (row * columns + if (rightColumn) columns - 1 else 0).coerceAtMost(count - 1)
        if (index != selectedPosition) selectedPosition = index
        return true
    }

    /**
     * 选中看得见的最下一整行里横向离 [windowX] (窗口坐标) 最近的那格: 焦点随后送进网格时落在它上面 (网格照常把那一行滚到停位).
     * 一格都还没排出来时不动, 返回 false.
     */
    internal fun selectNearestBottom(windowX: Float): Boolean {
        val location = IntArray(2)
        var best = NO_POSITION
        var bestRow = -1
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val position = getChildAdapterPosition(child)
            if (position == NO_POSITION) continue
            // 整格看得见: 不算被上下让出那一截 (渐隐) 盖住的
            if (child.top < paddingTop - 1 || child.bottom > height - paddingBottom + 1) continue
            val row = position / columns
            child.getLocationInWindow(location)
            val distance = abs(location[0] + child.width / 2f - windowX)
            if (row > bestRow || (row == bestRow && distance < bestDistance)) {
                best = position
                bestRow = row
                bestDistance = distance
            }
        }
        if (best == NO_POSITION) return false
        if (best != selectedPosition) setSelectedPositionSmooth(best)
        return true
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (childCount == 0 && (adapter?.itemCount ?: 0) > 0) {
            // 有格但一张都还没排出来 (刚建出来): 网格自己先接住焦点 (返回 false 后由 ViewGroup.requestFocus 落到网格本身),
            // 排完交给选中的那格 (见 onLayout), 格一拿到焦点网格就撤回可聚焦 (见 onFocusChanged)
            isFocusableInTouchMode = true
            return false
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (isFocused && childCount > 0) {
            findViewHolderForAdapterPosition(selectedPosition.coerceAtLeast(0))?.itemView?.requestFocus()
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) markEntered()
        if (!gainFocus && isFocusable) isFocusable = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action != KeyEvent.ACTION_DOWN || code !in TV_GALLERY_DIRECTION_KEYS) return super.dispatchKeyEvent(event)
        val focused = focusedChild ?: return super.dispatchKeyEvent(event)
        val index = getChildAdapterPosition(focused)
        // 焦点所在的格正被换掉 (数据刷新途中): 这一下吞掉, 不交给系统找焦点
        if (index == NO_POSITION) return true
        val side = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val sideKeyAt = SystemClock.uptimeMillis()
        // 同方向刚按过 (快速连点一路点到边上): 到边了这一下也不送出去, 停一下再按才送 —— 多点的那一下不把焦点带到别处
        val hurried = side && code == lastSideKey && sideKeyAt - lastSideKeyAt < TV_GALLERY_EXIT_SIDE_PAUSE_MILLIS
        if (side) {
            lastSideKey = code
            lastSideKeyAt = sideKeyAt
        }
        if (event.repeatCount > 0) {
            val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            val limit = if (vertical) TV_NATIVE_VERTICAL_REPEAT_MILLIS else TV_NATIVE_HORIZONTAL_REPEAT_MILLIS
            val now = SystemClock.uptimeMillis()
            if (now - lastRepeatMove < limit) return true
            lastRepeatMove = now
        }
        val target = tvGalleryGridTarget(index, adapter?.itemCount ?: 0, columns, code)
        if (target == null) {
            // 第一行新按下的上键交给外面 (见 releaseUpAtTop)
            if (releaseUpAtTop && code == KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0) return false
            // 新按下的那一下才算 (按住方向键一路走到边上, 余下的连发不把焦点送出去)
            if (event.repeatCount == 0) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> onExitDown?.invoke()
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (hurried && guardHurriedSideExit && onExitSide != null) {
                        bump(focused, toRight = code == KeyEvent.KEYCODE_DPAD_RIGHT)
                    } else onExitSide?.let { exit ->
                        val location = IntArray(2)
                        focused.getLocationInWindow(location)
                        exit(code == KeyEvent.KEYCODE_DPAD_RIGHT, location[1] + focused.height / 2f)
                    }
                }
            }
            return true
        }
        val view = findViewHolderForAdapterPosition(target)?.itemView
        // 没排出来 (连按超前): 平滑滚过去, 到位时 leanback 把焦点给它 (网格有焦点)
        if (view == null || !view.requestFocus()) setSelectedPositionSmooth(target)
        return true
    }

    /**
     * [view] 往 [toRight] 那边轻轻顶一下再回来: 提示到边了, 再按一下才跨出去. 只动 translationX (合成时挪, 不重排不重画格子);
     * 视觉效果流畅档 (不做滚动动画) 时不顶.
     */
    private fun bump(view: View, toRight: Boolean) {
        if (!animatedScroll) return
        bumpAnimator?.cancel()
        val distance = TV_GALLERY_EDGE_BUMP_DP * resources.displayMetrics.density * (if (toRight) 1 else -1)
        bumpAnimator = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, distance, 0f).apply {
            duration = TV_GALLERY_EDGE_BUMP_MILLIS
            // 被下一次顶打断时归位
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    view.translationX = 0f
                }
            })
            start()
        }
    }
}

/**
 * 画廊网格里按方向键从第 [index] 格去哪一格; null = 不动 (这一下吞掉). 左右在一行里走, 行首按左 / 行尾按右不动; 上下落在同一列,
 * 下一行没有同一列的格时落到最后一格; 首行按上、末行按下不动.
 */
internal fun tvGalleryGridTarget(index: Int, count: Int, columns: Int, keyCode: Int): Int? {
    if (count <= 0 || index !in 0 until count || columns <= 0) return null
    val column = index % columns
    val lastRow = (count - 1) / columns
    val row = index / columns
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> if (column == 0) null else index - 1
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (column == columns - 1 || index == count - 1) null else index + 1
        KeyEvent.KEYCODE_DPAD_UP -> if (row == 0) null else index - columns
        KeyEvent.KEYCODE_DPAD_DOWN -> if (row == lastRow) null else minOf(index + columns, count - 1)
        else -> null
    }
}

/**
 * 最左列按左 / 最右边按右要隔上一次同方向的按键这么久才送出去 ([TvNativeGalleryGridView.onExitSide], 只对 [TvNativeGalleryGridView.guardHurriedSideExit] 的画廊):
 * 快速连点走到边上时多点的那一下不跨走, 停在边上再按一下就跨.
 */
private const val TV_GALLERY_EXIT_SIDE_PAUSE_MILLIS = 400L

/** 那一下没送出去时格子顶出去多远、来回一共多久. */
private const val TV_GALLERY_EDGE_BUMP_DP = 6f
private const val TV_GALLERY_EDGE_BUMP_MILLIS = 180L

private val TV_GALLERY_DIRECTION_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
)

/**
 * 人物画廊 (圆头像) 的原生网格. 格子同详情页的圆头像行 ([TvNativeMonogramCardView], 样式 [style] 里带标签样式就画标签), 一行 [columns] 格,
 * 列距是 [TvNativeMonogramStyle.spacingPx].
 *
 * @param items 列表里的 null 是分页还没到的那一格 (画成占位).
 * @param onLongPress 长按 (遥控器按住确定键 / 触屏长按); null = 没有.
 * @param onBind 第几格被绑定 (分页的访问提示).
 * @param touchTaps 触屏: 点一下就是点击 (见 [TvNativeGalleryGridView.touchTaps]).
 * @param initialIndex 一开始选中哪一格 (焦点送进网格时落在它上面, 网格先排到它那一行).
 * @param onExitDown 见 [TvNativeGalleryGridView.onExitDown].
 * @param edgeBleed 原生视图左右各往外伸多少 (网格内侧的留白跟着加, 格子位置不变): 首末列聚焦时的放大与投影画进这一截,
 *   不被装它的 AndroidView 按自己的边界裁掉. 外面要真有这么宽的空地 (如弹窗内容区的内边距).
 * @param topBleed 原生视图往上伸多少 (顶上的留白跟着加, 格子位置不变): 首行聚焦的投影画进这一截; 往下滚了以后这一截整个擦掉,
 *   内容只在 [topInset] 里渐隐. 外面要真有这么高的空地 (如弹窗标题行与内容之间的间距).
 * @param modifier 打开时的落点挂在这上面 (焦点送进网格时落在选中的那格).
 */
@Composable
fun TvNativeMonogramGallery(
    items: List<TvNativeMonogram?>,
    style: TvNativeMonogramStyle,
    columns: Int,
    rowSpacing: Dp,
    sideInset: Dp,
    topInset: Dp,
    bottomInset: Dp,
    touchTaps: Boolean,
    edgeBleed: Dp = 0.dp,
    topBleed: Dp = 0.dp,
    onClick: (index: Int) -> Unit,
    onLongPress: ((index: Int) -> Unit)?,
    onBind: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    initialIndex: Int = 0,
    onExitDown: (() -> Unit)? = null,
) {
    val sketch = LocalSketch.current
    val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnBind by rememberUpdatedState(onBind)
    val currentOnExitDown by rememberUpdatedState(onExitDown)
    TvNativeGalleryHost(
        count = items.size,
        columns = columns,
        cellHeightPx = style.cellHeightPx,
        columnSpacingPx = style.spacingPx,
        rowSpacing = rowSpacing,
        sideInset = sideInset,
        topInset = topInset,
        bottomInset = bottomInset,
        edgeBleed = edgeBleed,
        topBleed = topBleed,
        adapter = { TvNativeMonogramAdapter(style, sketch) },
        initialIndex = initialIndex,
        update = { grid ->
            val cells = grid.adapter as TvNativeMonogramAdapter
            grid.animatedScroll = animatedScroll
            grid.onExitDown = if (onExitDown != null) ({ currentOnExitDown?.invoke() }) else null
            grid.touchTaps = touchTaps
            grid.onTouchLongPress = if (onLongPress != null) ({ currentOnLongPress?.invoke(it) }) else null
            cells.longPressEnabled = onLongPress != null
            cells.listener = object : TvNativeCardListener {
                override fun onFocused(index: Int) = Unit
                override fun onClick(index: Int) = currentOnClick(index)
                override fun onLongPress(index: Int, anchor: Rect) {
                    currentOnLongPress?.invoke(index)
                }
            }
            cells.onBind = { currentOnBind(it) }
            cells.submit(items)
        },
        modifier = modifier,
        // 格子尺寸与配色在建格时定死: 样式变了就整个重建
        rebuildKey = style,
    )
}

/**
 * 作品画廊 (海报) 的原生网格: 海报卡同海报墙 ([TvNativeCardView], 卡宽 [cardWidth]), 一行 [columns] 张. NSFW 设为模糊时按卡片的条目 id 打码.
 * 参数同 [TvNativeMonogramGallery], 没有长按.
 *
 * @param overVideo 卡下的字直接压在播放画面上 (播放器胶囊上方的画廊): 番名白、小字淡一档, 都垫一层轻阴影 (见 [overVideo]), 没聚焦时也不怎么淡.
 */
@Composable
fun TvNativePosterGallery(
    cards: List<TvNativeCard?>,
    cardWidth: Dp,
    columns: Int,
    columnSpacing: Dp,
    rowSpacing: Dp,
    sideInset: Dp,
    topInset: Dp,
    bottomInset: Dp,
    touchTaps: Boolean,
    edgeBleed: Dp = 0.dp,
    topBleed: Dp = 0.dp,
    onClick: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    initialIndex: Int = 0,
    onExitDown: (() -> Unit)? = null,
    overVideo: Boolean = false,
) {
    val sketch = LocalSketch.current
    val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
    val nsfw = rememberNsfwPolicy()
    val shownCards = remember(cards, nsfw.snapshot) { cards.withNsfw(nsfw) }
    val wallStyle = rememberTvNativeWallStyle(cardWidth, columns, columnSpacing = columnSpacing)
    val density = LocalDensity.current
    val style = remember(wallStyle, overVideo, density) {
        if (!overVideo) {
            wallStyle
        } else {
            wallStyle.copy(
                title = wallStyle.title.overVideo(Color.White, density),
                subtitle = wallStyle.subtitle.overVideo(Color.White.copy(alpha = TV_OVER_VIDEO_SUBTITLE_ALPHA), density),
                labelIdleAlpha = TV_OVER_VIDEO_LABEL_IDLE_ALPHA,
            )
        }
    }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnBind by rememberUpdatedState(onBind)
    val currentOnExitDown by rememberUpdatedState(onExitDown)
    val columnSpacingPx = with(LocalDensity.current) { columnSpacing.roundToPx() }
    TvNativeGalleryHost(
        count = shownCards.size,
        columns = columns,
        cellHeightPx = style.cardBlockHeightPx,
        columnSpacingPx = columnSpacingPx,
        rowSpacing = rowSpacing,
        sideInset = sideInset,
        topInset = topInset,
        bottomInset = bottomInset,
        edgeBleed = edgeBleed,
        topBleed = topBleed,
        adapter = { TvNativeCardAdapter(style, sketch) },
        initialIndex = initialIndex,
        update = { grid ->
            val cells = grid.adapter as TvNativeCardAdapter
            grid.animatedScroll = animatedScroll
            grid.onExitDown = if (onExitDown != null) ({ currentOnExitDown?.invoke() }) else null
            grid.touchTaps = touchTaps
            cells.listener = object : TvNativeCardListener {
                override fun onFocused(index: Int) = Unit
                override fun onClick(index: Int) = currentOnClick(index)
            }
            cells.onBind = { currentOnBind(it) }
            cells.submit(shownCards) { it.toLong() }
        },
        modifier = modifier,
        // 卡片样式 (卡宽、配色) 在建卡时定死: 变了就整个重建
        rebuildKey = style,
    )
}

/**
 * 播放器胶囊聚焦时就显示的画廊 (相关推荐 / 角色 / 制作人员, 见 ui-tv 的 TvPlayerDetailLayers) 拿来摆布网格的把手, 经 [LocalTvGalleryGridHandle]
 * 交给网格: 焦点进来过之前网格只排看得见的格 ([TvNativeGalleryGridView.lazyUntilFocused]); 网格重建 (换胶囊再回来、样式变了) 时落回原来选中的那格.
 */
class TvGalleryGridHandle {
    internal var view: TvNativeGalleryGridView? = null

    /** 网格拆掉时选中的那一格: 重建时落回去 (换胶囊再回来停在原处); -1 = 没有. */
    internal var savedSelection: Int = -1

    /** 焦点进来过 (之后网格自己记着选中那格, 送焦点进来就落在它上面). */
    var entered: Boolean = false
        internal set

    /** 选中看得见的最下一整行里横向离 [windowX] 最近的那格 (见 [TvNativeGalleryGridView.selectNearestBottom]). */
    fun selectNearestBottom(windowX: Float): Boolean = view?.selectNearestBottom(windowX) ?: false

    /** 见 [TvNativeGalleryGridView.onExitSide]; 由播放器的画廊那一层设. */
    var onExitSide: ((toRight: Boolean, windowY: Float) -> Unit)? = null

    /** 见 [TvNativeGalleryGridView.selectEdgeNearest]. */
    fun selectEdgeNearest(rightColumn: Boolean, windowY: Float): Boolean = view?.selectEdgeNearest(rightColumn, windowY) ?: false

    /** 选中第一格 (网格滚回顶上). */
    fun selectFirst() {
        val grid = view ?: return
        if ((grid.adapter?.itemCount ?: 0) > 0 && grid.selectedPosition != 0) grid.selectedPosition = 0
    }
}

/** 见 [TvGalleryGridHandle]; null = 平常的画廊 (打开就进焦点, 照常多排). */
val LocalTvGalleryGridHandle = staticCompositionLocalOf<TvGalleryGridHandle?> { null }

/**
 * 没有 [TvGalleryGridHandle] 的画廊 (播放器里人物页叠出来的「查看全部」) 最左列按左 / 最右边按右时调, 参数同 [TvNativeGalleryGridView.onExitSide];
 * null = 到边停住. 有把手的画廊用把手上的那个.
 */
val LocalTvGalleryExitSide = staticCompositionLocalOf<((toRight: Boolean, windowY: Float) -> Unit)?> { null }

/** 字压在画面上时 ([TvNativePosterGallery] 的 overVideo) 小字的不透明度, 与没聚焦时整行字淡到多少. */
internal const val TV_OVER_VIDEO_SUBTITLE_ALPHA = 0.72f
internal const val TV_OVER_VIDEO_LABEL_IDLE_ALPHA = 0.92f

/**
 * 装 [TvNativeGalleryGridView] 的 AndroidView: 宽铺满, 高 = 内容高 (行数 × 格高 + 行距 + 上下让出), 父布局给的上限之内; 超出就在网格里滚,
 * 滚不完的那边渐隐 (同 Compose 的 aniVerticalScrollFade; 能不能滚由网格的滚动回调写进状态, 绘制阶段读). 往外伸的那几截见 [TvNativeMonogramGallery].
 */
@Composable
private fun TvNativeGalleryHost(
    count: Int,
    columns: Int,
    cellHeightPx: Int,
    columnSpacingPx: Int,
    rowSpacing: Dp,
    sideInset: Dp,
    topInset: Dp,
    bottomInset: Dp,
    edgeBleed: Dp,
    topBleed: Dp,
    adapter: () -> RecyclerView.Adapter<*>,
    initialIndex: Int,
    update: (TvNativeGalleryGridView) -> Unit,
    modifier: Modifier,
    rebuildKey: Any? = null,
) {
    var canScrollUp by remember { mutableStateOf(false) }
    var canScrollDown by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val rowSpacingPx = with(density) { rowSpacing.roundToPx() }
    val sidePx = with(density) { (sideInset + edgeBleed).roundToPx() }
    val topPx = with(density) { (topInset + topBleed).roundToPx() }
    val bottomPx = with(density) { bottomInset.roundToPx() }
    val rows = (count + columns - 1) / columns
    val contentHeightPx = rows * cellHeightPx + (rows - 1).coerceAtLeast(0) * rowSpacingPx + topPx + bottomPx
    val handle = LocalTvGalleryGridHandle.current
    val exitSide by rememberUpdatedState(LocalTvGalleryExitSide.current)
    DisposableEffect(handle) {
        onDispose {
            handle?.view?.let { grid -> handle.savedSelection = grid.selectedPosition }
            handle?.view = null
        }
    }
    TvNativeHost(
        factory = { context ->
            TvNativeGalleryGridView(context, columns, columnSpacingPx, rowSpacingPx, sidePx, topPx, bottomPx).also { grid ->
                grid.adapter = adapter()
                // 重建 (换胶囊再回来、样式变了) 时落回原来选中的那格
                grid.pendingSelection = (handle?.view?.selectedPosition ?: handle?.savedSelection)?.takeIf { it >= 0 } ?: initialIndex
                if (handle != null) {
                    grid.lazyUntilFocused = !handle.entered
                    grid.onEntered = { handle.entered = true }
                    handle.view = grid
                }
                if (handle != null || exitSide != null) {
                    grid.onExitSide = { toRight, windowY -> (handle?.onExitSide ?: exitSide)?.invoke(toRight, windowY) }
                    // 没有把手的是人物页叠出来的画廊: 跨走回不来, 拦快速连点 (见 guardHurriedSideExit)
                    grid.guardHurriedSideExit = handle == null
                }
                grid.onScrollChanged = {
                    canScrollUp = grid.hasMoreAbove()
                    canScrollDown = grid.hasMoreBelow()
                }
            }
        },
        update = { grid ->
            update(grid)
            grid.applyPendingSelection()
        },
        modifier = modifier
            .tvGridBleed(start = edgeBleed, end = edgeBleed)
            .tvNativeBleedTop(topBleed)
            .height(with(density) { contentHeightPx.toDp() })
            // 顶上: 伸出去的那截滚动时整个擦掉, 渐隐在 topInset 那一截里; 底下: bottomInset 那一截里渐隐
            .aniVerticalScrollFade({ canScrollUp }, { canScrollDown }, fade = bottomInset, topFade = topInset, topClear = topBleed),
        rebuildKey = rebuildKey,
    )
}

/**
 * 往上伸 [top] (原生网格的投影与渐隐画进上面那段空地, 见 [TvNativeMonogramGallery] 的 topBleed): 量的时候多要 [top] 高, 报给外面的不算,
 * 往上挪同样多. 高度照旧随内容 (父布局给的上下限各加 [top]), 所以不用会把高度撑满的 tvGridBleed.
 */
internal fun Modifier.tvNativeBleedTop(top: Dp): Modifier = if (top <= 0.dp) this else layout { measurable, constraints ->
    val t = top.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = t))
    layout(placeable.width, (placeable.height - t).coerceAtLeast(0)) { placeable.place(0, -t) }
}
