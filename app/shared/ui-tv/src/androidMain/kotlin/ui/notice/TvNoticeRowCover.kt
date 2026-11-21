/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.NSFW_OBSCURED_COVER_LONG_EDGE_PX
import me.him188.ani.app.ui.foundation.TvNativeImages

/**
 * 原生列表行里的封面 (见 [TvNoticeRowView]): 一张圆角、中心裁剪的图. [show] 换一张 (同一张不重取), 取到没有经 [onShown] 告诉行 ——
 * 取到之前、取不到时行照旧画类别图标; [clear] 撤掉.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
internal class TvNoticeRowCover(context: Context, cornerPx: Float, private val onShown: (Boolean) -> Unit) : ImageView(context) {
    private var image: TvNoticeCoverImage? = null

    var cornerPx: Float = cornerPx
        set(value) {
            if (field == value) return
            field = value
            invalidateOutline()
        }

    init {
        scaleType = ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, this@TvNoticeRowCover.cornerPx)
            }
        }
        visibility = GONE
    }

    fun show(next: TvNoticeCoverImage, sketch: Sketch, widthPx: Int, heightPx: Int) {
        if (next == image) return
        image = next
        visibility = VISIBLE
        onShown(false)
        TvNativeImages.loadCover(
            sketch, this, next.url, widthPx, heightPx,
            crossfade = false,
            obscureLongEdgePx = if (next.obscure) NSFW_OBSCURED_COVER_LONG_EDGE_PX else null,
        ) { ok ->
            // 这一行已经换成别的图 (复用到别的通知) 以后才回来的结果不算
            if (image == next) onShown(ok)
        }
    }

    fun clear() {
        if (image == null) return
        image = null
        TvNativeImages.clearCover(this)
        visibility = GONE
        onShown(false)
    }
}
