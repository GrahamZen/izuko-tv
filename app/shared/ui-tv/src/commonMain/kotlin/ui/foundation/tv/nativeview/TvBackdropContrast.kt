/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * 整屏模糊背景上的文字对比度 (新番时间表, 见 TvNativeWallBackdropView). 照 tvOS 产品页往下翻时的做法: 模糊剧照上压一层暗, 亮的图压得深
 * (Apple 按剧照的底色分两档). 这里按每张图自己量: 找出文字最难看清的那一处 (浅色字 = 最亮的一成里最暗的那点, 深色字反过来), 压暗到
 * 文字与它的对比度够 [TV_WALL_BACKDROP_TEXT_CONTRAST]; 本来就够的图照旧只压起步那一份.
 */

/** sRGB 分量 (0..255) 到线性值的查表. */
private val SRGB_TO_LINEAR = FloatArray(256) { i ->
    val c = i / 255f
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
}

/** [argb] 的相对亮度 (WCAG 的定义, 0..1; 不看透明度). */
internal fun tvRelativeLuminance(argb: Int): Float =
    0.2126f * SRGB_TO_LINEAR[(argb shr 16) and 0xFF] +
        0.7152f * SRGB_TO_LINEAR[(argb shr 8) and 0xFF] +
        0.0722f * SRGB_TO_LINEAR[argb and 0xFF]

/** 相对亮度 (按 1/4095 取整) 到它的 sRGB 值 (0..255 取整) 的查表: 按 sRGB 值分档, 暗部分得细. */
private val LUMINANCE_TO_SRGB_BIN = IntArray(4096) { i -> (linearToSrgb(i / 4095f) * 255f).toInt().coerceIn(0, 255) }

/**
 * 背景图 [pixels] (ARGB) 上文字最难看清那一处的相对亮度: [lightText] = 浅色字, 取最亮的 [TV_WALL_BACKDROP_WORST_FRACTION] 之外最亮的那点;
 * 深色字取最暗那头对称的一点. 按亮度的 sRGB 值分 256 档, 取档里对看清不利的那一边 (浅色字取上沿, 深色字取下沿). 没有像素时按最好看清算.
 */
internal fun tvBackdropWorstLuminance(pixels: IntArray, lightText: Boolean): Float {
    if (pixels.isEmpty()) return if (lightText) 0f else 1f
    val bins = IntArray(256)
    for (p in pixels) bins[LUMINANCE_TO_SRGB_BIN[(tvRelativeLuminance(p) * 4095f).roundToInt().coerceIn(0, 4095)]]++
    val skip = (pixels.size * TV_WALL_BACKDROP_WORST_FRACTION).toInt()
    var seen = 0
    val order = if (lightText) 255 downTo 0 else 0..255
    for (i in order) {
        seen += bins[i]
        if (seen > skip) return SRGB_TO_LINEAR[if (lightText) minOf(i + 1, 255) else i]
    }
    return if (lightText) 0f else 1f
}

/**
 * 压暗色 (相对亮度 [maskLuminance]) 该压多深 (不透明度 0..1): 至少 [base]; 文字 ([textLuminance]) 与压过之后的最难看清处 ([worstLuminance])
 * 对比度不到 [TV_WALL_BACKDROP_TEXT_CONTRAST] 就加深, 最多 [TV_WALL_BACKDROP_MASK_ALPHA_MAX] (再深就看不出背景是哪张图了). 压暗是按不透明度
 * 在 sRGB 分量上混 (SRC_ATOP), 这里按灰阶算. 文字比压暗色亮 = 浅色字 (深色主题), 背景要压暗; 反过来是浅色主题, 背景要提亮.
 */
internal fun tvBackdropMaskAlpha(worstLuminance: Float, maskLuminance: Float, textLuminance: Float, base: Float): Float {
    val max = maxOf(base, TV_WALL_BACKDROP_MASK_ALPHA_MAX)
    val contrast = TV_WALL_BACKDROP_TEXT_CONTRAST
    val worst = linearToSrgb(worstLuminance)
    val mask = linearToSrgb(maskLuminance)
    val needed = if (textLuminance >= maskLuminance) {
        // 背景要暗到这个亮度以下
        val limit = (textLuminance + 0.05f) / contrast - 0.05f
        if (limit <= 0f) return max
        val s = linearToSrgb(limit)
        when {
            worst <= s -> 0f
            mask >= s -> 1f
            else -> (worst - s) / (worst - mask)
        }
    } else {
        // 背景要亮到这个亮度以上
        val limit = contrast * (textLuminance + 0.05f) - 0.05f
        if (limit >= 1f) return max
        val s = linearToSrgb(limit)
        when {
            worst >= s -> 0f
            mask <= s -> 1f
            else -> (s - worst) / (mask - worst)
        }
    }
    return needed.coerceIn(base, max)
}

private fun linearToSrgb(y: Float): Float =
    if (y <= 0.0031308f) y * 12.92f else 1.055f * y.coerceIn(0f, 1f).pow(1f / 2.4f) - 0.055f

/** 文字与背景至少的对比度: WCAG 正文小字的 AA 线. 按番名 (主要文字) 算, 次要那行字 (半透明) 约为它的三分之二. */
internal const val TV_WALL_BACKDROP_TEXT_CONTRAST = 4.5f

/** 量最难看清处时不计的那一截 (最亮 / 最暗的一成): 一小块高光不至于把整张图压黑. */
internal const val TV_WALL_BACKDROP_WORST_FRACTION = 0.1f

/** 压暗最深到多少. */
internal const val TV_WALL_BACKDROP_MASK_ALPHA_MAX = 0.75f
