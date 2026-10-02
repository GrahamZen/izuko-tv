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
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 探索页 ([TvNativeExploreView]) 开了「hero 态铺模糊背景」([TvNativeExploreView.heroBlur]): 聚焦卡的 hero 态在整页底下铺整部横版背景图的
 * 模糊版, 背景图 (这里是另一张: 单集剧照) 照旧画在上面; hero 态里按确定各行、背景图与简介淡没, 模糊背景对焦变清晰, 到位才进详情页;
 * 点开途中按键吞掉、取消就倒放; 回来倒放; 回到轮播时整屏背景撤掉 (轮播不铺).
 * 开着过渡跑 (模拟器开着系统动画). 要在对焦途中断言的, 按键直接派发给页面、同一轮主线程消息里接着做 ([confirmThen]).
 */
class TvNativeExploreHeroBlurTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = Listener()
    private lateinit var view: TvNativeExploreView

    private class Listener : TvNativeExploreListener {
        val cardClicks = mutableListOf<Pair<String, Int>>()
        var opened = 0
        var opening = false
        var heroButton = -1

        override fun onCardFocused(rowKey: String, index: Int, column: Int) = Unit
        override fun onCardClick(rowKey: String, index: Int) {
            cardClicks += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onHeroButtonFocused(button: Int) {
            heroButton = button
        }

        override fun onHeroButtonClick(button: Int) = Unit
        override fun onSwitchCarousel(delta: Int): Boolean = false
        override fun onExitLeft() = Unit
        override fun onHeroActiveChanged(active: Boolean) = Unit
        override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit

        /** 报上来的整屏黑度: 最后一次 / 最大的一次. */
        var tone = 0f
        var maxTone = 0f

        override fun onToneChanged(tone: Float, splitY: Float) {
            this.tone = tone
            maxTone = maxOf(maxTone, tone)
        }

        override fun onScrollingChanged(scrolling: Boolean) = Unit
        override fun onWallOpeningChanged(opening: Boolean) {
            this.opening = opening
        }

        override fun onWallOpened() {
            opened++
        }
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val style = testWallStyle()
            view = TvNativeExploreView(
                host.activity, host.sketch, scope, style, testExploreMetrics(style),
                testHeroTextStyle(), testHeroButtonStyle(), testTextStyle(32f, 44),
            )
            view.animatedScroll = false
            view.listener = listener
            view.heroBlur = true
            view.wallBackdrop!!.maskColor = 0x40000000
            view.wallColor = WALL_COLOR
            view.setButtons("立即观看", null, "新番时间表", null)
            view.setCarousel(3, 0, Color.WHITE)
            view.setSources(carousel = sourceOf(CAROUSEL_SUBJECT, "carousel"), card = sourceOf(CARD_SUBJECT, "card"))
            view.setItems(
                listOf(
                    TvNativeExploreItem.Spacer("spacer"),
                    TvNativeExploreItem.Header("followed-header", "继续观看"),
                    TvNativeExploreItem.Row(FOLLOWED, testCards(12, "在看")),
                    TvNativeExploreItem.Header("rec-header", "推荐"),
                    TvNativeExploreItem.Row(REC, testCards(12, "推荐")),
                ),
            )
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        host.onMain { view.focusCard(FOLLOWED, 0) }
        host.waitUntil("焦点到继续观看第 0 张") { view.cardAreaHasFocus && view.focusedRowKey == FOLLOWED && view.focusedCardIndex == 0 }
        assertTrue(ValueAnimator.areAnimatorsEnabled(), "要开着系统动画跑: 看的是对焦过渡的先后")
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    /** 一部的 hero 内容: 背景图是单集剧照, 整页底下的模糊背景是整部的横版背景图 (两张不同的图). */
    private fun sourceOf(subjectId: Int, name: String): TvNativeHeroSource = TvNativeHeroSource(
        backdrop = TvNativeBackdropTarget(host.testImage("$STILL$name"), subjectId),
        dimming = false,
        rawSubjectId = subjectId,
        text = TvNativeHeroText(subjectId, "条目 $name", infoReady = true, rating = "8.0", summary = "$name 的简介"),
        wall = TvNativeWallBackdropTarget(host.testImage("$SERIES$name"), subjectId, sharp = true),
    )

    private val wall: TvNativeWallBackdropView get() = view.wallBackdrop!!

    /** 背景图 (整屏背景在最底下, 它是第二层; 主线程上调). */
    private fun heroImage(): TvNativeBackdropView = view.getChildAt(1) as TvNativeBackdropView

    /** 清晰层的透明度 (主线程上调). */
    private fun sharpAlpha(): Float = wall.getChildAt(wall.childCount - 1).alpha

    /** 列表里已排出来的各行 (主线程上调). */
    private fun rows(): List<TvNativeRowView> {
        val out = ArrayList<TvNativeRowView>()
        fun walk(v: View) {
            if (v is TvNativeRowView) out += v else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return out
    }

    /** hero 文字的标题 / 简介那一行的透明度 (主线程上调; 行的顺序: 标题, 信息行, 下一集行, 简介). */
    private fun titleAlpha(): Float = view.heroText.getChildAt(0).alpha
    private fun summaryAlpha(): Float = view.heroText.getChildAt(3).alpha

    /** 在聚焦的卡上按确定进 hero 态, 等整屏模糊背景淡满、清晰图解好. */
    private fun enterHero() {
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态、模糊背景淡满") { view.heroActive && wall.alpha == 1f && wall.currentTarget?.subjectId == CARD_SUBJECT }
        host.waitUntil("清晰图解好") { wall.sharpReady }
    }

    private fun dispatchPress(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** 在焦点所在的卡上按一下确定 (直接派发给页面), 同一轮主线程消息里接着做 [then] (对焦还没走完). */
    private fun confirmThen(then: () -> Unit) = host.onMain {
        dispatchPress(KeyEvent.KEYCODE_DPAD_CENTER)
        then()
    }

    @Test
    fun `the card hero state lays the blurred series backdrop under the hero image`() {
        enterHero()
        host.onMain {
            // 整页底下: 整部的横版背景图的模糊版, 还没对焦
            assertTrue(wall.currentTarget!!.url.contains("${SERIES}card"))
            assertEquals(0f, sharpAlpha())
            // 上面: 背景图照旧 (聚焦卡的单集剧照), 边缘擦成透明 (每一格各自一层离屏层, 图层本身不开)
            assertTrue(heroImage().currentTarget!!.url.contains("${STILL}card"))
            assertEquals(1f, heroImage().alpha)
            assertEquals(View.LAYER_TYPE_NONE, heroImage().layerType)
            assertTrue(heroImage().slotLayerTypes().let { it.isNotEmpty() && it.all { t -> t == View.LAYER_TYPE_HARDWARE } })
            // 深色主题 (视图默认) 下整屏也不压黑: 模糊背景盖满整屏; 最底下铺着卡片墙的底色 (盖住主壳的轮播分界带)
            assertEquals(0f, listener.maxTone)
            assertEquals(WALL_COLOR, floorColor())
        }
        // 放大转场的来源登记的是整屏背景那张, 不是背景图
        host.waitUntil("登记了整屏背景") { TvHeroZoomHandoff.sourceDebug().contains("${SERIES}card.png") }
    }

    @Test
    fun `confirm in the hero state sharpens the backdrop and hides the rows and the hero image before opening`() {
        enterHero()
        confirmThen {
            assertEquals(emptyList(), listener.cardClicks)
            assertTrue(listener.opening)
        }
        host.waitUntil("对焦到位后进了继续观看第 0 张") { listener.cardClicks == listOf(FOLLOWED to 0) }
        host.onMain {
            assertEquals(1, listener.opened)
            assertEquals(false, listener.opening)
            assertEquals(1f, sharpAlpha())
            assertTrue(rows().isNotEmpty() && rows().all { it.rowAlpha == 0f }, "各行淡没")
            assertEquals(0f, heroImage().alpha)
            assertEquals(1f, titleAlpha())
            assertEquals(0f, summaryAlpha())
        }
    }

    @Test
    fun `keys during the open are swallowed`() {
        enterHero()
        confirmThen {
            dispatchPress(KeyEvent.KEYCODE_DPAD_RIGHT)
            dispatchPress(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        host.waitUntil("对焦到位后进了一次") { listener.cardClicks == listOf(FOLLOWED to 0) }
        SystemClock.sleep(TV_WALL_BACKDROP_SHARPEN_MILLIS.toLong())
        host.onMain {
            assertEquals(listOf(FOLLOWED to 0), listener.cardClicks)
            assertEquals(0, view.focusedCardIndex)
        }
    }

    @Test
    fun `cancelling during the open brings the hero state back without opening`() {
        enterHero()
        confirmThen { view.cancelWallOpen() }
        SystemClock.sleep(TV_WALL_BACKDROP_SHARPEN_MILLIS * 2L)
        assertEquals(emptyList(), host.onMain { listener.cardClicks.toList() })
        host.waitUntil("倒放回 hero 态") {
            sharpAlpha() == 0f && rows().all { it.rowAlpha > 0f } && summaryAlpha() == 1f && heroImage().alpha == 1f
        }
    }

    @Test
    fun `coming back from the details brings the blurred hero state back`() {
        enterHero()
        confirmThen { }
        host.waitUntil("进了继续观看第 0 张") { listener.cardClicks == listOf(FOLLOWED to 0) }
        host.onMain { view.endWallOpen() }
        host.waitUntil("倒放回 hero 态") {
            sharpAlpha() == 0f && rows().all { it.rowAlpha > 0f } && summaryAlpha() == 1f && heroImage().alpha == 1f
        }
        host.onMain {
            assertTrue(view.heroActive)
            assertEquals(1f, wall.alpha)
        }
    }

    @Test
    fun `in the light theme the title is already the white details title while the backdrop sharpens`() {
        host.onMain { view.heroText.style = testLightHeroTextStyle() }
        enterHero()
        host.onMain { assertTitleLook(view.heroText.getChildAt(0) as TextView, 0) }
        confirmThen { }
        // 背景还在对焦 (各行先淡没, 背景更慢): 标题已经是详情页的白字压黑影, 登记给放大转场的也是这个样子
        host.waitUntil("背景对焦过了七成") { sharpAlpha() >= 0.7f }
        host.onMain {
            assertTitleLook(view.heroText.getChildAt(0) as TextView, 1)
            assertTrue(TvHeroZoomHandoff.sourceDebug().contains("look=1.0"), TvHeroZoomHandoff.sourceDebug())
        }
        host.waitUntil("进了继续观看第 0 张") { listener.cardClicks == listOf(FOLLOWED to 0) }
        host.onMain { view.endWallOpen() }
        host.waitUntil("倒放回 hero 态") { sharpAlpha() == 0f && summaryAlpha() == 1f }
        host.onMain { assertTitleLook(view.heroText.getChildAt(0) as TextView, 0) }
    }

    @Test
    fun `back on the carousel the blurred backdrop is gone and the hero image is painted the usual way`() {
        enterHero()
        val frames = backToCarouselFloorFrames()
        // 深色: 卡片墙底色还看得见时, 背景图的边缘照旧擦成透明 (遮罩色近黑, 画在灰底上是一圈黑框)
        assertTrue(frames.any { it.first in 1..254 }, "看到了底色淡出途中的帧")
        assertEquals(emptyList(), frames.filter { !it.second }.map { it.first }, "底色还在时 (各帧底色的透明度) 背景图的边缘按遮罩色画了")
        host.onMain {
            // 底色撤了: 轮播照旧用背景图 (轮播那一部的), 按遮罩色画边缘, 放大转场的来源也还是它
            assertTrue(heroImage().currentTarget!!.url.contains("${STILL}carousel"))
            assertEquals(View.LAYER_TYPE_NONE, heroImage().layerType)
            assertTrue(heroImage().slotLayerTypes().all { it == View.LAYER_TYPE_NONE })
        }
        host.waitUntil("登记了轮播的背景图") { TvHeroZoomHandoff.sourceDebug().contains("${STILL}carousel.png") }
    }

    @Test
    fun `in the light theme going back to the carousel paints the image edges the usual way once the blurred backdrop is gone`() {
        host.onMain { view.dark = false }
        enterHero()
        val frames = backToCarouselFloorFrames()
        // 浅色的遮罩色就是卡片墙底色: 模糊背景淡没就按遮罩色画边缘, 不为底色留着离屏层
        assertTrue(frames.any { it.first in 1..254 && !it.second }, frames.toString())
    }

    /**
     * 从 hero 态回轮播按钮, 等最底下那层卡片墙底色随上面几行 (连同轮播) 淡回来之后撤掉 (轮播的分界带照旧露出来). 返回底色还看得见的每一帧 (画之前记):
     * 底色的透明度, 背景图的边缘是不是擦成透明 (各格都走离屏层).
     */
    private fun backToCarouselFloorFrames(): List<Pair<Int, Boolean>> {
        val frames = mutableListOf<Pair<Int, Boolean>>()
        val check = ViewTreeObserver.OnPreDrawListener {
            val floor = Color.alpha(floorColor())
            val slots = heroImage().slotLayerTypes()
            if (floor > 0 && slots.isNotEmpty()) frames += floor to slots.all { it == View.LAYER_TYPE_HARDWARE }
            true
        }
        host.onMain {
            view.viewTreeObserver.addOnPreDrawListener(check)
            view.focusHeroButton(0)
        }
        host.waitUntil("回到轮播、整屏背景撤掉") { listener.heroButton == 0 && !view.heroActive && wall.alpha == 0f && wall.currentTarget == null }
        host.waitUntil("卡片墙底色那层撤了") { Color.alpha(floorColor()) == 0 }
        host.onMain { view.viewTreeObserver.removeOnPreDrawListener(check) }
        return frames
    }

    @Test
    fun `without the blurred backdrop the dark card hero state still turns the page black`() {
        host.onMain { view.heroBlur = false }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态、整屏黑透") { view.heroActive && listener.tone == 1f }
        // 背景图等黑透才露面, 照旧按遮罩色画边缘
        host.waitUntil("背景图淡满") { heroImageOnly().alpha == 1f }
        host.onMain {
            assertEquals(View.LAYER_TYPE_NONE, heroImageOnly().layerType)
            assertTrue(heroImageOnly().slotLayerTypes().all { it == View.LAYER_TYPE_NONE })
        }
    }

    /** 没铺模糊背景时的背景图 (最底下一层; 主线程上调). */
    private fun heroImageOnly(): TvNativeBackdropView = view.getChildAt(0) as TvNativeBackdropView

    /** 视图最底下铺的那层卡片墙底色此刻的颜色 (主线程上调). */
    private fun floorColor(): Int = (view.background as ColorDrawable).color

    private companion object {
        const val WALL_COLOR = 0xFF2C2C2E.toInt()
        const val FOLLOWED = "followed"
        const val REC = "rec"
        const val CAROUSEL_SUBJECT = 710
        const val CARD_SUBJECT = 720
        const val STILL = "hb-ex-still-"
        const val SERIES = "hb-ex-series-"
    }
}
