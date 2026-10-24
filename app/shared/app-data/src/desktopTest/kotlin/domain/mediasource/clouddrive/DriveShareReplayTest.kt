/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.him188.ani.app.data.network.SeriesGraphTable
import java.io.File
import kotlin.test.Test

/**
 * 网盘补搜 ([DriveSharePartSearch]) 的离线对照: 站点搜索的回应与分享里的目录事先录好 (格式见 [DriveReplayData]), 用真的搜索引擎
 * ([DriveShareSearchEngine]) 在同一份数据上各跑一遍「不补搜」与「补搜」, 比两边认出的文件. 不联网, 几秒跑完, 改了规则重跑就有结果.
 * 设 `DRIVE_REPLAY_DIR` (录下的数据) 与 `DRIVE_SERIES_GRAPH` (系列关系图 bgm-series-graph.tsv) 才跑.
 * 跑完写 `missing.json` (数据里还没有的站点请求与分享目录) 与 `report.json` (每部每个源两边的差别).
 * 不补搜的条目两边走的路一模一样, 不比.
 */
class DriveShareReplayTest {
    @Test
    fun replay() = runBlocking {
        val dir = System.getenv("DRIVE_REPLAY_DIR")?.let(::File) ?: return@runBlocking println("跳过: 设 DRIVE_REPLAY_DIR 与 DRIVE_SERIES_GRAPH 才跑")
        val table = System.getenv("DRIVE_SERIES_GRAPH")?.let { SeriesGraphTable.parse(File(it).readBytes()) }
            ?: return@runBlocking println("跳过: 没设 DRIVE_SERIES_GRAPH")
        val data = DriveReplayData(dir)

        val report = buildJsonArray {
            for (case in data.cases) {
                val request = data.requestOf(case)
                if (OtherWorkGuard.of(request, table).parts == null) continue
                for ((site, config) in data.configs) {
                    suspend fun run(parts: Boolean): List<DriveShareMatch> {
                        val otherWorks = OtherWorks { OtherWorkGuard.of(it, table).let { guard -> if (parts) guard else guard.withoutParts() } }
                        return DriveShareSearchEngine(config, data.shares, data.links, otherWorks = otherWorks, fetch = data.fetch).search(request)
                    }
                    val before = run(parts = false).associateBy { it.replayKey() }
                    val after = run(parts = true).associateBy { it.replayKey() }
                    add(
                        buildJsonObject {
                            put("id", case["id"]!!.jsonPrimitive.int)
                            put("site", site)
                            put("before", before.size)
                            put("after", after.size)
                            put("lost", buildJsonArray { (before.keys - after.keys).forEach { add(JsonPrimitive(before.getValue(it).replayDescribe(request))) } })
                            put("new", buildJsonArray { (after.keys - before.keys).forEach { add(JsonPrimitive(after.getValue(it).replayDescribe(request))) } })
                            // 补搜后要的这一集有哪些 (与真机控制台的候选对照用)
                            put("episode", buildJsonArray { after.values.filter { it.isEpisodeOf(request) }.forEach { add(JsonPrimitive(it.replayDescribe(request))) } })
                        },
                    )
                }
            }
        }
        File(dir, "report.json").writeText(report.toString(), Charsets.UTF_8)
        data.writeMissing()
        val (sites, shares) = data.missingCount
        println("replay: ${report.size} 条 (部 × 源), 缺 $sites 个站点请求、$shares 个分享目录")
    }
}
