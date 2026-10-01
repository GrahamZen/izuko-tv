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
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * hero 文字块里评分数字的颜色 ([rememberTvNativeHeroTextStyle]): 与标题同为主要文字色, **不用主题色** —— 主题色是中等亮度的彩色,
 * 压在 hero 图 / 模糊背景上与它撞色 (背景是每部番自己的图, 颜色不定, 实测对比度低到 1.3). 颜色只留在旁边的星上.
 */
class TvNativeHeroRatingColorTest {
    private val host = TvNativeTestHost()

    @AfterTest
    fun tearDown() = host.close()

    private fun styleIn(dark: Boolean): TvNativeHeroTextStyle {
        host.launch()
        var style: TvNativeHeroTextStyle? = null
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(LocalThemeSettings provides ThemeSettings.Default) {
                        style = rememberTvNativeHeroTextStyle(titleMaxLines = 1, lineSpacing = 10.dp)
                    }
                }
            }
        }
        host.waitUntil("排版参数算出来") { style != null }
        return style!!
    }

    @Test
    fun `the rating takes the title color instead of the theme color in dark mode`() {
        val style = styleIn(dark = true)
        assertEquals(style.title.color, style.rating.color, "评分与标题同色")
        assertNotEquals(darkColorScheme().primary.toArgb(), style.rating.color, "评分不用主题色")
    }

    @Test
    fun `the summary is one tier dimmer than the lines above it`() {
        // 简介是次要信息: 比标题 / 评分 / 下一集行淡一档, 几行字之间才有层次
        val style = styleIn(dark = true)
        assertNotEquals(style.title.color, style.summary.color, "简介与标题不同色")
        assertEquals(style.meta.color, style.summary.color, "简介与信息行同为次要色")
    }

    @Test
    fun `the summary is dimmer in light mode too`() {
        val style = styleIn(dark = false)
        assertNotEquals(style.title.color, style.summary.color, "简介与标题不同色")
        assertEquals(style.meta.color, style.summary.color, "简介与信息行同为次要色")
    }

    @Test
    fun `the rating takes the title color in light mode too`() {
        val style = styleIn(dark = false)
        assertEquals(style.title.color, style.rating.color, "评分与标题同色")
        assertNotEquals(lightColorScheme().primary.toArgb(), style.rating.color, "评分不用主题色")
        assertNotEquals(Color.White.toArgb(), style.rating.color, "浅色主题下是深色字")
    }
}
