/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import com.github.panpf.sketch.BitmapImage
import com.github.panpf.sketch.Image
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.asImage
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.transform.TransformResult
import com.github.panpf.sketch.transform.Transformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.network.TmdbTitleLogo
import me.him188.ani.app.ui.foundation.TvNativeImages
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBackdropSample
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropCropSampler
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropMeasurePixels
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropMeasureSample
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 量 [bitmap] (解好的 logo) 的色调. 缩小到长边 160 以内再量, 零点几毫秒. */
fun tvTitleLogoTone(bitmap: Bitmap): TvTitleLogoTone {
    val pixels = tvBackdropMeasurePixels(bitmap)
    var opaque = 0
    var black = 0
    var white = 0
    for (p in pixels) {
        if ((p ushr 24) <= 128) continue
        opaque++
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        if (max(r, max(g, b)) < 64) {
            black++
        } else if (min(r, min(g, b)) > 200) {
            white++
        }
    }
    if (opaque == 0) return TvTitleLogoTone(0f, 0f)
    return TvTitleLogoTone(black.toFloat() / opaque, white.toFloat() / opaque)
}

/**
 * 把 logo 里无彩色的部分翻到另一头, 彩色的部分原样 (逐像素, 见 [tvTitleLogoFlipColor]): [lightText] (压在浅色字的底上, 即深色底) 时黑翻成白、
 * 深灰翻成浅灰, 本来就浅的不动; 深色字反过来. 透明度不变. 返回新的位图.
 */
fun flipTvTitleLogo(bitmap: Bitmap, lightText: Boolean): Bitmap {
    val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
    val source = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val width = source.width
    val height = source.height
    // getPixels / createBitmap 都是未预乘的颜色: 只动颜色, 透明度原样
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val p = pixels[i]
        if (p ushr 24 != 0) pixels[i] = tvTitleLogoFlipColor(p, lightText)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * 标题 logo 按文字的底翻色 (Sketch 的变换: 在解码线程上做, 结果按带它的键进内存缓存). [lightText] = 显示处的字是浅色 (深色底).
 * 要不要翻由 logo 自己的色调定 ([TvTitleLogoTone.needsFlip]), 要翻的见 [flipTvTitleLogo]; 不用翻的原样 (结果的 transformeds 里没有它).
 * [force] = 不看色调一律翻 (已经按背景判过看不清, 见 tvTitleLogoBackdropLook): 有颜色的 logo 里黑白灰的部分也翻.
 */
data class TvTitleLogoFlip(val lightText: Boolean, val force: Boolean = false) : Transformation {
    override val key: String get() = if (force) "TvTitleLogoFlip($lightText,force)" else "TvTitleLogoFlip($lightText)"

    override fun transform(requestContext: RequestContext, input: Image): TransformResult? {
        val bitmap = (input as? BitmapImage)?.bitmap ?: return null
        if (!force && !tvTitleLogoTone(bitmap).needsFlip(lightText)) return null
        return TransformResult(flipTvTitleLogo(bitmap, lightText).asImage(), key)
    }

    override fun toString(): String = key
}

/**
 * 量 [bitmap] (解好的原样 logo) 的格子颜色与色调 (见 [TvTitleLogoGrid]): 分 [TV_TITLE_LOGO_GRID_ROWS] 行、列数按宽高比, 每格按面积取平均.
 * 要读全部像素 (几十万个), 放在后台线程.
 */
fun tvTitleLogoGrid(bitmap: Bitmap): TvTitleLogoGrid {
    val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
    val source = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val w = source.width
    val h = source.height
    val rows = TV_TITLE_LOGO_GRID_ROWS.coerceAtMost(h).coerceAtLeast(1)
    val cols = (w.toFloat() / h * rows).roundToInt().coerceIn(1, minOf(TV_TITLE_LOGO_GRID_MAX_COLS, w))
    val n = cols * rows
    val sumA = FloatArray(n)
    val sumR = FloatArray(n)
    val sumG = FloatArray(n)
    val sumB = FloatArray(n)
    val count = IntArray(n)
    val line = IntArray(w)
    for (y in 0 until h) {
        source.getPixels(line, 0, w, 0, y, w, 1)
        val row = (y * rows / h) * cols
        for (x in 0 until w) {
            val p = line[x]
            val cell = row + x * cols / w
            val a = (p ushr 24) / 255f
            sumA[cell] += a
            sumR[cell] += ((p shr 16) and 0xFF) * a
            sumG[cell] += ((p shr 8) and 0xFF) * a
            sumB[cell] += (p and 0xFF) * a
            count[cell]++
        }
    }
    val coverage = FloatArray(n) { if (count[it] == 0) 0f else sumA[it] / count[it] }
    val colors = IntArray(n) { i ->
        val a = sumA[i]
        if (a <= 0f) {
            0xFF000000.toInt()
        } else {
            (0xFF shl 24) or ((sumR[i] / a).roundToInt().coerceIn(0, 255) shl 16) or
                ((sumG[i] / a).roundToInt().coerceIn(0, 255) shl 8) or (sumB[i] / a).roundToInt().coerceIn(0, 255)
        }
    }
    return TvTitleLogoGrid(cols, rows, coverage, colors, tvTitleLogoTone(source))
}

/**
 * 标题 logo 的格子颜色 (见 [tvTitleLogoGrid]) 按图片路径缓存, 量与判 (见 [tvTitleLogoBackdropLook]) 都在后台做、结果回主线程.
 * 缓存只在主线程读写.
 */
actual object TvTitleLogoGrids {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = object : LinkedHashMap<String, TvTitleLogoGrid>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TvTitleLogoGrid>?): Boolean = size > MAX_ENTRIES
    }

    fun peek(logo: TmdbTitleLogo): TvTitleLogoGrid? = cache[logo.filePath]

    /** 量 [bitmap] ([logo] 的原样图), 量完在主线程回调 [onReady] (同时记进缓存). */
    fun measure(logo: TmdbTitleLogo, bitmap: Bitmap, onReady: (TvTitleLogoGrid) -> Unit): Job = scope.launch {
        val grid = tvTitleLogoGrid(bitmap)
        withContext(Dispatchers.Main) {
            cache[logo.filePath] = grid
            onReady(grid)
        }
    }

    /**
     * [logo] 的格子颜色: 量过的直接给; 没量过就按显示大小 [size] 取原图 (不翻色, 与列表页 hero 原样那张同一个缓存键, 见 TvNativeImages.fetchLogo)
     * 在后台量. 取不到图为 null. 在主线程调.
     */
    actual suspend fun obtain(sketch: Sketch, context: Context, logo: TmdbTitleLogo, size: TvTitleLogoSize): TvTitleLogoGrid? {
        peek(logo)?.let { return it }
        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            TvNativeImages.fetchLogo(sketch, context, logo.url(size.widthPx), size.widthPx, size.heightPx) { if (cont.isActive) cont.resume(it) }
        } ?: return null
        val grid = withContext(Dispatchers.Default) { tvTitleLogoGrid(bitmap) }
        cache[logo.filePath] = grid
        return grid
    }

    /** 后台取背景色 ([background], 与 [grid] 同样分格) 并判 [grid] 压在上面的样子, 主线程回调. */
    fun decide(grid: TvTitleLogoGrid, background: () -> IntArray, onReady: (TvTitleLogoBackdropLook) -> Unit): Job = scope.launch {
        val look = tvTitleLogoBackdropLook(grid, background())
        withContext(Dispatchers.Main) { onReady(look) }
    }

    private const val MAX_ENTRIES = 64
}

