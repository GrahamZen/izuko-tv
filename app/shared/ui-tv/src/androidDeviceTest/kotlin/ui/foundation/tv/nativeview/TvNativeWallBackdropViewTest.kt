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
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 海报墙底下的整屏背景 ([TvNativeWallBackdropView]): 模糊层解成小图并模糊, 按图的亮度压暗 (亮图压得深), 换图时新图叠在旧图上淡入、满了撤掉
 * 旧图; 清晰层要解了才有、按对焦程度显示, 满了盖住模糊层; 清晰图解好就登记放大转场的整屏框, 换条目撤掉. 图是测试写进缓存目录的 PNG
 * (左右两色, 看得出模糊).
 */
class TvNativeWallBackdropViewTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private lateinit var backdrop: TvNativeWallBackdropView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            backdrop = TvNativeWallBackdropView(host.activity, host.sketch, scope)
            backdrop.maskColor = 0x40000000
            host.root.addView(backdrop, FrameLayout.LayoutParams(1920, 1080))
        }
        host.waitUntil("量出尺寸") { backdrop.width > 0 }
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    /** 模糊层各张 (从底下起). */
    private fun blurred(): List<ImageView> = (0 until backdrop.childCount - 1).map { backdrop.getChildAt(it) as ImageView }

    /** 清晰层 (最上面). */
    private fun sharp(): ImageView = backdrop.getChildAt(backdrop.childCount - 1) as ImageView

    @Test
    fun `the blurred layer is a small blurred decode that fades in`() {
        host.onMain { backdrop.show(TvNativeWallBackdropTarget(host.testImage("blur"), subjectId = 1, sharp = true)) }
        host.waitUntil("模糊层淡满") { blurred().singleOrNull()?.alpha == 1f }
        val (width, edge) = host.onMain {
            val bitmap = renderDrawable(blurred().single())!!
            bitmap.width to bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        }
        assertTrue(width <= TV_WALL_BACKDROP_BLUR_LONG_EDGE_PX * 2, "解的是小图 (宽 $width)")
        // 左白右黑的分界处糊成灰 (再按亮度压一层黑)
        val gray = Color.red(edge)
        assertTrue(gray in 40..215, "分界处应被模糊成灰, 实际 $gray")
    }

    @Test
    fun `a bright image is masked deeper than a dark one`() {
        host.onMain { backdrop.show(TvNativeWallBackdropTarget(host.testImage("dark", left = Color.rgb(30, 30, 30), right = Color.BLACK), subjectId = 1, sharp = false)) }
        host.waitUntil("暗图淡满") { blurred().singleOrNull()?.alpha == 1f }
        // 暗图上白字本来就看得清: 照起步那一份压 (25%)
        assertEquals(0x40, host.onMain { backdrop.topMaskAlpha })
        host.onMain { backdrop.show(TvNativeWallBackdropTarget(host.testImage("bright", left = Color.WHITE, right = Color.WHITE), subjectId = 2, sharp = false)) }
        host.waitUntil("亮图淡满") { blurred().singleOrNull()?.alpha == 1f && backdrop.topMaskAlpha != 0x40 }
        val bright = host.onMain { backdrop.topMaskAlpha }
        assertTrue(bright > 0x40 && bright <= (TV_WALL_BACKDROP_MASK_ALPHA_MAX * 255).toInt() + 1, "亮图应压得更深, 实际 $bright")
    }

    @Test
    fun `a new image fades in over the old one and the old one is dropped once it is full`() {
        host.onMain { backdrop.show(TvNativeWallBackdropTarget(host.testImage("old"), subjectId = 1, sharp = false)) }
        host.waitUntil("第一张淡满") { blurred().singleOrNull()?.alpha == 1f }
        host.onMain {
            backdrop.show(TvNativeWallBackdropTarget(host.testImage("new", left = Color.RED, right = Color.RED), subjectId = 2, sharp = false))
            // 新图解好之前旧图一直满着
            assertEquals(1f, blurred().first().alpha)
        }
        host.waitUntil("新图淡满、旧图撤掉") {
            val only = blurred().singleOrNull()
            only != null && only.alpha == 1f && renderDrawable(only)?.let { Color.red(it.getPixel(2, it.height / 2)) > Color.blue(it.getPixel(2, it.height / 2)) } == true
        }
    }

    @Test
    fun `the sharp layer shows only once decoded and at the given sharpness`() {
        host.onMain {
            backdrop.show(TvNativeWallBackdropTarget(host.testImage("sharp"), subjectId = 3, sharp = true))
            // 停留之前不解
            assertNull(sharp().drawable)
            backdrop.prepareSharp()
        }
        host.waitUntil("清晰图解好") { backdrop.sharpReady }
        host.onMain {
            assertEquals(0f, sharp().alpha)
            backdrop.sharpness = 0.5f
            assertEquals(0.5f, sharp().alpha)
            // 对焦途中清晰层还压着一部分暗 (起步时与模糊层一样暗, 随对焦提亮)
            assertTrue(sharp().colorFilter != null)
            assertTrue(blurred().all { it.visibility == View.VISIBLE })
            backdrop.sharpness = 1f
            assertEquals(1f, sharp().alpha)
            // 对焦满了: 不再压暗, 模糊层整个不画
            assertNull(sharp().colorFilter)
            assertTrue(blurred().all { it.visibility == View.INVISIBLE })
        }
    }

    @Test
    fun `the sharp image is decoded after the target stays for a moment`() {
        host.onMain { backdrop.show(TvNativeWallBackdropTarget(host.testImage("stay"), subjectId = 4, sharp = true)) }
        host.waitUntil("停一小会儿自己解好") { backdrop.sharpReady }
    }

    @Test
    fun `a cover-only target never gets a sharp layer`() {
        host.onMain {
            backdrop.show(TvNativeWallBackdropTarget(host.testImage("cover"), subjectId = 5, sharp = false))
            backdrop.prepareSharp()
            backdrop.sharpness = 1f
        }
        host.waitUntil("模糊层淡满") { blurred().singleOrNull()?.alpha == 1f }
        host.onMain {
            assertTrue(!backdrop.sharpReady)
            assertEquals(0f, sharp().alpha)
            assertTrue(blurred().all { it.visibility == View.VISIBLE })
        }
    }

    @Test
    fun `a decoded sharp image registers the full screen frame for the zoom into details`() {
        host.onMain {
            backdrop.show(TvNativeWallBackdropTarget(host.testImage("zoom"), subjectId = 42, sharp = true))
            backdrop.prepareSharp()
        }
        host.waitUntil("清晰图解好") { backdrop.sharpReady }
        assertTrue(TvHeroZoomHandoff.sourceDebug().startsWith("source=42:"), TvHeroZoomHandoff.sourceDebug())
        // 换了条目: 登记撤掉 (新那张的清晰图还没解)
        host.onMain { backdrop.show(TvNativeWallBackdropTarget(host.testImage("other"), subjectId = 43, sharp = true)) }
        assertTrue(TvHeroZoomHandoff.sourceDebug().startsWith("source=null"), TvHeroZoomHandoff.sourceDebug())
    }

    /** 整张 [color] 的图. */
    private fun solid(name: String, color: Int, subjectId: Int) =
        TvNativeWallBackdropTarget(host.testImage(name, left = color, right = color), subjectId, sharp = true)

    /** 模糊层最上面那张图中间的颜色偏哪个通道 (主线程上调). */
    private fun topColor(): Int? {
        val bitmap = blurred().lastOrNull()?.let { renderDrawable(it) } ?: return null
        val p = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        return when (maxOf(Color.red(p), Color.green(p), Color.blue(p))) {
            Color.red(p) -> Color.RED
            Color.green(p) -> Color.GREEN
            else -> Color.BLUE
        }
    }

    /** 连着换: 先铺满红的, 再换成绿的 (单次换, 当场开始), 等绿的在淡入. */
    private fun startGreenFadeOverRed(prefix: String) {
        host.onMain {
            backdrop.coalesceSwaps = true
            backdrop.show(solid("$prefix-red", Color.RED, 1))
        }
        host.waitUntil("第一张淡满") { blurred().singleOrNull()?.alpha == 1f }
        host.onMain {
            backdrop.show(solid("$prefix-green", Color.GREEN, 2))
            // 单次换 (上一张没在淡入): 当场加上新的一张
            assertEquals(2, blurred().size)
        }
        host.waitUntil("绿的在淡入") { topColor() == Color.GREEN && blurred().last().alpha < 1f }
    }

    @Test
    fun `with coalescing a change during a fade-in waits for it and only the last change is swapped in`() {
        startGreenFadeOverRed("co")
        host.onMain {
            // 绿的还在淡入时连着换两次: 目标当场换 (对焦 / 放大登记按新目标走), 模糊层先不加新的一张
            backdrop.show(solid("co-red2", Color.RED, 3))
            backdrop.show(solid("co-blue", Color.BLUE, 4))
            assertEquals(4, backdrop.currentTarget!!.subjectId)
            assertEquals(2, blurred().size)
        }
        // 绿的淡满后换成最后那张 (蓝), 中间那张 (红) 不露面
        val seen = ArrayList<Int>()
        host.waitUntil("换成最后那张") {
            topColor()?.let { if (seen.lastOrNull() != it) seen += it }
            blurred().size == 1 && blurred().single().alpha == 1f && topColor() == Color.BLUE
        }
        assertEquals(listOf(Color.GREEN, Color.BLUE), seen)
    }

    @Test
    fun `confirming starts a waiting swap at once`() {
        startGreenFadeOverRed("cf")
        host.onMain {
            backdrop.show(solid("cf-blue", Color.BLUE, 3))
            assertEquals(2, blurred().size)
            // 按下确认键: 等着的当场开始换
            backdrop.prepareSharp()
            assertEquals(3, blurred().size)
        }
        host.waitUntil("换成蓝的") { blurred().size == 1 && blurred().single().alpha == 1f && topColor() == Color.BLUE }
    }
}

/**
 * 缓存目录里一张 1280 × 720 的 PNG (TMDB w1280 背景图的尺寸): 左半 [left] 色、右半 [right] 色. 返回 file:// 地址 (Sketch 直接读).
 */
internal fun TvNativeTestHost.testImage(name: String, left: Int = Color.WHITE, right: Int = Color.BLACK): String {
    val file = File(instrumentation.targetContext.cacheDir, "wall-backdrop-$name.png")
    val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        drawColor(right)
        drawRect(0f, 0f, 640f, 720f, Paint().apply { color = left })
    }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return "file://${file.absolutePath}"
}

/** [view] 此刻的图按它自己的尺寸画成位图 (主线程上调); 没有图时 null. */
internal fun renderDrawable(view: ImageView): Bitmap? {
    val drawable = view.drawable ?: return null
    val width = drawable.intrinsicWidth
    val height = drawable.intrinsicHeight
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val bounds = drawable.copyBounds()
    drawable.setBounds(0, 0, width, height)
    drawable.draw(Canvas(bitmap))
    drawable.bounds = bounds
    return bitmap
}
