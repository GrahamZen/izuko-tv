/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 开播时各数据源同时认部: 判页名之前先等 ([SubjectSearchPlan.awaitIdentity]), 整张名字表只扫一遍, 之后的 [SubjectSearchPlan.identify] 直接用算好的.
 */
class SubjectSearchPlanAwaitIdentityTest {
    private val table = SeriesGraphTable.parse(
        "# bangumi-anime v1 max_id=900 dump=test\n822\tフリクリ\tFLCL\t2000-04-26\t6\tOVA\t\t\t\t\t\t".encodeToByteArray(),
    )

    private val request = MediaFetchRequest(
        subjectId = "822",
        episodeId = "1",
        subjectNameCN = "FLCL",
        subjectNames = listOf("FLCL"),
        episodeSort = EpisodeSort(1),
        episodeName = "",
        episodeEp = EpisodeSort(1),
    )

    @Test
    fun `sources identifying at once share one identity`() = runTest {
        AnimeNames.source = { table }
        try {
            val identities = withContext(Dispatchers.Default) {
                List(16) { async { AnimeNames.awaitIdentityOf(request) } }.awaitAll()
            }

            identities.forEach { assertSame(identities.first(), it) }
        } finally {
            AnimeNames.source = null
        }
    }

    @Test
    fun `identify after awaiting uses the identity already built`() = runTest {
        AnimeNames.source = { table }
        try {
            val plan = SubjectSearchPlan.of(request)
            plan.awaitIdentity()

            assertSame(AnimeNames.awaitIdentityOf(request), AnimeNames.identityOf(request))
            assertEquals(SubjectMatch.OWN, plan.identify("FLCL"))
        } finally {
            AnimeNames.source = null
        }
    }
}
