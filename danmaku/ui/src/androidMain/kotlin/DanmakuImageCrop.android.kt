/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicInteger

/** 裁剪后四周留下的透明像素: 弹幕层画时四边各缩进半个像素 (见 [GlDanmakuLayerView]), 只缩掉透明的. */
private const val CROP_PADDING = 2

/**
 * 正在画弹幕的 GL 弹幕层有几个 (见 [DanmakuLayer]). 不为 0 时弹幕图只由弹幕层自己传进图集, 界面的渲染线程画不到,
 * 就不让它预先传一份到 GPU ([Bitmap.prepareToDraw]); 用旧画法 ([DanmakuHost]) 时照旧预传.
 */
internal val glDanmakuLayersInUse = AtomicInteger()

private fun Bitmap.toDanmakuImage(offset: Offset): ImageBitmapWithOffset =
    ImageBitmapWithOffset(asImageBitmap().also { if (glDanmakuLayersInUse.get() == 0) it.prepareToDraw() }, offset)

// 找边界时拷像素用, 留着复用 (弹幕图本来就一张一张地画, 见 StyledDanmaku.cachedImage)
private val scanBufferLock = Any()
private var scanBuffer = IntArray(0)

/**
 * 把弹幕图 [bitmap] 四周的透明边裁到只剩 [CROP_PADDING] 像素, [offset] 跟着挪, 画出来的位置不变.
 *
 * 画弹幕图时四周各留了半个字高 (给描边、伸出行高的字形留地方), 笔画只占图的一半不到. 弹幕层把在屏的图都放进一张图集,
 * 字号、显示区域调大后满屏的图会比最大的图集还大; 裁掉后图集能多放一倍多, 每帧要混合的透明像素也少一半.
 * 边界按像素找, 伸出行高的字形 (叠起来的变音符号等) 不会被裁掉. 裁剪后 [bitmap] 被回收.
 */
internal fun cropDanmakuImage(bitmap: Bitmap, offset: Offset): ImageBitmapWithOffset = synchronized(scanBufferLock) { cropLocked(bitmap, offset) }

private fun cropLocked(bitmap: Bitmap, offset: Offset): ImageBitmapWithOffset {
    val width = bitmap.width
    val height = bitmap.height
    // 按内存里的原样拷出来 (透明像素是 0), 不用 getPixels: 那个要逐像素换算预乘, 比画这张图还慢
    val stride = bitmap.rowBytes / 4
    val count = stride * height
    if (scanBuffer.size < count) scanBuffer = IntArray(count)
    val pixels = scanBuffer
    bitmap.copyPixelsToBuffer(IntBuffer.wrap(pixels, 0, count))
    fun rowIsEmpty(y: Int): Boolean {
        val start = y * stride
        for (i in start until start + width) if (pixels[i] != 0) return false
        return true
    }

    var top = 0
    while (top < height && rowIsEmpty(top)) top++
    if (top == height) return bitmap.toDanmakuImage(offset) // 没有笔画 (全是空格等)
    var bottom = height - 1
    while (rowIsEmpty(bottom)) bottom--
    // 左右边界: 每行只看比已找到的边界更靠外的那几个像素
    var left = width
    var right = -1
    for (y in top..bottom) {
        val start = y * stride
        var x = 0
        while (x < left && pixels[start + x] == 0) x++
        if (x < left) left = x
        x = width - 1
        while (x > right && pixels[start + x] == 0) x--
        if (x > right) right = x
    }
    val x0 = (left - CROP_PADDING).coerceAtLeast(0)
    val y0 = (top - CROP_PADDING).coerceAtLeast(0)
    val x1 = (right + 1 + CROP_PADDING).coerceAtMost(width)
    val y1 = (bottom + 1 + CROP_PADDING).coerceAtMost(height)
    // 本来就没有多余的边
    if (x0 == 0 && y0 == 0 && x1 == width && y1 == height) {
        return bitmap.toDanmakuImage(offset)
    }
    val cropped = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
    bitmap.recycle()
    return cropped.toDanmakuImage(offset + Offset(x0.toFloat(), y0.toFloat()))
}
