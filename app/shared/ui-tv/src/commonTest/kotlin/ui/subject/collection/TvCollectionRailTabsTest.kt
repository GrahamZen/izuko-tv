/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DOING
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DONE
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DROPPED
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.ON_HOLD
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.WISH
import kotlin.test.Test
import kotlin.test.assertEquals

/** 追番页标签行的整排: 分类的先后与「追更」(null) 的位置分两份存, 合起来 ([tvCollectionRailTabs]). */
class TvCollectionRailTabsTest {
    private val order = listOf(DOING, WISH, ON_HOLD, DONE, DROPPED)

    @Test
    fun `the default puts the progress tab first`() {
        assertEquals(listOf(null, DOING, WISH, ON_HOLD, DONE, DROPPED), tvCollectionRailTabs(order, 0))
        assertEquals(listOf(null) + TV_COLLECTION_TABS, TV_COLLECTION_RAIL_TABS)
    }

    @Test
    fun `the progress tab goes at its saved position among the categories`() {
        assertEquals(listOf(DOING, WISH, null, ON_HOLD, DONE, DROPPED), tvCollectionRailTabs(order, 2))
        assertEquals(listOf(DOING, WISH, ON_HOLD, DONE, DROPPED, null), tvCollectionRailTabs(order, 5))
    }

    @Test
    fun `a position out of range goes to the nearest end`() {
        assertEquals(listOf(DOING, WISH, ON_HOLD, DONE, DROPPED, null), tvCollectionRailTabs(order, 9))
        assertEquals(listOf(null, DOING, WISH, ON_HOLD, DONE, DROPPED), tvCollectionRailTabs(order, -3))
    }
}
