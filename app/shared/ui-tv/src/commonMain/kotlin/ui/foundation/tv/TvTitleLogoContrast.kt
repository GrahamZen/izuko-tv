/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * 模糊背景上的标题 logo 看不看得清, 看不清怎么办.
 *
 * logo 默认原样画在模糊背景上: 亮的区间里大号字只要有一点亮度差、或者颜色不同, 就认得出; 2026-10 拿 800 部热门番的日文 logo 在中档模糊
 * 背景上比过, 真看不清的只有一类 —— 字暗、背后也暗、两者亮度又几乎一样 (深蓝字压在偏黑的底上; 黑字 logo 压在暗场景上). 暗的区间里人眼
 * 几乎分不出颜色, 只能靠亮度差. 对称地, 浅色主题下怕的是浅色字压在很浅的底上 (那时颜色要也差不多才算).
 *
 * 判据用 APCA 对比度 (WCAG 3 草案的对比度模型, 已经把暗区看不清折算进去): logo 不透明处与正下方背景的 |Lc| 中位数 < [TV_TITLE_LOGO_LC_UNREADABLE]
 * 且两者都暗 (OKLab L 中位数 < 0.5); 或者两者都亮、颜色也差不多. 800 部里约 2% 触发.
 *
 * 触发了: 先把 logo 里黑白灰的部分翻到另一头 (暗底翻成白, 亮底翻成黑, 彩色部分原样, 见 [tvTitleLogoFlipColor]) —— 黑白灰的 logo 整个翻,
 * 有颜色的 logo 里的黑字、黑描边也翻 (头文字D 的黑字配红 D), 翻完有 [TV_TITLE_LOGO_FLIP_READABLE_SHARE] 以上的字看得清就用它.
 * 翻了也不够 (整个是深色彩色字) 才在 logo 背后加一团柔光 ([TvTitleLogoGlow]): 白色 (或黑色) 叠层, 浓度按 logo 的形状大范围摊开、离 logo
 * 越远越淡, 只加到刚好看得清 (中位 |Lc| 到 [TV_TITLE_LOGO_LC_TARGET]), 两个方向都试, 取浓度小的那个. 柔光只在模糊背景上画: 压在清晰图上
 * 像一团雾 (详情页首屏按清晰图判出柔光时原样画).
 *
 * 整体看得清的 logo 还可能有一块字看不清 (药屋少女的呢喃: 红底白字的「薬屋」看得清, 旁边一列黑色假名压在暗处看不见), 中位数看不出来,
 * 另按「看丢的墨」判, 见 [tvTitleLogoLostPartLook]. 这种只翻色, 翻了不见好就原样, 不加柔光.
 */

/**
 * 一张标题 logo 的格子化颜色 (按 logo 的框分成 [cols] × [rows] 格, 每格: 不透明度 [coverage] 0..1 与按不透明度加权的平均色 [colors] (不透明 ARGB))
 * 与色调 [tone]. 由解好的原图量一次 (见 tvTitleLogoGrid), 按图片路径缓存.
 */
class TvTitleLogoGrid(
    val cols: Int,
    val rows: Int,
    val coverage: FloatArray,
    val colors: IntArray,
    val tone: TvTitleLogoTone,
) {
    init {
        require(coverage.size == cols * rows && colors.size == cols * rows)
    }
}

/**
 * logo 在模糊背景上的样子: [flipLightText] 非 null = 黑白灰的部分按这种字色一律翻 (TvTitleLogoFlip 的 lightText 且 force; true = 翻成浅色),
 * null = 原样; [glow] = 背后加的柔光 (null = 不加).
 */
data class TvTitleLogoBackdropLook(val flipLightText: Boolean?, val glow: TvTitleLogoGlow?) {
    companion object {
        val Original = TvTitleLogoBackdropLook(null, null)
    }
}

/**
 * logo 背后的柔光: [lighten] = 白色提亮 (false = 黑色压暗); 浓度 (0..1) 是 [cols] × [rows] 格的 [alpha], 覆盖 logo 的框向四周各扩 [marginCells] 格
 * (一格 = logo 高 / [logoRows]). 画的时候拉伸成平滑的一团 (双线性).
 */
class TvTitleLogoGlow(
    val lighten: Boolean,
    val cols: Int,
    val rows: Int,
    val alpha: FloatArray,
    val logoRows: Int,
    val marginCells: Int,
) {
    /** 一格占 logo 高的多少. */
    val cellPerLogoHeight: Float get() = 1f / logoRows
}

