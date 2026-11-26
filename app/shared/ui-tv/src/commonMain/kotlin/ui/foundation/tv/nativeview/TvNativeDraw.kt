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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF

// 自己画底与图标的原生小视图 (行、按钮、卡片、气泡) 共用的画法. 矩形与画笔由视图备着复用, 这里不分配.

/** 铺满 [width] × [height] 的圆角实底 (行、按钮、卡片的底, 与触屏按下时罩的一层); 全透明的不画. */
internal fun Canvas.drawTvNativePlate(rect: RectF, width: Int, height: Int, corner: Float, color: Int, paint: Paint) {
    if (Color.alpha(color) == 0) return
    rect.set(0f, 0f, width.toFloat(), height.toFloat())
    paint.color = color
    drawRoundRect(rect, corner, corner, paint)
}

/** 白色位图图标按 [color] 着色, 画在 ([left], [top]) 起 [size] 见方的格子里; 没有图标或格子为空时不画. */
internal fun Canvas.drawTvNativeIcon(bitmap: Bitmap?, left: Float, top: Float, size: Int, color: Int, paint: Paint, dst: RectF) {
    if (bitmap == null || size <= 0) return
    paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    dst.set(left, top, left + size, top + size)
    drawBitmap(bitmap, null, dst, paint)
}
