/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Bitmap
import android.os.Build
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import kotlin.math.roundToInt

/**
 * 整屏模糊背景那张小图怎么解: 按长边 [longEdgePx] 解, 在小图上模糊 [radiusPx] (按小图的像素算), 拉伸铺满交给 GPU 双线性.
 * 拉到 1080p 整屏上的模糊半径约为 radiusPx × 1920 / longEdgePx. 不做实时模糊, 哪一档每帧画的都只是这一张小贴图.
 * 海报墙 ([TvNativeWallBackdropView]) 与详情页各按自己的一档 (设置里各一项); 选了同一档就是同一个内存缓存键.
 */
internal data class TvBackdropBlurSpec(val longEdgePx: Int, val radiusPx: Int)

/**
 * [level] 对应的解法. 长边取 TMDB w1280 的 1/2、1/4、1/8 (JPEG 按 2 的幂采样解码最省); 越轻小图越大, 不然放大的倍数本身就糊掉了细节.
 * 最轻那档的小图 640 × 360, 约 0.9MB. [TvBackdropBlurLevel.None] (不铺) 由调用方先判掉, 传进来按中.
 */
internal fun tvBackdropBlurSpec(level: TvBackdropBlurLevel): TvBackdropBlurSpec = when (level) {
    // 整屏上约 24px: 看得出画的是什么
    TvBackdropBlurLevel.Light -> TvBackdropBlurSpec(longEdgePx = 640, radiusPx = 8)
    // 约 48px
    TvBackdropBlurLevel.Medium, TvBackdropBlurLevel.None -> TvBackdropBlurSpec(longEdgePx = 320, radiusPx = 8)
    // 约 120px: 只剩颜色
    TvBackdropBlurLevel.Strong -> TvBackdropBlurSpec(longEdgePx = 160, radiusPx = 10)
}

/**
 * 量压暗用的像素 (见 tvBackdropWorstLuminance): 图的长边超过 [TV_BACKDROP_MEASURE_LONG_EDGE_PX] 时先缩小再取 —— 模糊过的图缩小后亮度分布不变,
 * 在主线程上量也只要零点几毫秒 (海报墙的模糊层是在解好的回调里、主线程上量的). 硬件位图读不了像素, 先拷一份 (模糊变换出来的是普通位图, 这里只是保险).
 */
internal fun tvBackdropMeasurePixels(bitmap: Bitmap): IntArray = tvBackdropMeasureSample(bitmap).pixels

/** 量过的一张图: 缩到长边 [TV_BACKDROP_MEASURE_LONG_EDGE_PX] 以内的像素 ([width] × [height], ARGB). */
internal class TvBackdropSample(val pixels: IntArray, val width: Int, val height: Int) {
    /** 图上相对位置 ([u], [v] 0..1) 处的颜色 (双线性). */
    fun at(u: Float, v: Float): Int {
        val x = (u * width - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val y = (v * height - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        fun ch(p: Int, shift: Int) = ((p shr shift) and 0xFF).toFloat()
        fun mix(shift: Int): Int {
            val top = ch(pixels[y0 * width + x0], shift) * (1 - fx) + ch(pixels[y0 * width + x1], shift) * fx
            val bottom = ch(pixels[y1 * width + x0], shift) * (1 - fx) + ch(pixels[y1 * width + x1], shift) * fx
            return (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

/** 同 [tvBackdropMeasurePixels], 带上尺寸; 长边缩到 [longEdgePx] 以内. */
internal fun tvBackdropMeasureSample(bitmap: Bitmap, longEdgePx: Int = TV_BACKDROP_MEASURE_LONG_EDGE_PX): TvBackdropSample {
    val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
    val readable = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val longEdge = maxOf(readable.width, readable.height)
    val source = if (longEdge > longEdgePx) {
        val scale = longEdgePx.toFloat() / longEdge
        Bitmap.createScaledBitmap(
            readable,
            (readable.width * scale).roundToInt().coerceAtLeast(1),
            (readable.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        readable
    }
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    return TvBackdropSample(pixels, source.width, source.height)
}

/** 量亮度时图最多这么大 (长边 px). */
private const val TV_BACKDROP_MEASURE_LONG_EDGE_PX = 160
