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
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LineHeightSpan
import android.util.FloatProperty
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Immutable
import com.github.panpf.sketch.Sketch
import kotlin.math.roundToInt
import me.him188.ani.app.ui.foundation.TvNativeImages
import me.him188.ani.app.ui.foundation.tv.TV_OBSCURED_COVER_LONG_EDGE_PX
import kotlin.math.ceil
import kotlin.math.min

/**
 * 原生海报墙的一张卡的数据.
 *
 * @param subtitle 给了就番名只占一行、下面一行小字 (详情页关联条目的「续集」「前传」), 块高不变. 小字没聚焦时淡一档, 聚焦时全亮.
 * @param progress 继续观看的集数进度 (0..1), 贴封面底缘一条细进度条; null 不画.
 * @param obscure NSFW 打码: 封面只解一张很小的图, 放大成糊图.
 * @param subjectId 这张卡是哪个条目: 网格页进 hero 态时拿它核对页面给的 hero 内容是不是这一张的 (见 TvNativeGridPageView); null = 不核对.
 * @param subtitleColor [subtitle] 的颜色 (ARGB); null = 样式里的 ([TvNativeWallStyle.subtitle], 与番名同色).
 * @param badge 封面右上角画角标 (样式给了 [TvNativeWallStyle.badge] 才画; 新番时间表的「在追」).
 */
@Immutable
data class TvNativeCard(
    val imageUrl: String?,
    val title: String,
    val subtitle: String? = null,
    val progress: Float? = null,
    val obscure: Boolean = false,
    val subjectId: Int? = null,
    val subtitleColor: Int? = null,
    val badge: Boolean = false,
    /**
     * 行尾的「更多」卡 (不是条目): 封面位置是一块玻璃 (见 [TvNativeMoreGlassStyle]), 底下铺 [imageUrl] (一张竖版封面, 由页面挑) 的模糊小图,
     * 中间一枚圆钮托着加号, 加载中换成转着的圆弧; null = 普通海报.
     */
    val more: TvNativeMore? = null,
)

/** 「更多」卡的两种样子, 见 [TvNativeCard.more]. */
enum class TvNativeMore {
    Idle,
    Loading,
}

/**
 * 行尾「更多」卡 ([TvNativeCard.more]) 的玻璃外观, 照 tvOS 的玻璃材质 (配色同顶栏玻璃控件那一族, 见 TvGlassColors): 底下是一张竖版封面的
 * 模糊小图, 盖一层玻璃色 [tint], 左上角斜着一道高光 [sheen] 淡到透明, 一圈细亮边 [edge]; 中间一枚玻璃圆钮 ([buttonFill] / [buttonEdge])
 * 托着加号 ([icon]). 卡片聚焦时圆钮随聚焦程度换成浅色实底 ([buttonFocusedFill]) 配深色加号 ([iconFocused]), 同顶栏玻璃控件聚焦时的实底.
 * 颜色都是 ARGB.
 */
@Immutable
data class TvNativeMoreGlassStyle(
    val tint: Int,
    val sheen: Int,
    val edge: Int,
    val buttonFill: Int,
    val buttonEdge: Int,
    val icon: Int,
    val buttonFocusedFill: Int,
    val iconFocused: Int,
)

/** 深色主题: 中性深色玻璃, 白边白字; 聚焦的圆钮同顶栏玻璃的浅灰实底. */
internal val TV_NATIVE_MORE_GLASS_DARK = TvNativeMoreGlassStyle(
    tint = 0x6B1E1E1E,
    sheen = 0x29FFFFFF,
    edge = 0x38FFFFFF,
    buttonFill = 0x29FFFFFF,
    buttonEdge = 0x59FFFFFF,
    icon = 0xF2FFFFFF.toInt(),
    buttonFocusedFill = 0xFFD0D1D3.toInt(),
    iconFocused = 0xFF000000.toInt(),
)

