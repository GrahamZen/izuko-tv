/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.cache.CachePolicy
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.resize.Precision
import com.github.panpf.sketch.resize.Scale
import com.github.panpf.sketch.source.DataFrom
import com.github.panpf.sketch.util.Size as SketchSize
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.him188.ani.utils.ktor.asScopedHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * 回归测试: 顶部对齐的裁剪必须保留**顶部**.
 *
 * 角色/制作人员的圆形头像用的是全身立绘, 脸在图片顶部, 所以调用方传的是
 * `ContentScale.Crop` + `Alignment.TopCenter`. 而 sketch 的裁剪发生在**解码时**, 保留哪一侧
 * 由请求里的 [Scale] 决定 —— 一旦退化成中心裁剪, 脸在解码那一刻就没了, Compose 侧的 alignment
 * 再也救不回来 (2026-08-21 真机实测: 头像只剩身体).
 */
class AniCropScaleTest {
    /** 竖图 (立绘) 裁成方形头像: 被裁的是高度, 所以取 alignment 的**纵向**分量. */
    private fun portraitCropScale(alignment: Alignment): Scale =
        scaleDeciderOf(alignment).get(SketchSize(500, 1000), SketchSize(100, 100))

    /** 横图 (backdrop) 裁成方形: 被裁的是宽度, 取**横向**分量. */
    private fun landscapeCropScale(alignment: Alignment): Scale =
        scaleDeciderOf(alignment).get(SketchSize(1000, 500), SketchSize(100, 100))

    private fun scaleDeciderOf(alignment: Alignment) = ImageRequest(
        PlatformContext.INSTANCE,
        "https://example.com/a.jpg",
    ) {
        configureAniImageRequest(
            contentScale = ContentScale.Crop,
            alignment = alignment,
            requestSize = IntSize(100, 100),
        )
    }.scaleDecider

    @Test
    fun `top aligned crop keeps the top`() {
        assertEquals(Scale.START_CROP, portraitCropScale(Alignment.TopCenter))
        assertEquals(Scale.START_CROP, portraitCropScale(Alignment.TopStart))
    }

    @Test
    fun `bottom aligned crop keeps the bottom`() {
        assertEquals(Scale.END_CROP, portraitCropScale(Alignment.BottomCenter))
    }

    @Test
    fun `centered crop stays centered`() {
        assertEquals(Scale.CENTER_CROP, portraitCropScale(Alignment.Center))
    }

    /**
     * 裁哪一维由源图与目标框的比例决定: 横图被裁的是宽度, 于是取 alignment 的横向分量.
     * (fork 早前那版只看纵向偏置, 横图配非居中 alignment 时保留的方向是错的.)
     */
    @Test
    fun `landscape source uses the horizontal component`() {
        assertEquals(Scale.START_CROP, landscapeCropScale(Alignment.TopStart))
        assertEquals(Scale.END_CROP, landscapeCropScale(Alignment.BottomEnd))
        assertEquals(Scale.CENTER_CROP, landscapeCropScale(Alignment.TopCenter))
    }
}

/**
 * 钉住 [configureAniImageRequest] **确实按 alignment 配了 scale**: 上一组测试只管映射本身,
 * 调用点若退回 sketch 的 `toScale()` 它们照样全绿 (实测过).
 */
class AniImageRequestScaleTest {
    @Test
    fun `crop request carries the alignment derived scale`() {
        fun scaleOf(alignment: Alignment) = ImageRequest(
            com.github.panpf.sketch.PlatformContext.INSTANCE,
            "https://example.com/a.jpg",
        ) {
            configureAniImageRequest(
                contentScale = ContentScale.Crop,
                alignment = alignment,
                requestSize = androidx.compose.ui.unit.IntSize(100, 100),
            )
        }.scaleDecider

        // 竖图裁成方形: 取纵向分量
        val portrait = SketchSize(500, 1000)
        val square = SketchSize(100, 100)
        assertEquals(Scale.START_CROP, scaleOf(Alignment.TopCenter).get(portrait, square))
        assertEquals(Scale.CENTER_CROP, scaleOf(Alignment.Center).get(portrait, square))
    }
}

