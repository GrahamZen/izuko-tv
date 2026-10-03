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
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import me.him188.ani.app.ui.foundation.TvNativeImages
import me.him188.ani.app.ui.foundation.theme.SubjectSeedColorCache
import me.him188.ani.app.ui.foundation.theme.subjectSeedColor
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_PREFETCH_HANDOFF_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_HERO_SPLIT_STOPS
import me.him188.ani.app.ui.foundation.tv.TvHeroImagePrefetch
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.mixArgb
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 海报墙底下整屏背景要铺的图: [url] = 条目的横版背景图 (TMDB), 没有时是它的竖版封面; [subjectId] = 这张图属于哪个条目 (给放大转场登记、
 * 提前取色; null = 不是哪个条目自己的图, 如还没聚焦过卡片时的默认图); [sharp] = 能变清晰 (竖版封面裁成整屏太糊, 只铺模糊版).
 */
@Immutable
data class TvNativeWallBackdropTarget(
    val url: String,
    val subjectId: Int?,
    val sharp: Boolean,
)

/**
 * 海报墙底下的整屏背景 (新番时间表): 平时铺聚焦那部背景图的**模糊版** (同 tvOS 的模糊底), 对焦时 ([sharpness]) 叠上清晰原图.
 *
 * 模糊层: 同一个地址只解一张小图 (多大、糊多少按 [blur], 见 tvBackdropBlurSpec), 解码线程上模糊, 拉伸铺满 (见 TvNativeImages.loadBlurredBackdrop).
 * 不做实时模糊: Shield 是 Android 11, 没有 RenderEffect; 索尼有, 但整屏每帧模糊是低端机上最贵的常驻 GPU 开销之一. 整屏压暗 ([maskColor])
 * 按每张图自己的亮度加深, 保证上面的字 ([textColor]) 看得清 (见 tvBackdropMaskAlpha); hero 态铺模糊背景时 (深色主题) 照 Apple TV 只分两档
 * ([brightMaskAlpha]). 压暗用颜色滤镜画在同一次绘制里, 不另开一层. 换图时新图解好后叠在旧图上淡入 ([TV_WALL_BACKDROP_CROSSFADE_MILLIS]; [crossfade] = false 时当场换), 满了再撤掉
 * 下面的; 新图没解好之前旧图一直在; 连着换时可以等上一张淡满再换 ([coalesceSwaps]). 各层是直接改透明度的 ImageView (没有背景, 透明度逐绘制指令乘, 不开离屏层).
 *
 * 清晰层: 同一张图按原尺寸解 (TMDB w1280, 与详情页同一个内存缓存键), 叠在模糊层上, 透明度 = [sharpness] (解好之前恒 0; 透明度 0 的层
 * 不画). 换图后停在这张上 [TV_WALL_BACKDROP_SHARP_DELAY_MILLIS] 才解 ([prepareSharp] 当场解), 解好先传到 GPU ([Bitmap.prepareToDraw]);
 * 只留当前这一张. 清晰层满了时下面的模糊层整个不画.
 *
 * 清晰图解好就给详情页的放大转场登记整屏框 (全屏对全屏, 图原地不动) 并标记加载过、提前取色; 登记留到换条目 / 离开窗口 —— 从详情页返回、
 * 缩回落地后要凭它判列表页就绪 (TvHeroZoomHandoff.listReady). 调用方在清晰层满了之后才进详情页 (见 TvNativeGridPageView 的对焦一节).
 */
