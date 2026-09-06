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
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.graphics.Color as ComposeColor
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import me.him188.ani.app.ui.foundation.tv.TV_CARD_HERO_BACKDROP_GEOMETRY
import me.him188.ani.app.ui.foundation.tv.tvPageBackdropTreatment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 背景图层 ([TvNativeBackdropView]) 的边缘: 平时左缘 / 下缘渐变到遮罩色 (底下是同色的纯色底); 叠在整屏模糊背景上时
 * ([TvNativeBackdropView.feather]) 同样的渐变改成把图擦成透明, 露出底下那一层 —— 每一格各自一层离屏层, 只擦本格, 图层本身不开层.
 * 图层底下铺一整块蓝, 挂一张整张红的图, 遮罩色给绿, 截窗口看: 平时左缘、下缘是绿, 羽化时露出底下的蓝; 按下即压暗 (层合成时的颜色滤镜)
 * 只压图, 擦掉的边照旧是蓝.
 */
class TvNativeBackdropFeatherTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private lateinit var view: TvNativeBackdropView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            host.root.addView(View(host.activity).apply { setBackgroundColor(Color.BLUE) }, FrameLayout.LayoutParams(WIDTH, HEIGHT))
            view = TvNativeBackdropView(host.activity, host.sketch, scope)
            view.fadeColor = Color.GREEN
            view.treatment = tvPageBackdropTreatment(1f, topScrim = false, fadeColor = ComposeColor.Green, geometry = TV_CARD_HERO_BACKDROP_GEOMETRY)
            host.root.addView(view, FrameLayout.LayoutParams(WIDTH, HEIGHT))
        }
        host.waitUntil("背景图层排好") { view.isLaidOut && view.width == WIDTH }
        host.onMain { view.show(TvNativeBackdropTarget(host.testImage("feather-red", Color.RED, Color.RED), subjectId = 1)) }
        shotWhen("图画出来") { isRed(it.getPixel(INSIDE_X, INSIDE_Y)) }
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    @Test
    fun `edges fade to the mask color by default`() {
        host.onMain {
            assertEquals(View.LAYER_TYPE_NONE, view.layerType)
            assertEquals(listOf(View.LAYER_TYPE_NONE), view.slotLayerTypes())
        }
        val shot = host.windowShot()
        assertTrue(isGreen(shot.getPixel(LEFT_X, MIDDLE_Y)), "左缘应盖满遮罩色 (绿): ${hex(shot.getPixel(LEFT_X, MIDDLE_Y))}")
        assertTrue(isGreen(shot.getPixel(INSIDE_X, BOTTOM_Y)), "下缘应盖满遮罩色 (绿): ${hex(shot.getPixel(INSIDE_X, BOTTOM_Y))}")
        assertTrue(isRed(shot.getPixel(INSIDE_X, INSIDE_Y)))
    }

    @Test
    fun `feathered edges show what is below and each image gets its own layer`() {
        host.onMain {
            view.feather = true
            // 图层本身不开层, 每一格一层
            assertEquals(View.LAYER_TYPE_NONE, view.layerType)
            assertEquals(listOf(View.LAYER_TYPE_HARDWARE), view.slotLayerTypes())
        }
        val shot = shotWhen("左缘露出底下的蓝") { isBlue(it.getPixel(LEFT_X, MIDDLE_Y)) }
        assertTrue(isBlue(shot.getPixel(INSIDE_X, BOTTOM_Y)), "下缘应露出底下的蓝: ${hex(shot.getPixel(INSIDE_X, BOTTOM_Y))}")
        assertTrue(isRed(shot.getPixel(INSIDE_X, INSIDE_Y)), "右上部分照旧是图: ${hex(shot.getPixel(INSIDE_X, INSIDE_Y))}")
        // 关掉回到盖遮罩色
        host.onMain {
            view.feather = false
            assertEquals(listOf(View.LAYER_TYPE_NONE), view.slotLayerTypes())
        }
        shotWhen("左缘回到遮罩色") { isGreen(it.getPixel(LEFT_X, MIDDLE_Y)) }
    }

    @Test
    fun `the press dim darkens the feathered image but not its erased edges`() {
        host.onMain {
            view.feather = true
            // 目标还没跟上: 压暗压到最深后停着不放
            view.dimming = true
            view.triggerPressDim()
        }
        val shot = shotWhen("压暗压到最深") {
            val inside = it.getPixel(INSIDE_X, INSIDE_Y)
            Color.red(inside) < 150 && Color.green(inside) > 100
        }
        assertTrue(isBlue(shot.getPixel(LEFT_X, MIDDLE_Y)), "擦掉的左缘照旧是底下的蓝, 不被压暗的绿盖上: ${hex(shot.getPixel(LEFT_X, MIDDLE_Y))}")
        host.onMain { view.dimming = false }
    }

    /** 反复截本窗口, 直到 [ready]. */
    private fun shotWhen(what: String, ready: (Bitmap) -> Boolean): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 3000
        while (true) {
            val shot = host.windowShot()
            if (ready(shot)) return shot
            if (SystemClock.uptimeMillis() > deadline) fail("等不到: $what")
            SystemClock.sleep(50)
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720

        /** 取点: 右上部分 (没有遮罩; 避开测试窗口顶上盖着的标题栏) / 左缘 / 下缘. */
        const val INSIDE_X = WIDTH - 40
        const val INSIDE_Y = HEIGHT / 4
        const val LEFT_X = 1
        const val MIDDLE_Y = HEIGHT / 2
        const val BOTTOM_Y = HEIGHT - 2

        fun isRed(p: Int) = Color.red(p) > 200 && Color.green(p) < 60 && Color.blue(p) < 60
        fun isGreen(p: Int) = Color.green(p) > 200 && Color.red(p) < 60 && Color.blue(p) < 60
        fun isBlue(p: Int) = Color.blue(p) > 200 && Color.red(p) < 60 && Color.green(p) < 60
        fun hex(p: Int): String = Integer.toHexString(p)
    }
}

/** 背景图层各格的离屏层类型 (最上面那个子视图是图层的遮罩, 不算; 主线程上调). */
internal fun TvNativeBackdropView.slotLayerTypes(): List<Int> = (0 until childCount - 1).map { getChildAt(it).layerType }
