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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.cache.CachePolicy
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.resize.Precision
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.utils.ktor.asScopedHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.github.panpf.sketch.util.Size as SketchSize

/**
 * 清晰度度量本身的单测: 四种判定各钉一条.
 *
 * 这个度量存在的理由见 [measureImageSharpness] —— "看配置"看不出清晰度问题, 所以要有一个能打进
 * 日志、能跨版本对比的数字. 数字算错了就等于没有, 所以它自己也要被钉住.
 */
class AniImageSharpnessTest {
    @Test
    fun `exact 1 to 1 decode`() {
        val sample = measure(source = 900 to 1350, decoded = 200 to 300, box = 200 to 300)
        assertEquals(ImageSharpnessVerdict.EXACT, sample.verdict)
        assertEquals("1.00", sample.drawScale.roundTo2())
        assertEquals(200L * 300, sample.decodedPixels)
        assertEquals(200L * 300, sample.displayedPixels) // 一个像素都没白解
    }

    /**
     * 就是 2026-08-21 那个真实回归: 源图 900×1350 够大, 但 `SAME_ASPECT_RATIO` 只做 2 的幂次
     * 降采样, 解出 113×169 —— 显示时被拉伸 1.8 倍. 这条必须报 [ImageSharpnessVerdict.UNDERSAMPLED].
     */
    @Test
    fun `undersampled decode is blamed on us not on the source`() {
        val sample = measure(source = 900 to 1350, decoded = 113 to 169, box = 200 to 300)
        assertEquals(ImageSharpnessVerdict.UNDERSAMPLED, sample.verdict)
        assertEquals("1.78", sample.drawScale.roundTo2())
        // 显示需要 200×300, 只解出 113×169: 少解了一半多的像素, 缺的那些由拉伸补 = 发虚
        assertTrue(sample.decodedPixels < sample.displayedPixels)
    }

    /** 源图本身比显示尺寸小: 同样被放大, 但这是无解的, 不能算 bug. */
    @Test
    fun `small source is source limited`() {
        val sample = measure(source = 100 to 150, decoded = 100 to 150, box = 200 to 300)
        assertEquals(ImageSharpnessVerdict.SOURCE_LIMITED, sample.verdict)
        assertEquals("2.00", sample.drawScale.roundTo2())
    }

    /** 桌面的 2 倍过采样 (显示 200×300 却解出 400×600): 不伤清晰度, 但要能看出多解了多少像素. */
    @Test
    fun `oversample is reported as waste not as blur`() {
        val sample = measure(source = 900 to 1350, decoded = 400 to 600, box = 200 to 300)
        assertEquals(ImageSharpnessVerdict.OVERSAMPLED, sample.verdict)
        assertEquals(4L, sample.decodedPixels / sample.displayedPixels) // 2 倍边长 = 4 倍像素
    }

    /**
     * `ContentScale.Fit` 的图有一维小于显示框是它**本来的样子** (留白), 不是模糊 ——
     * 若这里按裁剪那套算法取两维的大者, 每一张 Fit 的图都会被误报成欠采样.
     */
    @Test
    fun `fit keeps letterboxing out of the blur metric`() {
        val sample = measure(
            source = 900 to 1350,
            decoded = 200 to 300,
            box = 300 to 300,
            precision = Precision.LESS_PIXELS,
        )
        assertEquals(ImageSharpnessVerdict.EXACT, sample.verdict)
        assertEquals("1.00", sample.drawScale.roundTo2())
    }

