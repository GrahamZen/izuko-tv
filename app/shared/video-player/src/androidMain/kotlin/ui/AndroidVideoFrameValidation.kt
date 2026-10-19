/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.graphics.Bitmap
import android.os.Build
import android.view.PixelCopy
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

private object VideoFrameCopyDiagnostics
private val frameCopyLogger = logger<VideoFrameCopyDiagnostics>()

internal fun logAndroidVideoFrameCopyException(error: Throwable) {
    frameCopyLogger.warn(error) { "Video screenshot: PixelCopy request threw" }
}

/**
 * SUCCESS describes the copy operation, not whether the hardware video plane was readable.
 * A fully transparent video frame cannot provide a screenshot; an opaque black frame can be a normal scene.
 * No decoder or output-surface switch is attempted here: a second decoder can disrupt single-decoder TVs.
 */
internal fun validateAndroidVideoFrame(bitmap: Bitmap, result: Int): Boolean {
    val device = "api=${Build.VERSION.SDK_INT}, manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}, hardware=${Build.HARDWARE}"
    if (result != PixelCopy.SUCCESS) {
        frameCopyLogger.warn { "Video screenshot: PixelCopy result=$result, ${bitmap.width}x${bitmap.height}, $device" }
        return false
    }
    val visible = !bitmap.isTransparent()
    if (!visible) {
        frameCopyLogger.warn { "Video screenshot: PixelCopy SUCCESS but fully transparent bitmap, ${bitmap.width}x${bitmap.height}, $device" }
    } else {
        frameCopyLogger.info { "Video screenshot: PixelCopy SUCCESS, non-transparent bitmap, ${bitmap.width}x${bitmap.height}, $device" }
    }
    return visible
}
