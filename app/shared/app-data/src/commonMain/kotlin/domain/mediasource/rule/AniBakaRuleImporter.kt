/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.him188.ani.utils.ktor.UrlHelpers
import kotlin.coroutines.cancellation.CancellationException

/**
 * 把 AniBaka 的规则 (`anx-rule/2`) 转换成规则源.
 *
 * AniBaka 规则的三段也是步骤列表, 「当前值」与变量的模型和规则源相同, 大部分步骤一一对应. 少数步骤 (加解密、站点专用的、
 * 处理 HLS 清单的) 没有对应, 用到它们的规则整条不转, 并说明原因. 只影响请求的细节选项 (嗅探的等待时间等) 直接忽略.
 *
 * 请求头里的 `User-Agent` 不转: 规则源的请求与 WebView 共用一套按站点对齐的身份, 自己指定 UA 会让过站点验证得到的 Cookie 失效.
 */
object AniBakaRuleImporter {
    const val RULE_FORMAT_PREFIX = "anx-rule/"
    const val HUB_FORMAT_PREFIX = "anx-rulehub/"

    /** 一条规则的转换结果. */
    sealed interface Converted {
        val name: String

        data class Ok(val arguments: RuleMediaSourceArguments) : Converted {
            override val name: String get() = arguments.name
        }

        data class Unsupported(override val name: String, val reasons: List<String>) : Converted
    }

    /**
     * 一次导入的结果. [failures] 是没能导入的规则及原因 (含下载失败的);
     * [downloadFailed] 为 `true` 表示规则库里有规则文件没下载下来, 这次的结果不完整.
     */
    data class ImportResult(
        val sources: List<RuleMediaSourceArguments>,
        val failures: List<String>,
        val downloadFailed: Boolean = false,
    )

    private val json = Json { isLenient = true }