    /**
     * 慢解码限频**不能吃掉破纪录的那一次**: 汇总里的 `max=` 必须能在日志里找到对应的图.
     *
     * 2026-08-21 真实踩到: 限频上线后第一轮就出现 `max=3488ms`, 而那张图的行排在配额之后被吞了,
     * 于是"最慢的是哪张"在日志里查无此人.
     */
    @Test
    fun `rate limiting never swallows a new slowest decode`() {
        ImageSharpnessLog.reset()
        // 先用满配额 (SLOW_LOG_MAX_PER_SUMMARY=3), 耗时递减以免自己就是新纪录
        listOf(400L, 300L, 200L).forEach { millis -> ImageSharpnessLog.record(slowSample(millis)) }
        assertEquals(3, ImageSharpnessLog.slowLoggedCount())

        // 配额已满, 但这条破纪录 —— 必须照样记
        ImageSharpnessLog.record(slowSample(3_488))
        assertEquals(4, ImageSharpnessLog.slowLoggedCount())

        // 配额已满且没破纪录的那些, 仍然只计数不记行
        ImageSharpnessLog.record(slowSample(160))
        assertEquals(4, ImageSharpnessLog.slowLoggedCount())
    }

    private fun slowSample(decodeMillis: Long) = requireNotNull(
        measureImageSharpness(
            uri = "https://example.com/slow-$decodeMillis.jpg",
            source = SketchSize(900, 1350),
            decoded = SketchSize(200, 300),
            box = SketchSize(200, 300),
            precision = Precision.EXACTLY,
            decodeMillis = decodeMillis,
            dataFrom = "NETWORK",
        ),
    )

    /** 日志里的 URL 必须去掉 query/fragment: 自建源 (如 Jellyfin) 的图 URL 里带签名/token. */
    @Test
    fun `uri query and fragment are stripped from logs`() {
        val sample = requireNotNull(
            measureImageSharpness(
                uri = "https://jf.example.com/Items/42/Images/Primary?api_key=SECRET#frag",
                source = SketchSize(900, 1350),
                decoded = SketchSize(200, 300),
                box = SketchSize(200, 300),
                precision = Precision.EXACTLY,
                decodeMillis = 7,
                dataFrom = "NETWORK",
            ),
        )
        assertEquals("https://jf.example.com/Items/42/Images/Primary", sample.uri)
        assertTrue("SECRET" !in sample.detail(), sample.detail())
    }

    /** hero 预取用 `size(1,1)` 只为把字节落进磁盘缓存, 不该污染统计. */
    @Test
    fun `prefetch sized requests are not measured`() {
        assertNull(
            measureImageSharpness(
                uri = "https://example.com/a.jpg",
                source = SketchSize(1280, 720),
                decoded = SketchSize(1, 1),
                box = SketchSize(1, 1),
                precision = Precision.SAME_ASPECT_RATIO,
                decodeMillis = 1,
                dataFrom = "NETWORK",
            ),
        )
    }

    private fun measure(
        source: Pair<Int, Int>,
        decoded: Pair<Int, Int>,
        box: Pair<Int, Int>,
        precision: Precision = Precision.EXACTLY,
    ) = requireNotNull(
        measureImageSharpness(
            uri = "https://example.com/a.jpg",
            source = SketchSize(source.first, source.second),
            decoded = SketchSize(decoded.first, decoded.second),
            box = SketchSize(box.first, box.second),
            precision = precision,
            decodeMillis = 7,
            dataFrom = "DOWNLOAD_CACHE",
        ),
    )
}

/**
 * 钉住"日志里的数字来自**真解码**": 上面那组是纯算术, 就算拦截器压根没挂上、或者挂错了位置
 * (比如挂在内存缓存外面, 命中时也计一次), 它们照样全绿.
 */