/** 浅色主题: 发白的磨砂玻璃, 深色加号; 聚焦的圆钮是白色实底. */
internal val TV_NATIVE_MORE_GLASS_LIGHT = TvNativeMoreGlassStyle(
    tint = 0x59FFFFFF,
    sheen = 0x66FFFFFF,
    edge = 0x8CFFFFFF.toInt(),
    buttonFill = 0x73FFFFFF,
    buttonEdge = 0xB3FFFFFF.toInt(),
    icon = 0xBF000000.toInt(),
    buttonFocusedFill = 0xFFFFFFFF.toInt(),
    iconFocused = 0xFF000000.toInt(),
)

/**
 * 原生海报墙的卡片: 可聚焦的外框 (卡宽 × (卡高 + 番名块)) 里放海报与番名. 外观取值见 [TvNativeWallStyle]: 圆角封面、1 像素玻璃边
 * (Android TV 上 Apple TV App 的卡片), 静止时底下一圈看得出的影 (照 tvOS 海报 lockup, 预先模糊好的图, 见 [TvNativeCardShadowView]) /
 * 聚焦时换成抬高的一大片软影 (系统阴影, 同 Google TV 桌面卡片的 elevation)、放大, 番名往下让开 (番名不放大, 让出海报多伸出来的那截).
 * 番名与下面那行小字没聚焦时都淡一档、聚焦时全亮 (见 [TvNativeWallStyle.labelIdleAlpha]).
 *
 * 聚焦效果照 Google TV 桌面卡片的做法由状态动画 (StateListAnimator) 驱动, 只动 RenderNode 属性、不重录卡片内容: 动画只推一个
 * 聚焦程度 [focusProgress] (0..1, [TvNativeWallStyle.focusMillis] FastOutSlowIn), 放大倍数、高度、小字亮度与番名位移都由它推出来.
 * 压暗 ([dim]) 与番名显隐 ([titleVisibility]) 乘在各自的透明度上.
 */
@SuppressLint("ViewConstructor")
class TvNativeCardView(context: Context, private val style: TvNativeWallStyle) : FrameLayout(context) {
    private val idleShadow = TvNativeCardShadowView(context, style)
    private val cover = TvNativeCoverView(context, style)
    private val label = TvNativeLabelView(context)
    private val title = TvNativeTextView(context)
    private val subtitle = TvNativeTextView(context)
    private var hasSubtitle = false
    private var marquee = false
    private val confirmKey = TvNativeConfirmKey()
    private val windowXY = IntArray(2)

    /** 确定键长按的回调 (收藏菜单), 参数是封面在窗口里的框 (菜单锚点); null = 没有长按, 按住也只算点击. */
    var longPressHandler: ((Rect) -> Unit)? = null

    /** 聚焦程度 (0..1), 由状态动画推 (外框拿到 / 失去焦点时动画到 1 / 0), 见类说明. */
    var focusProgress: Float = 0f
        set(value) {
            field = value
            applyFocus()
        }

