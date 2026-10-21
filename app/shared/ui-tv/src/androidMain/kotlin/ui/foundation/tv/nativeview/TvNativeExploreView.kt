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
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_FADE_IN_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_FADE_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_HERO_SPLIT_BAND
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_BUTTONS_BOTTOM
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_DOT
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_DOTS_BOTTOM
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_DOT_GAP
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_DOT_INACTIVE_ALPHA
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_DOT_SELECTED
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_TEXT_BOTTOM
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_FLOOR_START
import kotlin.math.roundToInt

/**
 * 探索页海报墙: 行式海报墙 ([TvNativeRowWallView]: 组标题 + 横滑行与卡片的 hero 态) + 顶部区域的热门轮播 —— 轮播的背景图与文字 (同一张背景图、
 * 同一块文字, 焦点在卡片上进 hero 态时换成聚焦卡的, 尺寸在两套之间缩放)、「立即观看 / 新番时间表」两颗按钮、指示圆点 (压在卡片列表上面).
 * 轮播跟着列表一起上移 (见 [carouselShift]); 首行按上回「新番时间表」, 「新番时间表」按下进首行屏上第一列; hero 按钮在第一项按左出页面 (侧边栏).
 */
@SuppressLint("ViewConstructor")
class TvNativeExploreView(
    context: Context,
    sketch: Sketch,
    scope: CoroutineScope,
    style: TvNativeWallStyle,
    metrics: TvNativeExploreMetrics,
    heroTextStyle: TvNativeHeroTextStyle,
    buttonStyle: TvNativeHeroButtonStyle,
    headerStyle: TvNativeTextStyle,
) : TvNativeRowWallView(context, sketch, scope, style, metrics, heroTextStyle, headerStyle), TvExploreSurface {
    var listener: TvNativeExploreListener? = null
        set(value) {
            field = value
            rowListener = value
        }

    private val buttons = LinearLayout(context)
    private val primaryButton = TvNativeHeroButton(context, buttonStyle, filled = true)
    private val scheduleButton = TvNativeHeroButton(context, buttonStyle, filled = false)
    private val dots = TvNativeCarouselDotsView(context, metrics.dotPx, metrics.dotSelectedWidthPx, metrics.dotGapPx, metrics.dotInactiveAlpha)
    private var carouselSwipe: TvNativeHorizontalSwipe? = null

    /** 上次停的那颗 hero 按钮 (0 立即观看 / 1 新番时间表). */
    var lastHeroButton: Int = 0
    private var pendingHeroButton = -1

    // ---- 轮播 ----
    private var carouselSource: TvNativeHeroSource? = null

    /** 背景图尺寸 (0 = 轮播, 1 = 聚焦卡): 换来源时在两套之间插值. */
    private var mode = 0f
    private var modeAnimator: ValueAnimator? = null
    private var carouselGone = false
    private var lastDimSource: Boolean? = null
    private var buttonsAnimator: ValueAnimator? = null

    /** 轮播按钮自己的透明度 (淡入动画写的那份), 画的时候再乘点开时的淡没 (见 [TvNativeHeroStage.openFade]). */
    private var buttonsAlpha = 1f
    private var dotsShown = true
    private var dotsFade = 1f
    private var dotsAnimator: ValueAnimator? = null

    /** 换页交接途中分界线那层的浓度 (TvPosterWallTone.splitGate), 乘在轮播背景图上. */
    var splitGate: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyFrame()
        }

    init {
        buttons.orientation = LinearLayout.VERTICAL
        buttons.clipChildren = false
        buttons.addView(primaryButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        buttons.addView(scheduleButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        (scheduleButton.layoutParams as LinearLayout.LayoutParams).topMargin = metrics.buttonGapPx
        heroBox.extra = buttons
        addView(dots)
        primaryButton.onFocused = { onHeroButtonFocused(0) }
        scheduleButton.onFocused = { onHeroButtonFocused(1) }
        primaryButton.setOnClickListener { if (!openCarouselWithWallFocus()) listener?.onHeroButtonClick(0) }
        scheduleButton.setOnClickListener { listener?.onHeroButtonClick(1) }
        applyButtons(visible = true, animated = false)
        applyFrame()
    }

    // ------------------------------------------------------------------
    // 页面给的数据
    // ------------------------------------------------------------------

    fun update(style: TvNativeWallStyle, metrics: TvNativeExploreMetrics, heroTextStyle: TvNativeHeroTextStyle, buttonStyle: TvNativeHeroButtonStyle) {
        primaryButton.style = buttonStyle
        scheduleButton.style = buttonStyle
        (scheduleButton.layoutParams as LinearLayout.LayoutParams).topMargin = metrics.buttonGapPx
        update(style, metrics, heroTextStyle)
    }

    override fun showsCardSummaryOverridden(): Boolean = true

    // ---- 平板版画法 (见 padHero, TvPadHeroGeometry) ----

    /** 平板版: 轮播区高 —— 页顶到首组标题 (列表停在顶上时 hero 占位垫着的那一段). */
    private val padCarouselRegionPx: Int get() = metrics.listTopPx + metrics.spacerPx

    /** 平板版: 轮播时文字块下沿 (从页顶算; 列表滚动时跟着上移). */
    private val padCarouselTextBottomPx: Int get() = padCarouselRegionPx - dpPx(TV_PAD_CAROUSEL_TEXT_BOTTOM)

    override fun padBackdropHeightPx(): Int = padCarouselRegionPx

    override fun padTextBoxBottomPx(): Int = maxOf(padCarouselTextBottomPx, padCardTextBottomPx)

    /**
     * 平板版画法: 按钮从文字块里挪出来横排在轮播区左下 (文字块往上长, 按钮的位置不随文字长短变), 圆点靠左在按钮下面、改成小圆点;
     * 触屏上只留「新番时间表」(点轮播画面就是进详情页, 见 [carouselTap]).
     */
    override fun onPadHeroChanged(enabled: Boolean) {
        val m = metrics
        if (enabled) {
            heroBox.extra = null
            if (buttons.parent == null) addView(buttons)
            buttons.orientation = LinearLayout.HORIZONTAL
            (scheduleButton.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 0
                marginStart = m.buttonGapPx
            }
            dots.alignStart = true
            dots.setGeometry(
                dpPx(TV_PAD_CAROUSEL_DOT).toFloat(), dpPx(TV_PAD_CAROUSEL_DOT_SELECTED).toFloat(), dpPx(TV_PAD_CAROUSEL_DOT_GAP).toFloat(),
                TV_PAD_CAROUSEL_DOT_INACTIVE_ALPHA,
            )
        } else {
            if (buttons.parent === this) removeView(buttons)
            buttons.orientation = LinearLayout.VERTICAL
            (scheduleButton.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = m.buttonGapPx
                marginStart = 0
            }
            heroBox.extra = buttons
            dots.alignStart = false
            dots.setGeometry(m.dotPx, m.dotSelectedWidthPx, m.dotGapPx, m.dotInactiveAlpha)
        }
        carouselTap = if (enabled && touchInput) newCarouselTap() else null
        applyPadButtons()
        applyPadText(card = showsCard)
    }

    /**
     * 列表叠在 hero 之上、按下点在 hero 按钮上时把手势让给按钮 (列表整页大, 不让的话按钮收不到); 按钮按下即聚焦, 第一下点就是点击;
     * 轮播区左右滑翻轮播 (见 [TvNativeHorizontalSwipe]); 平板版画法点轮播画面进详情页 (见 [carouselTap]).
     */
    override fun onTouchInputChanged(enabled: Boolean) {
        primaryButton.focusOnPress = enabled
        scheduleButton.focusOnPress = enabled
        list.touchPassThrough = if (enabled) ::heroButtonAt else null
        carouselSwipe = if (enabled) {
            TvNativeHorizontalSwipe(context, this, ::inCarouselSwipeArea) { listener?.onSwitchCarousel(it) }
        } else {
            null
        }
        carouselTap = if (enabled && padHero) newCarouselTap() else null
        applyPadButtons()
    }

    override fun onTouchModeChanged() = applyPadButtons()

    /** 平板版画法在触屏上 (用手指时) 只留「新番时间表」; 平板接着键盘 / 遥控器按键时两颗都在. */
    private fun applyPadButtons() {
        val hide = padHero && touchMode
        primaryButton.visibility = if (hide) GONE else VISIBLE
    }

    /** 平板版画法: 点轮播画面 (按钮以外) 进这一部的详情页, 同「立即观看」. 只在触屏设备上装. */
    private var carouselTap: TvNativeHeroBlankTap? = null

    private fun newCarouselTap() = TvNativeHeroBlankTap(
        this,
        { padHero && !showsCard && !carouselGone && carouselAlpha() >= 0.5f && !wallFocus.opening },
        heroAreaBottom = {
            val location = IntArray(2)
            getLocationInWindow(location)
            location[1] + padCarouselRegionPx - list.scrolledPx()
        },
        onHeroTap = { if (!openCarouselWithWallFocus()) listener?.onHeroButtonClick(0) },
        onBlankTap = {},
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        carouselTap?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    fun setButtons(primaryText: String, primaryIcon: Bitmap?, scheduleText: String, scheduleIcon: Bitmap?) {
        primaryButton.bind(primaryText, primaryIcon)
        scheduleButton.bind(scheduleText, scheduleIcon)
    }

    fun setCarousel(count: Int, selected: Int, color: Int) {
        dots.count = count
        dots.selectedIndex = selected
        dots.color = color
    }

    /** 两路 hero 内容 (轮播 / 聚焦卡). */
    override fun setSources(carousel: TvNativeHeroSource, card: TvNativeHeroSource) {
        carouselSource = carousel
        cardHero = card
        applySources(reset = false)
    }

    // ------------------------------------------------------------------
    // 焦点 (顶部区域: 两颗 hero 按钮)
    // ------------------------------------------------------------------

    /**
     * 送焦到 hero 按钮. 还没布局 (刚建出来) 时记下, 布局完成再送. 按触屏的规矩走时不送 (焦点不带着滚动): 记下这一颗, 焦点停放
     * (见 touchPark), 页面照样收到这一颗拿到焦点.
     */
    fun focusHeroButton(button: Int): Boolean {
        cancelPending()
        if (touchMode) {
            pendingHeroButton = -1
            lastHeroButton = button
            // 已经停放着: 照样报这一颗 (页面据此清掉落点请求)
            if (touchParkedNow) onTouchParked() else touchPark()
            return true
        }
        if (!isLaidOut) {
            pendingHeroButton = button
            return false
        }
        pendingHeroButton = -1
        val target = if (button == 1) scheduleButton else primaryButton
        if (target.isFocused) {
            // 已经持着焦点: requestFocus 不会再回调, 当场按到位报上去 (页面据此清掉挂着的落点请求)
            onHeroButtonFocused(button)
            return true
        }
        return target.requestFocus()
    }

    override fun onFocusCardRequested() {
        pendingHeroButton = -1
    }

    override fun resolveTopAreaPending(): Boolean {
        if (pendingHeroButton < 0) return false
        val button = pendingHeroButton
        post { if (pendingHeroButton == button) focusHeroButton(button) }
        return true
    }

    /** 首行按上回「新番时间表」. */
    override fun onFirstRowUp() {
        focusHeroButton(1)
    }

    /** 卡片行一行都不剩: 回轮播按钮. */
    override fun onAllRowsGone() {
        focusHeroButton(lastHeroButton)
    }

    /** 从页面外进来 (侧边栏按右 / 返回、全局兜底): 回上次那张卡, 否则回上次停的那颗 hero 按钮. */
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (touchParkOnEntry()) return false
        val rowKey = focusedRowKey
        if (rowKey != null) {
            focusCard(rowKey, -1)
            if (findFocus() != null) return true
        }
        return focusHeroButton(lastHeroButton)
    }

    /** 探索页的焦点交接由页面的落点与 [onAllRowsGone] 管, 不另外改送系统送来的焦点. */
    override val redirectStrayFocus: Boolean get() = false

    /** 按触屏的规矩停放了焦点: 对页面同焦点回到上次那颗 hero 按钮 (页面回到轮播态), 列表不动. */
    override fun onTouchParked() {
        focusedRowKey = null
        listener?.onHeroButtonFocused(lastHeroButton)
        listener?.onFocusParked()
        applyFrame()
    }

    override fun settleTouchAtTop(nearTop: Boolean): Boolean {
        if (!nearTop) return false
        focusHeroButton(lastHeroButton)
        return true
    }

    override fun handleTopAreaKey(focused: View?, code: Int): Boolean {
        if (focused !== primaryButton && focused !== scheduleButton) return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (listener?.onSwitchCarousel(-1) != true) listener?.onExitLeft()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> listener?.onSwitchCarousel(1)

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (focused === scheduleButton) primaryButton.requestFocus()
            }

            else -> {
                if (focused === primaryButton) {
                    scheduleButton.requestFocus()
                } else {
                    // 新番时间表按下: 进首行屏上第一列
                    firstRowKey()?.let { focusCardAtColumn(it, 0) }
                }
            }
        }
        return true
    }

    private fun onHeroButtonFocused(button: Int) {
        list.heldFocus = null
        lastHeroButton = button
        cancelPending()
        focusedRowKey = null
        setCardAreaFocus(false)
        listener?.onHeroButtonFocused(button)
        // 焦点回到轮播按钮: 回卡片墙, 列表按远跳 spring 滚回顶 (轮播随列表一起滑回原位)
        if (timeline.active) {
            timeline.setActive(false, animated = true)
            TvNativeFocusCues.setSpotlight(this, on = false, animated = true)
            listener?.onHeroActiveChanged(false)
        }
        list.scrollToStop(0, TvScrollSpring.Far, animatedScroll)
        applyFrame()
    }

    // ------------------------------------------------------------------
    // 触摸 (只在触屏设备上, 见 touchInput)
    // ------------------------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        carouselSwipe?.onIntercept(ev) == true || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        carouselSwipe?.onTouch(event) == true || super.onTouchEvent(event)

    /** 屏幕坐标 ([rawX], [rawY]) 落在看得见的 hero 按钮上. */
    private fun heroButtonAt(rawX: Float, rawY: Float): Boolean {
        val shown = if (padHero) buttons.visibility == VISIBLE else heroBox.extraPresent && heroBox.alpha >= 0.5f
        if (!shown || buttons.alpha < 0.5f) return false
        return touchHits(primaryButton, rawX, rawY) || touchHits(scheduleButton, rawX, rawY)
    }

    private fun touchHits(view: View, rawX: Float, rawY: Float): Boolean {
        if (!view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX < location[0] + view.width && rawY >= location[1] && rawY < location[1] + view.height
    }

    /** 轮播区: 正显示轮播 (不是聚焦卡、没滚出屏), 指示圆点那条线以上 (卡片行都在它下面). */
    private fun inCarouselSwipeArea(x: Float, y: Float): Boolean =
        !showsCard && !carouselGone && carouselAlpha() >= 0.5f && y < metrics.dotsCenterYPx - carouselShift() + metrics.dotPx

    // ------------------------------------------------------------------
    // 点开 (轮播上按「立即观看」)
    // ------------------------------------------------------------------

    /** 轮播上按「立即观看」: 背景此刻是轮播这一部的、能变清晰就先对焦, 到位再进 (返回 true); 否则 false, 调用方当场进. */
    private fun openCarouselWithWallFocus(): Boolean {
        val wall = wallBackdrop ?: return false
        val src = carouselSource
        val t = wall.currentTarget
        val matches = !showsCard && !carouselGone && src != null && !src.dimming && t != null && t.sharp && t.subjectId != null &&
            t.subjectId == src.rawSubjectId
        wallOpenRow = CAROUSEL_WALL_OPEN
        if (wallFocus.open(0, matches)) return true
        wallOpenRow = null
        return false
    }

    override fun onWallOpenedAt(rowKey: String, index: Int) {
        if (rowKey == CAROUSEL_WALL_OPEN) listener?.onHeroButtonClick(0) else super.onWallOpenedAt(rowKey, index)
    }

    // ------------------------------------------------------------------
    // 逐帧联动
    // ------------------------------------------------------------------

    // 平板版画法的轮播区 / hero 区自己铺满、下缘渐变到卡片墙底色: 整屏底色不分界
    override fun splitY(): Float = if (padHero) -dpPx(TV_POSTER_WALL_HERO_SPLIT_BAND).toFloat() else metrics.carouselBottomPx - carouselShift()

    private fun carouselShift(): Float {
        val m = metrics
        val items = list.items
        val first = items.indexOfFirst { it is TvNativeExploreItem.Row }
        val firstRowStop = if (first < 0) m.spacerPx else m.centeredScroll(items, first)
        return tvNativeCarouselShift(list.scrolledPx().toFloat(), firstRowStop, m.overhangPx)
    }

    private fun carouselAlpha(): Float = if (focusedRowKey != null) 1f - timeline.above else 1f

    /** 按此刻的滚动量、hero 态进度、尺寸插值, 摆背景图 / 文字 / 指示器, 算各项的淡化. 只动 RenderNode 属性. */
    override fun applyFrame() {
        val m = metrics
        val scrolled = list.scrolledPx().toFloat()
        val shift = carouselShift()
        // 换来源 (轮播 ↔ 聚焦卡) 的判据: 进时等轮播露着的那截随上面几行淡没, 出时等聚焦卡淡完
        val card = cardShownNow()
        if (card != showsCard || !modeSettled) onShowsCardChanged(card)
        val gone = shift >= m.carouselBottomPx
        if (gone != carouselGone) {
            carouselGone = gone
            // 轮播的图整个滚出屏就不挂 (挂着还会登记成放大转场的来源), 滚回来时直接出现, 不交叉淡入
            if (!showsCard) {
                backdrop.rebuild()
                applySources(reset = false)
            }
        }
        val md = mode
        val carouselA = carouselAlpha()
        val heroShown = stage.contentAlpha
        val wallFade = stage.openFade
        // 点开时 (wallFade) 聚焦卡的背景图随各行淡没, 底下的模糊背景变清晰接上
        backdrop.alpha = (if (showsCard) heroShown else carouselA * splitGate) * (1f - wallFade)
        // 铺着模糊背景: 最底下铺卡片墙的底色盖住主壳的轮播分界带, 跟着上面几行淡入淡出 (见 wallColor)
        val floor = if (heroBlur) (timeline.above * (wallColor ushr 24)).roundToInt() else 0
        heroFloor.color = if (floor <= 0) Color.TRANSPARENT else (wallColor and 0xFFFFFF) or (floor shl 24)
        val wall = wallBackdrop
        if (wall != null) {
            if (showsCard) {
                // hero 态的模糊背景跟着 hero 时间线显隐 (回轮播时随聚焦卡的内容一起淡没)
                wall.alpha = heroShown
                wall.translationY = 0f
                wall.setFloor(Float.NaN, 0f, Color.TRANSPARENT)
            } else {
                // 轮播的模糊背景跟着轮播的图走 (滚动、随上面几行淡没), 往下渐变成卡片墙的底色;
                // hero 态里按上回轮播时 (尺寸缩放过去) 底边跟着淡入
                wall.alpha = if (carouselGone) 0f else carouselA * splitGate
                if (padHero) {
                    // 平板版: 跟着列表一起滚 (轮播区就是列表顶上那一段), 从轮播区的这个高度起渐变到卡片墙的底色, 到轮播区底边全遮
                    wall.translationY = -scrolled * (1f - md)
                    val region = padCarouselRegionPx.toFloat()
                    wall.setFloor(region * TV_PAD_HERO_FLOOR_START, region * (1f - TV_PAD_HERO_FLOOR_START), wallColor, opacity = 1f - md)
                } else {
                    wall.translationY = -shift * (1f - md)
                    val fadeTop = TV_CAROUSEL_BLUR_FADE_START * m.pageHeightPx
                    val fadeBottom = TV_CAROUSEL_BLUR_FADE_END * m.pageHeightPx
                    wall.setFloor(fadeTop, (fadeBottom - fadeTop).coerceAtLeast(1f), wallColor, opacity = 1f - md)
                }
            }
            // 叠在模糊背景上 (轮播、hero 态里背景图看得见时) 背景图的边缘擦成透明. 看不见时不擦, 不走离屏层
            backdrop.feather = !showsCard || heroShown > 0f
        } else {
            backdrop.feather = false
        }
        // 铺着模糊背景: 进详情页从整屏背景起, 背景图不登记成放大转场的来源; 轮播那一部没有清晰图 (只有竖版封面) 时照旧从背景图放大
        backdrop.zoomSource = !heroBlur || (!showsCard && wall?.currentTarget?.sharp != true)
        // 平板版: 图高铺满轮播区 / hero 区 (两块高不同, 缩放过去), 跟着列表一起滚
        val cardScale = if (padHero) padCardRegionPx.toFloat() / padCarouselRegionPx else m.cardBackdropScale
        val scale = 1f + (cardScale - 1f) * md
        backdrop.scaleX = scale
        backdrop.scaleY = scale
        backdrop.translationY = (if (padHero) -scrolled else -shift) * (1f - md)
        if (md != lastTreatmentMode) {
            lastTreatmentMode = md
            backdrop.treatment = treatmentFor?.invoke(md)
        }
        backdrop.publishZoom()
        if (padHero) {
            // 平板版: 文字块贴着两路各自的下沿 (块在两者中低的那个位置上排, 见 padTextBoxBottomPx), 轮播时跟着列表上移
            val box = padTextBoxBottomPx()
            val toBottom = (padCarouselTextBottomPx - box) * (1f - md) + (padCardTextBottomPx - box) * md
            heroBox.translationY = toBottom - scrolled * (1f - md)
            buttons.translationY = -scrolled * (1f - md)
            dots.translationY = -scrolled * (1f - md)
        } else {
            heroBox.translationY = -scrolled * (1f - md)
            dots.translationY = -shift * (1f - md)
        }
        heroBox.alpha = if (showsCard) timeline.text else carouselA
        dots.alpha = dotsFade * carouselA * (1f - wallFade)
        buttons.alpha = buttonsAlpha * (1f - wallFade)
        // 轮播标题随文字块移出屏幕上缘就停跑马灯 (看不见的跑马灯照样每帧重画)
        titleHeightPx = heroText.titleHeight.takeIf { it > 0 } ?: titleHeightPx
        val titleOnScreen = scrolled * (1f - md) < m.heroTopPx + titleHeightPx
        heroText.setTitleMarquee(showsCard || titleOnScreen)
        heroText.publishTitle()
        applyHeaderEmphasis()
        applyItemFades()
    }

    override fun onShowsCardChanged(card: Boolean) {
        val target = if (card) 1f else 0f
        val visibleBefore = if (card) carouselAlpha() > 0f else timeline.content > 0f
        modeAnimator?.cancel()
        val settled = modeSettled
        markShowsCard(card)
        if (settled && transitions && visibleBefore) {
            // 换之前那一路看得见 (hero 态里在首行按上回轮播): 尺寸缩放过去, 图交叉淡入, 文字按换条目的节奏换
            val from = mode
            modeAnimator = ValueAnimator.ofFloat(from, target).apply {
                duration = TV_NATIVE_WALL_BACKDROP_RESIZE_MILLIS
                interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
                addUpdateListener {
                    mode = it.animatedValue as Float
                    applyFrame()
                }
                start()
            }
            applySources(reset = false)
        } else {
            // 看不见的那一刻换: 直接到位, 图与文字整个重建 (不交叉淡入, 否则没褪完的旧图 / 旧字会跟着露出来)
            mode = target
            if (settled) backdrop.rebuild()
            applySources(reset = settled)
        }
        applyButtons(visible = !card, animated = settled)
        applyDots(visible = !card)
        heroText.summaryWidthPx = if (card) metrics.cardSummaryWidthPx else metrics.carouselSummaryWidthPx
        applyPadText(card)
        heroBox.requestLayout()
    }

    /** 按当前来源换背景图与文字. [reset] = 换来源的重建 (文字当场换). */
    override fun applySources(reset: Boolean) {
        val src = (if (showsCard) cardHero else carouselSource) ?: return
        // 按下即压暗: 同一来源里换了条目 (轮播翻页 / hero 态里换卡) 就压暗, 换来源不压
        if (lastDimSource == showsCard && lastDimSubject != src.rawSubjectId) backdrop.triggerPressDim()
        lastDimSource = showsCard
        lastDimSubject = src.rawSubjectId
        backdrop.dimming = src.dimming
        val target = if (!showsCard && carouselGone) null else src.backdrop
        backdrop.show(target)
        // 模糊背景: 聚焦卡 / 轮播铺那一部的横版背景图 (整部的那张), 轮播滚出屏就撤. 换来源的重建 (看不见的那一刻换) 当场撤掉旧的那张,
        // 不跟新的交叉淡入
        stage.showWall(if (!showsCard && carouselGone) null else src.wall, replace = reset)
        val transition = when {
            reset -> TvNativeTextTransition.Reset
            !showsCard && src.autoAdvanced -> TvNativeTextTransition.Carousel
            else -> TvNativeTextTransition.Key
        }
        // 压在模糊背景上的字照 vibrancy 画 (轮播与 hero 态都铺着)
        heroText.setText(stage.textOf(src), transition)
    }

    private fun applyButtons(visible: Boolean, animated: Boolean) {
        buttonsAnimator?.cancel()
        buttons.visibility = if (visible) VISIBLE else GONE
        heroBox.extraPresent = visible
        if (!visible) return
        // 轮播按钮每次重新出现都从 0 淡入 (hero 态里回轮播时文字在交叉淡入, 按钮不该当帧冒出来)
        if (!animated) {
            buttonsAlpha = 1f
            buttons.alpha = 1f - stage.openFade
            return
        }
        buttonsAlpha = 0f
        buttons.alpha = 0f
        buttonsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TV_HERO_BUTTON_FADE_IN_MILLIS.toLong()
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener {
                buttonsAlpha = it.animatedValue as Float
                buttons.alpha = buttonsAlpha * (1f - stage.openFade)
            }
            start()
        }
    }

    private fun applyDots(visible: Boolean) {
        if (dotsShown == visible) return
        dotsShown = visible
        dotsAnimator?.cancel()
        val from = dotsFade
        dotsAnimator = ValueAnimator.ofFloat(from, if (visible) 1f else 0f).apply {
            duration = (if (visible) TV_HERO_BUTTON_FADE_IN_MILLIS else TV_HERO_BUTTON_FADE_OUT_MILLIS).toLong()
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener {
                dotsFade = it.animatedValue as Float
                dots.alpha = dotsFade * carouselAlpha() * (1f - stage.openFade)
            }
            start()
        }
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    override fun measureTopArea() {
        if (padHero) buttons.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        dots.measure(
            MeasureSpec.makeMeasureSpec(metrics.pageWidthPx.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((metrics.dotPx.toInt() * 3).coerceAtLeast(0), MeasureSpec.EXACTLY),
        )
    }

    override fun layoutTopArea() {
        val m = metrics
        if (padHero) {
            // 平板版: 自下而上圆点 → 按钮, 贴轮播区左下 (文字块的左缘)
            val x = m.bleedLeftPx + m.heroStartPx
            val by = padCarouselRegionPx - dpPx(TV_PAD_CAROUSEL_BUTTONS_BOTTOM) - buttons.measuredHeight
            buttons.layout(x, by, x + buttons.measuredWidth, by + buttons.measuredHeight)
            val dotCenter = padCarouselRegionPx - dpPx(TV_PAD_CAROUSEL_DOTS_BOTTOM) - dpPx(TV_PAD_CAROUSEL_DOT) / 2
            val dy = dotCenter - dots.measuredHeight / 2
            dots.layout(x, dy, x + dots.measuredWidth, dy + dots.measuredHeight)
            return
        }
        val dy = m.dotsCenterYPx - dots.measuredHeight / 2
        dots.layout(m.bleedLeftPx, dy, m.bleedLeftPx + dots.measuredWidth, dy + dots.measuredHeight)
    }
}

/** 海报墙背景图在轮播与聚焦卡两套尺寸之间缩放的时长 (hero 态里在首行按上回到轮播), 同 hero 背景图淡入. */
private const val TV_NATIVE_WALL_BACKDROP_RESIZE_MILLIS = 400L

/** 点开的是轮播 (按「立即观看」), 不是哪一行的卡 (见 TvNativeRowWallView 的 wallOpenRow). */
private const val CAROUSEL_WALL_OPEN = "carousel"

/** 热门轮播的模糊背景从这里 (离页面顶, 占页面高的比例) 开始往卡片墙的底色渐变: 过渡带放宽到大半个轮播, 不显得急. */
internal const val TV_CAROUSEL_BLUR_FADE_START = 0.30f

/** 渐变到这里整片是卡片墙的底色 (轮播背景图的下缘在 0.67, 再往下一截). */
internal const val TV_CAROUSEL_BLUR_FADE_END = 0.75f