    /**
     * 导入 [text]: 可以是一条规则、规则数组、规则库索引 (`anx-rulehub/2`), 或者它们的地址.
     * 是地址时用 [download] 下载; 索引里的规则文件按索引地址解析后逐个下载.
     *
     * @return 不是 AniBaka 规则时返回 `null`, 调用方按别的格式处理.
     */
    suspend fun import(text: String, download: suspend (url: String) -> String): ImportResult? {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            if (trimmed.any { it.isWhitespace() }) return null
            return importContent(download(trimmed), trimmed, download)
        }
        return importContent(trimmed, null, download)
    }

    /**
     * 导入已经下载到的内容 [content]. [sourceUrl] 是它的地址 (规则库索引里的规则文件按它解析); 不知道时为 `null`.
     *
     * @return 不是 AniBaka 规则时返回 `null`.
     */
    suspend fun importContent(content: String, sourceUrl: String?, download: suspend (url: String) -> String): ImportResult? {
        val root = parseOrNull(content.trim()) ?: return null
        return importElement(root, sourceUrl, download)
    }

    private suspend fun importElement(
        root: JsonElement,
        sourceUrl: String?,
        download: suspend (url: String) -> String,
    ): ImportResult? = when {
        root is JsonObject && root.formatStartsWith(HUB_FORMAT_PREFIX) -> {
            if (sourceUrl == null) {
                ImportResult(emptyList(), listOf("规则库索引要用它的地址导入 (里面的规则文件按地址找)"))
            } else {
                importHub(root, sourceUrl, download)
            }
        }

        root is JsonObject && root.formatStartsWith(RULE_FORMAT_PREFIX) -> collect(listOf(convert(root)))
        root is JsonArray && root.isNotEmpty() && root.all { it is JsonObject && it.formatStartsWith(RULE_FORMAT_PREFIX) } ->
            collect(root.map { convert(it as JsonObject) })

        else -> null
    }

    private suspend fun importHub(hub: JsonObject, hubUrl: String, download: suspend (url: String) -> String): ImportResult {
        val entries = (hub["entries"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val converted = mutableListOf<Converted>()
        val failures = mutableListOf<String>()
        var downloadFailed = false
        for (entry in entries) {
            val title = entry.string("title") ?: entry.string("key") ?: "?"
            val ref = entry.string("ref") ?: continue
            val url = ruleFileUrl(hubUrl, ref) ?: continue
            try {
                val rule = parseOrNull(download(url)) as? JsonObject
                if (rule == null) {
                    // 多半是镜像或网络给了个错误页, 与下载失败同样对待
                    downloadFailed = true
                    failures += "$title: 下载到的不是规则"
                    continue
                }
                converted += convert(rule)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadFailed = true
                failures += "$title: 下载失败 (${e.message ?: e::class.simpleName})"
            }
        }
        val result = collect(converted)
        return result.copy(failures = failures + result.failures, downloadFailed = downloadFailed)
    }

    /**
     * 规则文件的地址. 相对路径直接替换索引地址的最后一段: 索引经加速镜像下载时地址形如
     * `https://mirror/https://raw.githubusercontent.com/…/index.json`, 按 URL 规范解析会把路径里的 `//` 弄乱.
     */
    internal fun ruleFileUrl(hubUrl: String, ref: String): String? = when {
        ref.startsWith("http://") || ref.startsWith("https://") -> ref
        ref.startsWith("/") -> UrlHelpers.computeAbsoluteUrlOrNull(hubUrl, ref)
        else -> hubUrl.substringBefore('?').substringBefore('#').substringBeforeLast('/') + "/" + ref
    }

    private fun collect(converted: List<Converted>): ImportResult = ImportResult(
        sources = converted.filterIsInstance<Converted.Ok>().map { it.arguments },
        failures = converted.filterIsInstance<Converted.Unsupported>().map { "${it.name}: ${it.reasons.distinct().joinToString("、")}" },
    )

    /** 转换一条规则. */
    fun convert(rule: JsonObject): Converted {
        val name = rule.string("name") ?: rule.string("id") ?: "AniBaka"
        // 有的规则把三段与请求头包在 pipeline 里
        val body = (rule["pipeline"] as? JsonObject) ?: rule
        val context = Context()
        val headers = (body["headers"] as? JsonObject ?: rule["headers"] as? JsonObject)
            .orEmpty().mapNotNull { (key, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (key.equals("User-Agent", ignoreCase = true)) null else key to context.template(text)
            }.toMap()
        val search = context.steps(body["search"], "search")
        val detail = context.steps(body["detail"], "detail")
        val play = context.steps(body["play"], "play")
        if (search.isEmpty() && context.reasons.isEmpty()) context.reasons += "没有搜索段"
        if (context.reasons.isNotEmpty()) return Converted.Unsupported(name, context.reasons)
        return Converted.Ok(
            RuleMediaSourceArguments(
                name = name,
                description = rule.string("description").orEmpty(),
                iconUrl = rule.string("iconUrl").orEmpty(),
                rule = RuleConfig(
                    baseUrl = rule.string("baseUrl").orEmpty().trimEnd('/'),
                    headers = headers,
                    search = search,
                    detail = detail,
                    play = play,
                ),
            ),
        )
    }

    private class Context {
        val reasons = mutableListOf<String>()

        fun unsupported(reason: String): List<RuleStep> {
            reasons += reason
            return emptyList()
        }

        fun steps(element: JsonElement?, stage: String): List<RuleStep> =
            (element as? JsonArray).orEmpty().flatMap { step ->
                val obj = step as? JsonObject ?: return@flatMap unsupported("$stage 里有不是对象的步骤")
                step(obj, stage)
            }

        private fun step(obj: JsonObject, stage: String): List<RuleStep> {
            val op = obj.string("op") ?: return unsupported("$stage 里有没写 op 的步骤")
            val unknown = obj.keys - (KNOWN_KEYS[op] ?: return unsupported("不支持的步骤 $op")) - "op"
            if (unknown.isNotEmpty()) return unsupported("$op 的 ${unknown.sorted().joinToString("/")}")
            return when (op) {
                "fetch" -> listOf(
                    RuleStep.Fetch(
                        url = template(obj.string("url").orEmpty()),
                        method = obj.string("method") ?: "GET",
                        headers = requestHeaders(obj["headers"]),
                        body = when (val body = obj["body"]) {
                            // 对象是表单字段
                            is JsonObject -> formBody(body)
                            else -> template(obj.string("body").orEmpty())
                        },
                        contentType = obj.string("contentType").orEmpty(),
                    ),
                )

                // follow 打开当前值这个地址 (一段开头时就是这一段的输入地址)
                "follow" -> listOf(RuleStep.Fetch(url = "{${RuleEngine.VAR_VALUE}}", headers = requestHeaders(obj["headers"])))
                "sniff" -> when (obj.string("goal")) {
                    "html" -> listOf(RuleStep.Sniff(goal = RuleStep.Sniff.GOAL_HTML, url = template(obj.string("url").orEmpty())))
                    "video", null -> listOf(RuleStep.Sniff(goal = RuleStep.Sniff.GOAL_VIDEO))
                    else -> unsupported("sniff 的 goal ${obj.string("goal")}")
                }

                "searchList" -> listOf(
                    RuleStep.Subjects(
                        list = stringList(obj["selectors"]).ifEmpty { return unsupported("searchList 没有 selectors") },
                        linkPattern = obj.string("detailPattern").orEmpty(),
                    ),
                )

                "episodes" -> listOf(
                    RuleStep.Episodes(
                        lists = stringList(obj["listSelectors"]).ifEmpty { return unsupported("episodes 没有 listSelectors") },
                        tabs = stringList(obj["tabSelectors"]),
                        reverse = obj.boolean("reverse") == true || obj.boolean("reverseEpisodes") == true,
                    ),
                )

                "select" -> listOf(
                    RuleStep.Select(
                        css = obj.string("css") ?: return unsupported("select 没有 css"),
                        attr = obj.string("attr") ?: "text",
                    ),
                )

                "json" -> listOf(RuleStep.Json(path = obj.string("path").orEmpty()))
                "regex" -> listOf(
                    RuleStep.Regex(
                        // AniBaka 的正则里也能嵌变量, 如 `"{playerFrom:raw}"\s*:`
                        pattern = template(obj.string("pattern") ?: return unsupported("regex 没有 pattern")),
                        group = obj.string("group") ?: "1",
                        ignoreCase = obj.boolean("ignoreCase") == true,
                    ),
                )

                "replace" -> listOfNotNull(
                    obj.string("input")?.let { RuleStep.Template(template(it)) },
                    RuleStep.Replace(
                        pattern = template(obj.string("pattern") ?: return unsupported("replace 没有 pattern")),
                        replacement = template(obj.string("replacement").orEmpty()),
                        // AniBaka 的 replace 默认按字面匹配
                        regex = obj.boolean("regex") == true,
                        first = obj.boolean("first") == true,
                    ),
                )

                "template" -> listOf(RuleStep.Template(template(obj.string("value").orEmpty())))
                "setVar" -> listOf(
                    RuleStep.SetVar(
                        name = obj.string("name") ?: return unsupported("setVar 没有 name"),
                        value = template(obj.string("value") ?: "{url:raw}"),
                    ),
                )

                "query" -> listOf(
                    RuleStep.Query(
                        name = obj.string("name") ?: return unsupported("query 没有 name"),
                        input = template(obj.string("input") ?: "{url:raw}"),
                        variable = obj.string("var").orEmpty(),
                    ),
                )

                "first" -> {
                    val branches = (obj["branches"] as? JsonArray).orEmpty().map { steps(it, stage) }
                    listOf(RuleStep.First(branches))
                }

                "playerAaaa" -> listOf(
                    RuleStep.MacCmsPlayer(
                        variable = obj.string("var") ?: "player_aaaa",
                        key = obj.string("key") ?: "url",
                    ),
                )

                "maccmsSuggest" -> listOf(
                    RuleStep.Fetch(
                        url = "/index.php/ajax/suggest?mid=1&wd={keyword}",
                        headers = requestHeaders(obj["headers"]),
                    ),
                    jsonSubjects(
                        listPath = "list",
                        idKey = "id",
                        nameKey = "name",
                        urlKey = obj.string("urlKey"),
                        urlTemplate = obj.string("detailUrlTemplate"),
                    ) ?: return unsupported("maccmsSuggest 没有 detailUrlTemplate"),
                )

                // 校验交给规则源自己的条目名匹配
                "maccmsVerify" -> emptyList()
                "jsonSeries" -> listOf(
                    jsonSubjects(
                        listPath = obj.string("listPath").orEmpty(),
                        idKey = obj.string("idKey") ?: "id",
                        nameKey = obj.string("nameKey") ?: "name",
                        urlKey = obj.string("urlKey"),
                        urlTemplate = obj.string("detailUrlTemplate"),
                    ) ?: return unsupported("jsonSeries 没有 urlKey 或 detailUrlTemplate"),
                )

                "jsonEpisodes" -> {
                    val urlTemplate = obj.string("detailUrlTemplate") ?: obj.string("episodeIdTemplate")
                        ?: return unsupported("jsonEpisodes 没有地址模板")
                    listOf(
                        RuleStep.JsonEpisodes(
                            listPath = obj.string("episodesPath").orEmpty(),
                            idPath = obj.string("idKey") ?: "id",
                            namePath = obj.string("nameKey") ?: obj.string("episodeNameKey") ?: "name",
                            urlTemplate = itemTemplate(urlTemplate),
                            channelName = obj.string("sourceName").orEmpty(),
                        ),
                    )
                }

                "setMediaHeaders" -> listOf(
                    RuleStep.MediaHeaders(
                        headers = (obj["headers"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                            (value as? JsonPrimitive)?.contentOrNull?.let { key to template(it) }
                        }.toMap(),
                        remove = stringList(obj["remove"]),
                    ),
                )

                "videoUrl" -> listOf(RuleStep.VideoUrl)
                else -> unsupported("不支持的步骤 $op")
            }
        }

        private fun jsonSubjects(
            listPath: String,
            idKey: String,
            nameKey: String,
            urlKey: String?,
            urlTemplate: String?,
        ): RuleStep.JsonSubjects? {
            if (urlKey.isNullOrBlank() && urlTemplate.isNullOrBlank()) return null
            return RuleStep.JsonSubjects(
                listPath = listPath,
                idPath = idKey,
                namePath = nameKey,
                urlPath = urlKey.orEmpty(),
                urlTemplate = urlTemplate?.let(::itemTemplate).orEmpty(),
            )
        }

        private fun requestHeaders(element: JsonElement?): Map<String, String> =
            (element as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (key.equals("User-Agent", ignoreCase = true)) null else key to template(text)
            }.toMap()

        /**
         * 步骤里的模板: AniBaka 的 `{url}` 是当前值, `{seriesId}` / `{episodeId}` 是这一段的输入地址.
         * AniBaka 的变量默认做 URL 编码、写 `:raw` 才原样; 规则源相反 (只有 `keyword` 默认编码), 所以逐个写明.
         */
        fun template(text: String): String = VARIABLE.replace(text) { match ->
            val name = match.groupValues[1]
            val raw = match.groupValues[2] == "raw"
            when {
                name == RuleEngine.VAR_KEYWORD -> if (raw) "{keyword:raw}" else "{keyword}"
                else -> {
                    val mapped = VARIABLE_NAMES[name] ?: name
                    if (raw) "{$mapped}" else "{$mapped:url}"
                }
            }
        }

        /** 列表每一项的地址模板: 变量指该项的字段, 名字不换; 编码规则同 [template]. */
        private fun itemTemplate(text: String): String = VARIABLE.replace(text) { match ->
            val name = match.groupValues[1]
            if (match.groupValues[2] == "raw") "{$name}" else "{$name:url}"
        }

        /** 表单字段 (值是模板) 拼成 `a=1&b=2`, 字段值里的变量一律 URL 编码. */
        private fun formBody(fields: JsonObject): String = fields.entries.joinToString("&") { (key, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
            val encoded = buildString {
                var last = 0
                for (match in VARIABLE.findAll(text)) {
                    append(text.substring(last, match.range.first).encodeURLParameter())
                    val name = match.groupValues[1]
                    append("{${VARIABLE_NAMES[name] ?: name}:url}")
                    last = match.range.last + 1
                }
                append(text.substring(last).encodeURLParameter())
            }
            "${key.encodeURLParameter()}=$encoded"
        }
    }

    private fun parseOrNull(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

    private fun JsonElement.formatStartsWith(prefix: String): Boolean =
        ((this as? JsonObject)?.get("format") as? JsonPrimitive)?.contentOrNull?.startsWith(prefix) == true

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.let { primitive ->
        primitive.intOrNull?.toString() ?: primitive.contentOrNull
    }

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun stringList(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull?.takeIf(String::isNotBlank))
        else -> emptyList()
    }

    // 安卓的正则 (ICU) 不认没转义的 `}`
    private val VARIABLE = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)(?::(raw|url))?\}""")

    private val VARIABLE_NAMES = mapOf(
        "url" to RuleEngine.VAR_VALUE,
        "seriesId" to RuleEngine.VAR_SUBJECT_URL,
        "episodeId" to RuleEngine.VAR_EPISODE_URL,
    )

    /**
     * 每种步骤认得的选项. 列在这里但规则源用不上的选项 (嗅探的等待时间、搜索结果的关键词过滤等) 转换时忽略;
     * 没列的选项意味着规则源做不到, 用到它的规则不转.
     */
    private val KNOWN_KEYS: Map<String, Set<String>> = mapOf(
        "fetch" to setOf("url", "method", "headers", "body", "contentType"),
        "follow" to setOf("headers"),
        "sniff" to setOf(
            "goal", "url", "readyContains", "rejectContains", "timeoutMs", "settleMs", "followEmbeddedPlayer",
            "resolveMediaRedirects",
        ),
        "searchList" to setOf("selectors", "detailPattern", "keywordMatch"),
        "episodes" to setOf("listSelectors", "tabSelectors", "reverse", "reverseEpisodes"),
        "select" to setOf("css", "attr"),
        "json" to setOf("path"),
        "regex" to setOf("pattern", "group", "ignoreCase"),
        "replace" to setOf("pattern", "replacement", "regex", "first", "input"),
        // filterHlsAds (去掉 HLS 里插的广告片段) 规则源做不到, 忽略后照样能播, 只是可能带广告
        "template" to setOf("value", "filterHlsAds"),
        "setVar" to setOf("name", "value"),
        "query" to setOf("name", "input", "var"),
        "first" to setOf("branches", "filterHlsAds"),
        "playerAaaa" to setOf("var", "key"),
        "maccmsSuggest" to setOf("detailUrlTemplate", "urlKey", "verify", "headers"),
        "maccmsVerify" to emptySet(),
        "jsonSeries" to setOf("listPath", "idKey", "nameKey", "imageKey", "urlKey", "detailUrlTemplate", "descKey", "keywordMatch"),
        "jsonEpisodes" to setOf("episodesPath", "idKey", "nameKey", "episodeNameKey", "detailUrlTemplate", "episodeIdTemplate", "sourceName"),
        "setMediaHeaders" to setOf("headers", "remove"),
        "videoUrl" to emptySet(),
    )
}
