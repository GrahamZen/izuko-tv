/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 详情页换页时一页露在屏上的比例 ([tvDetailsSwapShownFraction]): 屏外的页不淡、露一截的页不建离屏层. 视口 1080, 下移至多 324, 上移至多 24. */
class TvDetailsSwapShownFractionTest {
    @Test
    fun `page inside the viewport is fully shown`() {
        assertEquals(1f, tvDetailsSwapShownFraction(top = 0f, height = 928f, viewport = 1080f, lo = 0f, hi = 324f))
    }

    @Test
    fun `next page peeking under the target shows only its visible part`() {
        // 选集页 928 高, 下一页的页顶落在 928: 露出底下 152 像素
        val shown = tvDetailsSwapShownFraction(top = 928f, height = 856f, viewport = 1080f, lo = 0f, hi = 324f)
        assertEquals(152f / 856f, shown, 0.0001f)
    }

    @Test
    fun `pages beyond the viewport for the whole swap are not shown`() {
        // 目标页再往下隔一页: 从下方滑上来的那段位移里也碰不到视口
        assertEquals(0f, tvDetailsSwapShownFraction(top = 1784f, height = 889f, viewport = 1080f, lo = 0f, hi = 324f))
        // 往下翻时被翻过去的首屏: 页底正好贴着视口顶, 再上移也还在屏外
        assertEquals(0f, tvDetailsSwapShownFraction(top = -1008f, height = 1008f, viewport = 1080f, lo = -24f, hi = 0f))
    }

    @Test
    fun `page reaching the viewport only during the shift is faded without an offscreen layer`() {
        // 静止时在视口下方, 往上移的那 24 像素里露出来: 要淡, 但露出比例小于建离屏层的门槛
        val shown = tvDetailsSwapShownFraction(top = 1080f, height = 800f, viewport = 1080f, lo = -24f, hi = 0f)
        assertTrue(shown > 0f && shown < 0.5f, "shown = $shown")
    }

    @Test
    fun `empty page or viewport is not shown`() {
        assertEquals(0f, tvDetailsSwapShownFraction(top = 0f, height = 0f, viewport = 1080f, lo = 0f, hi = 324f))
        assertEquals(0f, tvDetailsSwapShownFraction(top = 0f, height = 900f, viewport = 0f, lo = 0f, hi = 324f))
    }
}
