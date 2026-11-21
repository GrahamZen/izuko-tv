/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.source.MediaFetchHostWork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HostWorksTest {
    /** 全部动画的表的几行: TV 13 集与接在后面编号 (第一集 sort 14) 的 OVA, 再加一部第一集 sort 是 1 的 OVA */
    private val table = assertNotNull(
        SeriesGraphTable.parse(
            listOf(
                "# bangumi-anime v1 max_id=700000 dump=dump-2026-10-20.210332Z.zip | 列: …",
                "337\tフルメタル・パニック! The Second Raid\t全金属狂潮 The Second Raid\t2005-07-13\t13\tTV\t\t334\t150246\t\t\t全金属狂潮第三季|全金属狂潮3|Full Metal Panic! The Second Raid",
                "2689\tフルメタル・パニック! The Second Raid 特別版OVA「わりとヒマな戦隊長の一日」\t全金属狂潮 The Second Raid OVA 战队长悠闲的一天\t2006-05-26\t1\tOVA\t\t\t\t14\t\t",
                "5000\tある OVA\t某 OVA\t2010-01-01\t1\tOVA\t\t\t\t\t\t",
            ).joinToString("\n").encodeToByteArray(),
        ),
    )

    private fun story(id: Int, episodes: Int? = null) = SeriesNode(
        id = id, name = "story $id", nameCn = "", imageLarge = "", metaTags = emptyList(), nsfw = false,
        airDate = PackedDate.Invalid, episodes = episodes,
    )

    @Test
    fun `the main story whose episodes end right before the OVA is its host`() = runTest {
        var requests = 0
        val host = findHostWork(2689, isolated = true, table) { requests++; listOf(story(337)) }
        assertEquals(
            MediaFetchHostWork(
                names = listOf(
                    "フルメタル・パニック! The Second Raid", "全金属狂潮 The Second Raid",
                    "全金属狂潮第三季", "全金属狂潮3", "Full Metal Panic! The Second Raid",
                ),
                firstSort = 1,
                episodeCount = 13,
            ),
            host,
        )
        assertEquals(1, requests)
    }

    @Test
    fun `no request for subjects numbered from 1 or on a sequel chain`() = runTest {
        var requests = 0
        assertNull(findHostWork(5000, isolated = true, table) { requests++; listOf(story(337)) })
        assertNull(findHostWork(2689, isolated = false, table) { requests++; listOf(story(337)) })
        assertNull(findHostWork(2689, isolated = true, null) { requests++; listOf(story(337)) })
        assertEquals(0, requests)
    }

    @Test
    fun `a failed request means no host`() = runTest {
        assertNull(findHostWork(2689, isolated = true, table) { error("offline") })
    }

    @Test
    fun `a main story whose episodes do not end right before the OVA is not its host`() = runTest {
        // 不在表里的本篇按关系里的集数算: 12 集接不到 14
        assertNull(findHostWork(2689, isolated = true, table) { listOf(story(9999, episodes = 12)) })
        val outside = findHostWork(2689, isolated = true, table) { listOf(story(9999, episodes = 13)) }
        assertEquals(listOf("story 9999"), outside?.names)
    }
}
