/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.asBitmapOrNull
import com.github.panpf.sketch.disposeLoad
import com.github.panpf.sketch.request.ImageRequest
import me.him188.ani.app.ui.foundation.tv.tvHeroBackdropDecodeAtOriginalSize

/**
 * 电视原生页面 (View) 的图片加载: 请求参数与 Compose 版 [AsyncImage] 完全一致, 走应用的同一个 [Sketch] 实例
 * (内存缓存、下载缓存、缩略图失败回落原图都共用), 同一张图在两边是同一个缓存键.
 *
 * 目标是 sketch-view-core 的 ImageViewTarget: 同一个 View 上的新请求自动取消旧请求; View 不在窗口上时请求挂起, 上窗口才发
 * (RecyclerView 预取的行要等 attach 那一帧才开始取图, 内存缓存命中时在主线程当场上屏、不淡入).
 */
object TvNativeImages {
    /**
     * 竖版封面 (海报墙卡片): 封面框 [widthPx] × [heightPx] 按 [toAniImageRequestSize] 取整, Bangumi 封面换成不小于显示宽度的缩略图档
     * ([bangumiCoverThumbnailUrl]), 请求 2 倍过采样、SAME_ASPECT_RATIO、中心裁剪 ([configureAniImageRequest]).
     * [obscureLongEdgePx] 非 null 时按框比例只解这么长的长边 (NSFW 打码, 同 AsyncImage 的 downsampleLongEdgePx).
     * [url] 为 null 时清掉旧图. 允许空图: 复用的 View 在新图到之前先清掉上一张.
     */
    fun loadCover(
        sketch: Sketch,
        view: ImageView,
        url: String?,
        widthPx: Int,
        heightPx: Int,
        crossfade: Boolean,
        obscureLongEdgePx: Int? = null,
    ) {
        if (url == null) {
            view.disposeLoad()
            view.setImageDrawable(null)
            return
        }
        val requestSize = IntSize(widthPx, heightPx).toAniImageRequestSize()
        val model = bangumiCoverThumbnailUrl(url, requestSize.width) ?: url
        val request = ImageRequest(view, model) {
            configureAniImageRequest(
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                requestSize = requestSize,
                downsampleLongEdgePx = obscureLongEdgePx,
            )
            crossfade(crossfade)
            allowNullImage(true)
        }
        sketch.enqueue(request)
    }

    /** 取消 [view] 上在途的请求并清掉图 (图层整个撤掉时用). */
    fun clear(view: ImageView) {
        view.disposeLoad()
        view.setImageDrawable(null)
    }

    /**
     * 列表页 hero 的背景图 (同 TvCards.kt 的 TvBackdropImage): 中心裁剪; TMDB w1280 档按原尺寸解 ([tvHeroBackdropDecodeAtOriginalSize],
     * 与详情页同一个内存缓存键, 放大进详情页首帧就有图); [obscureLongEdgePx] 非 null 时打码 (降采样, 此时不按原尺寸解).
     * [widthPx] × [heightPx] = 图层框 (按原尺寸解时不用). 不淡入: 换图的交叉淡入由调用方做. [onSuccess] 在主线程回调解出的位图.
     */
    fun loadBackdrop(
        sketch: Sketch,
        view: ImageView,
        url: String,
        widthPx: Int,
        heightPx: Int,
        alpha: Float = 1f,
        obscureLongEdgePx: Int? = null,
        onSuccess: ((Bitmap) -> Unit)? = null,
    ) {
        val decodeAtOriginalSize = obscureLongEdgePx == null && tvHeroBackdropDecodeAtOriginalSize(url)
        val requestSize = IntSize(widthPx, heightPx).toAniImageRequestSize()
        view.alpha = alpha
        val request = ImageRequest(view, url) {
            configureAniImageRequest(
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                requestSize = requestSize,
                decodeAtOriginalSize = decodeAtOriginalSize,
                downsampleLongEdgePx = obscureLongEdgePx,
            )
            crossfade(false)
            allowNullImage(true)
            if (onSuccess != null) {
                addListener(onSuccess = { _, result -> result.image.asBitmapOrNull()?.let(onSuccess) })
            }
        }
        sketch.enqueue(request)
    }
}
