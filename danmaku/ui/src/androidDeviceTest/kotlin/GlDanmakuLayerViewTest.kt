/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真的起一个 [GlDanmakuLayerView], 交给它一帧固定位置的弹幕 (每条一种纯色), 从它的表面读回来逐条看画出来没有.
 */
class GlDanmakuLayerViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: Activity
    private lateinit var view: GlDanmakuLayerView
    private val copyThread = HandlerThread("PixelCopy").apply { start() }

    @BeforeTest
    fun setUp() {
        val intent = Intent(instrumentation.targetContext, Activity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent)
        instrumentation.runOnMainSync {
            view = GlDanmakuLayerView(activity)
            activity.setContentView(
                FrameLayout(activity).apply {
                    addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                },
            )
        }
        instrumentation.waitForIdleSync()
    }

    @AfterTest
    fun tearDown() {
        instrumentation.runOnMainSync {
            view.release()
            activity.finish()
        }
        copyThread.quitSafely()
    }

    private class Expected(val x: Int, val y: Int, val color: Int)

    private fun solid(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun item(bitmap: Bitmap, x: Float, y: Float) =
        DanmakuLayerItem(bitmap, 0f, 0f, floating = false, x = x, y = y, placeNanos = 0L, speedPxPerSecond = 0f)

    private fun colorOf(i: Int): Int = Color.rgb(40 + i * 4 % 200, 250 - i * 3 % 200, i * 37 % 256)

    /** 发一帧, 等它画出来, 返回 [expected] 里画对了的个数 (等到全对或超时). */
    private fun publishAndCount(items: Array<DanmakuLayerItem>, expected: List<Expected>): Int {
        val (width, height) = view.width to view.height
        instrumentation.runOnMainSync {
            view.publish(DanmakuLayerFrame(items, 0L, System.nanoTime(), paused = true, hostWidth = width, hostHeight = height, alpha = 1f))
        }
        var drawn = 0
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val pixels = capture() ?: continue
            drawn = expected.count { e ->
                val p = pixels.getPixel(e.x, e.y)
                Color.alpha(p) == 255 &&
                    Math.abs(Color.red(p) - Color.red(e.color)) <= 2 &&
                    Math.abs(Color.green(p) - Color.green(e.color)) <= 2 &&
                    Math.abs(Color.blue(p) - Color.blue(e.color)) <= 2
            }
            if (drawn == expected.size) break
            Thread.sleep(100)
        }
        return drawn
    }

    private fun capture(): Bitmap? {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(view, bitmap, { result = it; latch.countDown() }, Handler(copyThread.looper))
        latch.await(5, TimeUnit.SECONDS)
        return bitmap.takeIf { result == PixelCopy.SUCCESS }
    }

    @Test
    fun `a single danmaku is drawn`() {
        val bitmap = solid(340, 104, colorOf(1))
        val drawn = publishAndCount(arrayOf(item(bitmap, 200f, 300f)), listOf(Expected(370, 352, colorOf(1))))
        assertEquals(1, drawn)
    }

    @Test
    fun `a screen full of danmaku is drawn even beyond the initial atlas capacity`() {
        // 1080p 下初始图集 2048x1024, 8 个字的弹幕图 (340x104, 1 倍字号) 放得下 45 张; 这里一屏 50 张
        val items = ArrayList<DanmakuLayerItem>()
        val expected = ArrayList<Expected>()
        val columns = view.width / 380
        val rows = view.height / 106
        for (i in 0 until minOf(50, columns * rows)) {
            val x = (i % columns) * 380f
            val y = (i / columns) * 106f
            items += item(solid(340, 104, colorOf(i)), x, y)
            expected += Expected(x.toInt() + 170, y.toInt() + 52, colorOf(i))
        }
        assertEquals(50, items.size, "屏幕要放得下 5x10 格, 实际 ${view.width}x${view.height}")
        assertEquals(50, publishAndCount(items.toTypedArray(), expected), "画出来的弹幕条数")
    }

    @Test
    fun `danmaku beyond what the largest atlas holds are all drawn`() {
        // 60 张 4000x100 的图共 24 MPx, 比最大的图集 (4096x4096, 16.7 MPx) 还大. 图大半透明, 只在 (2000, 40) 有一块 20x20 的色块,
        // 摆成色块在屏上互不重叠: 每张都在屏上, 都要占位置
        val items = ArrayList<DanmakuLayerItem>()
        val expected = ArrayList<Expected>()
        for (i in 0 until 60) {
            val bitmap = Bitmap.createBitmap(4000, 100, Bitmap.Config.ARGB_8888)
            for (x in 2000 until 2020) for (y in 40 until 60) bitmap.setPixel(x, y, colorOf(i))
            val squareX = 100 + (i % 20) * 80
            val y = (i / 20) * 110
            items += item(bitmap, (squareX - 2000).toFloat(), y.toFloat())
            expected += Expected(squareX + 10, y + 50, colorOf(i))
        }
        assertEquals(60, publishAndCount(items.toTypedArray(), expected), "画出来的色块数")
    }

    @Test
    fun `a danmaku wider than the largest atlas is drawn with its own texture`() {
        val color = colorOf(9)
        val bitmap = solid(6000, 104, color)
        val expected = listOf(100, 900, 1800).map { Expected(it, 352, color) }
        assertEquals(3, publishAndCount(arrayOf(item(bitmap, -3000f, 300f)), expected), "超长弹幕上取的三个点")
    }

    @Test
    fun `a shader that does not compile is reported so the caller can draw another way`() {
        val reported = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.release()
            view = GlDanmakuLayerView(activity, onUnavailable = { reported.countDown() }, fragmentShader = "this does not compile")
            activity.setContentView(
                FrameLayout(activity).apply {
                    addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                },
            )
        }
        assertTrue(reported.await(5, TimeUnit.SECONDS), "编不过的着色器要报出来")
    }

    @Test
    fun `a danmaku wider than the initial atlas is drawn`() {
        // 比初始图集 (1080p 下 2048) 还宽: 1 倍字号约 80 个字, 2 倍约 40 个字
        val color = colorOf(7)
        val bitmap = solid(3000, 104, color)
        val expected = listOf(100, 900, 1800).map { Expected(it, 552, color) }
        assertEquals(3, publishAndCount(arrayOf(item(bitmap, -540f, 500f)), expected), "长弹幕上取的三个点")
    }
}
