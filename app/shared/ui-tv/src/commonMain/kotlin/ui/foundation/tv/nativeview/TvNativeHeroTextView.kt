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
import android.graphics.RectF
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
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.Job
import me.him188.ani.app.data.models.preference.TvTitleLogoDisplay
import me.him188.ani.app.data.network.TmdbTitleLogo
import me.him188.ani.app.ui.foundation.TvNativeImages
import me.him188.ani.app.ui.foundation.tv.TV_CAROUSEL_TEXT_IN_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_CAROUSEL_TEXT_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_TEXT_STAGGER_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_ENTER_AT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_HIDE_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_IN_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvHeroTitleLogoLook
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoBackdropLook
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoBitmaps
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoBox
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoFlip
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoGlow
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoGrid
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoGrids
import me.him188.ani.app.ui.foundation.tv.TvTitleLogoUnreadable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** hero 标题 logo 背后铺的整屏模糊背景 (见 [TvNativeHeroTextView.logoBackdrop]). */
fun interface TvNativeLogoBackdrop {
    /** 条目 [subjectId] 的背景此刻在窗口坐标 [rect] 处的取色器 (主线程上调, 取色可以放到后台); 还没铺上这一部的图时 null. */
    fun sampler(subjectId: Int, rect: RectF): TvBackdropSampler?
}

/** 一段带颜色的字 (hero 信息行里的开播状态、总集数、标签等). */
@Immutable
data class TvNativeTextSpan(val text: String, val color: Int)

/**
 * hero 信息块里的「下一集」一行 (探索页继续观看 / 追番页): [lead] 集号 (不截断), [name] 集名 (放不下跑马灯), [tail] 尾段 (剩余分钟 /
 * 已看完, 不截断). 三段同色 [color]. [name] 前面接 [nameSeparator] (探索页「更多」卡那一行是一句话里嵌着名字, 不要分隔).
 * [wrap] = 整行就是 [lead] 一段字, 放不下就折行 (不用 [name] / [tail]).
 */
@Immutable
data class TvNativeHeroStatus(
    val lead: String,
    val name: String?,
    val tail: String?,
    val color: Int,
    val nameSeparator: String = " · ",
    val wrap: Boolean = false,
)

/**
 * hero 文字块要显示的内容. 字符串由页面在 Compose 里按字符串资源排好, 原生只管排版与过渡.
 *
 * @param rating 评分数字 (如 "8.7"), 画成「★ 8.7/10」; null 不画.
 * @param meta 评分之后的那一串 (开播状态 · 总集数 / 标签 / 开播年月), 按段着色.
 * @param infoReady 条目信息到了: 信息行、下一集行、简介才出现 (没到时只有标题).
 * @param summaryMaxLines 简介最多几行 (0 = 占满剩余高度).
 * @param vibrant 压在 hero 态铺着的模糊背景上 (深色主题, 由页面按状态给): 信息行 / 下一集行 / 简介 / 评分数字照 tvOS 的 vibrancy 画
 *   (见 setTvVibrancy) —— 次要色的字与简介换成次要那一档 ([tvVibrancySecondary])、评分数字换成主要文字色, 都以加法混合画在背景上;
 *   标题与评分旁边的星照常.
 * @param logo 标题 logo (见 ThemeSettings.tvTitleLogoDisplay), 样式里有 logo 框 ([TvNativeHeroTextStyle.logoBox]) 时用; null = 文字标题.
 * @param logoPending 还不知道有没有 [logo] (TMDB 还没查完): 换到这一部时进场先等一会儿 (见 [TvNativeHeroTextView]).
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
    val summaryMaxLines: Int = 0,
    val vibrant: Boolean = false,
    val logo: TmdbTitleLogo? = null,
    val logoPending: Boolean = false,
)

/**
 * hero 文字块的排版参数 (px), 由 Compose 按主题、界面缩放与视觉效果档位算好.
 *
 * @param lineSpacingPx 标题 / 信息行 / 下一集行 / 简介之间的间距 (探索页 10dp, 追番页 8dp).
 * @param statusHeightPx 下一集行的定高 (0 = 按内容高).
 * @param marqueeRepeat 标题 (与下一集集名) 跑马灯的圈数: 0 = 不跑 (视觉效果流畅档), -1 = 一直跑.
 * @param stagger 分行错落进场 (tvHeroTextStaggerEnabled); [animated] = false (流畅档) 时换字不过渡.
 * @param openTitle 详情页大标题的样子, 整屏背景点开时标题渐变成它 (见 [TvNativeHeroTextView.titleLook]); null = 与 [title] 同一个样子 (深色主题).
 * @param logoBox 标题 logo 的大小规则 (宽度另不超过标题宽); null = 不用 logo (设置里关了).
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
    val openTitle: TvNativeTitleLook? = null,
    val logoBox: TvTitleLogoBox? = null,
    /** 标题 logo 看不清时怎么办 (见 ThemeSettings.tvTitleLogoDisplay); 关掉 logo 时 [logoBox] 为 null. */
    val logoDisplay: TvTitleLogoDisplay = TvTitleLogoDisplay.Auto,
)

