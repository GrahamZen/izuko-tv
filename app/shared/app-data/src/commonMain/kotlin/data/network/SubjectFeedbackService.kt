/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.foundation.GitHubFileSources
import me.him188.ani.app.domain.foundation.RepoHostedList
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * 详情页「反馈」: 标题 logo 不对 ([reportLogo]) 与对应的 TMDB 作品不对 ([reportEntry]). 报告发到中转 (Cloudflare Worker, 代码在对应表仓库
 * bangumi-tmdb-map 的 `worker/`), 由它在那个仓库开修正请求; 之后核实、开 PR 都在那边, 维护者合并后写进对应表, 所有人一般一天内用上.
 * 电视上没法登录 GitHub、令牌也不能放进包里, 所以经中转.
 *
 * 中转的根地址放在对应表仓库根目录的 `report-endpoints.json` (字段 `endpoints`, 按顺序试, 连不上换下一个; 中转部署时由那边的 worker
 * 工作流写进去): workers.dev 在大陆多半连不上, 换地址、加自定义域名都不用发版.
 *
 * 「对应的作品不对」的备选 ([entryCandidates]) 取自对应表的核对页数据 (`docs/data/s/<id / 2000>.json`, 与核对页上「备选」同一份).
 */
class SubjectFeedbackService(
    listCache: Settings<RepoHostedListCache>,
    private val client: () -> ScopedHttpClient,
    scope: CoroutineScope,
) {
    private val endpoints = RepoHostedList(SPEC, listCache, client, scope, repository = { TMDB_SUBJECT_MAP_REPOSITORY })

    /** 一次报告的结果. */
    sealed interface Result {
        /** 开了修正请求 ([issue] 为编号). */
        data class Created(val issue: Int) : Result

        /** 同样的请求已经开着 ([issue], 维护者还没处理), 没再提交. */
        data class Duplicate(val issue: Int) : Result

        /** 同一处已经有别的修正请求开着 ([issue], 维护者还没处理), 处理完之前不收新的. */
        data class Pending(val issue: Int) : Result

        /** 报告太频繁, 过一会儿再试. */
        data object RateLimited : Result

        /** 网络不通: 中转地址清单拉不到, 或每个中转都连不上 / 超时. 配了代理可能就通了. */
        data object Unreachable : Result

        /** 中转拒收 (报告的内容它不认) 或出错. */
        data object Failed : Result
    }

    /**
     * 报告条目 [subjectId] 的 [language] 标题 logo 应该是 [logo] (null = 这种语言没有合适的, 显示文字); [tmdbRef] = logo 取自的 TMDB 条目
     * (如 `tv/65844`).
     */
    suspend fun reportLogo(subjectId: Int, tmdbRef: String, language: String, logo: TmdbTitleLogo?): Result = report(
        "logo-report",
        buildJsonObject {
            put("bgm_id", subjectId)
            put("tmdb", tmdbRef)
            put("language", language)
            put("logo", logo?.filePath?.let(::JsonPrimitive) ?: JsonNull)
            put("app", currentAniBuildConfig.versionName)
        },
    )

    /**
     * 报告条目 [subjectId] 的 [language] 标题 logo 在列出的候选里没有 (TMDB 网页上有、接口不列的那种, 如 SVG 格式的): 请求里不带图,
     * 维护者审核时到 TMDB 上找到对的那张补进去, 之后照常生成修正. [tmdbRef] 同 [reportLogo].
     */
    suspend fun reportLogoNotListed(subjectId: Int, tmdbRef: String, language: String): Result = report(
        "logo-report",
        buildJsonObject {
            put("bgm_id", subjectId)
            put("tmdb", tmdbRef)
            put("language", language)
            put("logo", JsonNull)
            put("not_listed", true)
            put("app", currentAniBuildConfig.versionName)
        },
    )

    /** 报告条目 [subjectId] 对应的 TMDB 作品应该是 [option] (null = TMDB 上没有对应). */
    suspend fun reportEntry(subjectId: Int, option: SubjectEntryOption?): Result = report(
        "entry-report",
        buildJsonObject {
            put("bgm_id", subjectId)
            put("tmdb", option?.ref?.let(::JsonPrimitive) ?: JsonNull)
            put("backdrop", option?.backdropPath?.let(::JsonPrimitive) ?: JsonNull)
            put("app", currentAniBuildConfig.versionName)
        },
    )

    private suspend fun report(path: String, body: JsonObject): Result {
        // 清单在本对象建好时才开始拉 (第一次打开反馈弹窗): 还是空的就等这一次拉完, 别把「还没拉到」当成「没有地址」
        var bases = endpoints.entries.first()
        if (bases.isEmpty()) {
            endpoints.refreshIfStale()
            bases = endpoints.entries.first()
        }
        // 这次也没拉到: GitHub 与 jsDelivr 都连不上
        if (bases.isEmpty()) return Result.Unreachable
        val text = body.toString()
        var answered = false
        for (base in bases) {
            val url = "$base/$path"
            val reply = try {
                withTimeoutOrNull(REPORT_TIMEOUT) {
                    client().use {
                        val response = post(url) {
                            expectSuccess = false
                            // 中转只收带这个头的请求 (挡随手乱发的, 见 worker/src/index.js)
                            header("X-Izuko-Client", currentAniBuildConfig.versionName)
                            contentType(ContentType.Application.Json)
                            setBody(text)
                        }
                        response.status.value to response.bodyAsText()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.info { "feedback: $url unreachable (${e::class.simpleName})" }
                continue
            }
            if (reply == null) {
                logger.info { "feedback: $url timed out" }
                continue
            }
            answered = true
            val (status, answer) = reply
            when (status) {
                200 -> {
                    val json = runCatching { Json.parseToJsonElement(answer) as? JsonObject }.getOrNull()
                    val issue = json?.get("issue")?.jsonPrimitive?.intOrNull ?: 0
                    logger.info { "feedback $path -> issue $issue" }
                    return when (json?.get("status")?.jsonPrimitive?.content) {
                        "duplicate" -> Result.Duplicate(issue)
                        "pending" -> Result.Pending(issue)
                        else -> Result.Created(issue)
                    }
                }
                429 -> return Result.RateLimited
                in 400..499 -> {
                    logger.warn { "feedback rejected by $url: $status $answer" }
                    return Result.Failed
                }
                else -> logger.info { "feedback: $url answered $status, trying next" }
            }
        }
        return if (answered) Result.Failed else Result.Unreachable
    }

    /**
     * 「对应的作品不对」的选项: 核对页数据里这个条目现在对应的 TMDB 作品与备选. 网络出错返回 null; 核对页里没有这个条目 (还没查过)
     * 返回空的 [SubjectEntryCandidates].
     */
    suspend fun entryCandidates(subjectId: Int): SubjectEntryCandidates? {
        val path = "docs/data/s/${subjectId / REVIEW_SHARD}.json"
        for (url in GitHubFileSources.urls(TMDB_SUBJECT_MAP_REPOSITORY, path)) {
            val text = try {
                withTimeoutOrNull(CANDIDATES_TIMEOUT) {
                    client().use {
                        val response = get(url) { expectSuccess = false }
                        if (!response.status.isSuccess()) null else response.bodyAsText()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.info { "feedback: $url unreachable (${e::class.simpleName})" }
                null
            } ?: continue
            val record = extractTopLevelObject(text, subjectId.toString()) ?: return SubjectEntryCandidates.Empty
            return runCatching { parseSubjectEntryCandidates(Json.parseToJsonElement(record) as JsonObject) }
                .onFailure { logger.warn(it) { "feedback: unreadable review record for $subjectId" } }
                .getOrNull()
        }
        return null
    }

    private companion object {
        private val logger = logger<SubjectFeedbackService>()

        /** 核对页数据每个文件放多少个条目 (对应表仓库 merge.py 的 SHARD). */
        const val REVIEW_SHARD = 2000

        /**
         * 每个中转最多等多久, 到点换下一个. workers.dev 在大陆常常连接一直挂着, 不限时要等到客户端的默认超时 (几十秒到几分钟),
         * 弹窗就一直停在「正在提交」.
         */
        val REPORT_TIMEOUT = 15.seconds

        /** 核对页数据每个入口最多等多久 (一个文件八百多 KB, 比清单给得宽些). */
        val CANDIDATES_TIMEOUT = 20.seconds

        private val SPEC = RepoHostedList.Spec(
            fileName = "report-endpoints.json",
            field = "endpoints",
            // 包里不带: 从没拉到过清单就当作连不上
            bundled = emptyList(),
            normalize = { url -> url.trim().trimEnd('/').takeIf { it.startsWith("https://") && ' ' !in it } },
        )
    }
}

/**
 * 「对应的作品不对」的选项: [current] 现在对应的作品 (null 且 [currentNone] 为 false = 还没查过; [currentNone] = 确认 TMDB 上没有对应),
 * [options] 全部可选的作品 (现在的在前, 其次自动匹配的结果与备选).
 */
data class SubjectEntryCandidates(
    val current: SubjectEntryOption?,
    val currentNone: Boolean,
    val options: List<SubjectEntryOption>,
) {
    companion object {
        val Empty = SubjectEntryCandidates(null, currentNone = false, options = emptyList())
    }
}

/** 一部 TMDB 作品: [ref] 如 `tv/65844`, [backdropPath] 背景图路径 (可能没有). */
data class SubjectEntryOption(
    val ref: String,
    val name: String,
    val original: String? = null,
    val date: String? = null,
    val backdropPath: String? = null,
) {
    /** 背景图的缩略图地址 (w300 档, 选项卡片上用); 没有背景图为 null. */
    val backdropThumbnailUrl: String? get() = backdropPath?.let { TmdbImageEndpoints.CANONICAL_BASE_URL + "/t/p/w300" + it }
}

/** 核对页数据里一个条目的记录 → 选项 (见对应表仓库 merge.py 的条目记录: manual 人工修正, auto 自动结果与 candidates 备选). */
internal fun parseSubjectEntryCandidates(record: JsonObject): SubjectEntryCandidates {
    fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val manual = record["manual"] as? JsonObject
    val auto = record["auto"] as? JsonObject
    val autoOption = auto?.takeIf { it["status"].str() == "hit" }?.let { a ->
        val tmdb = a["tmdb"] as? JsonObject
        val ref = a["backdrop"].str() ?: a["stillsSource"].str()?.substringBefore("/season/") ?: return@let null
        SubjectEntryOption(
            ref = ref,
            name = tmdb?.get("name").str() ?: ref,
            original = tmdb?.get("original").str(),
            date = tmdb?.get("date").str(),
            backdropPath = a["backdropPath"].str() ?: tmdb?.get("backdrop").str(),
        )
    }
    val manualNone = (manual?.get("none") as? JsonPrimitive)?.booleanOrNull == true
    val manualOption = manual?.takeIf { !manualNone }?.let { m ->
        val ref = m["backdrop"].str() ?: (m["stills"] as? JsonArray)?.firstOrNull().str()?.substringBefore("/season/")
        ref?.let { SubjectEntryOption(it, m["title"].str() ?: it, backdropPath = m["backdrop_path"].str()) }
    }
    val candidates = (auto?.get("candidates") as? JsonArray).orEmpty().mapNotNull { c ->
        val obj = c as? JsonObject ?: return@mapNotNull null
        val ref = obj["ref"].str() ?: return@mapNotNull null
        SubjectEntryOption(ref, obj["name"].str() ?: ref, date = obj["date"].str(), backdropPath = obj["backdrop"].str())
    }
    val current = if (manual != null) manualOption else autoOption
    val options = (listOfNotNull(current, autoOption) + candidates).distinctBy { it.ref }
    return SubjectEntryCandidates(current, currentNone = manualNone || (manual == null && auto?.get("status").str() == "miss"), options)
}

/**
 * 从 `{"<键>":{...},"<键>":{...}}` 这样的大 JSON 里只截出顶层键 [key] 的那个对象的原文, 不整份解析 (核对页数据一个文件八百多 KB).
 * 只认顶层的键 (前面是 `{` 或 `,`), 跳过字符串里的花括号; 没有返回 null.
 */
internal fun extractTopLevelObject(text: String, key: String): String? {
    val marker = "\"$key\":"
    var from = 0
    while (true) {
        val i = text.indexOf(marker, from)
        if (i < 0) return null
        val start = i + marker.length
        val previous = text.getOrNull(i - 1)
        if ((previous == '{' || previous == ',') && text.getOrNull(start) == '{') {
            var depth = 0
            var inString = false
            var escaped = false
            for (j in start until text.length) {
                val c = text[j]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, j + 1)
                    }
                }
            }
            return null
        }
        from = i + 1
    }
}