/**
 * 判断 [logo] 压在背景 [background] 上 (与 logo 同样分格、每格正下方的背景色, 不透明 ARGB) 要不要处理, 怎么处理. 见本文件开头.
 */
fun tvTitleLogoBackdropLook(logo: TvTitleLogoGrid, background: IntArray): TvTitleLogoBackdropLook {
    require(background.size == logo.cols * logo.rows)
    val contrast = FloatArray(background.size) { apcaContrast(logo.colors[it], background[it]) }
    val cells = (0 until logo.cols * logo.rows).filter { logo.coverage[it] >= TV_TITLE_LOGO_SOLID_COVERAGE }.toIntArray()
    if (cells.isEmpty()) return TvTitleLogoBackdropLook.Original
    val lightText = if (median(FloatArray(cells.size) { contrast[cells[it]] }) < TV_TITLE_LOGO_LC_UNREADABLE) {
        unreadableFlipDirection(logo, background, cells)
    } else {
        null
    }
    if (lightText == null) return tvTitleLogoLostPartLook(logo, background, contrast)
    // 黑白灰的部分翻到另一头 (暗底翻成浅色, 亮底翻成深色), 翻完够认就翻
    val readable = cells.count { apcaContrast(tvTitleLogoFlipColor(logo.colors[it], lightText), background[it]) >= TV_TITLE_LOGO_LC_TARGET }
    if (readable >= cells.size * TV_TITLE_LOGO_FLIP_READABLE_SHARE) return TvTitleLogoBackdropLook(flipLightText = lightText, glow = null)
    return TvTitleLogoBackdropLook(flipLightText = null, glow = tvTitleLogoGlow(logo, background, cells))
}

/**
 * [cells] 这些格看不清是不是「字与底同暗 / 同亮」那种, 是的话往哪边翻: 都暗 (OKLab L 中位数 < 0.5) 为 true (翻成浅色), 都亮且颜色也差不多为
 * false (翻成深色), 都不是为 null.
 */
private fun unreadableFlipDirection(logo: TvTitleLogoGrid, background: IntArray, cells: IntArray): Boolean? {
    val textL = median(FloatArray(cells.size) { oklabL(logo.colors[cells[it]]) })
    val backL = median(FloatArray(cells.size) { oklabL(background[cells[it]]) })
    if (textL < 0.5f && backL < 0.5f) return true
    val bothLight = textL >= 0.5f && backL >= 0.5f &&
        median(FloatArray(cells.size) { oklabChromaDistance(logo.colors[cells[it]], background[cells[it]]) }) < TV_TITLE_LOGO_SIMILAR_CHROMA
    return if (bothLight) false else null
}

/**
 * 整体看得清的 logo 里有一块字看不清: 有墨的格 (不透明度到 [TV_TITLE_LOGO_INK_COVERAGE]) 里看不清 (|Lc| 不到 [TV_TITLE_LOGO_LC_UNREADABLE])、
 * 离看得清的格又超过 [TV_TITLE_LOGO_LOST_REACH] 格的, 是「看丢的墨」—— 紧挨着看得清的字的描边、阴影、抗锯齿边看不清不要紧, 不算.
 * 看丢的墨按不透明度加权占全部墨的 [TV_TITLE_LOGO_LOST_SHARE] 以上、字与底同暗 (或同亮且同色) 时试着翻黑白灰的部分: 看丢的那部分翻完有
 * [TV_TITLE_LOGO_FLIP_READABLE_SHARE] 以上到 [TV_TITLE_LOGO_LC_TARGET]、整体可读的墨 (|Lc| 到 [TV_TITLE_LOGO_LC_TARGET], 按不透明度加权) 也多出
 * [TV_TITLE_LOGO_LOST_GAIN] 以上才翻; 否则原样 —— 翻了反而把本来看得清的字翻糊的不翻 (古见同学有交流障碍症: 深灰字大半压在浅底上).
 * 2026-10 那 800 部里, 详情页首屏的清晰图上因此翻色的约 2%, 列表页模糊底上约 1.5%.
 */
