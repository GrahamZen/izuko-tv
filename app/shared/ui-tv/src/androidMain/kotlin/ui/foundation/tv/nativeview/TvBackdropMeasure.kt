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
import androidx.compose.runtime.Composable
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import kotlin.math.roundToInt

/**
 * 量压暗用的像素 (见 tvBackdropWorstLuminance): 图的长边超过 [TV_BACKDROP_MEASURE_LONG_EDGE_PX] 时先缩小再取 —— 模糊过的图缩小后亮度分布不变,
 * 在主线程上量也只要零点几毫秒 (海报墙的模糊层是在解好的回调里、主线程上量的). 硬件位图读不了像素, 先拷一份 (模糊变换出来的是普通位图, 这里只是保险).
 */
internal fun tvBackdropMeasurePixels(bitmap: Bitmap): IntArray = tvBackdropMeasureSample(bitmap).pixels

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
