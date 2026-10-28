/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.TmdbSubjectMapIndex
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.media.selector.filter.MediaSelectorFilterSortAlgorithm
import me.him188.ani.app.domain.mediasource.rule.RuleMediaSource
import me.him188.ani.app.domain.mediasource.web.InMemoryWebSearchSessionCacheDao
import me.him188.ani.app.domain.mediasource.web.NoopWebSearchSessionCacheDao
import me.him188.ani.app.domain.mediasource.web.PageEvaluator
import me.him188.ani.app.domain.mediasource.web.SelectorMediaSource
import me.him188.ani.app.domain.mediasource.web.captcha.UnsupportedCaptchaBrowserFactory
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceCookieJar
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceIdentityRegistry
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.utils.ktor.asScopedHttpClient
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * 网页类数据源 (网页、规则) 自动搜索的离线录放: 在录好的站点回应上用真的数据源代码跑每一部的每个源, 记下发了哪些请求、
 * 选择器 (同开播, 不看字幕组与分辨率偏好) 对要的这一集留下哪些资源, 写进 `result-<标签>.json`. 改搜索规则前后各跑一次, 比两份文件.
 *
 * 设 `WEB_REPLAY_DIR` (数据目录)、`WEB_REPLAY_GRAPH` (全部动画的表 bgm-anime.tsv 或系列关系图 bgm-series-graph.tsv)、`WEB_REPLAY_TMDB` (bangumi-tmdb-map 的 bgm-tmdb.tsv) 才跑;
 * `WEB_REPLAY_LABEL` 是输出的标签 (默认 `run`), `WEB_REPLAY_RECORD=1` 时没录过的请求现场发出去录下 (站点的请求间隔照常),
 * 不录时没录过的请求失败并写进 `missing.json`. `WEB_REPLAY_CASES` 只跑这几部 (逗号分隔的条目 id); `WEB_REPLAY_HOT=1` 时每部每个源用同一份搜索缓存连查两次, 第二次的结果写进 `result-<标签>-hot.json`, `WEB_REPLAY_NO_TABLE=1` 时认部当作名字表还没读进来. 数据目录里:
 * - `cases.json`: 样例 (格式见 [ReplaySubjects]);
 * - `sources/` 下每个 `.json`: Web 控制台导出的数据源 (`{json: "{factoryId, arguments}"}`);
 * - `responses/`: 录下的站点回应 (见 [WebReplayHttp]).
 * 请求与选择器上下文照开播时的样子建 ([ReplaySubjects]).
 */
class WebSearchReplayTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class Source(val id: String, val name: String, val factoryId: String, val arguments: JsonElement)

    @Test
    fun replay() = runBlocking {
        val dir = System.getenv("WEB_REPLAY_DIR")?.let(::File) ?: return@runBlocking println("跳过: 设 WEB_REPLAY_DIR 才跑")
        val table = System.getenv("WEB_REPLAY_GRAPH")?.let { SeriesGraphTable.parse(File(it).readBytes()) }
            ?: return@runBlocking println("跳过: 没设 WEB_REPLAY_GRAPH")
        val tmdb = System.getenv("WEB_REPLAY_TMDB")?.let { TmdbSubjectMapIndex.parse(File(it).readBytes()) }
            ?: return@runBlocking println("跳过: 没设 WEB_REPLAY_TMDB")
        val label = System.getenv("WEB_REPLAY_LABEL") ?: "run"
        val record = System.getenv("WEB_REPLAY_RECORD") == "1"
        val only = System.getenv("WEB_REPLAY_CASES")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        // 同 app: 网页认部用全部动画的名字表; WEB_REPLAY_NO_TABLE=1 时当作表还没读进来 (建请求照旧用表, 同 app 里系列信息另取)
        val noTable = System.getenv("WEB_REPLAY_NO_TABLE") == "1"
        AnimeNames.source = { table.takeUnless { noTable } }

        val http = WebReplayHttp(File(dir, "responses"), record)
        val sources = File(dir, "sources").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.map { file ->
            val exported = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            val config = json.parseToJsonElement(exported["json"]!!.jsonPrimitive.content).jsonObject
            val arguments = config["arguments"]!!
            Source(
                id = file.nameWithoutExtension,
                name = arguments.jsonObject["name"]?.jsonPrimitive?.content ?: file.nameWithoutExtension,
                factoryId = config["factoryId"]!!.jsonPrimitive.content,
                // 回放时不必替站点限速
                arguments = if (record) arguments else arguments.withoutIntervals(),
            )
        }
        val cases = json.parseToJsonElement(File(dir, "cases.json").readText(Charsets.UTF_8)).jsonArray.map { it.jsonObject }
            .filter { only == null || it["id"]!!.jsonPrimitive.int in only }

        val indexScope = CoroutineScope(SupervisorJob())
        val subjects = ReplaySubjects(table, tmdb, indexScope)
        // 每跑完一部记一行 (源 请求数/留下的资源数), 跑得久时看进度
        val progress = File(dir, "progress-$label.txt").apply { writeText("") }
        val hot = System.getenv("WEB_REPLAY_HOT") == "1"
        val hotRows = mutableListOf<JsonObject>()
        val result = buildJsonArray {
            for (case in cases) {
                val prepared = subjects.prepare(case)
                val rows = sources.map { source ->
                    async {
                        if (!hot) return@async runSource(source, prepared, http, noCache()) to null
                        // 同一份搜索缓存连查两次: 第二次走缓存那条路, 结果应与第一次一样
                        val repository = SelectorMediaSourceEpisodeCacheRepository(InMemoryWebSearchSessionCacheDao(), flowOf(1.hours))
                        runSource(source, prepared, http, repository) to runSource(source, prepared, http, repository)
                    }
                }.awaitAll()
                rows.forEach { (cold, second) ->
                    add(cold)
                    second?.let(hotRows::add)
                }
                progress.appendText(
                    "${prepared.id} ${prepared.name}: " +
                        rows.joinToString { (cold, _) -> cold["source"]!!.jsonPrimitive.content + " " + cold["requests"]!!.jsonArray.size + "/" + cold["included"]!!.jsonArray.size } + "\n",
                    Charsets.UTF_8,
                )
            }
        }
        indexScope.cancel()
        File(dir, "result-$label.json").writeText(result.toString(), Charsets.UTF_8)
        if (hot) File(dir, "result-$label-hot.json").writeText(JsonArray(hotRows).toString(), Charsets.UTF_8)
        File(dir, "missing.json").writeText(buildJsonArray { http.missing.sorted().forEach { add(JsonPrimitive(it)) } }.toString(), Charsets.UTF_8)
        AnimeNames.source = null
        println("web replay ($label): ${result.size} 条 (部 × 源), 缺 ${http.missing.size} 个请求")
    }

    private fun noCache() = SelectorMediaSourceEpisodeCacheRepository(NoopWebSearchSessionCacheDao, flowOf(Duration.ZERO))

    private suspend fun runSource(
        source: Source,
        prepared: ReplaySubjects.Prepared,
        http: WebReplayHttp,
        repository: SelectorMediaSourceEpisodeCacheRepository,
    ): JsonObject {
        val log = mutableListOf<String>()
        val client = HttpClient(http.engine(log)).asScopedHttpClient()
        val scope = CoroutineScope(SupervisorJob())
        val sessionManager = WebSessionManager(
            browserFactory = UnsupportedCaptchaBrowserFactory,
            evaluator = PageEvaluator(),
            cookieJar = WebSourceCookieJar(),
            identityRegistry = WebSourceIdentityRegistry(),
            client = client,
            backgroundScope = scope,
        )
        val config = MediaSourceConfig(serializedArguments = source.arguments)
        val mediaSource: MediaSource = when (source.factoryId) {
            "web-selector" -> SelectorMediaSource(source.id, config, repository, client = client, sessionManager = sessionManager)
            "rule" -> RuleMediaSource(source.id, config, client, repository, sessionManager)
            else -> error("不支持的数据源类型 ${source.factoryId}")
        }
        var error: String? = null
        val media: List<Media> = try {
            withTimeoutOrNull(5.minutes) { mediaSource.fetch(prepared.request).results.toList().map { it.media } }
                ?: emptyList<Media>().also { error = "timeout" }
        } catch (e: Exception) {
            error = e.toString()
            emptyList()
        } finally {
            scope.cancel()
        }
        val context = MediaSelectorContext(
            subjectFinished = true,
            mediaSourcePrecedence = null,
            subtitlePreferences = null,
            subjectSeriesInfo = prepared.selectorSeries,
            subjectInfo = prepared.selectorSubject,
            episodeInfo = prepared.episode,
            mediaSourceTiers = null,
        )
        val filterStart = TimeSource.Monotonic.markNow()
        val filtered = MediaSelectorFilterSortAlgorithm().filterMediaList(media, MediaPreference.Any, MediaSelectorSettings.Default, context)
        val filterColdMs = filterStart.elapsedNow().inWholeMilliseconds
        val warmStart = TimeSource.Monotonic.markNow()
        repeat(3) { MediaSelectorFilterSortAlgorithm().filterMediaList(media, MediaPreference.Any, MediaSelectorSettings.Default, context) }
        val filterWarmMs = warmStart.elapsedNow().inWholeMilliseconds / 3
        fun Media.describe() = "${properties.subjectName} | ${properties.episodeName} | ${episodeRange} | $mediaId"
        return buildJsonObject {
            put("id", prepared.id)
            put("name", prepared.name)
            put("source", source.name)
            put("requests", buildJsonArray { synchronized(log) { log.toList() }.forEach { add(JsonPrimitive(it)) } })
            put("media", media.size)
            put("filterColdMs", filterColdMs)
            put("filterWarmMs", filterWarmMs)
            put("pages", buildJsonArray { media.mapNotNull { it.properties.subjectName }.distinct().forEach { add(JsonPrimitive(it)) } })
            put("included", buildJsonArray { filtered.filterIsInstance<MaybeExcludedMedia.Included>().forEach { add(JsonPrimitive(it.result.describe())) } })
            // 条目名算精确匹配的 (选源面板上不提示「条目名不完全一致」, 自动选源优先)
            put(
                "exact",
                buildJsonArray {
                    filtered.filterIsInstance<MaybeExcludedMedia.Included>()
                        .filter { it.metadata.subjectMatchKind == MatchMetadata.SubjectMatchKind.EXACT }
                        .forEach { add(JsonPrimitive(it.result.describe())) }
                },
            )
            put(
                "excluded",
                buildJsonObject {
                    filtered.filterIsInstance<MaybeExcludedMedia.Excluded>()
                        .groupingBy { it.exclusionReason::class.simpleName.orEmpty() }.eachCount()
                        .forEach { (reason, count) -> put(reason, count) }
                },
            )
            error?.let { put("error", it) }
        }
    }

    /** 配置里的请求间隔 (`requestInterval` / `requestIntervalMillis`) 都改成 0. */
    private fun JsonElement.withoutIntervals(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            mapValues { (key, value) ->
                if (key == "requestInterval" || key == "requestIntervalMillis") JsonPrimitive(0) else value.withoutIntervals()
            },
        )

        is JsonArray -> JsonArray(map { it.withoutIntervals() })
        else -> this
    }
}
