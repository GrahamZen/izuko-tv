/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.github.panpf.sketch.AsyncImageState
import com.github.panpf.sketch.LocalPlatformContext
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.asBitmapOrNull
import com.github.panpf.sketch.cache.CachePolicy
import com.github.panpf.sketch.cache.DiskCache
import com.github.panpf.sketch.cache.MemoryCache
import com.github.panpf.sketch.decode.supportSvg
import com.github.panpf.sketch.painter.asEquitable
import com.github.panpf.sketch.rememberAsyncImagePainter
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ComposableImageRequest
import com.github.panpf.sketch.request.ImageOptions
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.request.LoadState
import com.github.panpf.sketch.resize.Precision
import com.github.panpf.sketch.resize.Scale
import com.github.panpf.sketch.resize.ScaleDecider
import com.github.panpf.sketch.state.PainterStateImage
import com.github.panpf.sketch.state.StateImage
import com.github.panpf.sketch.util.Size as SketchSize
import com.github.panpf.sketch.util.asComposeImageBitmap
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.platform.files
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.platform.currentPlatform
import me.him188.ani.utils.platform.isDesktop
import me.him188.ani.utils.platform.isIos
import okio.Path
import okio.Path.Companion.toPath
import com.github.panpf.sketch.AsyncImage as SketchAsyncImage

private const val MEBIBYTE = 1024L * 1024L
/**
 * 上游是 100 MiB; fork 提到 300 MiB —— 电视上除封面之外还要缓存 TMDB 的 backdrop 与分集剧照
 * (单张就有几百 KB 到数 MB), 100 MiB 会让上周看过的作品回来时又要重下一遍.
 */
private const val IMAGE_DOWNLOAD_CACHE_SIZE = 300L * MEBIBYTE
internal const val ANI_IMAGE_CACHE_DIRECTORY = "image-cache"

val LocalSketch = staticCompositionLocalOf<Sketch> {
    error("No Ani image loader provided")
}

/** A library-neutral successful image load result exposed to feature UI modules. */
@Immutable
data class AniImageLoadSuccess(
    val bitmap: ImageBitmap?,
    val width: Int,
    val height: Int,
)

internal fun ImageResult.Success.toAniImageLoadSuccess(): AniImageLoadSuccess = AniImageLoadSuccess(
    bitmap = image.asBitmapOrNull()?.asComposeImageBitmap(),
    width = imageInfo.width,
    height = imageInfo.height,
)

@Stable
inline val defaultFilterQuality: FilterQuality
    get() = if (currentPlatform().isDesktop()) FilterQuality.High else FilterQuality.Low

/** Owns the application Sketch instance and keeps Sketch types inside ui-foundation. */
@Composable
fun rememberAniSketchInstance(client: ScopedHttpClient): Sketch {
    val context = LocalPlatformContext.current
    val appCacheRoot = LocalContext.current.files.cacheDir.absolutePath.toPath()
    val sketch = remember(context, client, appCacheRoot) {
        cleanUpLegacyCoilDiskCacheAsync(appCacheRoot)
        createDefaultSketch(context, client, appCacheRoot.resolve(ANI_IMAGE_CACHE_DIRECTORY))
    }
    DisposableEffect(sketch) {
        onDispose(sketch::shutdown)
    }
    return sketch
}

@Composable
fun AsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholder: Painter? = null,
    error: Painter? = null,
    fallback: Painter? = error,
    onLoading: (() -> Unit)? = null,
    onSuccess: ((AniImageLoadSuccess) -> Unit)? = null,
    onError: ((Throwable?) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = defaultFilterQuality,
    clipToBounds: Boolean = true,
    crossfade: Boolean? = null,
    crossfadeDurationMillis: Int? = null,
    decodeAtOriginalSize: Boolean = false,
) {
    val state = rememberAsyncImageState()
    AniAsyncImage(
        model = model,
        contentDescription = contentDescription,
        state = state,
        modifier = modifier,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        onLoading = onLoading,
        onSuccess = onSuccess,
        onError = onError,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality,
        clipToBounds = clipToBounds,
        crossfade = crossfade,
        crossfadeDurationMillis = crossfadeDurationMillis,
        decodeAtOriginalSize = decodeAtOriginalSize,
    )
}

