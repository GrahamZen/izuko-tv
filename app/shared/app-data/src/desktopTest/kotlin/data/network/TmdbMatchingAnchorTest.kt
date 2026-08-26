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
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ServerListFeatureHandler
import me.him188.ani.app.domain.settings.NoProxyProvider
import me.him188.ani.app.platform.currentAniBuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * **锚点回归**: 历次修复里已知正确答案的例子, 拿**真实的 [TmdbImageService]** 跑一遍.
 *
 * 匹配规则一直是在离线模拟器上验证的, 但"模拟器对了"不等于"实现对了" —— 两边任何一处判据不同,
 * 验证就等于没做. 这个测试用真实实现 + 真实 TMDB/Bangumi 接口把期望值逐条对一遍, 堵的就是这个缝.
 *
 * 期望值表 `tmdb-anchors.json` 由模拟器导出, 每条附了它守的是哪条规则 (`why`).
 *
 * **默认跳过**: 放进 CI 会被限流也会因 TMDB 数据变动而不稳定. 本机验证时开:
 * ```
 * ANI_TMDB_E2E=1 ./gradlew :app:shared:app-data:desktopTest --tests "*TmdbMatchingAnchor*"
 * ```
 * `=1` 走 HTTP 录制重放 (真实实现 + 落盘响应, 全命中时十几秒, 见
 * [TmdbE2eReplayUserAgentFeatureHandler]), 日常改规则用它快筛; miss 的请求自动真实补录.
 * `=fresh` 忽略旧录制全部真打并重录 (~8 分钟), **commit 前必须跑一次**, 捕捉服务端数据漂移.
 */
class TmdbMatchingAnchorTest {
    @Serializable
    private data class Case(
        val subjectId: Int,
        val name: String,
        val nameCn: String = "",
        val date: String = "",
        val theatrical: Boolean = false,
        val screeningYear: Int? = null,
        val aliases: List<String> = emptyList(),
        /** TMDB 图片路径 (backdrop 或 poster); null = 该条目本就匹配不到 */
        val expectPath: String? = null,
        val layer: String? = null,
        val why: String = "",
    )

    @Serializable
    private data class Fixture(val cases: List<Case>)

    @Serializable
    private data class StillCase(
        val subjectId: Int,
        val name: String,
        val nameCn: String = "",
        val date: String = "",
        val eps: Int? = null,
        val theatrical: Boolean = false,
        /** 上映年度修正 (bgm 的 date 可能是发售日). collect 生成期望值时喂的是它, 这里不喂就对不上. */
        val screeningYear: Int? = null,
        val aliases: List<String> = emptyList(),
        /** bgm 分集原语言名, 供削字合集档按集标题认领 (SE/总集编条目). */
        val episodeNames: List<String> = emptyList(),
        /** byAirDate 里必须存在的日期键 (取自 TMDB 那侧的首播日). 错配到本传/他作时日期段必然错位. */
        val expectAirDateKeys: List<String> = emptyList(),
        /** byAirDate 里带图条目数的下限 (TMDB 的图一般只增不减; 空壳条目靠它抓 —— 集数可能对而全无图). */
        val minWithStill: Int = 0,
        val why: String = "",
    )

