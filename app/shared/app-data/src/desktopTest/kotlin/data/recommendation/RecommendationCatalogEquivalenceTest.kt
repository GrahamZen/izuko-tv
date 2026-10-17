/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.recommendation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.SubjectSearchFilters
import me.him188.ani.app.domain.search.SearchSort
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 推荐目录答的查询与 bangumi 现场搜索逐路对照 (推荐重算实际会发的那几种: 排行榜按标签 / 分数线、种子补齐、高分经典、本季、近半年热门、
 * 没收藏时的两条榜). 要联网, 设 `BGM_REC_DIR` (生成出来的 rec 目录, 含 manifest.json) 才跑; 设了 `BGM_SERIES_GRAPH` 时用它补名字.
 */
class RecommendationCatalogEquivalenceTest {
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `catalog answers match live bangumi search`() {
        val dir = System.getenv("BGM_REC_DIR")?.let(::File) ?: return println("跳过: 设 BGM_REC_DIR 才跑")
        val manifest = json.decodeFromString(RecommendationCatalogManifest.serializer(), File(dir, "manifest.json").readText())
        fun text(entry: RecommendationCatalogManifest.FileEntry) = File(dir, entry.path.substringAfterLast('/')).readText()
        val graph = System.getenv("BGM_SERIES_GRAPH")?.let { SeriesGraphTable.parse(File(it).readBytes()) }
        val catalog = RecommendationCatalog.build(
            manifest, manifest.static.map(::text), text(manifest.stats), text(manifest.recent), manifest.recs.map(::text),
            names = { id -> graph?.nodeOf(id)?.let { it.name to it.nameCn } },
        )
        val today = LocalDate.now(ZoneOffset.ofHours(8))
        val seasonStart = today.withMonth((today.monthValue - 1) / 3 * 3 + 1).withDayOfMonth(1).toString()
        val recentHotSince = today.minusMonths(6).withDayOfMonth(1).toString()
        val feedCutoff = today.minusDays(180).toString()
        val ranked = SubjectSearchFilters(ranks = listOf(">=1"), ratingCounts = listOf(">=1000"), nsfw = false)
        val queries = listOf(
            "口味高分 后宫 >=7.5" to Q(ranked.copy(tags = listOf("后宫"), ratings = listOf(">=7.5"))),
            "口味高分 战斗 >=7.5" to Q(ranked.copy(tags = listOf("战斗"), ratings = listOf(">=7.5"))),
            "口味高分 恋爱 >=7.5" to Q(ranked.copy(tags = listOf("恋爱"), ratings = listOf(">=7.5"))),
            "放宽 恋爱 7~7.5" to Q(ranked.copy(tags = listOf("恋爱"), ratings = listOf(">=7", "<7.5"))),
            "种子补齐 漫画改+喜剧+日常" to Q(ranked.copy(tags = listOf("漫画改", "喜剧", "日常"))),
            "种子补齐 异世界+奇幻+战斗" to Q(ranked.copy(tags = listOf("异世界", "奇幻", "战斗"))),
            "高分经典 >=8" to Q(ranked.copy(ratings = listOf(">=8"))),
            "换换口味 治愈 >=7" to Q(ranked.copy(tags = listOf("治愈"), ratings = listOf(">=7"))),
            "本季 TV" to Q(SubjectSearchFilters(airDates = listOf(">=$seasonStart"), metaTags = listOf("TV"), nsfw = false), SearchSort.COLLECTION, 40),
            "本季 WEB" to Q(SubjectSearchFilters(airDates = listOf(">=$seasonStart"), metaTags = listOf("WEB"), nsfw = false), SearchSort.COLLECTION, 20),
            "近半年热门" to Q(SubjectSearchFilters(airDates = listOf(">=$recentHotSince"), nsfw = false), SearchSort.COLLECTION, 60),
            "新番榜" to Q(SubjectSearchFilters(airDates = listOf(">=$feedCutoff"), nsfw = false), SearchSort.COLLECTION, 140),
            "老番榜" to Q(SubjectSearchFilters(ranks = listOf(">=1"), airDates = listOf("<$feedCutoff"), nsfw = false), SearchSort.COLLECTION, 140),
        )
        val problems = mutableListOf<String>()
        for ((label, q) in queries) {
            val local = localIds(catalog, q, today.toString())
            if (local == null) {
                problems += "$label: 目录不答"
                continue
            }
            // 按热度、首播在近期分片起点之后的查询, 目录只收到收藏总数 minCollection 为止: 更低的推荐那边本来也不要 (本季与大家最近在看的
            // 观众规模地板 600 人, 新番榜的前 140 名都在线以上), 现场结果里低于这条线的不比
            val floor = if (q.sort == SearchSort.COLLECTION && q.filters.ranks == null) manifest.recentScope.minCollection else 0
            val live = liveIds(q).filter { it.second >= floor }.map { it.first }
            val head = live.take(20).count { it in local.take(20) }
            val recall = if (live.isEmpty()) 1.0 else live.count { it in local }.toDouble() / live.size
            println("$label: 现场 ${live.size} / 目录 ${local.size}, 前 20 重合 $head, 现场的找回 ${"%.1f".format(recall * 100)}%")
            // 热度序 bangumi 用的是搜索索引里的收藏数, 相邻两部偶尔对调, 前 20 允许多差一个
            val minHead = if (q.sort == SearchSort.COLLECTION) 17 else 18
            if (head < minOf(minHead, live.size) || recall < 0.95) problems += "$label: 前 20 重合 $head, 找回 $recall"
        }
        assertEquals(emptyList(), problems)
    }

