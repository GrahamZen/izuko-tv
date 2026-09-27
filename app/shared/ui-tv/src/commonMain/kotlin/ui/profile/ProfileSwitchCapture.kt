/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.profile

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 这个视图所在的弹窗窗口 (某级祖先是 compose 的 DialogLayout); 不在弹窗里返回 null. */
internal fun View.findDialogWindow(): Window? {
    var node: View? = this
    while (node != null) {
        (node as? DialogWindowProvider)?.let { return it.window }
        node = node.parent as? View
    }
    return null
}

/**
 * 截下 [window] 已经画在屏上的内容 (整个窗口, 原尺寸), 截不了返回 null. 换人的过场定格后截那一帧用 (见 TvUserProfilePicker.beginSwitch).
 * 用 [PixelCopy] 读窗口画好的那块缓冲: 投影、模糊这些只有硬件渲染才有的效果都在, 与屏上逐像素一样.
 */
internal suspend fun captureWindowFrame(window: Window): Bitmap? = withContext(Dispatchers.Main.immediate) {
    val decor = window.peekDecorView() ?: return@withContext null
    if (decor.width <= 0 || decor.height <= 0) return@withContext null
    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    val result = runCatching {
        suspendCoroutine { continuation ->
            PixelCopy.request(window, bitmap, { copyResult -> continuation.resume(copyResult) }, Handler(Looper.getMainLooper()))
        }
    }.getOrElse { PixelCopy.ERROR_UNKNOWN }
    if (result == PixelCopy.SUCCESS) {
        bitmap
    } else {
        bitmap.recycle()
        null
    }
}
