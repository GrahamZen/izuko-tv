/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 列过的分享文件夹 (几分钟内复用) 与同时列分享文件夹的请求个数上限, 同一个网盘的分享浏览 ([CloudDriveService.shareBrowser]) 共用.
 *
 * 一次搜索里好几个分享搜索源常常找到同一个分享, 各自从根往下逐层列一遍: 同一个文件夹只列一次, 同时来的等前一个列完直接拿结果.
 * 列到的文件带着转存凭证, 只配列它时的分享令牌, 所以只给拿着同一个令牌的请求复用.
 * 一次搜索要列几十个分享、几百个文件夹, 同时只发 [maxConcurrentRequests] 个列目录的请求.
 */
internal class ShareFolderListings(
    private val ttl: Duration = 3.minutes,
    private val maxEntries: Int = 200,
    maxConcurrentRequests: Int = 4,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** 列一个文件夹的结果: [shareToken] 是实际列它时用的分享令牌 (中途可能换过). */
    class Listed(val files: List<DriveFile>, val shareToken: String)

    private class Entry(val listed: Listed, val time: TimeMark)

    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private val folderLocks = List(FOLDER_LOCK_STRIPES) { Mutex() }
    private val requests = Semaphore(maxConcurrentRequests)

    /** 向网盘发一个列目录的请求: 同时最多 [maxConcurrentRequests] 个. */
    suspend fun <T> request(block: suspend () -> T): T = requests.withPermit { block() }

    /**
     * 分享 [shareId] 里文件夹 [folderId] 的内容: [ttl] 内用分享令牌 [shareToken] 列过就直接用, 否则用 [list] 列一遍记下.
     */
    suspend fun getOrList(shareId: String, folderId: String, shareToken: String, list: suspend () -> Listed): List<DriveFile> {
        val key = "$shareId/$folderId"
        cached(key, shareToken)?.let { return it }
        // 同一个文件夹同时来的排队: 后来的等前一个列完, 直接拿它的结果
        return folderLocks[(key.hashCode() and Int.MAX_VALUE) % FOLDER_LOCK_STRIPES].withLock {
            cached(key, shareToken) ?: list().also { put(key, it) }.files
        }
    }

    /** 分享 [shareId] 失效了 (取消、违规): 丢掉列过的, 下次谁来都重新列一遍、看到它没了. */
    suspend fun forget(shareId: String) = lock.withLock {
        entries.keys.removeAll { it.startsWith("$shareId/") }
    }

    private suspend fun cached(key: String, shareToken: String): List<DriveFile>? = lock.withLock {
        val entry = entries[key] ?: return@withLock null
        if (entry.time.elapsedNow() >= ttl) {
            entries.remove(key)
            return@withLock null
        }
        entry.listed.files.takeIf { entry.listed.shareToken == shareToken }
    }

    private suspend fun put(key: String, listed: Listed) = lock.withLock {
        entries.remove(key)
        entries[key] = Entry(listed, timeSource.markNow())
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    private companion object {
        /** 按文件夹分几把锁: 同一个文件夹一定落在同一把上, 不同文件夹偶尔同一把也只是排一下队. */
        const val FOLDER_LOCK_STRIPES = 16
    }
}
