/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.max
import android.graphics.Canvas as AndroidCanvas

// create a gpu-accelerated bitmap
internal actual fun createDanmakuImageBitmap(
    solidTextLayout: TextLayoutResult,
    borderTextLayout: TextLayoutResult?,
): ImageBitmapWithOffset {
    // We must ensure the size is at least 1x1, otherwise there may be an exception, see #1838.
    val width = max(borderTextLayout?.size?.width ?: 0, solidTextLayout.size.width).coerceAtLeast(1)
    val height = max(borderTextLayout?.size?.height ?: 0, solidTextLayout.size.height).coerceAtLeast(1)
    val extraMargin = height shr 1
    val extraMarginFloat = extraMargin.toFloat()

    val destBitmap = Bitmap.createBitmap(
        width + extraMargin * 2,
        height + extraMargin * 2,
        Bitmap.Config.ARGB_8888,
    )
    val destCanvas = Canvas(AndroidCanvas(destBitmap))

    destCanvas.translate(extraMarginFloat, extraMarginFloat)
    borderTextLayout?.let { destCanvas.paintIfNotEmpty(it) }
    destCanvas.paintIfNotEmpty(solidTextLayout)

    return cropDanmakuImage(destBitmap, Offset(-extraMarginFloat, -extraMarginFloat))
}