/**
 * 回归测试: **解码落点必须 ≥ 显示尺寸, 而且不能靠软件缩放去凑**.
 *
 * 这两条钉的是同一处 (`configureAniImageRequest`) 上踩过的两个坑, 方向相反:
 * 1. 请求尺寸从"显示×2"改成 1 倍 → `SAME_ASPECT_RATIO` 的幂次采样欠采样 (900×1350 请求 200×300
 *    只解出 113×169), 显示时被拉伸 1.8 倍, **发虚**.
 * 2. 改用 `Precision.EXACTLY` 去拿精确尺寸 → 触发 sketch 的软件缩放
 *    `Canvas.drawBitmap(..., paint = null)`, null Paint 不做双线性过滤 = 最近邻, 横屏剧照一片
 *    **锯齿**. (真机可见; 清晰度日志量像素数, 量不到这个.)
 *
 * 所以正确形态是上游那套: 请求 2 倍 + 纯裁剪, 落点必然 ≥ 显示尺寸, 唯一那次缩放交给 GPU.
 * 下面用真实解码把它钉住 —— 断言的是"不小于显示尺寸", 而不是某个具体数字, 因为幂次采样的
 * 具体落点本来就允许浮动, 会伤到画质的只有"小于".
 */
class AniImageOversampleTest {
    /** 源图够大: 落点不低于显示尺寸 (可以更大, 交给 GPU 缩). */
    @Test
    fun `large source decodes at or above display size`() = runTest {
        assertDecodedAtLeastDisplaySize(source = IntSize(900, 1350), display = IntSize(200, 300))
    }

    /** 跨比例 (竖图裁成方形头像): 两个维度都不能不足. */
    @Test
    fun `square crop of a portrait source covers both dimensions`() = runTest {
        assertDecodedAtLeastDisplaySize(source = IntSize(900, 1350), display = IntSize(96, 96))
    }

    /** 横屏剧照缩到小卡片 —— 就是出锯齿那一类. 同样只要求不欠采样. */
    @Test
    fun `landscape still shrunk into a small card covers the box`() = runTest {
        assertDecodedAtLeastDisplaySize(source = IntSize(1280, 720), display = IntSize(240, 135))
    }

    /** 源图比显示小: 保持原样, **不放大** (放大只白占内存, 一点细节都不会多). */
    @Test
    fun `small source is not upscaled`() = runTest {
        val decoded = decodeWithAniRequest(source = IntSize(100, 150), display = IntSize(200, 300))
        assertEquals(100, decoded.width, "解码宽度")
        assertEquals(150, decoded.height, "解码高度")
    }

    /**
     * 直接钉住"裁剪时不用 EXACTLY": 上面那些断言只看落点大小, 就算哪天又改回 EXACTLY 它们照样
     * 全绿 (EXACTLY 给的落点是精确等于, 也满足"不小于"), 而锯齿会悄悄回来.
     */
    @Test
    fun `crop never asks for EXACTLY`() {
        val request = ImageRequest(PlatformContext.INSTANCE, "https://example.com/a.png") {
            configureAniImageRequest(
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                requestSize = IntSize(200, 300),
            )
        }
        assertEquals(
            "FixedPrecisionDecider(SAME_ASPECT_RATIO)",
            request.precisionDecider.toString(),
            "裁剪必须走纯裁剪路径, 不能触发 sketch 的最近邻软件缩放",
        )
        // 请求尺寸带着 2 倍过采样 (欠采样保护), 而显示尺寸原样记在 extras 里供清晰度度量用
        assertEquals(SketchSize(400, 600), request.sizeResolver.let { runBlocking { it.size() } })
        assertEquals(SketchSize(200, 300), request.aniDisplaySize())
    }