@SuppressLint("ViewConstructor")
class TvNativeWallBackdropView(
    context: Context,
    private val sketch: Sketch,
    private val scope: CoroutineScope,
) : FrameLayout(context) {
    private val slots = ArrayList<BlurSlot>()
    private val sharpImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
    }
    private var target: TvNativeWallBackdropTarget? = null

    /** 清晰层上此刻解好的那张的地址 (null = 没有). */
    private var sharpUrl: String? = null

    /** 清晰图的请求 (排着延后 / 等预热 / 已发出). 换条目时作废. */
    private var sharpJob: Job? = null

    /** 清晰图的请求已经发出 (不只是排着延后). */
    private var sharpRequested = false

    /** 放大转场的登记身份; [published] = 登记着. */
    private val owner = Any()
    private var published = false
    private val windowXY = IntArray(2)
    private val rootXY = IntArray(2)

    /** Compose 根视图: 放大转场的框按它的坐标登记 (null = 按窗口坐标). */
    var composeRoot: View? = null

    /**
     * 整屏压暗 (页面底色 + 起步的透明度): 每张模糊图按自己的亮度在这份透明度上往深里加 (见 tvBackdropMaskAlpha); 清晰层起步时也压
     * 同一份 (见 [applySharpMask]).
     */
    var maskColor: Int = Color.TRANSPARENT
        set(value) {
            if (field == value) return
            field = value
            refreshMasks()
        }

    /** 压在背景上的主要文字 (卡片番名) 的颜色: 压暗按它与背景的对比度加深. */
    var textColor: Int = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            refreshMasks()
        }

    /**
     * 浅色字 (深色主题) 的压暗分两档 (见 tvBackdropTwoLevelMaskAlpha, hero 态铺模糊背景时用): 平时只压起步那一份 ([maskColor] 的透明度),
     * 图的主色很亮时压到这么深 (0..1). NaN = 按文字对比度加深 (见 tvBackdropMaskAlpha, 新番时间表). 深色字照旧按对比度.
     */
    var brightMaskAlpha: Float = Float.NaN
        set(value) {
            if (field == value || (field.isNaN() && value.isNaN())) return
            field = value
            refreshMasks()
        }

    /** 模糊程度 (设置里的那一档, 见 tvBackdropBlurSpec). 换了就按新的程度重解当前这张, 解好照常淡入换上. */
    internal var blur: TvBackdropBlurSpec = tvBackdropBlurSpec(TvBackdropBlurLevel.Medium)
        set(value) {
            if (field == value) return
            field = value
            target?.let { addSlot(it, direct = false) }
        }

    /** 卡片的封面框: 竖版封面的模糊版取同一档缩略图 (下载缓存命中). */
    var coverWidthPx: Int = 0
    var coverHeightPx: Int = 0

    /** 模糊层换图交叉淡入; false (流畅档) = 解好当场换. */
    var crossfade: Boolean = true

    /**
     * 连着换图时整屏同时只有一张在淡入: 换图时上一张还在淡入, 就等它淡满再换, 这期间又换了只换最后那张; 没在淡入 (单次按键) 当场换.
     * hero 态铺模糊背景时开 (hero 图也在同时交叉淡入). 目标 ([currentTarget]) 当场就换, 对焦与放大登记照常按新目标走; 按下确认键
     * ([prepareSharp]) 时等着的当场开始换.
     */
    var coalesceSwaps: Boolean = false

    /** 有一次换图在等上一张淡满 (见 [coalesceSwaps]): 淡满时换成那时的目标. */
    private var swapPending = false

    /** 当前目标的清晰图解好了. */
    var onSharpReady: (() -> Unit)? = null

    /** 模糊层最上面露着的那张换了, 或压暗变了 ([sample] 的结果跟着变). */
    var onShownChanged: (() -> Unit)? = null

    /** 底边 (见 [setFloor]): 本层坐标, NaN = 整屏铺满. */
    private var floorTopPx = Float.NaN
    private var floorBandPx = 0f
    private var floorColor = Color.TRANSPARENT
    private var floorOpacity = 1f
    private val floorBandPaint = Paint()
    private val floorPaint = Paint()

    /**
     * 只铺到 [topPx] (本层坐标) 为止: 往下 [bandPx] 内渐变成 [color], 再往下整片是它 (探索页热门轮播: 模糊背景只铺在轮播那块, 往下过渡到
     * 卡片墙的底色; 渐变同主壳的分界带, smoothstep). [topPx] 为 NaN = 整屏铺满. [opacity] = 底边这层的浓度 (从整屏铺满过渡过来时淡入);
     * 对焦时清晰层盖上来, 底边跟着淡掉 (对焦成整屏清晰图). 底边满着时下面那截不画模糊层 (裁掉).
     */
    fun setFloor(topPx: Float, bandPx: Float, color: Int, opacity: Float = 1f) {
        if (topPx.isNaN() && floorTopPx.isNaN()) return
        if (topPx == floorTopPx && bandPx == floorBandPx && color == floorColor && opacity == floorOpacity) return
        if (bandPx != floorBandPx || color != floorColor) {
            val n = TV_POSTER_WALL_HERO_SPLIT_STOPS
            val colors = IntArray(n + 1) { i ->
                val f = i / n.toFloat()
                val a = ((color ushr 24) * (f * f * (3f - 2f * f))).roundToInt()
                (a shl 24) or (color and 0xFFFFFF)
            }
            val positions = FloatArray(n + 1) { it / n.toFloat() }
            floorBandPaint.shader = LinearGradient(0f, 0f, 0f, bandPx.coerceAtLeast(1f), colors, positions, Shader.TileMode.CLAMP)
        }
        floorTopPx = topPx
        floorBandPx = bandPx
        floorColor = color
        floorOpacity = opacity
        invalidate()
    }

    /** 底边此刻的浓度: [floorOpacity] 乘清晰层没盖住的那份. */
    private fun floorAlpha(): Float = floorOpacity.coerceIn(0f, 1f) * (1f - sharpImage.alpha.coerceIn(0f, 1f))

    override fun dispatchDraw(canvas: Canvas) {
        val top = floorTopPx
        val opacity = floorAlpha()
        if (top.isNaN() || opacity <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val a = (opacity * 255f).roundToInt()
        floorBandPaint.alpha = a
        floorPaint.color = floorColor
        floorPaint.alpha = ((floorColor ushr 24) * opacity).roundToInt()
        val bottom = top + floorBandPx
        val w = width.toFloat()
        if (opacity >= 1f) {
            val save = canvas.save()
            canvas.clipRect(0f, 0f, w, bottom)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        } else {
            super.dispatchDraw(canvas)
        }
        if (floorBandPx > 0f) {
            canvas.save()
            canvas.translate(0f, top)
            canvas.drawRect(0f, 0f, w, floorBandPx, floorBandPaint)
            canvas.restore()
        }
        if (bottom < height) canvas.drawRect(0f, bottom, w, height.toFloat(), floorPaint)
    }

    /** 清晰层的透明度 (0..1, 对焦程度). 清晰图解好之前不显示. */
    var sharpness: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            applySharpness()
        }

    val currentTarget: TvNativeWallBackdropTarget? get() = target

    /** 当前目标的清晰图已解好. */
    val sharpReady: Boolean get() = target?.let { it.sharp && it.url == sharpUrl } == true

    init {
        addView(sharpImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 换图 (同一个目标重复调用是空操作). 换了地址 = 模糊层解新图淡入 (连着换时见 [coalesceSwaps]), 清晰图作废 (停下来再解新的);
     * 换了条目 = 放大登记作废. null = 淡出成页面底色. [replace] = 本层此刻看不见 (调用方换了一路内容): 旧图当场撤掉, 不留着跟新图交叉淡入.
     */
    fun show(target: TvNativeWallBackdropTarget?, replace: Boolean = false) {
        val old = this.target
        if (target == old) return
        this.target = target
        if (old?.url != target?.url || old?.subjectId != target?.subjectId) retractZoom()
        if (old?.url != target?.url || target?.sharp != true) clearSharp()
        if (old?.url != target?.url) {
            if (replace) {
                swapPending = false
                for (s in slots.toList()) removeSlot(s)
                if (target != null) addSlot(target, direct = false)
            } else if (target == null) {
                swapPending = false
                for (s in slots.toList()) s.fadeOut()
            } else if (coalesceSwaps && slots.any { it.fadingIn }) {
                // 上一张还在淡入 (连着换): 还没露面的作废, 等它淡满再换
                slots.lastOrNull()?.takeIf { !it.shown }?.let { removeSlot(it) }
                swapPending = true
            } else {
                swapPending = false
                addSlot(target, direct = false)
            }
        }
        if (target?.sharp == true && sharpUrl != target.url) scheduleSharp()
        applySharpness()
    }

    /** 等着的那次换图现在开始: 换成此刻的目标 (最上面那张已经是它就不换). */
    private fun startPendingSwap() {
        if (!swapPending) return
        swapPending = false
        val t = target ?: return
        if (slots.lastOrNull { it.shown && !it.leaving }?.target?.url == t.url) return
        addSlot(t, direct = false)
    }

    /** 当场开始解清晰图 (按下确认键 / 长按 / 恢复点开的状态), 不等停留; 等着的换图也当场开始. 已解好 / 已发出请求时是空操作. */
    fun prepareSharp() {
        startPendingSwap()
        val t = target ?: return
        if (!t.sharp || t.url == sharpUrl || sharpRequested) return
        sharpJob?.cancel()
        sharpJob = scope.launch { loadSharp(t) }
    }

    // ------------------------------------------------------------------
    // 模糊层
    // ------------------------------------------------------------------

    private fun addSlot(target: TvNativeWallBackdropTarget, direct: Boolean) {
        // 最上面那张还没解好 (没露过面): 直接作废, 不必等它
        slots.lastOrNull()?.takeIf { !it.shown }?.let { removeSlot(it) }
        // 连着换得比淡入还快: 撤掉最上面那张在淡入的 (它露得最少), 只留垫底的与一张在淡入的
        while (slots.size >= TV_WALL_BACKDROP_MAX_SLOTS) removeSlot(slots.last())
        val slot = BlurSlot(target, direct)
        slots.add(slot)
        slot.image.visibility = if (sharpCovers()) INVISIBLE else VISIBLE
        addView(slot.image, indexOfChild(sharpImage), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        slot.load()
    }

    private fun removeSlot(slot: BlurSlot) {
        slot.dispose()
        slots.remove(slot)
        removeView(slot.image)
    }

    /** [slot] 淡满了: 下面的都被它盖住, 撤掉. */
    private fun removeBelow(slot: BlurSlot) {
        val i = slots.indexOf(slot)
        if (i <= 0) return
        for (s in slots.subList(0, i).toList()) removeSlot(s)
    }

    /** 压暗色 / 文字色换了 (换主题): 各张按量好的亮度重算压暗, 不重解. */
    private fun refreshMasks() {
        for (s in slots) s.applyMask()
        sharpMaskAlpha = -1
        applySharpMask()
        onShownChanged?.invoke()
    }

    /**
     * 条目 [subjectId] 的模糊图此刻在窗口坐标 [rect] 处的取色器 ([TvBackdropSampler]: 按格子取颜色, 已混上压暗); 最上面露着的那张不是这一部
     * (还没解好 / 已经换走) 时 null. 只在主线程上记下位置与压暗, 取色本身可以放到后台. 只算模糊层, 不算清晰层与上面的 hero 图 (标题那块
     * hero 图已经羽化成透明).
     */
    fun sampler(subjectId: Int, rect: RectF): TvBackdropSampler? {
        val slot = slots.lastOrNull { it.shown && !it.leaving } ?: return null
        if (slot.target.subjectId != subjectId) return null
        val sample = slot.sample ?: return null
        if (width == 0 || height == 0 || !isAttachedToWindow) return null
        getLocationInWindow(windowXY)
        // 图按 CENTER_CROP 铺满本层
        val scale = max(width.toFloat() / sample.width, height.toFloat() / sample.height)
        val drawnW = sample.width * scale
        val drawnH = sample.height * scale
        return TvBackdropSampler(
            sample, RectF(rect),
            left = windowXY[0] + (width - drawnW) / 2, top = windowXY[1] + (height - drawnH) / 2, drawnW = drawnW, drawnH = drawnH,
            mask = slot.maskAlpha.coerceAtLeast(0) / 255f, maskColor = maskColor or OPAQUE,
        )
    }

    /** 同 [sampler], 当场取 [cols] × [rows] 格. */
    fun sample(subjectId: Int, rect: RectF, cols: Int, rows: Int): IntArray? = sampler(subjectId, rect)?.sample(cols, rows)

    /** 最上面那张模糊图压着的透明度 (0..255), 还没解好是 -1 (测试看压暗的深浅). */
    internal val topMaskAlpha: Int get() = slots.lastOrNull()?.maskAlpha ?: -1

    /** 此刻清晰层对应的那张模糊图压着的透明度 (0..255); 它还没解好就按起步那一份. */
    private fun currentMaskAlpha(): Int =
        slots.lastOrNull { it.target.url == target?.url && it.maskAlpha >= 0 }?.maskAlpha ?: (maskColor ushr 24)

    /** 模糊层的一张. [direct] = 解好直接出现, 不淡入. */
    private inner class BlurSlot(val target: TvNativeWallBackdropTarget, private val direct: Boolean) {
        /** 这张按哪一档解 (建的时候的 [blur]). */
        private val spec = blur

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }

        /** 解好上过屏 (淡入中 / 满了 / 在淡出). */
        var shown = false
            private set

        /** 在淡出 (整屏背景撤掉了). */
        var leaving = false
            private set

        /** 在淡入 (还没满). */
        var fadingIn = false
            private set
        private var animator: ValueAnimator? = null
        private var job: Job? = null

        /** 这张图上浅色字 / 深色字最难看清那一处的亮度 (解好时量, 见 tvBackdropWorstLuminance); NaN = 还没量. */
        private var worstForLightText = Float.NaN
        private var worstForDarkText = Float.NaN

        /** 这张图主色的感知亮度 (解好时量, 见 tvBackdropLuminosity, 两档压暗用). */
        private var luminosity = 0f

        /** 此刻压着的透明度 (0..255), -1 = 还没解好. */
        var maskAlpha = -1
            private set

        /** 解好时量的缩小图 (见 [sample]); null = 还没解好. */
        var sample: TvBackdropSample? = null
            private set

        /** 按量好的亮度与此刻的压暗色 / 文字色算压多深, 设成颜色滤镜. */
        fun applyMask() {
            if (worstForLightText.isNaN()) return
            val textLuminance = tvRelativeLuminance(textColor)
            val maskLuminance = tvRelativeLuminance(maskColor)
            val lightText = textLuminance >= maskLuminance
            val base = (maskColor ushr 24) / 255f
            val a = if (lightText && !brightMaskAlpha.isNaN()) {
                tvBackdropTwoLevelMaskAlpha(luminosity, base, brightMaskAlpha)
            } else {
                tvBackdropMaskAlpha(if (lightText) worstForLightText else worstForDarkText, maskLuminance, textLuminance, base)
            }
            val alpha = (a * 255f).roundToInt()
            if (alpha == maskAlpha) return
            maskAlpha = alpha
            image.colorFilter = if (alpha <= 0) null else PorterDuffColorFilter((maskColor and 0xFFFFFF) or (alpha shl 24), PorterDuff.Mode.SRC_ATOP)
        }

        private fun measure(bitmap: Bitmap) {
            val measured = tvBackdropMeasureSample(bitmap)
            sample = measured
            val pixels = measured.pixels
            worstForLightText = tvBackdropWorstLuminance(pixels, lightText = true)
            worstForDarkText = tvBackdropWorstLuminance(pixels, lightText = false)
            luminosity = tvBackdropLuminosity(pixels)
        }

        fun load() {
            job = scope.launch {
                awaitSize()
                awaitPrefetch(target.url)
                TvNativeImages.loadBlurredBackdrop(
                    sketch, image, target.url, width, height,
                    longEdgePx = spec.longEdgePx,
                    blurRadiusPx = spec.radiusPx,
                    coverWidthPx = coverWidthPx,
                    coverHeightPx = coverHeightPx,
                ) { bitmap -> if (bitmap != null) onLoaded(bitmap) else onFailed() }
            }
        }

        private fun onLoaded(bitmap: Bitmap) {
            if (this !in slots || shown) return
            // 上面已经有更新的一张在等: 这张不必再露面
            if (slots.last() !== this) {
                removeSlot(this)
                return
            }
            // 先压好再露面 (淡入从透明度 0 起, 没压过的图不会闪一下); 清晰层起步压的那份跟着这张走
            measure(bitmap)
            applyMask()
            sharpMaskAlpha = -1
            applySharpMask()
            shown = true
            fadeTo(1f, animated = crossfade && !direct) {
                removeBelow(this)
                // 连着换时等着的那张 (见 coalesceSwaps)
                startPendingSwap()
            }
            onShownChanged?.invoke()
        }

        /** 解不出来: 是当前这张就连下面的一起淡掉 (不拿别的条目的图顶着), 否则旧图留着. */
        private fun onFailed() {
            if (this !in slots) return
            val top = slots.last() === this
            removeSlot(this)
            if (top) for (s in slots.toList()) s.fadeOut()
        }

        fun fadeOut() {
            shown = true
            leaving = true
            fadeTo(0f, animated = crossfade) { removeSlot(this) }
        }

        private fun fadeTo(to: Float, animated: Boolean, onEnd: () -> Unit) {
            animator?.cancel()
            fadingIn = false
            if (!animated || image.alpha == to) {
                image.alpha = to
                onEnd()
                return
            }
            fadingIn = to == 1f
            animator = ValueAnimator.ofFloat(image.alpha, to).apply {
                duration = TV_WALL_BACKDROP_CROSSFADE_MILLIS.toLong()
                interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
                addUpdateListener { image.alpha = it.animatedValue as Float }
                addListener(
                    tvNativeEndListener {
                        fadingIn = false
                        onEnd()
                    },
                )
                start()
            }
        }

        fun dispose() {
            fadingIn = false
            animator?.cancel()
            job?.cancel()
            TvNativeImages.clear(image)
        }
    }

    // ------------------------------------------------------------------
    // 清晰层
    // ------------------------------------------------------------------

    private fun clearSharp() {
        sharpJob?.cancel()
        sharpJob = null
        sharpRequested = false
        sharpUrl = null
        TvNativeImages.clear(sharpImage)
    }

    /** 停在这张上一小段再解清晰图: 连着换图时不白解. */
    private fun scheduleSharp() {
        if (sharpJob != null) return
        val t = target ?: return
        sharpJob = scope.launch {
            delay(TV_WALL_BACKDROP_SHARP_DELAY_MILLIS)
            loadSharp(t)
        }
    }

    private suspend fun loadSharp(t: TvNativeWallBackdropTarget) {
        sharpRequested = true
        awaitSize()
        awaitPrefetch(t.url)
        // 透明度由 applySharpness 管: 请求先按 0 挂上, 解好再按对焦程度显示
        TvNativeImages.loadBackdrop(sketch, sharpImage, t.url, width, height, alpha = 0f) { bitmap ->
            if (target?.url == t.url) onSharpLoaded(bitmap)
        }
    }

    private fun onSharpLoaded(bitmap: Bitmap) {
        val t = target ?: return
        sharpUrl = t.url
        // 先传到 GPU (渲染线程上异步传): 对焦那一帧不用再现传一整张 1280 图
        bitmap.prepareToDraw()
        val subjectId = t.subjectId
        if (subjectId != null && SubjectSeedColorCache[subjectId] == null) {
            // 提前取色: 与详情页共用同一个取色函数 (同 TvNativeBackdropView)
            val imageBitmap = bitmap.asImageBitmap()
            scope.launch { SubjectSeedColorCache[subjectId] = imageBitmap.subjectSeedColor() }
        }
        applySharpness()
        onSharpReady?.invoke()
    }

    /** 清晰层满了: 下面的模糊层整个被盖住. */
    private fun sharpCovers(): Boolean = sharpReady && sharpness >= 1f

    private fun applySharpness() {
        val ready = sharpReady
        val alpha = if (ready) sharpness else 0f
        // 底边跟着清晰层淡 (画在本层的 dispatchDraw 里)
        if (alpha != sharpImage.alpha && !floorTopPx.isNaN()) invalidate()
        sharpImage.alpha = alpha
        applySharpMask()
        val covered = sharpCovers()
        for (s in slots) s.image.visibility = if (covered) INVISIBLE else VISIBLE
        if (ready && !published) publishZoom()
    }

    /** 清晰层此刻压着的那份压暗的不透明度 (0..255), 没变就不重设. */
    private var sharpMaskAlpha = -1

    /**
     * 清晰层起步时与模糊层一样暗 (同一份压暗, 按这张图算过的深浅, 见 [currentMaskAlpha]), 随对焦程度提亮, 满了不压: 叠上去的一刻亮度不跳,
     * 看着是对焦而不是换了张亮图. 压暗用颜色滤镜画在同一次绘制里 (不另开一层); 对焦满了撤掉滤镜.
     */
    private fun applySharpMask() {
        val alpha = (currentMaskAlpha() * (1f - sharpness.coerceIn(0f, 1f))).roundToInt()
        if (alpha == sharpMaskAlpha) return
        sharpMaskAlpha = alpha
        sharpImage.colorFilter = if (alpha <= 0) {
            null
        } else {
            PorterDuffColorFilter((maskColor and 0xFFFFFF) or (alpha shl 24), PorterDuff.Mode.SRC_ATOP)
        }
    }

    // ------------------------------------------------------------------
    // 放大转场的登记
    // ------------------------------------------------------------------

    /**
     * 登记放大转场的来源: 清晰图此刻在根视图里的框 (整屏), 并标记这张图加载过 (返回缩回撤层前要等列表页这张图画得出来, 见
     * TvHeroZoomHandoff.listReady). 清晰图没解好 / 不是条目自己的图时不登记.
     */
    private fun publishZoom() {
        val t = target ?: return
        val subjectId = t.subjectId ?: return
        if (!sharpReady || width == 0 || height == 0 || !isAttachedToWindow) return
        TvHeroZoomHandoff.markSourceLoaded(subjectId, t.url)
        getLocationInWindow(windowXY)
        val root = composeRoot
        if (root != null) {
            root.getLocationInWindow(rootXY)
        } else {
            rootXY[0] = 0
            rootXY[1] = 0
        }
        val left = (windowXY[0] - rootXY[0]).toFloat()
        val top = (windowXY[1] - rootXY[1]).toFloat()
        TvHeroZoomHandoff.publish(owner, subjectId, t.url, Rect(left, top, left + width, top + height))
        published = true
    }

    private fun retractZoom() {
        TvHeroZoomHandoff.retract(owner)
        published = false
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            published = false
            applySharpness()
        }
    }

    /** 本层挪了 (探索页轮播的模糊背景随列表滚动): 已登记的整屏框跟着重新登记, 放大从此刻的位置起. */
    override fun setTranslationY(translationY: Float) {
        if (translationY == this.translationY) return
        super.setTranslationY(translationY)
        if (published) publishZoom()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applySharpness()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        retractZoom()
    }

    // ------------------------------------------------------------------

    /** 等第一次布局出尺寸 (刚建出来就换了图): 请求按图层的框取整. */
    private suspend fun awaitSize() {
        if (width > 0 && height > 0) return
        suspendCancellableCoroutine { cont ->
            val listener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, orr: Int, ob: Int) {
                    if (r - l == 0 || b - t == 0) return
                    removeOnLayoutChangeListener(this)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            addOnLayoutChangeListener(listener)
            cont.invokeOnCancellation { removeOnLayoutChangeListener(listener) }
        }
    }

    /** 这张图正被预热 (hero 流水线给详情页预取) 时先等它跑完, 最多等到预热开始后 [TV_BACKDROP_PREFETCH_HANDOFF_MILLIS] (同 TvNativeBackdropView). */
    private suspend fun awaitPrefetch(url: String) {
        val prefetch = TvHeroImagePrefetch.inFlight(url) ?: return
        val left = TV_BACKDROP_PREFETCH_HANDOFF_MILLIS - prefetch.elapsedMillis
        if (left > 0) withTimeoutOrNull(left) { prefetch.job.join() }
    }
}

