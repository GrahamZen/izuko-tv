/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import me.him188.ani.app.data.models.recommend.RecommendedSubjectInfo
import me.him188.ani.app.data.recommendation.RecommendationGroup
import me.him188.ani.app.data.recommendation.RecommendationGroupKind
import kotlin.test.Test
import kotlin.test.assertEquals

class TvRecRowsTest {
    private fun group(kind: RecommendationGroupKind, size: Int, firstId: Int = 1) = RecommendationGroup(
        kind,
        titleArg = null,
        items = List(size) { RecommendedSubjectInfo(firstId + it, "", "") },
    )

    private fun List<TvRecRow>.shape() = map { Triple(it.start, it.size, it.header) }

    @Test
    fun `未登录那一组只排满行 - 末尾凑不满一行的不放`() {
        val rows = tvRecRowsOf(listOf(group(RecommendationGroupKind.FEED, 200)), feedRowSize = 6)
        assertEquals(33, rows.size)
        assertEquals(List(33) { Triple(it * 6, 6, it == 0) }, rows.shape())
    }

    @Test
    fun `未登录那一组正好整行 - 全放`() {
        val rows = tvRecRowsOf(listOf(group(RecommendationGroupKind.FEED, 12)), feedRowSize = 6)
        assertEquals(listOf(Triple(0, 6, true), Triple(6, 6, false)), rows.shape())
    }

    @Test
    fun `未登录那一组不到一行 - 照放`() {
        val rows = tvRecRowsOf(listOf(group(RecommendationGroupKind.FEED, 4)), feedRowSize = 6)
        assertEquals(listOf(Triple(0, 4, true)), rows.shape())
    }

    @Test
    fun `登录后的分组一组一行 - 条数不截`() {
        val rows = tvRecRowsOf(
            listOf(
                group(RecommendationGroupKind.BECAUSE_YOU_LIKED, 9),
                group(RecommendationGroupKind.TOP_RATED, 12, firstId = 100),
            ),
            feedRowSize = 6,
        )
        assertEquals(listOf(Triple(0, 9, true), Triple(9, 12, true)), rows.shape())
    }
}