    private fun encodedRaster(format: EncodedImageFormat, width: Int, height: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            surface.canvas.clear(0xFF6750A4.toInt())
            return requireNotNull(surface.makeImageSnapshot().encodeToData(format, 100)).bytes
        } finally {
            surface.close()
        }
    }

    private suspend fun assertDecodedAtLeastDisplaySize(source: IntSize, display: IntSize) {
        val decoded = decodeWithAniRequest(source, display)
        assertTrue(
            decoded.width >= display.width && decoded.height >= display.height,
            "解码落点 ${decoded.width}x${decoded.height} 小于显示尺寸 ${display.width}x${display.height}: 显示时会被拉伸",
        )
    }

    private suspend fun decodeWithAniRequest(source: IntSize, display: IntSize): IntSize {
        val bytes = encodedRaster(EncodedImageFormat.PNG, width = source.width, height = source.height)
        val client = HttpClient(
            MockEngine { respond(content = bytes, headers = headersOf(HttpHeaders.ContentType, "image/png")) },
        )
        val sketch = createDefaultSketch(PlatformContext.INSTANCE, client.asScopedHttpClient())
        try {
            val success = assertIs<ImageResult.Success>(
                sketch.execute(
                    ImageRequest(PlatformContext.INSTANCE, "https://example.com/p-$source-$display.png") {
                        configureAniImageRequest(
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            requestSize = display,
                        )
                        downloadCachePolicy(CachePolicy.DISABLED)
                        resultCachePolicy(CachePolicy.DISABLED)
                        memoryCachePolicy(CachePolicy.DISABLED)
                    },
                ),
            )
            return IntSize(success.image.width, success.image.height)
        } finally {
            sketch.shutdown()
            client.close()
        }
    }
}

/**
 * 回归测试: **请求尺寸的比例必须等于布局框的比例**.
 *
 * `Precision.SAME_ASPECT_RATIO` 是按请求比例在**解码时**裁剪的, 所以请求比例一歪, 画面就被多裁
 * 一圈 —— 不是清晰度问题, 是取景问题, 而且清晰度日志量不到 (它反而"像素更多了").
 *
 * 真实案例 (2026-08-21): 探索页 hero 框 1267×713 (16:9), 两边各自取整成 1280×768 后请求比例变成
 * 1.667, 16:9 源图被裁掉左右各 3.3%, 与旧包对比截图整体大了 7%.
 */
class AniImageRequestSizeAspectTest {
    @Test
    fun `request size keeps the layout aspect ratio`() {
        // 最坏的那一档: 高 713 离 64 的倍数差 55px, 宽 1267 只差 13px
        listOf(
            IntSize(1267, 713), // 探索页 hero (16:9)
            IntSize(224, 320), // 竖封面卡
            IntSize(96, 96), // 圆形头像
            IntSize(1920, 1030), // 详情页 backdrop
            IntSize(455, 256), // 横屏剧照卡
        ).forEach { box ->
            val requested = box.toAniImageRequestSize()
            val boxAspect = box.width.toFloat() / box.height
            val requestedAspect = requested.width.toFloat() / requested.height
            assertTrue(
                kotlin.math.abs(requestedAspect / boxAspect - 1f) < 0.01f,
                "$box -> $requested: 比例 $requestedAspect 与框比例 $boxAspect 差了超过 1%",
            )
            assertTrue(
                requested.width >= box.width && requested.height >= box.height,
                "$box -> $requested: 请求尺寸不能小于布局尺寸 (会欠采样)",
            )
        }
    }

    /** 长边仍然量化, 桌面拖窗口时不会每像素重发一次 (上游加这个取整的原意). */
    @Test
    fun `long edge is still quantised`() {
        assertEquals(
            IntSize(1280, 720).width,
            IntSize(1267, 713).toAniImageRequestSize().width,
        )
        assertEquals(
            IntSize(1267, 713).toAniImageRequestSize(),
            IntSize(1270, 715).toAniImageRequestSize(),
            "长边落在同一档内的相邻尺寸应当合并成同一个请求",
        )
    }
}

