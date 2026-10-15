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
import kotlinx.serialization.json.longOrNull
import me.him188.ani.datasources.api.source.MediaSourceTier
import me.him188.ani.utils.ktor.UrlHelpers
import kotlin.coroutines.cancellation.CancellationException

/**
 * 把 AniBaka 的规则 (`anx-rule/2`) 转换成规则源.
 *
 * AniBaka 规则的三段也是步骤列表, 「当前值」与变量的模型和规则源相同, 大部分步骤一一对应. 少数步骤 (站点专用的、处理 HLS 清单的)
 * 没有对应, 用到它们的规则整条不转, 并说明原因. 嗅探的选项 (等待时长、页面加载好的标志等) 照录进 [RuleStep.Sniff].
 * 站点专用步骤里能用通用步骤表达的 (anime1 的三步) 翻译过来, 见 [Context.anime1Search]; XPath 换成 CSS 选择器 (见 [XPathToCss]).
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
                converted += convert(rule).withTier(tierOfLabels(entry["labels"]))
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

    /**
     * 规则库索引给每条规则标的广告情况换成层级: 无广告 T0、少广告 T1、有广告 T3, 没标的按默认 ([MediaSourceTier.Fallback]).
     * 单独一条规则文件里没有这些标签. 要 WebView 才拿得到视频的源由画像再降一级 (见 `SourceProfiles`).
     */
    internal fun tierOfLabels(labels: JsonElement?): MediaSourceTier {
        val names = (labels as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return when {
            "有广告" in names -> MediaSourceTier(3u)
            "少广告" in names -> MediaSourceTier(1u)
            "无广告" in names -> MediaSourceTier(0u)
            else -> MediaSourceTier.Fallback
        }
    }

    private fun Converted.withTier(tier: MediaSourceTier): Converted =
        if (this is Converted.Ok) Converted.Ok(arguments.copy(tier = tier)) else this

    private fun collect(converted: List<Converted>): ImportResult = ImportResult(
        sources = converted.filterIsInstance<Converted.Ok>().map { it.arguments },
        failures = converted.filterIsInstance<Converted.Unsupported>().map { "${it.name}: ${it.reasons.distinct().joinToString("、")}" },
    )

    /** 转换一条规则. */
    fun convert(rule: JsonObject): Converted {
        val name = rule.string("name") ?: rule.string("id") ?: "AniBaka"
        // 有的规则把三段与请求头包在 pipeline 里
        val body = (rule["pipeline"] as? JsonObject) ?: rule
        // anime1 的三步互相引用 (搜索段拼条目地址要用详情段的地址模板, 播放段取 token 要用详情段的选择器), 先找出详情段那一步
        val context = Context(
            anime1Detail = topLevelStep(body["detail"], "anime1Detail"),
            detailEntryFetch = (body["detail"] as? JsonArray)?.firstOrNull()
                ?.let { it as? JsonObject }
                ?.takeIf { it.string("op") == "fetch" && it.string("url")?.contains(SERIES_ID) == true },
        )
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
                    matchEpisodeSortFromName = context.matchEpisodeSortFromName ?: RuleConfig().matchEpisodeSortFromName,
                ),
            ),
        )
    }

    private fun topLevelStep(stage: JsonElement?, op: String): JsonObject? =
        (stage as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull { it.string("op") == op }

    private class Context(
        /** 详情段的 `anime1Detail` 步骤 (没有为 `null`). */
        val anime1Detail: JsonObject? = null,
        /**
         * 详情段开头那个用到 `{seriesId}` 的请求 (没有为 `null`). 搜索段的 `jsonSeries` 只给了条目 id 时, 条目地址就是这个请求的地址
         * (见 [subjectUrlFromDetailEntry]).
         */
        val detailEntryFetch: JsonObject? = null,
    ) {
        val reasons = mutableListOf<String>()

        /**
         * 条目地址取的是 [detailEntryFetch] 的地址 (AniBaka 的条目可以只有 id, 由详情段自己拼请求地址; 规则源的条目要有地址):
         * 详情段的那个请求于是直接打开条目地址.
         */
        var subjectUrlFromDetailEntry = false

        /** 翻译出来的步骤要求的集号正则 (见 [RuleConfig.matchEpisodeSortFromName]); `null` 用默认的. */
        var matchEpisodeSortFromName: String? = null

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
                        url = if (obj === detailEntryFetch && subjectUrlFromDetailEntry) "" else template(obj.string("url").orEmpty()),
                        method = obj.string("method") ?: "GET",
                        headers = requestHeaders(obj["headers"]),
                        body = when (val body = obj["body"]) {
                            // 对象: JSON 类型时是 JSON 对象, 否则是表单字段
                            is JsonObject -> if (obj.string("contentType")?.contains("json", ignoreCase = true) == true) jsonBody(body) else formBody(body)
                            else -> template(obj.string("body").orEmpty())
                        },
                        contentType = obj.string("contentType").orEmpty(),
                    ),
                )

                // follow 打开当前值这个地址 (一段开头时就是这一段的输入地址)
                "follow" -> listOf(RuleStep.Fetch(url = "{${RuleEngine.VAR_VALUE}}", headers = requestHeaders(obj["headers"])))
                "sniff" -> {
                    val sniff = RuleStep.Sniff(
                        timeoutMillis = obj.long("timeoutMs") ?: 0,
                        readyContains = stringList(obj["readyContains"]),
                        rejectContains = stringList(obj["rejectContains"]),
                        settleMillis = obj.long("settleMs") ?: 0,
                        resolveMediaRedirects = obj.boolean("resolveMediaRedirects") == true,
                    )
                    when (obj.string("goal")) {
                        "html" -> listOf(sniff.copy(goal = RuleStep.Sniff.GOAL_HTML, url = template(obj.string("url").orEmpty())))
                        "video", null -> listOf(sniff.copy(goal = RuleStep.Sniff.GOAL_VIDEO))
                        else -> unsupported("sniff 的 goal ${obj.string("goal")}")
                    }
                }

                "searchList" -> {
                    // 写了 listXPath 的按 XPath 取: 卡片、卡片里的名字与链接 (没写链接时卡片本身就是链接)
                    val listXPath = obj.string("listXPath")?.takeIf { it.isNotBlank() }
                    listOf(
                        RuleStep.Subjects(
                            list = if (listXPath != null) {
                                listOf(css(listXPath, "searchList 的 listXPath") ?: return emptyList())
                            } else {
                                stringList(obj["selectors"]).ifEmpty { return unsupported("searchList 没有 selectors") }
                            },
                            name = obj.string("nameXPath")?.takeIf { listXPath != null && it.isNotBlank() }
                                ?.let { css(it, "searchList 的 nameXPath") ?: return emptyList() }.orEmpty(),
                            link = obj.string("linkXPath")?.takeIf { listXPath != null && it.isNotBlank() }
                                ?.let { css(it, "searchList 的 linkXPath") ?: return emptyList() }.orEmpty(),
                            linkPattern = obj.string("detailPattern").orEmpty(),
                            filterByKeyword = obj.boolean("keywordMatch") == true,
                        ),
                    )
                }

                "episodes" -> {
                    val roadsXPath = obj.string("roadsXPath")?.takeIf { it.isNotBlank() }
                    listOf(
                        RuleStep.Episodes(
                            lists = if (roadsXPath != null) {
                                listOf(css(roadsXPath, "episodes 的 roadsXPath") ?: return emptyList())
                            } else {
                                stringList(obj["listSelectors"]).ifEmpty { return unsupported("episodes 没有 listSelectors") }
                            },
                            tabs = stringList(obj["tabSelectors"]),
                            items = obj.string("itemsXPath")?.takeIf { roadsXPath != null && it.isNotBlank() }
                                ?.let { css(it, "episodes 的 itemsXPath") ?: return emptyList() } ?: "a",
                            reverse = obj.boolean("reverse") == true || obj.boolean("reverseEpisodes") == true,
                        ),
                    )
                }

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
                "jsonSeries" -> {
                    fun subjects(urlKey: String?, urlTemplate: String?) = jsonSubjects(
                        listPath = obj.string("listPath").orEmpty(),
                        idKey = obj.string("idKey") ?: "id",
                        nameKey = obj.string("nameKey") ?: "name",
                        urlKey = urlKey,
                        urlTemplate = urlTemplate,
                    )
                    val step = subjects(obj.string("urlKey"), obj.string("detailUrlTemplate"))
                        ?: detailEntryFetch?.string("url")
                            ?.let { url -> subjects(null, SERIES_ID.replace(url) { "{id${it.groupValues[1]}}" }) }
                            ?.also { subjectUrlFromDetailEntry = true }
                        ?: return unsupported("jsonSeries 没有 urlKey 或 detailUrlTemplate")
                    listOf(step.copy(filterByKeyword = obj.boolean("keywordMatch") == true))
                }

                "maccmsApiEpisodes" -> listOf(
                    RuleStep.MacCmsEpisodes(
                        listPath = obj.string("listPath") ?: "list",
                        directOnly = obj.boolean("directOnly") == true,
                        preferHls = obj.boolean("preferHls") == true,
                    ),
                )

                "crypto" -> crypto(obj)

                "anime1Search" -> anime1Search(obj)
                "anime1Detail" -> anime1Detail(obj)
                "anime1Play" -> anime1Play(obj)

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

        /**
         * anime1 的搜索: 下载全部番剧的 JSON 表 (每一项是数组, 按 `idIndex` / `nameIndex` 取分类 id 与名字), 按关键词筛出条目.
         * 条目地址用详情段 `anime1Detail` 的地址模板拼 (`{seriesId}` 是分类 id). 分类 id 为 0 的行是指向别站的外链, 去掉.
         */
        fun anime1Search(obj: JsonObject): List<RuleStep> {
            val detailUrl = anime1Detail?.string("url") ?: return unsupported("anime1Search 没有配套的 anime1Detail")
            val idIndex = obj.string("idIndex")?.toIntOrNull() ?: 0
            val nameIndex = obj.string("nameIndex")?.toIntOrNull() ?: 1
            return listOfNotNull(
                RuleStep.Fetch(url = template(obj.string("url") ?: return unsupported("anime1Search 没有 url"))),
                RuleStep.Replace(pattern = ANIME1_ZERO_ID_ROWS, regex = true).takeIf { idIndex == 0 },
                RuleStep.JsonSubjects(
                    idPath = "[$idIndex]",
                    namePath = "[$nameIndex]",
                    urlTemplate = VARIABLE.replace(detailUrl) { match ->
                        val name = if (match.groupValues[1] == "seriesId") "id" else match.groupValues[1]
                        if (match.groupValues[2] == "raw") "{$name}" else "{$name:url}"
                    },
                    filterByKeyword = true,
                ),
            )
        }

        /**
         * anime1 的详情: 分类页 (条目地址) 每页列出若干集, 新的在前, `nextSelector` 翻到更早的页.
         * AniBaka 在分类页上直接取每集视频的 token, 按顺序命名「第 N 集」; 这里改取每集文章页的链接 (含 token 的文章的标题),
         * 名字用文章标题 (「葬送的芙莉蓮 第二季 [38]」, 集号按方括号里的取): 站点缺集时按顺序编号会错位,
         * token 带签发时间, 搜索缓存里存着的旧 token 可能已经失效, 播放时再从文章页取.
         */
        fun anime1Detail(obj: JsonObject): List<RuleStep> {
            val item = obj.string("itemSelector") ?: return unsupported("anime1Detail 没有 itemSelector")
            matchEpisodeSortFromName = ANIME1_EPISODE_SORT
            return listOf(
                RuleStep.Fetch(),
                RuleStep.Episodes(
                    lists = listOf("main", "body"),
                    items = "article:has($item) .entry-title a",
                    reverse = obj.boolean("reverse") == true,
                    nextPage = obj.string("nextSelector").orEmpty(),
                    maxPages = obj.string("maxPages")?.toIntOrNull() ?: 20,
                ),
            )
        }

        /**
         * anime1 的播放: 从文章页取 token (详情段的 `itemSelector` / `tokenAttribute`), 原样作为表单字段 `tokenField` 提交给取地址的接口,
         * 按 `sourcePath` 取视频地址. 接口同时发下载视频要的 Cookie, 交给播放器.
         */
        fun anime1Play(obj: JsonObject): List<RuleStep> {
            val detail = anime1Detail ?: return unsupported("anime1Play 没有配套的 anime1Detail")
            val item = detail.string("itemSelector") ?: return unsupported("anime1Detail 没有 itemSelector")
            val tokenAttribute = detail.string("tokenAttribute") ?: return unsupported("anime1Detail 没有 tokenAttribute")
            val field = obj.string("tokenField") ?: "d"
            return listOf(
                RuleStep.Fetch(),
                RuleStep.Select(css = item, attr = tokenAttribute),
                RuleStep.Fetch(
                    url = template(obj.string("url") ?: return unsupported("anime1Play 没有 url")),
                    method = obj.string("method") ?: "POST",
                    headers = requestHeaders(obj["headers"]),
                    // token 本身已是 URL 编码过的文本, 原样提交
                    body = "${field.encodeURLParameter()}={${RuleEngine.VAR_VALUE}}",
                    contentType = obj.string("contentType") ?: "form",
                    cookies = ANIME1_COOKIES,
                ),
                RuleStep.Json(path = obj.string("sourcePath") ?: return unsupported("anime1Play 没有 sourcePath")),
                RuleStep.MediaHeaders(headers = requestHeaders(obj["mediaHeaders"]) + ("Cookie" to "{$ANIME1_COOKIES}")),
            )
        }

        /** 解密 / 摘要: AniBaka 不写 `mode` 时是解密, 规则里也只用到解密. 输入默认是当前值. */
        fun crypto(obj: JsonObject): List<RuleStep> {
            val input = template(obj.string("input") ?: "{url:raw}")
            return when (val algorithm = obj.string("algo")?.lowercase()) {
                "md5" -> listOf(RuleStep.Crypto(RuleStep.Crypto.MD5, input = input))
                "aes-cbc" -> {
                    val mode = obj.string("mode")?.lowercase()
                    if (mode != null && mode != "decrypt") return unsupported("crypto 的 mode $mode")
                    listOf(
                        RuleStep.Crypto(
                            RuleStep.Crypto.AES_CBC,
                            input = input,
                            key = template(obj.string("key") ?: return unsupported("crypto 没有 key")),
                            iv = template(obj.string("iv") ?: return unsupported("crypto 没有 iv")),
                        ),
                    )
                }

                else -> unsupported("crypto 的 algo $algorithm")
            }
        }

        /** XPath 换成 CSS; 换不了时记下原因 ([what] 是哪一项). */
        private fun css(xpath: String, what: String): String? =
            XPathToCss.convert(xpath) ?: run {
                reasons += "$what 转不成 CSS ($xpath)"
                null
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

        /** JSON 请求体: 字符串字段里的变量按 JSON 字符串转义 (`{x:json}`), 其余照原样写成 JSON. */
        private fun jsonBody(fields: JsonObject): String = fields.entries.joinToString(",", "{", "}") { (key, value) ->
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            val encoded = if (text == null) {
                value.toString()
            } else {
                buildString {
                    append('"')
                    var last = 0
                    for (match in VARIABLE.findAll(text)) {
                        append(jsonEscape(text.substring(last, match.range.first)))
                        val name = match.groupValues[1]
                        append("{${VARIABLE_NAMES[name] ?: name}:json}")
                        last = match.range.last + 1
                    }
                    append(jsonEscape(text.substring(last)))
                    append('"')
                }
            }
            "${JsonPrimitive(key)}:$encoded"
        }

        private fun jsonEscape(text: String): String = JsonPrimitive(text).toString().removeSurrounding("\"")

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

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun stringList(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull?.takeIf(String::isNotBlank))
        else -> emptyList()
    }

    // 安卓的正则 (ICU) 不认没转义的 `}`
    private val VARIABLE = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)(?::(raw|url))?\}""")

    /** 模板里的 `{seriesId}` (带不带 `:raw` / `:url`). */
    private val SERIES_ID = Regex("""\{seriesId(:raw|:url)?\}""")

    private val VARIABLE_NAMES = mapOf(
        "url" to RuleEngine.VAR_VALUE,
        "seriesId" to RuleEngine.VAR_SUBJECT_URL,
        "episodeId" to RuleEngine.VAR_EPISODE_URL,
    )

    /**
     * 每种步骤认得的选项. 列在这里但规则源用不上的选项 (嗅探时是否跟进嵌入的播放器、搜索结果的关键词过滤等) 转换时忽略;
     * 没列的选项意味着规则源做不到, 用到它的规则不转.
     */
    private val KNOWN_KEYS: Map<String, Set<String>> = mapOf(
        "fetch" to setOf("url", "method", "headers", "body", "contentType"),
        "follow" to setOf("headers"),
        "sniff" to setOf(
            "goal", "url", "readyContains", "rejectContains", "timeoutMs", "settleMs", "followEmbeddedPlayer",
            "resolveMediaRedirects",
        ),
        "searchList" to setOf("selectors", "detailPattern", "keywordMatch", "listXPath", "nameXPath", "linkXPath"),
        "episodes" to setOf("listSelectors", "tabSelectors", "reverse", "reverseEpisodes", "roadsXPath", "itemsXPath"),
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
        "crypto" to setOf("algo", "mode", "input", "key", "iv"),
        "maccmsApiEpisodes" to setOf("listPath", "preferHls", "directOnly"),
        "anime1Search" to setOf("url", "idIndex", "nameIndex", "caseSensitive"),
        "anime1Detail" to setOf(
            "url", "itemSelector", "tokenAttribute", "nextSelector", "maxPages", "reverse", "sourceName", "episodeNameTemplate",
        ),
        "anime1Play" to setOf(
            "url", "method", "contentType", "tokenField", "sourcePath", "mediaBaseUrl", "cookieNames", "cookiePrefixes", "headers",
            "mediaHeaders", "validateWithCookies",
        ),
    )

    /** anime1 番剧表里分类 id 为 0 的行 (连同一个逗号; 第一行带后面的逗号, 其余带前面的). */
    private const val ANIME1_ZERO_ID_ROWS =
        """\[0,(?:"(?:[^"\\]|\\.)*"|[^\[\]"])*\],|,\[0,(?:"(?:[^"\\]|\\.)*"|[^\[\]"])*\]"""

    /** anime1 文章标题「名字 [38]」里的集号. */
    private const val ANIME1_EPISODE_SORT = """\[(?<ep>[^\[\]]+)\]\s*$"""

    /** anime1 取地址的接口发的 Cookie 存在这个变量里. */
    private const val ANIME1_COOKIES = "anime1Cookies"
}
