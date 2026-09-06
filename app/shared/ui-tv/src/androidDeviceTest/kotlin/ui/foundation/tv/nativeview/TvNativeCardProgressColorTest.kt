/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.widget.FrameLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 卡片进度条 ([rememberTvNativeWallStyle]): 已看那段用主题色 (压在封面图上要够跳), 底是恒定的淡白 —— **底不跟主题深浅走**, 跟着换成
 * 深色的话浅色主题下压在深色封面上就看不见了. 条长按这组卡自己的封面宽算 (卡宽随每排张数与「海报墙大小」变), 不是写死的那一档.
 */
class TvNativeCardProgressColorTest {
    private val host = TvNativeTestHost()

    @AfterTest
    fun tearDown() = host.close()

    private fun styleIn(dark: Boolean): TvNativeWallStyle {
        host.launch()
        var style: TvNativeWallStyle? = null
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(
                        LocalSketch provides host.sketch,
                        LocalThemeSettings provides ThemeSettings.Default,
                    ) {
                        style = rememberTvNativeWallStyle(cardWidth = 160.dp, columns = 6)
                    }
                }
            }
        }
        host.waitUntil("样式算出来") { style != null }
        return style!!
    }

    @Test
    fun `the progress bar takes the theme color over a white track`() {
        val style = styleIn(dark = true)
        assertEquals(darkColorScheme().primary.toArgb(), style.progressFillColor, "已看那段是主题色")
        assertEquals(Color.White.toArgb() and 0xFFFFFF, style.progressTrackColor and 0xFFFFFF, "底是白")
    }

    @Test
    fun `the progress bar takes the theme color in light mode too`() {
        val style = styleIn(dark = false)
        assertEquals(lightColorScheme().primary.toArgb(), style.progressFillColor, "已看那段是主题色")
        assertEquals(Color.White.toArgb() and 0xFFFFFF, style.progressTrackColor and 0xFFFFFF, "底是白")
    }

    @Test
    fun `the progress bar spans the cover width minus its two corners`() {
        val style = styleIn(dark = true)
        val cover = style.coverWidthPx
        assertTrue(style.progressBarLengthPx <= cover, "条不能超出封面 (条 ${style.progressBarLengthPx}, 封面 $cover)")
        // 去掉两个圆角那一截 (再放宽 2dp), 肉眼上就是「与卡片底边同宽」
        assertTrue(style.progressBarLengthPx >= cover * 0.8f, "条应接近封面宽 (条 ${style.progressBarLengthPx}, 封面 $cover)")
    }
}
