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
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

/**
 * 海报墙卡片 ([TvNativeCardView]) 的聚焦提示, 补上 tvOS 聚焦效果里安卓版 Apple TV App 没有的几样 (放大与抬高的投影由卡片自己画):
 *
 * - **高光**: 聚焦时封面正中偏上一大片柔光, 跟着聚焦程度原地淡入淡出 (同 tvOS 的 highlight 聚焦效果: 光随抬起一起出现, 不从侧边滑入;
 *   tvOS 上它还随手指在触摸板上的移动挪动, 遥控器方向键没有这个输入, 停在原地).
 * - **失焦慢慢缩回**: 焦点离开的那张用 [TV_NATIVE_SLOW_FOCUS_OUT_MILLIS] 缩回 (tvOS 实测约 0.4 秒), 比聚焦抬起慢.
 * - **停手压暗** (设置, 默认开, 见 [dimming]): 停手 [idleMillis] 后同一页其余卡压暗 (封面乘颜色滤镜, 见 TvNativeCoverView.shade)、聚焦那张再放大一点, 按方向键 / 返回等 (或焦点换了) 就撤 —— tvOS 的
 *   「After a period of inactivity, out-of-focus content dims and the focused element expands」. 确定键不撤: 按确定是「就要这一张」
 *   (进 hero 态 / 点开), 撤了再暗就是亮一下又暗回去. 只在挂了 [attachPage] 的页面 (探索页、追番 / 搜索 / 时间表网格页) 里做;
 *   焦点不在卡上、窗口没焦点 (弹窗开着) 时不做.
 * - **聚光** ([setSpotlight], 与停手压暗同一个开关): 页面进 hero 态时当场压暗 (不等停手), 整个 hero 态里一直暗着, 换卡时亮的那张跟着焦点走;
 *   退出 hero 态撤掉, 回到停手计时.
 *
 * 全局一份: 同一时刻只有一页在接收按键. 页面视图挂上 / 离开窗口时 [attachPage] / [detachPage], 按键先过 [onKey], 进出 hero 态时
 * [setSpotlight]; 卡片挂上 / 离开窗口与拿到焦点时报到这里. 都在主线程.
 */
