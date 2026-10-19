/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.codec

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.fetch.MediaFetcher
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.datasources.api.matcher.MediaSourceWebVideoMatcherLoader
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceFactory
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.utils.ktor.ScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MediaSourceTextImportTest {
    private val codec = createTestMediaSourceCodecManager()

    private fun rss(name: String) = """{"factoryId":"rss","version":1,"arguments":{"name":"$name","description":"","iconUrl":""}}"""

    private fun importer(sources: RecordingSources, download: suspend (String) -> String = { error("not used") }) =
        MediaSourceTextImport(sources, codec, download)

    @Test
    fun `text that is neither rules nor exported json is not recognized`() = runTest {
        assertNull(importer(RecordingSources()).import("hello"))
    }

    @Test
    fun `exported sources are added as local sources`() = runTest {
        val sources = RecordingSources()
        val result = assertNotNull(importer(sources).import("""{"mediaSources":[${rss("A")},${rss("B")}]}"""))
        assertEquals(2, result.added)
        assertEquals(emptyList(), result.failures)
        assertEquals(listOf(FactoryId("rss"), FactoryId("rss")), sources.saves.map { it.factoryId })
    }

    @Test
    fun `unknown types are reported and the rest are still added`() = runTest {
        val sources = RecordingSources()
        val unknown = """{"factoryId":"unknown","version":1,"arguments":{}}"""
        val result = assertNotNull(importer(sources).import("[${rss("A")},$unknown]"))
        assertEquals(1, result.added)
        assertEquals(listOf<MediaSourceTextImport.Failure>(MediaSourceTextImport.Failure.UnsupportedType("unknown")), result.failures)
    }

    @Test
    fun `an address that fails to download is reported`() = runTest {
        val result = assertNotNull(importer(RecordingSources()) { error("offline") }.import("https://example.com/rules.json"))
        assertEquals(0, result.added)
        assertEquals(listOf<MediaSourceTextImport.Failure>(MediaSourceTextImport.Failure.Download("offline")), result.failures)
    }

    private class RssFactory : MediaSourceFactory {
        override val factoryId: FactoryId = FactoryId("rss")
        override val allowMultipleInstances: Boolean get() = true
        override val info: MediaSourceInfo = MediaSourceInfo("RSS")
        override fun create(mediaSourceId: String, config: MediaSourceConfig, client: ScopedHttpClient): MediaSource = error("not used")
    }

    private class RecordingSources : MediaSourceManager {
        var saves: List<MediaSourceSave> = emptyList()

        override val allInstances: Flow<List<MediaSourceInstance>> = flowOf(emptyList())
        override val allFactories: List<MediaSourceFactory> = listOf(RssFactory())
        override val allFactoryIds: List<FactoryId> = allFactories.map { it.factoryId }
        override val mediaFetcher: Flow<MediaFetcher> = flowOf()
        override val webVideoMatcherLoader = MediaSourceWebVideoMatcherLoader(flowOf(emptyList()))
        override fun instanceConfigFlow(instanceId: String): Flow<MediaSourceConfig?> = flowOf(null)
        override fun mediaSourceTiersFlow() = flowOf(MediaSelectorSourceTiers.Empty)
        override suspend fun addInstance(instanceId: String, mediaSourceId: String, factoryId: FactoryId, config: MediaSourceConfig) {
            saves = saves + MediaSourceSave(instanceId, mediaSourceId, factoryId, true, config)
        }

        override suspend fun getListBySubscriptionId(subscriptionId: String) = emptyList<MediaSourceSave>()
        override suspend fun partiallyReorderInstances(instanceIds: List<String>) {}
        override suspend fun updateConfig(instanceId: String, config: MediaSourceConfig): Boolean = true
        override suspend fun setEnabled(instanceId: String, enabled: Boolean) = error("not used")
        override suspend fun removeInstance(instanceId: String) {}
    }
}
