/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.graphics.Color as ComposeColor
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import me.him188.ani.app.ui.foundation.tv.TV_CARD_HERO_BACKDROP_GEOMETRY
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.tvPageBackdropTreatment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 网格页 (追番 / 搜索) 开了「hero 态铺模糊背景」([TvNativeGridPageView.heroBlur]): hero 态在整页底下铺整部横版背景图的模糊版, 贴右上角的
 * 背景图 (这里是另一张: 单集剧照) 照旧画在上面; hero 态里按确定背景图、卡片与 hero 文字 (标题以外) 淡没, 模糊背景对焦变清晰, 到位才进
 * 详情页; 回来倒放; 长按照旧只弹菜单; 退出 hero 态整屏背景撤掉. 进详情页的放大转场从整屏背景起, 不从背景图起.
 * 开着过渡跑 (模拟器开着系统动画). 要在对焦途中断言的, 按键直接派发给页面、同一轮主线程消息里接着做 ([confirmThen]).
 */
class TvNativeGridPageHeroBlurTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = Listener()
    private lateinit var page: TvNativeGridPageView

    private class Listener : TvNativeGridPageListener {
        val clicked = mutableListOf<Int>()
        val opened = mutableListOf<Int>()
        val longPressed = mutableListOf<Int>()
        var fade = 0f

        override fun onFocused(index: Int) = Unit
        override fun onClick(index: Int) {
            clicked += index
        }

        override fun onLongPress(index: Int, anchor: Rect) {
            longPressed += index
        }

        override fun onTopRowUp(): Boolean = true
        override fun onHeroActiveChanged(active: Boolean) = Unit

        /** 报上来的整屏黑度: 最后一次 / 最大的一次. */
        var tone = 0f
        var maxTone = 0f

        override fun onToneChanged(tone: Float) {
            this.tone = tone
            maxTone = maxOf(maxTone, tone)
        }

        override fun onScrollingChanged(scrolling: Boolean) = Unit
        override fun onGridFocusChanged(hasFocus: Boolean) = Unit
        override fun onFocusParkedChanged(parked: Boolean) = Unit
        override fun onWallOpened(index: Int) {
            opened += index
        }

        override fun onWallFade(fade: Float) {
            this.fade = fade
        }
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val grid = TvNativeGridMetrics(
                columns = 6,
                startPx = 48,
                endPx = 48,
                topBleedPx = 0,
                bottomBleedPx = 0,
                endMarginPx = 60,
                heroLinePx = 400,
                fadeDistancePx = 128f,
            )
            val metrics = TvNativeGridPageMetrics(
                pageWidthPx = 1920,
                pageHeightPx = 1080,
                gridTopPx = 160,
                grid = grid,
                backdropWidthPx = 1280,
                backdropHeightPx = 720,
                heroLeftPx = 48,
                heroTopPx = 60,
                heroWidthPx = 1200,
                heroHeightPx = 320,
                titleWidthPx = 800,
                summaryWidthPx = 800,
            )
            page = TvNativeGridPageView(host.activity, host.sketch, scope, testWallStyle(), metrics, testHeroTextStyle())
            page.animatedScroll = false
            page.listener = listener
            page.heroBlur = true
            page.wallBackdrop!!.maskColor = 0x40000000
            host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            page.showGrid(0, direction = 0, animated = false)
            page.setCards(0, List(12) { TvNativeCard(imageUrl = null, title = "卡 $it", subjectId = SUBJECT_BASE + it) })
            page.setSource(sourceOf(0))
        }
        host.waitUntil("卡片排出来") { (page.grid?.childCount ?: 0) > 1 }
        host.onMain { page.focusItem(0) }
        host.waitUntil("焦点到第 0 张") { page.grid?.focusedChild?.let { page.grid?.getChildAdapterPosition(it) } == 0 }
        assertTrue(ValueAnimator.areAnimatorsEnabled(), "要开着系统动画跑: 看的是对焦过渡的先后")
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    /** 第 [card] 张卡那部的 hero 内容: 背景图是单集剧照, 整页底下的模糊背景是整部的横版背景图 (两张不同的图). */
    private fun sourceOf(card: Int): TvNativeHeroSource = TvNativeHeroSource(
        backdrop = TvNativeBackdropTarget(host.testImage("$STILL$card"), SUBJECT_BASE + card),
        dimming = false,
        rawSubjectId = SUBJECT_BASE + card,
        text = TvNativeHeroText(SUBJECT_BASE + card, "卡 $card", infoReady = true, rating = "7.5", summary = "第 $card 张的简介"),
        wall = TvNativeWallBackdropTarget(host.testImage("$SERIES$card"), SUBJECT_BASE + card, sharp = true),
    )

    private val wall: TvNativeWallBackdropView get() = page.wallBackdrop!!

    /** 贴右上角的背景图 (整屏背景在最底下, 它是第二层; 主线程上调). */
    private fun heroImage(): TvNativeBackdropView = page.getChildAt(1) as TvNativeBackdropView

    /** 第 [index] 张卡的淡化 (主线程上调). */
    private fun cardDim(index: Int): Float = (page.grid!!.findViewHolderForAdapterPosition(index)!!.itemView as TvNativeCardView).dim

    /** 清晰层的透明度 (主线程上调). */
    private fun sharpAlpha(): Float = wall.getChildAt(wall.childCount - 1).alpha

    /** hero 文字的标题 / 简介那一行的透明度 (主线程上调; 行的顺序: 标题, 信息行, 下一集行, 简介). */
    private fun titleAlpha(): Float = page.heroText.getChildAt(0).alpha
    private fun summaryAlpha(): Float = page.heroText.getChildAt(3).alpha

    /** 在聚焦的卡上按确定进 hero 态, 等整屏模糊背景淡满、清晰图解好. */
    private fun enterHero() {
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态、模糊背景淡满") { page.heroActive && wall.alpha == 1f && wall.currentTarget?.subjectId == SUBJECT_BASE }
        host.waitUntil("清晰图解好") { wall.sharpReady }
    }

    /** 在焦点所在的卡上按一下确定 (直接派发给页面), 同一轮主线程消息里接着做 [then] (对焦还没走完). */
    private fun confirmThen(then: () -> Unit) = host.onMain {
        val now = SystemClock.uptimeMillis()
        page.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        page.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        then()
    }

    @Test
    fun `the hero state lays the blurred series backdrop under the hero image`() {
        enterHero()
        host.onMain {
            // 整页底下: 整部的横版背景图的模糊版, 还没对焦
            assertTrue(wall.currentTarget!!.url.contains(SERIES))
            assertEquals(0f, sharpAlpha())
            // 上面: 背景图照旧 (单集剧照), 边缘擦成透明 (每一格各自一层离屏层, 图层本身不开)
            assertTrue(heroImage().currentTarget!!.url.contains(STILL))
            assertEquals(1f, heroImage().alpha)
            assertEquals(View.LAYER_TYPE_NONE, heroImage().layerType)
            assertTrue(heroImage().slotLayerTypes().let { it.isNotEmpty() && it.all { t -> t == View.LAYER_TYPE_HARDWARE } })
            // 深色主题 (视图默认) 下整屏也不压黑: 模糊背景盖满整屏
            assertEquals(0f, listener.maxTone)
        }
    }

    @Test
    fun `the hero image fades into the blurred backdrop at its edges instead of the mask color`() {
        host.onMain {
            // 遮罩色给成蓝的、背景图整张红、整屏背景整张绿: 背景图的左缘要是盖了遮罩色截出来是蓝, 擦成透明露出模糊背景就是绿
            page.fadeColor = Color.BLUE
            page.treatment = tvPageBackdropTreatment(1f, topScrim = false, fadeColor = ComposeColor.Blue, geometry = TV_CARD_HERO_BACKDROP_GEOMETRY)
            page.setSource(
                TvNativeHeroSource(
                    backdrop = TvNativeBackdropTarget(host.testImage("${STILL}red", Color.RED, Color.RED), SUBJECT_BASE),
                    dimming = false,
                    rawSubjectId = SUBJECT_BASE,
                    text = null,
                    wall = TvNativeWallBackdropTarget(host.testImage("${SERIES}green", Color.GREEN, Color.GREEN), SUBJECT_BASE, sharp = true),
                ),
            )
        }
        enterHero()
        val xy = host.onMain { IntArray(2).also { heroImage().getLocationInWindow(it) } }
        val size = host.onMain { heroImage().width to heroImage().height }
        // 背景图右上部分 (没有遮罩) 画出来了: 红的. 取图高 1/4 处: 测试窗口顶上有一条标题栏盖着
        val insideX = xy[0] + size.first - 40
        val insideY = xy[1] + size.second / 4
        shotWhen("背景图画出来") { Color.red(it.getPixel(insideX, insideY)) > 200 }
        // 两层的换图淡入走完再看
        SystemClock.sleep(TV_WALL_BACKDROP_CROSSFADE_MILLIS + 200L)
        val shot = host.windowShot()
        val inside = shot.getPixel(insideX, insideY)
        assertTrue(Color.red(inside) > 200 && Color.green(inside) < 60, "背景图右上部分应是图本身 (红): ${Integer.toHexString(inside)}")
        val edge = shot.getPixel(xy[0] + 3, xy[1] + size.second / 2)
        assertTrue(
            Color.green(edge) > Color.red(edge) + 40 && Color.green(edge) > Color.blue(edge) + 40,
            "背景图左缘应露出底下的模糊背景 (绿), 不是遮罩色 (蓝) 也不是图 (红): ${Integer.toHexString(edge)}",
        )
        // 下缘取最右边那条留白里的一点 (卡片排不到那里)
        val bottom = shot.getPixel(xy[0] + size.first - 10, xy[1] + size.second - 3)
        assertTrue(
            Color.green(bottom) > Color.red(bottom) + 40 && Color.green(bottom) > Color.blue(bottom) + 40,
            "背景图下缘应露出底下的模糊背景 (绿): ${Integer.toHexString(bottom)}",
        )
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

    @Test
    fun `entering the details zooms from the full screen backdrop instead of the hero image`() {
        enterHero()
        // 放大转场的来源登记的是整屏背景那张 (整部的横版背景图), 不是背景图 (单集剧照)
        host.waitUntil("登记了整屏背景") { TvHeroZoomHandoff.sourceDebug().contains("$SERIES${0}.png") }
    }

    @Test
    fun `confirm in the hero state sharpens the backdrop and hides the cards and the hero image before opening`() {
        enterHero()
        // 还在对焦, 没进
        confirmThen { assertEquals(emptyList(), listener.clicked) }
        host.waitUntil("对焦到位后进了第 0 张") { listener.clicked == listOf(0) }
        host.onMain {
            assertEquals(listOf(0), listener.opened)
            assertEquals(1f, sharpAlpha())
            assertEquals(0f, cardDim(0))
            assertEquals(0f, cardDim(1))
            assertEquals(0f, heroImage().alpha)
            assertEquals(1f, listener.fade)
            // 标题留着 (交给详情页的标题接着画), 简介跟着淡没
            assertEquals(1f, titleAlpha())
            assertEquals(0f, summaryAlpha())
        }
    }

    @Test
    fun `coming back from the details brings the blurred hero state back`() {
        enterHero()
        confirmThen { }
        host.waitUntil("进了第 0 张") { listener.clicked == listOf(0) }
        host.onMain { page.endWallOpen() }
        host.waitUntil("倒放回 hero 态") {
            cardDim(0) == 1f && sharpAlpha() == 0f && listener.fade == 0f && summaryAlpha() == 1f && heroImage().alpha == 1f
        }
        host.onMain {
            assertTrue(page.heroActive)
            assertEquals(1f, wall.alpha)
        }
    }

    @Test
    fun `long press in the hero state only reports the press`() {
        enterHero()
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(450)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(0), host.onMain { listener.longPressed.toList() })
        SystemClock.sleep(TV_WALL_BACKDROP_SHARPEN_MILLIS.toLong())
        host.onMain {
            assertEquals(1f, cardDim(1))
            assertEquals(0f, sharpAlpha())
            assertEquals(1f, heroImage().alpha)
            assertEquals(emptyList(), listener.clicked)
        }
    }

    @Test
    fun `leaving the hero state takes the blurred backdrop away`() {
        enterHero()
        host.onMain { page.setHeroActive(false) }
        host.waitUntil("整屏背景淡没、撤掉") { wall.alpha == 0f && wall.currentTarget == null }
    }

    @Test
    fun `without the blurred backdrop the hero state opens the card at once from the hero image`() {
        host.onMain {
            page.heroBlur = false
            assertNull(page.wallBackdrop)
        }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态") { page.heroActive }
        // 深色主题照旧整屏压黑; 背景图照旧按遮罩色画边缘, 放大转场的来源是它
        host.waitUntil("整屏黑透") { listener.tone == 1f }
        host.waitUntil("登记了背景图") { TvHeroZoomHandoff.sourceDebug().contains("$STILL${0}.png") }
        host.onMain {
            val image = page.getChildAt(0) as TvNativeBackdropView
            assertEquals(View.LAYER_TYPE_NONE, image.layerType)
            assertTrue(image.slotLayerTypes().all { it == View.LAYER_TYPE_NONE })
        }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(0), host.onMain { listener.clicked.toList() })
        assertEquals(emptyList(), host.onMain { listener.opened.toList() })
    }

    @Test
    fun `turning the blurred backdrop on in a dark hero state lifts the black at once`() {
        host.onMain { page.heroBlur = false }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态、整屏黑透") { page.heroActive && listener.tone == 1f }
        // 停在 hero 态时打开 (从设置页回来): 报上去的黑度当场回 0, 模糊背景接着铺上
        host.onMain {
            page.heroBlur = true
            page.wallBackdrop!!.maskColor = 0x40000000
            assertEquals(0f, listener.tone)
        }
        host.waitUntil("模糊背景铺上") { wall.alpha == 1f && wall.currentTarget?.subjectId == SUBJECT_BASE }
    }

    private companion object {
        const val SUBJECT_BASE = 600
        const val STILL = "hb-grid-still-"
        const val SERIES = "hb-grid-series-"
    }
}
