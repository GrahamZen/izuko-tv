/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import kotlinx.coroutines.CancellationException
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

private val logger = logger<TorrentMediaCacheStorage>()

/**
 * 关掉 [previous] 里已经不在 [current] 中的 BT 缓存对象的文件句柄.
 *
 * refreshCache 把每条下载重新恢复成新的缓存对象, 换下来的旧对象不再出现在列表里, 但它的句柄仍在向 torrent 服务
 * 请求下载这个文件: 不关掉的话, 暂停列表里的那一份不起作用, 换下来的这份照样在下, 每刷新一次就多一份.
 * 服务换过进程时旧句柄已经失效, 关闭失败只记日志.
 */
internal suspend fun closeReplacedTorrentCaches(previous: List<MediaCache>, current: List<MediaCache>) {
    for (cache in previous) {
        if (cache !is TorrentMediaCacheEngine.TorrentMediaCache) continue
        if (current.any { it === cache }) continue
        try {
            cache.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Failed to close the replaced download ${cache.cacheId}: $e" }
        }
    }
}