/** 这种样子对应的图片变换 (原样为 null). */
fun TvHeroTitleLogoLook.transformation(): TvTitleLogoFlip? = flipLightText?.let { TvTitleLogoFlip(lightText = it, force = force) }

/**
 * 详情页首屏 (与放大 / 缩回层) 上标题 logo 的样子, 按 logo 与背景图记: 详情页按自己的清晰背景图、logo 在首屏上的位置判 (见 [request];
 * 判据同列表页, 见 tvTitleLogoBackdropLook), 与列表页 hero (模糊背景, logo 也在别处) 各判各的; 进出详情页途中各处读的是同一份.
 * 判出柔光的照原样画 (柔光压在清晰图上像一团雾). 判任务挂在全局, 首屏占位页与真页交接时不断. 只在主线程读写.
 */
actual object TvTitleLogoDetailsLooks {
    /** 背景图迟迟不来、没法按图判时的样子: 照白字底, 按色调翻 (见 [TvTitleLogoTone.needsFlip]). */
    val DEFAULT = TvHeroTitleLogoLook(flipLightText = true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val looks = mutableStateMapOf<String, TvHeroTitleLogoLook>()
    private val decided = HashSet<String>()
    private val deciding = HashSet<String>()

    /** 详情页背景图量好的取样 (按图片地址, 见 [putBackdrop]); 只留最近几张. */
    private val backdrops = MutableStateFlow<Map<String, TvBackdropSample>>(emptyMap())
    private val measuring = HashSet<String>()

    private fun key(logo: TmdbTitleLogo, backdropUrl: String) = logo.filePath + "\n" + backdropUrl

    /** [logo] 压在背景图 [backdropUrl] 上判出来的样子 (快照读); 还没判为 null. */
    actual fun of(logo: TmdbTitleLogo, backdropUrl: String): TvHeroTitleLogoLook? = looks[key(logo, backdropUrl)]

    /** 详情页首屏的背景图 [url] 解好了 ([bitmap]): 后台缩小取样 (长边 [TV_DETAILS_LOGO_BACKDROP_SAMPLE_PX]), 留给判 logo 用. 同一张只量一次. */
    actual fun putBackdrop(url: String, bitmap: ImageBitmap) = putBackdrop(url, bitmap.asAndroidBitmap())

    fun putBackdrop(url: String, bitmap: Bitmap) {
        if (url in backdrops.value || !measuring.add(url)) return
        scope.launch {
            try {
                val sample = withContext(Dispatchers.Default) { tvBackdropMeasureSample(bitmap, TV_DETAILS_LOGO_BACKDROP_SAMPLE_PX) }
                backdrops.value = (backdrops.value - url + (url to sample)).entries.toList()
                    .takeLast(TV_DETAILS_LOGO_BACKDROP_MAX).associate { it.toPair() }
            } finally {
                measuring.remove(url)
            }
        }
    }

    /**
     * 判 [logo] (按 [size] 显示在 [logoRect], 根坐标) 压在背景图 [backdropUrl] (中心裁剪铺满 [backdropRect]) 上的样子, 判完记下 (见 [of]);
     * [leftScrim] = 图左压着的那层渐变 (见 TvBackdropTreatment.left; 取样时照样压上). 原样看不清的同时记进 [TvTitleLogoUnreadable].
     * 判过 / 在判的不重复. 背景图 [TV_DETAILS_LOGO_BACKDROP_WAIT_MILLIS] 内没解好先记 [DEFAULT] (logo 先按它显示), 解好了再判;
     * logo 的原图取不到也记 [DEFAULT] (由显示那边的图片加载失败换回文字标题).
     */
    actual fun request(
        sketch: Sketch,
        context: Context,
        logo: TmdbTitleLogo,
        size: TvTitleLogoSize,
        backdropUrl: String,
        logoRect: Rect,
        backdropRect: Rect,
        leftScrim: TvBackdropFade?,
    ) {
        val k = key(logo, backdropUrl)
        if (k in decided || !deciding.add(k)) return
        scope.launch {
            try {
                val sample = withTimeoutOrNull(TV_DETAILS_LOGO_BACKDROP_WAIT_MILLIS) { awaitBackdrop(backdropUrl) } ?: run {
                    if (k !in looks) looks[k] = DEFAULT
                    withTimeoutOrNull(TV_DETAILS_LOGO_BACKDROP_GIVE_UP_MILLIS) { awaitBackdrop(backdropUrl) }
                } ?: return@launch
                val grid = TvTitleLogoGrids.obtain(sketch, context, logo, size)
                if (grid == null) {
                    if (k !in looks) looks[k] = DEFAULT
                    return@launch
                }
                val sampler = tvBackdropCropSampler(
                    sample,
                    RectF(logoRect.left, logoRect.top, logoRect.right, logoRect.bottom),
                    RectF(backdropRect.left, backdropRect.top, backdropRect.right, backdropRect.bottom),
                )
                val look = withContext(Dispatchers.Default) {
                    val background = sampler.sample(grid.cols, grid.rows)
                    if (leftScrim != null) applyLeftScrim(background, grid.cols, logoRect, backdropRect, leftScrim)
                    tvTitleLogoBackdropLook(grid, background)
                }
                looks[k] = TvHeroTitleLogoLook(look.flipLightText, force = true)
                decided += k
                if (look != TvTitleLogoBackdropLook.Original) TvTitleLogoUnreadable.mark(logo)
            } finally {
                deciding.remove(k)
            }
        }
    }

    private suspend fun awaitBackdrop(url: String): TvBackdropSample = backdrops.first { url in it }.getValue(url)

    /**
     * 按格压上图左的渐变 [scrim] (与 tvBackdropFadeFromBlackStops 同一条曲线: [TvBackdropFade.start] 之前 maxAlpha, 到 [TvBackdropFade.end]
     * smoothstep 淡到 0; 坐标是 [frame] 宽的比例). [background] 是 [rect] 分成 [cols] 列的格子, 原地改.
     */
    private fun applyLeftScrim(background: IntArray, cols: Int, rect: Rect, frame: Rect, scrim: TvBackdropFade) {
        val color = scrim.color.toArgb()
        for (i in background.indices) {
            val x = rect.left + (i % cols + 0.5f) / cols * rect.width
            val f = (((x - frame.left) / frame.width - scrim.start) / (scrim.end - scrim.start)).coerceIn(0f, 1f)
            val alpha = scrim.maxAlpha * (1f - f * f * (3f - 2f * f))
            if (alpha > 0f) background[i] = mixArgb(background[i], color, alpha)
        }
    }
}

/** 详情页背景图取样的长边 (px): 清晰图上 logo 底下的细节比模糊图多, 比列表页模糊图取得密 (一格约屏宽的 1/320). */
private const val TV_DETAILS_LOGO_BACKDROP_SAMPLE_PX = 320

/** 详情页背景图取样留几张. */
private const val TV_DETAILS_LOGO_BACKDROP_MAX = 4

/** 详情页背景图等这么久还没解好, logo 先按 [TvTitleLogoDetailsLooks.DEFAULT] 显示. */
private const val TV_DETAILS_LOGO_BACKDROP_WAIT_MILLIS = 800L

/** 再等这么久还没有就不判了. */
private const val TV_DETAILS_LOGO_BACKDROP_GIVE_UP_MILLIS = 30_000L

actual fun TvHeroTitleLogoLook.tvLogoTransformation(): Transformation? = transformation()

actual fun tvTitleLogoLightTransformation(): Transformation? = TvTitleLogoFlip(lightText = true)
