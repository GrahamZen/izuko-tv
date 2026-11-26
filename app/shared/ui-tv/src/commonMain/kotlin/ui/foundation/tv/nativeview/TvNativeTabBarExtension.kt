/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View

/**
 * 网格页顶上的玻璃标签栏 (如新番时间表的日期行; 样子见 [TvNativeGlassTabBar]) 挂成网格页的扩展 ([TvNativeWallExtension]):
 * 签与网格在同一个原生根里, 方向键都在原生里判 —— 首行按上由页面直接送回选中的签, 签上按下由页面送进网格, 不经 Compose 的焦点搜索
 * (Compose 与原生之间的焦点接缝最容易出错).
 *
 * 选中哪一格、按下进哪里、长按做什么由页面 (Compose) 定, 经 [callbacks] 接; 签的字、选中、透明度由页面推进来.
 * 签那一排摆在页面给的位置 ([setAnchor]: 窗口坐标, 页面在原来标签行的位置留一块等高的空); 透明度每帧问 [alphaOf].
 *
 * 按键 (焦点在签上):
 * - 左右: 移到相邻的签 (焦点跟过去) 并报 [Callbacks.onMoved] (聚焦即选中, 页面决定什么时候换墙); 第一个签按左 [Callbacks.onExitLeft]
 *   (进侧边栏), 最后一个签按右不动. 按住的连发限速 ([TV_NATIVE_HORIZONTAL_REPEAT_MILLIS]); [Callbacks.navigationLocked] 期间只吞连发;
 * - 下: [Callbacks.onDown]; 上: 不动;
 * - 确认: 短按 [Callbacks.onClick], 长按 [Callbacks.onLongClick] (判定同 tvLongPressKey, 见 [TvNativeConfirmKey]); 触屏点签同短按.
 *
 * 签那一排比给的宽度长时 (新番时间表 15 天的日期, [setAnchor] 给了宽度) 横向滚: 焦点所在 / 选中的签滚到居中, 两头夹住 (同原来 LazyRow
 * 的居中滚动); [startInsetPx] / [endInsetPx] 是两头留的空 (电视上整行往左出血到屏幕边, 第一个签离边还有一截), [clipToViewport] 时裁在给的
 * 范围里 (平板模式: 左边是「返回」, 滑过去的签切在它右边). 上下位移 ([setOffsetY]) 由页面推 (日期行随网格内容滚出屏).
 */
internal class TvNativeTabBarExtension(context: Context, style: TvNativeGlassTabStyle) : TvNativeWallExtension {
    interface Callbacks {
        /** 左右键移到了第 [index] 个签 (焦点已经过去) */
        fun onMoved(index: Int)

        /** 确认键短按、触屏点了第 [index] 个签 */
        fun onClick(index: Int)

        /** 确认键长按第 [index] 个签 ([longClickable] 为 false 时不认长按, 按住同短按) */
        fun onLongClick(index: Int) {}

        /** 签上按下 */
        fun onDown()

        /** 第一个签按左 (按住的连发也算) */
        fun onExitLeft()

        /** 焦点进出签 ([index] 是焦点所在的签, -1 = 离开了签那一排) */
        fun onFocusChanged(index: Int) {}

        /** 新的一次方向键 (不是连发): 用户接手了 */
        fun onUserNavigation() {}

        /** 此刻只吞连发 (页面在等选中的签拿到焦点) */
        fun navigationLocked(): Boolean = false
    }

    val bar = TvNativeGlassTabBar(context, style)
    override val views: List<View> = listOf(bar)

    var callbacks: Callbacks? = null

    /** 长按做不做事 (如长按签打开排序); false 时按住同短按 */
    var longClickable: Boolean = false

    /** 签那一排此刻的透明度 (hero 态里、点开进详情页时跟着卡片淡没); 每帧问一次 */
    var alphaOf: (TvNativeWallHost) -> Float = { 1f - it.chromeFade }

    private var wall: TvNativeWallHost? = null
    private val confirm = TvNativeConfirmKey()
    private var lastMoveAt = 0L
    private var anchorX = Int.MIN_VALUE
    private var anchorY = Int.MIN_VALUE
    private val location = IntArray(2)

