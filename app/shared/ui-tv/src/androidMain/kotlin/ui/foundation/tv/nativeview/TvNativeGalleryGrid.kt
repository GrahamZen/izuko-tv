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
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade

/**
 * 「查看全部」弹窗里的原生画廊网格 (人物圆头像 / 作品海报, 外观的定义在 ui-subject 的 ViewAllGalleries.kt): leanback VerticalGridView,
 * 定列数, 格子定宽定高.
 * - 焦点滚动对齐 (同海报墙 TvNativeGridView): 聚焦那一行的中线对准视口中线, 两头贴边 (开头几行不滚, 末行底边停在下让出线);
 *   上下各让出 [verticalPx] (滚不完时内容在这一截里渐隐, 由 Compose 那侧画). 上下各多排一屏 (setExtraLayoutSpace), 连按时下一行已经排好,
 *   每一下都是同一种滚法. 按需挪 (FOCUS_SCROLL_ITEM) 不行: 只排看得见的行, 连按到底边那行时下一行还没排出来, 走 leanback 自带的
 *   「平滑滚到选中项」(按居中对齐), 与按需挪的停位不是一回事, 一会儿不滚一会儿滚一大截, 分页插数据重排时还往回闪 (Shield 实测一秒两下必现).
 * - 方向键网格自己走 (见 [tvGalleryGridTarget]): 焦点不出网格 (弹窗里没有别的去处, 放出去会被系统按屏幕位置送到垫在下面的页面上);
 *   上面有按钮的给 [releaseUpAtTop], 第一行按上交给外面. 长按连发限速同横滑行 / 纵向列表.
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
    verticalPx: Int,
) : VerticalGridView(context) {
    private val scroll = TvNativeSpringScroll()
    private var lastRepeatMove = 0L

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

    init {
        clipChildren = false
        clipToPadding = false
        // 网格本身不可聚焦 (RecyclerView 默认连触摸模式都可聚焦): 焦点不会停在没有聚焦效果的网格上
        isFocusable = false
        setNumColumns(columns)
        horizontalSpacing = columnSpacingPx
        verticalSpacing = rowSpacingPx
        setPadding(sidePx, verticalPx, sidePx, verticalPx)
        // 聚焦那一行的中线对准视口中线, 两头贴边 (视口中线 = 视图中线: 上下让出一样多)
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
        // 上下各多排一屏: 连按时下一行已经排好 (见类注释)
        if (h != oldh) setExtraLayoutSpace(h)
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
        if (!gainFocus && isFocusable) isFocusable = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action != KeyEvent.ACTION_DOWN || code !in TV_GALLERY_DIRECTION_KEYS) return super.dispatchKeyEvent(event)
        val focused = focusedChild ?: return super.dispatchKeyEvent(event)
        val index = getChildAdapterPosition(focused)
        // 焦点所在的格正被换掉 (数据刷新途中): 这一下吞掉, 不交给系统找焦点
        if (index == NO_POSITION) return true
        if (event.repeatCount > 0) {
            val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            val limit = if (vertical) TV_NATIVE_VERTICAL_REPEAT_MILLIS else TV_NATIVE_HORIZONTAL_REPEAT_MILLIS
            val now = SystemClock.uptimeMillis()
            if (now - lastRepeatMove < limit) return true
            lastRepeatMove = now
        }
        val target = tvGalleryGridTarget(index, adapter?.itemCount ?: 0, columns, code)
            ?: return !(releaseUpAtTop && code == KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0)
        val view = findViewHolderForAdapterPosition(target)?.itemView
        // 没排出来 (连按超前): 平滑滚过去, 到位时 leanback 把焦点给它 (网格有焦点)
        if (view == null || !view.requestFocus()) setSelectedPositionSmooth(target)
        return true
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
 * @param modifier 打开时的落点挂在这上面 (焦点送进网格时落在选中的那格, 首次是第一格).
 */
