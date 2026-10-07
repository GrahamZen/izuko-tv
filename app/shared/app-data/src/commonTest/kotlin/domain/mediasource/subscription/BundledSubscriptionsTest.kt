/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.subscription

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionsSaveData
import me.him188.ani.app.domain.media.fetch.MediaFetcher
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.datasources.api.matcher.MediaSourceWebVideoMatcherLoader
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledSubscriptionsTest {
    private val bt = "https://sub.creamycake.org/v1/bt1.json"
    private val retired = BundledSubscriptions.retiredUrls.single()
    private val bundled = BundledSubscriptions.urls.single()

    private val marks = mutableSetOf<String>()
    private val queried = mutableListOf<String>()

    private val manager = object : MediaSourceManager {
        override val allInstances: Flow<List<MediaSourceInstance>> get() = flowOf(emptyList())
        override val allFactories: List<MediaSourceFactory> get() = emptyList()
        override val allFactoryIds: List<FactoryId> get() = emptyList()
        override val mediaFetcher: Flow<MediaFetcher> get() = throw UnsupportedOperationException()
        override val webVideoMatcherLoader: MediaSourceWebVideoMatcherLoader get() = throw UnsupportedOperationException()
        override fun instanceConfigFlow(instanceId: String): Flow<MediaSourceConfig?> = flowOf(null)
        override suspend fun addInstance(instanceId: String, mediaSourceId: String, factoryId: FactoryId, config: MediaSourceConfig) = Unit
        override suspend fun getListBySubscriptionId(subscriptionId: String): List<MediaSourceSave> {
            queried += subscriptionId
            return emptyList()
        }

        override suspend fun partiallyReorderInstances(instanceIds: List<String>) = Unit
        override suspend fun updateConfig(instanceId: String, config: MediaSourceConfig): Boolean = true
        override suspend fun setEnabled(instanceId: String, enabled: Boolean) = Unit
        override suspend fun removeInstance(instanceId: String) = Unit
        override fun mediaSourceTiersFlow(): Flow<MediaSelectorSourceTiers> = flowOf(MediaSelectorSourceTiers.Empty)
    }

    private fun repository(vararg urls: String) = MediaSourceSubscriptionRepository(
        MemoryDataStore(
            MediaSourceSubscriptionsSaveData(urls.mapIndexed { i, url -> MediaSourceSubscription("s$i", url) }, version = 1),
        ),
    )

    private suspend fun reconcile(repository: MediaSourceSubscriptionRepository) =
        BundledSubscriptions.reconcile(repository, manager, { it in marks }, { marks += it })

    private suspend fun MediaSourceSubscriptionRepository.urls() = flow.first().map { it.url }

    @Test
    fun `new installs get upstream defaults without the retired subscription, plus the bundled one`() {
        val upstream = listOf(MediaSourceSubscription("a", bt), MediaSourceSubscription("b", retired))
        assertEquals(listOf(bt, bundled), BundledSubscriptions.withDefaults(upstream).map { it.url })
        assertEquals(listOf(bt, bundled), MediaSourceSubscriptionsSaveData.Default.list.map { it.url })
    }

    @Test
    fun `existing users lose the retired subscription with its sources and get the bundled one`() = runTest {
        val repository = repository(bt, retired)
        reconcile(repository)
        assertEquals(listOf(bt, bundled), repository.urls())
        assertEquals(listOf("s1"), queried) // 删的是它带来的数据源
    }

    @Test
    fun `a retired subscription the user adds back is kept`() = runTest {
        val repository = repository(bt, retired)
        reconcile(repository)
        repository.add(MediaSourceSubscription("again", retired))
        reconcile(repository)
        assertTrue(retired in repository.urls())
    }

    @Test
    fun `a bundled subscription the user deleted is not added back`() = runTest {
        val repository = repository(bt)
        reconcile(repository)
        repository.remove(repository.flow.first().single { it.url == bundled })
        reconcile(repository)
        assertEquals(listOf(bt), repository.urls())
    }
}