private fun tvTitleLogoLostPartLook(logo: TvTitleLogoGrid, background: IntArray, contrast: FloatArray): TvTitleLogoBackdropLook {
    val cols = logo.cols
    val rows = logo.rows
    val n = cols * rows
    val ink = BooleanArray(n) { logo.coverage[it] >= TV_TITLE_LOGO_INK_COVERAGE }
    // 看得清的格往四周扩 [TV_TITLE_LOGO_LOST_REACH] 格
    val nearReadable = BooleanArray(n)
    val reach = TV_TITLE_LOGO_LOST_REACH
    for (i in 0 until n) {
        if (!ink[i] || contrast[i] < TV_TITLE_LOGO_LC_UNREADABLE) continue
        val x = i % cols
        val y = i / cols
        for (yy in max(0, y - reach)..min(rows - 1, y + reach)) {
            for (xx in max(0, x - reach)..min(cols - 1, x + reach)) nearReadable[yy * cols + xx] = true
        }
    }
    val lost = (0 until n).filter { ink[it] && contrast[it] < TV_TITLE_LOGO_LC_UNREADABLE && !nearReadable[it] }.toIntArray()
    if (lost.isEmpty()) return TvTitleLogoBackdropLook.Original
    var inkSum = 0f
    for (i in 0 until n) if (ink[i]) inkSum += logo.coverage[i]
    var lostSum = 0f
    for (i in lost) lostSum += logo.coverage[i]
    if (lostSum < inkSum * TV_TITLE_LOGO_LOST_SHARE) return TvTitleLogoBackdropLook.Original
    val lightText = unreadableFlipDirection(logo, background, lost) ?: return TvTitleLogoBackdropLook.Original
    val fixed = lost.count { apcaContrast(tvTitleLogoFlipColor(logo.colors[it], lightText), background[it]) >= TV_TITLE_LOGO_LC_TARGET }
    if (fixed < lost.size * TV_TITLE_LOGO_FLIP_READABLE_SHARE) return TvTitleLogoBackdropLook.Original
    var before = 0f
    var after = 0f
    for (i in 0 until n) {
        if (!ink[i]) continue
        if (contrast[i] >= TV_TITLE_LOGO_LC_TARGET) before += logo.coverage[i]
        if (apcaContrast(tvTitleLogoFlipColor(logo.colors[i], lightText), background[i]) >= TV_TITLE_LOGO_LC_TARGET) after += logo.coverage[i]
    }
    if (after - before < inkSum * TV_TITLE_LOGO_LOST_GAIN) return TvTitleLogoBackdropLook.Original
    return TvTitleLogoBackdropLook(flipLightText = lightText, glow = null)
}

/**
 * 一个颜色里黑白灰的成分翻到另一头, 彩色原样 (翻 logo 的逐像素规则, 见 flipTvTitleLogo): [lightText] (压在深色底上) 时暗的翻亮、本来就浅的不动;
 * 深色字反过来. 是不是彩色按饱和度 (最大与最小通道之差 / 最大通道) 判, 不按差值本身 —— 深蓝 (0, 0, 48) 差值只有 48, 却是十足的蓝色,
 * 按差值会被当成灰洗白. 饱和度不超过 [TV_TITLE_LOGO_FLIP_SATURATION_FULL] 的整个翻, 到 [TV_TITLE_LOGO_FLIP_SATURATION_NONE] 渐变到不翻
 * (彩色与黑白交界处的抗锯齿过渡不起硬边); 暗到看不出颜色的 (最亮的通道不到 [TV_TITLE_LOGO_FLIP_DARK_FULL], 浅色字反过来看最暗的通道)
 * 不管饱和度照黑白翻, 往外 [TV_TITLE_LOGO_FLIP_DARK_RAMP] 内渐变. 透明度不变.
 */
internal fun tvTitleLogoFlipColor(argb: Int, lightText: Boolean): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val saturation = if (hi == 0) 0f else (hi - lo).toFloat() / hi
    val bySaturation = ((TV_TITLE_LOGO_FLIP_SATURATION_NONE - saturation) / (TV_TITLE_LOGO_FLIP_SATURATION_NONE - TV_TITLE_LOGO_FLIP_SATURATION_FULL))
        .coerceIn(0f, 1f)
    // 暗到 / 亮到看不出颜色: 浅色字看最亮的通道多暗, 深色字看最暗的通道多亮
    val extreme = if (lightText) hi else 255 - lo
    val byExtreme = ((TV_TITLE_LOGO_FLIP_DARK_FULL + TV_TITLE_LOGO_FLIP_DARK_RAMP - extreme).toFloat() / TV_TITLE_LOGO_FLIP_DARK_RAMP)
        .coerceIn(0f, 1f)
    val weight = max(bySaturation, byExtreme)
    if (weight <= 0f) return argb
    fun flip(c: Int): Int {
        val flipped = if (lightText) max(c, 255 - c) else min(c, 255 - c)
        return (c + (flipped - c) * weight).roundToInt()
    }
    return (argb and ALPHA_MASK) or (flip(r) shl 16) or (flip(g) shl 8) or flip(b)
}

