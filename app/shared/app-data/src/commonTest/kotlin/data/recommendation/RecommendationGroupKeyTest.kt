/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.recommendation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecommendationGroupKeyTest {
    @Test
    fun `种子行的键带着种子 - 能反解出种类与种子`() {
        val first = recommendationGroupKey(RecommendationGroupKind.BECAUSE_YOU_LIKED, 0, seedSubjectId = 326)
        val second = recommendationGroupKey(RecommendationGroupKind.BECAUSE_YOU_LIKED, 1, seedSubjectId = 51)
        assertEquals("because_you_liked@326", first)
        assertEquals("because_you_liked#1@51", second)
        assertEquals(RecommendationGroupKind.BECAUSE_YOU_LIKED, RecommendationGroupKind.ofKeyOrNull(first))
        assertEquals(RecommendationGroupKind.BECAUSE_YOU_LIKED, RecommendationGroupKind.ofKeyOrNull(second))
        assertEquals(326, seedSubjectIdOfGroupKey(first))
        assertEquals(51, seedSubjectIdOfGroupKey(second))
    }

    @Test
    fun `别的组的键不变 - 没有种子`() {
        assertEquals("top_rated", recommendationGroupKey(RecommendationGroupKind.TOP_RATED, 0, seedSubjectId = null))
        assertEquals("similar_to#2", recommendationGroupKey(RecommendationGroupKind.SIMILAR_TO, 2, seedSubjectId = null))
        assertEquals(RecommendationGroupKind.SIMILAR_TO, RecommendationGroupKind.ofKeyOrNull("similar_to#2"))
        assertNull(seedSubjectIdOfGroupKey("similar_to#2"))
        assertNull(seedSubjectIdOfGroupKey("feed"))
    }
}
