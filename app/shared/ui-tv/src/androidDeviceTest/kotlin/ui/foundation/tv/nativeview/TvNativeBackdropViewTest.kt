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
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 背景图换图 ([TvNativeBackdropView.show]): 交叉淡入不分视觉效果档 —— 旧图撑到新图就位; 刚建出来 / 刚重建过的头一次直接出现.
 * 换图那几条的地址指向不存在的文件: 加载失败无妨, 测的是各格的去留与透明度 (show 当场定).
 *
 * 剧照升档 ([TvNativeBackdropTarget.upgradeUrl]) 那几条用测试写的 PNG: 主图真解码出来才开始计静止时间.
 */
class TvNativeBackdropViewTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private lateinit var backdrop: TvNativeBackdropView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            backdrop = TvNativeBackdropView(host.activity, host.sketch, scope)
            host.root.addView(backdrop, FrameLayout.LayoutParams(1344, 756))
        }
        host.waitUntil("量出尺寸") { backdrop.width > 0 }
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    @Test
    fun `first image appears directly`() {
        val alphas = host.onMain {
            backdrop.show(image("a"))
            slotAlphas(backdrop)
        }
        assertEquals(listOf(1f), alphas)
    }

    @Test
    fun `switching images keeps the old one until the crossfade ends`() {
        val alphas = host.onMain {
            backdrop.show(image("a"))
            backdrop.show(image("b"))
            slotAlphas(backdrop)
        }
        // 旧图还满着 (淡出刚起步), 新图从 0 淡入
        assertEquals(listOf(1f, 0f), alphas)
        host.waitUntil("旧图淡完撤掉") { slotAlphas(backdrop) == listOf(1f) }
    }

    @Test
    fun `after rebuild the next image appears directly`() {
        val alphas = host.onMain {
            backdrop.show(image("a"))
            backdrop.show(image("b"))
            backdrop.rebuild()
            backdrop.show(image("c"))
            slotAlphas(backdrop)
        }
        assertEquals(listOf(1f), alphas)
    }

    @Test
    fun `an image arriving after an empty start fades in`() {
        // 重建那一刻的目标 (这里是没有图) 直接画, 之后来的图照常淡入
        val alphas = host.onMain {
            backdrop.rebuild()
            backdrop.show(null)
            backdrop.show(image("a"))
            slotAlphas(backdrop)
        }
        assertEquals(listOf(0f), alphas)
    }

    @Test
    fun `crossfade off replaces the old image at once`() {
        val alphas = host.onMain {
            backdrop.show(image("a"))
            backdrop.show(image("b"), crossfade = false)
            slotAlphas(backdrop)
        }
        assertEquals(listOf(1f), alphas)
    }

    @Test
    fun `explore page crossfades the carousel backdrop with transitions off`() {
        val style = testWallStyle()
        val empty = TvNativeHeroSource(backdrop = null, dimming = false, rawSubjectId = null, text = null)
        lateinit var view: TvNativeExploreView
        host.onMain {
            host.root.removeView(backdrop)
            view = TvNativeExploreView(
                host.activity, host.sketch, scope, style, testExploreMetrics(style),
                testHeroTextStyle(), testHeroButtonStyle(), testTextStyle(32f, 44),
            )
            view.transitions = false
            view.animatedScroll = false
            view.setButtons("立即观看", null, "新番时间表", null)
            view.setSources(empty.copy(backdrop = image("a")), empty)
            view.setItems(listOf(TvNativeExploreItem.Spacer("spacer"), TvNativeExploreItem.Row("row", testCards(12))))
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        val alphas = host.onMain {
            view.setSources(empty.copy(backdrop = image("b")), empty)
            slotAlphas(requireNotNull(view.findBackdrop()))
        }
        // 流畅档 (过渡关掉) 也交叉淡入: 当帧撤掉旧图的话, 新图下载解码那段 hero 没有背景
        assertEquals(listOf(1f, 0f), alphas)
    }

    @Test
    fun `still upgrade lays the original over the main image once settled`() {
        val main = host.testImage("upgrade-main-a", left = Color.RED, right = Color.BLUE)
        val original = host.testImage("upgrade-original-a", left = Color.GREEN, right = Color.YELLOW)
        host.onMain { backdrop.show(TvNativeBackdropTarget(main, subjectId = null, upgradeUrl = original)) }
        host.waitUntil("主图上屏") { currentSlotImages(backdrop).firstOrNull()?.drawable != null }
        // 静止时间还没到: 不去取原图
        assertEquals(1, host.onMain { currentSlotImages(backdrop).size })
        host.waitUntil("原图叠上并淡满", timeoutMillis = 5000) { upgradeShown(backdrop) }
        // 原图叠在同一格里, 不是另起一格交叉淡入
        assertEquals(listOf(1f), host.onMain { slotAlphas(backdrop) })
    }

    @Test
    fun `no still upgrade while navigating`() {
        val navigating = mutableStateOf(true)
        val main = host.testImage("upgrade-main-b", left = Color.RED, right = Color.BLUE)
        val original = host.testImage("upgrade-original-b", left = Color.GREEN, right = Color.YELLOW)
        host.onMain {
            backdrop.navigating = { navigating.value }
            backdrop.show(TvNativeBackdropTarget(main, subjectId = null, upgradeUrl = original))
        }
        host.waitUntil("主图上屏") { currentSlotImages(backdrop).firstOrNull()?.drawable != null }
        // 静止时间早过了, 但一直在导航 (滚动 / 按住方向键): 不去取原图
        SystemClock.sleep(2500)
        assertEquals(1, host.onMain { currentSlotImages(backdrop).size })
        host.onMain {
            navigating.value = false
            Snapshot.sendApplyNotifications()
        }
        host.waitUntil("停下来就叠上原图") { upgradeShown(backdrop) }
    }

    @Test
    fun `an upgrade target added to the shown image upgrades it in place`() {
        val main = host.testImage("upgrade-main-c", left = Color.RED, right = Color.BLUE)
        val original = host.testImage("upgrade-original-c", left = Color.GREEN, right = Color.YELLOW)
        host.onMain { backdrop.show(TvNativeBackdropTarget(main, subjectId = null)) }
        host.waitUntil("主图上屏") { currentSlotImages(backdrop).firstOrNull()?.drawable != null }
        val alphas = host.onMain {
            backdrop.show(TvNativeBackdropTarget(main, subjectId = null, upgradeUrl = original))
            slotAlphas(backdrop)
        }
        // 同一张主图只多了升档目标: 不算换图, 不交叉淡入
        assertEquals(listOf(1f), alphas)
        host.waitUntil("原图叠上并淡满", timeoutMillis = 5000) { upgradeShown(backdrop) }
    }

    private fun image(name: String) = TvNativeBackdropTarget(url = "file:///data/local/tmp/tv-native-test-missing-$name.jpg", subjectId = null)

    /** 当前那一格 (最不透明的一格) 里的各张图: 主图在前, 升档原图 (有的话) 叠在最后. 测试不带垫底. */
    private fun currentSlotImages(of: TvNativeBackdropView): List<ImageView> {
        val frame = (0 until of.childCount).map { of.getChildAt(it) }.filterIsInstance<FrameLayout>().maxByOrNull { it.alpha }
            ?: return emptyList()
        return (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<ImageView>()
    }

    /** 升档原图已经解码好、淡入到满. */
    private fun upgradeShown(of: TvNativeBackdropView): Boolean =
        currentSlotImages(of).getOrNull(1)?.let { it.drawable != null && it.alpha == 1f } == true

    /** 各格 (一张图一格, 按叠放次序) 此刻的透明度. 格是 FrameLayout, 压在最上面的遮罩层不是. */
    private fun slotAlphas(of: TvNativeBackdropView): List<Float> =
        (0 until of.childCount).map { of.getChildAt(it) }.filterIsInstance<FrameLayout>().map { it.alpha }

    private fun View.findBackdrop(): TvNativeBackdropView? = when (this) {
        is TvNativeBackdropView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findBackdrop() }
        else -> null
    }
}