/** 有颜色的 logo: 两个方向的柔光都试, 取浓度小的那个 (都到不了目标就取到得最高的). */
private fun tvTitleLogoGlow(logo: TvTitleLogoGrid, background: IntArray, cells: IntArray): TvTitleLogoGlow {
    val shape = tvTitleLogoGlowShape(logo)
    // 每个 logo 格在柔光格子上的浓度 (形状, 未乘强度)
    val atCell = FloatArray(cells.size) { i ->
        val c = cells[i]
        shape.sampleAtLogo(c % logo.cols, c / logo.cols, logo.cols, logo.rows)
    }

    fun medianLc(lighten: Boolean, strength: Float): Float {
        val over = if (lighten) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        return median(FloatArray(cells.size) { i -> apcaContrast(logo.colors[cells[i]], mixArgb(background[cells[i]], over, strength * atCell[i])) })
    }

    fun search(lighten: Boolean): Pair<Float, Float> {
        val top = medianLc(lighten, TV_TITLE_LOGO_GLOW_MAX)
        if (top < TV_TITLE_LOGO_LC_TARGET) return TV_TITLE_LOGO_GLOW_MAX to top
        var lo = 0f
        var hi = TV_TITLE_LOGO_GLOW_MAX
        repeat(8) {
            val mid = (lo + hi) / 2
            if (medianLc(lighten, mid) >= TV_TITLE_LOGO_LC_TARGET) hi = mid else lo = mid
        }
        return hi to medianLc(lighten, hi)
    }

    val up = search(lighten = true)
    val down = search(lighten = false)
    val upOk = up.second >= TV_TITLE_LOGO_LC_TARGET
    val downOk = down.second >= TV_TITLE_LOGO_LC_TARGET
    val lighten = when {
        upOk && downOk -> up.first <= down.first
        upOk != downOk -> upOk
        else -> up.second >= down.second
    }
    val strength = if (lighten) up.first else down.first
    return TvTitleLogoGlow(
        lighten = lighten,
        cols = shape.cols,
        rows = shape.rows,
        alpha = FloatArray(shape.values.size) { shape.values[it] * strength },
        logoRows = shape.logoRows,
        marginCells = shape.margin,
    )
}

/** 柔光的形状 (0..1): logo 的不透明度缩到粗格子上, 高斯摊开 (半径 = logo 高), 除以峰值再开 [TV_TITLE_LOGO_GLOW_GAMMA] 次方, 边上淡到 0. */
private class GlowShape(val cols: Int, val rows: Int, val values: FloatArray, val logoCols: Int, val logoRows: Int, val margin: Int) {
    /** logo 细格子 ([fineCols] × [fineRows]) 第 ([x], [y]) 格中心处的形状值 (双线性). */
    fun sampleAtLogo(x: Int, y: Int, fineCols: Int, fineRows: Int): Float {
        val gx = margin + (x + 0.5f) / fineCols * logoCols - 0.5f
        val gy = margin + (y + 0.5f) / fineRows * logoRows - 0.5f
        val x0 = gx.toInt().coerceIn(0, cols - 1)
        val y0 = gy.toInt().coerceIn(0, rows - 1)
        val x1 = min(x0 + 1, cols - 1)
        val y1 = min(y0 + 1, rows - 1)
        val fx = (gx - x0).coerceIn(0f, 1f)
        val fy = (gy - y0).coerceIn(0f, 1f)
        val top = values[y0 * cols + x0] * (1 - fx) + values[y0 * cols + x1] * fx
        val bottom = values[y1 * cols + x0] * (1 - fx) + values[y1 * cols + x1] * fx
        return top * (1 - fy) + bottom * fy
    }
}

