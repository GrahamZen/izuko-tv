/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.github.panpf.sketch.LocalPlatformContext
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropMaskAlpha
import me.him188.ani.app.ui.foundation.tv.nativeview.tvFetchBlurredBackdrop
import me.him188.ani.app.ui.foundation.tv.nativeview.tvRelativeLuminance

/**
 * 详情页翻离首屏后的整屏底: 这部背景图的模糊版 (同海报墙 hero 态底下铺的那张, 解法与参数相同就是同一个内存缓存键, 海报墙上铺过的直接命中).
 * 一张小图拉伸铺满 (多大、糊多少按设置里详情页那一档, 见 tvBackdropBlurSpec), 不做实时模糊; 透明度不变时不重画. 换了程度先留着旧图, 新图解好再换.
 * 按页面底色压暗, 深浅照次要文字 (`onSurfaceVariant`) 最难看清那一处的对比度定 (见 tvBackdropMaskAlpha, 同新番时间表的模糊底).
 *
 * @param alpha 不透明度, 绘制里读 (首屏 0, 翻页时与清晰图交叉)
 * @param load 开始解图 (放大转场进行中先不解, 不与转场抢)
 * @param onLoaded 模糊图解好、能盖满整屏了
 */
@Composable
internal fun TvDetailsBlurredBackdrop(
    url: String,
    alpha: () -> Float,
    load: Boolean,
    onLoaded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sketch = LocalSketch.current
    val context = LocalPlatformContext.current
    val maskColor = AniThemeDefaults.pageContentBackgroundColor.copy(alpha = TV_FULLSCREEN_BACKDROP_DIM_ALPHA)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val spec = tvBackdropBlurSpec(LocalThemeSettings.current.tvDetailsBackdropBlur)
    val loaded by rememberUpdatedState(onLoaded)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var blurred by remember(url) { mutableStateOf<BlurredBackdrop?>(null) }
    LaunchedEffect(url, size, load, spec) {
        if (!load || size.width <= 0 || size.height <= 0 || blurred?.spec == spec) return@LaunchedEffect
        blurred = loadTvBlurredBackdrop(sketch, context, url, size, spec) ?: return@LaunchedEffect
        loaded()
    }
    TvBlurredBackdropImage(
        blurred,
        alpha = alpha,
        maskColor = maskColor,
        textColor = textColor,
        modifier = modifier.onSizeChanged { size = it },
    )
}

/**
 * 解一张整屏模糊背景小图并量好亮度 (详情页与缓存页选源背景共用; 地址、尺寸、程度相同就命中同一个内存缓存键).
 * 竖版封面按原地址解: 清晰层铺的就是它, 下载缓存里已经有了. 解不出来时 null.
 */
internal suspend fun loadTvBlurredBackdrop(
    sketch: Sketch,
    context: PlatformContext,
    url: String,
    size: IntSize,
    spec: TvBackdropBlurSpec,
): BlurredBackdrop? {
    val image = tvFetchBlurredBackdrop(sketch, context, url, size.width, size.height, spec) ?: return null
    return BlurredBackdrop(image.bitmap, spec, image.worstForLightText, image.worstForDarkText)
}

/**
 * 铺满的模糊背景: 按 [maskColor] 压暗, 深浅照 [textColor] 在图上最难看清那一处的对比度定 (见 tvBackdropMaskAlpha).
 * [image] 为 null 时不画.
 *
 * @param alpha 不透明度, 绘制里读
 */
@Composable
internal fun TvBlurredBackdropImage(
    image: BlurredBackdrop?,
    alpha: () -> Float,
    maskColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val maskAlpha = remember(image, maskColor, textColor) { image?.maskAlpha(maskColor, textColor) ?: 0f }
    Image(
        image?.bitmap ?: EMPTY_BITMAP,
        contentDescription = null,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = if (image == null) 0f else alpha()
                // 只有一张图, 透明度逐笔乘就够, 不开离屏层
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        contentScale = ContentScale.Crop,
        // 压暗画在同一次绘制里 (只盖有图处), 不另开一层
        colorFilter = if (maskAlpha > 0f) ColorFilter.tint(maskColor.copy(alpha = maskAlpha), BlendMode.SrcAtop) else null,
        // 模糊后的图没有细节, 双线性放大就看不出是小图
        filterQuality = FilterQuality.Low,
    )
}

/** 解好的模糊小图 (按 [spec] 解的) 与量好的亮度 (浅色字 / 深色字各自最难看清那一处, 见 tvBackdropWorstLuminance). */
internal class BlurredBackdrop(
    val bitmap: ImageBitmap,
    val spec: TvBackdropBlurSpec,
    private val worstForLightText: Float,
    private val worstForDarkText: Float,
) {
    /** 压暗色 [mask] 压多深上面的 [text] 才看得清 (0..1), 判法同海报墙的模糊层. */
    fun maskAlpha(mask: Color, text: Color): Float {
        val maskLuminance = tvRelativeLuminance(mask.toArgb())
        val textLuminance = tvRelativeLuminance(text.toArgb())
        val lightText = textLuminance >= maskLuminance
        return tvBackdropMaskAlpha(if (lightText) worstForLightText else worstForDarkText, maskLuminance, textLuminance, mask.alpha)
    }
}

/** 还没解好时占位 (透明度恒 0, 不画). */
private val EMPTY_BITMAP = ImageBitmap(1, 1)
