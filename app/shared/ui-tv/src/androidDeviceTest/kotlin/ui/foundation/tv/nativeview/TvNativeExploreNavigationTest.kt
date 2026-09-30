/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 探索页海报墙 ([TvNativeExploreView]) 的导航: 轮播按钮 ↔ 首行、行内按需挪、上下落到屏上同一列 (行短了落到最后一张)、行首按左出页面、
 * 行尾按右吞掉、确定键两步 (先进 hero 态, 再进详情页)、轮播按钮左右翻页、远跳、从页面外进来回到上次那张.
 *
 * 列表: [hero 占位] [继续观看标题] 继续观看 20 张 [推荐标题] 推荐 12 张 / 3 张 / 12 张. 1080p, 一屏完整放得下 6 张.
 */
class TvNativeExploreNavigationTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = ExploreListener()
    private lateinit var view: TvNativeExploreView
    private lateinit var outside: View

    private class ExploreListener : TvNativeExploreListener {
        val cardFocused = mutableListOf<Pair<String, Int>>()
        val heroButtonFocused = mutableListOf<Int>()
        val heroButtonClicked = mutableListOf<Int>()
        val cardClicks = mutableListOf<Pair<String, Int>>()
        val heroActive = mutableListOf<Boolean>()
        val switchCarousel = mutableListOf<Int>()
        var exitLeft = 0

        override fun onCardFocused(rowKey: String, index: Int, column: Int) {
            cardFocused += rowKey to index
        }
        override fun onCardClick(rowKey: String, index: Int) {
            cardClicks += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onHeroButtonFocused(button: Int) {
            heroButtonFocused += button
        }

        override fun onHeroButtonClick(button: Int) {
            heroButtonClicked += button
        }

        // 只有一页往右翻得动: 第一项按左翻不动, 交给侧边栏
        override fun onSwitchCarousel(delta: Int): Boolean {
            switchCarousel += delta
            return delta > 0
        }

        override fun onExitLeft() {
            exitLeft++
        }

        override fun onHeroActiveChanged(active: Boolean) {
            heroActive += active
        }

        override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit
        override fun onToneChanged(tone: Float, splitY: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
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
            view.transitions = false
            view.animatedScroll = false
            view.listener = listener
            view.setButtons("立即观看", null, "新番时间表", null)
            view.setCarousel(3, 0, Color.WHITE)
            val empty = TvNativeHeroSource(backdrop = null, dimming = false, rawSubjectId = null, text = null)
            view.setSources(empty, empty)
            view.setItems(
                listOf(
                    TvNativeExploreItem.Spacer("spacer"),
                    TvNativeExploreItem.Header("followed-header", "继续观看"),
                    TvNativeExploreItem.Row(FOLLOWED, testCards(20, "在看")),
                    TvNativeExploreItem.Header("rec-header", "推荐"),
                    TvNativeExploreItem.Row(REC0, testCards(12, "推荐")),
                    TvNativeExploreItem.Row(REC1, testCards(3, "短")),
                    TvNativeExploreItem.Row(REC2, testCards(12, "再推荐")),
                ),
            )
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            // 页面外的一个落点 (代替侧边栏), 用来测从页面外进来
            outside = View(host.activity).apply { isFocusable = true }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        host.onMain { view.focusHeroButton(0) }
        host.waitUntil("焦点在立即观看") { listener.heroButtonFocused.lastOrNull() == 0 }
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    private fun card(): Pair<String?, Int> = host.onMain { (if (view.cardAreaHasFocus) view.focusedRowKey else null) to view.focusedCardIndex }

    private fun waitCard(rowKey: String, index: Int) =
        host.waitUntil("焦点到 $rowKey 第 $index 张") { view.cardAreaHasFocus && view.focusedRowKey == rowKey && view.focusedCardIndex == index }

    private fun press(keyCode: Int, times: Int = 1) = repeat(times) { host.press(keyCode) }

    /**
     * 换成 [rows] 行、每行 [cards] 张的列表 (行键见 [longRow]), 滚动带动画: 长按连发要看平滑滚动落后焦点的那一段.
     * [moreCard] = 每行末尾再接一张「更多」卡 (第 [cards] 张).
     */
    private fun useLongRows(rows: Int, cards: Int, moreCard: Boolean = false) = host.onMain {
        view.animatedScroll = true
        view.setItems(
            listOf(TvNativeExploreItem.Spacer("spacer")) +
                (0 until rows).map { row ->
                    val more = if (moreCard) listOf(TvNativeCard(imageUrl = null, title = "更多", more = TvNativeMore.Idle)) else emptyList()
                    TvNativeExploreItem.Row(longRow(row), testCards(cards, "第 $row 行") + more)
                },
        )
    }

    private fun longRow(index: Int) = "long-row-$index"

    /** 从立即观看下到首行第一张. */
    private fun enterFirstRow() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        waitCard(FOLLOWED, 0)
    }

    @Test
    fun `hero buttons go down into the first row and the first row goes up to the schedule button`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("焦点在新番时间表") { listener.heroButtonFocused.lastOrNull() == 1 }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(FOLLOWED, 0)
        press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("回到新番时间表") { !view.cardAreaHasFocus && listener.heroButtonFocused.lastOrNull() == 1 }
    }

    @Test
    fun `cards move on demand within a row`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 5)
        waitCard(FOLLOWED, 5)
        assertEquals(0, host.onMain { view.rowLeftIndex[FOLLOWED] ?: 0 })
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(FOLLOWED, 6)
        assertEquals(1, host.onMain { view.rowLeftIndex[FOLLOWED] })
    }

    @Test
    fun `holding right moves focus on every repeat while the row glides`() {
        useLongRows(rows = 1, cards = 40)
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        waitCard(longRow(0), 0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 5)
        waitCard(longRow(0), 5)
        // 按住: 新按下挪到露一截的第 6 张, 之后照系统连发约 50ms 一发 (比上限 40ms 慢, 每一发都挪), 行一路平滑滚着、落后焦点两张多.
        // 按键在页面里就接住了, 行等不到自己的左右键: 行外一直多排着, 每一发的目标卡都已经排好、焦点当场过去
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(longRow(0) to 6, card())
        for (i in 1..20) {
            SystemClock.sleep(50)
            host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = i)
            assertEquals(longRow(0) to 6 + i, card(), "第 $i 发连发")
        }
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    @Test
    fun `holding left walks back to the row start and the next repeat leaves the page`() {
        useLongRows(rows = 1, cards = 40)
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        waitCard(longRow(0), 0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 8)
        waitCard(longRow(0), 8)
        // 按住往回走: 每一发挪一张, 到了行首, 下一发出页面 (进侧边栏, 同搜索页的网格)
        host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(longRow(0) to 7, card())
        for (i in 1..7) {
            SystemClock.sleep(50)
            host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = i)
            assertEquals(longRow(0) to 7 - i, card(), "第 $i 发连发")
        }
        assertEquals(0, listener.exitLeft)
        SystemClock.sleep(50)
        host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = 8)
        host.keyUp(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, listener.exitLeft)
    }

    @Test
    fun `holding down moves one row per repeat while the list glides`() {
        useLongRows(rows = 16, cards = 8)
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        waitCard(longRow(0), 0)
        // 按住: 照系统连发约 50ms 一发 (比上限 40ms 慢, 每一发都换行), 列表一路平滑滚着、落后焦点两行多;
        // 上下各多排一屏, 每一发的目标行都已经排好、焦点当场过去
        host.keyDown(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(longRow(1) to 0, card())
        for (i in 1..12) {
            SystemClock.sleep(50)
            host.keyDown(KeyEvent.KEYCODE_DPAD_DOWN, repeatCount = i)
            assertEquals(longRow(1 + i) to 0, card(), "第 $i 发连发")
        }
        host.keyUp(KeyEvent.KEYCODE_DPAD_DOWN)
    }

    @Test
    fun `up and down land on the same screen column and a short row lands on its last card`() {
        enterFirstRow()
        // 行首挪到第 1 张, 聚焦卡在屏上第 5 列
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 6)
        waitCard(FOLLOWED, 6)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC0, 5)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC1, 2)
        // 短行里停在第 2 列, 往上按第 2 列落
        press(KeyEvent.KEYCODE_DPAD_UP)
        waitCard(REC0, 2)
    }

    @Test
    fun `row start left leaves the page and row end right is swallowed`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, listener.exitLeft)
        assertEquals(FOLLOWED to 0, card())

        host.onMain { view.focusCard(REC1, 2) }
        waitCard(REC1, 2)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(REC1 to 2, card())
    }

    @Test
    fun `confirm enters the hero state first and opens details on the second press`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC0, 0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(REC0, 1)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(true), listener.heroActive)
        assertTrue(host.onMain { view.heroActive })
        assertTrue(listener.cardClicks.isEmpty())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(REC0 to 1), listener.cardClicks)
    }

    @Test
    fun `hero buttons page the carousel and the first item left leaves the page`() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(1), listener.switchCarousel)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf(1, -1), listener.switchCarousel)
        assertEquals(1, listener.exitLeft)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(0), listener.heroButtonClicked)
    }

    @Test
    fun `far jump lands on the target row`() {
        host.onMain { view.focusCard(REC2, 3) }
        waitCard(REC2, 3)
        host.onMain { view.focusCard(FOLLOWED, 0, far = true) }
        waitCard(FOLLOWED, 0)
    }

    @Test
    fun `the card opened from the hero state keeps its focused look after the focus leaves`() {
        host.onMain { view.focusCard(REC0, 2) }
        waitCard(REC0, 2)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进 hero 态") { listener.heroActive == listOf(true) }
        // hero 态里再按确认 = 进详情页; 焦点交给详情页 (这里用页面外的视图代替)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(REC0 to 2), listener.cardClicks)
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点离开页面") { outside.isFocused }
        assertTrue(host.onMain { cardView(REC0, 2)?.focusLookHeld == true }, "焦点走了那张卡仍画成聚焦态")
        // 返回后焦点交还: 放开
        host.onMain { view.focusCard(REC0, 2) }
        waitCard(REC0, 2)
        assertEquals(false, host.onMain { cardView(REC0, 2)?.focusLookHeld })
    }

    /** [rowKey] 那一行的视图 (行视图的 tag 是行键). 在主线程上调. */
    private fun rowView(rowKey: String): TvNativeRowView? {
        fun find(v: View): TvNativeRowView? = when {
            v is TvNativeRowView && v.tag == rowKey -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(view)
    }

    /** [rowKey] 行第 [index] 张卡的视图. 在主线程上调. */
    private fun cardView(rowKey: String, index: Int): TvNativeCardView? =
        rowView(rowKey)?.findViewHolderForAdapterPosition(index)?.itemView as? TvNativeCardView

    /** 走系统的按键派发 (同遥控器) 按一下, 不等主线程空闲: 动画途中按键 (见 [press] 为什么不行). */
    private fun pressNow(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        host.instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        host.instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** 长行 (滚动带动画) 第 1 行停在第 [index] 张, 列表与行都停稳. */
    private fun restOnLongRow(index: Int) {
        useLongRows(rows = 3, cards = 30)
        host.onMain { view.focusCard(longRow(1), index) }
        waitCard(longRow(1), index)
        SystemClock.sleep(700)
        host.waitUntil("行停稳") { rowView(longRow(1))?.scrollState == RecyclerView.SCROLL_STATE_IDLE }
    }

    @Test
    fun `going back to the row start from far keeps the focus in the page`() {
        restOnLongRow(20)
        val heroFocused = listener.heroButtonFocused.size
        // 页面按返回回本行首卡 (远跳): 出发那张随行滚远被回收时焦点不能跟着丢 —— 丢了系统从窗口根上重新送焦, 落到轮播按钮上
        host.onMain { view.focusCard(longRow(1), 0, far = true) }
        waitCard(longRow(1), 0)
        assertEquals(heroFocused, listener.heroButtonFocused.size, "回首卡途中焦点跑到了轮播按钮上")
        assertEquals(0, host.onMain { rowView(longRow(1))?.leftIndex() })
    }

    @Test
    fun `a direction key while going back to the row start lands on the first card at once`() {
        restOnLongRow(20)
        host.onMain { view.focusCard(longRow(1), 0, far = true) }
        SystemClock.sleep(60)
        assertTrue(host.onMain { rowView(longRow(1))?.scrollState != RecyclerView.SCROLL_STATE_IDLE }, "行还在往回滚")
        val frames = FrameCounter.start(host)
        pressNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(longRow(1), 0)
        // 一步到位, 不等行滚完 (从第 20 张滚回来要几十帧); 这一下右键吞掉, 焦点没往右走
        assertLandedWithinFrames(frames.stop())
        SystemClock.sleep(200)
        assertEquals(longRow(1) to 0, card())
        assertEquals(0, host.onMain { rowView(longRow(1))?.leftIndex() })
    }

    @Test
    fun `two quick direction keys while going back to the row start land and move on`() {
        restOnLongRow(20)
        host.onMain { view.focusCard(longRow(1), 0, far = true) }
        SystemClock.sleep(60)
        // 第一下当场落地 (要等这一趟布局), 第二下紧跟着来 (连按两下): 落地不能被它作废, 焦点不能停在整个视图上
        pressNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        pressNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("焦点落到第 1 行的卡上") { view.findFocus() is TvNativeCardView && view.focusedRowKey == longRow(1) }
        SystemClock.sleep(300)
        val (row, index) = card()
        assertEquals(longRow(1), row)
        assertTrue(index <= 1, "落地后跑远了: 第 $index 张")
        assertTrue(host.onMain { view.findFocus() is TvNativeCardView }, "焦点停在整个视图上")
    }

    @Test
    fun `a direction key once the first card is laid out off screen on the way back lands on it`() {
        restOnLongRow(20)
        host.onMain { view.focusCard(longRow(1), 0, far = true) }
        // 行往回滚到第一张已经排出来 (在屏幕左边外面, 还没滚进来) 的那一刻按: 落点要给这张排在屏外的卡送焦
        host.waitUntil("第一张排出来了而行还在滚", timeoutMillis = 3000) {
            val row = rowView(longRow(1))
            val first = row?.findViewHolderForAdapterPosition(0)?.itemView
            row != null && first != null && row.scrollState != RecyclerView.SCROLL_STATE_IDLE && first.right <= row.paddingLeft
        }
        pressNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertLandedNear(longRow(1), maxIndex = 0)
        assertEquals(0, host.onMain { rowView(longRow(1))?.leftIndex() })
    }

    @Test
    fun `holding a direction key while going back to the row start lands and keeps the focus on a card`() {
        restOnLongRow(20)
        host.onMain { view.focusCard(longRow(1), 0, far = true) }
        SystemClock.sleep(60)
        holdNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertLandedNear(longRow(1), maxIndex = 4)
    }

    /** 按住一个方向键: 首次按下后紧跟着 [repeats] 次连发 (repeatCount > 0), 再抬起 (走系统派发, 同遥控器). */
    private fun holdNow(keyCode: Int, repeats: Int = 3) {
        val down = SystemClock.uptimeMillis()
        for (repeat in 0..repeats) {
            host.instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, repeat))
        }
        host.instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** 焦点落在 [rowKey] 行的卡上 (不是停在整个视图上), 停稳后仍是前 [maxIndex] + 1 张之一 (没有乱跑). */
    private fun assertLandedNear(rowKey: String, maxIndex: Int) {
        host.waitUntil("焦点落到 $rowKey 的卡上") { view.findFocus() is TvNativeCardView && view.focusedRowKey == rowKey }
        SystemClock.sleep(400)
        val (row, index) = card()
        assertEquals(rowKey, row)
        assertTrue(index <= maxIndex, "落地后跑远了: 第 $index 张")
        assertTrue(host.onMain { view.findFocus() is TvNativeCardView }, "焦点停在整个视图上")
    }

    /** 按下到落地画了几帧: 「当场落地」是几帧之内, 等滚完要几十帧. 数帧不数毫秒 —— 模拟器负载一高每帧都变慢, 帧数不变. */
    private fun assertLandedWithinFrames(frames: Int) {
        assertTrue(frames <= TV_LANDING_MAX_FRAMES, "按了方向键没有当场落地: 过了 $frames 帧")
    }

    /** 从开始到 [stop] 主线程画了几帧. */
    private class FrameCounter private constructor() : Choreographer.FrameCallback {
        @Volatile private var frames = 0

        @Volatile private var running = true

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            frames++
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun stop(): Int {
            running = false
            return frames
        }

        companion object {
            fun start(host: TvNativeTestHost): FrameCounter = FrameCounter().also { counter ->
                host.onMain { Choreographer.getInstance().postFrameCallback(counter) }
            }
        }
    }

    @Test
    fun `going back to the row start from the more card lands on the first card even with quick direction keys`() {
        // 行尾的「更多」卡 (第 30 张) 上按返回 = 本行回首卡 (远跳), 途中连按两下方向键
        useLongRows(rows = 3, cards = 30, moreCard = true)
        host.onMain { view.focusCard(longRow(1), 30) }
        waitCard(longRow(1), 30)
        SystemClock.sleep(700)
        host.waitUntil("行停稳") { rowView(longRow(1))?.scrollState == RecyclerView.SCROLL_STATE_IDLE }
        host.onMain { view.focusCard(longRow(1), 0, far = true) }
        SystemClock.sleep(60)
        pressNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        pressNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertLandedNear(longRow(1), maxIndex = 1)
    }

    @Test
    fun `confirm then a direction key while going back to the row start lands and drops the confirm`() {
        restOnLongRow(20)
        host.onMain { view.focusCard(longRow(1), 0, far = true) }
        SystemClock.sleep(60)
        pressNow(KeyEvent.KEYCODE_DPAD_CENTER)
        pressNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertLandedNear(longRow(1), maxIndex = 1)
        assertEquals(emptyList(), listener.heroActive)
    }

    /**
     * 跳组首行 (页面在非组首行的行首按返回): 第 0 行 (组首行) 先停在第 15 张, 焦点在第 1 行行首, 再远跳到第 0 行第一张
     * (纵向滚过去, 第 0 行还要横着滚回行首). 远跳发出后约 60ms 返回, 正在途中.
     */
    private fun goBackToSectionFirstRow() {
        useLongRows(rows = 3, cards = 30)
        host.onMain { view.focusCard(longRow(0), 15) }
        waitCard(longRow(0), 15)
        SystemClock.sleep(500)
        host.onMain { view.focusCard(longRow(1), 0) }
        waitCard(longRow(1), 0)
        SystemClock.sleep(700)
        host.onMain { view.focusCard(longRow(0), 0, far = true) }
        SystemClock.sleep(60)
    }

    @Test
    fun `going back to the section first row whose first card is laid out moves the focus straight there`() {
        // 组首行停在行首、就在上面一行: 第一张已经排出来, 不远跳, 同按上键当场送焦 (远跳要等滚停才落焦, 跳一行也要一秒多)
        useLongRows(rows = 3, cards = 30)
        host.onMain { view.focusCard(longRow(1), 0) }
        waitCard(longRow(1), 0)
        SystemClock.sleep(500)
        val landed = host.onMain {
            view.focusCard(longRow(0), 0, far = true)
            view.focusedRowKey to view.focusedCardIndex
        }
        assertEquals(longRow(0) to 0, landed)
        assertLandedNear(longRow(0), maxIndex = 0)
    }

    @Test
    fun `going back to the section first row lands on its first card`() {
        val heroFocused = listener.heroButtonFocused.size
        goBackToSectionFirstRow()
        waitCard(longRow(0), 0)
        assertEquals(heroFocused, listener.heroButtonFocused.size, "途中焦点跑到了轮播按钮上")
        assertEquals(0, host.onMain { rowView(longRow(0))?.leftIndex() })
        assertLandedNear(longRow(0), maxIndex = 0)
    }

    @Test
    fun `a direction key on the way to the section first row lands there at once`() {
        goBackToSectionFirstRow()
        val frames = FrameCounter.start(host)
        pressNow(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(longRow(0), 0)
        assertLandedWithinFrames(frames.stop())
        // 这一下吞掉: 没往下走到第 1 行
        assertLandedNear(longRow(0), maxIndex = 0)
    }

    @Test
    fun `two quick direction keys on the way to the section first row land and move on`() {
        goBackToSectionFirstRow()
        pressNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        pressNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertLandedNear(longRow(0), maxIndex = 1)
    }

    @Test
    fun `holding a direction key on the way to the section first row keeps the focus on a card`() {
        goBackToSectionFirstRow()
        holdNow(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertLandedNear(longRow(0), maxIndex = 4)
    }

    @Test
    fun `confirm on the way to the section first row opens its first card once it lands`() {
        goBackToSectionFirstRow()
        pressNow(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(listener.heroActive.isEmpty(), "还没落地就点了")
        waitCard(longRow(0), 0)
        host.waitUntil("落地后点了第一张") { listener.heroActive == listOf(true) }
        assertEquals(longRow(0) to 0, card())
    }

    @Test
    fun `a direction key right after going back to the hero buttons moves normally`() {
        // 组首行行首按返回 = 焦点当场到立即观看, 列表随后滚回顶; 这时按方向键照常移动 (立即观看往下是新番时间表)
        useLongRows(rows = 3, cards = 30)
        host.onMain { view.focusCard(longRow(1), 3) }
        waitCard(longRow(1), 3)
        SystemClock.sleep(500)
        host.onMain { view.focusHeroButton(0) }
        host.waitUntil("焦点在立即观看") { !view.cardAreaHasFocus && listener.heroButtonFocused.lastOrNull() == 0 }
        pressNow(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("焦点在新番时间表") { listener.heroButtonFocused.lastOrNull() == 1 }
        SystemClock.sleep(400)
        assertFalse(host.onMain { view.cardAreaHasFocus })
        assertEquals(1, listener.heroButtonFocused.last())
    }

    @Test
    fun `confirm while going back to the row start opens the first card once it lands`() {
        restOnLongRow(20)
        host.onMain { view.focusCard(longRow(1), 0, far = true) }
        SystemClock.sleep(60)
        pressNow(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(listener.heroActive.isEmpty(), "还没落地就点了")
        waitCard(longRow(1), 0)
        // 卡片墙上按确认 = 这张进 hero 态: 点在落地的那张上
        host.waitUntil("落地后点了行首那张") { listener.heroActive == listOf(true) }
        assertEquals(longRow(1) to 0, card())
    }

    /**
     * 页面的返回分层连按 (探索页途中再按返回 = 那一步当场落地, 再按落点进下一层): 第 0 行 (组首行) 先滑到第 15 张, 停在第 1 行第 20 张;
     * 第一下返回 = 本行回首卡 (远跳), 行还在往回滚时第二下 = 先 [TvNativeExploreView.settleFarJump], 再跳组首行 (远跳, 第 0 行也得横着滚回行首).
     */
    private fun backTwiceFromDeepInRow() {
        useLongRows(rows = 3, cards = 30)
        host.onMain { view.focusCard(longRow(0), 15) }
        waitCard(longRow(0), 15)
        SystemClock.sleep(500)
        host.onMain { view.focusCard(longRow(1), 20) }
        waitCard(longRow(1), 20)
        SystemClock.sleep(700)
        host.onMain { view.focusCard(longRow(1), 0, far = true) }
        SystemClock.sleep(60)
        val settled = host.onMain {
            val settled = view.settleFarJump()
            view.focusCard(longRow(0), 0, far = true)
            settled
        }
        assertTrue(settled, "第一下的回首卡应该还在滚")
    }

    @Test
    fun `back again while going back to the row start settles it and moves on to the section first row`() {
        val heroFocused = listener.heroButtonFocused.size
        backTwiceFromDeepInRow()
        // 第一步当场落地: 出发那一行一步回到行首 (不是接着慢慢滚), 行首与停的那张照落地记下
        assertEquals(0, host.onMain { view.rowLeftIndex[longRow(1)] })
        host.waitUntil("出发那一行当场回到行首", timeoutMillis = 150) { rowView(longRow(1))?.leftIndex() == 0 }
        waitCard(longRow(0), 0)
        assertEquals(heroFocused, listener.heroButtonFocused.size, "途中焦点跑到了轮播按钮上")
        assertEquals(0, host.onMain { rowView(longRow(0))?.leftIndex() })
        SystemClock.sleep(600)
        assertEquals(longRow(0) to 0, card())
    }

    @Test
    fun `a third back on the way to the section first row settles it and goes to the hero buttons`() {
        backTwiceFromDeepInRow()
        SystemClock.sleep(60)
        // 第三下: 跳组首行这一步当场落地, 再按落点 (组首行行首) 算 = 回轮播主按钮
        val settled = host.onMain {
            val settled = view.settleFarJump()
            view.focusHeroButton(0)
            settled
        }
        assertTrue(settled, "跳组首行应该还在滚")
        host.waitUntil("焦点在立即观看") { !view.cardAreaHasFocus && listener.heroButtonFocused.lastOrNull() == 0 }
        assertEquals(0, host.onMain { view.rowLeftIndex[longRow(0)] })
        val cardsFocused = listener.cardFocused.size
        SystemClock.sleep(900)
        // 落地不送焦, 半路停下的滚动也不再送焦: 焦点一直在立即观看
        assertEquals(cardsFocused, listener.cardFocused.size, "焦点又被拉回了卡片上")
        assertFalse(host.onMain { view.cardAreaHasFocus })
        assertEquals(0, listener.heroButtonFocused.last())
        assertEquals(0, host.onMain { rowView(longRow(0))?.leftIndex() })
        // 没有远跳在滚时不做事
        assertFalse(host.onMain { view.settleFarJump() })
    }

    @Test
    fun `going back to the row start while the first card is still laid out moves the focus straight to it`() {
        // 第 7 张: 行首只挪了两张, 第一张还排着 (行外多排的那几张里) —— 不必远跳, 焦点当场过去, 行自己滚回来
        restOnLongRow(7)
        val landed = host.onMain {
            view.focusCard(longRow(1), 0, far = true)
            view.focusedRowKey to view.focusedCardIndex
        }
        assertEquals(longRow(1) to 0, landed)
    }

    /**
     * 在主线程上当场派发一次按下 + 抬起 (远跳途中焦点停放在本视图上, 按键正是先到这里). 远跳途中按键不能用 [press]: 它等主线程空闲才返回,
     * 模拟器一慢就等到远跳落地之后, 这一下成了落地后按的.
     */
    private fun pressOnView(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    @Test
    fun `confirm during a far jump acts on the target once it lands`() {
        host.onMain { view.focusCard(REC2, 3) }
        waitCard(REC2, 3)
        val heroOnTheWay = host.onMain {
            view.animatedScroll = true
            view.focusCard(FOLLOWED, 0, far = true)
            pressOnView(KeyEvent.KEYCODE_DPAD_CENTER)
            listener.heroActive.toList()
        }
        assertEquals(emptyList(), heroOnTheWay)
        waitCard(FOLLOWED, 0)
        // 卡片墙上按确认 = 这张进 hero 态: 点在目标上, 焦点仍在目标
        host.waitUntil("落地后点了目标") { listener.heroActive == listOf(true) }
        assertEquals(FOLLOWED to 0, card())
    }

    @Test
    fun `a direction key during a far jump drops the queued confirm`() {
        host.onMain { view.focusCard(REC2, 3) }
        waitCard(REC2, 3)
        host.onMain {
            view.animatedScroll = true
            view.focusCard(FOLLOWED, 0, far = true)
            pressOnView(KeyEvent.KEYCODE_DPAD_CENTER)
            // 当场落到目标, 这一下吞掉 (不往下走), 排队的确认作废
            pressOnView(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        waitCard(FOLLOWED, 0)
        SystemClock.sleep(300)
        assertEquals(emptyList(), listener.heroActive)
        assertEquals(FOLLOWED to 0, card())
    }

    @Test
    fun `focus coming back from outside the page lands on the last card`() {
        host.onMain { view.focusCard(REC0, 4) }
        waitCard(REC0, 4)
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点到页面外") { outside.isFocused && !view.cardAreaHasFocus }
        host.onMain { view.requestFocus() }
        waitCard(REC0, 4)
        assertFalse(host.onMain { outside.isFocused })
    }

    @Test
    fun `a request for the card that already has focus is reported as arrived`() {
        host.onMain { view.focusCard(REC0, 3) }
        waitCard(REC0, 3)
        val before = listener.cardFocused.size
        // 页面的落点请求靠这一下回报清掉 (进页恢复 / 继续观看整行回来时补发的请求常常就指着焦点所在的这张)
        host.onMain { view.focusCard(REC0, 3) }
        host.waitUntil("再报一次到位") { listener.cardFocused.size == before + 1 }
        assertEquals(REC0 to 3, listener.cardFocused.last())

        host.onMain { view.focusHeroButton(0) }
        host.waitUntil("焦点在立即观看") { listener.heroButtonFocused.lastOrNull() == 0 }
        val buttonsBefore = listener.heroButtonFocused.size
        host.onMain { view.focusHeroButton(0) }
        host.waitUntil("按钮也再报一次") { listener.heroButtonFocused.size == buttonsBefore + 1 }
    }

    private companion object {
        const val FOLLOWED = "followed-row"

        /** 「当场落地」最多几帧: 实测两三帧 (送焦要等这一行排一次), 给足余量; 等行滚完要几十帧. */
        const val TV_LANDING_MAX_FRAMES = 8
        const val REC0 = "rec-row-0"
        const val REC1 = "rec-row-1"
        const val REC2 = "rec-row-2"
    }
}

/** 1080p 探索页的几何 (TvExplorationPage 按 960 × 540 dp、320dpi 算出来的量级). */
internal fun testExploreMetrics(style: TvNativeWallStyle): TvNativeExploreMetrics = TvNativeExploreMetrics(
    pageWidthPx = 1824,
    pageHeightPx = 1080,
    bleedLeftPx = 96,
    columns = 6,
    listTopPx = 48,
    listTopBleedPx = 240,
    listBottomBleedPx = 256,
    spacerPx = 480,
    headerPx = 72,
    rowPx = style.cardBlockHeightPx + style.rowSpacingPx,
    rowGapPx = style.rowSpacingPx,
    viewportPx = 1032,
    endMarginPx = 80,
    heroHeaderTopPx = 488,
    rowStartPx = 128,
    endPadPx = 48,
    fadeDistancePx = 128f,
    carouselBottomPx = 820f,
    overhangPx = 100,
    backdropWidthPx = 1458,
    backdropHeightPx = 820,
    cardBackdropScale = 0.8f,
    heroStartPx = 32,
    heroTopPx = 56,
    heroEndPadPx = 48,
    heroBlockPx = 480,
    heroBlockExpandedPx = 528,
    titleWidthPx = 1000,
    carouselSummaryWidthPx = 900,
    cardSummaryWidthPx = 900,
    buttonsTopGapPx = 12,
    buttonGapPx = 8,
    dotsCenterYPx = 900,
    dotPx = 12f,
    dotSelectedWidthPx = 40f,
    dotGapPx = 16f,
    dotInactiveAlpha = 0.4f,
)

internal fun testHeroTextStyle(): TvNativeHeroTextStyle = TvNativeHeroTextStyle(
    title = testTextStyle(56f, 72),
    titleMaxLines = 1,
    rating = testTextStyle(32f, 44),
    meta = testTextStyle(28f, 40),
    status = testTextStyle(28f, 40),
    summary = testTextStyle(28f, 40),
    star = null,
    starSizePx = 36,
    starGapPx = 8,
    metaGapPx = 32,
    lineSpacingPx = 16,
    statusHeightPx = 0,
    slidePx = 28,
    stagger = false,
    animated = false,
    marqueeRepeat = 0,
)

internal fun testHeroButtonStyle(): TvNativeHeroButtonStyle = TvNativeHeroButtonStyle(
    text = testTextStyle(26f, 36),
    iconSizePx = 36,
    iconGapPx = 14,
    paddingHorizontalPx = 25,
    paddingVerticalPx = 14,
    cornerPx = 16f,
    outlineWidthPx = 1f,
    outlineColor = Color.GRAY,
    filledColor = Color.DKGRAY,
    unfilledColor = Color.BLACK,
    focusedColor = Color.WHITE,
    contentColor = Color.WHITE,
    focusedContentColor = Color.BLACK,
)
