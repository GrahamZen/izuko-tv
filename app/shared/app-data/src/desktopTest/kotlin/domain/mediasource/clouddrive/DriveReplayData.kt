/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.him188.ani.datasources.api.source.MediaFetchRequest
import java.io.File

/**
 * 网盘离线录放 ([DriveSeasonReplayTest]) 读的录好的数据, 目录 [dir] 里:
 * - `cases.json`: `[{id, names, sort, ep, episodes: [{sort, ep}]}]`, names 按请求里的顺序 (中文名、原名、别名);
 * - `configs.json`: `{源名: DriveShareSearchConfig}`; `protocol.json`: 网盘协议 ([CloudDriveProtocol]);
 * - `sites.json`: `{请求地址: 回应正文 (失败为 null)}`;
 * - `shares.json`: `{分享 id: {title, unavailable, folders: {文件夹 id: [{fid, name, dir, size, video, token}]}}}`.
 * 数据里还没有的站点请求与分享目录记下来, [writeMissing] 写进 `missing.json`, 录了再跑, 直到没有缺的.
 */
internal class DriveReplayData(val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun read(name: String) = json.parseToJsonElement(File(dir, name).readText(Charsets.UTF_8))

    val cases: List<JsonObject> = read("cases.json").jsonArray.map { it.jsonObject }
    val configs: Map<String, DriveShareSearchConfig> =
        read("configs.json").jsonObject.mapValues { json.decodeFromJsonElement(DriveShareSearchConfig.serializer(), it.value) }
    val links = DriveShareLinks(json.decodeFromJsonElement(CloudDriveProtocol.serializer(), read("protocol.json")))
    private val sites = File(dir, "sites.json").takeIf { it.exists() }?.let { read("sites.json").jsonObject }.orEmpty()
    private val shareData = File(dir, "shares.json").takeIf { it.exists() }?.let { read("shares.json").jsonObject }.orEmpty()

    private val missingSites = linkedSetOf<String>()
    private val missingShares = linkedSetOf<Triple<String, String, String>>()

    val shares: DriveShareBrowser = ReplayShares(shareData, missingShares)

    val fetch: suspend (String) -> ByteArray? = { url ->
        when {
            url !in sites -> {
                missingSites += url
                null
            }

            else -> sites[url]?.jsonPrimitive?.contentOrNull?.encodeToByteArray()
        }
    }

    val missingCount: Pair<Int, Int> get() = missingSites.size to missingShares.size

    fun writeMissing() {
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
    }

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

internal fun DriveShareMatch.replayKey() = "${share.shareId}/${file.fid}#$episode"

/** 「站点标题 / 文件夹… / 文件名 → 第几集 (是不是要的这一集)」. */
internal fun DriveShareMatch.replayDescribe(request: MediaFetchRequest) =
    (listOf(share.siteTitle) + folders + file.fileName).joinToString(" / ") + " → $episode" + if (isEpisodeOf(request)) " (这一集)" else ""
