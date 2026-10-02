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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import java.io.File
import kotlin.test.Test

/**
 * 网盘补搜 ([DriveSharePartSearch]) 的离线对照: 站点搜索的回应与分享里的目录事先录好, 用真的搜索引擎 ([DriveShareSearchEngine])
 * 在同一份数据上各跑一遍「不补搜」与「补搜」, 比两边认出的文件. 不联网, 几秒跑完, 改了规则重跑就有结果.
 * 设 `DRIVE_REPLAY_DIR` (录下的数据) 与 `DRIVE_SERIES_GRAPH` (系列关系图 bgm-series-graph.tsv) 才跑. 目录里:
 * - `cases.json`: `[{id, names, sort, ep, episodes: [{sort, ep}]}]`, names 按请求里的顺序 (中文名、原名、别名);
 * - `configs.json`: `{源名: DriveShareSearchConfig}`; `protocol.json`: 网盘协议 ([CloudDriveProtocol]);
 * - `sites.json`: `{请求地址: 回应正文 (失败为 null)}`;
 * - `shares.json`: `{分享 id: {title, unavailable, folders: {文件夹 id: [{fid, name, dir, size, video, token}]}}}`.
 * 跑完写 `missing.json` (数据里还没有的站点请求与分享目录, 录了再跑, 直到没有缺的) 与 `report.json` (每部每个源两边的差别).
 * 不补搜的条目两边走的路一模一样, 不比.
 */
class DriveShareReplayTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun replay() = runBlocking {
        val dir = System.getenv("DRIVE_REPLAY_DIR")?.let(::File) ?: return@runBlocking println("跳过: 设 DRIVE_REPLAY_DIR 与 DRIVE_SERIES_GRAPH 才跑")
        val table = System.getenv("DRIVE_SERIES_GRAPH")?.let { SeriesGraphTable.parse(File(it).readBytes()) }
            ?: return@runBlocking println("跳过: 没设 DRIVE_SERIES_GRAPH")
        fun read(name: String) = json.parseToJsonElement(File(dir, name).readText(Charsets.UTF_8))
        val cases = read("cases.json").jsonArray.map { it.jsonObject }
        val configs = read("configs.json").jsonObject.mapValues { json.decodeFromJsonElement(DriveShareSearchConfig.serializer(), it.value) }
        val links = DriveShareLinks(json.decodeFromJsonElement(CloudDriveProtocol.serializer(), read("protocol.json")))
        val sites = File(dir, "sites.json").takeIf { it.exists() }?.let { read("sites.json").jsonObject }.orEmpty()
        val shareData = File(dir, "shares.json").takeIf { it.exists() }?.let { read("shares.json").jsonObject }.orEmpty()

        val missingSites = linkedSetOf<String>()
        val missingShares = linkedSetOf<Triple<String, String, String>>()
        val shares = ReplayShares(shareData, missingShares)
        val fetch: suspend (String) -> ByteArray? = { url ->
            when {
                url !in sites -> {
                    missingSites += url
                    null
                }

                else -> sites[url]?.jsonPrimitive?.contentOrNull?.encodeToByteArray()
            }
        }