/**
 * 按帧推进的一段过渡 (进度 0 → 1, 经 [interpolator] 交给 [onUpdate]): 每帧最多推进 [TV_NATIVE_TWEEN_MAX_STEP_NANOS]. 主线程卡住一下之后
 * (长按时收藏菜单那个新窗口建窗口、头一回组合) 不跳到终点, 从卡住前的地方接着走 —— ValueAnimator 按墙钟时间算进度, 卡了多久就跳过多少,
 * 400ms 的过渡被卡掉大半, 看着像瞬间换了. 时长乘系统的动画时长缩放 (开发者选项里关了动画就当场到位). [cancel] 之后不回调 [onEnd].
 */
internal class TvNativeFrameTween(
    private val durationMillis: Int,
    private val interpolator: TimeInterpolator,
    private val onUpdate: (Float) -> Unit,
    private val onEnd: (() -> Unit)?,
) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private var elapsedNanos = 0L
    private var lastFrameNanos = -1L

    var running: Boolean = false
        private set

    fun start() {
        running = true
        choreographer.postFrameCallback(this)
    }

    fun cancel() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos >= 0) elapsedNanos += (frameTimeNanos - lastFrameNanos).coerceIn(0L, TV_NATIVE_TWEEN_MAX_STEP_NANOS)
        lastFrameNanos = frameTimeNanos
        val totalNanos = durationMillis * 1_000_000f * ValueAnimator.getDurationScale()
        val t = if (totalNanos <= 0f) 1f else (elapsedNanos / totalNanos).coerceIn(0f, 1f)
        onUpdate(interpolator.getInterpolation(t))
        if (t >= 1f) {
            running = false
            onEnd?.invoke()
        } else {
            choreographer.postFrameCallback(this)
        }
    }
}

