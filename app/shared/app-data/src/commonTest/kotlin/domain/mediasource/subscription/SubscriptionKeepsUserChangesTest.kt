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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionsSaveData
import me.him188.ani.app.domain.media.fetch.MediaFetcher
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceData
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceDataList
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.app.domain.mediasource.rule.RuleConfig
import me.him188.ani.app.domain.mediasource.rule.RuleMediaSource
import me.him188.ani.app.domain.mediasource.rule.RuleMediaSourceArguments
import me.him188.ani.app.domain.mediasource.rule.RuleMediaSourceCodec
import me.him188.ani.datasources.api.matcher.MediaSourceWebVideoMatcherLoader
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/**
 * 订阅更新只换数据源的配置: 用户调过的顺序 (设置「订阅更新时恢复订阅里的顺序」关着时) 与启停保留, 订阅里改了名的源原地更新.
 */
class SubscriptionKeepsUserChangesTest {
    private val subscriptionId = "sub"
    private var now = 1_000_000L
    private var remote = listOf(rule("甲", "a.example.com"), rule("乙", "b.example.com"), rule("丙", "c.example.com"))
    private var restore = false

    /** 全部数据源实例, 顺序即优先级. */
    private val saves = mutableListOf<MediaSourceSave>()
    private var reorderCalls = 0

    private fun rule(name: String, site: String) = ExportedMediaSourceData(
        RuleMediaSource.FactoryId,
        RuleMediaSourceCodec.currentVersion,
        MediaSourceCodecManager.json.encodeToJsonElement(
            RuleMediaSourceArguments.serializer(),
            // 图标都放在同一个图床: 认改名时不能拿它当站点
            RuleMediaSourceArguments(name = name, iconUrl = "https://img.example.com/icon.png", rule = RuleConfig(baseUrl = "https://$site")),
        ),
    )

    private val manager = object : MediaSourceManager {
        override val allInstances: Flow<List<MediaSourceInstance>> get() = flowOf(emptyList())
        override val allFactories: List<MediaSourceFactory> get() = emptyList()
        override val allFactoryIds: List<FactoryId> get() = emptyList()
        override val mediaFetcher: Flow<MediaFetcher> get() = throw UnsupportedOperationException()
        override val webVideoMatcherLoader: MediaSourceWebVideoMatcherLoader get() = throw UnsupportedOperationException()
        override fun instanceConfigFlow(instanceId: String): Flow<MediaSourceConfig?> = flowOf(saves.find { it.instanceId == instanceId }?.config)
        override suspend fun addInstance(instanceId: String, mediaSourceId: String, factoryId: FactoryId, config: MediaSourceConfig) {
            saves += MediaSourceSave(instanceId, mediaSourceId, factoryId, isEnabled = true, config = config)
        }

        override suspend fun getListBySubscriptionId(subscriptionId: String): List<MediaSourceSave> =
            saves.filter { it.config.subscriptionId == subscriptionId }

        override suspend fun partiallyReorderInstances(instanceIds: List<String>) {
            reorderCalls++
            val slots = saves.indices.filter { saves[it].instanceId in instanceIds }
            val moved = instanceIds.map { id -> saves.first { it.instanceId == id } }
            slots.zip(moved).forEach { (slot, save) -> saves[slot] = save }
        }

        override suspend fun updateConfig(instanceId: String, config: MediaSourceConfig): Boolean {
            val i = saves.indexOfFirst { it.instanceId == instanceId }
            if (i < 0) return false
            val old = saves[i]
            saves[i] = MediaSourceSave(old.instanceId, old.mediaSourceId, old.factoryId, old.isEnabled, config)
            return true
        }

        override suspend fun setEnabled(instanceId: String, enabled: Boolean) {
            val i = saves.indexOfFirst { it.instanceId == instanceId }
            val old = saves[i]
            saves[i] = MediaSourceSave(old.instanceId, old.mediaSourceId, old.factoryId, enabled, old.config)
        }

        override suspend fun removeInstance(instanceId: String) {
            saves.removeAll { it.instanceId == instanceId }
        }

        override fun mediaSourceTiersFlow(): Flow<MediaSelectorSourceTiers> = flowOf(MediaSelectorSourceTiers.Empty)
    }

    private val updater = MediaSourceSubscriptionUpdater(
        subscriptions = MediaSourceSubscriptionRepository(
            MemoryDataStore(MediaSourceSubscriptionsSaveData(listOf(MediaSourceSubscription(subscriptionId, url = "https://localhost/sub.json")), version = 1)),
        ),
        mediaSourceManager = manager,
        codecManager = MediaSourceCodecManager(),
        requester = { SubscriptionUpdateData(ExportedMediaSourceDataList(remote)) },
        getCurrentTimeMillis = { now },
        restoreOrder = { restore },
    )

    private suspend fun update() {
        updater.updateAllOutdated()
        now += 2.hours.inWholeMilliseconds
    }

    private fun names() = saves.map { MediaSourceCodecManager.json.decodeFromJsonElement(RuleMediaSourceArguments.serializer(), it.config.serializedArguments!!).name }

    private suspend fun moveToFront(name: String) {
        val i = names().indexOf(name)
        saves.add(0, saves.removeAt(i))
    }

    @Test
    fun `user order and enabled state survive updates`() = runTest {
        update()
        assertEquals(listOf("甲", "乙", "丙"), names())
        moveToFront("丙")
        manager.setEnabled(saves.first { it.config.serializedArguments.toString().contains("乙") }.instanceId, false)
        update()
        assertEquals(listOf("丙", "甲", "乙"), names())
        assertEquals(listOf(true, true, false), saves.map { it.isEnabled })
        assertEquals(0, reorderCalls)
    }

    @Test
    fun `restore order setting puts sources back in subscription order`() = runTest {
        update()
        moveToFront("丙")
        restore = true
        update()
        assertEquals(listOf("甲", "乙", "丙"), names())
    }

    @Test
    fun `a renamed source keeps its instance, position and enabled state`() = runTest {
        update()
        val before = saves.map { it.instanceId }
        moveToFront("乙")
        manager.setEnabled(saves.first().instanceId, false)
        remote = listOf(rule("甲", "a.example.com"), rule("乙 (新名字)", "b.example.com"), rule("丙", "c.example.com"))
        update()
        assertEquals(listOf("乙 (新名字)", "甲", "丙"), names())
        assertEquals(false, saves.first().isEnabled)
        assertEquals(before.toSet(), saves.map { it.instanceId }.toSet())
    }

    @Test
    fun `a removed source and an unrelated new one are not paired`() = runTest {
        update()
        val removedId = saves.first { it.config.serializedArguments.toString().contains("丙") }.instanceId
        remote = listOf(rule("甲", "a.example.com"), rule("乙", "b.example.com"), rule("丁", "d.example.com"))
        update()
        assertEquals(listOf("甲", "乙", "丁"), names())
        assertEquals(false, saves.any { it.instanceId == removedId })
    }
}
