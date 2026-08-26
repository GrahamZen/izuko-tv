/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ServerListFeatureHandler
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * **匹配探针 (常驻调试工具, 默认跳过)**: 对一批条目跑真实实现, 输出 backdrop (双路径) 与
 * 剧照形状, 用于排查单个条目的匹配问题或为锚点表收集期望值 (人工复核后写入).
 *
 * ```
 * ANI_TMDB_COLLECT=<输入清单.json> ./gradlew :app:shared:app-data:desktopTest --tests "*TmdbMatchProbe*"
 * ```
 * 输入: `{"cases":[{subjectId,name,nameCn,date,eps,theatrical,screeningYear,aliases}]}`
 * (字段与锚点表一致 —— **喂的输入必须与锚点测试逐字段相同**, 少一项就可能测出另一个结果.)
 * 输出: `build/franchise-collect.txt`, 行式 `C|sid|详情页path|列表页path|首日期键|条目数|带图数`.
 * 走 [TmdbE2eReplayUserAgentFeatureHandler] 的录制重放, 与锚点测试共享落盘缓存.
 */
class TmdbMatchProbeTest {
    @Serializable
    private data class In(
        val subjectId: Int,
        val name: String,
        val nameCn: String = "",
        val date: String = "",
        val eps: Int? = null,
        val theatrical: Boolean = false,
        val screeningYear: Int? = null,
        val aliases: List<String> = emptyList(),
        val episodeNames: List<String> = emptyList(),
    )

    @Serializable
    private data class InFixture(val cases: List<In>)

    @Test
    fun probe() = runTest(timeout = 120.minutes) {
        val input = System.getenv("ANI_TMDB_COLLECT") ?: run {
            println("跳过: 设 ANI_TMDB_COLLECT=<输入清单> 才跑")
            return@runTest
        }
        val cases = Json { ignoreUnknownKeys = true }
            .decodeFromString(InFixture.serializer(), File(input).readText()).cases
        val out = File("build/franchise-collect.txt")
        out.parentFile.mkdirs()
        val sb = StringBuilder()
        for ((i, c) in cases.withIndex()) {
            fun newService() = TmdbImageService(
                httpClientProvider = TestHttpClientProvider(),
                dataStore = MemoryDataStore(TmdbImageCache()),
                ioDispatcher = Dispatchers.IO,
            )
            val hints = TmdbMatchHints(
                nameCn = c.nameCn,
                screeningYear = c.screeningYear,
                theatrical = c.theatrical,
                airYear = c.date.take(4).toIntOrNull(),
                aliases = c.aliases,
            )
            fun String?.short() = this?.substringAfter("/t/p/w1280", missingDelimiterValue = this ?: "")
            val fromDetails = runCatching {
                newService().getBackdropUrl(c.subjectId, c.name, c.date.takeIf { it.isNotBlank() }, hints).short()
            }.getOrElse { "ERR:${it.message?.take(60)}" }
            val fromList = runCatching {
                newService().getBackdropUrl(c.subjectId, c.name, null, hints).short()
            }.getOrElse { "ERR:${it.message?.take(60)}" }
            val stills = runCatching {
                newService().getEpisodeStills(
                    subjectId = c.subjectId,
                    originalName = c.name,
                    language = "zh-CN",
                    newestWantedAirDate = c.date.takeIf { it.isNotBlank() },
                    subjectAirDate = c.date.takeIf { it.isNotBlank() },
                    subjectEpisodeCount = c.eps,
                    subjectEpisodeNames = c.episodeNames,
                    hints = hints,
                )
            }.getOrNull()
            val byAirDate = stills?.byAirDate.orEmpty()
            val withStill = byAirDate.values.flatten().count { it.stillUrl != null }
            val line = "C|${c.subjectId}|${fromDetails ?: ""}|${fromList ?: ""}|" +
                    "${byAirDate.keys.minOrNull() ?: ""}|${byAirDate.size}|$withStill"
            sb.appendLine(line)
            println("[${i + 1}/${cases.size}] $line")
        }
        out.writeText(sb.toString())
    }

    @Suppress("TestFunctionName")
    private fun TestScope.TestHttpClientProvider(): HttpClientProvider =
        DefaultHttpClientProvider(
            me.him188.ani.app.domain.settings.NoProxyProvider, this,
            featureHandlers = listOf(
                TmdbE2eReplayUserAgentFeatureHandler,
                ServerListFeatureHandler(flowOf(listOf(Url("https://auth.myani.org/")))),
            ),
        ).apply {
            coroutineContext.job.invokeOnCompletion {
                launch(NonCancellable) { forceReleaseAll() }
            }
        }
}
