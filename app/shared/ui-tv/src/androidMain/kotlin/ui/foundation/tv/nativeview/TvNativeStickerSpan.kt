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
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import android.view.View
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.TvNativeImages
import java.lang.ref.WeakReference
import kotlin.math.min

/**
 * 原生文字里的一枚行内表情 (同 Compose 那侧的 tvStickerInlineContent): 占一格 [sizePx] 见方, 图到了画进去 (等比缩进格子, 在这一行里竖直居中),
 * 没到时空着. 不改行的度量: 文字的固定行高 (TvNativeTextView) 照样管住每一行, 格子要不比行高大 (由调用方定 [sizePx]).
 */
internal class TvNativeStickerSpan(private val url: String, private val sizePx: Int) : ReplacementSpan() {
    private val rect = RectF()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = sizePx

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val bitmap = TvNativeStickers.cached(url) ?: return
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = min(sizePx.toFloat() / bitmap.width, sizePx.toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = x + (sizePx - width) / 2f
        val imageTop = top + (bottom - top - height) / 2f
        rect.set(left, imageTop, left + width, imageTop + height)
        canvas.drawBitmap(bitmap, null, rect, BITMAP_PAINT)
    }

    private companion object {
        val BITMAP_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}

/**
 * 行内表情的图: 按地址取一次, 之后各条共用 (表情包就那么几百枚, 每枚只有几 KB). 取的时候记下要用它的文字视图, 到了让它们重画.
 * 只在主线程上用.
 */
internal object TvNativeStickers {
    private val cache = HashMap<String, Bitmap>()
    private val failed = HashSet<String>()
    private val waiting = HashMap<String, MutableList<WeakReference<View>>>()

    fun cached(url: String): Bitmap? = cache[url]

    fun request(sketch: Sketch, view: View, url: String, sizePx: Int) {
        if (url in cache || url in failed) return
        waiting[url]?.let {
            it += WeakReference(view)
            return
        }
        waiting[url] = mutableListOf(WeakReference(view))
        TvNativeImages.fetchSticker(sketch, view.context, url, sizePx) { bitmap ->
            if (bitmap != null) cache[url] = bitmap else failed += url
            waiting.remove(url)?.forEach { it.get()?.invalidate() }
        }
    }
}
