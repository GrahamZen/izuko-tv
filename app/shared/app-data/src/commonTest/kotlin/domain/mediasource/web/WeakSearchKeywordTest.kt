/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeakSearchKeywordTest {
    private val config = SelectorSearchConfig(searchUrl = "https://example.com/search?wd={keyword}")

    @Test
    fun `short latin words are weak`() {
        for (keyword in listOf("to", "Re", "one", "86")) assertTrue(isWeakSearchKeyword(keyword), keyword)
        for (keyword in listOf("fate", "更多", "冰菓", "k-on", "toらぶる")) assertFalse(isWeakSearchKeyword(keyword), keyword)
    }

    @Test
    fun `fallback cut down to a short latin word is dropped`() {
        // 「更多 出包王女」的系列基础名: 中文名「出包王女」, 第一季原名「To LOVEる -とらぶる-」取首词只剩「To」
        val fallback = listOf("出包王女", "To LOVEる -とらぶる-", "NEW GAME!")
        assertEquals(
            listOf("出包王女"),
            config.distinctFallbackKeywords(primary = listOf("更多 出包王女"), fallback = fallback),
        )
        // 不取首词时是整个名字, 照搜
        assertEquals(
            fallback,
            config.copy(autoMatch = SelectorAutoMatchConfig(searchUseOnlyFirstWord = false))
                .distinctFallbackKeywords(primary = listOf("更多 出包王女"), fallback = fallback),
        )
    }
}
