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
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import me.him188.ani.app.ui.foundation.tv.TV_CAROUSEL_TEXT_IN_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_CAROUSEL_TEXT_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_TEXT_STAGGER_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_ENTER_AT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_HIDE_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_IN_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import kotlin.math.max

/** 一段带颜色的字 (hero 信息行里的开播状态、总集数、标签等). */
@Immutable
data class TvNativeTextSpan(val text: String, val color: Int)

/**
 * hero 信息块里的「下一集」一行 (探索页继续观看 / 追番页): [lead] 集号 (不截断), [name] 集名 (放不下跑马灯), [tail] 尾段 (剩余分钟 /
 * 已看完, 不截断). 三段同色 [color].
 */
@Immutable
data class TvNativeHeroStatus(
    val lead: String,
    val name: String?,
    val tail: String?,
    val color: Int,
)

/**
 * hero 文字块要显示的内容. 字符串由页面在 Compose 里按字符串资源排好, 原生只管排版与过渡.
 *
 * @param rating 评分数字 (如 "8.7"), 画成「★ 8.7/10」; null 不画.
 * @param meta 评分之后的那一串 (开播状态 · 总集数 / 标签 / 开播年月), 按段着色.
 * @param infoReady 条目信息到了: 信息行、下一集行、简介才出现 (没到时只有标题).
 */
@Immutable
data class TvNativeHeroText(
    val subjectId: Int,
    val title: String,
    val infoReady: Boolean,
    val rating: String? = null,
    val meta: List<TvNativeTextSpan> = emptyList(),
    val status: TvNativeHeroStatus? = null,
    val summary: String = "",
)

/**
 * hero 文字块的排版参数 (px), 由 Compose 按主题、界面缩放与视觉效果档位算好.
 *
 * @param lineSpacingPx 标题 / 信息行 / 下一集行 / 简介之间的间距 (探索页 10dp, 追番页 8dp).
 * @param statusHeightPx 下一集行的定高 (0 = 按内容高).
 * @param marqueeRepeat 标题 (与下一集集名) 跑马灯的圈数: 0 = 不跑 (视觉效果流畅档), -1 = 一直跑.
 * @param stagger 分行错落进场 (tvHeroTextStaggerEnabled); [animated] = false (流畅档) 时换字不过渡.
 */
@Immutable
data class TvNativeHeroTextStyle(
    val title: TvNativeTextStyle,
    val titleMaxLines: Int,
    val rating: TvNativeTextStyle,
    val meta: TvNativeTextStyle,
    val status: TvNativeTextStyle,
    val summary: TvNativeTextStyle,
    val star: Bitmap?,
    val starSizePx: Int,
    val starGapPx: Int,
    val metaGapPx: Int,
    val lineSpacingPx: Int,
    val statusHeightPx: Int,
    val slidePx: Int,
    val stagger: Boolean,
    val animated: Boolean,
    val marqueeRepeat: Int,
)

/** hero 文字换内容的节奏 (见 TvScrollActivity.kt 的 tvScrollHiddenTextTransform / tvCarouselTextTransform). */
enum class TvNativeTextTransition {
    /** 按键换卡 / 滚动停稳: 旧字 300ms 淡出 (藏起来 400ms), 新字 310ms 起 200ms 滑入 (从隐藏态出来的 0ms 起). */
    Key,

    /** 轮播自动换页: 350ms 淡出, 350ms 起 450ms 滑入. */
    Carousel,

    /** 整块重建 (换来源的那一刻, 文字块看不见): 当场换, 不过渡. */
    Reset,
}

