/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.domain.mediasource.search.AnimeNameIndex
import me.him188.ani.app.domain.mediasource.search.ReplaySubjects
import me.him188.ani.app.domain.mediasource.search.TitleIdentities
import me.him188.ani.app.domain.mediasource.search.TitleIdentity
import java.io.File
import kotlin.test.Test

/**
 * 网盘认季认集的离线录放: 在录好的数据 (格式见 [DriveReplayData]) 上用真的搜索引擎 ([DriveShareSearchEngine])、
 * TMDB 对应表与系列关系图跑每一部的每个源, 把认出的文件全部写进 `season-<标签>.json`. 改规则前后各跑一次, 比两份文件.
 * 查询请求照开播时的样子建 ([ReplaySubjects]: 带系列关系推出的认部名字).
 * 设 `DRIVE_REPLAY_DIR` (录下的数据)、`DRIVE_SERIES_GRAPH` (bgm-anime.tsv 或 bgm-series-graph.tsv)、`DRIVE_TMDB_MAP` (bangumi-tmdb-map 的 bgm-tmdb.tsv)
 * 才跑, `DRIVE_REPLAY_LABEL` 是输出的标签 (默认 `run`), `DRIVE_REPLAY_CASES` 只跑这几部 (逗号分隔的条目 id), `DRIVE_REPLAY_NO_TABLE=1` 时认部当作系列关系图还没读进来. 缺的站点请求与分享目录写进 `missing.json`, 录了再跑.
 */
class DriveSeasonReplayTest {
    @Test
    fun replay() = runBlocking {
        val dir = System.getenv("DRIVE_REPLAY_DIR")?.let(::File) ?: return@runBlocking println("跳过: 设 DRIVE_REPLAY_DIR 才跑")
        val table = System.getenv("DRIVE_SERIES_GRAPH")?.let { SeriesGraphTable.parse(File(it).readBytes()) }
            ?: return@runBlocking println("跳过: 没设 DRIVE_SERIES_GRAPH")
        val tmdbEpisodes = System.getenv("DRIVE_TMDB_MAP")?.let { path ->
            File(path).useLines { lines ->
                lines.filterNot { it.startsWith("#") }
                    .map { it.split('\t') }
                    .mapNotNull { cols -> cols[0].toIntOrNull()?.let { id -> id to cols.getOrNull(5)?.takeIf(String::isNotBlank) } }
                    .mapNotNull { (id, episodes) -> episodes?.let { id to it } }
                    .toMap()
            }
        } ?: return@runBlocking println("跳过: 没设 DRIVE_TMDB_MAP")
        val label = System.getenv("DRIVE_REPLAY_LABEL") ?: "run"
        val only = System.getenv("DRIVE_REPLAY_CASES")?.takeIf { it.isNotBlank() }?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        val data = DriveReplayData(dir)
        val numbering = TmdbEpisodeNumbering.of { tmdbEpisodes[it] }
        val index = AnimeNameIndex.of(table)
        // DRIVE_REPLAY_NO_TABLE=1 时当作表还没读进来, 同 app 里 [TitleIdentities.of] 那时的样子
        val noTable = System.getenv("DRIVE_REPLAY_NO_TABLE") == "1"
        val identities = TitleIdentities { if (noTable) null else TitleIdentity.of(it, table, index) }
        val subjectsScope = CoroutineScope(SupervisorJob())
        val subjects = ReplaySubjects(table, tmdb = null, subjectsScope)

        val result = buildJsonArray {
            for (case in data.cases) {
                if (only != null && case["id"]!!.jsonPrimitive.int !in only) continue
                val request = subjects.prepare(case).request
                for ((site, config) in data.configs) {
                    val matches = DriveShareSearchEngine(
                        config, data.shares, data.links, numbering = numbering, identities = identities, fetch = data.fetch,
                    ).search(request)
                    add(
                        buildJsonObject {
                            put("id", case["id"]!!.jsonPrimitive.int)
                            put("name", case["names"]!!.jsonArray.first().jsonPrimitive.content)
                            put("site", site)
                            put(
                                "matches",
                                buildJsonObject { matches.sortedBy { it.replayKey() }.forEach { put(it.replayKey(), it.replayDescribe(request)) } },
                            )
                        },
                    )
                }
            }
        }
        subjectsScope.cancel()
        File(dir, "season-$label.json").writeText(result.toString(), Charsets.UTF_8)
        data.writeMissing()
        val (sites, shares) = data.missingCount
        println("season replay ($label): ${result.size} 条 (部 × 源), 缺 $sites 个站点请求、$shares 个分享目录")
    }
}