internal object TvNativeFocusCues {
    /** 停手压暗与 hero 态压暗 (设置): 由 Compose 侧同步 (见 rememberTvNativeWallStyle); 关掉时正暗着的当场撤掉. */
    var dimming: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) setIdle(0f, animated = true)
        }

    private val pages = ArrayList<View>()
    private val cards = LinkedHashSet<TvNativeCardView>()

    /** 停手计时与压暗所在的页面. */
    private var idlePage: View? = null
    private var idleCards: List<TvNativeCardView> = emptyList()
    private var idleLevel = 0f

    /** 压暗正走向的程度: 撤到 0 (而不是淡入动画的起点 0) 时才清掉参与的卡. */
    private var idleTarget = 0f
    private var idleAnimator: ValueAnimator? = null
    private val idleRunnable = Runnable { enterIdle() }

    /** 在 hero 态 (聚光) 的页面: 压暗不随按键撤, 跟着焦点走. */
    private var spotlightPage: View? = null

    /** 聚光开着、焦点还没落到卡上 (返回本页时先恢复 hero 态): 落到卡上时压暗走不走动画. */
    private var spotlightAnimated = true

    fun attachPage(page: View) {
        if (page !in pages) pages += page
    }

    fun detachPage(page: View) {
        pages -= page
        if (spotlightPage === page) spotlightPage = null
        if (idlePage === page) {
            page.removeCallbacks(idleRunnable)
            setIdle(0f, animated = false)
            idlePage = null
        }
    }

    /** 页面视图的按键 (dispatchKeyEvent 最先调): 按下撤掉停手压暗、重新计时 (确定键与 hero 态里除外). */
    fun onKey(page: View, event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        // 聚光时压暗由 hero 态管; 确定键 (进 hero 态 / 点开) 不撤
        if (spotlightPage === page || tvNativeIsConfirmKey(event.keyCode)) return
        setIdle(0f, animated = true)
        armIdle(page)
    }

    fun onCardAttached(card: TvNativeCardView) {
        cards += card
        val page = spotlightPage ?: idlePage
        if (idleLevel > 0f && page != null && tvNativeIsInside(card, page)) {
            card.cues.setIdleFocused(false, animate = false)
            card.cues.idle = idleLevel
            idleCards = idleCards + card
        }
    }

    fun onCardDetached(card: TvNativeCardView) {
        cards -= card
        card.cues.reset()
    }

    /** [card] 拿到焦点: hero 态里亮的那张换成它, 否则撤掉停手压暗、重新计时. */
    fun onCardFocused(card: TvNativeCardView) {
        val page = pages.firstOrNull { tvNativeIsInside(card, it) }
        if (page != null && page === spotlightPage) {
            // hero 态: 一直暗着, 亮的那张换成这一张
            moveSpotlight(page, card)
            return
        }
        // 焦点换了: 撤掉停手压暗, 从这一页重新计时 (程序化送焦 —— 进页落点 —— 也从这里起算)
        setIdle(0f, animated = true)
        page?.let { armIdle(it) }
    }

    /**
     * 页面 [page] 进 / 出 hero 态. 进: 其余卡当场压暗 (不等停手), 一直暗到出 hero 态; 焦点此刻不在卡上 (返回本页先恢复 hero 态) 就等落到卡上再暗.
     * 出: 撤掉, 回到停手计时. [animated] = false 当场到位.
     */
    fun setSpotlight(page: View, on: Boolean, animated: Boolean) {
        if (on) {
            spotlightPage = page
            spotlightAnimated = animated
            if (idlePage === page) page.removeCallbacks(idleRunnable)
            val focused = page.findFocus() as? TvNativeCardView
            if (focused != null && focused.isFocused) dimAround(page, focused, animated, SPOTLIGHT_IN_MILLIS)
        } else if (spotlightPage === page) {
            spotlightPage = null
            setIdle(0f, animated)
            armIdle(page)
        }
    }

    private fun moveSpotlight(page: View, focused: TvNativeCardView) {
        if (idleTarget == 0f) {
            dimAround(page, focused, spotlightAnimated, SPOTLIGHT_IN_MILLIS)
            return
        }
        if (focused !in idleCards) {
            focused.cues.idle = idleLevel
            idleCards = idleCards + focused
        }
        for (card in idleCards) card.cues.setIdleFocused(card === focused, animate = focused.cues.animated)
    }

    /** 这一页其余卡压暗、[focused] 亮着放大. */
    private fun dimAround(page: View, focused: TvNativeCardView, animated: Boolean, inMillis: Long) {
        if (!dimming || !focused.cues.enabled) return
        idleCards = cards.filter { it.cues.enabled && tvNativeIsInside(it, page) }
        for (card in idleCards) card.cues.setIdleFocused(card === focused, animate = false)
        setIdle(1f, animated = animated && focused.cues.animated, inMillis = inMillis)
    }

    /** 停手多久后压暗 (测试里调短). */
    internal var idleMillis: Long = IDLE_MILLIS

    private fun armIdle(page: View) {
        idlePage?.removeCallbacks(idleRunnable)
        idlePage = page
        page.postDelayed(idleRunnable, idleMillis)
    }

    private fun enterIdle() {
        val page = idlePage ?: return
        if (page === spotlightPage) return
        if (!page.isAttachedToWindow || !page.hasWindowFocus()) return
        val focused = page.findFocus() as? TvNativeCardView ?: return
        if (!focused.isFocused) return
        dimAround(page, focused, animated = true, inMillis = IDLE_IN_MILLIS)
    }

    /** 压暗走到 [to] (变暗用 [inMillis]); 撤到 0 时清掉参与的卡. */
    private fun setIdle(to: Float, animated: Boolean, inMillis: Long = IDLE_IN_MILLIS) {
        if (idleLevel == to && idleAnimator == null) return
        idleAnimator?.cancel()
        idleAnimator = null
        idleTarget = to
        val from = idleLevel
        if (!animated || from == to) {
            applyIdle(to)
            return
        }
        idleAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = if (to > from) inMillis else IDLE_OUT_MILLIS
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener { applyIdle(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (idleAnimator === animation) idleAnimator = null
                }
            })
            start()
        }
    }

    private fun applyIdle(level: Float) {
        idleLevel = level
        for (card in idleCards) card.cues.idle = level
        if (level == 0f && idleTarget == 0f) {
            for (card in idleCards) card.cues.setIdleFocused(false, animate = false)
            idleCards = emptyList()
        }
    }
}