    /** 签那一排能滚的宽度 (px, 从锚点起; 0 = 不滚, 签那一排就摆在锚点上) */
    private var viewportWidth = 0

    /** 滚动范围两头留的空 (px): 第一个签离范围左缘、最后一个签离右缘 */
    var startInsetPx: Int = 0
    var endInsetPx: Int = 0

    /** 签那一排裁在给的范围里 (平板模式, 左边是「返回」) */
    var clipToViewport: Boolean = false

    /** 聚焦放大与投影伸出签外的余量 (裁剪时让出来) */
    var bleedPx: Int = 0

    /** 此刻滚了多少 (px, 签那一排往左挪) */
    private var scroll = 0f
    private var scrollAnimator: ValueAnimator? = null
    private var scrollAnimatorTarget = 0f

    /** 排好之后要滚到居中的签 (-1 = 没有); [scrollJump] = 直接到位 */
    private var scrollPending = -1
    private var scrollJump = true
    private var laidOutOnce = false

    /** 签那一排在给的宽度里摆过 (之后的居中才演滚动) */
    private var placed = false
    private var lastBarWidth = -1
    private val clip = Rect()

    /** 焦点是从签上离开墙的 (进了侧边栏、弹了别的窗): 再从外面进墙时还给选中的签, 不落到卡上 (见 [onEntryFocus]) */
    private var returnToTab = false

    /** 拿到焦点前就要的那一次 ([focusTab] 时还没挂上 / 没排版): 挂上排好之后补给, 至多等 [PENDING_FOCUS_MILLIS] */
    private var pendingFocus = -1
    private var pendingFocusAt = 0L

    init {
        bar.onTabClick = { index -> callbacks?.onClick(index) }
        bar.onTabFocusChanged = { _, _ -> callbacks?.onFocusChanged(bar.focusedIndex()) }
    }

    fun setTabs(tabs: List<TvNativeGlassTab>, selected: Int, style: TvNativeGlassTabStyle) {
        val selectionChanged = selected != bar.selectedIndex
        bar.setTabs(tabs, selected, style)
        // 选中随字一起推进来 (组合当帧, 早于 [setSelected]): 同样带到居中
        if (selectionChanged && laidOutOnce) scrollToTab(selected, animate = true)
        // 记着要焦点的那一下, 签这时才建出来: 挂着就当场给
        val pending = pendingFocus
        if (pending >= 0 && bar.isAttachedToWindow && bar.focusTab(pending)) pendingFocus = -1
    }

    fun setSelected(index: Int) {
        val changed = index != bar.selectedIndex
        bar.setSelected(index)
        // 选中换了 (海报墙跨天、日期行上移到别的签): 带到居中
        if (changed) scrollToTab(index, animate = true)
    }

    /**
     * 签那一排的左上角在窗口里的位置 (px); [viewportWidthPx] > 0 = 签那一排在从这里起这么宽的范围里横向滚 (见类说明). 变了才重排.
     */
    fun setAnchor(windowX: Int, windowY: Int, viewportWidthPx: Int = 0) {
        if (windowX == anchorX && windowY == anchorY && viewportWidthPx == viewportWidth) return
        // 能滚的宽度头一回给到 (或变了): 焦点所在 / 选中的签直接滚到居中 (排版时宽度还没到, 那时算不了)
        if (viewportWidthPx != viewportWidth && viewportWidthPx > 0 && scrollPending < 0) {
            scrollPending = bar.focusedIndex().takeIf { it >= 0 } ?: bar.selectedIndex
            scrollJump = true
        }
        anchorX = windowX
        anchorY = windowY
        viewportWidth = viewportWidthPx
        bar.requestLayout()
    }

    /** 签那一排的上下位移 (px; 日期行随网格内容往上滚走时为负) */
    fun setOffsetY(px: Float) {
        if (bar.translationY != px) bar.translationY = px
    }

