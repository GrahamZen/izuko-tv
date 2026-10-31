/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.repository.danmaku.SearchDanmakuRequest
import me.him188.ani.app.domain.danmaku.imported.DanmakuImporter
import me.him188.ani.app.domain.danmaku.imported.DanmakuLinkImportResult
import me.him188.ani.app.domain.danmaku.imported.ImportedDanmakuProvider
import me.him188.ani.app.domain.danmaku.imported.ImportedDanmakuStore
import me.him188.ani.app.domain.danmaku.server.DanmakuServerEntry
import me.him188.ani.app.domain.danmaku.server.DanmakuServerRegistry
import me.him188.ani.app.domain.danmaku.server.DanmakuServerStore
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.DanmakuLocation
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchInfo
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProvider
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.Imported
import me.him188.ani.danmaku.api.provider.SimpleDanmakuProvider
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.asScopedHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

class DanmakuExtraSourcesTest {
    private val dir = SystemPaths.createTempDirectory("danmaku-extra-sources-test")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** 导入链接的超时要按真实时间走: 请求在真实线程上, 虚拟时间会让超时立刻到 */
    private fun realTimeTest(block: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

    private fun content(text: String) = DanmakuContent(1000, 255, text, DanmakuLocation.TOP)

    private fun result(provider: String, id: String) = DanmakuFetchResult(
        DanmakuProviderId(provider),
        DanmakuMatchInfo(DanmakuServiceId(provider), 1, DanmakuMatchMethod.ExactId(1, 1)),
        listOf(DanmakuInfo(id, DanmakuServiceId(provider), "", content(id))),
    )

    private class FakeProvider(val id: String, val response: suspend () -> List<DanmakuFetchResult>) : SimpleDanmakuProvider {
        override val providerId = DanmakuProviderId(id)
        override val mainServiceId = DanmakuServiceId(id)
        override suspend fun fetchAutomatic(request: DanmakuFetchRequest) = response()
    }

    private val request = DanmakuFetchRequest(
        subjectId = 1,
        subjectPrimaryName = "番",
        subjectNames = listOf("番"),
        subjectPublishDate = PackedDate.Invalid,
        episodeId = 1,
        episodeSort = EpisodeSort(1),
        episodeEp = null,
        episodeName = "",
        filename = null,
        fileHash = null,
        fileSize = null,
        videoDuration = Duration.ZERO,
    )

    private fun ids(results: List<DanmakuFetchResult>?) = results.orEmpty().flatMap { r -> r.list.map { it.id } }

    @Test
    fun `slow source does not hold back the fast one and order follows the sources`() = runTest {
        val slow = CompletableDeferred<List<DanmakuFetchResult>>()
        val fetchers = listOf(
            DanmakuFetcher(FakeProvider("slow") { slow.await() }),
            DanmakuFetcher(FakeProvider("fast") { listOf(result("fast", "f")) }),
        )

        fetchAllConcurrently(fetchers, request).test {
            assertEquals(listOf("f"), ids(awaitItem()))
            slow.complete(listOf(result("slow", "s")))
            assertEquals(listOf("s", "f"), ids(awaitItem()))
            awaitComplete()
        }
    }

    @Test
    fun `empty source emits nothing while others are pending`() = runTest {
        val slow = CompletableDeferred<List<DanmakuFetchResult>>()
        val fetchers = listOf(
            DanmakuFetcher(FakeProvider("empty") { emptyList() }),
            DanmakuFetcher(FakeProvider("slow") { slow.await() }),
        )

        fetchAllConcurrently(fetchers, request).test {
            expectNoEvents()
            slow.complete(listOf(result("slow", "s")))
            assertEquals(listOf("s"), ids(awaitItem()))
            awaitComplete()
        }
    }

    @Test
    fun `all sources empty still emits once when finished`() = runTest {
        val fetchers = listOf(
            DanmakuFetcher(FakeProvider("a") { emptyList() }),
            DanmakuFetcher(FakeProvider("b") { emptyList() }),
        )

        fetchAllConcurrently(fetchers, request).test {
            assertEquals(emptyList(), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `failed source becomes a placeholder and others still arrive`() = runTest {
        val fetchers = listOf(
            DanmakuFetcher(FakeProvider("bad") { error("boom") }),
            DanmakuFetcher(FakeProvider("good") { listOf(result("good", "g")) }),
        )

        fetchAllConcurrently(fetchers, request).test {
            var last = awaitItem()
            if (last.size < 2) last = awaitItem()
            assertEquals(listOf("g"), ids(last))
            assertEquals(true, last.first { it.providerId.id == "bad" }.matchInfo.fetchFailed)
            awaitComplete()
        }
    }

    private fun imported(id: String) = DanmakuFetchResult(
        DanmakuProviderId.Imported,
        DanmakuMatchInfo(DanmakuServiceId.Imported, 1, DanmakuMatchMethod.ExactId(1, 1)),
        listOf(DanmakuInfo(id, DanmakuServiceId.Imported, "", content(id))),
    )

    private fun cached(id: String) = DanmakuFetchResult(
        DanmakuProviderId.Local,
        DanmakuMatchInfo(DanmakuServiceId.Dandanplay, 1, DanmakuMatchMethod.ExactId(1, 1)),
        listOf(DanmakuInfo(id, DanmakuServiceId.Dandanplay, "", content(id))),
    )

    private fun TestScope.loader(
        local: Flow<List<DanmakuFetchResult>>,
        remote: Flow<List<DanmakuFetchResult>>,
    ) = DanmakuLoaderImpl(
        requestFlow = MutableStateFlow(
            SearchDanmakuRequest(
                subjectInfo = SubjectInfo.createPlaceholder(1, "Subject", image = ""),
                episodeInfo = EpisodeInfo.Empty.copy(episodeId = 1, name = "Episode 1"),
                episodeId = 1,
            ),
        ),
        flowScope = backgroundScope,
        fetchFromLocal = { local },
        fetchFromAllRemotes = { remote },
        cacheDanmakuIfNeeded = { _, _, _ -> },
    )

    @Test
    fun `partial remote results keep arriving`() = runTest {
        val local = MutableSharedFlow<List<DanmakuFetchResult>>()
        val remote = MutableSharedFlow<List<DanmakuFetchResult>>()

        loader(local, remote).fetchResultFlow.test {
            assertEquals(emptyList(), ids(awaitItem()))
            remote.emit(listOf(result("a", "1")))
            assertEquals(listOf("1"), ids(awaitItem()))
            remote.emit(listOf(result("a", "1"), result("b", "2")))
            assertEquals(listOf("1", "2"), ids(awaitItem()))
        }
    }

    @Test
    fun `imported danmaku do not replace the local cache`() = runTest {
        val local = MutableSharedFlow<List<DanmakuFetchResult>>()
        val remote = MutableSharedFlow<List<DanmakuFetchResult>>()

        loader(local, remote).fetchResultFlow.test {
            assertEquals(emptyList(), ids(awaitItem()))
            local.emit(listOf(cached("c")))
            assertEquals(listOf("c"), ids(awaitItem()))
            remote.emit(listOf(imported("i")))
            assertEquals(listOf("c", "i"), ids(awaitItem()))
            remote.emit(listOf(imported("i"), result("a", "1")))
            assertEquals(listOf("i", "1"), ids(awaitItem()))
        }
    }

    @Test
    fun `local cache arriving late keeps imported danmaku`() = runTest {
        val local = MutableSharedFlow<List<DanmakuFetchResult>>()
        val remote = MutableSharedFlow<List<DanmakuFetchResult>>()

        loader(local, remote).fetchResultFlow.test {
            assertEquals(emptyList(), ids(awaitItem()))
            remote.emit(listOf(imported("i")))
            assertEquals(listOf("i"), ids(awaitItem()))
            local.emit(listOf(cached("c")))
            assertEquals(listOf("c", "i"), ids(awaitItem()))
        }
    }

    @Test
    fun `imported batches are stored per episode and form one source`() = runTest {
        val store = ImportedDanmakuStore(dir.resolve("imported"))
        val provider = ImportedDanmakuProvider(store)

        assertEquals(emptyList(), provider.results(1, 1))

        val first = store.add(1, 1, "a.xml", listOf(content("a")))
        store.add(1, 1, "b.xml", listOf(content("b"), content("c")))
        store.add(1, 2, "other.xml", listOf(content("other")))

        val result = provider.results(1, 1).single()
        assertEquals(DanmakuServiceId.Imported, result.matchInfo.serviceId)
        assertEquals(3, result.matchInfo.count)
        assertNull(result.matchInfo.sourceEpisodeId)
        assertEquals(listOf("a", "b", "c"), result.list.map { it.text })
        assertEquals(content("a"), result.list.first().content)
        assertEquals(3, result.list.map { it.id }.toSet().size)

        assertEquals(true, store.remove(1, 1, first.id))
        assertEquals(false, store.remove(1, 1, first.id))
        assertEquals(listOf("b", "c"), provider.results(1, 1).single().list.map { it.text })
        assertEquals(listOf("other"), provider.results(1, 2).single().list.map { it.text })

        store.list(1, 1).forEach { store.remove(1, 1, it.id) }
        assertEquals(emptyList(), provider.results(1, 1))
    }

    @Test
    fun `imported danmaku survive a new store`() = runTest {
        ImportedDanmakuStore(dir.resolve("imported")).add(1, 1, "a.xml", listOf(content("a")))
        assertEquals("a.xml", ImportedDanmakuStore(dir.resolve("imported")).list(1, 1).single().label)
    }

    private fun importer(vararg servers: Pair<String, String>): Pair<DanmakuImporter, ImportedDanmakuStore> {
        val store = ImportedDanmakuStore(dir.resolve("imported"))
        val serverStore = DanmakuServerStore(dir.resolve("servers.json"))
        val engine = MockEngine { data ->
            val body = servers.firstOrNull { data.url.host == it.first }?.second
            if (body == null) {
                respond("", HttpStatusCode.NotFound)
            } else {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }
        val client = HttpClient(engine).asScopedHttpClient()
        val registry = DanmakuServerRegistry(serverStore, MutableStateFlow(emptyList())) { client }
        return DanmakuImporter(store, registry) to store.also {
            runBlocking {
                serverStore.update { saved ->
                    saved.copy(servers = listOf("a.com", "b.com").map { DanmakuServerEntry(it, "https://$it") })
                }
            }
        }
    }

    @Test
    fun `importing a file stores it and refuses other files`() = runTest {
        val (importer, store) = importer()

        assertNull(importer.importFile(1, 1, "x.txt", "not danmaku".encodeToByteArray()))
        val empty = assertNotNull(importer.importFile(1, 1, "e.xml", "<i></i>".encodeToByteArray()))
        assertEquals(0, empty.count)
        assertEquals(emptyList(), store.list(1, 1))

        val xml = """<i><d p="1,1,25,255,0,0,a,1">hi</d></i>""".encodeToByteArray()
        assertEquals(1, assertNotNull(importer.importFile(1, 1, "ok.xml", xml)).count)
        assertEquals("ok.xml", store.list(1, 1).single().label)
    }

    @Test
    fun `link import uses the server that has danmaku`() = realTimeTest {
        val (importer, store) = importer(
            "a.com" to """{"success":true,"count":0,"comments":[]}""",
            "b.com" to """{"success":true,"count":1,"comments":[{"p":"1,1,255,u","m":"hi"}]}""",
        )

        val r = assertIs<DanmakuLinkImportResult.Success>(importer.importLink(1, 1, "BV1GJ411x7h7"))

        assertEquals("b.com", r.serverName)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", store.list(1, 1).single().label)
    }

    @Test
    fun `link import reports why nothing came back`() = realTimeTest {
        val (noRoutes, _) = importer()
        assertEquals(DanmakuLinkImportResult.NoServer, noRoutes.importLink(1, 1, "https://example.org/v"))

        val (empty, _) = importer("a.com" to """{"comments":[]}""", "b.com" to """{"comments":[]}""")
        assertEquals(DanmakuLinkImportResult.Empty, empty.importLink(1, 1, "https://example.org/v"))
        assertEquals(DanmakuLinkImportResult.InvalidLink, empty.importLink(1, 1, "孤独摇滚"))

        val (refused, _) = importer(
            "a.com" to """{"success":false,"errorMessage":"不支持的链接"}""",
            "b.com" to """{"comments":[]}""",
        )
        assertEquals(DanmakuLinkImportResult.Failed("不支持的链接"), refused.importLink(1, 1, "https://example.org/v"))
    }

    @Test
    fun `failing to read servers leaves only the imported source`() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }).asScopedHttpClient()
        val registry = DanmakuServerRegistry(
            DanmakuServerStore(dir.resolve("servers.json")),
            flow { throw IllegalStateException("corrupted") },
        ) { client }
        val imported = ImportedDanmakuProvider(ImportedDanmakuStore(dir.resolve("imported")))

        assertEquals(listOf<DanmakuProvider>(imported), DanmakuExtraProviders(imported, registry).all())
    }

    @Test
    fun `saved servers are read back and stopped ones produce no provider`() = runTest {
        val file = dir.resolve("servers.json")
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }).asScopedHttpClient()
        DanmakuServerStore(file).update {
            it.copy(
                servers = listOf(DanmakuServerEntry("A", "https://a.com"), DanmakuServerEntry("B", "https://b.com")),
                disabledUrls = setOf("https://b.com"),
            )
        }

        val registry = DanmakuServerRegistry(DanmakuServerStore(file), MutableStateFlow(emptyList())) { client }

        assertEquals(listOf("A", "B"), registry.items().map { it.name })
        val first = registry.providers().single()
        assertEquals("A", first.endpoint.name)
        assertEquals(first, registry.providers().single(), "same provider remembers which routes are missing")
        assertEquals(first, registry.providerOrNull(first.providerId))
    }
}