    /**
     * 整张卡的淡化 / 压暗 (0..1, 1 = 不淡): 横滑行越过行首的离场卡、hero 态越线淡没的行. 乘在海报与番名自己的透明度上, 系统阴影色再乘
     * 平方 —— 半透明的封面按透明遮挡投影, 封面底下整块阴影会透出来.
     */
    var dim: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyFocus()
            applyShadowColor()
        }

    /** 番名的显隐 (0..1): 页面切到 hero 态时淡出. */
    var titleVisibility: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyFocus()
        }

    /**
     * 远跳途中 (返回键回首卡) 一律画成未聚焦: 一路滚过的卡不挨个放大, 到位再恢复 —— 同 Apple TV 回顶时只有落点那张有聚焦效果.
     *
     * 放开时没有焦点的卡, 状态动画先跳到失焦态的终点再画: 远跳出发时聚焦的那张, 压着期间焦点一直在它身上, 焦点给到落点那一刻才起步
     * 失焦动画, 紧接着放开的话它按动画起点画成放大态、再缩回去 (返回回首卡时原来那张闪一下放大).
     */
    var focusEffectSuppressed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value && !isFocused) {
                refreshDrawableState()
                stateListAnimator?.jumpToCurrentState()
            }
            applyFocus()
        }

    /**
     * 没有焦点也画成聚焦态: 进详情页交出焦点之后、返回后焦点交还之前, 那张卡一直放大着, 不先缩回去再放大. 改用 [setFocusLookHeld].
     */
    var focusLookHeld: Boolean = false
        private set
    private var releaseAnimator: Animator? = null

    /**
     * 按住 / 放开聚焦态 ([focusLookHeld]). 放开时状态动画跳到当前状态的终点: 真焦点已在这张上就停在聚焦态, 画面不变 (不跳到动画中途);
     * 没焦点时 [animate] 就照失焦的动画缩回, 否则当场按未聚焦画 (换绑别的条目).
     */
    fun setFocusLookHeld(held: Boolean, animate: Boolean) {
        if (focusLookHeld == held) return
        focusLookHeld = held
        releaseAnimator?.cancel()
        releaseAnimator = null
        if (!held) {
            // 放开常发生在拿到焦点的回调里 (适配器的焦点监听): View 在 onFocusChanged 之后才按新焦点 refreshDrawableState, 这时
            // 状态动画还停在失焦态, 跳到的终点是缩回的样子, 随后再从 0 放大一遍. 先按真实焦点刷新一次, 跳到的就是聚焦态
            refreshDrawableState()
            stateListAnimator?.jumpToCurrentState()
            if (animate && !isFocused) {
                releaseAnimator = ObjectAnimator.ofFloat(this, TV_NATIVE_FOCUS_PROGRESS, 1f, 0f).apply {
                    duration = style.focusMillis
                    interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
                    start()
                }
            }
        }
        applyFocus()
    }

    init {
        layoutParams = LayoutParams(style.cardWidthPx, style.cardHeightPx + style.labelHeightPx)
        // 触摸模式下也可聚焦: 窗口进了触摸模式时卡照样拿得到焦点, 焦点不会因此停到行 / 网格上或丢掉
        isFocusableInTouchMode = true
        // 系统默认的聚焦高亮 (API 26 起给没有聚焦态背景的 View 叠一层) 不要: 聚焦只由放大与投影表达
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        clipChildren = false
        clipToPadding = false
        // 静止时的投影垫在海报底下 (先加 = 先画); 流畅档没有这层
        idleShadow.shadow?.let { shadow ->
            addView(
                idleShadow,
                LayoutParams(shadow.bitmap.width, shadow.bitmap.height).apply {
                    leftMargin = style.gapPx - shadow.padPx
                    topMargin = style.gapPx - shadow.padPx + style.idleShadowOffsetYPx.roundToInt()
                },
            )
        }
        addView(
            cover,
            LayoutParams(style.coverWidthPx, style.coverHeightPx).apply {
                leftMargin = style.gapPx
                topMargin = style.gapPx
            },
        )
        label.orientation = LinearLayout.VERTICAL
        // 番名排在海报之后画: 系统阴影在外框的 Z 排序区里按 Z 画, 番名的 Z 高于海报才压在海报投影上面
        label.translationZ = style.focusedElevationPx + 1f
        label.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        label.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(
            label,
            LayoutParams(style.cardWidthPx, style.labelHeightPx - style.titleTopGapPx).apply {
                topMargin = style.cardHeightPx + style.titleTopGapPx
            },
        )
        style.title.applyTo(title)
        style.subtitle.applyTo(subtitle)
        if (style.labelVibrancy) {
            // 字色加在底下的背景上 (见 TvNativeWallStyle.labelVibrancy): 番名块与两行字都不开离屏层, 透明度直接乘在画笔上
            title.paint.xfermode = TV_NATIVE_LABEL_VIBRANCY
            subtitle.paint.xfermode = TV_NATIVE_LABEL_VIBRANCY
        }
        subtitle.visibility = GONE
        stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusAnimator(1f))
            addState(intArrayOf(), focusAnimator(0f))
        }
        applyShadowColor()
        applyFocus()
    }

    fun bind(card: TvNativeCard, sketch: Sketch) {
        contentDescription = card.title
        title.text = card.title
        val sub = card.subtitle
        hasSubtitle = sub != null
        if (sub != null) {
            title.maxLines = 1
            subtitle.text = sub
            subtitle.setTextColor(card.subtitleColor ?: style.subtitle.color)
            subtitle.visibility = VISIBLE
        } else {
            title.maxLines = 2
            subtitle.visibility = GONE
        }
        marquee = hasSubtitle && style.marquee
        updateMarquee()
        cover.progress = card.progress
        cover.badge = card.badge
        cover.more = card.more
        val url = card.imageUrl
        if (card.more != null && url != null) {
            // 「更多」卡的玻璃底: 那张竖版封面的模糊小图 (解码线程上模糊, 拉伸铺满封面框), 与海报本身同一档缩略图地址
            TvNativeImages.loadBlurredBackdrop(
                sketch, cover, url, style.coverWidthPx, style.coverHeightPx,
                longEdgePx = TV_NATIVE_MORE_BLUR_LONG_EDGE_PX, blurRadiusPx = TV_NATIVE_MORE_BLUR_RADIUS_PX,
                coverWidthPx = style.coverWidthPx, coverHeightPx = style.coverHeightPx,
            ) {}
        } else {
            TvNativeImages.loadCover(
                sketch, cover, url, style.coverWidthPx, style.coverHeightPx, style.crossfade,
                obscureLongEdgePx = if (card.obscure) TV_OBSCURED_COVER_LONG_EDGE_PX else null,
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        confirmKey.onKey(this, event, longPressHandler?.let { handler -> { handler(coverRectInWindow()) } }) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        confirmKey.onKey(this, event, null) || super.onKeyUp(keyCode, event)

    /** 封面此刻在窗口里的框 (未放大时的位置): 长按菜单锚在这里. */
    fun coverRectInWindow(): Rect {
        cover.getLocationInWindow(windowXY)
        return Rect(windowXY[0], windowXY[1], windowXY[0] + cover.width, windowXY[1] + cover.height)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) confirmKey.reset()
        // 放开时的缩回动画还在走又拿到焦点: 交给状态动画从当前值接着走
        releaseAnimator?.cancel()
        releaseAnimator = null
        updateMarquee()
    }

    /** 带副标题的卡番名只有一行, 聚焦时放不下就跑马灯 (同 tvOS 聚焦单元里截断的标签); 流畅档不滚. */
    private fun updateMarquee() {
        val run = marquee && isFocused
        title.ellipsize = if (run) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
        title.isSelected = run
    }

    private fun applyFocus() {
        val p = when {
            focusEffectSuppressed -> 0f
            focusLookHeld -> 1f
            else -> focusProgress
        }
        val s = 1f + (style.focusScale - 1f) * p
        cover.scaleX = s
        cover.scaleY = s
        cover.moreFocus = p
        // 静止那圈影随聚焦淡出, 系统阴影随聚焦从 0 抬起: 两层交接, 聚焦的那张影子明显大一圈. 影子的透明度乘压暗的平方 (同系统阴影色)
        cover.elevation = style.focusedElevationPx * p
        idleShadow.alpha = (1f - p) * dim * dim
        cover.alpha = dim
        label.translationY = style.titleShiftPx * p
        label.alpha = titleVisibility * dim
        val labelAlpha = style.labelIdleAlpha + (1f - style.labelIdleAlpha) * p
        title.alpha = labelAlpha
        subtitle.alpha = labelAlpha
    }

    private fun applyShadowColor() {
        if (Build.VERSION.SDK_INT < 28) return
        val base = style.shadowColor
        val a = (Color.alpha(base) * dim * dim).toInt().coerceIn(0, 255)
        val c = Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
        cover.outlineAmbientShadowColor = c
        cover.outlineSpotShadowColor = c
    }

    private fun focusAnimator(target: Float) = AnimatorSet().apply {
        play(ObjectAnimator.ofFloat<TvNativeCardView>(null, TV_NATIVE_FOCUS_PROGRESS, target))
        duration = style.focusMillis
        interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
    }
}

/**
 * 海报. 圆角靠 outline 裁剪 (同 Google TV 桌面卡片), 系统阴影按同一个 outline 投; 加载前露出占位底色. 集数进度条画在图上
 * (贴封面底缘居中, 同 TvPortraitCard), 玻璃边是前景里一圈 1 像素描边 (圆角减半像素, 画在封面边缘以内).
 * 尺寸由布局参数定死: 换图时 ImageView 会按图片尺寸 requestLayout, 那会冒泡到 Compose 让整棵原生树重新测量 —— 量过一次之后挡掉,
 * 图片矩阵由 setImageDrawable 自己重算.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
private class TvNativeCoverView(context: Context, private val style: TvNativeWallStyle) : ImageView(context) {
    var progress: Float? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 右上角画角标 ([TvNativeWallStyle.badge]). */
    var badge: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 画成「更多」卡 ([TvNativeCard.more]): 图 (模糊小图, 见 [TvNativeCardView.bind]) 上盖一块玻璃, 中间一枚圆钮托着加号; 加载中换成转着的圆弧.
     * 外观见 [TvNativeMoreGlassStyle].
     */
    var more: TvNativeMore? = null
        set(value) {
            if (field == value) return
            field = value
            updateSpinner()
            invalidate()
        }

    /** 卡片的聚焦程度 (0..1, 见 [TvNativeCardView.focusProgress]): 「更多」卡的圆钮随它换成实底. */
    var moreFocus: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            if (more != null) invalidate()
        }

    // 不在 Paint 的 apply 里读 style: Paint 自己也有 style 属性, 会被它遮住
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = style.progressTrackColor }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = style.progressFillColor }
    private val barRect = RectF()
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = style.badge?.backgroundColor ?: 0 }
    private val badgeIconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glass = style.moreGlass
    private val glassTintPaint = Paint().also { it.color = glass.tint }
    private val glassSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.color = glass.edge
    }
    private val glassRect = RectF()
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE }
    private val morePaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.strokeCap = Paint.Cap.ROUND
    }
    private val moreArcRect = RectF()
    private var spinnerAngle = 0f
    private var spinner: ValueAnimator? = null

    /** 加载中的圆弧只在挂在窗口上时转, 离开就停 (回收池里的卡不空转). */
    private fun updateSpinner() {
        val spinning = more == TvNativeMore.Loading && isAttachedToWindow
        if (spinning && spinner == null) {
            spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = TV_NATIVE_MORE_SPIN_MILLIS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    spinnerAngle = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!spinning) {
            spinner?.cancel()
            spinner = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSpinner()
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        spinner = null
        super.onDetachedFromWindow()
    }

    init {
        scaleType = ScaleType.CENTER_CROP
        setBackgroundColor(style.placeholderColor)
        foreground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = style.cornerPx - 0.5f
            setStroke(1, style.edgeColor)
        }
        val corner = style.cornerPx
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        clipToOutline = true
        isDuplicateParentStateEnabled = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 高光: 左上角斜着淡到透明, 过了对角线的 [TV_NATIVE_MORE_SHEEN_REACH] 就没有了
        glassSheenPaint.shader = LinearGradient(
            0f, 0f, w * TV_NATIVE_MORE_SHEEN_REACH, h * TV_NATIVE_MORE_SHEEN_REACH,
            glass.sheen, glass.sheen and 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = more
        if (m != null) {
            drawMoreGlass(canvas, m)
            return
        }
        val b = style.badge
        if (badge && b != null) {
            val r = b.sizePx / 2f
            val cx = width - b.insetPx - r
            val cy = b.insetPx + r
            canvas.drawCircle(cx, cy, r, badgePaint)
            canvas.drawBitmap(b.icon, cx - b.icon.width / 2f, cy - b.icon.height / 2f, badgeIconPaint)
        }
        val p = progress ?: return
        if (p <= 0f) return
        val h = style.progressBarHeightPx
        val left = (width - style.progressBarLengthPx) / 2f
        val bottom = height - style.progressBarBottomGapPx
        barRect.set(left, bottom - h, left + style.progressBarLengthPx, bottom)
        canvas.drawRoundRect(barRect, h / 2f, h / 2f, trackPaint)
        barRect.right = left + style.progressBarLengthPx * p.coerceIn(0f, 1f)
        canvas.drawRoundRect(barRect, h / 2f, h / 2f, fillPaint)
    }

    /** 「更多」卡的玻璃 (见 [TvNativeMoreGlassStyle]): 盖色、高光、细亮边, 中间圆钮托着加号 / 转着的圆弧. */
    private fun drawMoreGlass(canvas: Canvas, m: TvNativeMore) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, glassTintPaint)
        canvas.drawRect(0f, 0f, w, h, glassSheenPaint)
        // 细亮边画在封面边缘以内, 圆角照封面的 outline
        val edge = TV_NATIVE_MORE_EDGE_PX
        val inset = edge / 2f
        glassEdgePaint.strokeWidth = edge
        glassRect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(glassRect, style.cornerPx - inset, style.cornerPx - inset, glassEdgePaint)
        // 圆钮: 玻璃底随聚焦换成实底, 亮边随之淡掉 (实底上不留边)
        val f = moreFocus.coerceIn(0f, 1f)
        val r = min(w, h) * TV_NATIVE_MORE_ICON_RADIUS
        val cx = w / 2f
        val cy = h / 2f
        buttonPaint.color = tvNativeBlendArgb(glass.buttonFill, glass.buttonFocusedFill, f)
        canvas.drawCircle(cx, cy, r, buttonPaint)
        buttonEdgePaint.strokeWidth = edge
        buttonEdgePaint.color = glass.buttonEdge
        buttonEdgePaint.alpha = (Color.alpha(glass.buttonEdge) * (1f - f)).roundToInt()
        canvas.drawCircle(cx, cy, r - inset, buttonEdgePaint)
        morePaint.color = tvNativeBlendArgb(glass.icon, glass.iconFocused, f)
        morePaint.strokeWidth = r * TV_NATIVE_MORE_STROKE
        if (m == TvNativeMore.Loading) {
            val ar = r * TV_NATIVE_MORE_SPINNER_RADIUS
            moreArcRect.set(cx - ar, cy - ar, cx + ar, cy + ar)
            canvas.drawArc(moreArcRect, spinnerAngle, 270f, false, morePaint)
        } else {
            val arm = r * TV_NATIVE_MORE_ARM
            canvas.drawLine(cx - arm, cy, cx + arm, cy, morePaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, morePaint)
        }
    }

    override fun requestLayout() {
        if (!isLaidOut) super.requestLayout()
    }
}

