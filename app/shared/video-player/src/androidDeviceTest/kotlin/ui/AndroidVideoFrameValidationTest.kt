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
import android.graphics.Color
import android.view.PixelCopy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidVideoFrameValidationTest {
    @Test
    fun rejectsTransparentSuccess() = withBitmap { bitmap ->
        assertFalse(validateAndroidVideoFrame(bitmap, PixelCopy.SUCCESS))
    }

    @Test
    fun acceptsOpaqueBlackScene() = withBitmap { bitmap ->
        bitmap.eraseColor(Color.BLACK)
        assertTrue(validateAndroidVideoFrame(bitmap, PixelCopy.SUCCESS))
    }

    @Test
    fun acceptsContentAtLastPixel() = withBitmap { bitmap ->
        bitmap.setPixel(bitmap.width - 1, bitmap.height - 1, Color.WHITE)
        assertTrue(validateAndroidVideoFrame(bitmap, PixelCopy.SUCCESS))
    }

    @Test
    fun rejectsCopyErrorsEvenWithContent() = withBitmap { bitmap ->
        bitmap.eraseColor(Color.WHITE)
        for (status in listOf(
            PixelCopy.ERROR_UNKNOWN, PixelCopy.ERROR_TIMEOUT, PixelCopy.ERROR_SOURCE_NO_DATA,
            PixelCopy.ERROR_SOURCE_INVALID, PixelCopy.ERROR_DESTINATION_INVALID,
        )) {
            assertFalse(validateAndroidVideoFrame(bitmap, status), "status=$status")
        }
    }

    private fun withBitmap(block: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            block(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
