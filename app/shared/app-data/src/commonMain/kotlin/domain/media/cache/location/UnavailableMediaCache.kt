/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 所在的盘没接上的缓存: 恢复记录时那个位置 ([location]) 不在, 用它占着列表里的位置, 不碰下载引擎与文件.
 * 不能播, 暂停 / 继续不做什么; 盘插回来时存储重新恢复, 换成真的缓存, 按记下的暂停状态接着下.
 *
 * 删除只删记录 ([deleteRows]), 文件留在盘上, 下次带着这个盘启动时由清扫回收 —— 这时删不到文件,
 * 也绝不能把「盘不在」当成「文件丢了」.
 */
class UnavailableMediaCache(
    override val origin: Media,
    override val metadata: MediaCacheMetadata,
    val location: String,
    /** 盘又接上了: 存储正在重新恢复它 (BT 要先校验盘上的数据, 可能要一两分钟), 恢复完就换成真的缓存 */
    val reconnected: Flow<Boolean> = flowOf(false),
    private val deleteRows: suspend () -> Unit,
) : MediaCache {
    override val state: Flow<MediaCacheState> = flowOf(MediaCacheState.FAILED)
    override val canPlay: Flow<Boolean> = flowOf(false)
    override val isLocationUnavailable: Boolean get() = true
    override val locationId: String? get() = location
    override val fileStats: Flow<MediaCache.FileStats> = flowOf(MediaCache.FileStats.Unspecified)
    override val sessionStats: Flow<MediaCache.SessionStats> = flowOf(MediaCache.SessionStats.Unspecified)
    override val isDeleted: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val rowsLock = Mutex()
    private var rowsDeleted = false

    override suspend fun getCachedMedia(): CachedMedia =
        throw IllegalStateException("The storage location $location of ${origin.mediaId} is not available")

    override suspend fun pause() {}
    override suspend fun resume() {}
    override suspend fun close() {}

    override suspend fun deletePersistedRows() = rowsLock.withLock {
        if (rowsDeleted) return@withLock
        deleteRows()
        rowsDeleted = true
    }

    override suspend fun closeAndDeleteFiles() {
        isDeleted.value = true
        deletePersistedRows()
        logger.info { "Deleted the record of ${origin.mediaId} on unavailable location $location, its files stay there" }
    }

    private companion object {
        private val logger = logger<UnavailableMediaCache>()
    }
}