/** 两个 ARGB 色按 [t] (0..1) 逐通道插值. */
private fun tvNativeBlendArgb(from: Int, to: Int, t: Float): Int {
    fun mix(a: Int, b: Int) = (a + (b - a) * t).roundToInt()
    return Color.argb(
        mix(Color.alpha(from), Color.alpha(to)),
        mix(Color.red(from), Color.red(to)),
        mix(Color.green(from), Color.green(to)),
        mix(Color.blue(from), Color.blue(to)),
    )
}

/**
 * 海报静止时底下的那圈投影 ([TvNativeWallStyle.idleShadowColor]): 同尺寸的卡共用一张预先模糊好的图 (见 [tvNativeCardShadow]), 只画
 * 封面四周露出来的那一圈 —— 中间被不透明的封面挡着, 不画, 省掉整张卡面积的混合. 透明度由卡按聚焦程度与压暗设 (图层属性, 不重录).
 */
@SuppressLint("ViewConstructor")
private class TvNativeCardShadowView(context: Context, private val style: TvNativeWallStyle) : View(context) {
    val shadow: TvNativeCardShadow? = tvNativeCardShadow(style)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).also { it.color = style.idleShadowColor }
    private val src = Rect()

    override fun onDraw(canvas: Canvas) {
        val s = shadow ?: return
        val w = s.bitmap.width
        val h = s.bitmap.height
        // 封面挡住的那块 (图里的坐标): 封面比这张图靠上 idleShadowOffsetY, 四角的圆角那一截露在外面, 照画
        val inset = s.padPx + ceil(style.cornerPx).toInt()
        val dy = style.idleShadowOffsetYPx.roundToInt()
        val top = (inset - dy).coerceIn(0, h)
        val bottom = (h - inset - dy).coerceIn(top, h)
        val left = inset.coerceIn(0, w)
        val right = (w - inset).coerceIn(left, w)
        piece(canvas, s.bitmap, 0, 0, w, top)
        piece(canvas, s.bitmap, 0, bottom, w, h)
        piece(canvas, s.bitmap, 0, top, left, bottom)
        piece(canvas, s.bitmap, right, top, w, bottom)
    }

    private fun piece(canvas: Canvas, bitmap: Bitmap, l: Int, t: Int, r: Int, b: Int) {
        if (r <= l || b <= t) return
        src.set(l, t, r, b)
        canvas.drawBitmap(bitmap, src, src, paint)
    }

    // 四块不重叠: 透明度逐绘制指令乘, 不走离屏层
    override fun hasOverlappingRendering(): Boolean = false
}