class AniImageSharpnessInterceptorTest {
    /**
     * 走**真实**请求配置 (2 倍过采样 + 纯裁剪) 时, 落点必须 ≥ 显示尺寸 —— 绘制时只会缩小, 绝不
     * 会被拉伸.
     *
     * 顺便钉住一个反直觉的事实: **"请求 2 倍"不等于"多解 4 倍像素"**. 幂次采样会把落点带到离显示
     * 尺寸最近的那一档 —— 这里源 900×1350 请求 400×600, 实际解出 225×337, 只比显示尺寸多 26%
     * 像素 (不是 300%). 所以那个 2 倍是"欠采样保护", 不是"固定 4 倍开销".
     */
    @Test
    fun `real decode never lands below the display size`() = runTest {
        val sample = decodeAndMeasure(source = IntSize(900, 1350), display = IntSize(200, 300))

        assertEquals(SketchSize(900, 1350), sample.source)
        assertTrue(sample.decoded.width >= 200 && sample.decoded.height >= 300, "decoded=${sample.decoded}")
        assertTrue(sample.drawScale <= 1f, "drawScale=${sample.drawScale} 说明被拉伸了")
        assertTrue(
            sample.verdict == ImageSharpnessVerdict.EXACT || sample.verdict == ImageSharpnessVerdict.OVERSAMPLED,
            "${sample.verdict} decoded=${sample.decoded}",
        )
        assertTrue(sample.decodedPixels < sample.displayedPixels * 2, "多解的像素超过一倍: ${sample.decoded}")
        assertEquals(0, ImageSharpnessLog.verdictCount(ImageSharpnessVerdict.UNDERSAMPLED))
        assertTrue("1 decodes" in ImageSharpnessLog.summary(), ImageSharpnessLog.summary())
    }

    /** 源图比显示小时报"源图不够大", 而不是甩锅给解码参数. */
    @Test
    fun `real decode of a small source reports source limited`() = runTest {
        val sample = decodeAndMeasure(source = IntSize(100, 150), display = IntSize(200, 300))

        assertEquals(ImageSharpnessVerdict.SOURCE_LIMITED, sample.verdict)
        assertEquals(SketchSize(100, 150), sample.decoded) // 没被放大
        assertEquals("2.00", sample.drawScale.roundTo2())
    }

    /** 内存缓存命中不重新解码, 也就不该再计一次 —— 否则"解码次数"和耗时统计全是假的. */
    @Test
    fun `memory cache hits are not counted as decodes`() = runTest {
        val bytes = encodedRaster(900, 1350)
        val client = HttpClient(
            MockEngine { respond(content = bytes, headers = headersOf(HttpHeaders.ContentType, "image/png")) },
        )
        val sketch = createDefaultSketch(PlatformContext.INSTANCE, client.asScopedHttpClient())
        ImageSharpnessLog.reset()
        try {
            val request = ImageRequest(PlatformContext.INSTANCE, "https://example.com/memory-cached.png") {
                configureAniImageRequest(
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    requestSize = IntSize(200, 300),
                )
                downloadCachePolicy(CachePolicy.DISABLED)
                resultCachePolicy(CachePolicy.DISABLED)
                memoryCachePolicy(CachePolicy.ENABLED)
            }
            assertIs<ImageResult.Success>(sketch.execute(request))
            assertIs<ImageResult.Success>(sketch.execute(request))
        } finally {
            sketch.shutdown()
            client.close()
        }

        assertEquals(1, ImageSharpnessLog.decodeCount())
    }

    private suspend fun decodeAndMeasure(source: IntSize, display: IntSize): ImageSharpnessSample {
        val bytes = encodedRaster(source.width, source.height)
        val client = HttpClient(
            MockEngine { respond(content = bytes, headers = headersOf(HttpHeaders.ContentType, "image/png")) },
        )
        val sketch = createDefaultSketch(PlatformContext.INSTANCE, client.asScopedHttpClient())
        ImageSharpnessLog.reset()
        try {
            assertIs<ImageResult.Success>(
                sketch.execute(
                    ImageRequest(PlatformContext.INSTANCE, "https://example.com/s-$source-$display.png") {
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
        } finally {
            sketch.shutdown()
            client.close()
        }
        return requireNotNull(ImageSharpnessLog.lastSample) { "拦截器没有量到这次解码" }
    }

    private fun encodedRaster(width: Int, height: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            surface.canvas.clear(0xFF6750A4.toInt())
            return requireNotNull(
                surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG, 100),
            ).bytes
        } finally {
            surface.close()
        }
    }
}

private fun Float.roundTo2(): String {
    val scaled = (this * 100).toDouble().let { kotlin.math.round(it).toInt() }
    return "${scaled / 100}." + (scaled % 100).toString().padStart(2, '0')
}