/**
 * hero 文字块 (探索 / 追番 / 搜索三页共用): 标题 → 信息行 (★评分 + 分段着色的一串) → 下一集行 → 简介 (占满剩余高度, 最后一行
 * 省略号), 纵向排, 行距 [TvNativeHeroTextStyle.lineSpacingPx]. 宽度: 标题 [titleWidthPx], 下一集行与简介 [summaryWidthPx].
 *
 * 换条目的过渡: 旧字整块原地线性淡出, 新字按行错开 40ms 从右 14dp 处边滑边淡入 (见 [TvNativeTextTransition]), 两段先后不重叠;
 * 同一条目的内容变了 (条目信息晚到) 原地换, 不过渡; 淡出途中目标又回到正在淡出的那一部, 原地淡回来. [setText] 传 null = 藏起来
 * (滚动 / 连发中).
 *
 * 放大转场: 标题每次挪动都把它在 Compose 根坐标里的框登记给 [TvHeroZoomHandoff.publishTitle] (不含缩回让位平移与进场滑入), 缩回期间
 * 由 [setTitleHandoff] 按转场层的判定隐藏或平移, 并停掉跑马灯.
 */
@SuppressLint("ViewConstructor")
class TvNativeHeroTextView(
    context: Context,
    style: TvNativeHeroTextStyle,
) : TvNativeBoundaryLayout(context) {
    var style: TvNativeHeroTextStyle = style
        set(value) {
            if (field == value) return
            field = value
            applyStyle()
        }

    var titleWidthPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var summaryWidthPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Compose 根视图 (放大转场的框按它的坐标登记). */
    var composeRoot: View? = null

    /** 标题显示的条目变了 (页面据此观察放大转场的缩回, 见 [setTitleHandoff]). */
    var onShownSubjectChanged: ((Int?) -> Unit)? = null

    private val title = TvNativeTextView(context)
    private val metaRow = LinearLayout(context)
    private val star = ImageView(context)
    private val rating = TvNativeTextView(context)
    private val meta = TvNativeTextView(context)
    private val status = TvNativeStatusRow(context)
    private val summary = TvNativeTextView(context)
    private val lines: List<View> = listOf(title, metaRow, status, summary)

    private var shown: TvNativeHeroText? = null
    private var pending: TvNativeHeroText? = null
    private var hasPending = false
    private var pendingTransition = TvNativeTextTransition.Key
    private var changedAt = 0L
    private var fadeOut: ValueAnimator? = null
    private var fadeBack: ValueAnimator? = null
    private val lineAnimators = ArrayList<ValueAnimator>()
    private var blockAlpha = 1f

    private val titleOwner = Any()
    private var titleMarquee = false
    private var titleSettling = false
    private var titleHidden = false
    private var titleOffsetX = 0f
    private var titleOffsetY = 0f
    private val xy = IntArray(2)
    private val rootXY = IntArray(2)

    init {
        clipChildren = false
        clipToPadding = false
        metaRow.orientation = LinearLayout.HORIZONTAL
        metaRow.gravity = Gravity.CENTER_VERTICAL
        star.scaleType = ImageView.ScaleType.FIT_CENTER
        metaRow.addView(star)
        metaRow.addView(rating)
        metaRow.addView(meta)
        rating.maxLines = 1
        meta.setSingleLine(true)
        meta.ellipsize = TextUtils.TruncateAt.END
        summary.ellipsize = TextUtils.TruncateAt.END
        for (line in lines) addView(line)
        applyStyle()
        bindContent(null)
    }

    private fun applyStyle() {
        val s = style
        s.title.applyTo(title)
        if (s.titleMaxLines == 1) {
            // 定宽一行, 放不下跑马灯滚全文 (不省略号); 不跑时硬裁 (登记给放大转场的 clipOverflow 跟着这里, 转场标题同样硬裁)
            title.setSingleLine(true)
            title.setHorizontallyScrolling(true)
        } else {
            title.setSingleLine(false)
            title.setHorizontallyScrolling(false)
            title.maxLines = s.titleMaxLines
        }
        title.marqueeRepeatLimit = s.marqueeRepeat
        s.rating.applyTo(rating)
        s.meta.applyTo(meta)
        s.summary.applyTo(summary)
        status.applyStyle(s.status, s.marqueeRepeat)
        star.setImageBitmap(s.star)
        star.layoutParams = LinearLayout.LayoutParams(s.starSizePx, s.starSizePx).apply { marginEnd = s.starGapPx }
        rating.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = s.metaGapPx }
        meta.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        updateTitleMarquee()
        requestLayout()
    }

    /** 此刻显示的条目 (淡出中的也算), null = 空. */
    val shownSubjectId: Int? get() = shown?.subjectId

    /** 标题这一行的高度 (px), 页面据此算标题什么时候移出屏幕上缘. */
    val titleHeight: Int get() = title.height

    /** 换内容. 条目变了 (或从无到有 / 藏起来) 按 [transition] 过渡; 同一条目只是内容变了原地换. */
    fun setText(text: TvNativeHeroText?, transition: TvNativeTextTransition) {
        val target = if (hasPending) pending else shown
        if (text == target) return
        val current = shown
        if (!style.animated || transition == TvNativeTextTransition.Reset) {
            cancelAnimations()
            hasPending = false
            pending = null
            blockAlpha = 1f
            alpha = 1f
            bindContent(text)
            for (line in lines) {
                line.translationX = 0f
                line.alpha = 1f
            }
            applyTitleOffset()
            return
        }
        if (text != null && current != null && text.subjectId == current.subjectId) {
            hasPending = false
            pending = null
            bindContent(text)
            if (fadeOut != null) {
                // 淡出途中目标又回到这一部 (藏起来又马上放出来): 原地淡回来, 不走一遍淡出再滑入
                fadeOut?.cancel()
                fadeOut = null
                animateBlockBack()
            }
            return
        }
        changedAt = SystemClock.uptimeMillis()
        hasPending = true
        pending = text
        pendingTransition = transition
        if (current == null) {
            // 从空 / 隐藏态出来: 直接进场
            cancelAnimations()
            blockAlpha = 1f
            alpha = 1f
            enter(sequential = false)
            return
        }
        // 旧字整块原地淡出 (线性), 淡完换新字再按行进场; 正在淡出就接着淡, 淡完用最新的目标
        fadeBack?.cancel()
        fadeBack = null
        if (fadeOut != null) return
        cancelLineAnimators()
        val outMillis = when {
            text == null -> TV_NATIVE_TEXT_HIDE_OUT_MILLIS
            transition == TvNativeTextTransition.Carousel -> TV_NATIVE_CAROUSEL_TEXT_OUT_MILLIS
            else -> TV_NATIVE_TEXT_OUT_MILLIS
        }
        val from = blockAlpha
        fadeOut = ValueAnimator.ofFloat(from, 0f).apply {
            duration = (outMillis * from).toLong().coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                blockAlpha = it.animatedValue as Float
                alpha = blockAlpha
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    fadeOut = null
                    enter(sequential = true)
                }
            })
            start()
        }
    }

    private fun animateBlockBack() {
        val from = blockAlpha
        fadeBack = ValueAnimator.ofFloat(from, 1f).apply {
            duration = (TV_NATIVE_TEXT_IN_MILLIS * (1f - from)).toLong().coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                blockAlpha = it.animatedValue as Float
                alpha = blockAlpha
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fadeBack = null
                }
            })
            start()
        }
    }

    /** 换上待显示的目标并按行进场. [sequential] = 旧字刚淡完 (进场起点从换目标那一刻算, 见 [TvNativeTextTransition]). */
    private fun enter(sequential: Boolean) {
        val text = pending
        val transition = pendingTransition
        hasPending = false
        pending = null
        blockAlpha = 1f
        alpha = 1f
        bindContent(text)
        if (text == null) return
        val carousel = transition == TvNativeTextTransition.Carousel
        val base = when {
            carousel -> TV_NATIVE_CAROUSEL_TEXT_OUT_MILLIS
            sequential -> TV_NATIVE_TEXT_ENTER_AT_MILLIS
            else -> 0L
        }
        val elapsed = SystemClock.uptimeMillis() - changedAt
        val duration = if (carousel) TV_NATIVE_CAROUSEL_TEXT_IN_MILLIS else TV_NATIVE_TEXT_IN_MILLIS
        val s = style
        for ((i, line) in lines.withIndex()) {
            // 行号: 标题 0, 信息行 1, 下一集行与简介 2; 不错落时整块同进 (都按第 0 行)
            val lineIndex = if (s.stagger) minOf(i, 2) else 0
            val delay = (base + lineIndex * TV_NATIVE_TEXT_STAGGER_MILLIS - elapsed).coerceAtLeast(0L)
            line.alpha = 0f
            line.translationX = s.slidePx.toFloat()
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                startDelay = delay
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    line.alpha = if (line === title && titleHidden) 0f else f
                    line.translationX = s.slidePx * (1f - TV_NATIVE_LINEAR_OUT_SLOW_IN.getInterpolation(f))
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        lineAnimators.remove(animation)
                        if (line === title) applyTitleOffset()
                    }
                })
            }
            lineAnimators.add(animator)
            animator.start()
        }
    }

    private fun cancelAnimations() {
        fadeOut?.cancel()
        fadeOut = null
        fadeBack?.cancel()
        fadeBack = null
        cancelLineAnimators()
    }

    private fun cancelLineAnimators() {
        // cancel 当场回调 onAnimationEnd, 那里会把自己从列表里摘掉: 先拷一份再逐个取消
        lineAnimators.toList().forEach { it.cancel() }
        lineAnimators.clear()
    }

    override fun hasOverlappingRendering(): Boolean = false

    private fun bindContent(text: TvNativeHeroText?) {
        val old = shown
        shown = text
        if (old?.subjectId != text?.subjectId) onShownSubjectChanged?.invoke(text?.subjectId)
        if (text == null) {
            for (line in lines) line.visibility = GONE
            if (old != null) TvHeroZoomHandoff.retractTitle(titleOwner)
            return
        }
        title.visibility = VISIBLE
        title.text = text.title
        contentDescription = text.title
        val info = text.infoReady
        val hasRating = text.rating != null
        metaRow.visibility = if (info && (hasRating || text.meta.isNotEmpty())) VISIBLE else GONE
        star.visibility = if (hasRating) VISIBLE else GONE
        rating.visibility = if (hasRating) VISIBLE else GONE
        rating.text = text.rating?.let { "$it/10" }
        meta.text = spansOf(text.meta)
        meta.visibility = if (text.meta.isEmpty()) GONE else VISIBLE
        val st = text.status
        status.visibility = if (info && st != null) VISIBLE else GONE
        if (st != null) status.bind(st)
        summary.visibility = if (info) VISIBLE else GONE
        summary.text = text.summary
        if (old?.subjectId != text.subjectId) {
            titleHidden = false
            titleOffsetX = 0f
            titleOffsetY = 0f
        }
        updateTitleMarquee()
        requestLayout()
    }

    private fun spansOf(spans: List<TvNativeTextSpan>): CharSequence {
        if (spans.isEmpty()) return ""
        val b = SpannableStringBuilder()
        for (span in spans) {
            val start = b.length
            b.append(span.text)
            b.setSpan(ForegroundColorSpan(span.color), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    // ---- 放大转场的标题 ----

    /**
     * 标题要不要跑马灯 (调用方判: 探索页轮播标题随文字块移出屏幕上缘就停, 见 TvNativeExploreView). 缩回期间
     * ([setTitleHandoff] 的 settling) 一律不跑: 停掉即回到行首, 否则标题正滚到中间被拉去平移, 落位那一刻跑马灯重新开始又跳回行首,
     * 看着闪一下.
     */
    fun setTitleMarquee(enabled: Boolean) {
        if (titleMarquee == enabled) return
        titleMarquee = enabled
        updateTitleMarquee()
    }

    /**
     * 缩回转场期间的标题: [hidden] = 绘制权在转场层 (TvHeroZoomHandoff.titleOwnedByOverlay), [offsetX] / [offsetY] = 让位平移
     * (shrinkTitleOffset, 退路), [settling] = 缩回中 (停跑马灯, titleSettling). 由页面按当前条目观察 TvHeroZoomHandoff 后调.
     */
    fun setTitleHandoff(hidden: Boolean, offsetX: Float, offsetY: Float, settling: Boolean) {
        titleHidden = hidden
        titleOffsetX = offsetX
        titleOffsetY = offsetY
        if (titleSettling != settling) {
            titleSettling = settling
            updateTitleMarquee()
        }
        applyTitleOffset()
    }

    private fun applyTitleOffset() {
        if (lineAnimators.isNotEmpty()) {
            // 进场滑入中: 位移与透明度归进场动画管, 只处理隐藏
            if (titleHidden) title.alpha = 0f
            return
        }
        title.alpha = if (titleHidden) 0f else 1f
        title.translationX = titleOffsetX
        title.translationY = titleOffsetY
    }

    private fun updateTitleMarquee() {
        val oneLine = style.titleMaxLines == 1
        val run = oneLine && titleMarquee && !titleSettling && style.marqueeRepeat != 0
        title.ellipsize = when {
            run -> TextUtils.TruncateAt.MARQUEE
            oneLine -> null
            else -> TextUtils.TruncateAt.END
        }
        title.isSelected = run
    }

    /**
     * 登记标题此刻在 Compose 根坐标里的框 (不含缩回让位平移与进场滑入: 详情页按登记的框算平移起点, 框里算上平移就被自己的平移改写, 自激).
     * 本块或祖先挪动 / 缩放后调 (页面在滚动联动里调, 布局完成时自己调).
     */
    fun publishTitle() {
        val text = shown ?: return
        if (title.visibility != VISIBLE || title.width == 0 || !isAttachedToWindow) return
        getLocationInWindow(xy)
        val root = composeRoot
        if (root != null) {
            root.getLocationInWindow(rootXY)
        } else {
            rootXY[0] = 0
            rootXY[1] = 0
        }
        val left = xy[0] - rootXY[0] + title.left * scaleX
        val top = xy[1] - rootXY[1] + title.top * scaleY
        TvHeroZoomHandoff.publishTitle(
            titleOwner, text.subjectId, Rect(left, top, left + title.width * scaleX, top + title.height * scaleY), text.title,
            maxLines = style.titleMaxLines, clipOverflow = style.titleMaxLines == 1,
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        TvHeroZoomHandoff.retractTitle(titleOwner)
    }

    // ---- 排版 ----

    override fun measureContent(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val s = style
        val titleW = (if (titleWidthPx > 0) titleWidthPx else width).coerceAtMost(width)
        val summaryW = (if (summaryWidthPx > 0) summaryWidthPx else width).coerceAtMost(width)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var used = 0
        var count = 0
        fun gap() = if (count > 0) s.lineSpacingPx else 0
        if (title.visibility != GONE) {
            title.measure(MeasureSpec.makeMeasureSpec(titleW, MeasureSpec.EXACTLY), unspecified)
            used += gap() + title.measuredHeight
            count++
        }
        if (metaRow.visibility != GONE) {
            metaRow.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), unspecified)
            used += gap() + metaRow.measuredHeight
            count++
        }
        if (status.visibility != GONE) {
            val h = if (s.statusHeightPx > 0) MeasureSpec.makeMeasureSpec(s.statusHeightPx, MeasureSpec.EXACTLY) else unspecified
            status.measure(MeasureSpec.makeMeasureSpec(summaryW, MeasureSpec.EXACTLY), h)
            used += gap() + status.measuredHeight
            count++
        }
        if (summary.visibility != GONE) {
            // 简介占满剩余高度: 放得下几行就几行, 最后一行省略号
            val remaining = (height - used - gap()).coerceAtLeast(0)
            val lineHeight = max(1, s.summary.lineHeightPx.takeIf { it > 0 } ?: summary.lineHeight)
            val maxLines = remaining / lineHeight
            if (summary.maxLines != maxLines) summary.maxLines = maxLines
            summary.measure(
                MeasureSpec.makeMeasureSpec(summaryW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxLines * lineHeight, MeasureSpec.AT_MOST),
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun layoutContent(width: Int, height: Int) {
        var y = 0
        var count = 0
        for (line in lines) {
            if (line.visibility == GONE) continue
            if (count > 0) y += style.lineSpacingPx
            line.layout(0, y, line.measuredWidth, y + line.measuredHeight)
            y += line.measuredHeight
            count++
        }
        publishTitle()
    }
}

/**
 * 下一集那一行: 集号 → 集名 (占剩下的宽度, 放不下跑马灯) → 尾段, 集号与尾段永不截断.
 */
@SuppressLint("ViewConstructor")
private class TvNativeStatusRow(context: Context) : ViewGroup(context) {
    private val lead = TvNativeTextView(context)
    private val name = TvNativeTextView(context)
    private val tail = TvNativeTextView(context)
    private val parts = listOf(lead, name, tail)

    init {
        for (v in parts) {
            v.setSingleLine(true)
            v.setHorizontallyScrolling(true)
            v.ellipsize = null
            addView(v)
        }
    }

    fun applyStyle(style: TvNativeTextStyle, marqueeRepeat: Int) {
        for (v in parts) style.applyTo(v)
        name.marqueeRepeatLimit = marqueeRepeat
        name.ellipsize = if (marqueeRepeat != 0) TextUtils.TruncateAt.MARQUEE else null
        name.isSelected = marqueeRepeat != 0
    }

    fun bind(status: TvNativeHeroStatus) {
        lead.text = status.lead
        name.text = status.name?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        name.visibility = if (status.name.isNullOrBlank()) GONE else VISIBLE
        tail.text = status.tail.orEmpty()
        tail.visibility = if (status.tail.isNullOrEmpty()) GONE else VISIBLE
        for (v in parts) v.setTextColor(status.color)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        lead.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), unspecified)
        var left = (width - lead.measuredWidth).coerceAtLeast(0)
        if (tail.visibility != GONE) {
            tail.measure(MeasureSpec.makeMeasureSpec(left, MeasureSpec.AT_MOST), unspecified)
            left = (left - tail.measuredWidth).coerceAtLeast(0)
        }
        if (name.visibility != GONE) {
            name.measure(unspecified, unspecified)
            val w = name.measuredWidth.coerceAtMost(left)
            name.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), unspecified)
        }
        var contentHeight = 0
        for (v in parts) if (v.visibility != GONE) contentHeight = maxOf(contentHeight, v.measuredHeight)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else contentHeight
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t
        var x = 0
        for (v in parts) {
            if (v.visibility == GONE) continue
            val y = (h - v.measuredHeight) / 2
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
            x += v.measuredWidth
        }
    }

    override fun hasOverlappingRendering(): Boolean = false
}

