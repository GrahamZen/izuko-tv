/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.app.data.network.SeriesGraphTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 全部动画的名字表 ([SeriesGraphTable] 的别名列) 与名字索引 ([AnimeNameIndex]).
 */
class AnimeNameIndexTest {
    private val table = assertNotNull(
        SeriesGraphTable.parse(
            listOf(
                "# bangumi-anime v1 max_id=600000 dump=dump-2026-10-20.210353Z.zip | 列: …",
                "234\tAIR\tAIR\t2005-01-06\t12\tTV\t\t\t\t\t\tAIR TV|青空",
                "1229\tKanon\tKanon\t2002-01-31\t13\tTV\t\t\t\t\t\t雪之少女|Kanon（第二部 BS-i版）",
                "1230\tKanon\tKanon\t2006-10-05\t24\tTV\t\t\t\t\t\t雪之少女",
                "2471\tエア・ギア\t飞轮少年\t2006-04-04\t25\tTV\t\t\t\t\t\tAir Gear",
                "300\tクラナド\tCLANNAD\t2007-10-04\t23\tTV\t\t\t301\t\t\t",
                "301\tクラナド アフターストーリー\tCLANNAD ～AFTER STORY～\t2008-10-02\t24\tTV\t\t300\t\t\t\t",
            ).joinToString("\n").encodeToByteArray(),
        ),
    )
    private val index = AnimeNameIndex.of(table)

    @Test
    fun `anime table carries aliases`() {
        assertTrue(table.allAnime)
        assertEquals(listOf("AIR", "AIR TV", "青空"), table.namesOf(234))
        assertEquals(listOf("クラナド", "CLANNAD"), table.namesOf(300))
        assertEquals(listOf(2471), table.nodesContaining("Air Gear").map { it.id })
        assertEquals(listOf(300), table.edgesOf(301)?.prequels?.map { it.id })
    }

    @Test
    fun `series graph table has no aliases`() {
        val graph = assertNotNull(
            SeriesGraphTable.parse("# bangumi-series-graph v1 max_id=600000 dump=x\n300\tクラナド\tCLANNAD\t2007-10-04\t23\tTV\t\t\t301".encodeToByteArray()),
        )
        assertFalse(graph.allAnime)
        assertEquals(listOf("クラナド", "CLANNAD"), graph.namesOf(300))
    }

    @Test
    fun `finds works by the whole name ignoring case and symbols`() {
        assertEquals(listOf(1229, 1230), index.worksNamed("Kanon").map { it.id })
        assertEquals(listOf(1229, 1230), index.worksNamed("雪之少女").map { it.id })
        assertEquals(listOf(2471), index.worksNamed("AIR GEAR").map { it.id })
        assertEquals(listOf(301), index.worksNamed("Clannad After Story").map { it.id })
    }

    @Test
    fun `part of a name finds nothing`() {
        assertEquals(emptyList(), index.worksNamed("CLANNAD AFTER").map { it.id })
        assertEquals(emptyList(), index.worksNamed("少女").map { it.id })
    }
}