/**
 * 卡片聚焦提示 ([TvNativeFocusCues]) 随主题与视觉效果档变的那几样, 由 rememberTvNativeWallStyle 给.
 *
 * @param enabled 这组卡做不做高光与停手压暗 (压暗还要设置开着, 见 [TvNativeFocusCues.dimming]).
 * @param animated 高光滑入与压暗走动画 (流畅档 false: 高光当场停在停点、压暗当场到位).
 * @param idleDim 停手后其余卡压暗多少 (0..1, 同盖一层这么浓的黑).
 */
@Immutable
data class TvNativeFocusCueStyle(
    val enabled: Boolean,
    val animated: Boolean = true,
    val idleDim: Float = 0f,
) {
    companion object {
        /** 不做任何提示. */
        val None = TvNativeFocusCueStyle(enabled = false)
    }
}

/**
 * 一张卡的聚焦提示 (卡片视图持有一份, 见 [TvNativeFocusCues]): 高光画在封面上 ([draw]), 压暗由封面乘颜色滤镜 ([shade]), 停手时聚焦那张多放大的
 * 倍数 ([idleScale]) 由卡片乘在聚焦倍数上.
 */
internal class TvNativeCardCues(private val style: TvNativeFocusCueStyle, private val host: Host) {
    interface Host {
        /** 封面重画 (高光 / 压暗变了). */
        fun invalidateCover()

        /** 放大倍数或番名透明度变了: 卡片重新套一遍聚焦外观. */
        fun applyCues()
    }

    val enabled: Boolean get() = style.enabled
    val animated: Boolean get() = style.animated