/**
 * 回归测试: **同一张图在不同页面上必须落到同一个请求尺寸**.
 *
 * sketch 的内存/结果缓存键含请求尺寸, 差 1 像素就彻底 miss (coil 的键只按 URL, 没这个问题).
 * 而同一张封面在两个页面的框天生差一两个像素: 探索页固定 `width(112.dp)`, 追番页
 * `GridCells.Adaptive(112.dp)` 按列数均分 —— 实测一个 229x320 一个 228x320, 于是换页就要把
 * 2000px 的 JPEG 重解一遍 (190~260ms, 解码只有 4 个并行槽), 追番页明显慢于探索页.
 *
 * **清晰度不受影响**: 吸附一律向上取整, 请求尺寸只会 ≥ 布局尺寸, 解码落点因此仍然不低于显示
 * 尺寸 (那几条真解码测试钉的就是这个); 代价只有请求比例 ≤0.5% 的偏差.
 */
class AniImageRequestSizeSharingTest {
    @Test
    fun `nearby card boxes collapse to one request size`() {
        // 224x311 = 探索页 (112dp 固定), 228x317 = 追番页 (自适应列宽), 都是 0.72 竖版卡
        val exploration = IntSize(224, 311).toAniImageRequestSize()
        val following = IntSize(228, 317).toAniImageRequestSize()
        assertEquals(exploration, following, "两页的封面请求尺寸必须一致, 否则跨页要重解一遍")
    }

    @Test
    fun `snapping never requests less than the layout size`() {
        listOf(
            IntSize(224, 311), IntSize(228, 317), IntSize(96, 96), IntSize(1330, 748),
            IntSize(1267, 713), IntSize(455, 256), IntSize(75, 75),
        ).forEach { box ->
            val requested = box.toAniImageRequestSize()
            assertTrue(
                requested.width >= box.width && requested.height >= box.height,
                "$box -> $requested: 请求尺寸小于布局尺寸就会欠采样 (发虚)",
            )
            val error = kotlin.math.abs(
                (requested.width.toFloat() / requested.height) / (box.width.toFloat() / box.height) - 1f,
            )
            assertTrue(error < 0.01f, "$box -> $requested: 比例偏差 $error 超过 1%, 解码时会多裁一圈")
        }
    }
}

/**
 * 回归测试: `decodeAtOriginalSize` (分集剧照那条路) 的两条硬性质.
 *
 * 背景: 同一张 w780 剧照有三个布局尺寸互不相同的消费端 (详情页选集卡 / 播放器选集条 / 长按弹窗
 * 背景), 而 sketch 的内存缓存键含请求尺寸 —— 按各自布局请求就是各解一份 (常常还都被源图封顶,
 * 解出来一样大、键不同, 纯白解). `Size.Origin` + `LESS_PIXELS` + 钉死的 scale 让它们共用一个键.
 */
class AniOriginalSizeDecodeTest {
    /**
     * LESS_PIXELS + Origin **不做解码期裁剪也不缩放**: TMDB 上存在非 16:9 的剧照, 走
     * `SAME_ASPECT_RATIO` 那套会按请求比例裁掉一条边 —— 这条钉住"解出来就是源图本身".
     */
    @Test
    fun `original size decode never crops non 16-9 stills`() = runTest {
        withStillSketch(source = IntSize(800, 500)) { sketch ->
            val success = assertIs<ImageResult.Success>(sketch.execute(cardRequest(Alignment.Center)))
            assertEquals(800, success.image.width)
            assertEquals(500, success.image.height)
        }
    }

