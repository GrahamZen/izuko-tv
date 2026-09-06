/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.recommendation

import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.network.SeriesEdges
import me.him188.ani.app.data.network.SeriesNode
import me.him188.ani.app.data.network.walkPrequelChain
import me.him188.ani.datasources.api.PackedDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SequelSeasonsTest {
    private fun node(
        id: Int,
        date: String,
        episodes: Int? = 12,
        format: String? = "TV",
        nsfw: Boolean = false,
    ) = SeriesNode(
        id = id,
        name = "n$id",
        nameCn = "",
        imageLarge = "",
        metaTags = listOfNotNull(format, "日本"),
        nsfw = nsfw,
        airDate = PackedDate.parseFromDate(date),
        episodes = episodes,
    )

    /** 关系图: 条目 id -> (前传, 续集), 两个方向照 bangumi 的双向登记写. */
    private class Graph(vararg nodes: SeriesNode) {
        val byId = nodes.associateBy { it.id }
        private val prequels = mutableMapOf<Int, MutableList<Int>>()
        private val sequels = mutableMapOf<Int, MutableList<Int>>()

        fun prequel(of: Int, vararg prequels: Int) = apply {
            for (p in prequels) {
                this.prequels.getOrPut(of) { mutableListOf() } += p
                sequels.getOrPut(p) { mutableListOf() } += of
            }
        }

        fun edges(id: Int) = SeriesEdges(
            prequels = prequels[id].orEmpty().map { byId.getValue(it) },
            sequels = sequels[id].orEmpty().map { byId.getValue(it) },
        )
    }

    private suspend fun Graph.candidates(id: Int): List<Int> =
        sequelSeasonCandidates(walkPrequelChain(id, MAX_PREQUEL_HOPS, ::isSeasonFormat) { edges(it) }).map { it.id }

    @Test
    fun `按播出早的在前，不是前传链的先后`() = runTest {
        // Re:Zero: 第三季 → 第二季后半 → 第二季 → 第一季
        val graph = Graph(
            node(140001, "2016-04-03"), node(278826, "2020-07-08"),
            node(316247, "2021-01-06"), node(425998, "2024-10-02"),
        ).prequel(425998, 316247).prequel(316247, 278826).prequel(278826, 140001)
        assertEquals(listOf(140001, 278826, 316247), graph.candidates(425998))
        assertEquals(emptyList(), graph.candidates(140001))
    }

    @Test
    fun `比自己晚播的前传不算`() = runTest {
        // 苍穹之法芙娜: 2004 年 TV 版的前传是 2005 年才播的特别篇 —— 故事时间线在前, 播出在后
        val graph = Graph(node(1, "2004-07-04", episodes = 26), node(2, "2005-12-29")).prequel(1, 2)
        assertEquals(emptyList(), graph.candidates(1))
    }

    @Test
    fun `剧场版、短篇与 nsfw 不当一季，岔路先走能当一季的`() = runTest {
        val graph = Graph(
            node(10, "2017-07-07"), node(11, "2020-01-17", episodes = 1, format = "剧场版"),
            node(12, "2022-07-06"), node(13, "2019-01-01", episodes = 2, format = "WEB"),
            node(14, "2015-01-01", nsfw = true),
        )
            // 第二季挂着两个前传: 剧场版在前, 第一季在后 —— 走第一季那条
            .prequel(12, 11, 10).prequel(10, 13).prequel(13, 14)
        assertEquals(listOf(10), graph.candidates(12))
    }

    @Test
    fun `没写形态和集数的当不知道，放行`() = runTest {
        val graph = Graph(node(1, "2010-01-01", episodes = null, format = null), node(2, "2012-01-01"))
            .prequel(2, 1)
        assertEquals(listOf(1), graph.candidates(2))
    }

    @Test
    fun `日期缺的排在最后`() = runTest {
        val graph = Graph(node(1, ""), node(2, "2010-01-01"), node(3, "2012-01-01"))
            .prequel(3, 2).prequel(2, 1)
        assertEquals(listOf(2, 1), graph.candidates(3))
    }
}