/** [TvNativeFrameTween] 每帧最多推进多少 (≈ 60Hz 下两帧): 掉一两帧照常追上, 再长的卡顿不计. */
private const val TV_NATIVE_TWEEN_MAX_STEP_NANOS = 34_000_000L

/** 动画正常走完才回调 [onEnd] (被 cancel 时不回调: cancel 会当场回调 onAnimationEnd). */
internal fun tvNativeEndListener(onEnd: (() -> Unit)?): Animator.AnimatorListener = object : AnimatorListenerAdapter() {
    private var cancelled = false

    override fun onAnimationCancel(animation: Animator) {
        cancelled = true
    }

    override fun onAnimationEnd(animation: Animator) {
        if (!cancelled) onEnd?.invoke()
    }
}

/** 模糊层换图的交叉淡入. */
const val TV_WALL_BACKDROP_CROSSFADE_MILLIS = 400

/** 对焦时卡片 (点开时连同顶栏) 淡没、倒放时淡回的时长. */
const val TV_WALL_BACKDROP_CARDS_MILLIS = 200

/** 背景变清晰 / 变回模糊的时长: 比卡片淡没长, 卡片先让开, 图再慢慢对上焦; 点开时它走完才进详情页. */
const val TV_WALL_BACKDROP_SHARPEN_MILLIS = 400

