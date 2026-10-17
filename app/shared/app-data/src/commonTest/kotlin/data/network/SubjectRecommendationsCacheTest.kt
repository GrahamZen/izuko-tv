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
import me.him188.ani.app.data.recommendation.RecommendationCatalog
import me.him188.ani.app.data.recommendation.RecommendationCatalogManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubjectRecommendationsCacheTest {
    private val file = RecommendationCatalogManifest.FileEntry("rec/x.tsv", 0, "")
    private val catalog = RecommendationCatalog.build(
        RecommendationCatalogManifest(
            format = 1, generated = "2026-10-17T00:00:00Z", tags = listOf("恋爱"), tagConfidence = 0.15,
            catalog = RecommendationCatalogManifest.Catalog("2026-10-15", 2, 1000),
            static = listOf(file), stats = file, recent = file,
            recentScope = RecommendationCatalogManifest.RecentScope("2026-03-01", 100, "2026-10-17"), recs = listOf(file),
        ),
        staticFiles = listOf("1\t甲\tKou\t2008-04-06\t25\tTV\taa/1.jpg\n2\t乙\tOtsu\t2010-01-01\t12\tTV\tbb/2.jpg"),
        stats = "1\t80\t2000\t10\t1,1,1,1,1\t0*\n2\t75\t1500\t20\t1,1,1,1,1\t0*",
        recent = "",
        recs = listOf("1\t2\n2\t1,99"),
        names = { null },
    )

    private fun item(id: Int) = SubjectRecommendationItem(id, "n$id", null, "img$id")

    @Test
    fun `catalog answers without fetching`() = runTest {
        val cache = SubjectRecommendationsCache(catalog = { catalog }, file = null)
        assertEquals(listOf(2), cache.get(1) { error("should not fetch") }.map { it.subjectId })
    }

    @Test
    fun `fetched results are kept including empty ones`() = runTest {
        val cache = SubjectRecommendationsCache(catalog = { catalog }, file = null)
        var fetches = 0
        // 2 推荐到的 99 不在目录里: 要问一次, 之后用存下的
        repeat(3) { assertEquals(listOf(5), cache.get(2) { fetches++; listOf(item(5)) }.map { it.subjectId }) }
        repeat(2) { assertEquals(emptyList(), cache.get(7) { fetches++; emptyList() }) }
        assertEquals(2, fetches)
    }

    @Test
    fun `failures are not kept`() = runTest {
        val cache = SubjectRecommendationsCache(catalog = { null }, file = null)
        assertFailsWith<IllegalStateException> { cache.get(3) { error("network") } }
        assertEquals(listOf(4), cache.get(3) { listOf(item(4)) }.map { it.subjectId })
    }
}
