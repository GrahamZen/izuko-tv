/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent.session

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.app.torrent.anitorrent.session.AnitorrentDownloadSession.AnitorrentEntry
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.utils.logging.info
import kotlin.concurrent.Volatile

/**
 * 一个种子里同时缓存着几个文件时 (整季包里缓存了好几集) 按顺序一个个下.
 *
 * 请求 [FilePriority.NORMAL] (缓存) 且没下完的文件里, 只有路径排在最前的那个照请求下 (见 [orderedPriorities]);
 * 其余在 libtorrent 里降到 [FilePriority.LOW], 也不推进下载窗口 ([AnitorrentEntry.isWanted] 为 false):
 * 窗口里的分块会被设截止时间, 不管文件优先级照样下.
 * libtorrent 先挑优先级高的分块, 排在前面的几乎独占带宽; 它没有能下的分块时 (连着的节点都没有、都在请求中),
 * 后面的照样下, 不会卡住. 排在前面的下完、暂停或删掉, 下一个接上, 从头排下载窗口.
 */
internal class TorrentFileDownloadOrder(
    private val session: AnitorrentDownloadSession,
    private val handle: TorrentHandle,
    /** 种子里的全部文件, 按 [AnitorrentEntry.index] 排; 还没拿到种子信息时为 null */
    private val entries: () -> List<AnitorrentEntry>?,
) {
    private val lock = SynchronizedObject()

    /** 各文件 (按 [AnitorrentEntry.index]) 的句柄们请求的最高优先级 */
    private val requested = HashMap<Int, FilePriority>()

    /** 各文件最后设进 libtorrent 的优先级 */
    private val applied = HashMap<Int, FilePriority>()

    /** 在排队等前面的文件的 */
    private val waiting = HashSet<Int>()

    /** 排在最前的文件的 index, 没有时为 -1. 事件线程上不加锁读 */
    @Volatile
    private var firstIndex = -1

    /** [entry] 的句柄们请求的优先级变成了 [priority]. 总会给 [entry] 设一次优先级, 别的文件只在排队的位置变了时设 */
    fun request(entry: AnitorrentEntry, priority: FilePriority): Unit = synchronized(lock) {
        requested[entry.index] = priority
        apply(entries() ?: listOf(entry), changed = entry)
    }

    /** 分块 [pieceIndex] 下完了: 排在最前的文件因此下完时, 下一个接上 */
    fun onPieceFinished(entries: List<AnitorrentEntry>, pieceIndex: Int) {
        val first = entries.getOrNull(firstIndex) ?: return
        if (pieceIndex in first.pieceIndexRange && first.isDownloaded) synchronized(lock) { apply(entries, changed = null) }
    }

    /** 第 [index] 个文件下完了 */
    fun onFileCompleted(entries: List<AnitorrentEntry>, index: Int) {
        if (index == firstIndex) synchronized(lock) { apply(entries, changed = null) }
    }

    private fun apply(entries: List<AnitorrentEntry>, changed: AnitorrentEntry?) {
        val requests = entries.map {
            FileDownloadRequest(it.pathInTorrent, requested[it.index] ?: FilePriority.IGNORE, it.isDownloaded)
        }
        val priorities = orderedPriorities(requests)
        var first: AnitorrentEntry? = null
        for ((i, entry) in entries.withIndex()) {
            val request = requests[i]
            val priority = priorities[i]
            val previous = applied[entry.index]
            if (entry === changed || (previous != null && previous != priority)) {
                handle.setFilePriority(entry.index, priority)
                applied[entry.index] = priority
            }

            val isWaiting = priority != request.priority
            if (request.isQueued && !isWaiting) first = entry
            val wasWaiting = if (isWaiting) !waiting.add(entry.index) else waiting.remove(entry.index)
            entry.isWanted = request.priority != FilePriority.IGNORE && !isWaiting
            // 等到了: 从头排下载窗口 (按分块状态, 排队时以低优先级下好的跳过)
            if (wasWaiting && !isWaiting && entry.isWanted) entry.controller.resume()
        }
        val index = first?.index ?: -1
        if (index != firstIndex) {
            firstIndex = index
            val path = first?.pathInTorrent
            val waitingCount = waiting.size
            if (path != null) {
                session.logger.info { "[${session.handleId}] Download order: $path first, $waitingCount waiting" }
            }
        }
    }

    private val AnitorrentEntry.isDownloaded get() = downloadedBytes.value >= length
}

/** 一个文件的句柄们请求的最高优先级 [priority], 用来排下载顺序 */
internal class FileDownloadRequest(
    val pathInTorrent: String,
    val priority: FilePriority,
    val isDownloaded: Boolean,
) {
    /** 在缓存、要排队 (播放请求的是 [FilePriority.HIGH], 不排队) */
    val isQueued: Boolean get() = priority == FilePriority.NORMAL && !isDownloaded
}

/**
 * [files] 各自实际要设的优先级, 与 [files] 一一对应: 要排队的文件 ([FileDownloadRequest.isQueued]) 里
 * 路径排在最前的照请求下, 其余降到 [FilePriority.LOW]; 别的照请求.
 *
 * 先后按路径的自然顺序 (数字按数值比, 第 2 集排在第 10 集前面): 整季包按集数命名, 路径顺序就是集数顺序.
 */
internal fun orderedPriorities(files: List<FileDownloadRequest>): List<FilePriority> {
    val first = files.indices.filter { files[it].isQueued }
        .minWithOrNull(compareBy(NaturalPathOrder) { files[it].pathInTorrent })
    return files.mapIndexed { i, file -> if (file.isQueued && i != first) FilePriority.LOW else file.priority }
}

/** 数字按数值比的字符串顺序, 其余字符不分大小写; 只差在补零或大小写时按原样比 */
internal object NaturalPathOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val endA = a.digitsEnd(i)
                val endB = b.digitsEnd(j)
                val numberA = a.substring(i, endA).trimStart('0')
                val numberB = b.substring(j, endB).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length - numberB.length
                val byValue = numberA.compareTo(numberB)
                if (byValue != 0) return byValue
                i = endA
                j = endB
            } else {
                val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        val byRest = (a.length - i).compareTo(b.length - j)
        return if (byRest != 0) byRest else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun String.digitsEnd(start: Int): Int {
        var end = start
        while (end < length && this[end].isAsciiDigit()) end++
        return end
    }
}
