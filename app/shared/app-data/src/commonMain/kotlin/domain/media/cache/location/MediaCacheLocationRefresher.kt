/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.location

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.media.cache.storage.AbstractDataStoreMediaCacheStorage
import me.him188.ani.app.domain.media.cache.storage.MediaCacheStorage
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 外接盘插拔时让各存储重新恢复记录: 盘拔了, 它上面的缓存换成占位 ([UnavailableMediaCache]), 旧的缓存对象随刷新关掉句柄;
 * 插回来, 占位换回真的缓存, 按记下的暂停状态接着下. 只看接着哪些位置, 剩余空间这类变化不触发.
 */
class MediaCacheLocationRefresher(
    private val locations: MediaCacheLocations,
    private val storages: () -> List<MediaCacheStorage>,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            locations.locations
                .map { list -> list.mapTo(HashSet()) { it.id } }
                .distinctUntilChanged()
                .drop(1) // 启动时的那一份由启动恢复处理
                .collect { available ->
                    logger.info { "Cache locations changed, available: $available. Refreshing caches" }
                    // 各存储同时刷新: BT 的缓存插回来要先校验盘上的数据 (可能一两分钟), 别让网页缓存跟着等
                    coroutineScope {
                        for (storage in storages()) {
                            if (storage !is AbstractDataStoreMediaCacheStorage) continue
                            launch {
                                try {
                                    storage.refreshCache()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to refresh ${storage.mediaSourceId} after locations changed" }
                                }
                            }
                        }
                    }
                }
        }
    }

    private companion object {
        private val logger = logger<MediaCacheLocationRefresher>()
    }
}