    /** 停手压暗的程度 (0..1), 由 [TvNativeFocusCues] 推. */
    var idle: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            host.applyCues()
            if (focusBlend < 1f) host.invalidateCover()
        }

    /** 压暗时这张是聚焦的那张: 放大而不压暗. 改用 [setIdleFocused]. */
    var idleFocused: Boolean = false
        private set

    /** 压暗时这张算「聚焦那张」的程度 (0 = 压暗, 1 = 放大): hero 态里换卡时两张交接. */
    private var focusBlend = 0f
    private var blendAnimator: ValueAnimator? = null

    /** 压暗时这张是不是聚焦的那张; [animate] = true 时与上一张交接 (hero 态里换卡), 时长同聚焦动画. */
    fun setIdleFocused(value: Boolean, animate: Boolean) {
        if (idleFocused == value) return
        idleFocused = value
        blendAnimator?.cancel()
        blendAnimator = null
        val to = if (value) 1f else 0f
        if (!animate || !style.animated || idle == 0f) {
            focusBlend = to
            host.applyCues()
            host.invalidateCover()
            return
        }
        blendAnimator = ValueAnimator.ofFloat(focusBlend, to).apply {
            duration = SPOTLIGHT_MOVE_MILLIS
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener {
                focusBlend = it.animatedValue as Float
                host.applyCues()
                host.invalidateCover()
            }
            start()
        }
    }

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaderWidth = 0
    private var shaderHeight = 0

    /** 离开窗口 (回收): 动画停掉, 回到静止的样子. */
    fun reset() {
        blendAnimator?.cancel()
        blendAnimator = null
        idleFocused = false
        focusBlend = 0f
        idle = 0f
    }

    /** 停手时聚焦那张多放大的倍数 (乘在聚焦倍数上; [focus] = 聚焦程度). 番名跟着它往下让. */
    fun idleScale(focus: Float): Float =
        if (idle > 0f && focusBlend > 0f) 1f + IDLE_EXTRA_SCALE * idle * focusBlend * focus else 1f

    /** 停手时其余卡的番名跟着暗. */
    fun labelAlpha(): Float = 1f - shade()

    /** 压暗的浓度 (0..1): 等同在封面上叠一层这么不透明的黑. 封面按它乘颜色滤镜, 见 TvNativeCoverView.shade. */
    fun shade(): Float =
        if (idle > 0f && focusBlend < 1f) style.idleDim * idle * (1f - focusBlend) else 0f

    /** 高光中心的不透明度: 跟着聚焦程度 [focus] 原地淡入淡出 (同 tvOS: 光随抬起一起出现, 随缩回一起淡掉). */
    fun sheenStrength(focus: Float): Float = if (style.enabled) SHEEN_ALPHA * focus else 0f

    /** 高光画在封面上 (封面图与角标之后、玻璃边之前). [focus] = 卡片此刻的聚焦程度, 高光随它淡入淡出. */
    fun draw(canvas: Canvas, width: Int, height: Int, focus: Float) {
        if (!style.enabled || width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        ensureShaders(width, height)
        val sheen = sheenStrength(focus)
        if (sheen > 0f) {
            sheenPaint.alpha = (255 * sheen.coerceIn(0f, 1f)).roundToInt()
            canvas.drawRect(0f, 0f, w, h, sheenPaint)
        }
    }

    private fun ensureShaders(width: Int, height: Int) {
        if (width == shaderWidth && height == shaderHeight) return
        shaderWidth = width
        shaderHeight = height
        // 高光: 以停点为心的白色柔光
        sheenPaint.shader = RadialGradient(
            width * SHEEN_CENTER_X, height * SHEEN_CENTER_Y, width * SHEEN_RADIUS,
            intArrayOf(Color.WHITE, Color.argb(SHEEN_MID_ALPHA, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, SHEEN_MID_STOP, 1f),
            Shader.TileMode.CLAMP,
        )
    }
}

/**
 * 高光的中心 (封面宽 / 高的比例) 与半径 (封面宽的倍数): 照 tvOS 17 highlight 效果的实测 (WWDC23 10162 逐帧) —— 一大片柔光, 质心在卡面正中,
 * 最亮处偏上 (≈ 0.3 高处), 盖住大半卡面.
 */
private const val SHEEN_CENTER_X = 0.5f
private const val SHEEN_CENTER_Y = 0.3f
private const val SHEEN_RADIUS = 1.3f

/** 高光的径向渐变: 中心白, 到半径的 [SHEEN_MID_STOP] 处剩 [SHEEN_MID_ALPHA] / 255, 边上透明. 整体再乘 [SHEEN_ALPHA]. */
private const val SHEEN_MID_STOP = 0.45f
private const val SHEEN_MID_ALPHA = 102

/** 高光中心的白 (全聚焦时): tvOS 实测中心约 20%, 电视上看着偏亮, 取 15%. */
private const val SHEEN_ALPHA = 0.15f

/** 停手多久后压暗. */
private const val IDLE_MILLIS = 3000L

/** 停手压暗时聚焦那张在聚焦倍数上再放大多少. */
private const val IDLE_EXTRA_SCALE = 0.04f

/** 失焦缩回的时长: tvOS 17 实测失焦缩回约 0.4 秒 (聚焦抬起只要 0.1 秒多). */
internal const val TV_NATIVE_SLOW_FOCUS_OUT_MILLIS = 400L

/** 停手压暗淡入 / 撤掉的时长. */
private const val IDLE_IN_MILLIS = 650L
private const val IDLE_OUT_MILLIS = 150L

/** 进 hero 态时压暗淡入的时长 (同 hero 背景渐入那一档); hero 态里换卡时亮的那张交接的时长 (同卡片聚焦动画). */
private const val SPOTLIGHT_IN_MILLIS = 300L
private const val SPOTLIGHT_MOVE_MILLIS = 150L
