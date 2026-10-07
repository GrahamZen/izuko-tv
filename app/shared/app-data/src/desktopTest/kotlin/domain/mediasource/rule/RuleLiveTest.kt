/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceData
import me.him188.ani.utils.ktor.getPlatformKtorEngine
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * 对真实站点试跑规则: 搜索 → 打开条目 → 取第一集的视频地址 → 请求视频地址的开头几 KB, 每个源一行汇总.
 * 默认跳过; 用环境变量开启:
 *
 * - `ANI_RULE_INPUT`: 规则源导出的 JSON 文件 (单个或列表), AniBaka 规则文件, 或 AniBaka 规则 / 规则库的地址.
 * - `ANI_RULE_KEYWORDS`: 搜索关键词, 用 `|` 分隔, 依次试到有结果为止.
 * - `ANI_RULE_REPORT`: 可选, 汇总另写一份到这个文件.
 * - `ANI_RULE_ONLY`: 可选, 只跑名字里含这些词的源, 用 `|` 分隔.
 * - `ANI_RULE_VERBOSE`: 可选, 为 `1` 时打印每一步.
 *
 * 这里没有验证码会话, 被站点验证挡住的源会显示为请求失败.
 */
class RuleLiveTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(getPlatformKtorEngine()) {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
        followRedirects = true
    }

    private val http = RuleHttp { request ->
        val headers = if (request.headers.keys.any { it.equals(HttpHeaders.UserAgent, ignoreCase = true) }) {
            request.headers
        } else {
            request.headers + (HttpHeaders.UserAgent to DESKTOP_UA)
        }
        client.executeRuleRequest(request.copy(headers = headers))
    }

    @Test
    fun `run rules against real sites`() = runBlocking<Unit> {
        val input = System.getenv("ANI_RULE_INPUT")
        val keywords = System.getenv("ANI_RULE_KEYWORDS")?.split('|')?.filter { it.isNotBlank() }
        if (input.isNullOrBlank() || keywords.isNullOrEmpty()) {
            println("[skip] ANI_RULE_INPUT or ANI_RULE_KEYWORDS not set")
            return@runBlocking
        }
        val only = System.getenv("ANI_RULE_ONLY")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        val (allSources, failures) = loadSources(input)
        val sources = if (only.isEmpty()) allSources else allSources.filter { source -> only.any { it in source.name } }
        val lines = mutableListOf<String>()
        failures.forEach { lines += "[未导入] $it" }
        for (source in sources) {
            val line = withTimeoutOrNull(90.seconds) { tryOne(source, keywords) } ?: "${source.name} | 超时"
            println(line)
            lines += line
        }
        val report = lines.joinToString("\n")
        println("===== 汇总 (${sources.size} 个源) =====\n$report")
        System.getenv("ANI_RULE_REPORT")?.let { File(it).writeText(report) }
    }

    private suspend fun loadSources(input: String): Pair<List<RuleMediaSourceArguments>, List<String>> {
        val text = if (input.startsWith("http://") || input.startsWith("https://")) input else File(input).readText()
        AniBakaRuleImporter.import(text) { url -> client.get(url) { header(HttpHeaders.UserAgent, DESKTOP_UA) }.bodyAsText() }
            ?.let { return it.sources to it.failures }
        val root = json.parseToJsonElement(text)
        val items = when {
            root is JsonArray -> root
            root is JsonObject && "mediaSources" in root -> root.getValue("mediaSources").jsonArray
            else -> listOf(root)
        }
        val sources = items.map { json.decodeFromJsonElement(ExportedMediaSourceData.serializer(), it.jsonObject) }
            .filter { it.factoryId == RuleMediaSource.FactoryId }
            .map { json.decodeFromJsonElement(RuleMediaSourceArguments.serializer(), it.arguments) }
        return sources to emptyList()
    }

    private suspend fun tryOne(source: RuleMediaSourceArguments, keywords: List<String>): String {
        val trace = mutableListOf<String>()
        val verbose = System.getenv("ANI_RULE_VERBOSE") == "1"
        val engine = RuleEngine(source.rule, http) { label, ok, detail ->
            if (verbose) println("  ${if (ok) "ok  " else "FAIL"} $label: $detail")
            if (!ok) trace += "$label: $detail"
        }
        fun failed(stage: String) = "${source.name} | $stage | ${trace.takeLast(2).joinToString(" / ")}"
        try {
            var keyword = keywords.first()
            var subjects = emptyList<RuleSubject>()
            for (candidate in keywords) {
                keyword = candidate
                subjects = engine.search(candidate)
                if (subjects.isNotEmpty()) break
            }
            if (subjects.isEmpty()) return failed("搜索 0 条")
            val subject = subjects.firstOrNull { keyword in it.name } ?: subjects.first()
            val channels = engine.detail(subject.url)
            if (channels.isEmpty()) return failed("搜索 ${subjects.size} 条 [${subject.name}] | 剧集 0")
            val episode = channels.first().episodes.first()
            val summary = "搜索 ${subjects.size} 条 [${subject.name}] | 线路 ${channels.size} 剧集 ${channels.sumOf { it.episodes.size }} " +
                    "[${channels.first().name}/${episode.name}]"
            return when (val play = engine.play(episode.url)) {
                RulePlayResult.Sniff -> "${source.name} | $summary | 播放: 要 WebView 嗅探 ${episode.url}"
                RulePlayResult.Failed -> failed("$summary | 播放失败")
                is RulePlayResult.Direct -> "${source.name} | $summary | 播放: ${probe(play)}"
            }
        } catch (e: Exception) {
            return failed("出错 $e")
        }
    }

    /** 请求视频地址的开头, 看是不是真的视频. */
    private suspend fun probe(play: RulePlayResult.Direct): String = try {
        // 流式只读开头: 不认 Range 的服务器会回整个文件
        client.prepareGet(play.url) {
            for ((name, value) in play.headers) header(name, value)
            header(HttpHeaders.Range, "bytes=0-4095")
        }.execute { response ->
            val bytes = response.bodyAsChannel().readRemaining(4096).readByteArray()
            val kind = when {
                bytes.decodeToString().startsWith("#EXTM3U") -> "m3u8"
                bytes.size > 8 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" -> "mp4"
                bytes.isNotEmpty() && bytes[0] == 0x47.toByte() -> "ts"
                else -> "其他(${response.headers[HttpHeaders.ContentType]})"
            }
            "${response.status.value} $kind ${play.url.take(100)}"
        }
    } catch (e: Exception) {
        "探测出错 $e ${play.url.take(100)}"
    }

    private companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
