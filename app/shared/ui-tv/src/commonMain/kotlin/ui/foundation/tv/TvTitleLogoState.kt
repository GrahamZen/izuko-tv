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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.isSpecified
import com.github.panpf.sketch.BitmapImage
import com.github.panpf.sketch.Image
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.asImage
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.transform.TransformResult
import com.github.panpf.sketch.transform.Transformation
import kotlinx.coroutines.CancellationException
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
import me.him188.ani.app.data.models.preference.TvTitleLogoDisplay
import me.him188.ani.app.data.models.preference.TvTitleLogoLanguage
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbTitleLogo
import me.him188.ani.app.data.network.toTmdbLanguage
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.TvNativeImages
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBackdropSample
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropCropSampler
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropMeasurePixels
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropMeasureSample
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 条目的标题 logo 查到哪一步了: [logo] = 查到的 logo (null = 没有或还没查完), [pending] = 还没查完 (查完才知道有没有). 设置里关了时
 * 两者都是「没有」.
 */
@Immutable
data class TvTitleLogoLookup(val logo: TmdbTitleLogo?, val pending: Boolean) {
    companion object {
        val None = TvTitleLogoLookup(null, pending = false)
    }
}

/**
 * hero 标题用的 logo (见 ThemeSettings.tvTitleLogoDisplay; 作品原版的 logo, 见 [TmdbImageService.getTitleLogo]) 与查没查完. 读 TMDB 服务的进程内热表
 * (快照订阅, 查完自动重组), 没查过就查一次: 对应表里的条目不要原名, 表里没有的按 [originalName] 搜 (条目信息到了才有, 到了再查).
 * 列表页 hero 流水线会替邻居先查好 (见 [prefetchTvTitleLogo]), 走过去时多半已在热表里. 设置为「看不清时显示文字」时判过看不清的 logo
 * 当作没有 (见 [shownTvTitleLogo]).
 */
@Composable
fun rememberTvTitleLogoLookup(subjectId: Int, originalName: String?): TvTitleLogoLookup {
    val display = LocalThemeSettings.current.tvTitleLogoDisplay
    if (display == TvTitleLogoDisplay.Off) return TvTitleLogoLookup.None
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val language = rememberTvTitleLogoLanguage()
    LaunchedEffect(tmdb, subjectId, originalName, language) {
        if (!tmdb.peekTitleLogoResolved(subjectId, language)) tmdb.getTitleLogo(subjectId, originalName.orEmpty(), language)
    }
    return TvTitleLogoLookup(
        tvTitleLogoShown(tmdb.peekTitleLogo(subjectId, language), display),
        pending = !tmdb.peekTitleLogoResolved(subjectId, language),
    )
}

/**
 * 按设置 [display] 显不显示 [logo]: 关了, 或「看不清时显示文字」且列表页 / 详情页哪一处判过它看不清 (见 [TvTitleLogoUnreadable]) 时为 null.
 * 快照读 (记下看不清时重组).
 */
fun tvTitleLogoShown(logo: TmdbTitleLogo?, display: TvTitleLogoDisplay): TmdbTitleLogo? = when {
    logo == null || display == TvTitleLogoDisplay.Off -> null
    display == TvTitleLogoDisplay.TextWhenUnreadable && TvTitleLogoUnreadable.isMarked(logo) -> null
    else -> logo
}

/** 同 [tvTitleLogoShown], 按此刻的设置: 别处带过来的 logo (如放大转场的会话里列表页那张) 显示前过一遍. */
@Composable
fun shownTvTitleLogo(logo: TmdbTitleLogo?): TmdbTitleLogo? = tvTitleLogoShown(logo, LocalThemeSettings.current.tvTitleLogoDisplay)

/**
 * 判出来原样看不清的标题 logo (按图片路径记): 列表页 hero (模糊背景上按正下方的背景判, 没铺模糊背景时按标题的底色看要不要翻) 与详情页首屏
 * (按自己的清晰背景图判, 见 [TvTitleLogoDetailsLooks]) 任一处看不清就记下, 不论设置是哪一档 (换到「看不清时显示文字」当场生效).
 * 设置为「看不清时显示文字」时, 记下的 logo 各处 (连播放器) 都换回文字标题 (见 [tvTitleLogoShown]); 播放器上画面一直在变, 不判.
 * 只在主线程读写, 进程内有效.
 */
object TvTitleLogoUnreadable {
    private val marked = mutableStateMapOf<String, Boolean>()

    fun mark(logo: TmdbTitleLogo) {
        if (marked[logo.filePath] != true) marked[logo.filePath] = true
    }

    /** 快照读. */
    fun isMarked(logo: TmdbTitleLogo): Boolean = marked[logo.filePath] == true
}