private fun tvTitleLogoGlowShape(logo: TvTitleLogoGrid): GlowShape {
    val logoRows = TV_TITLE_LOGO_GLOW_ROWS
    val logoCols = max(1, (logo.cols.toFloat() / logo.rows * logoRows).roundToInt())
    val sigma = logoRows * TV_TITLE_LOGO_GLOW_RADIUS
    val margin = (sigma * TV_TITLE_LOGO_GLOW_MARGIN).roundToInt()
    val cols = logoCols + 2 * margin
    val rows = logoRows + 2 * margin
    // logo 的不透明度按面积缩到粗格子上
    val cover = FloatArray(cols * rows)
    val weight = FloatArray(cols * rows)
    for (y in 0 until logo.rows) {
        val gy = margin + (y * logoRows / logo.rows)
        for (x in 0 until logo.cols) {
            val gx = margin + (x * logoCols / logo.cols)
            cover[gy * cols + gx] += logo.coverage[y * logo.cols + x]
            weight[gy * cols + gx] += 1f
        }
    }
    for (i in cover.indices) if (weight[i] > 0f) cover[i] /= weight[i]
    val blurred = gaussianBlur(cover, cols, rows, sigma)
    val peak = blurred.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val values = FloatArray(cols * rows) { i ->
        val x = i % cols
        val y = i / cols
        // 最外圈 [TV_TITLE_LOGO_GLOW_TAPER] 个 sigma 内再淡到 0, 位图边上不留硬边
        val edge = min(min(x, cols - 1 - x), min(y, rows - 1 - y)).toFloat()
        val taper = smoothstep((edge / (sigma * TV_TITLE_LOGO_GLOW_TAPER)).coerceIn(0f, 1f))
        (blurred[i] / peak).coerceIn(0f, 1f).pow(TV_TITLE_LOGO_GLOW_GAMMA) * taper
    }
    return GlowShape(cols, rows, values, logoCols, logoRows, margin)
}

private fun gaussianBlur(src: FloatArray, cols: Int, rows: Int, sigma: Float): FloatArray {
    val r = max(1, (3 * sigma).toInt())
    val kernel = FloatArray(2 * r + 1) { val d = (it - r).toFloat(); exp(-d * d / (2 * sigma * sigma)) }
    val sum = kernel.sum()
    for (i in kernel.indices) kernel[i] /= sum
    val tmp = FloatArray(src.size)
    for (y in 0 until rows) for (x in 0 until cols) {
        var acc = 0f
        for (k in -r..r) {
            val xx = x + k
            if (xx in 0 until cols) acc += src[y * cols + xx] * kernel[k + r]
        }
        tmp[y * cols + x] = acc
    }
    val out = FloatArray(src.size)
    for (y in 0 until rows) for (x in 0 until cols) {
        var acc = 0f
        for (k in -r..r) {
            val yy = y + k
            if (yy in 0 until rows) acc += tmp[yy * cols + x] * kernel[k + r]
        }
        out[y * cols + x] = acc
    }
    return out
}

private fun smoothstep(t: Float): Float = t * t * (3 - 2 * t)

/** 中位数 (就地排序 [values]). */
private fun median(values: FloatArray): Float {
    if (values.isEmpty()) return 0f
    values.sort()
    val n = values.size
    return if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2
}

