/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.him188.ani.app.domain.mediasource.directapi.JsonNode
import me.him188.ani.app.domain.mediasource.directapi.applyTransforms
import me.him188.ani.app.domain.mediasource.directapi.selectByPath
import me.him188.ani.app.domain.mediasource.directapi.stringByPath
import me.him188.ani.app.domain.mediasource.web.BlockedException
import me.him188.ani.datasources.api.util.namedGroup
import me.him188.ani.utils.xml.Element
import me.him188.ani.utils.xml.Html
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 规则源发请求的方式. 4xx / 5xx 也作为响应返回 (由引擎判为失败), 网络错误抛异常.
 */
fun interface RuleHttp {
    suspend fun request(request: RuleHttpRequest): RuleHttpResponse
}

data class RuleHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    /** 为 `null` 表示没有请求体. */
    val body: String?,
    val contentType: String?,
)

data class RuleHttpResponse(
    /** 跟随重定向之后的地址. */
    val finalUrl: String,
    val status: Int,
    val text: String,
)

/** 站点上的一个条目. */
data class RuleSubject(val name: String, val url: String)

/** 站点上的一集. */
data class RuleEpisode(val name: String, val url: String)

/** 一条线路. 站点没有线路名时 [name] 为 `null`. */
data class RuleChannel(val name: String?, val episodes: List<RuleEpisode>)

/** 播放段的结果. */
sealed interface RulePlayResult {
    /** 取到了视频地址. */
    data class Direct(val url: String, val headers: Map<String, String>) : RulePlayResult

    /** 规则要求交给 WebView 嗅探. */
    data object Sniff : RulePlayResult

    /** 没取到. */
    data object Failed : RulePlayResult
}

/**
 * 执行到某一步时的状态. 每一步产出新的状态, [RuleStep.First] 的分支失败时丢弃它的状态.
 */
internal data class RuleState(
    val value: String,
    val variables: Map<String, String>,
    /** 这一段的输入地址 (条目页 / 剧集页). 搜索段为 `null`. */
    val inputUrl: String?,
    /** 解析相对地址的基准. */
    val pageUrl: String,
    val mediaHeaders: Map<String, String> = emptyMap(),
    val removedMediaHeaders: Set<String> = emptySet(),
    val subjects: List<RuleSubject>? = null,
    val channels: List<RuleChannel>? = null,
    val sniffVideo: Boolean = false,
)

/**
 * 每一步的执行记录, 用于日志与调试. [ok] 为 `false` 时 [detail] 是失败原因.
 */
fun interface RuleTraceListener {
    fun onStep(label: String, ok: Boolean, detail: String)
}

/**
 * 按 [RuleConfig] 执行搜索、详情、播放三段.
 */
