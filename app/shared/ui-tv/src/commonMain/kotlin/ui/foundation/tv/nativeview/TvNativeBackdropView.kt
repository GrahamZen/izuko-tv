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
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.ui.foundation.TvNativeImages
import me.him188.ani.app.ui.foundation.theme.SubjectSeedColorCache
import me.him188.ani.app.ui.foundation.theme.subjectSeedColor
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_CROSSFADE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_EDGE_SEAM
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_PREFETCH_HANDOFF_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_PRESS_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_PRESS_DIM_HOLD_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_PRESS_DIM_IN_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_PRESS_DIM_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_UNDERLAY_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_OBSCURED_BACKDROP_LONG_EDGE_PX
import me.him188.ani.app.ui.foundation.tv.TvBackdropFade
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvHeroImagePrefetch
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.TvPolishFlags
import me.him188.ani.app.ui.foundation.tv.fadeInProfileOf
import me.him188.ani.app.ui.foundation.tv.fadeOutProfile

/**
 * 背景图要显示的内容: [url] 主图, [subjectId] 这张图属于哪个条目 (给放大转场登记、提前取色; null = 不是条目自己的图),
 * [underlayUrl] 应急垫底 (主图下载卡住时垫在下面的竖版封面, 半透明, 见 TvHeroMediaPipelineState.underlayUrl), [obscure] 打码,
 * [upgradeUrl] 剧照升档的原图 (视觉效果完整档 + 4K 界面的下一集剧照, 见 TvHeroMediaPipelineState.upgradeUrl): 主图上屏、停稳之后
 * 原地叠上去 (见 [TvNativeBackdropView.navigating]). 同一张主图只换它 (或 [seedUrl]) 时不算换图, 不交叉淡入.
 *
 * [seedUrl] = 提前取主色用的图, 即详情页铺的那张 (整部的横版背景, 见 TvHeroMediaPipelineState.seriesBackdropUrl): 默认就是主图;
 * 主图是继续观看的单集剧照时两张不同 —— 剧照算出的主色与详情页拿整部背景算的不一样, 进页会被换掉, 所以这时另解这一张取色;
 * null = 那张还不知道 (地址没解析出来), 先不取色.
 */
data class TvNativeBackdropTarget(
    val url: String,
    val subjectId: Int?,
    val underlayUrl: String? = null,
    val obscure: Boolean = false,
    val upgradeUrl: String? = null,
    val seedUrl: String? = url,
)

/**
 * 列表页 hero 的背景图层: 16:9 图按调用方给的框铺满、中心裁剪; 换图交叉淡入
 * [TV_BACKDROP_CROSSFADE_MILLIS] (FastOutSlowIn, 半路再换就从当前值接着走); 「按下即压暗」([triggerPressDim]); 遮罩 ([treatment]:
 * 左缘 / 下缘渐变, 画法与停点同放大转场用的 tvBackdropTreatmentPainter, 只画不透明段) 与左缘 / 下缘跨在图边上的实心压条 (图层缩放时边落在
 * 半个像素上, 抗锯齿那一列会漏出图色) 都在所有图之上画一次.
 *
 * 给详情页的放大转场登记这张图此刻在屏幕上的框 ([TvHeroZoomHandoff.publish], 调用方每次挪动 / 缩放图层后调 [publishZoom]), 图画出来后
 * 标记 [TvHeroZoomHandoff.markSourceLoaded] 并提前取主色 ([SubjectSeedColorCache]). 放大转场登记的始终是主图.
 *
 * 剧照升档 ([TvNativeBackdropTarget.upgradeUrl]): 主图上屏、停稳且不在导航 ([navigating]) 之后, 在同一格里叠上原图, 解码好后原地淡入
 * (同一构图, 看起来只是变清楚了); 换了目标就不再去取.
 *
 * 底下铺着整屏模糊背景时 (见 [feather], [zoomSource]): 左缘 / 下缘的渐变不盖遮罩色, 把图的边缘擦成透明露出那一层; 放大转场不从本层起.
 */
