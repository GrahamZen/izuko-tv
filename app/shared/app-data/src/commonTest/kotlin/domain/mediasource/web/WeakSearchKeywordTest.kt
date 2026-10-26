/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import me.him188.ani.app.domain.mediasource.search.KeywordStyle
import me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeakSearchKeywordTest {
    @Test
    fun `short latin words are weak`() {
        for (keyword in listOf("to", "Re", "one", "86")) assertTrue(isWeakSearchKeyword(keyword), keyword)
        for (keyword in listOf("fate", "更多", "冰菓", "k-on", "toらぶる")) assertFalse(isWeakSearchKeyword(keyword), keyword)
    }

    @Test
    fun `fallback cut down to a short latin word is dropped`() {
        // 「更多 出包王女」的系列基础名: 中文名「出包王女」, 第一季原名「To LOVEる -とらぶる-」取首词只剩「To」
        val request = MediaFetchRequest(
            subjectId = "1",
            episodeId = "1",
            subjectNames = listOf("更多 出包王女"),
            episodeSort = EpisodeSort(1),
            episodeName = "",
            fallbackSearchKeywords = listOf("出包王女", "To LOVEる -とらぶる-", "NEW GAME!"),
        )
        val plan = SubjectSearchPlan.of(request)
        assertEquals(
            listOf("出包王女", "更多出包王女"),
            plan.keywords(KeywordStyle(onlyFirstWord = true, removeSpecial = true, primaryCount = 1)).fallbackNames,
        )
        // 不取首词时是整个名字, 照搜
        assertEquals(
            listOf("出包王女", "To LOVEる -とらぶる-", "NEW GAME!", "更多出包王女"),
            plan.keywords(KeywordStyle(onlyFirstWord = false, removeSpecial = true, primaryCount = 1)).fallbackNames,
        )
    }
}