@Composable
internal fun AniAsyncImage(
    model: String?,
    contentDescription: String?,
    state: AsyncImageState,
    modifier: Modifier = Modifier,
    placeholder: Painter? = null,
    error: Painter? = null,
    fallback: Painter? = error,
    onLoading: (() -> Unit)? = null,
    onSuccess: ((AniImageLoadSuccess) -> Unit)? = null,
    onError: ((Throwable?) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = defaultFilterQuality,
    clipToBounds: Boolean = true,
    crossfade: Boolean? = null,
    crossfadeDurationMillis: Int? = null,
    decodeAtOriginalSize: Boolean = false,
) {
    var requestSize by remember { mutableStateOf<IntSize?>(null) }

    // **测到布局尺寸之前不发请求** (decodeAtOriginalSize 除外, 它与尺寸无关).
    // 否则首次组合就把无尺寸的 request 交给 sketch 先打一发, 首帧布局后 onSizeChanged 一设
    // requestSize, request 内容变化 -> sketch 取消旧请求重来 —— 每张图两次请求、第一次必
    // CANCELLED (真机日志: 115 个人物头像 URL 逐一"先取消再成功", API 请求量直接翻倍).
    // 这里只差一帧 (下一帧 onSizeChanged 即到), 视觉上与 placeholder 首帧无异.
    if (!decodeAtOriginalSize && requestSize == null) {
        Box(
            modifier.onSizeChanged { size ->
                val roundedSize = size.toAniImageRequestSize()
                if (requestSize != roundedSize) requestSize = roundedSize
            },
        )
        return
    }

    val placeholderStateImage = rememberStateImage(placeholder, "placeholder")
    val errorStateImage = rememberStateImage(error, "error")
    val fallbackStateImage = rememberStateImage(fallback, "fallback")

    val request = ComposableImageRequest(model) {
        if (placeholderStateImage != null) placeholder(placeholderStateImage)
        if (errorStateImage != null) error(errorStateImage)
        if (fallbackStateImage != null) fallback(fallbackStateImage)

        configureAniImageRequest(
            contentScale = contentScale,
            alignment = alignment,
            requestSize = requestSize,
            decodeAtOriginalSize = decodeAtOriginalSize,
        )

        when {
            crossfade == false -> crossfade(false)
            crossfadeDurationMillis != null -> crossfade(crossfadeDurationMillis)
            crossfade == true -> crossfade(true)
        }
    }

    ImageLoadStateEffect(state, onLoading, onSuccess, onError)
    SketchAsyncImage(
        request = request,
        sketch = LocalSketch.current,
        contentDescription = contentDescription,
        modifier = if (decodeAtOriginalSize) {
            // Origin 请求与布局尺寸无关, 也不许因尺寸变化改变请求内容 (见 configureAniImageRequest
            // 里那段崩溃记录) —— 不跟踪尺寸, 请求从头到尾只有一个
            modifier
        } else {
            modifier.onSizeChanged { size ->
                val roundedSize = size.toAniImageRequestSize()
                if (requestSize != roundedSize) requestSize = roundedSize
            }
        },
        state = state,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality,
        clipToBounds = clipToBounds,
    )
}

@Composable
private fun ImageLoadStateEffect(
    state: AsyncImageState,
    onLoading: (() -> Unit)?,
    onSuccess: ((AniImageLoadSuccess) -> Unit)?,
    onError: ((Throwable?) -> Unit)?,
) {
    val currentOnLoading by rememberUpdatedState(onLoading)
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val currentOnError by rememberUpdatedState(onError)

    val loadState = state.loadState

    LaunchedEffect(loadState) {
        dispatchImageLoadState(
            loadState = loadState,
            onLoading = currentOnLoading,
            onSuccess = currentOnSuccess,
            onError = currentOnError,
        )
    }
}

internal fun dispatchImageLoadState(
    loadState: LoadState?,
    onLoading: (() -> Unit)?,
    onSuccess: ((AniImageLoadSuccess) -> Unit)?,
    onError: ((Throwable?) -> Unit)?,
) {
    if (loadState == null) return

    when (loadState) {
        is LoadState.Started -> onLoading?.invoke()
        is LoadState.Success -> onSuccess?.invoke(loadState.result.toAniImageLoadSuccess())
        is LoadState.Error -> onError?.invoke(loadState.result.throwable)
        is LoadState.Canceled -> Unit
    }
}

@Composable
private fun rememberStateImage(painter: Painter?, role: String): StateImage? =
    remember(painter, role) {
        painter?.let {
            PainterStateImage(it.asEquitable(role to it))
        }
    }

@Composable
internal fun rememberAniAsyncImagePainter(
    model: String?,
    contentScale: ContentScale,
    requestSize: IntSize?,
    filterQuality: FilterQuality = defaultFilterQuality,
    state: AsyncImageState? = null,
): Painter {
    val rememberedState = rememberAsyncImageState()
    val finalState = state ?: rememberedState
    val request = ComposableImageRequest(model) {
        configureAniImageRequest(
            contentScale = contentScale,
            alignment = Alignment.Center,
            requestSize = requestSize,
        )
    }
    return rememberAsyncImagePainter(
        request = request,
        sketch = LocalSketch.current,
        state = finalState,
        contentScale = contentScale,
        filterQuality = filterQuality,
    )
}

/**
 * 请求尺寸相对显示尺寸的倍数, **一律 2**(与上游一致).
 *
 * 看着像浪费(2 倍边长 = 4 倍像素, 电视上一张卡就是几百 KB 到几 MB 的位图), 我确实试过在移动端/
 * 电视改回 1 倍, 结果连踩两个坑, 所以这里记清楚**这个 2 有两层用意**, 别再动:
 *
 * 1. **抬过欠采样**. [Precision.SAME_ASPECT_RATIO] 只做 2 的幂次降采样, 比例本来就一致时不触发
 *    精确缩放 —— 请求 1 倍时实测源 900×1350 只解出 113×169, 比显示尺寸还小, 显示时被拉伸 1.8 倍
 *    (明显发虚). 请求 2 倍后, 幂次采样最多欠 2 倍, 落点因此**必然 ≥ 显示尺寸**.
 * 2. **避开 sketch 的软件缩放**(这层更要命, 2026-08-21 才查出来). 只要解码后的尺寸不等于目标,
 *    [Precision.EXACTLY] 就会走 `Bitmaps_androidKt.mapping()` 做一次软件缩放, 而它是
 *    `Canvas.drawBitmap(bitmap, srcRect, dstRect, paint = null)` —— **null Paint 的
 *    `isFilterBitmap` 是 false, 即最近邻**, 横屏剧照缩下来一片锯齿(真机可见). 而
 *    `SAME_ASPECT_RATIO` 走的是"幂次采样 + 纯裁剪"(srcRect 与 dstRect 同尺寸, 不插值),
 *    唯一那次缩放留给 GPU 的双线性 —— 与 coil 时代等价.
 *
 * 结论: **不要为了省像素改成 EXACTLY 或降低这个倍数**. 想省解码量只能从别处想办法(减少不同的
 * 请求尺寸、复用位图), 不能拿插值质量换 —— 清晰度日志量得到像素数, 量不到锯齿.
 */
internal const val ANI_IMAGE_REQUEST_OVERSAMPLE: Int = 2

/**
 * Sketch's default [Precision.LESS_PIXELS] only matches the target's total pixel count. When a
 * portrait source is drawn into a landscape crop (or vice versa), that can decode one dimension
 * below the target and force Compose to upscale it. Crop to the target aspect while decoding so
 * both displayed dimensions have enough pixels.
 */
internal fun ImageRequest.Builder.configureAniImageRequest(
    contentScale: ContentScale,
    alignment: Alignment,
    requestSize: IntSize? = null,
    oversample: Int = ANI_IMAGE_REQUEST_OVERSAMPLE,
    decodeAtOriginalSize: Boolean = false,
) {
    if (decodeAtOriginalSize) {
        // 按源图原尺寸解码, 给"同一 URL、多个尺寸互不相同的消费端"用 (分集剧照: 详情页卡片 /
        // 播放器选集条 / 长按弹窗背景). 内存缓存键含请求尺寸, 按各自布局请求就是同一张图解三份
        // (还常常都被源图封顶, 解出来一样大、键不同, 纯白解); 钉成 Origin 后三处 + 预取共用同
        // 一个键, 谁先解谁受益.
        //
        // LESS_PIXELS **不做解码期裁剪** (非 16:9 的剧照不会被裁掉一条边), Compose 端照常 Crop;
        // 也不做精确缩放, 不会踩 EXACTLY 的最近邻软件缩放 (见 ANI_IMAGE_REQUEST_OVERSAMPLE).
        // scale 钉成常量: LESS_PIXELS 用不到它, 但它在缓存键里 —— 跟着 alignment 走的话,
        // 对齐方式不同的消费端又会各解一份.
        size(SketchSize.Origin)
        precision(Precision.LESS_PIXELS)
        scale(Scale.CENTER_CROP)
        // **这条路上绝不能挂 aniDisplaySize** (试过, 真机崩溃): sketch 的
        // `AsyncImageState.checkRequest` 校验"键相同的请求内容也必须相同", 而 extras 算内容 ——
        // 键里不含它并不代表可以随意变. 常规路径没事是因为 size() 跟着一起变、键本来就变了;
        // Origin 路径 size() 恒定, 于是"首帧还没测量所以没 extras → 测量后有 extras"正好撞上
        // 同键不同内容, 抛 IllegalArgumentException 直接闪退 (2026-08-21 实测三次).
        // 解码可观测性改由拦截器那侧兜: 没有显示尺寸的解码照旧计数并可单独记行 (量不了清晰度,
        // 但"这批图到底解没解、花了多久"看得见), 见 AniImageSharpness 的 recordUnmeasured.
        return
    }
    if (requestSize != null && requestSize.width > 0 && requestSize.height > 0) {
        size(requestSize.width * oversample, requestSize.height * oversample)
        // 顺手把"这张图要显示成多大"记进请求 (不进缓存键/请求键, 见 aniDisplaySize):
        // 解码时要拿它算清晰度 —— 只看 size() 的话就得反推 oversample, 反推错了度量就是错的.
        setAniDisplaySize(requestSize.width, requestSize.height)
    }
    scale(aniScaleDecider(contentScale, alignment))
    when (contentScale) {
        // 绝不用 EXACTLY: 见 ANI_IMAGE_REQUEST_OVERSAMPLE 第 2 条 (最近邻锯齿)
        ContentScale.Crop -> precision(Precision.SAME_ASPECT_RATIO)
        // FillBounds 是"拉满两边", 语义上必须精确, 只能吃那次软件缩放 (fork 里几乎没人用)
        ContentScale.FillBounds -> precision(Precision.EXACTLY)
    }
}

/**
 * Sketch's built-in alignment conversion only considers the horizontal component. For example,
 * [Alignment.TopCenter] becomes [Scale.CENTER_CROP], which discards the top before Compose draws
 * the image. Select the relevant alignment component after Sketch knows the source aspect ratio.
 */
private fun aniScaleDecider(contentScale: ContentScale, alignment: Alignment): ScaleDecider {
    if (
        contentScale == ContentScale.FillBounds ||
        contentScale == ContentScale.FillWidth ||
        contentScale == ContentScale.FillHeight
    ) {
        return ScaleDecider(Scale.FILL)
    }

    val (horizontalScale, verticalScale) = when (alignment) {
        Alignment.TopStart -> Scale.START_CROP to Scale.START_CROP
        Alignment.TopCenter -> Scale.CENTER_CROP to Scale.START_CROP
        Alignment.TopEnd -> Scale.END_CROP to Scale.START_CROP
        Alignment.CenterStart -> Scale.START_CROP to Scale.CENTER_CROP
        Alignment.Center -> Scale.CENTER_CROP to Scale.CENTER_CROP
        Alignment.CenterEnd -> Scale.END_CROP to Scale.CENTER_CROP
        Alignment.BottomStart -> Scale.START_CROP to Scale.END_CROP
        Alignment.BottomCenter -> Scale.CENTER_CROP to Scale.END_CROP
        Alignment.BottomEnd -> Scale.END_CROP to Scale.END_CROP
        else -> Scale.CENTER_CROP to Scale.CENTER_CROP
    }
    return AniAlignmentScaleDecider(horizontalScale, verticalScale)
}

private data class AniAlignmentScaleDecider(
    val horizontalScale: Scale,
    val verticalScale: Scale,
) : ScaleDecider {
    override val key: String = "AniAlignment($horizontalScale,$verticalScale)"

    override fun get(imageSize: SketchSize, targetSize: SketchSize): Scale {
        if (
            imageSize.width <= 0 || imageSize.height <= 0 ||
            targetSize.width <= 0 || targetSize.height <= 0
        ) {
            return Scale.CENTER_CROP
        }

        val imageAspectProduct = imageSize.width.toLong() * targetSize.height
        val targetAspectProduct = targetSize.width.toLong() * imageSize.height
        return when {
            imageAspectProduct > targetAspectProduct -> horizontalScale
            imageAspectProduct < targetAspectProduct -> verticalScale
            else -> Scale.CENTER_CROP
        }
    }
}

/**
 * Keep decoded dimensions at or above the layout size while avoiding a new request for every pixel
 * of a desktop window resize.
 *
 * **只对长边取整, 短边按真实框比例推出来** —— 上游是两边各自取整, 那会把请求比例歪掉, 而
 * [Precision.SAME_ASPECT_RATIO] 是按**请求比例**在解码时裁剪的, 于是画面被多裁一圈:
 *
 * 实测 (2026-08-21, 探索页 hero, 1080p): 真实框 1267×713 (16:9), 两边各自取整成 1280×768,
 * 请求比例变成 1.667 —— 16:9 的源图在解码时被裁成 1200×720 (左右各切 3.3%), Compose 再放大
 * 1.056 倍填满框. 与旧包 (coil 不在解码时裁剪) 对比截图, hero 整体大了 **7%**, 少看到一圈.
 *
 * 长边仍按 8/16/64 取整, 所以"桌面拖窗口不要每像素重发"这个原意还在; 短边跟着长边按比例走,
 * 比例误差 ≤1px, 裁掉的那点看不见.
 */
internal fun IntSize.toAniImageRequestSize(): IntSize {
    if (width <= 0 || height <= 0) return this
    return if (width >= height) {
        val rounded = width.roundUpImageRequestDimension()
        IntSize(rounded, scaleOtherDimension(from = width, to = rounded, other = height))
    } else {
        val rounded = height.roundUpImageRequestDimension()
        IntSize(scaleOtherDimension(from = height, to = rounded, other = width), rounded)
    }
}

/**
 * 按长边的取整倍数放大短边, **向上取整** —— 短边也必须 ≥ 布局尺寸, 否则又变成欠采样.
 *
 * 短边由**桶化后的长宽比**推出, 为的是**让相邻的框塌到同一个请求尺寸**: sketch 的内存/结果缓存键
 * 含请求尺寸, 差 1 像素就是彻底 miss (coil 的键只按 URL, 所以这个问题它没有). 而同一张封面在
 * 不同页面的框天生差一两个像素 —— 探索页是固定 `width(112.dp)`, 追番页是
 * `GridCells.Adaptive(112.dp)` 按列数均分 —— 于是实测一个 `box~229x320` 一个 `box~228x320`,
 * 换个页面就要把 2000px 的 JPEG 重解一遍 (190~260ms, 而解码只有 4 个并行槽), 追番页因此明显
 * 慢于探索页 (2026-08-21 用户报告 + 日志确认).
 *
 * 桶数取 1/256 是两个要求的折中: 太粗会歪掉请求比例 (那会让 [Precision.SAME_ASPECT_RATIO] 在
 * 解码时多裁一圈, 见 [toAniImageRequestSize] 里 hero 那个 7% 的实例), 1/256 带来的比例误差实测
 * 是封面 0.66%、hero 0.63%, 看不见.
 */
private fun scaleOtherDimension(from: Int, to: Int, other: Int): Int {
    // 先把长宽比桶化 (向上取整), 再由长边推短边. 两次都向上取整 = 请求尺寸恒 ≥ 布局尺寸,
    // 所以**清晰度不受影响**; 桶化只让"差一两个像素的框"落进同一个桶, 从而共用缓存.
    val ratioBucket = (other.toLong() * ASPECT_RATIO_BUCKETS + from - 1) / from
    val scaled = (to.toLong() * ratioBucket + ASPECT_RATIO_BUCKETS - 1) / ASPECT_RATIO_BUCKETS
    return scaled.toInt().coerceAtLeast(other)
}

/**
 * 长宽比的桶数. 1/256 一档: 页面之间因像素取整产生的比例差 (0.1~0.2%) 会落进同一桶而合并,
 * 而单桶带来的取景偏差 <1% (实测封面 0.66%、hero 0.63%), 看不见.
 */
private const val ASPECT_RATIO_BUCKETS = 256L

private fun Int.roundUpImageRequestDimension(): Int {
    if (this <= 0) return this
    val step = when {
        this <= 64 -> 8
        this <= 256 -> 16
        else -> 64
    }
    return ((this + step - 1) / step) * step
}

internal fun createDefaultSketch(
    context: PlatformContext,
    client: ScopedHttpClient,
    cacheDirectory: Path? = null,
): Sketch = Sketch.Builder(context).apply {
    componentLoaderEnabled(false)
    // 内存缓存**保留** (走 sketch 默认的 LRU: Android 上占堆的 25~33%), 与上游不同 ——
    // 上游换成了 DisabledMemoryCache, 理由是"别让请求把解码后的位图留在内存里".
    // 但电视上这个代价太大: 一张全屏 backdrop/剧照解一次要几十毫秒, 而遥控器导航天然是"来回走"
    // (A→B→A 极常见), 没有内存缓存就每次都从磁盘字节重解码 —— 网格滚动与 hero 换图肉眼可见地卡.
    // fork 在 coil 时代就是显式开着的 (maxSizePercent), 那条注释记的是同一件事.
    downloadCacheOptions(
        DiskCache.Options(
            directory = cacheDirectory?.resolve("download"),
            maxSize = IMAGE_DOWNLOAD_CACHE_SIZE,
        ),
    )
    resultCacheOptions(
        DiskCache.Options(
            directory = cacheDirectory?.resolve("result"),
        ),
    )
    globalImageOptions(
        ImageOptions {
            downloadCachePolicy(CachePolicy.ENABLED)
            memoryCachePolicy(CachePolicy.ENABLED) // 见上: fork 保留内存缓存

            // Result cache re-encodes transformed images. Keep the original bytes in the LRU
            // download cache instead so disk caching cannot reduce image quality.
            resultCachePolicy(CachePolicy.DISABLED)
            crossfade(!currentPlatform().isIos())
        },
    )
    addComponents {
        add(ScopedHttpClientHttpUriFetcherFactory(ScopedHttpClientHttpStack(client)))
        supportSvg()
        // componentLoaderEnabled(false) 关掉了自动注册, 动图要手动来 (Bangumi 表情包有动图)
        addAniAnimatedDecoders()
        // 只观察不改结果: 把每次解码的"清晰度"量成数字打进日志 (见 AniImageSharpness.kt)
        addRequestInterceptor(AniImageSharpnessInterceptor)
    }
}.build()

/**
 * 异步删除 coil 时代遗留的磁盘缓存目录.
 *
 * 图片库从 coil3 换成 sketch 时缓存目录跟着换了 (`coil3_disk_cache` → [ANI_IMAGE_CACHE_DIRECTORY]),
 * 旧目录没法迁移 (键与文件格式都不同, 命中在换库那一刻就已经丢了), 也再没有代码引用它 ——
 * 但它会一直占着最多 300 MiB, 而设置页的"清理图片缓存"清的只是 sketch 的新目录.
 * 删一次, 之后每次启动都是空操作.
 *
 * 只从**明确的应用缓存根目录**出发 ([rememberAniSketchInstance] / `aniSharedSketch` 才持有它),
 * 不放进 `createDefaultSketch` 从缓存目录反推 parent —— 测试会把任意临时目录传给后者,
 * 反推会把删除范围摸出调用方交出的目录之外.
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun cleanUpLegacyCoilDiskCacheAsync(appCacheRoot: Path) {
    val legacy = appCacheRoot.resolve("coil3_disk_cache")
    // GlobalScope: 进程级的一次性清理, 不隶属任何界面生命周期; 失败也无所谓, 下次启动再试
    GlobalScope.launch(Dispatchers.IO_) {
        runCatching { kotlinx.io.files.Path(legacy.toString()).inSystem.deleteRecursively() }
    }
}

/** Prevents requests from retaining decoded images while the download disk cache stays enabled. */
private data object DisabledMemoryCache : MemoryCache {
    private val mutex = Mutex()

    override val maxSize: Long = 0L
    override val size: Long = 0L

    override fun put(key: String, value: MemoryCache.Value): Int = -3

    override fun remove(key: String): MemoryCache.Value? = null

    override fun get(key: String): MemoryCache.Value? = null

    override fun exist(key: String): Boolean = false

    override fun trim(targetSize: Long) = Unit

    override fun keys(): Set<String> = emptySet()

    override fun entries(): Set<Map.Entry<String, MemoryCache.Value>> = emptySet()

    override fun clear() = Unit

    override suspend fun <R> withLock(
        key: String,
        action: suspend MemoryCache.() -> R,
    ): R = mutex.withLock { action(this) }
}

/** Provides a deterministic, network-free image loader for previews and screenshot tests. */
@PublishedApi
@Composable
internal fun rememberAniPreviewSketch(previewPainter: Painter): Sketch {
    val context = LocalPlatformContext.current
    val sketch = remember(context, previewPainter) {
        val previewStateImage = PainterStateImage(
            previewPainter.asEquitable("ani-preview-image"),
        )
        Sketch.Builder(context)
            .componentLoaderEnabled(false)
            .memoryCache(DisabledMemoryCache)
            .globalImageOptions(
                ImageOptions {
                    placeholder(previewStateImage)
                    error(previewStateImage)
                    fallback(previewStateImage)
                    downloadCachePolicy(CachePolicy.DISABLED)
                    memoryCachePolicy(CachePolicy.DISABLED)
                    resultCachePolicy(CachePolicy.DISABLED)
                    crossfade(false)
                },
            )
            .build()
    }
    DisposableEffect(sketch) {
        onDispose(sketch::shutdown)
    }
    return sketch
}