    @Serializable
    private data class StillFixture(val cases: List<StillCase>)

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `锚点逐条对期望值`() = runTest(timeout = 30.minutes) {
        if (System.getenv("ANI_TMDB_E2E") !in setOf("1", "fresh")) {
            println("跳过: 需要真实网络, 设 ANI_TMDB_E2E=1 (录制重放) 或 =fresh (全真实请求) 才跑")
            return@runTest
        }
        assertTrue(
            currentAniBuildConfig.tmdbApiToken.isNotBlank(),
            "local.properties 里没有 ani.tmdb.api.token, 这个测试跑不了",
        )

        // **先探一次 api.bgm.tv**: 它按 IP 限流, 而 TmdbImageService 连吃两次失败就熔断整个进程的
        // 关系查询 —— 撞上限流的表现是"所有走 root 档的锚点一起变无图", 与真回归长得一模一样.
        // 宁可跳过也不要把环境问题误报成代码问题.
        if (bangumiRateLimited()) {
            println("api.bgm.tv 正在限流 (429), 跳过本轮 —— 隔一阵再跑")
            return@runTest
        }

        val file = File("src/desktopTest/resources/tmdb-anchors.json")
        assertTrue(file.isFile, "期望值表不在: ${file.absolutePath}")
        val cases = json.decodeFromString(Fixture.serializer(), file.readText()).cases

        val failures = mutableListOf<String>()
        val snapshot = mutableListOf<String>()
        for (case in cases) {
            // **每条路径一个新实例** (缓存/熔断计数都是实例字段):
            // - 共用实例的话, 偶发一次限流的熔断会让后面所有走 root 档的锚点跟着变无图;
            // - 两条路径共用的话, 详情页先写的正缓存会把列表页的结果串味. 曾用
            //   subjectId + 10_000_000 的偏移隔离缓存, 但偏移后的假 id 会被送进 Ani/Bangumi
            //   的关系查询 —— 服务端对假 id 给不出关系, root 档在列表页路径整个失效,
            //   8 条衍生条目锚点集体变"无图", 与真回归一模一样 (2026-08-26 排查半天的教训).
            fun newService() = TmdbImageService(
                httpClientProvider = TestHttpClientProvider(),
                dataStore = MemoryDataStore(TmdbImageCache()),
                ioDispatcher = Dispatchers.IO,
            )
            val hints = TmdbMatchHints(
                nameCn = case.nameCn,
                screeningYear = case.screeningYear,
                theatrical = case.theatrical,
                airYear = case.date.take(4).toIntOrNull(),
                aliases = case.aliases,
            )
            // 详情页那条路: 拿得到条目日期
            val fromDetails = newService().resolvePath(case, activeAsOfDate = case.date, hints = hints)
            if (fromDetails != case.expectPath) {
                failures += "${case.subjectId} ${case.name}\n" +
                        "      守的规则: ${case.why}\n" +
                        "      期望 ${case.expectPath ?: "(无图)"} [${case.layer}]\n" +
                        "      实得 ${fromDetails ?: "(无图)"}"
            }
            // **列表页那条路: 没有分集数据, 拿不到"最新已播集日期"**. 结果必须与上面一致 ——
            // 不一致就是列表页会算出另一张图, 而且它先算完就把正缓存占了, 详情页再也纠不回来
            // (「攻殻機動隊」1995 剧场版原先在这条路上拿的是同系列 2026 年新剧的图).
            // 年份判据此时全靠 hints.airYear 垫底.
            val fromList = newService().resolvePath(case, activeAsOfDate = null, hints = hints)
            if (fromList != fromDetails) {
                failures += "${case.subjectId} ${case.name}\n" +
                        "      守的规则: 列表页 (无播出日期) 必须与详情页得到同一张图\n" +
                        "      详情页 ${fromDetails ?: "(无图)"}\n" +
                        "      列表页 ${fromList ?: "(无图)"}"
            }
            snapshot += "${case.subjectId}|${case.name}|${fromDetails ?: "(无图)"}" +
                    (if (fromList != fromDetails) "|列表页=${fromList ?: "(无图)"}" else "")
        }
        writeSnapshot("tmdb-anchors-snapshot.txt", snapshot)
        assertEquals(
            emptyList(), failures,
            "${failures.size} 处与期望不符 (${cases.size} 条锚点 x 两条调用路径):\n" +
                    failures.joinToString("\n"),
        )
    }