/** 背景变清晰的缓动: 两头慢中间快. 标准缓动前段最快, 头几十毫秒就冒出大半张图, 看着像瞬间换了图. */
internal val TV_WALL_BACKDROP_SHARPEN_EASING = PathInterpolator(0.45f, 0f, 0.55f, 1f)

/** 换图后停多久才解清晰图 (按确认键 / 长按时当场解). */
internal const val TV_WALL_BACKDROP_SHARP_DELAY_MILLIS = 150L

/** 点开时清晰图还没解好, 最多等多久 (卡片照样先淡; 等不到就不对焦, 直接进详情页). */
internal const val TV_WALL_BACKDROP_OPEN_WAIT_MILLIS = 300L

/** 模糊层同时最多几张 (垫底的 + 在淡入的). */
private const val TV_WALL_BACKDROP_MAX_SLOTS = 3

private const val OPAQUE = -0x1000000 // 0xFF000000

/**
 * 模糊背景上一块区域的取色器 (见 [TvNativeWallBackdropView.sampler]): 记下了图、区域 ([rect], 窗口坐标) 与图此刻铺在哪 ([left] / [top] /
 * [drawnW] / [drawnH], 窗口坐标) 和压暗 ([mask] 浓度, [maskColor] 不透明), 不再碰视图, 可以在后台线程上取.
 */
