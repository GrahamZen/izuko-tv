/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.createTestSubjectCollection
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.recommendation.MAX_PREQUEL_HOPS
import me.him188.ani.app.data.recommendation.isSeasonFormat
import me.him188.ani.app.data.recommendation.sequelSeasonCandidates
import me.him188.ani.app.domain.episode.withSeriesRelations
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SeriesGraphTableTest {
    /**
     * 死神: 1600 (TV 本篇) → 302286 (千年血战篇) → 412916 (第二部分); 前导篇 900 的本传是它的直接续集 901,
     * 901 后面还有 902 (第二季). 99 只有前传 1600 指向它 (单向关系), 自己没有关系行.
     */
    private fun tsv() = listOf(
        "# bangumi-series-graph v1 max_id=500000 dump=dump-2026-09-22.210341Z.zip | 列: bgm_id, 原名, 中文名, …",
        "1600\tBLEACH\t死神\t2004-10-05\t366\tTV\t\t\t302286,99",
        "302286\tBLEACH 千年血戦篇\t死神 千年血战篇\t2022-10-10\t13\tTV\t\t1600\t412916",
        "412916\tBLEACH 千年血戦篇-訣別譚-\t死神 千年血战篇-诀别谭-\t2023-07-08\t13\tTV\t\t302286\t",
        "99\tBLEACH 劇場版\t\t2006-12\t1\t剧场版\t\t\t",
        "900\tef - a tale of memories. ~prologue~\t\t2007-08-24\t1\tOVA\t\t\t901",
        "901\tef - a tale of memories.\t\t2007-10-07\t12\tTV\t\t900\t902",
        "902\tef - a tale of melodies.\t\t2008-10-07\t12\tTV\t1\t901\t",
        // 以下都认不出, 要跳过
        "abc\t1",
        "10",
    ).joinToString("\n")

    private val tempDirectory = SystemPaths.createTempDirectory("series-graph-table-test")

    @AfterTest
    fun cleanup() {
        tempDirectory.deleteRecursively()
    }

    private fun table() = assertNotNull(SeriesGraphTable.parse(tsv().encodeToByteArray()))

    @Test
    fun `查表 - 有这一行给前传续集, 覆盖到却没有给空, 比表新的给 null`() {
        val table = table()
        assertEquals(500000, table.maxId)
        assertEquals("dump-2026-09-22.210341Z.zip", table.dump)
        assertEquals(7, table.size)
        val edges = assertNotNull(table.edgesOf(302286))
        assertEquals(listOf(1600), edges.prequels.map { it.id })
        assertEquals(listOf(412916), edges.sequels.map { it.id })
        assertEquals(listOf(302286, 99), table.edgesOf(1600)!!.sequels.map { it.id })
        val none = assertNotNull(table.edgesOf(400602))
        assertTrue(none.prequels.isEmpty() && none.sequels.isEmpty())
        assertNull(table.edgesOf(500001))
    }

    @Test
    fun `节点字段与接口构造的一致`() {
        val node = assertNotNull(table().nodeOf(302286))
        assertEquals("BLEACH 千年血戦篇", node.name)
        assertEquals("死神 千年血战篇", node.nameCn)
        assertEquals(listOf("TV"), node.metaTags)
        assertEquals(PackedDate(2022, 10, 10), node.airDate)
        assertEquals(13, node.episodes)
        assertFalse(node.nsfw)
        assertEquals("", node.imageLarge)
        val movie = assertNotNull(table().nodeOf(99))
        assertEquals("", movie.nameCn)
        // 只写到年月的首播日同接口那边: 认不出就是无效
        assertEquals(PackedDate.Invalid, movie.airDate)
        assertTrue(table().nodeOf(902)!!.nsfw)
    }

    @Test
    fun `认不出的内容不当表`() {
        assertNull(SeriesGraphTable.parse("<html><body>404</body></html>".encodeToByteArray()))
        assertNull(SeriesGraphTable.parse("# bangumi-series-graph v1 dump=x\n1\ta".encodeToByteArray()), "缺覆盖范围")
        assertNull(SeriesGraphTable.parse("# bangumi-sequel-seasons v1 rules=1 max_id=1\n1\t2".encodeToByteArray()), "别的表")
    }

    @Test
    fun `续作换季在表上走 - 挑出最早的一季`() = runTest {
        val table = table()
        val chain = walkPrequelChain(412916, MAX_PREQUEL_HOPS, ::isSeasonFormat) { table.edgesOf(it)!! }
        assertEquals(listOf(1600, 302286), sequelSeasonCandidates(chain).map { it.id })
    }

    @Test
    fun `系列索引与 TMDB 根名都在表上走, 不发请求`() = runTest {
        val table = table()
        val service = SubjectSeriesIndexService(noNetwork(), scope = backgroundScope, graph = { table })
        val index = service.getSubjectRelationIndex(302286)
        assertEquals(listOf(1600, 302286, 412916), index.seriesMainSubjectIds)
        assertEquals(listOf(412916), index.sequelSubjects)
        assertTrue("死神" in index.seriesMainSubjectNames)
        assertEquals(listOf("BLEACH", "死神"), service.seriesRootNames(302286, listOf("BLEACH 千年血戦篇", "死神 千年血战篇")))
        // 前导篇: 本传是它的直接续集; 续集的续集不算
        assertEquals(listOf("ef - a tale of memories."), service.seriesRootNames(900, listOf("ef - a tale of memories. ~prologue~")))
        // 系列起点只有续集, 续集不当母条目
        assertEquals(emptyList(), service.seriesRootNames(1600, listOf("BLEACH", "死神")))
    }

    /** Re:ZERO 第三、四季: 每季拆成两段, 后一段的 sort 接着前一段; 带本篇第一集 sort 与特别篇数两列. */
    private fun reZeroTsv() = listOf(
        "# bangumi-series-graph v1 max_id=700000 dump=dump-2026-10-06.210359Z.zip | 列: …",
        "425998\tRe:ゼロから始める異世界生活 3rd season 襲擊編\tRe：从零开始的异世界生活 第三季 袭击篇\t2024-10-02\t8\tTV\t\t\t510728\t51\t",
        "510728\tRe:ゼロから始める異世界生活 3rd season 反擊編\tRe：从零开始的异世界生活 第三季 反击篇\t2025-02-05\t8\tTV\t\t425998\t547888\t59\t",
        "547888\tRe:ゼロから始める異世界生活 4th season 喪失編\tRe：从零开始的异世界生活 第四季 丧失篇\t2026-04-08\t11\tTV\t\t510728\t633836\t67\t",
        "633836\tRe:ゼロから始める異世界生活 4th season 奪還編\tRe：从零开始的异世界生活 第四季 夺还篇\t2026-07-01\t8\tTV\t\t547888\t\t78\t",
    ).joinToString("\n")

    @Test
    fun `本篇第一集的 sort 与特别篇数 - 空是默认值, 旧表没有这两列时不知道`() {
        val node = assertNotNull(assertNotNull(SeriesGraphTable.parse(reZeroTsv().encodeToByteArray())).nodeOf(547888))
        assertEquals(67, node.firstSort)
        assertEquals(0, node.inlineSpecialCount)
        val eightySix = assertNotNull(SeriesGraphTable.parseRow("302189\t86―エイティシックス―\t86 -不存在的战区-\t2021-04-11\t11\tTV\t\t\t331887\t\t1")).node
        assertEquals(1, eightySix.firstSort)
        assertEquals(1, eightySix.inlineSpecialCount)
        assertNull(table().nodeOf(302286)!!.firstSort)
    }

    @Test
    fun `拆分季在本地识别, 不发请求`() = runTest {
        val table = assertNotNull(SeriesGraphTable.parse(reZeroTsv().encodeToByteArray()))
        val index = SubjectSeriesIndexService(noNetwork(), scope = backgroundScope, graph = { table }).getSubjectRelationIndex(633836)
        // 当前条目 (第四季后半) 用自己已经取到的分集
        val episodes = (78..85).mapIndexed { i, sort ->
            EpisodeCollectionInfo(
                EpisodeInfo(i + 1, EpisodeType.MainStory, sort = EpisodeSort(sort), ep = EpisodeSort(i + 1)),
                UnifiedCollectionType.NOT_COLLECTED,
            )
        }
        val subject = createTestSubjectCollection(633836, episodes, UnifiedCollectionType.NOT_COLLECTED).let {
            it.copy(
                subjectInfo = it.subjectInfo.copy(
                    nameCn = "Re：从零开始的异世界生活 第四季 夺还篇",
                    name = "Re:ゼロから始める異世界生活 4th season 奪還編",
                ),
            )
        }
        val season = assertNotNull(index.splitSeasonOf(subject))
        assertEquals(listOf(547888, 633836), season.parts.map { it.subjectId })
        assertEquals(1, season.selfIndex)
        assertEquals(listOf(67, 78), season.parts.map { it.firstSort })
        assertEquals(listOf("Re：从零开始的异世界生活 第四季", "Re:ゼロから始める異世界生活 4th season"), season.baseNames)
        assertEquals(listOf(3), season.otherSeasonNumbers)
        // 整季合成一页时, 后半第一集是第 12 集
        assertEquals(12, season.seasonNumberOf(78))
        // 开播用的系列信息 (选源上下文) 也带上
        assertEquals(season, flowOf(subject).withSeriesRelations(633836, { index }).first().relations.splitSeason)
    }

    @Test
    fun `读本地的表`() = runTest {
        val file = tempDirectory.resolve("table.tsv").apply { writeText(tsv()) }
        assertEquals(listOf(1600), repository(file).current()?.edgesOf(302286)?.prequels?.map { it.id })
    }

    @Test
    fun `启动时删掉不再用的旧表文件`() = runTest {
        val file = tempDirectory.resolve("table.tsv").apply { writeText(tsv()) }
        val obsolete = tempDirectory.resolve("bgm-sequel-seasons.tsv").apply { writeText("old") }
        val repository = repository(file, obsoleteFiles = listOf(obsolete))
        repository.current()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (obsolete.exists()) delay(10) } }
        assertFalse(obsolete.exists())
    }

    /** 真实时间跑: 等首轮下载的那 3 秒超时在虚拟时间里会立刻到点. */
    @Test
    fun `本地没有表文件 - 下整份, 不带下载元数据里的 ETag`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                respond(tsv(), HttpStatusCode.OK)
            },
        )
        val file = tempDirectory.resolve("table.tsv")
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val repository = SeriesGraphTableRepository(
                cache = MemoryDataStore(SeriesGraphTableCache(etag = "\"old\"", source = "x", checkedAt = Long.MAX_VALUE)),
                tableFile = file,
                client = { client.asScopedHttpClient() },
                scope = scope,
            )
            val table = withContext(Dispatchers.Default) { withTimeout(10.seconds) { repository.current() } }
            assertEquals(listOf(412916), table?.edgesOf(302286)?.sequels?.map { it.id })
            assertTrue(file.exists())
            assertEquals(tsv(), file.readText())
            assertNull(requests.first().headers[HttpHeaders.IfNoneMatch])
        } finally {
            scope.cancel()
            client.close()
        }
    }

    /** 不下载 (检查时间是新的); 下载一旦发生就是测试写错了. */
    private fun TestScope.repository(file: SystemPath, obsoleteFiles: List<SystemPath> = emptyList()) = SeriesGraphTableRepository(
        cache = MemoryDataStore(SeriesGraphTableCache(checkedAt = Long.MAX_VALUE)),
        tableFile = file,
        client = { error("不应下载") },
        scope = backgroundScope,
        obsoleteFiles = obsoleteFiles,
    )

    private fun noNetwork() = object : ApiInvoker<SubjectBangumiNextApi> {
        override suspend fun <R> invoke(action: suspend SubjectBangumiNextApi.() -> R): R = error("不应发请求")
    }
}
