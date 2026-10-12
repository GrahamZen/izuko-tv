/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 弹幕图在后台多个线程上画 (收弹幕的协程放进轨道后画, 弹幕层发现还没画好时另起协程画).
 * 同一段文字的弹幕共用 [TextMeasurer] 缓存里的同一个段落和画笔, 画描边与填充时都要改那支画笔:
 * 同时画就会画错, 例如填充被画成描边 (白字只剩黑边).
 */
class StyledDanmakuRasterRaceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(context),
        defaultDensity = Density(context),
        defaultLayoutDirection = LayoutDirection.Ltr,
    )
    private val baseStyle = TextStyle(fontSize = 24.sp)

    private fun newDanmaku(text: String) = dummyDanmaku(measurer, baseStyle, DanmakuStyle.Default, text)

    @Test
    fun `same text rasterized on several threads at once matches a single-threaded rasterization`() {
        val text = "名场面！"
        val reference = newDanmaku(text).cachedImage().bitmap.asAndroidBitmap()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        var wrong = 0
        try {
            repeat(ROUNDS) {
                val danmaku = List(DANMAKU_PER_ROUND) { newDanmaku(text) }
                val barrier = CyclicBarrier(threads)
                val tasks = List(threads) { t ->
                    pool.submit {
                        barrier.await()
                        // 各线程从不同位置开始, 同一条也会被两个线程同时画
                        for (i in danmaku.indices) danmaku[(i + t) % danmaku.size].cachedImage()
                    }
                }
                tasks.forEach { it.get(30, TimeUnit.SECONDS) }
                wrong += danmaku.count { !it.cachedImage().bitmap.asAndroidBitmap().sameAs(reference) }            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(0, wrong, "画错的弹幕图 (共 ${ROUNDS * DANMAKU_PER_ROUND} 张)")
    }

    private companion object {
        const val ROUNDS = 200
        const val DANMAKU_PER_ROUND = 8
    }
}
