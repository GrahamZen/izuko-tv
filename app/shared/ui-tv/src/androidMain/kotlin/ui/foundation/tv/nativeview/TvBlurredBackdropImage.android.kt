/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.ui.graphics.asImageBitmap
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.ui.foundation.TvNativeImages

internal actual suspend fun tvFetchBlurredBackdrop(
    sketch: Sketch,
    context: PlatformContext,
    url: String,
    widthPx: Int,
    heightPx: Int,
    spec: TvBackdropBlurSpec,
): TvBlurredBackdropImage? {
    val bitmap = TvNativeImages.fetchBlurredBackdrop(
        sketch, context, url, widthPx, heightPx,
        longEdgePx = spec.longEdgePx,
        blurRadiusPx = spec.radiusPx,
        // 竖版封面按原地址解: 清晰层铺的就是它, 下载缓存里已经有了
        coverWidthPx = 0,
        coverHeightPx = 0,
    ) ?: return null
    // 同海报墙的模糊层量法: 同一张图两边压得一样深
    return withContext(Dispatchers.Default) {
        val pixels = tvBackdropMeasurePixels(bitmap)
        TvBlurredBackdropImage(
            bitmap.asImageBitmap(),
            worstForLightText = tvBackdropWorstLuminance(pixels, lightText = true),
            worstForDarkText = tvBackdropWorstLuminance(pixels, lightText = false),
        )
    }
}
