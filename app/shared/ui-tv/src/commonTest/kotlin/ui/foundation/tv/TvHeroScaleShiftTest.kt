/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [tvHeroScaleShift]: 页面不高于参照高 (1080p 电视 100% 及更大的缩放) 时一点不挪; 更高时参照高下的那条线跟页面高按同一比例下移,
 * 落在背景图 (按页面高的比例画) 的同一处.
 */
class TvHeroScaleShiftTest {
    @Test
    fun `no shift at the reference height or on shorter pages`() {
        // 正好是 0, 不是约等于: 100% 时排版一个像素都不能变
        assertEquals(0.dp, tvHeroScaleShift(TV_HERO_REFERENCE_PAGE_HEIGHT, 348.dp))
        assertEquals(0.dp, tvHeroScaleShift(TV_HERO_REFERENCE_PAGE_HEIGHT, TV_POSTER_WALL_HERO_ROW_TOP))
        // 110%、120%
        assertEquals(0.dp, tvHeroScaleShift(TV_HERO_REFERENCE_PAGE_HEIGHT / 1.1f, 348.dp))
        assertEquals(0.dp, tvHeroScaleShift(450.dp, 348.dp))
    }

    @Test
    fun `taller pages move the line in proportion to the page height`() {
        // 80%: 页面高 675dp, 参照高下 348dp 的海报顶边挪到 435dp, 与页面高之比不变
        assertEquals(87f, tvHeroScaleShift(675.dp, 348.dp).value, 0.01f)
        assertEquals(348f / 540f, (348.dp + tvHeroScaleShift(675.dp, 348.dp)) / 675.dp, 0.0001f)
        // 50%: 页面高翻倍, 线也翻倍
        assertEquals(348f, tvHeroScaleShift(1080.dp, 348.dp).value, 0.01f)
    }
}
