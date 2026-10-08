/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.recommendation.MAX_PREQUEL_HOPS
import me.him188.ani.app.data.recommendation.isSeasonFormat
import me.him188.ani.app.data.recommendation.sequelSeasonCandidates
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 用系列关系图在本地挑续作换季, 结果要与同一份 Bangumi 导出出的「续作 → 候选季」表逐条一致.
 *
 * 默认跳过; 设 `BGM_SERIES_GRAPH` (bgm-series-graph.tsv) 与 `BGM_SEQUEL_SEASONS` (bgm-sequel-seasons.tsv) 才跑, 两张表要出自同一份导出.
 */
class SeriesGraphEquivalenceTest {
    @Test
    fun `关系图上挑的季与候选季表一致`() {
        val graphFile = System.getenv("BGM_SERIES_GRAPH") ?: return println("跳过: 设 BGM_SERIES_GRAPH 与 BGM_SEQUEL_SEASONS 才跑")
        val sequelFile = System.getenv("BGM_SEQUEL_SEASONS") ?: return println("跳过: 没设 BGM_SEQUEL_SEASONS")
        val graph = SeriesGraphTable.parse(File(graphFile).readBytes())!!
        val expected = File(sequelFile).readLines().drop(1).filter { it.isNotBlank() }.associate { line ->
            val (id, candidates) = line.split('\t')
            id.toInt() to candidates.split(',').map { it.toInt() }
        }
        val ids = (graph.subjectIds + expected.keys).toSortedSet()
        val mismatches = runBlocking {
            ids.mapNotNull { id ->
                val chain = walkPrequelChain(id, MAX_PREQUEL_HOPS, ::isSeasonFormat) { graph.edgesOf(it)!! }
                val actual = sequelSeasonCandidates(chain).map { it.id }
                val want = expected[id].orEmpty()
                if (actual == want) null else "$id: graph=$actual table=$want"
            }
        }
        println("比较了 ${ids.size} 部, 不一致 ${mismatches.size}")
        assertEquals(emptyList(), mismatches.take(20))
    }
}