    /**
     * 对齐方式不同的消费端与预取必须共用**同一个内存缓存键**: scale 在缓存键里, 若跟着
     * alignment 走, 弹窗 (Center) 与卡片 (TopCenter) 又会各解一份. 第二、三次必须内存命中.
     */
    @Test
    fun `consumers with different alignment and the prefetch share one decode`() = runTest {
        withStillSketch(source = IntSize(780, 439)) { sketch ->
            val first = assertIs<ImageResult.Success>(sketch.execute(cardRequest(Alignment.Center)))
            assertTrue(first.dataFrom != DataFrom.MEMORY_CACHE, "第一次不该内存命中: ${first.dataFrom}")

            val second = assertIs<ImageResult.Success>(sketch.execute(cardRequest(Alignment.TopCenter)))
            assertEquals(DataFrom.MEMORY_CACHE, second.dataFrom, "对齐方式不同的消费端没共用解码")

            // 预取端手搓的请求 (TvEpisodeScreen) 必须与显示端同键 —— 两边任何一边改了配置都会在这里翻红
            val prefetch = assertIs<ImageResult.Success>(
                sketch.execute(
                    ImageRequest(PlatformContext.INSTANCE, STILL_URL) {
                        downloadCachePolicy(CachePolicy.DISABLED) // 测试免落盘; 不进内存缓存键, 不影响断言
                        memoryCachePolicy(CachePolicy.ENABLED)
                        size(SketchSize.Origin)
                        precision(Precision.LESS_PIXELS)
                        scale(Scale.CENTER_CROP)
                    },
                ),
            )
            assertEquals(DataFrom.MEMORY_CACHE, prefetch.dataFrom, "预取请求与显示端的缓存键不一致")
        }
    }

    /**
     * **Origin 请求绝不能带 `aniDisplaySize`** —— 带了会真机闪退.
     *
     * sketch 的 `AsyncImageState.checkRequest` 校验"键相同的请求内容也必须相同", 而 extras 算内容
     * (键里不含它 ≠ 可以随意变). Origin 路径的 `size()` 恒定, 于是"首帧未测量所以没 extras →
     * 测量后有 extras"正好撞上同键不同内容, 抛 IllegalArgumentException
     * (2026-08-21 实测崩溃三次: `extras different: 'null' vs 'Extras({aniDisplaySize=512x288...})'`).
     */
    @Test
    fun `original size requests never carry a display size`() {
        listOf(null, IntSize(455, 256), IntSize(1200, 675)).forEach { display ->
            val request = ImageRequest(PlatformContext.INSTANCE, STILL_URL) {
                configureAniImageRequest(
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    requestSize = display,
                    decodeAtOriginalSize = true,
                )
            }
            assertNull(request.aniDisplaySize(), "requestSize=$display 时挂上了显示尺寸, 同键不同内容会闪退")
        }
    }

    /** 显示端消费者形状的请求: 与 FocusEpisodeCard / 长按弹窗一致 (Crop + decodeAtOriginalSize). */
    private fun cardRequest(alignment: Alignment) =
        ImageRequest(PlatformContext.INSTANCE, STILL_URL) {
            configureAniImageRequest(
                contentScale = ContentScale.Crop,
                alignment = alignment,
                decodeAtOriginalSize = true,
            )
            downloadCachePolicy(CachePolicy.DISABLED) // 测试免落盘; 不进内存缓存键, 不影响断言
            resultCachePolicy(CachePolicy.DISABLED)
            memoryCachePolicy(CachePolicy.ENABLED)
        }

    private suspend fun withStillSketch(source: IntSize, block: suspend (com.github.panpf.sketch.Sketch) -> Unit) {
        val surface = Surface.makeRasterN32Premul(source.width, source.height)
        val bytes = try {
            surface.canvas.clear(0xFF6750A4.toInt())
            requireNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG, 100)).bytes
        } finally {
            surface.close()
        }
        val client = HttpClient(
            MockEngine { respond(content = bytes, headers = headersOf(HttpHeaders.ContentType, "image/png")) },
        )
        val sketch = createDefaultSketch(PlatformContext.INSTANCE, client.asScopedHttpClient())
        try {
            block(sketch)
        } finally {
            sketch.shutdown()
            client.close()
        }
    }

    private companion object {
        private const val STILL_URL = "https://image.tmdb.org/t/p/w780/original-size-still.jpg"
    }
}