        val report = buildJsonArray {
            for (case in cases) {
                val request = requestOf(case)
                if (OtherWorkGuard.of(request, table).parts == null) continue
                for ((site, config) in configs) {
                    suspend fun run(parts: Boolean): List<DriveShareMatch> {
                        val otherWorks = OtherWorks { OtherWorkGuard.of(it, table).let { guard -> if (parts) guard else guard.withoutParts() } }
                        return DriveShareSearchEngine(config, shares, links, otherWorks = otherWorks, fetch = fetch).search(request)
                    }
                    val before = run(parts = false).associateBy { it.key() }
                    val after = run(parts = true).associateBy { it.key() }
                    add(
                        buildJsonObject {
                            put("id", case["id"]!!.jsonPrimitive.int)
                            put("site", site)
                            put("before", before.size)
                            put("after", after.size)
                            put("lost", buildJsonArray { (before.keys - after.keys).forEach { add(JsonPrimitive(before.getValue(it).describe(request))) } })
                            put("new", buildJsonArray { (after.keys - before.keys).forEach { add(JsonPrimitive(after.getValue(it).describe(request))) } })
                            // 补搜后要的这一集有哪些 (与真机控制台的候选对照用)
                            put("episode", buildJsonArray { after.values.filter { it.isEpisodeOf(request) }.forEach { add(JsonPrimitive(it.describe(request))) } })
                        },
                    )
                }
            }
        }
        File(dir, "report.json").writeText(report.toString(), Charsets.UTF_8)
        File(dir, "missing.json").writeText(
            buildJsonObject {
                put("sites", buildJsonArray { missingSites.forEach { add(JsonPrimitive(it)) } })
                put(
                    "shares",
                    buildJsonArray {
                        missingShares.forEach { (shareId, passcode, folderId) ->
                            add(buildJsonObject { put("shareId", shareId); put("passcode", passcode); put("folderId", folderId) })
                        }
                    },
                )
            }.toString(),
            Charsets.UTF_8,
        )
        println("replay: ${report.size} 条 (部 × 源), 缺 ${missingSites.size} 个站点请求、${missingShares.size} 个分享目录")
    }

    private fun requestOf(case: JsonObject): MediaFetchRequest {
        val names = case["names"]!!.jsonArray.map { it.jsonPrimitive.content }
        fun sort(value: JsonElement?) = EpisodeSort(value!!.jsonPrimitive.content)
        return MediaFetchRequest(
            subjectId = case["id"]!!.jsonPrimitive.content,
            episodeId = "requested",
            subjectNameCN = names.firstOrNull(),
            subjectNames = names,
            episodeSort = sort(case["sort"]),
            episodeEp = sort(case["ep"]),
            episodeName = "",
            episodes = case["episodes"]!!.jsonArray.mapIndexed { index, episode ->
                val o = episode.jsonObject
                MediaFetchRequest.Episode("e$index", sort(o["sort"]), o["ep"]?.jsonPrimitive?.contentOrNull?.let(::EpisodeSort))
            },
        )
    }

    private fun DriveShareMatch.key() = "${share.shareId}/${file.fid}#$episode"

    /** 「站点标题 / 文件夹… / 文件名 → 第几集 (是不是要的这一集)」. */
    private fun DriveShareMatch.describe(request: MediaFetchRequest) =
        (listOf(share.siteTitle) + folders + file.fileName).joinToString(" / ") + " → $episode" + if (isEpisodeOf(request)) " (这一集)" else ""

    /** 录下的分享目录. 没录过的记进 [missing] 当空的. */
    private class ReplayShares(
        private val data: Map<String, JsonElement>,
        private val missing: MutableSet<Triple<String, String, String>>,
    ) : DriveShareBrowser {
        override val rootFolderId: String get() = "0"
        override val needsFileToken: Boolean get() = true

        private fun share(shareId: String): JsonObject? = data[shareId]?.jsonObject?.also {
            if (it["unavailable"]?.jsonPrimitive?.booleanOrNull == true) throw CloudDriveShareUnavailableException("replay", "录下时已失效")
        }

        override suspend fun open(shareId: String, passcode: String): String {
            val share = share(shareId) ?: run {
                missing += Triple(shareId, passcode, rootFolderId)
                return ""
            }
            return share["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }

        override suspend fun listFolder(shareId: String, passcode: String, folderId: String): List<DriveFile> {
            val items = share(shareId)?.get("folders")?.jsonObject?.get(folderId) as? JsonArray ?: run {
                missing += Triple(shareId, passcode, folderId)
                return emptyList()
            }
            return items.map { item ->
                val o = item.jsonObject
                DriveFile(
                    fid = o["fid"]!!.jsonPrimitive.content,
                    fileName = o["name"]!!.jsonPrimitive.content,
                    parentFid = folderId,
                    dir = o["dir"]?.jsonPrimitive?.booleanOrNull == true,
                    size = o["size"]?.jsonPrimitive?.longOrNull ?: 0,
                    isVideo = o["video"]?.jsonPrimitive?.booleanOrNull == true,
                    shareToken = if (o["token"]?.jsonPrimitive?.booleanOrNull == true) "t" else "",
                )
            }
        }
    }
}