/** 预先模糊好的海报投影: [bitmap] 只有透明度 (颜色由画的 Paint 给), 封面在图里的 ([padPx], [padPx]) 处. */
private class TvNativeCardShadow(val bitmap: Bitmap, val padPx: Int)

private data class TvNativeCardShadowKey(val width: Int, val height: Int, val cornerPx: Float, val blurPx: Float)

/** 番名 vibrancy 的混合模式 (见 [TvNativeWallStyle.labelVibrancy]): 字色加在底下已经画好的背景上 (饱和到白为止). */
private val TV_NATIVE_LABEL_VIBRANCY = PorterDuffXfermode(PorterDuff.Mode.ADD)

private val tvNativeCardShadows = HashMap<TvNativeCardShadowKey, TvNativeCardShadow>()

/**
 * 按封面尺寸、圆角与模糊半径做一张投影图, 同样的卡共用 (主线程上建卡时取). 模糊半径按 CSS / Sketch 的约定 (σ = 半径 / 2), 图四周留 3σ.
 * 投影透明 (流畅档) 时 null.
 */
private fun tvNativeCardShadow(style: TvNativeWallStyle): TvNativeCardShadow? {
    val w = style.coverWidthPx
    val h = style.coverHeightPx
    if (Color.alpha(style.idleShadowColor) == 0 || w <= 0 || h <= 0) return null
    val key = TvNativeCardShadowKey(w, h, style.cornerPx, style.idleShadowBlurPx)
    return tvNativeCardShadows.getOrPut(key) {
        val sigma = style.idleShadowBlurPx / 2f
        val pad = ceil(sigma * 3f).toInt()
        val bitmap = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ALPHA_8)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            // Skia 把模糊半径换成 σ = 0.57735 × r + 0.5
            if (sigma > 0.5f) maskFilter = BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bitmap).drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat()), style.cornerPx, style.cornerPx, paint)
        TvNativeCardShadow(bitmap, pad)
    }
}

