/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import kotlin.test.Test
import kotlin.test.assertEquals

class BrandAdviceTest {
    @Test
    fun `按厂商或品牌认出电视牌子`() {
        assertEquals(R.string.advice_xiaomi, BrandAdvice.forDevice("Xiaomi", "Xiaomi"))
        assertEquals(R.string.advice_xiaomi, BrandAdvice.forDevice("Xiaomi", "Redmi"))
        assertEquals(R.string.advice_tcl, BrandAdvice.forDevice("TCL", "TCL"))
        // 雷鸟的 BRAND 是 FFALCON
        assertEquals(R.string.advice_tcl, BrandAdvice.forDevice("TCL", "FFALCON"))
        assertEquals(R.string.advice_hisense, BrandAdvice.forDevice("Hisense", "Vidda"))
        assertEquals(R.string.advice_skyworth, BrandAdvice.forDevice("Skyworth", "Coocaa"))
        assertEquals(R.string.advice_huawei, BrandAdvice.forDevice("HUAWEI", "HONOR"))
    }

    @Test
    fun `认不出的牌子给通用建议`() {
        assertEquals(R.string.advice_generic, BrandAdvice.forDevice("Sony", "Sony"))
        assertEquals(R.string.advice_generic, BrandAdvice.forDevice(null, null))
    }
}
