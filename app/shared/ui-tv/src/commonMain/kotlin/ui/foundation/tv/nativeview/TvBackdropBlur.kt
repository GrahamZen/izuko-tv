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
internal fun tvBackdropMeasurePixels(bitmap: Bitmap): IntArray {
    val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
    val readable = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val longEdge = maxOf(readable.width, readable.height)
    val source = if (longEdge > TV_BACKDROP_MEASURE_LONG_EDGE_PX) {
        val scale = TV_BACKDROP_MEASURE_LONG_EDGE_PX.toFloat() / longEdge
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
    return pixels
}

/** 量亮度时图最多这么大 (长边 px). */
private const val TV_BACKDROP_MEASURE_LONG_EDGE_PX = 160