/** 两个不透明颜色在 sRGB 里按 [t] 混 (同 GPU 把 [over] 以透明度 [t] 叠在 [base] 上). */
internal fun mixArgb(base: Int, over: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val a = (base shr shift) and 0xFF
        val b = (over shr shift) and 0xFF
        return (a + (b - a) * k).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

private fun srgbToLinear(c: Int): Float {
    val v = c / 255f
    return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
}

/** OKLab 的 L, a, b. */
private fun oklab(argb: Int): FloatArray {
    val r = srgbToLinear((argb shr 16) and 0xFF)
    val g = srgbToLinear((argb shr 8) and 0xFF)
    val b = srgbToLinear(argb and 0xFF)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

internal fun oklabL(argb: Int): Float = oklab(argb)[0]

/** 两个颜色在 OKLab 色度平面上的距离 (只比颜色, 不比亮度). */
internal fun oklabChromaDistance(a: Int, b: Int): Float {
    val x = oklab(a)
    val y = oklab(b)
    return hypot(x[1] - y[1], x[2] - y[2])
}

/** APCA (0.0.98G-4g) 的亮度. */
private fun apcaY(argb: Int): Float {
    fun c(shift: Int) = (((argb shr shift) and 0xFF) / 255f).pow(2.4f)
    val y = 0.2126729f * c(16) + 0.7151522f * c(8) + 0.0721750f * c(0)
    return if (y < 0.022f) y + (0.022f - y).pow(1.414f) else y
}

/** 字色 [text] 压在底色 [background] 上的 APCA 对比度 |Lc| (0..约 108): 深字浅底与浅字深底分开算, 不到 10 的按 0 (看不出). */
internal fun apcaContrast(text: Int, background: Int): Float {
    val yt = apcaY(text)
    val yb = apcaY(background)
    val sapc = if (yb > yt) (yb.pow(0.56f) - yt.pow(0.57f)) * 1.14f else (yb.pow(0.65f) - yt.pow(0.62f)) * 1.14f
    if (abs(sapc) < 0.1f) return 0f
    return (abs(sapc) - 0.027f) * 100f
}

/** 中位 |Lc| 低于它算看不清 (示例里能看清的最低一部在 9 左右, 看不清的都是 0). */
internal const val TV_TITLE_LOGO_LC_UNREADABLE = 8f

/** 柔光加到中位 |Lc| 这么高为止. */
internal const val TV_TITLE_LOGO_LC_TARGET = 20f

/**
 * 黑白灰的部分翻完, 至少这么多的字 (不透明格, 对比度到 [TV_TITLE_LOGO_LC_TARGET]) 看得清才用翻色, 否则加柔光: 只翻得动一小部分时 (青之驱魔师
 * 深蓝渐变到黑, 只有底端翻得动, 约 14%) 一笔会变成两截颜色. 800 部里会触发的, 翻色的都在 65% 以上 (头文字D 黑字配红 D 99%).
 */
private const val TV_TITLE_LOGO_FLIP_READABLE_SHARE = 0.5f

/** 饱和度不超过这个的 (灰、黑、白) 整个翻. */
private const val TV_TITLE_LOGO_FLIP_SATURATION_FULL = 0.15f

/** 饱和度到这个就不翻 (彩色部分原样). */
private const val TV_TITLE_LOGO_FLIP_SATURATION_NONE = 0.4f

/** 最亮的通道不到这个 (浅色字时; 深色字看最暗的通道离 255 多远) 就看不出颜色, 照黑白整个翻. */
private const val TV_TITLE_LOGO_FLIP_DARK_FULL = 24

/** 从 [TV_TITLE_LOGO_FLIP_DARK_FULL] 往外这么宽渐变到按饱和度判. */
private const val TV_TITLE_LOGO_FLIP_DARK_RAMP = 16

private const val ALPHA_MASK = -0x1000000 // 0xFF000000

/** 不透明度到这么多的格算有墨 (量「看丢的墨」时, 见 [tvTitleLogoLostPartLook]; 比 [TV_TITLE_LOGO_SOLID_COVERAGE] 低, 细笔画也算进来). */
private const val TV_TITLE_LOGO_INK_COVERAGE = 0.25f

/** 离看得清的格这么多格以内的看不清, 算描边 / 阴影, 不算看丢 (logo 高分 [TV_TITLE_LOGO_GRID_ROWS] 格, 2 格 = 高的 5%). */
private const val TV_TITLE_LOGO_LOST_REACH = 2

/** 看丢的墨占全部墨的这么多才处理. */
private const val TV_TITLE_LOGO_LOST_SHARE = 0.1f

/** 局部看不清翻色时, 整体可读的墨至少要多出这么多 (占全部墨) 才翻 (奇巧计程车只多 3%: 翻了换了样子却没清楚多少). */
private const val TV_TITLE_LOGO_LOST_GAIN = 0.1f

/** 两者都亮时, 颜色 (OKLab 色度距离) 也差不多才算看不清. */
private const val TV_TITLE_LOGO_SIMILAR_CHROMA = 0.08f

/** 不透明度到这么多的格子才算 logo 的字 (量对比度只看它们). */
private const val TV_TITLE_LOGO_SOLID_COVERAGE = 0.5f

/** 柔光最浓到多少. */
private const val TV_TITLE_LOGO_GLOW_MAX = 0.6f

/** 柔光格子: logo 高分成几格. */
private const val TV_TITLE_LOGO_GLOW_ROWS = 10

/** 柔光摊开的半径 (相对 logo 高). */
private const val TV_TITLE_LOGO_GLOW_RADIUS = 1f

/** 柔光向四周扩多少 (相对半径). */
private const val TV_TITLE_LOGO_GLOW_MARGIN = 2.5f

/** 最外圈多宽 (相对半径) 内淡到 0. */
private const val TV_TITLE_LOGO_GLOW_TAPER = 0.6f

/** 形状的次方: 小于 1 让中间一片更平, 不是一个尖. */
private const val TV_TITLE_LOGO_GLOW_GAMMA = 0.7f

/** logo 量颜色时分多少行 (列数按宽高比). */
internal const val TV_TITLE_LOGO_GRID_ROWS = 40

/** 列数上限. */
internal const val TV_TITLE_LOGO_GRID_MAX_COLS = 320