class RuleEngine(
    private val config: RuleConfig,
    private val http: RuleHttp,
    private val trace: RuleTraceListener? = null,
) {
    /** 按关键词搜索, 返回站点上的条目. 搜不到返回空列表; 请求出错且没有分支成功时抛出那个错误. */
    suspend fun search(keyword: String): List<RuleSubject> {
        if (config.search.isEmpty()) return emptyList()
        val state = runStage(
            "search",
            config.search,
            RuleState(
                value = keyword,
                variables = mapOf(VAR_KEYWORD to keyword),
                inputUrl = null,
                pageUrl = config.baseUrl,
            ),
        ) ?: return emptyList()
        return state.subjects.orEmpty()
    }

    /** 打开条目页, 返回线路与剧集. */
    suspend fun detail(subjectUrl: String): List<RuleChannel> {
        if (config.detail.isEmpty()) return emptyList()
        val state = runStage(
            "detail",
            config.detail,
            RuleState(
                value = subjectUrl,
                variables = mapOf(VAR_SUBJECT_URL to subjectUrl),
                inputUrl = subjectUrl,
                pageUrl = subjectUrl,
            ),
        ) ?: return emptyList()
        return state.channels.orEmpty()
    }

    /** 从剧集页取视频地址. */
    suspend fun play(episodeUrl: String): RulePlayResult {
        val state = runStage(
            "play",
            config.play,
            RuleState(
                value = episodeUrl,
                variables = mapOf(VAR_EPISODE_URL to episodeUrl),
                inputUrl = episodeUrl,
                pageUrl = episodeUrl,
            ),
        ) ?: return RulePlayResult.Failed
        if (state.sniffVideo) return RulePlayResult.Sniff
        val value = state.value.trim()
        // 地址里的空格是允许的 (站点给的直链常带空格, 规整时会编码), 换行与 `<` 说明还是页面内容
        if (value.isEmpty() || value.length > MAX_URL_LENGTH || value.any { it == '\n' || it == '\r' || it == '<' }) {
            trace?.onStep("play", false, "最后的值不是地址: ${RuleText.preview(value)}")
            return RulePlayResult.Failed
        }
        val url = RuleText.resolveUrl(state.pageUrl, value) ?: run {
            trace?.onStep("play", false, "最后的值不是地址: ${RuleText.preview(value)}")
            return RulePlayResult.Failed
        }
        return RulePlayResult.Direct(RuleText.normalizeUrl(url), mediaHeaders(state))
    }

    /**
     * 播放时的请求头: 规则的 [RuleConfig.headers] (视频地址常常要求与页面相同的 Referer), 缺的再用 [RuleConfig.matchVideo] 的补上,
     * 最后是播放段里 [RuleStep.MediaHeaders] 写的.
     */
    private fun mediaHeaders(state: RuleState): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        fun putIfAbsent(name: String, value: String) {
            if (value.isNotBlank() && headers.keys.none { it.equals(name, ignoreCase = true) }) headers[name] = value
        }
        for ((name, value) in config.headers) putIfAbsent(name, substitute(value, state))
        val defaults = config.matchVideo.addHeadersToVideo
        putIfAbsent("User-Agent", defaults.userAgent)
        putIfAbsent("Referer", defaults.referer)
        for ((name, value) in state.mediaHeaders) {
            headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { headers.remove(it) }
            headers[name] = value
        }
        for (removed in state.removedMediaHeaders) {
            headers.keys.filter { it.equals(removed, ignoreCase = true) }.forEach { headers.remove(it) }
        }
        return headers
    }

    /** 记录这一段里最后一个异常: 所有分支都失败时把它抛出去, 让调用方知道是请求出错而不是没结果. */
    private class StageErrors {
        var last: Exception? = null
    }

    private suspend fun runStage(name: String, steps: List<RuleStep>, initial: RuleState): RuleState? {
        val errors = StageErrors()
        val result = runSteps(steps, initial, name, errors)
        if (result == null) errors.last?.let { throw it }
        return result
    }

    private suspend fun runSteps(
        steps: List<RuleStep>,
        initial: RuleState,
        path: String,
        errors: StageErrors,
    ): RuleState? {
        var state = initial
        for ((index, step) in steps.withIndex()) {
            val label = "$path[$index] ${opName(step)}"
            val next = try {
                runStep(step, state, label, errors)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BlockedException) {
                // 被站点验证挡住要让调用方知道 (界面上提示去验证), 不当作这一步没取到
                throw e
            } catch (e: Exception) {
                errors.last = e
                trace?.onStep(label, false, e.toString())
                null
            } ?: return null
            state = next
            // 要求嗅探之后结果就是「交给 WebView」, 后面的步骤 (包括外层的) 都不再执行
            if (state.sniffVideo) return state
        }
        return state
    }

    private fun fail(label: String, reason: String): RuleState? {
        trace?.onStep(label, false, reason)
        return null
    }

    private fun ok(label: String, state: RuleState, detail: String = RuleText.preview(state.value)): RuleState {
        trace?.onStep(label, true, detail)
        return state
    }

    private fun variablesOf(state: RuleState): Map<String, String> =
        buildMap {
            put(VAR_BASE_URL, config.baseUrl)
            put(VAR_PAGE_URL, state.pageUrl)
            putAll(state.variables)
            put(VAR_VALUE, state.value)
        }

    private fun substitute(template: String, state: RuleState): String =
        RuleText.substitute(template, variablesOf(state))

    private suspend fun runStep(step: RuleStep, state: RuleState, label: String, errors: StageErrors): RuleState? =
        when (step) {
            is RuleStep.Fetch -> fetch(state, label, step.url, step.method, step.headers, step.body, step.contentType)
            is RuleStep.Sniff -> when (step.goal) {
                RuleStep.Sniff.GOAL_HTML -> fetch(state, label, step.url, "GET", emptyMap(), "", "")
                else -> ok(label, state.copy(sniffVideo = true), "交给 WebView 嗅探")
            }

            is RuleStep.Select -> select(state, label, step)
            is RuleStep.Regex -> {
                val regex = RuleText.regex(substitute(step.pattern, state), step.ignoreCase)
                val match = regex.find(state.value) ?: return fail(label, "没有匹配")
                // 匹配到的空串是合法的值 (例如页面里的 `"key":""`), 分组没参与匹配才算失败
                val value = groupValue(match, regex, step.group) ?: return fail(label, "没有分组 ${step.group}")
                ok(label, state.copy(value = value))
            }

            is RuleStep.Replace -> {
                val replacement = substitute(step.replacement, state)
                val value = if (step.regex) {
                    val regex = RuleText.regex(substitute(step.pattern, state))
                    if (step.first) regex.replaceFirst(state.value, replacement) else regex.replace(state.value, replacement)
                } else {
                    if (step.first) {
                        state.value.replaceFirst(step.pattern, replacement)
                    } else {
                        state.value.replace(step.pattern, replacement)
                    }
                }
                ok(label, state.copy(value = value))
            }

            is RuleStep.Json -> {
                val node = JsonNode(JSON.parseToJsonElement(state.value)).selectByPath(step.path).firstOrNull()
                val value = (node as? JsonNode)?.element?.let(::jsonText)
                if (value.isNullOrEmpty()) fail(label, "路径 ${step.path} 没有值") else ok(label, state.copy(value = value))
            }

            is RuleStep.Template -> ok(label, state.copy(value = substitute(step.value, state)))
            is RuleStep.SetVar -> {
                val value = substitute(step.value, state)
                ok(label, state.copy(variables = state.variables + (step.name to value)), "${step.name} = ${RuleText.preview(value)}")
            }

            is RuleStep.Query -> {
                val input = substitute(step.input, state)
                val value = queryParameter(input, step.name) ?: return fail(label, "地址里没有参数 ${step.name}")
                if (step.variable.isNotBlank()) {
                    ok(label, state.copy(variables = state.variables + (step.variable to value)), "${step.variable} = ${RuleText.preview(value)}")
                } else {
                    ok(label, state.copy(value = value))
                }
            }

            is RuleStep.Transforms -> {
                val value = applyTransforms(state.value, step.transforms) ?: return fail(label, "变换失败")
                ok(label, state.copy(value = value))
            }

            is RuleStep.First -> {
                for ((index, branch) in step.branches.withIndex()) {
                    runSteps(branch, state, "$label/$index", errors)?.let { return ok(label, it, "分支 $index") }
                }
                fail(label, "所有分支都失败")
            }

            is RuleStep.MacCmsPlayer -> macCmsPlayer(state, label, step)
            RuleStep.VideoUrl -> {
                val value = state.value.trim()
                if (value.startsWith("http") && value.none { it.isWhitespace() || it == '<' || it == '"' }) {
                    ok(label, state)
                } else {
                    val found = VIDEO_URL.find(value.replace("\\/", "/"))?.value ?: return fail(label, "没有找到视频地址")
                    ok(label, state.copy(value = found))
                }
            }

            is RuleStep.MediaHeaders -> ok(
                label,
                state.copy(
                    mediaHeaders = state.mediaHeaders + step.headers.mapValues { substitute(it.value, state) },
                    removedMediaHeaders = state.removedMediaHeaders + step.remove,
                ),
                "headers",
            )

            is RuleStep.Subjects -> subjects(state, label, step)
            is RuleStep.JsonSubjects -> jsonSubjects(state, label, step)
            is RuleStep.Episodes -> episodes(state, label, step)
            is RuleStep.JsonEpisodes -> jsonEpisodes(state, label, step)
            is RuleStep.RegexEpisodes -> regexEpisodes(state, label, step)
        }

    // region 请求

    private suspend fun fetch(
        state: RuleState,
        label: String,
        url: String,
        method: String,
        stepHeaders: Map<String, String>,
        body: String,
        contentType: String,
    ): RuleState? {
        val raw = if (url.isBlank()) state.inputUrl ?: return fail(label, "这一段没有输入地址, 要写 url") else substitute(url, state)
        val base = state.pageUrl.ifBlank { config.baseUrl }
        val absolute = RuleText.resolveUrl(base, raw) ?: return fail(label, "不是有效的地址: $raw")
        val headers = buildMap {
            for ((name, value) in config.headers) put(name, substitute(value, state))
            for ((name, value) in stepHeaders) {
                keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { remove(it) }
                put(name, substitute(value, state))
            }
        }
        val bodyText = substitute(body, state).takeIf { it.isNotEmpty() }
        val type = when (contentType.trim().lowercase()) {
            "" -> if (bodyText != null) CONTENT_TYPE_FORM else null
            "form" -> CONTENT_TYPE_FORM
            "json" -> CONTENT_TYPE_JSON
            else -> contentType.trim()
        }
        val request = RuleHttpRequest(method.trim().uppercase().ifEmpty { "GET" }, RuleText.normalizeUrl(absolute), headers, bodyText, type)
        val response = http.request(request)
        if (response.status >= 400) return fail(label, "HTTP ${response.status} ${request.url}")
        return ok(
            label,
            state.copy(
                value = response.text,
                pageUrl = response.finalUrl,
            ),
            "${request.method} ${request.url} -> ${response.text.length} 字",
        )
    }

    private fun queryParameter(input: String, name: String): String? {
        runCatching { Url(input).parameters[name] }.getOrNull()?.let { return it }
        // 不是完整地址 (只有查询串) 时自己找
        val match = Regex("""(?:^|[?&])${Regex.escape(name)}=([^&#]*)""").find(input) ?: return null
        return runCatching { match.groupValues[1].decodeURLQueryComponent() }.getOrNull()
    }

    // endregion

    // region 取值

    private fun select(state: RuleState, label: String, step: RuleStep.Select): RuleState? {
        val document = Html.parse(state.value, state.pageUrl)
        val element = document.select(step.css).getOrNull(step.index) ?: return fail(label, "没有选中 ${step.css}")
        val value = when {
            step.attr == "text" -> textOf(element)
            step.attr == "html" -> RuleText.innerHtml(element)
            step.attr == "outerHtml" -> element.toString()
            step.attr.startsWith("abs:") -> element.absUrl(step.attr.removePrefix("abs:"))
            else -> element.attr(step.attr)
        }.trim()
        return if (value.isEmpty()) fail(label, "${step.css} 的 ${step.attr} 为空") else ok(label, state.copy(value = value))
    }

    private fun textOf(element: Element): String =
        if (element.tagName().lowercase() in RAW_TEXT_TAGS) RuleText.innerHtml(element) else element.text()

    private fun groupValue(match: MatchResult, regex: Regex, group: String): String? {
        group.trim().toIntOrNull()?.let { return match.groups[it]?.value }
        return try {
            match.namedGroup(regex, group.trim())?.value
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun jsonText(element: JsonElement): String? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> element.contentOrNull
        else -> element.toString()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun macCmsPlayer(state: RuleState, label: String, step: RuleStep.MacCmsPlayer): RuleState? {
        val declaration = Regex("""\b${Regex.escape(step.variable)}\s*=""").find(state.value)
            ?: return fail(label, "页面里没有 ${step.variable}")
        val objectText = RuleText.jsonObjectAfter(state.value, declaration.range.last + 1)
            ?: return fail(label, "${step.variable} 不是对象")
        val obj = JSON.parseToJsonElement(objectText) as? JsonObject ?: return fail(label, "${step.variable} 不是对象")
        val raw = (obj[step.key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return fail(label, "${step.variable} 里没有 ${step.key}")
        val value = when ((obj["encrypt"] as? JsonPrimitive)?.contentOrNull?.trim()) {
            "1" -> RuleText.jsUnescape(raw)
            "2" -> {
                val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
                val decoded = runCatching { Base64.Default.decode(padded).decodeToString() }.getOrNull()
                    ?: return fail(label, "${step.key} 不是 base64")
                RuleText.jsUnescape(decoded)
            }

            else -> raw
        }
        val from = (obj["from"] as? JsonPrimitive)?.contentOrNull
        val variables = if (from != null) state.variables + ("from" to from) else state.variables
        return ok(label, state.copy(value = value, variables = variables))
    }

    // endregion

    // region 列表

    private fun subjects(state: RuleState, label: String, step: RuleStep.Subjects): RuleState? {
        val document = Html.parse(state.value, state.pageUrl)
        val cards = step.list.asSequence().map { document.select(it) }.firstOrNull { it.isNotEmpty() }
            ?: return fail(label, "没有选中条目")
        val subjects = cards.mapNotNull { card ->
            val link = findLink(card, step) ?: return@mapNotNull null
            val url = RuleText.resolveUrl(state.pageUrl, link.attr(step.linkAttr)) ?: return@mapNotNull null
            if (step.linkPattern.isNotEmpty() && step.linkPattern !in url) return@mapNotNull null
            val name = subjectName(card, link, step).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            RuleSubject(name, url)
        }.distinctBy { it.url }
        if (subjects.isEmpty()) return fail(label, "${cards.size} 个元素里没有取到条目")
        return ok(label, state.copy(subjects = subjects), "${subjects.size} 个条目")
    }

    private fun findLink(card: Element, step: RuleStep.Subjects): Element? {
        if (step.link.isNotBlank()) return card.select(step.link).firstOrNull()
        fun acceptable(element: Element): Boolean {
            val href = element.attr(step.linkAttr)
            if (href.isBlank() || href.startsWith("javascript:", ignoreCase = true) || href == "#") return false
            return step.linkPattern.isEmpty() || step.linkPattern in href
        }
        if (card.hasAttr(step.linkAttr) && acceptable(card)) return card
        return card.select("[${step.linkAttr}]").firstOrNull(::acceptable)
    }

    private fun subjectName(card: Element, link: Element, step: RuleStep.Subjects): String {
        if (step.name.isNotBlank()) {
            val element = card.select(step.name).firstOrNull() ?: return ""
            return (if (step.nameAttr.isNotBlank()) element.attr(step.nameAttr) else element.text()).trim()
        }
        return sequence {
            yield(link.attr("title"))
            yield(card.attr("title"))
            yield(card.select("img[alt]").firstOrNull()?.attr("alt").orEmpty())
            yield(card.select(NAME_HINTS).firstOrNull()?.text().orEmpty())
            yield(link.text())
            yield(card.text())
        }.map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun jsonSubjects(state: RuleState, label: String, step: RuleStep.JsonSubjects): RuleState? {
        val items = jsonItems(state.value, step.listPath)
        val base = config.baseUrl.ifBlank { state.pageUrl }
        val subjects = items.mapNotNull { item ->
            val name = item.stringByPath(step.namePath)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = jsonItemUrl(item, state, step.idPath, step.urlPath, step.urlTemplate, base) ?: return@mapNotNull null
            RuleSubject(name, url)
        }.distinctBy { it.url }
        if (subjects.isEmpty()) return fail(label, "JSON 里没有取到条目")
        return ok(label, state.copy(subjects = subjects), "${subjects.size} 个条目")
    }

    private fun episodes(state: RuleState, label: String, step: RuleStep.Episodes): RuleState? {
        val document = Html.parse(state.value, state.pageUrl)
        val lists = step.lists.asSequence().map { document.select(it) }.firstOrNull { it.isNotEmpty() }
            ?: return fail(label, "没有选中剧集列表")
        val tabNames = step.tabs.asSequence().map { document.select(it) }.firstOrNull { it.isNotEmpty() }
            ?.map { it.text().trim() }
            .orEmpty()
        val channels = lists.mapIndexedNotNull { index, list ->
            val episodes = list.select(step.items).mapNotNull { item ->
                val url = RuleText.resolveUrl(state.pageUrl, item.attr("href")) ?: return@mapNotNull null
                val name = item.text().trim().ifEmpty { item.attr("title").trim() }
                RuleEpisode(name, url)
            }
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            RuleChannel(tabNames.getOrNull(index)?.takeIf { it.isNotEmpty() }, if (step.reverse) episodes.reversed() else episodes)
        }
        return finishChannels(state, label, channels)
    }

    private fun jsonEpisodes(state: RuleState, label: String, step: RuleStep.JsonEpisodes): RuleState? {
        val base = config.baseUrl.ifBlank { state.pageUrl }
        val episodes = jsonItems(state.value, step.listPath).mapNotNull { item ->
            val url = jsonItemUrl(item, state, step.idPath, step.urlPath, step.urlTemplate, base) ?: return@mapNotNull null
            RuleEpisode(item.stringByPath(step.namePath)?.trim().orEmpty(), url)
        }
        val named = episodes.mapIndexed { index, episode ->
            if (episode.name.isEmpty()) episode.copy(name = "第${index + 1}集") else episode
        }
        return finishChannels(state, label, listOf(RuleChannel(step.channelName.ifBlank { null }, named)))
    }

    private fun regexEpisodes(state: RuleState, label: String, step: RuleStep.RegexEpisodes): RuleState? {
        val regex = RuleText.regex(substitute(step.pattern, state), step.ignoreCase)
        val byChannel = linkedMapOf<String?, MutableList<RuleEpisode>>()
        for (match in regex.findAll(state.value)) {
            val rawUrl = optionalGroup(match, regex, "url") ?: return fail(label, "正则里要有命名分组 url")
            val url = RuleText.resolveUrl(state.pageUrl, rawUrl) ?: continue
            val channel = optionalGroup(match, regex, "channel")?.trim()?.takeIf { it.isNotEmpty() }
            val list = byChannel.getOrPut(channel) { mutableListOf() }
            val name = optionalGroup(match, regex, "name")?.trim()?.takeIf { it.isNotEmpty() } ?: "第${list.size + 1}集"
            list.add(RuleEpisode(name, url))
        }
        return finishChannels(state, label, byChannel.map { (name, episodes) -> RuleChannel(name, episodes) })
    }

    private fun optionalGroup(match: MatchResult, regex: Regex, name: String): String? = try {
        match.namedGroup(regex, name)?.value
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * 线路收尾: 去掉空线路; 有多条线路却没有名字时按顺序起名 (「线路1」…), 否则同名剧集的资源 id 会撞.
     */
    private fun finishChannels(state: RuleState, label: String, channels: List<RuleChannel>): RuleState? {
        val nonEmpty = channels.filter { it.episodes.isNotEmpty() }
        if (nonEmpty.isEmpty()) return fail(label, "没有取到剧集")
        val named = if (nonEmpty.size > 1) {
            nonEmpty.mapIndexed { index, channel -> if (channel.name == null) channel.copy(name = "线路${index + 1}") else channel }
        } else {
            nonEmpty
        }
        return ok(label, state.copy(channels = named), "${named.size} 条线路, ${named.sumOf { it.episodes.size }} 集")
    }

    private fun jsonItems(text: String, listPath: String): List<JsonNode> {
        val root = JsonNode(JSON.parseToJsonElement(text))
        return root.selectByPath(listPath).flatMap { node ->
            val element = (node as JsonNode).element
            if (element is JsonArray) element.map(::JsonNode) else listOf(node)
        }
    }

    private fun jsonItemUrl(
        item: JsonNode,
        state: RuleState,
        idPath: String,
        urlPath: String,
        urlTemplate: String,
        base: String,
    ): String? {
        val fromPath = urlPath.takeIf { it.isNotBlank() }?.let { item.stringByPath(it) }?.takeIf { it.isNotBlank() }
        val raw = fromPath ?: urlTemplate.takeIf { it.isNotBlank() }?.let { template ->
            val fields = (item.element as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }.toMap()
            val id = item.stringByPath(idPath)
            RuleText.substitute(template, variablesOf(state) + fields + listOfNotNull(id?.let { "id" to it }))
        } ?: return null
        return RuleText.resolveUrl(base, raw)
    }

    // endregion

    private fun opName(step: RuleStep): String = when (step) {
        is RuleStep.Fetch -> "fetch"
        is RuleStep.Sniff -> "sniff"
        is RuleStep.Select -> "select"
        is RuleStep.Regex -> "regex"
        is RuleStep.Replace -> "replace"
        is RuleStep.Json -> "json"
        is RuleStep.Template -> "template"
        is RuleStep.SetVar -> "set"
        is RuleStep.Query -> "query"
        is RuleStep.Transforms -> "transform"
        is RuleStep.First -> "first"
        is RuleStep.MacCmsPlayer -> "maccmsPlayer"
        RuleStep.VideoUrl -> "videoUrl"
        is RuleStep.MediaHeaders -> "mediaHeaders"
        is RuleStep.Subjects -> "subjects"
        is RuleStep.JsonSubjects -> "jsonSubjects"
        is RuleStep.Episodes -> "episodes"
        is RuleStep.JsonEpisodes -> "jsonEpisodes"
        is RuleStep.RegexEpisodes -> "regexEpisodes"
    }

    companion object {
        const val VAR_KEYWORD = "keyword"
        const val VAR_SUBJECT_URL = "subjectUrl"
        const val VAR_EPISODE_URL = "episodeUrl"
        const val VAR_BASE_URL = "baseUrl"
        const val VAR_PAGE_URL = "pageUrl"
        const val VAR_VALUE = "value"

        private const val CONTENT_TYPE_FORM = "application/x-www-form-urlencoded"
        private const val CONTENT_TYPE_JSON = "application/json"
        private const val MAX_URL_LENGTH = 8192
        private val RAW_TEXT_TAGS = setOf("script", "style")
        private const val NAME_HINTS = "h1, h2, h3, h4, h5, h6, .title, [class*=title], [class*=name]"
        private val VIDEO_URL = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|flv|mkv)(?:[?#][^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        private val JSON = Json { isLenient = true }
    }
}