/** 标题的颜色与阴影 (px), 见 [TvNativeHeroTextStyle.openTitle]. */
@Immutable
data class TvNativeTitleLook(
    val color: Int,
    val shadowColor: Int,
    val shadowDyPx: Float,
    val shadowRadiusPx: Float,
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
 *
 * 标题那一行可以是标题 logo ([TvNativeHeroText.logo], 样式里有 [TvNativeHeroTextStyle.logoBox] 时): 那一行高 = logo 框的高度上限, logo 底对齐、
 * 左对齐, 下面几行跟着往下排 (简介随之少几行). 放大转场登记的是 logo 的框. 不让文字标题先出来再被 logo 换掉 (换的时候下面几行还会跳):
 *  - 知道有 logo 就按 logo 排版, 图解好才显示; 图迟迟不来 ([TV_TITLE_LOGO_IMAGE_WAIT_MILLIS]) 才在 logo 的位置上先放文字标题 (底对齐),
 *    图到了原地换, 下面几行不动; 图加载失败换回文字标题的排版.
 *  - 新内容带着 logo 时 ([setText]), 旧字淡出的同时就把图解进内存 ([TvNativeImages.preloadLogo]), 进场时当场上屏.
 *  - 还不知道有没有 logo ([TvNativeHeroText.logoPending]) 时进场先等查完, 最多 [TV_TITLE_LOGO_PENDING_WAIT_MILLIS]; 到点还没查完就照文字标题进场.
 *
 * logo 的样子: 压在纯色底上时与字同底看不清的翻色 ([TvTitleLogoFlip]: 只翻黑白灰的部分, 彩色原样), 底按标题的字色判; 浅色主题下列表页是
 * 深色字、详情页是白字 ([titleLook]): 两种样子各解一张, 跟着标题交叉淡化, 放大转场把列表页的样子交给详情页. 压在整屏模糊背景上时
 * ([logoBackdrop]) 默认原样, 按正下方的背景判看不清才翻色或在背后加柔光 (见 TvTitleLogoContrast); 详情页按自己的清晰背景图另判
 * (TvTitleLogoDetailsLooks), 放大转场把这边的样子交过去交叉淡化. 判出原样看不清的记进 [TvTitleLogoUnreadable]. 设置 ([TvNativeHeroTextStyle.logoDisplay])
 * 为「不调色」时原图、不判; 「看不清时显示文字」时也不调色, 判出看不清就照加载失败那样换回文字标题.
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

    /** 加载标题 logo 用 (见 [TvNativeHeroText.logo]); null = 一律文字标题. */
    var sketch: Sketch? = null

    /** 标题显示的条目变了 (页面据此观察放大转场的缩回, 见 [setTitleHandoff]). */
    var onShownSubjectChanged: ((Int?) -> Unit)? = null

    /**
     * 跑马灯 (标题、下一集集名) 暂停: 页面不在前台 (被放大进来的详情页盖着, 视图仍附着) 时由页面置位. 跑马灯按选中态跑、不看焦点,
     * 不停的话盖着的这几行每帧失效, 整个窗口跟着逐帧重画. 只收回选中态: 文字回到行首, 省略方式不变 (不重新排版);
     * 回前台照常先停一拍再滚.
     */
    var marqueePaused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateTitleMarquee()
            status.setMarqueePaused(value)
        }

    private val title = TvNativeTextView(context)
    private val logo = ImageView(context)

    /** logo 在详情页那种样子 (白字底) 的那一张: 与 [logo] 的样子不同时按 [titleLook] 叠在它上面淡入. */
    private val logoOpen = ImageView(context)

    /** logo 背后的柔光 (见 [TvTitleLogoGlow]), 画在 logo 下面, 跟着标题那一行走. */
    private val logoGlow = ImageView(context)
    private val titleSlot = TvNativeTitleSlot(context, title, logo, logoOpen, logoGlow)
    private val metaRow = LinearLayout(context)
    private val star = ImageView(context)
    private val rating = TvNativeTextView(context)
    private val meta = TvNativeTextView(context)
    private val status = TvNativeStatusRow(context)
    private val summary = TvNativeTextView(context)
    private val lines: List<View> = listOf(titleSlot, metaRow, status, summary)

    /** 各行自己的透明度 (进场滑入 / 重建写的那份), 标题以外的几行画的时候再乘 [detailAlpha]. */
    private val lineAlpha = FloatArray(lines.size) { 1f }

    /**
     * 标题以外几行 (信息行 / 下一集行 / 简介) 的整体透明度 (0..1): 整屏背景点开时跟着卡片淡没 (见 TvNativeWallFocus), 标题留着 ——
     * 进详情页时由详情页的标题从这里接着画 (放大转场的标题交接).
     */
    var detailAlpha: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            for ((i, line) in lines.withIndex()) if (line !== titleSlot) line.alpha = lineAlpha[i] * value
            applyLogoLook()
        }

    /**
     * 标题像详情页大标题的程度 (0 = [TvNativeHeroTextStyle.title] 的颜色、没有阴影, 1 = [TvNativeHeroTextStyle.openTitle]), 颜色与阴影浓度按它插值.
     * 浅色主题下列表页标题是黑字、详情页是白字压黑影: 整屏背景点开时跟着卡片淡没走 (见 TvNativeWallFocus), 背景对上焦时标题已是白字, 进详情页
     * 只平移不再变色 (登记给放大转场, 见 [publishTitle]); 回来倒放时变回去. [TvNativeHeroTextStyle.openTitle] 为 null 时不起作用.
     */
    var titleLook: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            applyTitleLook()
            publishTitle()
        }

    private fun applyTitleLook() {
        applyLogoLook()
        val look = style.openTitle
        val t = titleLook.coerceIn(0f, 1f)
        if (look == null || t == 0f) {
            title.setTextColor(style.title.color)
            title.setShadowLayer(0f, 0f, 0f, 0)
            return
        }
        title.setTextColor(lerpArgb(style.title.color, look.color, t))
        val shadowAlpha = ((look.shadowColor ushr 24) * t).roundToInt()
        title.setShadowLayer(look.shadowRadiusPx, 0f, look.shadowDyPx, (shadowAlpha shl 24) or (look.shadowColor and 0xFFFFFF))
    }

    /** 写第 [line] 行自己的透明度; 标题以外的乘上 [detailAlpha]. 标题的隐藏另见 [applyTitleOffset]. */
    private fun setLineAlpha(line: View, alpha: Float) {
        lineAlpha[lines.indexOf(line)] = alpha
        line.alpha = if (line === titleSlot) alpha else alpha * detailAlpha
    }

    private var shown: TvNativeHeroText? = null
    private var pending: TvNativeHeroText? = null
    private var hasPending = false
    private var pendingTransition = TvNativeTextTransition.Key
    private var changedAt = 0L
    private var fadeOut: ValueAnimator? = null
    private var fadeBack: ValueAnimator? = null
    private val lineAnimators = ArrayList<ValueAnimator>()

    /** 标题那一行的进场滑入 (见 [enter]), 没在跑为 null: 在跑时标题的透明度与位移归它管, 见 [applyTitleOffset]. */
    private var titleEnter: ValueAnimator? = null
    private var blockAlpha = 1f

    /** 进场在等待显示的那一部查完 logo (见 [enter]); [enterDeferredSequential] = 等完按哪种进场. */
    private var enterDeferred = false
    private var enterDeferredSequential = false
    private val enterDeferredTimeout = Runnable { resumeDeferredEnter(waitLogo = false) }

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
        // logo 的框按它的宽高比定好了 (见 TvNativeTitleSlot), 铺满即可
        for (view in listOf(logo, logoOpen)) {
            view.scaleType = ImageView.ScaleType.FIT_XY
            view.visibility = INVISIBLE
        }
        // 柔光是一张几十像素的小图, 拉伸 (双线性) 成平滑的一团
        logoGlow.scaleType = ImageView.ScaleType.FIT_XY
        logoGlow.visibility = GONE
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
        applyTitleLook()
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
        // 样式里的颜色刚盖掉了显示中那一条的 vibrancy 配色, 按它重新上色
        shown?.let { bindContent(it) }
        requestLayout()
    }

    /** 此刻显示的条目 (淡出中的也算), null = 空. */
    val shownSubjectId: Int? get() = shown?.subjectId

    /** 标题这一行的高度 (px), 页面据此算标题什么时候移出屏幕上缘. */
    val titleHeight: Int get() = titleSlot.height

    /** 换内容. 条目变了 (或从无到有 / 藏起来) 按 [transition] 过渡; 同一条目只是内容变了原地换. */
    fun setText(text: TvNativeHeroText?, transition: TvNativeTextTransition) {
        val target = if (hasPending) pending else shown
        if (text == target) return
        preloadLogo(text?.logo)
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
                setLineAlpha(line, 1f)
            }
            applyTitleOffset()
            return
        }
        if (enterDeferred) {
            // 进场正等着 logo 查完 (旧字已淡完): 同一部只是内容变了就换上, 查完了马上进场; 换了一部 (或藏起来) 就从此刻进场那一部
            val samePending = text != null && text.subjectId == pending?.subjectId
            pending = text
            if (!samePending) pendingTransition = transition
            if (!samePending || !text.logoPending) resumeDeferredEnter(waitLogo = true)
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

    /**
     * 换上待显示的目标并按行进场. [sequential] = 旧字刚淡完 (进场起点从换目标那一刻算, 见 [TvNativeTextTransition]).
     * 待显示的那一部还不知道有没有 logo 时先等它查完 (见 [TvNativeHeroText.logoPending]): 旧字已经淡没, 这段时间整块空着.
     */
    private fun enter(sequential: Boolean) {
        val text = pending
        if (text != null && text.logoPending && style.logoBox != null && sketch != null) {
            enterDeferred = true
            enterDeferredSequential = sequential
            removeCallbacks(enterDeferredTimeout)
            postDelayed(enterDeferredTimeout, TV_TITLE_LOGO_PENDING_WAIT_MILLIS)
            return
        }
        enterNow(sequential)
    }

    /**
     * 不再等 logo: 进场时刻挪到此刻 (各行错落照旧). [waitLogo] = 待显示的那一部 (换过的) 还没查完时接着等; 等到点了传 false, 照文字标题进场.
     */
    private fun resumeDeferredEnter(waitLogo: Boolean) {
        removeCallbacks(enterDeferredTimeout)
        enterDeferred = false
        val sequential = enterDeferredSequential
        changedAt = SystemClock.uptimeMillis() - enterBaseMillis(pendingTransition, sequential)
        if (waitLogo) enter(sequential) else enterNow(sequential)
    }

    /** 进场第一行 (标题) 从换目标那一刻起多久开始 (见 [enterNow]). */
    private fun enterBaseMillis(transition: TvNativeTextTransition, sequential: Boolean): Long = when {
        transition == TvNativeTextTransition.Carousel -> TV_NATIVE_CAROUSEL_TEXT_OUT_MILLIS
        sequential -> TV_NATIVE_TEXT_ENTER_AT_MILLIS
        else -> 0L
    }

    private fun enterNow(sequential: Boolean) {
        val text = pending
        val transition = pendingTransition
        hasPending = false
        pending = null
        blockAlpha = 1f
        alpha = 1f
        bindContent(text)
        if (text == null) return
        val carousel = transition == TvNativeTextTransition.Carousel
        val base = enterBaseMillis(transition, sequential)
        val elapsed = SystemClock.uptimeMillis() - changedAt
        val duration = if (carousel) TV_NATIVE_CAROUSEL_TEXT_IN_MILLIS else TV_NATIVE_TEXT_IN_MILLIS
        val s = style
        for ((i, line) in lines.withIndex()) {
            // 行号: 标题 0, 信息行 1, 下一集行与简介 2; 不错落时整块同进 (都按第 0 行)
            val lineIndex = if (s.stagger) minOf(i, 2) else 0
            val delay = (base + lineIndex * TV_NATIVE_TEXT_STAGGER_MILLIS - elapsed).coerceAtLeast(0L)
            setLineAlpha(line, 0f)
            line.translationX = s.slidePx.toFloat()
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                startDelay = delay
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    setLineAlpha(line, if (line === titleSlot && titleHidden) 0f else f)
                    line.translationX = s.slidePx * (1f - TV_NATIVE_LINEAR_OUT_SLOW_IN.getInterpolation(f))
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        lineAnimators.remove(animation)
                        if (line === titleSlot) {
                            titleEnter = null
                            // 被取消的 (换字 / 整块重建) 由取消方接着摆
                            if (!cancelled) applyTitleOffset()
                        }
                    }
                })
            }
            if (line === titleSlot) titleEnter = animator
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
        if (enterDeferred) {
            enterDeferred = false
            removeCallbacks(enterDeferredTimeout)
        }
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
        titleSlot.visibility = VISIBLE
        title.text = text.title
        bindLogo(text.logo)
        contentDescription = text.title
        val info = text.infoReady
        val hasRating = text.rating != null
        metaRow.visibility = if (info && (hasRating || text.meta.isNotEmpty())) VISIBLE else GONE
        star.visibility = if (hasRating) VISIBLE else GONE
        rating.visibility = if (hasRating) VISIBLE else GONE
        rating.text = text.rating?.let { "$it/10" }
        // vibrancy: 次要色的字 (信息行里的开播状态 / 年月、探索页的下一集行) 与简介换成次要那一档, 其余颜色 (总集数、追番页的主色) 不换
        val vibrant = text.vibrant
        val secondary = tvVibrancySecondary(style.title.color)
        // 评分数字跟别的字一样走 vibrancy (颜色本来就是主要文字色, 见 rememberTvNativeHeroTextStyle)
        rating.setTvVibrancy(vibrant)
        meta.text = spansOf(text.meta) { if (vibrant && it == style.meta.color) secondary else it }
        meta.setTvVibrancy(vibrant)
        meta.visibility = if (text.meta.isEmpty()) GONE else VISIBLE
        val st = text.status
        status.visibility = if (info && st != null) VISIBLE else GONE
        if (st != null) status.bind(st, color = if (vibrant && st.color == style.status.color) secondary else st.color, vibrant = vibrant)
        summary.visibility = if (info) VISIBLE else GONE
        summary.text = text.summary
        summary.setTextColor(if (vibrant) secondary else style.summary.color)
        summary.setTvVibrancy(vibrant)
        if (old?.subjectId != text.subjectId) {
            titleHidden = false
            titleOffsetX = 0f
            titleOffsetY = 0f
        }
        updateTitleMarquee()
        requestLayout()
    }

    private inline fun spansOf(spans: List<TvNativeTextSpan>, colorOf: (Int) -> Int): CharSequence {
        if (spans.isEmpty()) return ""
        val b = SpannableStringBuilder()
        for (span in spans) {
            val start = b.length
            b.append(span.text)
            b.setSpan(ForegroundColorSpan(colorOf(span.color)), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    // ---- 标题 logo ----

    /**
     * 标题压在整屏模糊背景上 (开了「hero 态铺模糊背景」的页面给): logo 默认原样, 与正下方的背景看不清时才翻色或在背后加柔光 (见
     * TvTitleLogoContrast). null = 压在纯色底上, 按标题的字色翻色 ([TvTitleLogoFlip]).
     */
    var logoBackdrop: TvNativeLogoBackdrop? = null
        set(value) {
            if (field === value) return
            field = value
            bindLogo(shown?.logo)
        }

    /** [logoBackdrop] 上铺的图换了 (解好露面 / 压暗变了): logo 按新的背景重判. */
    fun logoBackdropChanged() {
        if (logoBackdrop != null && logoGrid != null) decideLogo(again = true)
    }

    /** 正在显示 / 加载的标题 logo; 样式里没有 logo 框或没有 [sketch] 时恒为 null. */
    private var logoTarget: TmdbTitleLogo? = null

    /** [logoTarget] 是按哪套规则加载的 (设置里换了档、换了主题、铺不铺模糊背景变了要重新加载). */
    private var logoLoadedFor: LogoLoad? = null

    /**
     * logo 怎么加载: [box] = 大小规则; [list] = 列表页样子的翻色 (纯色底上按标题的字色定; [backdrop] = 压在模糊背景上时为 null, 按背景判,
     * 见 [logoLook]); [open] = 详情页样子 (白字底) 的翻色, 与列表页的样子可能不同时才有; [textWhenUnreadable] = 不调色, 判出看不清换回文字标题
     * (这时 [list] 只用来判: 真翻了就是看不清).
     */
    private data class LogoLoad(
        val box: TvTitleLogoBox,
        val list: TvTitleLogoFlip?,
        val open: TvTitleLogoFlip?,
        val backdrop: Boolean,
        val textWhenUnreadable: Boolean = false,
    )

    /** [logo] 上请求的样子: 翻色的 lightText, null = 原样. */
    private var logoListFlip: Boolean? = null

    /** [logoTarget] 列表页样子那张 ([logo]): null = 还没解好 (压在模糊背景上时还要判完); 值 = 翻过色. 解好才显示 logo. */
    private var logoListFlipped: Boolean? = null

    /** [logoTarget] 详情页样子那张 ([logoOpen]): null = 还没解好或用不着; 值 = 翻过色. */
    private var logoOpenFlipped: Boolean? = null

    /** 压在模糊背景上时 logo 的格子颜色 (原图解好后量, 见 TvTitleLogoGrids); null = 还没量完. */
    private var logoGrid: TvTitleLogoGrid? = null

    /** 压在模糊背景上时判出来的样子; null = 还没判. */
    private var logoLook: TvTitleLogoBackdropLook? = null

    /** 后台量 logo 的格子 / 按背景判的任务; [decideAgain] = 判的途中背景又换了, 判完再判一次. */
    private var logoMeasureJob: Job? = null
    private var logoDecideJob: Job? = null
    private var decideAgain = false

    /** 背景迟迟没铺上这一部的图: 先按原样显示, 铺上了再判 (见 [logoBackdropChanged]). */
    private val logoBackdropWaitTimeout = Runnable {
        if (logoTarget != null && logoLook == null) applyLogoBackdropLook(TvTitleLogoBackdropLook.Original)
    }

    /** [logoTarget] 的图加载失败: 换回文字标题的排版. */
    private var logoFailed = false

    /** [logoTarget] 的图等了 [TV_TITLE_LOGO_IMAGE_WAIT_MILLIS] 还没到: 先在 logo 的位置上放文字标题. */
    private var logoImageLate = false
    private val logoImageWaitTimeout = Runnable {
        if (logoReserved && logoListFlipped == null) {
            logoImageLate = true
            updateLogoVisibility()
            requestLayout()
        }
    }

    /** 标题那一行按 logo 排版 (知道有 logo、图没加载失败); 图解好之前 logo 还看不见. */
    private val logoReserved: Boolean get() = logoTarget != null && !logoFailed && style.logoBox != null

    /** 此刻显示的是 logo (图已解好). */
    private val logoActive: Boolean get() = logoReserved && logoListFlipped != null

    /** 最近一次提前解进内存的 logo (见 [preloadLogo]), 同一张不重复发. */
    private var preloadedLogo: Pair<String, LogoLoad>? = null

    /** 按此刻的样式与底, logo 怎么加载; 样式里没有 logo 框时 null. */
    private fun logoLoad(): LogoLoad? {
        val box = style.logoBox ?: return null
        val display = style.logoDisplay
        // 不调色: 原图, 不判
        if (display == TvTitleLogoDisplay.Original) return LogoLoad(box, list = null, open = null, backdrop = false)
        val textWhenUnreadable = display == TvTitleLogoDisplay.TextWhenUnreadable
        // 压在模糊背景上: 详情页按自己的背景另判, 这边不另解详情页的样子
        if (logoBackdrop != null) return LogoLoad(box, list = null, open = null, backdrop = true, textWhenUnreadable = textWhenUnreadable)
        return LogoLoad(
            box,
            list = TvTitleLogoFlip(lightText = isLightArgb(style.title.color)),
            open = if (textWhenUnreadable) null else style.openTitle?.let { TvTitleLogoFlip(lightText = isLightArgb(it.color)) },
            backdrop = false,
            textWhenUnreadable = textWhenUnreadable,
        )
    }

    /** 换了 logo (或加载规则变了) 重新加载; 图解好之前照旧显示文字标题. */
    private fun bindLogo(target: TmdbTitleLogo?) {
        val load = logoLoad()
        val sk = sketch
        val wanted = target?.takeIf { load != null && sk != null }
        if (wanted == logoTarget && load == logoLoadedFor) return
        logoTarget = wanted
        logoLoadedFor = load
        logoListFlipped = null
        logoOpenFlipped = null
        logoGrid = null
        logoLook = null
        logoMeasureJob?.cancel()
        logoDecideJob?.cancel()
        decideAgain = false
        logoFailed = false
        logoImageLate = false
        removeCallbacks(logoImageWaitTimeout)
        removeCallbacks(logoBackdropWaitTimeout)
        setLogoGlow(null)
        updateLogoVisibility()
        if (wanted == null || load == null || sk == null) {
            TvNativeImages.clear(logo)
            TvNativeImages.clear(logoOpen)
            return
        }
        postDelayed(logoImageWaitTimeout, TV_TITLE_LOGO_IMAGE_WAIT_MILLIS)
        loadLogoList(load.list?.lightText)
        if (load.open != null && !load.backdrop) loadLogoOpen(load.open) else TvNativeImages.clear(logoOpen)
    }

    /**
     * 往 [logo] 上加载列表页的样子 [flip] (翻色的 lightText, null = 原样). 压在模糊背景上时先加载原图, 顺带量格子颜色、按背景判 (见 [decideLogo]),
     * 判完才显示; 判出来要翻色再加载一次翻过的 (之前显示着的照旧显示, 解好原地换).
     */
    private fun loadLogoList(flip: Boolean?) {
        val wanted = logoTarget ?: return
        val load = logoLoadedFor ?: return
        val sk = sketch ?: return
        val size = load.box.sizeOf(wanted.aspectRatio)
        logoListFlip = flip
        TvNativeImages.loadLogo(
            sk, logo, wanted.url(size.widthPx), size.widthPx, size.heightPx, flip?.let { TvTitleLogoFlip(lightText = it, force = load.backdrop) },
            onSuccess = { flipped, bitmap ->
                // 详情页那边接手画同一张时从这里同步取, 不空帧 (见 TvTitleLogoBitmaps)
                bitmap?.let { TvTitleLogoBitmaps.putDecoded(wanted, flip, flipped, it, force = load.backdrop) }
                if (logoTarget == wanted && logoListFlip == flip) onLogoListLoaded(wanted, flipped, bitmap)
            },
            onError = {
                if (logoTarget == wanted) {
                    removeCallbacks(logoImageWaitTimeout)
                    logoFailed = true
                    updateLogoVisibility()
                    requestLayout()
                }
            },
        )
    }

    private fun onLogoListLoaded(wanted: TmdbTitleLogo, flipped: Boolean, bitmap: Bitmap?) {
        val load = logoLoadedFor ?: return
        if (load.backdrop && logoLook == null) {
            // 压在模糊背景上: 原图解好了, 量完格子颜色、按背景判完才显示
            if (logoGrid != null || logoMeasureJob?.isActive == true) return
            val cached = TvTitleLogoGrids.peek(wanted)
            when {
                cached != null -> onLogoGridReady(cached)
                bitmap != null -> logoMeasureJob = TvTitleLogoGrids.measure(wanted, bitmap) { grid ->
                    if (logoTarget == wanted) onLogoGridReady(grid)
                }

                else -> applyLogoBackdropLook(TvTitleLogoBackdropLook.Original)
            }
            return
        }
        removeCallbacks(logoImageWaitTimeout)
        // 纯色底上按标题字色翻了色 = 原样看不清
        if (flipped && load.list != null) {
            TvTitleLogoUnreadable.mark(wanted)
            if (load.textWhenUnreadable) {
                showTextInsteadOfLogo()
                return
            }
        }
        logoListFlipped = flipped
        updateLogoVisibility()
        requestLayout()
    }

    private fun onLogoGridReady(grid: TvTitleLogoGrid) {
        logoGrid = grid
        postDelayed(logoBackdropWaitTimeout, TV_TITLE_LOGO_BACKDROP_WAIT_MILLIS)
        decideLogo(again = false)
    }

    /**
     * 按 logo 正下方的背景判样子 (后台判, 见 TvTitleLogoGrids.decide). 背景还没铺上这一部的图、或 logo 还没排好时先不判 (排好 / 铺上了再来;
     * 还没判过的到 [TV_TITLE_LOGO_BACKDROP_WAIT_MILLIS] 先按原样显示). 正在判时: [again] = 背景换了, 判完再判一次; 否则不重复判.
     */
    private fun decideLogo(again: Boolean) {
        val wanted = logoTarget ?: return
        val grid = logoGrid ?: return
        if (logoDecideJob?.isActive == true) {
            if (again) decideAgain = true
            return
        }
        val sampler = logoBackdropSampler() ?: return
        // 取色 (按 logo 的格子, 上万格) 放到后台与判断一起做, 主线程只记下位置
        logoDecideJob = TvTitleLogoGrids.decide(grid, { sampler.sample(grid.cols, grid.rows) }) { look ->
            if (logoTarget == wanted && logoGrid === grid) {
                applyLogoBackdropLook(look)
                if (decideAgain) {
                    decideAgain = false
                    // 这一次的任务还没收尾, 下一帧再判
                    post { decideLogo(again = false) }
                }
            }
        }
    }

    /** logo 此刻压着的那块背景的取色器 (见 [TvNativeLogoBackdrop.sampler]); 取不到时 null. */
    private fun logoBackdropSampler(): TvBackdropSampler? {
        val backdrop = logoBackdrop ?: return null
        val subjectId = shown?.subjectId ?: return null
        if (logo.width == 0 || logo.height == 0 || !isAttachedToWindow) return null
        logo.getLocationInWindow(xy)
        val rect = RectF(xy[0].toFloat(), xy[1].toFloat(), (xy[0] + logo.width).toFloat(), (xy[1] + logo.height).toFloat())
        return backdrop.sampler(subjectId, rect)
    }

    /**
     * 用上判出来的样子 [look]: 换翻色 (解好才换上)、换柔光; 与详情页的样子不同时把那一张也解好. 看不清 (不是原样) 时记进 [TvTitleLogoUnreadable];
     * 设置为「看不清时显示文字」时不调色, 换回文字标题.
     */
    private fun applyLogoBackdropLook(look: TvTitleLogoBackdropLook) {
        removeCallbacks(logoBackdropWaitTimeout)
        if (look == logoLook) return
        logoLook = look
        if (look != TvTitleLogoBackdropLook.Original) {
            logoTarget?.let { TvTitleLogoUnreadable.mark(it) }
            if (logoLoadedFor?.textWhenUnreadable == true) {
                showTextInsteadOfLogo()
                return
            }
        }
        setLogoGlow(look.glow)
        if (look.flipLightText != logoListFlip) {
            loadLogoList(look.flipLightText)
        } else if (logoListFlipped == null) {
            // logo 上已经是要的样子 (原图): 当场显示
            removeCallbacks(logoImageWaitTimeout)
            logoListFlipped = false
            requestLayout()
        }
        val open = logoLoadedFor?.open
        if (open != null && logoLooksDiffer()) {
            if (logoOpenFlipped == null) loadLogoOpen(open)
        } else {
            logoOpenFlipped = null
            TvNativeImages.clear(logoOpen)
        }
        updateLogoVisibility()
        publishTitle()
    }

    /**
     * logo 看不清、设置为「看不清时显示文字」: 照图片加载失败那样换回文字标题的排版 (这部的 logo 随后各处都当作没有, 见 [TvTitleLogoUnreadable]).
     */
    private fun showTextInsteadOfLogo() {
        removeCallbacks(logoImageWaitTimeout)
        removeCallbacks(logoBackdropWaitTimeout)
        setLogoGlow(null)
        logoFailed = true
        updateLogoVisibility()
        requestLayout()
    }

    /** 往 [logoOpen] 上加载详情页的样子. 没解好 (或失败) 时不叠, 只显示列表页那张. */
    private fun loadLogoOpen(open: TvTitleLogoFlip) {
        val wanted = logoTarget ?: return
        val load = logoLoadedFor ?: return
        val sk = sketch ?: return
        val size = load.box.sizeOf(wanted.aspectRatio)
        TvNativeImages.loadLogo(
            sk, logoOpen, wanted.url(size.widthPx), size.widthPx, size.heightPx, open,
            onSuccess = { flipped, bitmap ->
                bitmap?.let { TvTitleLogoBitmaps.putDecoded(wanted, open.lightText, flipped, it) }
                if (logoTarget == wanted) {
                    logoOpenFlipped = flipped
                    updateLogoVisibility()
                    publishTitle()
                }
            },
            onError = {},
        )
    }

    /** 柔光换成 [glow] (null = 撤掉): 白色 (或黑色) 的小图, 透明度就是柔光的浓度. */
    private fun setLogoGlow(glow: TvTitleLogoGlow?) {
        if (titleSlot.glow === glow) return
        titleSlot.glow = glow
        if (glow == null) {
            logoGlow.setImageDrawable(null)
        } else {
            val rgb = if (glow.lighten) 0xFFFFFF else 0
            val pixels = IntArray(glow.alpha.size) { ((glow.alpha[it].coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or rgb }
            logoGlow.setImageBitmap(Bitmap.createBitmap(pixels, glow.cols, glow.rows, Bitmap.Config.ARGB_8888))
        }
        titleSlot.requestLayout()
    }

    /** 新内容的 logo 提前解进内存 (见类文档), 进场时 [bindLogo] 当场命中. 正在显示的那张不必. */
    private fun preloadLogo(target: TmdbTitleLogo?) {
        val load = logoLoad() ?: return
        val sk = sketch ?: return
        if (target == null || target == logoTarget) return
        val size = load.box.sizeOf(target.aspectRatio)
        val url = target.url(size.widthPx)
        if (preloadedLogo == url to load) return
        preloadedLogo = url to load
        TvNativeImages.preloadLogo(sk, context, url, size.widthPx, size.heightPx, load.list)
        if (!load.backdrop) load.open?.let { TvNativeImages.preloadLogo(sk, context, url, size.widthPx, size.heightPx, it) }
    }

    /**
     * 列表页的样子与详情页的样子画出来不一样 (翻色的结果不同): 纯色底上按两张解出来的结果 (两种字色至多一种会翻), 都解好才知道.
     * 压在模糊背景上时详情页沿用这边的样子, 恒为 false.
     */
    private fun logoLooksDiffer(): Boolean {
        val load = logoLoadedFor ?: return false
        val open = load.open ?: return false
        val list = logoListFlipped ?: return false
        val opened = logoOpenFlipped ?: return false
        return (list || opened) && load.list?.lightText != open.lightText
    }

    /** 两种样子不同且都解好了: 按 [titleLook] 交叉淡化 [logo] 与 [logoOpen]. */
    private val logoCrossfade: Boolean
        get() = logoListFlipped != null && logoOpenFlipped != null && logoLooksDiffer()

    private fun updateLogoVisibility() {
        // 文字标题: 不按 logo 排版时, 或 logo 的图迟迟不来时 (放在 logo 的位置上)
        title.visibility = if (!logoReserved || (logoListFlipped == null && logoImageLate)) VISIBLE else GONE
        // 没显示时留在树上 (INVISIBLE): 加载请求挂在它上面, View 不在窗口上请求会挂起
        logo.visibility = if (logoActive) VISIBLE else INVISIBLE
        logoOpen.visibility = if (logoActive && logoCrossfade) VISIBLE else INVISIBLE
        logoGlow.visibility = if (logoActive && titleSlot.glow != null) VISIBLE else GONE
        applyLogoLook()
    }

    /** 标题那一行按 logo 排版时 logo 多大 (标题宽 [titleW] 以内); null = 按文字标题排版. */
    private fun activeLogoSize(titleW: Int): TvNativeTitleSlot.LogoSize? {
        if (!logoReserved) return null
        val target = logoTarget ?: return null
        val box = style.logoBox ?: return null
        val capped = if (titleW > 0 && box.maxWidthPx > titleW) box.copy(maxWidthPx = titleW) else box
        val size = capped.sizeOf(target.aspectRatio)
        return TvNativeTitleSlot.LogoSize(size.widthPx, size.heightPx, box.maxHeightPx)
    }

    /**
     * logo 随 [titleLook] 从列表页的样子渐变成详情页的样子 (同标题文字从黑变白); 两种样子一样时只显示 [logo]. 柔光是按模糊背景算的:
     * 点开时背景变清晰, 跟着淡掉 (连同 [detailAlpha]).
     */
    private fun applyLogoLook() {
        val t = titleLook.coerceIn(0f, 1f)
        if (logoCrossfade) {
            logo.alpha = 1f - t
            logoOpen.alpha = t
        } else {
            logo.alpha = 1f
        }
        logoGlow.alpha = (1f - t) * detailAlpha
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
     * 放大 / 缩回转场期间的标题: [hidden] = 绘制权在详情页那份或转场层 (TvHeroZoomHandoff.titleOwnedByDetails / titleOwnedByOverlay),
     * [offsetX] / [offsetY] = 让位平移 (shrinkTitleOffset, 退路), [settling] = 缩回中 (停跑马灯, titleSettling). 由页面按当前条目观察
     * TvHeroZoomHandoff 后调.
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
        if (titleEnter != null) {
            // 标题还在滑入: 位移与透明度归进场动画管, 只处理隐藏. 只看标题自己那一行 —— 别的行错开得更晚, 按它们判的话,
            // 标题滑完之后、别的行滑完之前放开的隐藏就丢了, 标题一直不见: 进过播放器再缩回探索页时, 列表页在缩回层下重建、
            // 文字刚进场, 缩回化开撤层 (交还标题) 正落在这一段
            if (titleHidden) titleSlot.alpha = 0f
            return
        }
        titleSlot.alpha = if (titleHidden) 0f else 1f
        titleSlot.translationX = titleOffsetX
        titleSlot.translationY = titleOffsetY
    }

    private fun updateTitleMarquee() {
        val oneLine = style.titleMaxLines == 1
        val run = oneLine && titleMarquee && !titleSettling && style.marqueeRepeat != 0
        title.ellipsize = when {
            run -> TextUtils.TruncateAt.MARQUEE
            oneLine -> null
            else -> TextUtils.TruncateAt.END
        }
        title.isSelected = run && !marqueePaused
    }

    /**
     * 登记标题此刻在 Compose 根坐标里的框 (不含缩回让位平移与进场滑入: 详情页按登记的框算平移起点, 框里算上平移就被自己的平移改写, 自激).
     * 本块或祖先挪动 / 缩放后调 (页面在滚动联动里调, 布局完成时自己调).
     */
    fun publishTitle() {
        val text = shown ?: return
        if (titleSlot.visibility != VISIBLE || titleSlot.width == 0 || !isAttachedToWindow) return
        // 按 logo 排版时登记 logo 的框 (图还没解好也是: 详情页那头也是 logo, 从这个框平移过去); 图迟迟不来、先放着文字标题时登记文字
        val logoShown = if (logoReserved && title.visibility != VISIBLE) logoTarget else null
        val shownView: View = if (logoShown != null) logo else title
        getLocationInWindow(xy)
        val root = composeRoot
        if (root != null) {
            root.getLocationInWindow(rootXY)
        } else {
            rootXY[0] = 0
            rootXY[1] = 0
        }
        val left = xy[0] - rootXY[0] + (titleSlot.left + shownView.left) * scaleX
        val top = xy[1] - rootXY[1] + (titleSlot.top + shownView.top) * scaleY
        val baseline = if (logoShown != null) -1 else title.baseline
        // logo 与详情页的样子不同: 详情页那头叠一份列表页的样子交叉淡化
        val logoDiffers = logoShown != null && logoLooksDiffer()
        // 压在模糊背景上: 详情页按自己的背景另判, 两边可能不同, 一律交过去 (相同时交叉淡化看不出)
        val backdropLook = logoShown != null && logoLoadedFor?.backdrop == true
        TvHeroZoomHandoff.publishTitle(
            titleOwner, text.subjectId, Rect(left, top, left + shownView.width * scaleX, top + shownView.height * scaleY), text.title,
            maxLines = style.titleMaxLines, clipOverflow = style.titleMaxLines == 1,
            baseline = if (baseline >= 0) baseline * scaleY else Float.NaN,
            // 没有另一种样子 (深色主题的文字标题、与详情页同样子的 logo) = 已经是详情页的样子
            look = if (style.openTitle != null || logoDiffers) titleLook else 1f,
            logo = logoShown,
            logoLook = when {
                backdropLook -> TvHeroTitleLogoLook(logoListFlip, force = true)
                logoDiffers -> TvHeroTitleLogoLook(logoListFlip)
                else -> null
            },
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        TvHeroZoomHandoff.retractTitle(titleOwner)
        removeCallbacks(logoImageWaitTimeout)
        removeCallbacks(logoBackdropWaitTimeout)
        if (enterDeferred) resumeDeferredEnter(waitLogo = false)
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
        if (titleSlot.visibility != GONE) {
            titleSlot.logoSize = activeLogoSize(titleW)
            titleSlot.measure(MeasureSpec.makeMeasureSpec(titleW, MeasureSpec.EXACTLY), unspecified)
            used += gap() + titleSlot.measuredHeight
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
            // 简介占满剩余高度: 放得下几行就几行 (内容自己限了行数的不超过它), 最后一行省略号
            val remaining = (height - used - gap()).coerceAtLeast(0)
            val lineHeight = max(1, s.summary.lineHeightPx.takeIf { it > 0 } ?: summary.lineHeight)
            val cap = shown?.summaryMaxLines?.takeIf { it > 0 } ?: Int.MAX_VALUE
            val maxLines = min(remaining / lineHeight, cap)
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
        // 压在模糊背景上、logo 还没判: 排好了才知道它压在背景的哪一块
        if (logoGrid != null && logoLook == null) decideLogo(again = false)
    }
}

/**
 * hero 文字块的标题那一行: 文字标题, 或标题 logo (只显示一个, 见 [TvNativeHeroTextView]). 按 logo 排版时高 = 槽高、logo 底对齐左对齐
 * (logo 的图迟迟不来时文字标题放在这个位置上, 同样底对齐); 按文字排版时就是文字的大小. [logoOpen] (logo 的另一种样子) 与 [logo] 同一个框;
 * [glowView] (logo 背后的柔光, 见 [glow]) 按 logo 的框向四周扩, 画在 logo 下面, 伸出本行 (不裁).
 */
@SuppressLint("ViewConstructor")
private class TvNativeTitleSlot(
    context: Context,
    private val text: View,
    private val logo: View,
    private val logoOpen: View,
    private val glowView: View,
) : ViewGroup(context) {
    /** 按 logo 排版时 logo 的大小与槽高 (px); null = 按文字标题排版. 由外面在量之前设. */
    var logoSize: LogoSize? = null

    /** logo 背后的柔光 (格子数与向四周扩几格); null = 没有. */
    var glow: TvTitleLogoGlow? = null

    class LogoSize(val widthPx: Int, val heightPx: Int, val slotHeightPx: Int)

    init {
        clipChildren = false
        clipToPadding = false
        // 柔光只在显示 logo 时有, 那时文字标题不显示: 叠在文字后面、logo 下面
        addView(text)
        addView(glowView)
        addView(logo)
        addView(logoOpen)
    }

    /** 柔光一格多宽、多高 (px): logo 的框按柔光的格子分. */
    private fun glowCell(size: LogoSize, glow: TvTitleLogoGlow): Pair<Float, Float> =
        size.widthPx.toFloat() / (glow.cols - 2 * glow.marginCells) to size.heightPx * glow.cellPerLogoHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val l = logoSize
        val g = glow
        if (l != null && g != null) {
            val (cellW, cellH) = glowCell(l, g)
            glowView.measure(
                MeasureSpec.makeMeasureSpec((g.cols * cellW).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((g.rows * cellH).roundToInt(), MeasureSpec.EXACTLY),
            )
        } else {
            glowView.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))
        }
        if (l != null) {
            for (view in listOf(logo, logoOpen)) {
                view.measure(MeasureSpec.makeMeasureSpec(l.widthPx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(l.heightPx, MeasureSpec.EXACTLY))
            }
            var height = l.slotHeightPx
            if (text.visibility != GONE) {
                text.measure(widthMeasureSpec, heightMeasureSpec)
                height = max(height, text.measuredHeight)
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
        } else {
            text.measure(widthMeasureSpec, heightMeasureSpec)
            for (view in listOf(logo, logoOpen)) {
                view.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(text.measuredWidth, text.measuredHeight)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val size = logoSize
        val height = b - t
        for (view in listOf(logo, logoOpen)) {
            if (size != null) view.layout(0, height - size.heightPx, size.widthPx, height) else view.layout(0, 0, 0, 0)
        }
        val g = glow
        if (size != null && g != null) {
            val (cellW, cellH) = glowCell(size, g)
            val left = -(g.marginCells * cellW).roundToInt()
            val top = height - size.heightPx - (g.marginCells * cellH).roundToInt()
            glowView.layout(left, top, left + glowView.measuredWidth, top + glowView.measuredHeight)
        } else {
            glowView.layout(0, 0, 0, 0)
        }
        if (text.visibility != GONE) {
            val top = if (size != null) height - text.measuredHeight else 0
            text.layout(0, top, text.measuredWidth, top + text.measuredHeight)
        }
    }

    override fun hasOverlappingRendering(): Boolean = false
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
    private var marqueeRepeat = 0
    private var marqueePaused = false

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
        this.marqueeRepeat = marqueeRepeat
        name.marqueeRepeatLimit = marqueeRepeat
        name.ellipsize = if (marqueeRepeat != 0) TextUtils.TruncateAt.MARQUEE else null
        name.isSelected = marqueeRepeat != 0 && !marqueePaused
    }

    /** 见 [TvNativeHeroTextView.marqueePaused]. */
    fun setMarqueePaused(paused: Boolean) {
        marqueePaused = paused
        name.isSelected = marqueeRepeat != 0 && !paused
    }

    /** [color] = 三段的字色 (vibrancy 时可能换过, 见 [TvNativeHeroText.vibrant]), [vibrant] = 以加法混合画. */
    fun bind(status: TvNativeHeroStatus, color: Int, vibrant: Boolean) {
        if (status.wrap != leadWraps) {
            leadWraps = status.wrap
            lead.setSingleLine(!status.wrap)
            lead.setHorizontallyScrolling(!status.wrap)
        }
        lead.text = status.lead
        name.text = status.name?.takeIf { it.isNotBlank() && !status.wrap }?.let { status.nameSeparator + it }.orEmpty()
        name.visibility = if (status.name.isNullOrBlank() || status.wrap) GONE else VISIBLE
        tail.text = status.tail.orEmpty()
        tail.visibility = if (status.tail.isNullOrEmpty() || status.wrap) GONE else VISIBLE
        for (v in parts) {
            v.setTextColor(color)
            v.setTvVibrancy(vibrant)
        }
    }

    /** [lead] 此刻是不是可以折行的多行字 (见 [TvNativeHeroStatus.wrap]). */
    private var leadWraps = false

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

/**
 * 知道有 logo、图还没显示出来时, 标题位最多空多久就先放文字标题 (见 [TvNativeHeroTextView]). 从加载到显示: 解码 (最可能走到的那一部由 hero 流水线
 * 提前解好, 别的邻居从磁盘解), 压在模糊背景上时还要量格子颜色、等背景铺上这一部 (最多 [TV_TITLE_LOGO_BACKDROP_WAIT_MILLIS])、按背景判,
 * 判出要翻色再解一张. 先放文字、图到了再换比标题位多空一会儿显眼, 所以给得宽: 只兜底网络很慢、图迟迟下不来的时候.
 */
private const val TV_TITLE_LOGO_IMAGE_WAIT_MILLIS = 2_000L

/**
 * logo 压在模糊背景上时, 原图解好后最多等背景铺上这一部的图多久 (见 [TvNativeHeroTextView]); 到点先按原样显示, 铺上了再判.
 * 连着换条目时整屏背景要等上一张淡满才换 (400ms), 文字也在淡出淡入, 一般赶得上.
 */
private const val TV_TITLE_LOGO_BACKDROP_WAIT_MILLIS = 500L

/**
 * 还不知道有没有 logo 时, 进场最多等多久 (旧字淡完之后再等这么久; 见 [TvNativeHeroTextView]). 热表或持久缓存里有的几毫秒就查完;
 * 要现查 TMDB 的多半等不到, 照文字标题进场.
 */
private const val TV_TITLE_LOGO_PENDING_WAIT_MILLIS = 300L

/** 字色 [argb] 是不是浅色 (亮度过半). */
private fun isLightArgb(argb: Int): Boolean =
    0.2126f * ((argb shr 16) and 0xFF) + 0.7152f * ((argb shr 8) and 0xFF) + 0.0722f * (argb and 0xFF) > 127.5f

/** 两个 ARGB 颜色逐通道线性插值 (sRGB 上直接插, 同两层字交叉淡化叠出来的颜色). */
private fun lerpArgb(a: Int, b: Int, t: Float): Int {
    var out = 0
    for (shift in intArrayOf(24, 16, 8, 0)) {
        val ca = a ushr shift and 0xFF
        val cb = b ushr shift and 0xFF
        out = out or ((ca + (cb - ca) * t).roundToInt().coerceIn(0, 255) shl shift)
    }
    return out
}
