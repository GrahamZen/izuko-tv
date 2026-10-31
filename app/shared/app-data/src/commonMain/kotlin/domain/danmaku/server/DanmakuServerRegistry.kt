/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.dandanplay.server.DanmakuServerEndpoint
import me.him188.ani.danmaku.dandanplay.server.DanmakuServerProvider
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * 手动添加的弹幕服务器存在单独的小文件里: 地址里带访问令牌, 不进设置备份.
 */
class DanmakuServerStore(private val file: SystemPath) {
    private val lock = Mutex()
    private val state = MutableStateFlow<SavedDanmakuServers?>(null)

    suspend fun current(): SavedDanmakuServers = lock.withLock { loadLocked() }

    suspend fun update(transform: (SavedDanmakuServers) -> SavedDanmakuServers) {
        lock.withLock {
            val next = transform(loadLocked())
            withContext(Dispatchers.IO_) {
                val temp = file.resolveSibling(file.name + ".tmp")
                temp.writeText(json.encodeToString(SavedDanmakuServers.serializer(), next))
                temp.moveTo(file)
            }
            state.value = next
        }
    }

    private suspend fun loadLocked(): SavedDanmakuServers {
        state.value?.let { return it }
        val loaded = withContext(Dispatchers.IO_) {
            try {
                if (file.exists()) json.decodeFromString(SavedDanmakuServers.serializer(), file.readText()) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to read ${file.name}, starting empty" }
                null
            }
        } ?: SavedDanmakuServers()
        state.update { it ?: loaded }
        return state.value ?: loaded
    }

    private companion object {
        private val logger = logger<DanmakuServerStore>()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * 当前生效的弹幕服务器: 手动添加的加上各订阅带来的 (见 [mergeDanmakuServers]). 同一台服务器一直用同一个
 * [DanmakuServerProvider], 它记着这台服务器缺哪些接口.
 */
class DanmakuServerRegistry(
    private val store: DanmakuServerStore,
    private val subscriptions: Flow<List<MediaSourceSubscription>>,
    private val client: () -> ScopedHttpClient,
) {
    private val lock = Mutex()
    private var cache: Map<ProviderKey, DanmakuServerProvider> = emptyMap()

    @Volatile
    private var lastProviders: List<DanmakuServerProvider> = emptyList()

    private data class ProviderKey(val name: String, val baseUrl: String, val autoMatch: Boolean, val linkImport: Boolean)

    /** 全部服务器, 含已停用的. */
    suspend fun items(): List<DanmakuServerItem> = mergeDanmakuServers(store.current(), subscriptions.first())

    /** 启用中的服务器. */
    suspend fun providers(): List<DanmakuServerProvider> {
        val items = items().filter { it.enabled }
        return lock.withLock {
            val next = items.associate { item ->
                val key = ProviderKey(item.name, item.baseUrl, item.autoMatch, item.linkImport)
                key to (cache[key] ?: newProvider(key))
            }
            cache = cache + next
            next.values.toList().also { lastProviders = it }
        }
    }

    /** [providers] 上次算出的结果里找; 手动匹配时用, 那时弹幕已经加载过一轮. */
    fun providerOrNull(id: DanmakuProviderId): DanmakuServerProvider? = lastProviders.firstOrNull { it.providerId == id }

    /** [providers] 上次算出的结果; 播放器状态每次轮询都要读, 不等锁也不读文件. */
    val cachedProviders: List<DanmakuServerProvider> get() = lastProviders

    /** 用这个地址建一个临时的, 测连接用; 不进列表. */
    fun probeProvider(name: String, baseUrl: String): DanmakuServerProvider =
        DanmakuServerProvider(DanmakuServerEndpoint(name, baseUrl), client())

    private fun newProvider(key: ProviderKey) = DanmakuServerProvider(
        DanmakuServerEndpoint(key.name, key.baseUrl, key.autoMatch, key.linkImport),
        client(),
    )
}