@SuppressLint("ViewConstructor")
class TvNativeBackdropView(
    context: Context,
    private val sketch: Sketch,
    private val scope: CoroutineScope,
) : FrameLayout(context) {
    private val slots = ArrayList<Slot>()
    private val treatmentPaint = TreatmentPaint()
    private val overlay = TreatmentOverlay(context)
    private var target: TvNativeBackdropTarget? = null
    private val windowXY = IntArray(2)

    /** 刚建出来 / 刚 [rebuild] 过: 下一次 [show] 直接换 (那一刻的目标直接画, 之后再换才交叉淡入). */
    private var showDirectlyNext = true

    /** 遮罩色 = 图层正下方的底色 (hero 的底, 见 TvPosterWallTone.heroColor). */
    var fadeColor: Int = AndroidColor.BLACK
        set(value) {
            if (field == value) return
            field = value
            overlay.invalidate()
            for (s in slots) {
                s.invalidateTreatment()
                s.applyDim()
            }
        }

    /** 压在图上的遮罩 (见 [TvBackdropTreatment]); 探索页在轮播与卡片两套几何之间逐帧插值时每帧换. */
    var treatment: TvBackdropTreatment? = null
        set(value) {
            if (field == value) return
            field = value
            treatmentPaint.invalidate()
            overlay.invalidate()
            for (s in slots) s.invalidateTreatment()
        }

    /**
     * 图叠在整屏模糊背景 (TvNativeWallBackdropView) 上、或不是遮罩色的底上 (探索页深色主题回轮播途中的卡片墙底色): 遮罩的左缘 / 下缘两条渐变与边上的压条不盖遮罩色, 改成按同样的深浅
     * 把图擦成透明 (DST_OUT), 露出底下那一层; 顶缘那条 (给顶栏垫底的压暗, 不是图的边) 照旧盖遮罩色. 擦要在离屏层里做 (不然连底下的模糊背景
     * 一起擦掉): 每一格 (一张图连同它的垫底 / 升档原图) 各自一层硬件离屏层, 遮罩画在层里 ([SlotTreatment]). 层里的内容只在图解好、遮罩变了时
     * 重画; 交叉淡入 (格的透明度) 与按下即压暗 (层合成时的颜色滤镜, 见 [Slot.applyDim]) 都是合成的时候算, 不重画.
     */
    var feather: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            for (s in slots) s.applyFeather()
            overlay.invalidate()
            // 本层透明度的合成方式跟着换 (见 hasOverlappingRendering)
            invalidate()
        }

    /** 羽化时各格各自一层离屏层, 本层的透明度逐格乘上去 (合成的时候算), 不为它把各格再合进一层离屏. */
    override fun hasOverlappingRendering(): Boolean = !feather

    /**
     * 本层登记成放大转场的来源. 底下铺着整屏模糊背景时进详情页从那张整屏图起 (见 TvNativeWallBackdropView), 本层不登记: 置 false 时撤掉
     * 已登记的.
     */
    var zoomSource: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) publishZoom() else slots.forEach { TvHeroZoomHandoff.retract(it.owner) }
        }

    /** Compose 根视图: 放大转场的框按它的坐标登记 (null = 按窗口坐标). */
    var composeRoot: View? = null
    private val rootXY = IntArray(2)

    /** 按下即压暗的当前值 (0..[TV_BACKDROP_PRESS_DIM_ALPHA]). */
    private var dim = 0f
    private var dimJob: Job? = null
    private var dimAnimator: ValueAnimator? = null

    /** 「目标已换、展示还没跟上」: 压暗放开前要等它变 false (见 [triggerPressDim]). */
    var dimming: Boolean = false

    /**
     * 此刻在不在导航 (卡片区在滚动 / 方向键按住). 剧照升档 ([TvNativeBackdropTarget.upgradeUrl]) 在主图上屏 [TV_BACKDROP_UPGRADE_SETTLE_MILLIS]
     * 之后还要等它为 false 才去取原图: 原图首次解码 + 往 GPU 传大纹理不落在导航里. 读快照状态 (页面的 TvScrollActivity / TvNavKeyTracker),
     * 由宿主给; 默认不导航.
     */
    var navigating: () -> Boolean = { false }

    init {
        clipChildren = false
        clipToPadding = false
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 当前主图 (交叉淡入的目标); null = 没有图. */
    val currentTarget: TvNativeBackdropTarget? get() = target

    /**
     * 换图. 交叉淡入不分视觉效果档: 淡入期间旧图一直在, 撑到新图有像素 —— 当帧撤掉旧图的话, 新图下载解码那段 hero 没有背景
     * (见 tvContentSwapAnimated). [crossfade] = false, 或刚建出来 / 刚 [rebuild] 过的头一次调用, 直接换. 同一张图重复调用是空操作.
     */
    fun show(target: TvNativeBackdropTarget?, crossfade: Boolean = true) {
        val animated = crossfade && !showDirectlyNext
        showDirectlyNext = false
        if (target == this.target) return
        this.target = target
        if (target == null) {
            slots.forEach { it.stopUpgrade() }
            if (!animated) clearSlots() else fadeSlots { 0f }
            overlay.visibility = if (slots.any { it.alpha > 0f }) VISIBLE else INVISIBLE
            return
        }
        val existing = slots.firstOrNull { it.target.url == target.url && it.target.obscure == target.obscure }
        existing?.retarget(target)
        val slot = existing ?: Slot(target).also { newSlot ->
            slots.add(newSlot)
            addView(newSlot.frame, indexOfChild(overlay), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            newSlot.load()
        }
        // 升档只给当前这张: 被换下去的那几格不再去取原图 (已经叠上的留着, 跟着那一格淡出)
        for (s in slots) if (s !== slot) s.stopUpgrade()
        slot.scheduleUpgrade()
        if (!animated) {
            slots.filter { it !== slot }.forEach { removeSlot(it) }
            slot.fadeTo(1f, animated = false)
        } else {
            fadeSlots { if (it === slot) 1f else 0f }
        }
        overlay.visibility = VISIBLE
        publishZoom()
    }

    /**
     * 各格淡到 [alphaOf] 给的值. fadeTo 会取消该格在途的动画, 取消当场回调的结束监听可能把刚起步 (透明度还是 0) 的格摘掉:
     * 按拷贝遍历, 已摘掉的跳过.
     */
    private inline fun fadeSlots(alphaOf: (Slot) -> Float) {
        for (s in slots.toList()) {
            if (s in slots) s.fadeTo(alphaOf(s), animated = true)
        }
    }

    /**
     * 整个重建: 丢掉所有图, 下一次 [show] 直接出现, 不与上一张交叉淡入 —— 调用方在图层看不见的那一刻换来源、
     * 换位置时用, 交叉淡入里没褪完的上一张会随图层重新亮起来一起露出来.
     */
    fun rebuild() {
        clearSlots()
        target = null
        showDirectlyNext = true
    }

    /**
     * 按下即压暗 (调用方在真实目标换了时调, Prime Video 式的即时反馈): 当前图压到 [TV_BACKDROP_PRESS_DIM_ALPHA] ([TV_BACKDROP_PRESS_DIM_IN_MILLIS], 线性),
     * 至少保持 [TV_BACKDROP_PRESS_DIM_HOLD_MILLIS], 再等 [dimming] 变 false (新图已换上) 用 [TV_BACKDROP_PRESS_DIM_OUT_MILLIS] 放开;
     * 再按就从当前值重新开始. 受 TvPolishFlags.pressDim 控制.
     */
    fun triggerPressDim() {
        if (!TvPolishFlags.pressDim) return
        dimJob?.cancel()
        dimJob = scope.launch {
            val start = SystemClock.uptimeMillis()
            animateDim(TV_BACKDROP_PRESS_DIM_ALPHA, TV_BACKDROP_PRESS_DIM_IN_MILLIS.toLong())
            val remaining = TV_BACKDROP_PRESS_DIM_HOLD_MILLIS - (SystemClock.uptimeMillis() - start)
            if (remaining > 0) delay(remaining)
            while (dimming) delay(16)
            animateDim(0f, TV_BACKDROP_PRESS_DIM_OUT_MILLIS.toLong())
        }
    }

    private suspend fun animateDim(to: Float, millis: Long) {
        val from = dim
        dimAnimator?.cancel()
        suspendCancellableCoroutine { cont ->
            val animator = ValueAnimator.ofFloat(from, to).apply {
                duration = millis
                interpolator = LinearInterpolator()
                addUpdateListener {
                    dim = it.animatedValue as Float
                    onDimChanged()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                })
            }
            dimAnimator = animator
            cont.invokeOnCancellation { animator.cancel() }
            animator.start()
        }
    }

    /** 按下即压暗换了深浅: 羽化时改各格离屏层合成时的颜色滤镜 (层里不重画), 否则重画遮罩. */
    private fun onDimChanged() {
        if (feather) {
            for (s in slots) s.applyDim()
        } else {
            overlay.invalidate()
        }
    }

    private var dimFilterColor = 0
    private var dimFilter: PorterDuffColorFilter? = null

    /**
     * 按下即压暗作为离屏层合成时的颜色滤镜: 遮罩色按 [dim] 的浓度盖在层上, 只盖层里有内容的地方 (SRC_ATOP, 擦成透明的边缘照旧透明);
     * 不压时 null. 同一浓度复用同一个.
     */
    private fun currentDimFilter(): PorterDuffColorFilter? {
        if (dim <= 0f) return null
        val color = withAlpha(fadeColor, dim)
        if (color != dimFilterColor || dimFilter == null) {
            dimFilterColor = color
            dimFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        }
        return dimFilter
    }

    /**
     * 登记放大转场的来源: 主图此刻在窗口里的框 (含本层的缩放与平移), 遮罩声明.
     * 调用方每次改了本层的位置 / 缩放后调.
     */
    fun publishZoom() {
        if (!zoomSource) return
        val t = target ?: return
        val subjectId = t.subjectId ?: return
        val slot = slots.lastOrNull { it.target.url == t.url } ?: return
        if (width == 0 || height == 0 || !isAttachedToWindow) return
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
        val rect = Rect(left, top, left + width * scaleX, top + height * scaleY)
        TvHeroZoomHandoff.publish(slot.owner, subjectId, t.url, rect, Color.Transparent, treatment)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) publishZoom()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        slots.forEach { TvHeroZoomHandoff.retract(it.owner) }
    }

    private fun clearSlots() {
        slots.toList().forEach { removeSlot(it) }
    }

    private fun removeSlot(slot: Slot) {
        slot.dispose()
        slots.remove(slot)
        removeView(slot.frame)
    }

    /**
     * 一张图 (连同它的应急垫底与升档原图), 交叉淡入的一格. 自己的透明度在 [frame] 上 (垫底、主图、原图三张重叠, 淡入淡出时走离屏层,
     * 各按透明度叠会互相透出来; 羽化时本格本来就是一层离屏层, 见 [feather]). 同一张主图换了别的字段 (升档目标) 时这一格原地沿用 ([retarget]).
     */
    private inner class Slot(target: TvNativeBackdropTarget) {
        var target: TvNativeBackdropTarget = target
            private set
        val owner = Any()
        val frame = FrameLayout(context)
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

        /** 垫底在建格时定下 (之后同一张主图换来的目标不再加 / 撤垫底). */
        private val underlayUrl: String? = target.underlayUrl
        private val underlay: ImageView? = underlayUrl?.let { ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }

        /** 羽化时画在本格离屏层里最上面的遮罩 (不羽化时不画, 遮罩由图层统一画). */
        private val treatment = SlotTreatment(context)

        /** 本格离屏层合成时用的画笔: 按下即压暗是它的颜色滤镜. */
        private val layerPaint = Paint()
        var alpha = 0f
            private set
        private var animator: ValueAnimator? = null
        private var loadJob: Job? = null

        /** 主图已上屏: 剧照升档从这一刻起计静止时间. */
        private var mainLoaded = false

        /** 升档原图 (叠在主图上, 解码好了淡入); 没在升档时 null. */
        private var upgrade: ImageView? = null

        /** 已经去取 (或已叠上) 的原图地址. */
        private var upgradeRequested: String? = null

        /** 已经另解来取色的那张图 (见 [TvNativeBackdropTarget.seedUrl]). */
        private var seedRequested: String? = null
        private var upgradeJob: Job? = null
        private var upgradeAnimator: ValueAnimator? = null

        /** 这一格是不是背景图此刻的目标. */
        private val isCurrent: Boolean
            get() = this@TvNativeBackdropView.target?.let { it.url == target.url && it.obscure == target.obscure } == true

        init {
            underlay?.let { frame.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
            frame.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            frame.addView(treatment, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            frame.alpha = 0f
            applyFeather()
        }

        /** 按图层此刻羽化与否设本格: 羽化时本格走硬件离屏层、遮罩画在层里; 不羽化时不开层. */
        fun applyFeather() {
            treatment.visibility = if (feather) VISIBLE else INVISIBLE
            layerPaint.colorFilter = if (feather) currentDimFilter() else null
            frame.setLayerType(if (feather) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, if (feather) layerPaint else null)
        }

        /** 按下即压暗换了深浅: 羽化时改本格离屏层合成时的颜色滤镜 (层里的内容不重画). */
        fun applyDim() {
            if (!feather) return
            val filter = currentDimFilter()
            if (layerPaint.colorFilter === filter) return
            layerPaint.colorFilter = filter
            frame.setLayerPaint(layerPaint)
        }

        /** 遮罩声明 / 遮罩色换了: 层里的遮罩重画. */
        fun invalidateTreatment() {
            treatment.invalidate()
        }

        fun load() {
            if (width == 0 || height == 0) {
                // 还没量过 (刚建出来就换了图): 等第一次布局出尺寸再发请求, 请求按图层的框取整
                addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, orr: Int, ob: Int) {
                        if (r - l == 0 || b - t == 0) return
                        removeOnLayoutChangeListener(this)
                        if (slots.contains(this@Slot)) load()
                    }
                })
                return
            }
            val obscureEdge = if (target.obscure) TV_OBSCURED_BACKDROP_LONG_EDGE_PX else null
            underlay?.let { view ->
                TvNativeImages.loadBackdrop(
                    sketch, view, underlayUrl!!, width, height, alpha = TV_BACKDROP_UNDERLAY_ALPHA, obscureLongEdgePx = obscureEdge,
                )
            }
            loadJob = scope.launch {
                // 接管在途预热 (同 TvBackdropImage): 这张图正被预热时先等它跑完, 最多等到预热开始后 TV_BACKDROP_PREFETCH_HANDOFF_MILLIS
                val prefetch = TvHeroImagePrefetch.inFlight(target.url)
                val left = TV_BACKDROP_PREFETCH_HANDOFF_MILLIS - (prefetch?.elapsedMillis ?: 0)
                if (prefetch != null && left > 0) withTimeoutOrNull(left) { prefetch.job.join() }
                TvNativeImages.loadBackdrop(sketch, image, target.url, width, height, obscureLongEdgePx = obscureEdge) { bitmap ->
                    onLoaded(bitmap)
                    mainLoaded = true
                    if (isCurrent) scheduleUpgrade()
                }
            }
        }

        /**
         * 同一张主图换来的新目标 (升档目标、取色用的图可能变了): 升档目标变了就撤掉旧的升档, 由 [show] 按新的重新安排;
         * 取色用的图晚到 (主图已上屏) 时当场去取色.
         */
        fun retarget(newTarget: TvNativeBackdropTarget) {
            val upgradeChanged = newTarget.upgradeUrl != target.upgradeUrl
            val seedChanged = newTarget.seedUrl != target.seedUrl
            target = newTarget
            if (upgradeChanged) dropUpgrade()
            if (seedChanged && mainLoaded) precomputeSeed(mainBitmap = null)
        }

        /**
         * 安排剧照升档 (只给当前这张, 见 [show]): 主图已上屏、有升档目标、还没去取时, 等主图上屏后静止 [TV_BACKDROP_UPGRADE_SETTLE_MILLIS]
         * 且 [navigating] 为 false, 再去取原图. 已经在等 / 已经取了就什么都不做.
         */
        fun scheduleUpgrade() {
            val url = target.upgradeUrl?.takeIf { it != target.url && !target.obscure } ?: return
            if (!mainLoaded || upgradeRequested == url || upgradeJob?.isActive == true) return
            upgradeJob = scope.launch {
                delay(TV_BACKDROP_UPGRADE_SETTLE_MILLIS)
                snapshotFlow { navigating() }.first { !it }
                showUpgrade(url)
            }
        }

        /** 这一格不再是目标: 还在等的升档取消, 还没露出来的原图撤掉; 已经叠上 (在淡入 / 已满) 的留着, 跟着这一格淡出. */
        fun stopUpgrade() {
            upgradeJob?.cancel()
            upgradeJob = null
            if (upgrade != null && upgradeAnimator == null) dropUpgrade()
        }

        private fun showUpgrade(url: String) {
            upgradeRequested = url
            val view = upgrade ?: ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }.also {
                upgrade = it
                // 叠在主图上、层里的遮罩下面
                frame.addView(it, frame.indexOfChild(treatment), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            TvNativeImages.loadBackdrop(sketch, view, url, width, height, alpha = 0f) { bitmap ->
                if (upgrade !== view) return@loadBackdrop
                // 先预传 GPU: 淡入的头一帧不当场上传整张纹理
                bitmap.prepareToDraw()
                upgradeAnimator = ValueAnimator.ofFloat(view.alpha, 1f).apply {
                    duration = TV_BACKDROP_UPGRADE_FADE_MILLIS.toLong()
                    interpolator = LinearInterpolator()
                    addUpdateListener { view.alpha = it.animatedValue as Float }
                    start()
                }
            }
        }

        private fun dropUpgrade() {
            upgradeJob?.cancel()
            upgradeJob = null
            upgradeAnimator?.cancel()
            upgradeAnimator = null
            upgrade?.let {
                TvNativeImages.clear(it)
                frame.removeView(it)
            }
            upgrade = null
            upgradeRequested = null
        }

        private fun onLoaded(bitmap: Bitmap) {
            val subjectId = target.subjectId ?: return
            // 返回缩回撤层前要等列表页 hero 这张图画得出来 (见 TvHeroZoomHandoff.listReady)
            TvHeroZoomHandoff.markSourceLoaded(subjectId, target.url)
            publishZoom()
            precomputeSeed(bitmap)
        }

        /**
         * 提前取主色写进 [SubjectSeedColorCache], 点进详情页第一帧就是动态色: 与详情页共用同一个取色函数, 取同一张图 (见 [TvNativeBackdropTarget.seedUrl]) ——
         * 图或算法不一致的话, 进页后详情页自己算出的色会把它换掉, 观感是变一下. 取色用的图就是主图时拿刚解出的 [mainBitmap];
         * 不是 (继续观看的单集剧照) 时按主图同样的解码参数另解那一张.
         */
        private fun precomputeSeed(mainBitmap: Bitmap?) {
            val subjectId = target.subjectId ?: return
            if (target.obscure || SubjectSeedColorCache[subjectId] != null) return
            val seed = target.seedUrl ?: return
            if (seed == target.url) {
                val imageBitmap = mainBitmap?.asImageBitmap() ?: return
                scope.launch { SubjectSeedColorCache[subjectId] = imageBitmap.subjectSeedColor() }
                return
            }
            if (seedRequested == seed) return
            seedRequested = seed
            val w = width
            val h = height
            scope.launch {
                val bitmap = TvNativeImages.fetchBackdrop(sketch, context, seed, w, h) ?: return@launch
                if (SubjectSeedColorCache[subjectId] == null) SubjectSeedColorCache[subjectId] = bitmap.asImageBitmap().subjectSeedColor()
            }
        }

        fun fadeTo(to: Float, animated: Boolean) {
            animator?.cancel()
            if (!animated || alpha == to) {
                alpha = to
                frame.alpha = to
                if (to == 0f && this@Slot.target != this@TvNativeBackdropView.target) post { if (alpha == 0f) removeSlot(this) }
                return
            }
            val from = alpha
            animator = ValueAnimator.ofFloat(from, to).apply {
                duration = TV_BACKDROP_CROSSFADE_MILLIS.toLong()
                interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
                addUpdateListener {
                    alpha = it.animatedValue as Float
                    frame.alpha = alpha
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (alpha == 0f && this@Slot !== slots.lastOrNull { s -> s.target == this@TvNativeBackdropView.target }) {
                            removeSlot(this@Slot)
                        }
                    }
                })
                start()
            }
        }

        fun dispose() {
            animator?.cancel()
            loadJob?.cancel()
            dropUpgrade()
            TvNativeImages.clear(image)
            underlay?.let { TvNativeImages.clear(it) }
            TvHeroZoomHandoff.retract(owner)
        }
    }

    /** 图层最上面的遮罩 (不羽化时画; 羽化时遮罩画在各格的离屏层里, 这里什么都不画). */
    private inner class TreatmentOverlay(context: Context) : View(context) {
        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            if (feather) return
            treatmentPaint.draw(canvas, width, height, erase = false, pressDim = true)
        }
    }

    /** 羽化时画在各格离屏层里最上面的遮罩: 左缘 / 下缘把本格擦成透明; 按下即压暗不在这里 (层合成时的颜色滤镜, 见 [Slot.applyDim]). */
    private inner class SlotTreatment(context: Context) : View(context) {
        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            treatmentPaint.draw(canvas, width, height, erase = true, pressDim = false)
        }
    }

    /**
     * 遮罩的画法 (图层的 [TreatmentOverlay] 与各格的 [SlotTreatment] 共用): 按下即压暗 (遮罩色的实色矩形) → 遮罩声明的顶 / 左 / 下三条渐变
     * (只画不透明段) → 左缘 / 下缘跨在图边上的实心压条. 画笔只在尺寸或声明变了时重建.
     */
    private inner class TreatmentPaint {
        private val dimPaint = Paint()
        private val seamPaint = Paint()
        private val topPaint = Paint()
        private val leftPaint = Paint()
        private val bottomPaint = Paint()
        private var built: Pair<TvBackdropTreatment?, Long>? = null
        private val seamPx = resources.displayMetrics.density * TV_BACKDROP_EDGE_SEAM.value
        private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

        /** 遮罩声明换了: 下一次画时重建画笔. */
        fun invalidate() {
            built = null
        }

        /**
         * 画到 [width] × [height] 的 [canvas] 上. [erase] = 左缘 / 下缘两条渐变与压条按同样的深浅把下面的 (连同压暗与顶缘那条) 擦成透明,
         * 否则盖遮罩色; [pressDim] = 画按下即压暗.
         */
        fun draw(canvas: Canvas, width: Int, height: Int, erase: Boolean, pressDim: Boolean) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val tr = treatment
            val key = tr to (width.toLong() shl 32 or height.toLong())
            if (built != key) {
                buildShaders(tr, w, h)
                built = key
            }
            val color = fadeColor
            val edgeMode = if (erase) eraseMode else null
            leftPaint.xfermode = edgeMode
            bottomPaint.xfermode = edgeMode
            seamPaint.xfermode = edgeMode
            if (pressDim && dim > 0f) {
                dimPaint.color = withAlpha(color, dim)
                canvas.drawRect(0f, 0f, w, h, dimPaint)
            }
            if (tr != null) {
                if (tr.dim.alpha > 0f) {
                    dimPaint.color = tr.dim.toArgb()
                    canvas.drawRect(0f, 0f, w, h, dimPaint)
                }
                tr.top?.takeIf { it.maxAlpha > 0f }?.let { canvas.drawRect(0f, 0f, w, h * it.end, topPaint) }
                tr.left?.takeIf { it.maxAlpha > 0f }?.let { canvas.drawRect(0f, 0f, w * it.end, h, leftPaint) }
                tr.bottom?.takeIf { it.maxAlpha > 0f }?.let { canvas.drawRect(0f, h * it.start, w, h, bottomPaint) }
            }
            seamPaint.color = color
            canvas.drawRect(-seamPx, 0f, seamPx, h + seamPx, seamPaint)
            canvas.drawRect(-seamPx, h - seamPx, w + seamPx, h + seamPx, seamPaint)
        }

        private fun buildShaders(tr: TvBackdropTreatment?, w: Float, h: Float) {
            tr?.top?.let { topPaint.shader = gradient(fadeOutProfile, it, 0f, h * it.start, 0f, h * it.end) }
            tr?.left?.let { leftPaint.shader = gradient(fadeOutProfile, it, w * it.start, 0f, w * it.end, 0f) }
            tr?.bottom?.let { bottomPaint.shader = gradient(fadeInProfileOf(it.smoothness), it, 0f, h * it.start, 0f, h * it.end) }
        }

        /** 停点均匀分布 (同 tvBackdropTreatmentPainter 里 Brush 的均匀重载): 第 i 个停点 = 遮罩色 × maxAlpha × profile[i]. */
        private fun gradient(profile: FloatArray, fade: TvBackdropFade, x0: Float, y0: Float, x1: Float, y1: Float): Shader {
            val colors = IntArray(profile.size) { i -> fade.color.copy(alpha = fade.maxAlpha * profile[i]).toArgb() }
            return LinearGradient(x0, y0, x1, y1, colors, null, Shader.TileMode.CLAMP)
        }
    }
}

private fun withAlpha(color: Int, alpha: Float): Int =
    AndroidColor.argb((alpha * 255).toInt().coerceIn(0, 255), AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))

/**
 * 剧照升档: 主图上屏后至少静止这么久才去取原图. 一格一格慢慢走 (约 1 秒一张) 也不该每张都去取: 0.8 秒时 Shield 上实测第二轮
 * janky 11%, 原图的解码与上传落进了下一次按键的滚动里.
 */
internal const val TV_BACKDROP_UPGRADE_SETTLE_MILLIS = 1_500L

/** 剧照升档: 原图解码好后原地淡入的时长 (线性). */
internal const val TV_BACKDROP_UPGRADE_FADE_MILLIS = 400