/**
 * 标题 logo 用哪种语言的 (见 ThemeSettings.tvTitleLogoLanguage): null = 条目的原语言, 否则界面语言的两个字母 (如 `zh`).
 * 传给 [TmdbImageService.getTitleLogo] 等.
 */
@Composable
fun rememberTvTitleLogoLanguage(): String? {
    if (LocalThemeSettings.current.tvTitleLogoLanguage == TvTitleLogoLanguage.Original) return null
    return Locale.current.toTmdbLanguage().substringBefore('-').lowercase()
}

/** 同 [rememberTvTitleLogoLookup], 只要 logo: 设置里关了、这部没有原版 logo (或还没查完)、或看不清要显示文字时为 null. */
@Composable
fun rememberTvTitleLogo(subjectId: Int, originalName: String?): TmdbTitleLogo? =
    rememberTvTitleLogoLookup(subjectId, originalName).logo

/**
 * 标题 logo 的框 (见 [tvTitleLogoBox]): 列表页 hero 文字块、hero 流水线的 logo 预热、详情页首屏与播放器共用 —— 按同一个框挑图片档位
 * ([TmdbTitleLogo.url]), 预热的才是显示时要的那张. 行高取标题 (headlineLarge) 的, 按像素向上取整 (同 toTvNativeTextStyle).
 * [titleWidthPx] = 标题宽 (宽度上限另不超过它), 0 = 不限. 设置里关了为 null.
 */
@Composable
fun rememberTvTitleLogoBox(titleWidthPx: Int = 0): TvTitleLogoBox? {
    val enabled = LocalThemeSettings.current.tvTitleLogoDisplay != TvTitleLogoDisplay.Off
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    return remember(enabled, density, typography, titleWidthPx) {
        if (enabled) tvTitleLogoBox(typography.headlineLarge.lineHeightPx(density), titleWidthPx) else null
    }
}

private fun TextStyle.lineHeightPx(density: Density): Int =
    if (lineHeight.isSpecified) with(density) { ceil(lineHeight.toPx()).toInt() } else 0

/**
 * 预取条目的标题 logo ([language] 同 [rememberTvTitleLogoLanguage]; 查进热表与持久缓存, 见 [TmdbImageService.getTitleLogo]); 已查过的直接返回. [originalName] 为 null 时只有对应表里的条目
 * 查得到 (按条目 id 查, 不要名字), 别的等名字到了再查. 失败不外泄 (下次聚焦重试).
 */
suspend fun TmdbImageService.prefetchTvTitleLogo(subjectId: Int, originalName: String?, language: String?) {
    if (peekTitleLogoResolved(subjectId, language)) return
    try {
        getTitleLogo(subjectId, originalName.orEmpty(), language)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

/**
 * 标题 logo 的色调: 不透明像素里接近纯黑 ([nearBlack], 三个通道都低于 64) 与接近纯白 ([nearWhite], 三个通道都高于 200) 的占比.
 * 由此判断 logo 压在文字的底色上看不看得清 (见 [needsFlip]).
 */
@Immutable
data class TvTitleLogoTone(val nearBlack: Float, val nearWhite: Float) {
    /**
     * 要不要翻色 (见 [flipTvTitleLogo]): logo 的主体与文字反色 (浅色字 = 深色底, 主体接近纯黑; 深色字 = 浅色底, 主体接近纯白)、自己又没有反色的描边或底.
     * 浅色字时接近纯黑的占六成以上且接近纯白的不到 8% 才算 (深色字反过来). 2026-10 抽查 18 部 TMDB 中文 / 日文 logo, 只命中两张纯黑字的
     * (四月是你的谎言、DARLING in the FRANXX); 黑字带黄描边、黑字白圆底、黑白各半的都不动. 照 Prime Video: 深红字、深色字加白描边原样放.
     */
    fun needsFlip(lightText: Boolean): Boolean =
        if (lightText) nearBlack >= 0.6f && nearWhite <= 0.08f else nearWhite >= 0.6f && nearBlack <= 0.08f
}

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
object TvTitleLogoGrids {
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
    suspend fun obtain(sketch: Sketch, context: Context, logo: TmdbTitleLogo, size: TvTitleLogoSize): TvTitleLogoGrid? {
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
object TvTitleLogoDetailsLooks {
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
    fun of(logo: TmdbTitleLogo, backdropUrl: String): TvHeroTitleLogoLook? = looks[key(logo, backdropUrl)]

    /** 详情页首屏的背景图 [url] 解好了 ([bitmap]): 后台缩小取样 (长边 [TV_DETAILS_LOGO_BACKDROP_SAMPLE_PX]), 留给判 logo 用. 同一张只量一次. */
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
    fun request(
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