class TvBackdropSampler internal constructor(
    private val image: TvBackdropSample,
    private val rect: RectF,
    private val left: Float,
    private val top: Float,
    private val drawnW: Float,
    private val drawnH: Float,
    private val mask: Float,
    private val maskColor: Int,
) {
    /** 区域分成 [cols] × [rows] 格, 每格中心的颜色 (双线性, 混上压暗; 不透明 ARGB). */
    fun sample(cols: Int, rows: Int): IntArray = IntArray(cols * rows) { i ->
        val x = rect.left + (i % cols + 0.5f) / cols * rect.width()
        val y = rect.top + (i / cols + 0.5f) / rows * rect.height()
        val c = image.at((x - left) / drawnW, (y - top) / drawnH)
        if (mask > 0f) mixArgb(c, maskColor, mask) else c
    }
}

/** 图 [sample] 中心裁剪铺满 [frame] 时, 区域 [rect] (与 [frame] 同一坐标系) 的取色器 (不压暗). */
internal fun tvBackdropCropSampler(sample: TvBackdropSample, rect: RectF, frame: RectF): TvBackdropSampler {
    val scale = max(frame.width() / sample.width, frame.height() / sample.height)
    val drawnW = sample.width * scale
    val drawnH = sample.height * scale
    return TvBackdropSampler(
        sample, RectF(rect),
        left = frame.left + (frame.width() - drawnW) / 2, top = frame.top + (frame.height() - drawnH) / 2, drawnW = drawnW, drawnH = drawnH,
        mask = 0f, maskColor = 0,
    )
}
