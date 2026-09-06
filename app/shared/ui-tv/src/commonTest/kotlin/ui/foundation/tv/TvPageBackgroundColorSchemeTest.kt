/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** [tvPageBackgroundColorScheme]: 浅色只换页面底那三档, 底板、弹窗、菜单与文字色原样; 深色整套原样. */
class TvPageBackgroundColorSchemeTest {
    @Test
    fun `light replaces page background roles only`() {
        val base = lightColorScheme()
        val scheme = tvPageBackgroundColorScheme(base)
        assertEquals(TV_POSTER_WALL_BACKGROUND_LIGHT, scheme.background)
        assertEquals(TV_POSTER_WALL_BACKGROUND_LIGHT, scheme.surface)
        assertEquals(TV_POSTER_WALL_BACKGROUND_LIGHT, scheme.surfaceContainerLowest)
        // 底板 (Low)、菜单 (Container)、弹窗 (High) 与文字色不动
        assertEquals(base.surfaceContainerLow, scheme.surfaceContainerLow)
        assertEquals(base.surfaceContainer, scheme.surfaceContainer)
        assertEquals(base.surfaceContainerHigh, scheme.surfaceContainerHigh)
        assertEquals(base.surfaceContainerHighest, scheme.surfaceContainerHighest)
        assertEquals(base.surfaceVariant, scheme.surfaceVariant)
        assertEquals(base.onBackground, scheme.onBackground)
        assertEquals(base.onSurface, scheme.onSurface)
        assertEquals(base.onSurfaceVariant, scheme.onSurfaceVariant)
    }

    @Test
    fun `light result is still judged light downstream`() {
        // 海报墙配色、详情页配色与主壳的 hero 底都按 surface 的亮度分深浅色
        val scheme = tvPageBackgroundColorScheme(lightColorScheme())
        assertEquals(TV_POSTER_WALL_BACKGROUND_LIGHT, tvPosterWallColorScheme(scheme).background)
    }

    @Test
    fun `dark is unchanged`() {
        val base = darkColorScheme()
        assertSame(base, tvPageBackgroundColorScheme(base))
    }
}
