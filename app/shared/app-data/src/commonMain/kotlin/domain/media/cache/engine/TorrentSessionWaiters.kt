/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.torrent.api.TorrentSession

/**
 * 各个种子有几份缓存在等它的种子信息 ([TorrentFileStartup.Phase.AwaitingMetadata]), 与它的下载会话.
 *
 * 同一个种子只开一个下载会话: 整季包的几集、边播边缓存的那一份都共用它, 而 BT 缓存都经 [TorrentMediaCacheEngine] 开会话,
 * 所以在这里按种子数 [begin] / [end] 就数得全. 种子信息还没来、最后一份在等的缓存就不要了 (删除、被刷新替换) 时把会话也关掉:
 * 不然它一直挂在 BT 服务里找节点、连 tracker, 直到应用重启. 等到了种子信息的不归这里管 (打开了文件的由文件句柄关会话).
 *
 * 键是种子数据 ([TorrentMediaCacheEngine] 给的, 同一个种子同一个键).
 */
internal class TorrentSessionWaiters {
    private class Waiting {
        var count = 0
        var session: TorrentSession? = null
    }

    private val lock = Mutex()
    private val waiting = HashMap<String, Waiting>()

    /** 一份缓存开始等 [key] 这个种子的信息 */
    suspend fun begin(key: String) = lock.withLock {
        waiting.getOrPut(key) { Waiting() }.count++
    }

    /**
     * [key] 的下载会话开好了. 已经没人在等时 (开会话的那一下取消不掉, 开好时缓存已经删了) 返回 false: 调用方关掉它.
     */
    suspend fun sessionStarted(key: String, session: TorrentSession): Boolean = lock.withLock {
        val entry = waiting[key] ?: return@withLock false
        entry.session = session
        true
    }

    /**
     * 一份缓存不再等了: 等到了种子信息或启动出错 ([abandoned] 为 false), 或还没等到就不要了 (true).
     * 是最后一份、而且是不要了时返回该关掉的会话; 会话还没开好时为 null (开好时由 [sessionStarted] 告诉调用方).
     */
    suspend fun end(key: String, abandoned: Boolean): TorrentSession? = lock.withLock {
        val entry = waiting[key] ?: return@withLock null
        entry.count--
        if (entry.count > 0) return@withLock null
        waiting.remove(key)
        entry.session.takeIf { abandoned }
    }
}