    private class Q(val filters: SubjectSearchFilters, val sort: SearchSort = SearchSort.RANK, val depth: Int = 1000)

    private fun localIds(catalog: RecommendationCatalog, q: Q, today: String): List<Int>? {
        val out = ArrayList<Int>()
        var offset = 0
        while (offset < q.depth) {
            val page = catalog.search("", q.filters, q.sort, offset, 20, today) ?: return null
            out += page.items.map { it.subjectInfo.subjectId }
            offset += 20
            if (page.items.size < 20 || offset >= page.total) break
        }
        return out.take(q.depth)
    }

    /** 现场搜索的结果: (条目 id, 收藏总数). */
    private fun liveIds(q: Q): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var offset = 0
        while (offset < q.depth) {
            val body = buildJsonObject {
                put("keyword", "")
                put("sort", if (q.sort == SearchSort.RANK) "rank" else "heat")
                putJsonObject("filter") {
                    putJsonArray("type") { add(2) }
                    put("nsfw", false)
                    q.filters.tags?.let { tags -> putJsonArray("tag") { tags.forEach { add(it) } } }
                    q.filters.metaTags?.let { tags -> putJsonArray("meta_tags") { tags.forEach { add(it) } } }
                    q.filters.ratings?.let { r -> putJsonArray("rating") { r.forEach { add(it) } } }
                    q.filters.ranks?.let { r -> putJsonArray("rank") { r.forEach { add(it) } } }
                    q.filters.ratingCounts?.let { r -> putJsonArray("rating_count") { r.forEach { add(it) } } }
                    q.filters.airDates?.let { r -> putJsonArray("air_date") { r.forEach { add(it) } } }
                }
            }
            val request = HttpRequest.newBuilder(URI("https://api.bgm.tv/v0/search/subjects?limit=20&offset=$offset"))
                .header("Content-Type", "application/json")
                .header("User-Agent", "GrahamZen/izuko-tv (recommendation catalog equivalence test)")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            val response = json.parseToJsonElement(http.send(request, HttpResponse.BodyHandlers.ofString()).body()) as JsonObject
            val data = response["data"]?.jsonArray.orEmpty()
            out += data.map { item ->
                val subject = item.jsonObject
                val collection = subject["collection"]?.jsonObject
                val total = listOf("wish", "collect", "doing", "on_hold", "dropped").sumOf { collection?.get(it)?.jsonPrimitive?.int ?: 0 }
                subject["id"]!!.jsonPrimitive.int to total
            }
            offset += data.size
            Thread.sleep(300)
            if (data.isEmpty() || offset >= (response["total"]?.jsonPrimitive?.int ?: 0)) break
        }
        return out.take(q.depth)
    }
}
