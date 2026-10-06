/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.asBitmapOrNull
import com.github.panpf.sketch.disposeLoad
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.transform.BlurTransformation
import com.github.panpf.sketch.transform.Transformation
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
            // 冷启动的启动页等首屏的封面加载出来才撤 (见 AniStartupProgress); 启动页撤掉之后不再挂监听
            if (AniStartupProgress.isTracking) {
                AniStartupProgress.coverStarted()
                addListener(
                    onCancel = { AniStartupProgress.coverFinished() },
                    onError = { _, _ -> AniStartupProgress.coverFinished() },
                    onSuccess = { _, _ -> AniStartupProgress.coverFinished() },
                )
            }
        }
        sketch.enqueue(request)
    }

    /**
     * 圆头像 (演职人员行): [sizePx] 见方的框, 裁剪顶部对齐 (立绘顶部是脸), 请求参数同 Compose 版 AvatarImage (Crop + TopCenter):
     * 同一张头像在两边是同一个缓存键. 解码时就按顶部对齐裁成正方形 ([configureAniImageRequest] 的 SAME_ASPECT_RATIO), ImageView
     * 照常居中裁剪即可.
     */
    fun loadAvatar(sketch: Sketch, view: ImageView, url: String, sizePx: Int, crossfade: Boolean) {
        val requestSize = IntSize(sizePx, sizePx).toAniImageRequestSize()
        val model = bangumiCoverThumbnailUrl(url, requestSize.width) ?: url
        val request = ImageRequest(view, model) {
            configureAniImageRequest(
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                requestSize = requestSize,
            )
            crossfade(crossfade)
            allowNullImage(true)
        }
        sketch.enqueue(request)
    }

    /**
     * 分集剧照 (选集卡): 请求参数同 Compose 版选集卡 (FocusEpisodeCard 的 AsyncImage, decodeAtOriginalSize) —— 按源图原尺寸解、解码时
     * 不裁剪 (裁剪由 ImageView 的 CENTER_CROP 做), 与长按弹窗的背景、播放器的预取是同一个缓存键. 图一到就交给渲染线程预传 GPU
     * ([Bitmap.prepareToDraw]), 同 Compose 版. [onError] 在主线程回调 (调用方按次数退避重试, 同 rememberAsyncImageRetryState).
     */
    fun loadStill(sketch: Sketch, view: ImageView, url: String, crossfade: Boolean, onError: () -> Unit) {
        val request = ImageRequest(view, url) {
            configureAniImageRequest(
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                decodeAtOriginalSize = true,
            )
            crossfade(crossfade)
            allowNullImage(true)
            addListener(
                onError = { _, _ -> onError() },
                onSuccess = { _, result -> result.image.asBitmapOrNull()?.prepareToDraw() },
            )
        }
        sketch.enqueue(request)
    }

    /**
     * 标题 logo (TMDB 的透明底 PNG, 宽高比不定): 按显示大小 [widthPx] × [heightPx] 等比解 (Fit), 解完在解码线程上做 [transformation]
     * (按底色调 logo 的明暗, 见调用方), 不淡入 (logo 与文字标题怎么切换由调用方排版). [onSuccess] 在主线程回调: [transformation] 真的改了图没有,
     * 与解出的位图 (取不到时 null); [onError] 在主线程回调加载失败.
     */
    fun loadLogo(
        sketch: Sketch,
        view: ImageView,
        url: String,
        widthPx: Int,
        heightPx: Int,
        transformation: Transformation?,
        onSuccess: (transformed: Boolean, bitmap: Bitmap?) -> Unit,
        onError: () -> Unit,
    ) {
        val request = ImageRequest(view, url) {
            configureLogo(widthPx, heightPx, transformation)
            addListener(
                onError = { _, _ -> onError() },
                onSuccess = { _, result -> onSuccess(!result.transformeds.isNullOrEmpty(), result.image.asBitmapOrNull()) },
            )
        }
        sketch.enqueue(request)
    }

    /**
     * 提前把 [loadLogo] 要的那张 logo 解进内存缓存 (参数相同就是同一个缓存键), 不上屏: 换内容时旧字还在淡出, 先解好新的, 进场时当场上屏.
     */
    fun preloadLogo(sketch: Sketch, context: Context, url: String, widthPx: Int, heightPx: Int, transformation: Transformation?) {
        sketch.enqueue(ImageRequest(context, url) { configureLogo(widthPx, heightPx, transformation) })
    }

    /**
     * 取 [loadLogo] 那张 logo 的原图 (不变换, 与原样显示的那张同一个缓存键), 不上屏 (量格子颜色用). [onResult] 在主线程回调解出的位图,
     * 失败为 null.
     */
    fun fetchLogo(sketch: Sketch, context: Context, url: String, widthPx: Int, heightPx: Int, onResult: (Bitmap?) -> Unit) {
        val request = ImageRequest(context, url) {
            configureLogo(widthPx, heightPx, null)
            addListener(
                onError = { _, _ -> onResult(null) },
                onSuccess = { _, result -> onResult(result.image.asBitmapOrNull()) },
            )
        }
        sketch.enqueue(request)
    }

    private fun ImageRequest.Builder.configureLogo(widthPx: Int, heightPx: Int, transformation: Transformation?) {
        configureAniImageRequest(
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            requestSize = IntSize(widthPx, heightPx).toAniImageRequestSize(),
        )
        if (transformation != null) transformations(transformation)
        crossfade(false)
        allowNullImage(true)
    }

    /**
     * 数据源图标 (选源面板的行首): [sizePx] 见方的框, 等比缩进框里 (Fit, 同 Compose 版 SourceIcon 的 AsyncImage). 不淡入.
     */
    fun loadIcon(sketch: Sketch, view: ImageView, url: String, sizePx: Int) {
        val request = ImageRequest(view, url) {
            configureAniImageRequest(
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
                requestSize = IntSize(sizePx, sizePx).toAniImageRequestSize(),
            )
            crossfade(false)
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

    /**
     * 解一张背景图只拿位图、不上屏 (提前取主色用): 参数同 [loadBackdrop] —— TMDB w1280 档按原尺寸解, 与详情页和列表页 hero 同一个内存缓存键,
     * 解出来的就是详情页拿去取色的那张位图 (取出的主色一致), 进详情页时内存缓存也直接命中. [widthPx] × [heightPx] = 显示它的图层框.
     * 下载 / 解码失败时 null.
     */
    suspend fun fetchBackdrop(sketch: Sketch, context: Context, url: String, widthPx: Int, heightPx: Int): Bitmap? {
        val request = ImageRequest(context, url) {
            configureAniImageRequest(
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                requestSize = IntSize(widthPx, heightPx).toAniImageRequestSize(),
                decodeAtOriginalSize = tvHeroBackdropDecodeAtOriginalSize(url),
            )
            crossfade(false)
            allowNullImage(true)
        }
        return (sketch.execute(request) as? ImageResult.Success)?.image?.asBitmapOrNull()
    }

    /**
     * 整屏背景图的模糊版 (新番时间表的海报墙底下, 同 tvOS 的模糊底): 按图层框 [widthPx] × [heightPx] 的比例只解长边 [longEdgePx] 的小图
     * (TMDB w1280 正好 1/8 采样), 在 Sketch 的解码线程上模糊 ([blurRadiusPx], 按小图的像素算); 放大交给 GPU 双线性 —— 模糊后的图没有细节,
     * 拉到整屏看不出是小图, 平时每帧也只多画这一张小贴图. 不压暗: 压多深按图的亮度定, 由调用方量了这张小图再用颜色滤镜压.
     * Bangumi 竖版封面 (没有横版图的条目) 换成与海报墙卡片同一档缩略图 ([coverWidthPx] × [coverHeightPx] = 卡片的封面框, 下载缓存命中).
     * 不淡入 (换图的交叉淡入由调用方做); [onResult] 在主线程回调: 已上屏的那张模糊小图, 解不出来时 null.
     */
    fun loadBlurredBackdrop(
        sketch: Sketch,
        view: ImageView,
        url: String,
        widthPx: Int,
        heightPx: Int,
        longEdgePx: Int,
        blurRadiusPx: Int,
        coverWidthPx: Int,
        coverHeightPx: Int,
        onResult: (Bitmap?) -> Unit,
    ) {
        val request = ImageRequest(view, blurredBackdropModel(url, coverWidthPx, coverHeightPx)) {
            configureBlurredBackdrop(widthPx, heightPx, longEdgePx, blurRadiusPx)
            addListener(onError = { _, _ -> onResult(null) }, onSuccess = { _, result -> onResult(result.image.asBitmapOrNull()) })
        }
        sketch.enqueue(request)
    }

    /**
     * 同 [loadBlurredBackdrop] 的那张模糊小图 (参数相同就是同一个内存缓存键: 海报墙上铺过的, 这里直接命中), 只拿位图、不上屏.
     * [coverWidthPx] 传 0 = 竖版封面不换缩略图, 按原地址解. 下载 / 解码失败时 null.
     */
    suspend fun fetchBlurredBackdrop(
        sketch: Sketch,
        context: Context,
        url: String,
        widthPx: Int,
        heightPx: Int,
        longEdgePx: Int,
        blurRadiusPx: Int,
        coverWidthPx: Int,
        coverHeightPx: Int,
    ): Bitmap? {
        val request = ImageRequest(context, blurredBackdropModel(url, coverWidthPx, coverHeightPx)) {
            configureBlurredBackdrop(widthPx, heightPx, longEdgePx, blurRadiusPx)
        }
        return (sketch.execute(request) as? ImageResult.Success)?.image?.asBitmapOrNull()
    }

    /** 模糊背景解哪个地址: Bangumi 竖版封面换成卡片那一档缩略图 (见 [loadBlurredBackdrop]), 其余原样. */
    private fun blurredBackdropModel(url: String, coverWidthPx: Int, coverHeightPx: Int): String {
        val coverRequestWidth = IntSize(coverWidthPx, coverHeightPx).toAniImageRequestSize().width
        return bangumiCoverThumbnailUrl(url, coverRequestWidth) ?: url
    }

    /** 模糊背景小图的解法: 两处 ([loadBlurredBackdrop] / [fetchBlurredBackdrop]) 共用, 内存缓存键才对得上. */
    private fun ImageRequest.Builder.configureBlurredBackdrop(widthPx: Int, heightPx: Int, longEdgePx: Int, blurRadiusPx: Int) {
        configureAniImageRequest(
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            requestSize = IntSize(widthPx, heightPx).toAniImageRequestSize(),
            downsampleLongEdgePx = longEdgePx,
        )
        transformations(BlurTransformation(radius = blurRadiusPx))
        crossfade(false)
        allowNullImage(true)
    }
}
