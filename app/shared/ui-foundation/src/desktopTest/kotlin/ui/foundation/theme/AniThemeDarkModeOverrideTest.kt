/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalPlatformFontFamily
import me.him188.ani.app.ui.foundation.PlatformFontFamily
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 强制深色区域 (如播放器) 里的条目取色主题: 用户设置是浅色时也得生成深色配色, 否则播放器内嵌详情页成了黑字压在视频遮罩上.
 */
class AniThemeDarkModeOverrideTest {
    @Test
    fun `subject palette theme follows dark override under light settings`() = runAniComposeUiTest {
        val subjectKey = Any()
        SubjectSeedColorCache[subjectKey] = Color(0xFF3F7FBF)
        var darkModeInside: DarkMode? = null
        var surface = Color.Unspecified
        setContent {
            CompositionLocalProvider(
                LocalThemeSettings provides ThemeSettings.Default.copy(darkMode = DarkMode.LIGHT),
                LocalPlatformFontFamily provides PlatformFontFamily(null),
            ) {
                AniTheme(darkModeOverride = DarkMode.DARK) {
                    darkModeInside = LocalThemeSettings.current.darkMode
                    MaterialThemeFromPaletteAndImage(palette = null, cacheKey = subjectKey) {
                        surface = MaterialTheme.colorScheme.surface
                    }
                }
            }
        }
        runOnIdle {
            assertEquals(DarkMode.DARK, darkModeInside)
            assertTrue(surface.luminance() < 0.5f, "surface luminance ${surface.luminance()}")
        }
    }
}