/** 番名块: 一两行字竖排; 透明度逐绘制指令乘 (两行不重叠, 不走离屏层). */
private class TvNativeLabelView(context: Context) : LinearLayout(context) {
    init {
        isDuplicateParentStateEnabled = true
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/** 定尺寸的文字: 字号 / 行高 / 字距由 [TvNativeTextStyle.applyTo] 设. */
@SuppressLint("AppCompatCustomView")
internal open class TvNativeTextView(context: Context) : TextView(context) {
    /**
     * 每行的固定高度 (px), 0 = 字体自然行高. 同 Compose M3 排版的 LineHeightStyle(Center, Trim.None) (见 [TvNativeLineHeightSpan]):
     * 每行恰好这么高, 字在行内居中. 不用 TextView.setLineHeight —— 它按主字体 (Roboto) 的度量折算行距, 中文行按回落字体 (更高) 的
     * 度量排, 行比设定的高, 定高的框里最后一行被裁掉一截.
     */
    var fixedLineHeightPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            text = text
        }

    init {
        includeFontPadding = false
        gravity = Gravity.TOP or Gravity.START
        ellipsize = TextUtils.TruncateAt.END
        setHorizontallyScrolling(false)
        marqueeRepeatLimit = -1
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        // 父类构造时就会调一次 (那时行高还是 0)
        super.setText(tvNativeWithLineHeight(text, fixedLineHeightPx), type)
    }

    /**
     * 首行的字体 (含回落字体) 比固定行高高出、被行框裁掉的上半截 (px; 不高出为 0). Compose 的 Text 在行高小于字体高度时按这个量在首行上方
     * 补内边距, 字形照字体本来的上沿排 (见 Compose TextLayout 的 lineHeightPaddings); 这里行框定死在行高里. 自成一行的字要与 Compose 版
     * 对齐时, 把视图往下挪这么多. 排版之后才有值.
     */
    val fontOverflowTopPx: Int
        get() {
            val laid = layout?.text as? Spanned ?: return 0
            val span = laid.getSpans(0, laid.length, TvNativeLineHeightSpan::class.java).firstOrNull() ?: return 0
            return span.overflowTop
        }

    override fun hasOverlappingRendering(): Boolean = false
}

/** 给整段字挂上 [heightPx] 的固定行高 (换掉已挂的; [heightPx] ≤ 0 = 摘掉). */
private fun tvNativeWithLineHeight(text: CharSequence?, heightPx: Int): CharSequence? {
    if (text.isNullOrEmpty()) return text
    val existing = (text as? Spanned)?.getSpans(0, text.length, TvNativeLineHeightSpan::class.java).orEmpty()
    if (heightPx <= 0 && existing.isEmpty()) return text
    if (existing.size == 1 && existing[0].heightPx == heightPx) {
        val spanned = text as Spanned
        if (spanned.getSpanStart(existing[0]) == 0 && spanned.getSpanEnd(existing[0]) == text.length) return text
    }
    return SpannableString(text).also { s ->
        for (span in existing) s.removeSpan(span)
        if (heightPx > 0) s.setSpan(TvNativeLineHeightSpan(heightPx), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }
}

/**
 * 固定行高, 算法同 Compose 的 LineHeightStyleSpan (Fixed 模式, Alignment.Center, Trim.None; M3 排版的默认): 按首行的字体度量
 * (含回落字体) 算一次目标 ascent / descent —— 与设定行高的差额上下各分一半 (差额为负时同样对半压) —— 之后每行都用这组值.
 * 每次排版从首行重算 (字号变了跟着变).
 */
private class TvNativeLineHeightSpan(val heightPx: Int) : LineHeightSpan {
    private var ascent = 0
    private var descent = 0

    /** 首行被行框裁掉的字体上半截 (见 [TvNativeTextView.fontOverflowTopPx]). */
    var overflowTop = 0
        private set

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        val current = fm.descent - fm.ascent
        if (current <= 0) return
        if (start == 0 || ascent == descent) {
            descent = fm.descent + ceil((heightPx - current) * 0.5f).toInt()
            ascent = descent - heightPx
            if (start == 0) overflowTop = (ascent - fm.ascent).coerceAtLeast(0)
        }
        fm.ascent = ascent
        fm.descent = descent
    }
}

/** 卡片的聚焦程度属性 (Property 版, 省掉 ObjectAnimator 按名字反射). */
private val TV_NATIVE_FOCUS_PROGRESS = object : FloatProperty<TvNativeCardView>("focusProgress") {
    override fun setValue(view: TvNativeCardView, value: Float) {
        view.focusProgress = value
    }

    override fun get(view: TvNativeCardView): Float = view.focusProgress
}

/** 「更多」卡中间圆钮的半径占封面短边的比例, 见 [TvNativeCoverView.more]. */
private const val TV_NATIVE_MORE_ICON_RADIUS = 0.16f

/** 「更多」卡加号 / 圆弧的笔画粗细占圆钮半径的比例. */
private const val TV_NATIVE_MORE_STROKE = 0.12f

/** 加号每一臂的长度占圆钮半径的比例. */
private const val TV_NATIVE_MORE_ARM = 0.42f

/** 加载中那道圆弧的半径占圆钮半径的比例. */
private const val TV_NATIVE_MORE_SPINNER_RADIUS = 0.55f

/** 玻璃的细亮边 (封面四周与圆钮) 宽多少 px. */
private const val TV_NATIVE_MORE_EDGE_PX = 1.5f

/** 高光从左上角往右下淡到透明, 走到对角线的这个比例为止. */
private const val TV_NATIVE_MORE_SHEEN_REACH = 0.6f

/** 玻璃底的模糊小图解多大 (长边 px): 糊成一片颜色就够, 小图省解码与内存. */
private const val TV_NATIVE_MORE_BLUR_LONG_EDGE_PX = 64

/** 玻璃底的模糊半径 (按小图的像素算). */
private const val TV_NATIVE_MORE_BLUR_RADIUS_PX = 8

/** 「更多」卡加载中的圆弧转一圈多久. */
private const val TV_NATIVE_MORE_SPIN_MILLIS = 900L