    /**
     * **剧照链锚点**: [fetchEpisodeStills 那条链] 与 backdrop 链的 findTv 是两套独立实现,
     * backdrop 锚点全绿不代表剧照链没坏 (2026-08-26 的 ゆめ∞みた 回归正是只坏剧照链).
     * 期望值判据用 byAirDate 的日期键 + 带图集数下限, 不需要生产结构暴露命中的 tv id.
     */
    @Test
    fun `剧照锚点逐条对期望值`() = runTest(timeout = 30.minutes) {
        if (System.getenv("ANI_TMDB_E2E") !in setOf("1", "fresh")) {
            println("跳过: 需要真实网络, 设 ANI_TMDB_E2E=1 (录制重放) 或 =fresh (全真实请求) 才跑")
            return@runTest
        }
        if (bangumiRateLimited()) {
            println("api.bgm.tv 正在限流 (429), 跳过本轮 —— 隔一阵再跑")
            return@runTest
        }
        val file = File("src/desktopTest/resources/tmdb-still-anchors.json")
        assertTrue(file.isFile, "期望值表不在: ${file.absolutePath}")
        val cases = json.decodeFromString(StillFixture.serializer(), file.readText()).cases

        val failures = mutableListOf<String>()
        val snapshot = mutableListOf<String>()
        for (case in cases) {
            val service = TmdbImageService(
                httpClientProvider = TestHttpClientProvider(),
                dataStore = MemoryDataStore(TmdbImageCache()),
                ioDispatcher = Dispatchers.IO,
            )
            val stills = service.getEpisodeStills(
                subjectId = case.subjectId,
                originalName = case.name,
                language = "zh-CN",
                newestWantedAirDate = case.date.takeIf { it.isNotBlank() },
                subjectAirDate = case.date.takeIf { it.isNotBlank() },
                subjectEpisodeCount = case.eps,
                subjectEpisodeNames = case.episodeNames,
                hints = TmdbMatchHints(
                    nameCn = case.nameCn,
                    screeningYear = case.screeningYear,
                    theatrical = case.theatrical,
                    airYear = case.date.take(4).toIntOrNull(),
                    aliases = case.aliases,
                ),
            )
            val byAirDate = stills?.byAirDate.orEmpty()
            val withStill = byAirDate.values.flatten().count { it.stillUrl != null }
            val missingKeys = case.expectAirDateKeys.filter { it !in byAirDate }
            if (missingKeys.isNotEmpty() || withStill < case.minWithStill) {
                failures += "${case.subjectId} ${case.name}\n" +
                        "      守的规则: ${case.why}\n" +
                        "      期望 byAirDate 含 ${case.expectAirDateKeys}, 带图 >= ${case.minWithStill}\n" +
                        "      实得 byAirDate=${byAirDate.size} 条 (缺 $missingKeys), 带图 $withStill"
            }
            snapshot += "${case.subjectId}|${case.name}|首键=${byAirDate.keys.minOrNull() ?: "(空)"}" +
                    "|日期数=${byAirDate.size}|带图=$withStill"
        }
        writeSnapshot("tmdb-still-anchors-snapshot.txt", snapshot)
        assertEquals(
            emptyList(), failures,
            "${failures.size} 处与期望不符 (${cases.size} 条剧照锚点):\n" + failures.joinToString("\n"),
        )
    }

    /**
     * **匹配结果快照** (用户要求: 每次更新匹配逻辑后留一个表, 覆盖不增长): 锚点测试跑完把
     * 每条的**实得值**按 subjectId 排序覆盖写进 resources, 随代码提交 —— `git diff` 直接
     * 展示这次改动让哪些条目换了答案, 比测试的红/绿更细 (含具体值). 断言失败也写 (失败状态
     * 的快照正是排查要看的).
     */
    private fun writeSnapshot(fileName: String, lines: List<String>) {
        File("src/desktopTest/resources/$fileName").writeText(
            lines.sortedBy { it.substringBefore('|').toIntOrNull() ?: 0 }
                .joinToString("\n", postfix = "\n"),
        )
    }

    /** 见调用处: 撞上限流就跳过整轮, 免得把它误读成回归. 探测本身失败 (断网等) 不算限流. */
    private suspend fun bangumiRateLimited(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URI("https://api.bgm.tv/v0/subjects/1").toURL()
                .openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "animeko-anchor-test/1.0")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val code = connection.responseCode
            connection.disconnect()
            code == 429
        }.getOrDefault(false)
    }

    /** 每条路径各自新实例 (见调用处), 这里用真实 subjectId —— 关系查询要靠它. */
    private suspend fun TmdbImageService.resolvePath(
        case: Case,
        activeAsOfDate: String?,
        hints: TmdbMatchHints,
    ): String? {
        val url = getBackdropUrl(
            subjectId = case.subjectId,
            originalName = case.name,
            activeAsOfDate = activeAsOfDate?.takeIf { it.isNotBlank() },
            hints = hints,
        )
        return url?.substringAfter("/t/p/w1280", missingDelimiterValue = url)
    }

    /**
     * **必须带 [ServerListFeatureHandler]**: Ani 的接口 baseurl 是占位符 host, 要靠这个 handler
     * 换成真实服务器. 少了它 [TmdbImageService] 取关系索引时会拿到 "No handler for feature",
     * 异常被吞掉后整条回落 Bangumi —— 表现是"走 root/ani 的条目全部无图", 正是这个测试第一次
     * 跑出来的那两条 (ef ~prologue~ / SideM Prologue).
     */
    @Suppress("TestFunctionName")
    private fun TestScope.TestHttpClientProvider(): HttpClientProvider =
        DefaultHttpClientProvider(
            NoProxyProvider, this,
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