// 时长与 Compose 页面的 hero 文字过渡同一份 (TvScrollActivity.kt: tvScrollHiddenTextTransform / tvCarouselTextTransform / tvHeroLineEnter)
private const val TV_NATIVE_TEXT_OUT_MILLIS = TV_SCROLL_HIDDEN_TEXT_OUT_MILLIS.toLong()
private const val TV_NATIVE_TEXT_HIDE_OUT_MILLIS = TV_SCROLL_HIDDEN_TEXT_HIDE_OUT_MILLIS.toLong()
private const val TV_NATIVE_TEXT_IN_MILLIS = TV_SCROLL_HIDDEN_TEXT_IN_MILLIS.toLong()
private const val TV_NATIVE_TEXT_ENTER_AT_MILLIS = TV_SCROLL_HIDDEN_TEXT_ENTER_AT_MILLIS
private const val TV_NATIVE_CAROUSEL_TEXT_OUT_MILLIS = TV_CAROUSEL_TEXT_OUT_MILLIS.toLong()
private const val TV_NATIVE_CAROUSEL_TEXT_IN_MILLIS = TV_CAROUSEL_TEXT_IN_MILLIS.toLong()
private const val TV_NATIVE_TEXT_STAGGER_MILLIS = TV_HERO_TEXT_STAGGER_MILLIS.toLong()
