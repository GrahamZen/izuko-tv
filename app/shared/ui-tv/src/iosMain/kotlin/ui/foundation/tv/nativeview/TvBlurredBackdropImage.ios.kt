/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.asBitmapOrNull
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.resize.Precision
import com.github.panpf.sketch.transform.BlurTransformation
import com.github.panpf.sketch.util.asComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 用 sketch 的模糊变换解一张小图 (长边 [TvBackdropBlurSpec.longEdgePx]), 量亮度用 Compose 读像素. */
internal actual suspend fun tvFetchBlurredBackdrop(
    sketch: Sketch,
    context: PlatformContext,
    url: String,
    widthPx: Int,
    heightPx: Int,
    spec: TvBackdropBlurSpec,
): TvBlurredBackdropImage? {
    val request = ImageRequest(context, url) {
        size(spec.longEdgePx, spec.longEdgePx)
        precision(Precision.LESS_PIXELS)
        transformations(BlurTransformation(radius = spec.radiusPx))
    }
    val result = sketch.execute(request) as? ImageResult.Success ?: return null
    val bitmap = result.image.asBitmapOrNull()?.asComposeImageBitmap() ?: return null
    return withContext(Dispatchers.Default) {
        val map = bitmap.toPixelMap()
        val pixels = IntArray(map.width * map.height) { i -> map[i % map.width, i / map.width].toArgb() }
        TvBlurredBackdropImage(
            bitmap,
            worstForLightText = tvBackdropWorstLuminance(pixels, lightText = true),
            worstForDarkText = tvBackdropWorstLuminance(pixels, lightText = false),
        )
    }
}