@Composable
fun TvNativeMonogramGallery(
    items: List<TvNativeMonogram?>,
    style: TvNativeMonogramStyle,
    columns: Int,
    rowSpacing: Dp,
    sideInset: Dp,
    verticalInset: Dp,
    touchTaps: Boolean,
    onClick: (index: Int) -> Unit,
    onLongPress: ((index: Int) -> Unit)?,
    onBind: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sketch = LocalSketch.current
    val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnBind by rememberUpdatedState(onBind)
    TvNativeGalleryHost(
        count = items.size,
        columns = columns,
        cellHeightPx = style.cellHeightPx,
        columnSpacingPx = style.spacingPx,
        rowSpacing = rowSpacing,
        sideInset = sideInset,
        verticalInset = verticalInset,
        adapter = { TvNativeMonogramAdapter(style, sketch) },
        update = { grid ->
            val cells = grid.adapter as TvNativeMonogramAdapter
            grid.animatedScroll = animatedScroll
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
 */
@Composable
fun TvNativePosterGallery(
    cards: List<TvNativeCard?>,
    cardWidth: Dp,
    columns: Int,
    columnSpacing: Dp,
    rowSpacing: Dp,
    sideInset: Dp,
    verticalInset: Dp,
    touchTaps: Boolean,
    onClick: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sketch = LocalSketch.current
    val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
    val nsfw = rememberNsfwPolicy()
    val shownCards = remember(cards, nsfw.snapshot) { cards.withNsfw(nsfw) }
    val style = rememberTvNativeWallStyle(cardWidth, columns, columnSpacing = columnSpacing)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnBind by rememberUpdatedState(onBind)
    val columnSpacingPx = with(LocalDensity.current) { columnSpacing.roundToPx() }
    TvNativeGalleryHost(
        count = shownCards.size,
        columns = columns,
        cellHeightPx = style.cardBlockHeightPx,
        columnSpacingPx = columnSpacingPx,
        rowSpacing = rowSpacing,
        sideInset = sideInset,
        verticalInset = verticalInset,
        adapter = { TvNativeCardAdapter(style, sketch) },
        update = { grid ->
            val cells = grid.adapter as TvNativeCardAdapter
            grid.animatedScroll = animatedScroll
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
 * 装 [TvNativeGalleryGridView] 的 AndroidView: 宽铺满, 高 = 内容高 (行数 × 格高 + 行距 + 上下让出), 父布局给的上限之内; 超出就在网格里滚,
 * 滚不完的那边在上下 [verticalInset] 里渐隐 (同 Compose 的 aniVerticalScrollFade; 能不能滚由网格的滚动回调写进状态, 绘制阶段读).
 */
@Composable
private fun TvNativeGalleryHost(
    count: Int,
    columns: Int,
    cellHeightPx: Int,
    columnSpacingPx: Int,
    rowSpacing: Dp,
    sideInset: Dp,
    verticalInset: Dp,
    adapter: () -> RecyclerView.Adapter<*>,
    update: (TvNativeGalleryGridView) -> Unit,
    modifier: Modifier,
    rebuildKey: Any? = null,
) {
    var canScrollUp by remember { mutableStateOf(false) }
    var canScrollDown by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val rowSpacingPx = with(density) { rowSpacing.roundToPx() }
    val sidePx = with(density) { sideInset.roundToPx() }
    val verticalPx = with(density) { verticalInset.roundToPx() }
    val rows = (count + columns - 1) / columns
    val contentHeightPx = rows * cellHeightPx + (rows - 1).coerceAtLeast(0) * rowSpacingPx + verticalPx * 2
    TvNativeHost(
        factory = { context ->
            TvNativeGalleryGridView(context, columns, columnSpacingPx, rowSpacingPx, sidePx, verticalPx).also { grid ->
                grid.adapter = adapter()
                grid.onScrollChanged = {
                    canScrollUp = grid.hasMoreAbove()
                    canScrollDown = grid.hasMoreBelow()
                }
            }
        },
        update = update,
        modifier = modifier
            .height(with(density) { contentHeightPx.toDp() })
            .aniVerticalScrollFade({ canScrollUp }, { canScrollDown }, fade = verticalInset),
        rebuildKey = rebuildKey,
    )
}
