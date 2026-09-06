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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 原生视图聚焦底色的过渡 ([tvNativeLerpColor]): 淡出到全透明时只变淡、不往黑里走 (浅色设置页焦点一走旧位置闪灰的那个问题).
 */
class TvNativeLerpColorTest {
    @Test
    fun `fading a white platter out stays white`() {
        val half = tvNativeLerpColor(0, Color.WHITE, 0.5f)
        assertEquals(255, Color.red(half))
        assertEquals(255, Color.green(half))
        assertEquals(255, Color.blue(half))
        assertTrue(Color.alpha(half) in 126..129, "alpha ${Color.alpha(half)}")
    }

    @Test
    fun `opaque colors mix channel by channel`() {
        val mid = tvNativeLerpColor(Color.BLACK, Color.WHITE, 0.5f)
        assertEquals(255, Color.alpha(mid))
        assertTrue(Color.red(mid) in 126..129, "red ${Color.red(mid)}")
    }

    @Test
    fun `ends are returned unchanged`() {
        val translucent = Color.argb(26, 28, 27, 31)
        assertEquals(translucent, tvNativeLerpColor(translucent, Color.WHITE, 0f))
        assertEquals(Color.WHITE, tvNativeLerpColor(translucent, Color.WHITE, 1f))
        assertEquals(0, tvNativeLerpColor(0, 0, 0.5f))
    }
}