    /**
     * 焦点给第 [index] 个签; 签还没挂上 / 还没建出来时记下: 焦点从外面进墙时照它给 (见 [onEntryFocus]), 签建出来、排好时补给
     * (至多等 [PENDING_FOCUS_MILLIS]).
     */
    fun focusTab(index: Int): Boolean {
        if (index < 0) return false
        scrollToTab(index, animate = laidOutOnce)
        if (bar.isAttachedToWindow && bar.focusTab(index)) {
            pendingFocus = -1
            return true
        }
        pendingFocus = index
        pendingFocusAt = SystemClock.uptimeMillis()
        return true
    }

    /** 焦点在第几个签上 (不在签上为 -1). */
    fun focusedIndex(): Int = bar.focusedIndex()

    override fun onAttach(wall: TvNativeWallHost) {
        this.wall = wall
    }

    override fun onMeasure(wall: TvNativeWallHost) {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        bar.measure(unspecified, unspecified)
    }

    override fun onLayout(wall: TvNativeWallHost) {
        val host = wall as? View
        var x = 0
        var y = 0
        if (host != null && anchorX != Int.MIN_VALUE) {
            host.getLocationInWindow(location)
            x = anchorX - location[0]
            y = anchorY - location[1]
        }
        if (viewportWidth > 0) x += startInsetPx
        bar.layout(x, y, x + bar.measuredWidth, y + bar.measuredHeight)
        // 签那一排变宽变窄了 (签后到、字换了): 焦点所在 / 选中的签重新滚到居中, 不留在按旧宽度夹住的位置 (正在滚过去的不打断)
        if (bar.measuredWidth != lastBarWidth) {
            lastBarWidth = bar.measuredWidth
            if (laidOutOnce && scrollPending < 0 && scrollAnimator?.isRunning != true) {
                scrollPending = bar.focusedIndex().takeIf { it >= 0 } ?: bar.selectedIndex
                scrollJump = true
            }
        }
        if (!laidOutOnce) {
            laidOutOnce = true
            // 第一次排好: 选中的签直接滚到居中 (不演滚动)
            if (scrollPending < 0) scrollPending = bar.selectedIndex
            scrollJump = true
        }
        val target = scrollPending
        if (target >= 0 && viewportWidth > 0) {
            scrollPending = -1
            // 还没在给的宽度里摆过 (进页、宽度头一回到): 直接到位, 不从第一个签滚过去
            applyScroll(scrollTargetFor(target), animate = !scrollJump && placed)
            placed = true
        } else if (target < 0 && scrollAnimator?.isRunning != true) {
            // 宽度变了 (旋转、窗口变化): 夹回范围里. 正在滚过去的不管 (排版一来就掐掉, 会停在半路)
            val clamped = scroll.coerceIn(0f, maxScroll())
            if (clamped != scroll) applyScroll(clamped, animate = false)
        }
        val pending = pendingFocus
        if (pending >= 0) {
            pendingFocus = -1
            if (SystemClock.uptimeMillis() - pendingFocusAt <= PENDING_FOCUS_MILLIS) bar.post { bar.focusTab(pending) }
        }
    }

    override fun onFrame(wall: TvNativeWallHost) {
        // 只动透明度, 不藏起来: 淡没了也照样能拿焦点 (页面在 hero 态里也可能把焦点送回签上, 同原来的 Compose 标签行)
        val alpha = alphaOf(wall).coerceIn(0f, 1f)
        if (bar.alpha != alpha) bar.alpha = alpha
    }

    override fun onEntryFocus(wall: TvNativeWallHost): Boolean {
        val pending = pendingFocus
        if (pending < 0) {
            // 焦点是从签上走的 (按左进了侧边栏等): 回来还到选中的签上
            if (returnToTab && bar.focusTab(bar.selectedIndex)) {
                returnToTab = false
                return true
            }
            return false
        }
        if (SystemClock.uptimeMillis() - pendingFocusAt > PENDING_FOCUS_MILLIS) {
            pendingFocus = -1
            return false
        }
        // 签还没建出来 (字还没推进来) 时给不上: 留着, 排好后补 (见 onLayout)
        if (!bar.focusTab(pending)) return false
        pendingFocus = -1
        return true
    }

    override fun onFocusInside(wall: TvNativeWallHost, inside: Boolean) {
        if (!inside) confirm.reset()
        // 离开签时焦点还在墙里 (下到了卡上) 就不用记; 离开了墙 (侧边栏、别的窗口) 记下, 回来时还给签
        returnToTab = !inside && (wall as? View)?.hasFocus() == false
        callbacks?.onFocusChanged(if (inside) bar.focusedIndex() else -1)
    }

