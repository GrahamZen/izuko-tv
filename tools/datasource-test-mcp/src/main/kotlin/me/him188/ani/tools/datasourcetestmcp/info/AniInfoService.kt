/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.tools.datasourcetestmcp.info

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 信息能力: 用名字搜索番剧, 获取番剧的剧集列表, 以及热门趋势.
 *
 * **数据直接来自 bangumi** (v0 的搜索/条目/分集 + p1 的 trending). 原先走的是 Ani 服务器的聚合
 * 接口, 那台服务器连同 `client/` 模块一起去掉了; 输入输出模型保持不变, 调用方不用改。
 *
 * 输入里的 `aniApiBaseUrl` 已被忽略 (地址写死 bangumi), `aniBearerToken` 当作 bangumi 的
 * access token —— 给了就带上, 不给也能用 (这三个接口都允许匿名).
 */
class AniInfoService(
    private val httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun searchSubjects(input: SearchSubjectsInput): SearchSubjectsResult {
        return try {
            val body = httpClient.post(V0 + "/search/subjects") {
                parameter("limit", input.limit)
                parameter("offset", input.offset)
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                bearer(input.aniBearerToken)
                setBody(
                    buildJsonObject {
                        put("keyword", input.query)
                        putJsonObject("filter") {
                            putJsonArray("type") { add(JsonPrimitive(2)) } // 2 = 动画
                        }
                    },
                )
            }.bodyAsText()
            val page = json.decodeFromString(V0SearchPage.serializer(), body)
            val subjects = page.data.map { item ->
                SubjectResult(
                    subjectId = item.id.toLong(),
                    name = item.name,
                    nameCn = item.nameCn,
                    airDate = item.date.orEmpty(),
                    mainEpisodeCount = item.eps,
                    episodes = if (input.includeEpisodes) {
                        runCatching { fetchEpisodes(item.id, input.aniBearerToken) }.getOrNull()
                    } else {
                        null
                    },
                )
            }
            SearchSubjectsResult(
                ok = true,
                summary = "Found ${subjects.size} subject(s) for \"${input.query}\"",
                subjects = subjects,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            SearchSubjectsResult(
                ok = false,
                summary = "Subject search failed",
                errors = listOf("${exception::class.simpleName}: ${exception.message.orEmpty()}"),
            )
        }
    }

    suspend fun getSubjectEpisodes(input: GetSubjectEpisodesInput): GetSubjectEpisodesResult {
        return try {
            val subject = fetchSubject(input.subjectId.toInt(), input.aniBearerToken)
            val episodes = fetchEpisodes(input.subjectId.toInt(), input.aniBearerToken)
            GetSubjectEpisodesResult(
                ok = true,
                summary = "Subject ${subject.nameCn.ifBlank { subject.name }} has ${episodes.size} episode(s)",
                subject = SubjectResult(
                    subjectId = subject.id.toLong(),
                    name = subject.name,
                    nameCn = subject.nameCn,
                    airDate = subject.date.orEmpty(),
                    mainEpisodeCount = episodes.size,
                    episodes = episodes,
                ),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            GetSubjectEpisodesResult(
                ok = false,
                summary = "Failed to fetch subject ${input.subjectId}",
                errors = listOf("${exception::class.simpleName}: ${exception.message.orEmpty()}"),
            )
        }
    }

    suspend fun getTrends(input: GetTrendsInput): GetTrendsResult {
        return try {
            val body = httpClient.get(NEXT + "/p1/trending/subjects") {
                parameter("type", 2) // 动画
                parameter("limit", if (input.limit > 0) input.limit else 20)
                accept(ContentType.Application.Json)
                bearer(input.aniBearerToken)
            }.bodyAsText()
            val page = json.decodeFromString(P1TrendingPage.serializer(), body)
            val trending = page.data.mapIndexed { index, item ->
                TrendingSubjectResult(
                    rank = index + 1,
                    subjectId = item.subject.id.toLong(),
                    nameCn = item.subject.nameCN.ifBlank { item.subject.name },
                    imageUrl = item.subject.images?.large.orEmpty(),
                )
            }
            GetTrendsResult(
                ok = true,
                summary = "Top ${trending.size} trending subject(s)",
                subjects = trending,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            GetTrendsResult(
                ok = false,
                summary = "Failed to fetch trends",
                errors = listOf("${exception::class.simpleName}: ${exception.message.orEmpty()}"),
            )
        }
    }

    /**
     * 获取用于 selector 引擎搜索的查询上下文 (条目名列表 + 剧集序号等).
     */
    suspend fun fetchEpisodeQueryContext(
        subjectId: Long,
        episodeId: Long,
        baseUrl: String,
        bearerToken: String?,
    ): EpisodeQueryContext {
        val subject = fetchSubject(subjectId.toInt(), bearerToken)
        val episode = fetchEpisodes(subjectId.toInt(), bearerToken)
            .firstOrNull { it.episodeId == episodeId }
            ?: error("Episode $episodeId not found in subject $subjectId")
        return EpisodeQueryContext(
            subjectNames = buildList {
                add(subject.nameCn)
                add(subject.name)
                addAll(subject.aliases())
            }.map(String::trim).filter(String::isNotBlank).distinct(),
            subjectDisplayName = subject.nameCn.ifBlank { subject.name },
            episodeSort = episode.sort,
            episodeEp = episode.ep,
            episodeName = episode.nameCn.ifBlank { episode.name }.ifBlank { null },
        )
    }

    private suspend fun fetchSubject(subjectId: Int, bearerToken: String?): V0Subject {
        val body = httpClient.get(V0 + "/subjects/" + subjectId) {
            accept(ContentType.Application.Json)
            bearer(bearerToken)
        }.bodyAsText()
        return json.decodeFromString(V0Subject.serializer(), body)
    }

    private suspend fun fetchEpisodes(subjectId: Int, bearerToken: String?): List<EpisodeResult> {
        val body = httpClient.get(V0 + "/episodes") {
            parameter("subject_id", subjectId)
            parameter("type", 0) // 只要正片
            parameter("limit", 100)
            accept(ContentType.Application.Json)
            bearer(bearerToken)
        }.bodyAsText()
        return json.decodeFromString(V0EpisodePage.serializer(), body).data.map { it.toEpisodeResult() }
    }

    private fun HttpRequestBuilder.bearer(token: String?) {
        token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer " + it) }
    }

    private companion object {
        const val V0 = "https://api.bgm.tv/v0"
        const val NEXT = "https://next.bgm.tv"
    }
}

class EpisodeQueryContext(
    val subjectNames: List<String>,
    val subjectDisplayName: String,
    val episodeSort: String,
    val episodeEp: String?,
    val episodeName: String?,
)

@Serializable
private class V0SearchPage(val data: List<V0Subject> = emptyList())

@Serializable
private class V0Subject(
    val id: Int,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val date: String? = null,
    val eps: Int = 0,
    val infobox: List<V0InfoboxItem>? = null,
) {
    /** 别名在 infobox 的「别名」项里, v0 没有单独字段. */
    fun aliases(): List<String> = infobox.orEmpty()
        .filter { it.key == "别名" }
        .flatMap { item ->
            when (val v = item.value) {
                is JsonPrimitive -> listOfNotNull(v.contentOrNullSafe())
                is JsonArray -> v.mapNotNull { element ->
                    ((element as? JsonObject)?.get("v") as? JsonPrimitive)?.contentOrNullSafe()
                }

                else -> emptyList()
            }
        }
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content.takeIf { it.isNotBlank() } else null

@Serializable
private class V0InfoboxItem(
    val key: String = "",
    val value: JsonElement,
)

@Serializable
private class V0EpisodePage(val data: List<V0Episode> = emptyList())

@Serializable
private class V0Episode(
    val id: Long,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    /** v0 里这两个是**数字** (可能是小数: 补录的 5.5 话). */
    val sort: Double = 0.0,
    val ep: Double? = null,
    val airdate: String = "",
    val type: Int = 0,
) {
    fun toEpisodeResult() = EpisodeResult(
        episodeId = id,
        sort = sort.toSortString(),
        ep = ep?.toSortString(),
        name = name,
        nameCn = nameCn,
        airDate = airdate,
        type = type.toString(),
    )
}

/** 整数别带 `.0`. */
private fun Double.toSortString(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

@Serializable
private class P1TrendingPage(val data: List<P1TrendingItem> = emptyList())

@Serializable
private class P1TrendingItem(val subject: P1Subject)

@Serializable
private class P1Subject(
    val id: Int,
    val name: String = "",
    val nameCN: String = "",
    val images: P1Images? = null,
)

@Serializable
private class P1Images(val large: String = "")
