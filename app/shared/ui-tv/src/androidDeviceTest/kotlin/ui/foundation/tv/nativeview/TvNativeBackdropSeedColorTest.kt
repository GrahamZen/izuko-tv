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
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import me.him188.ani.app.ui.foundation.theme.SubjectSeedColorCache
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.math.abs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 背景图层提前取主色 ([TvNativeBackdropTarget.seedUrl]): 主图是继续观看的单集剧照时, 主色取自详情页铺的那张整部背景, 不取剧照的;
 * 那张的地址还没解析出来时先不取, 晚到时补上; 没给 (取色用的就是主图) 时照旧拿主图算.
 * 测试图都是纯色: 剧照红、整部背景蓝, 取到的主色按色相认. 条目号各条不同 (主色缓存是进程级的).
 */
class TvNativeBackdropSeedColorTest {
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

    private val still by lazy { host.testImage("seed-still", left = Color.RED, right = Color.RED) }
    private val series by lazy { host.testImage("seed-series", left = Color.BLUE, right = Color.BLUE) }

    /** 缓存里 [subjectId] 的主色的色相 (0..360); 还没有时 null. 主线程上调. */
    private fun cachedHue(subjectId: Int): Float? {
        val color = SubjectSeedColorCache[subjectId] ?: return null
        val hsv = FloatArray(3)
        Color.colorToHSV(color.toArgb(), hsv)
        return hsv[0]
    }

    @Test
    fun `an episode still takes the theme color from the series backdrop`() {
        host.onMain { backdrop.show(TvNativeBackdropTarget(still, SUBJECT_STILL, seedUrl = series)) }
        host.waitUntil("取到主色", timeoutMillis = 5000) { cachedHue(SUBJECT_STILL) != null }
        host.onMain { assertHue(BLUE_HUE, cachedHue(SUBJECT_STILL)) }
    }

    @Test
    fun `the theme color waits until the series backdrop is known`() {
        host.onMain { backdrop.show(TvNativeBackdropTarget(still, SUBJECT_LATE, seedUrl = null)) }
        // 剧照解出来了 (这一格已上屏) 也不拿它取色
        host.waitUntil("剧照解出来", timeoutMillis = 5000) { backdropImageLoaded() }
        SystemClock.sleep(500)
        host.onMain { assertNull(cachedHue(SUBJECT_LATE)) }
        // 整部背景的地址晚到: 同一张主图换来的新目标, 当场去取色
        host.onMain { backdrop.show(TvNativeBackdropTarget(still, SUBJECT_LATE, seedUrl = series)) }
        host.waitUntil("取到主色", timeoutMillis = 5000) { cachedHue(SUBJECT_LATE) != null }
        host.onMain { assertHue(BLUE_HUE, cachedHue(SUBJECT_LATE)) }
    }

    @Test
    fun `without a separate image the main image gives the theme color`() {
        host.onMain { backdrop.show(TvNativeBackdropTarget(still, SUBJECT_MAIN)) }
        host.waitUntil("取到主色", timeoutMillis = 5000) { cachedHue(SUBJECT_MAIN) != null }
        host.onMain { assertHue(RED_HUE, cachedHue(SUBJECT_MAIN)) }
    }

    /** 主图已经解出来 (图层里有 ImageView 拿到了 drawable). 主线程上调. */
    private fun backdropImageLoaded(): Boolean {
        fun walk(v: View): Boolean = when (v) {
            is ImageView -> v.drawable != null
            is ViewGroup -> (0 until v.childCount).any { walk(v.getChildAt(it)) }
            else -> false
        }
        return walk(backdrop)
    }

    /** 色相 [hue] 离 [expected] 不超过 [HUE_TOLERANCE] (色相是一圈, 红色在 0 / 360 两头). */
    private fun assertHue(expected: Float, hue: Float?) {
        val d = abs((hue ?: error("还没有主色")) - expected) % 360f
        assertTrue(minOf(d, 360f - d) <= HUE_TOLERANCE, "色相 $hue, 应接近 $expected")
    }

    private companion object {
        const val SUBJECT_STILL = 970_001
        const val SUBJECT_LATE = 970_002
        const val SUBJECT_MAIN = 970_003
        const val RED_HUE = 0f
        const val BLUE_HUE = 240f
        const val HUE_TOLERANCE = 15f
    }
}