    override fun onKeyEvent(wall: TvNativeWallHost, focused: View, event: KeyEvent): Boolean {
        val index = bar.indexOf(focused)
        if (index < 0) return false
        val code = event.keyCode
        if (tvNativeIsConfirmKey(code)) {
            return confirm.onKey(focused, event, if (longClickable) ({ callbacks?.onLongClick(index) }) else null)
        }
        if (!tvNativeIsDirectionKey(code)) return false
        // 抬起一律吞掉 (按下已经处理过)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val repeat = event.repeatCount > 0
        val cb = callbacks
        if (repeat && cb?.navigationLocked() == true) return true
        if (!repeat) cb?.onUserNavigation()
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val now = SystemClock.uptimeMillis()
                // 按住的连发限速 (同原生行), 新按下的一下不丢
                if (repeat && now - lastMoveAt < TV_NATIVE_HORIZONTAL_REPEAT_MILLIS) return true
                val target = index + if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                when {
                    target < 0 -> cb?.onExitLeft()
                    target < bar.tabs.size -> {
                        lastMoveAt = now
                        if (bar.focusTab(target)) {
                            scrollToTab(target, animate = true)
                            cb?.onMoved(target)
                        }
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> cb?.onDown()
        }
        return true
    }

    // ---- 横向滚动 ----

    private fun maxScroll(): Float =
        if (viewportWidth <= 0) 0f else (startInsetPx + bar.measuredWidth + endInsetPx - viewportWidth).coerceAtLeast(0).toFloat()

    /** 第 [index] 个签滚到居中要滚多少 (夹在范围里) */
    private fun scrollTargetFor(index: Int): Float {
        val tab = bar.tabView(index) ?: return scroll
        if (viewportWidth <= 0) return 0f
        val center = startInsetPx + tab.left + tab.width / 2f
        return (center - viewportWidth / 2f).coerceIn(0f, maxScroll())
    }

    /** 第 [index] 个签滚到居中; 还没排好时记下, 排好再滚 */
    private fun scrollToTab(index: Int, animate: Boolean) {
        if (index < 0) return
        // 还没排好、还不知道能滚多宽: 记着, 排好 (宽度到了) 再滚
        if (!laidOutOnce || bar.isLayoutRequested || viewportWidth <= 0) {
            scrollPending = index
            scrollJump = !animate || !laidOutOnce
            return
        }
        applyScroll(scrollTargetFor(index), animate && placed)
    }

    private fun applyScroll(target: Float, animate: Boolean) {
        // 已经在往同一处滚 (按键移签与选中推送各要一次): 不重头再演
        if (animate && scrollAnimator?.isRunning == true && target == scrollAnimatorTarget) return
        scrollAnimator?.cancel()
        scrollAnimator = null
        if (!animate || !bar.style.animated || target == scroll) {
            setScroll(target)
            return
        }
        scrollAnimatorTarget = target
        scrollAnimator = ValueAnimator.ofFloat(scroll, target).apply {
            duration = SCROLL_MILLIS
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener { setScroll(it.animatedValue as Float) }
            start()
        }
    }

    private fun setScroll(value: Float) {
        scroll = value
        bar.translationX = -value
        if (clipToViewport && viewportWidth > 0) {
            // 裁在给的范围里 (签那一排自己的坐标): 范围左缘 = 锚点, 签那一排排在锚点往右 startInsetPx、再往左挪了 scroll
            val left = (value - startInsetPx).toInt()
            clip.set(left, -bleedPx, left + viewportWidth, bar.height + bleedPx)
            bar.clipBounds = clip
        } else if (bar.clipBounds != null) {
            bar.clipBounds = null
        }
    }

    private companion object {
        /** 要焦点时签还没排出来, 至多等这么久再补给 (再晚多半用户已经去了别处) */
        const val PENDING_FOCUS_MILLIS = 1500L

        /** 横向滚到居中的时长 */
        const val SCROLL_MILLIS = 250L
    }
}
