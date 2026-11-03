/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSidecarSubtitles
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.app.torrent.api.files.isFinished
import me.him188.ani.datasources.api.topic.isVideoFileName
import me.him188.ani.torrent.pikpak.CloudReadiness
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.isDirectory
import me.him188.ani.utils.io.list
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import kotlin.time.Duration
import me.him188.ani.datasources.api.MediaExtraFiles as CachedMediaExtraFiles
import me.him188.ani.datasources.api.Subtitle as CachedMediaSubtitle
import kotlin.time.Duration.Companion.seconds

/**
 * 种子里和视频放在一起的外挂字幕. 有的 BDRip (VCB-Studio 与字幕组合作的那种) 视频里没有字幕轨, 中文字幕是视频旁边同名的
 * `视频名.sc.ass` / `视频名.tc.ass`; 播放器只拿到选中的那个视频, 不会自己去种子里找.
 *
 * 选中视频时按网盘那套规则 ([DriveSidecarSubtitles]: 同名的优先, 没有同名的按集号) 认出配给它的字幕, 范围是视频所在的文件夹与
 * 里面的字幕子文件夹; 跟视频一样高优先级下 (每个几十 KB, 与视频的边界常落在同一个分块里, 开播本来就要先取视频的尾巴),
 * 下好了作为外挂字幕 (`file://`) 交给播放器, 字幕的显示名与语言照网盘那套认, 选字幕时照常分简繁.
 * 云端那种不在本机落盘的 ([CloudReadiness]) 不管.
 */
object TorrentSidecarSubtitles {
    private val logger = logger<TorrentSidecarSubtitles>()

    /** 开播时等字幕下完最多这么久: 播放器要字幕和视频一起就绪, 等太久宁可这次不挂 */
    val AWAIT_TIMEOUT: Duration = 15.seconds

    /** [files] (种子里全部文件, [pathOf] 取在种子里的路径) 里配给视频 [videoPath] 的字幕, 简体在前, 显示名不重复. */
    internal fun <T> match(videoPath: String, files: List<T>, pathOf: (T) -> String): List<DriveSidecarSubtitles.Match<T>> {
        val dir = parentOf(videoPath)
        val nearby = files.filter { file ->
            val parent = parentOf(pathOf(file))
            parent == dir || parent.isNotEmpty() && parentOf(parent) == dir &&
                DriveSidecarSubtitles.isSubtitleFolder(parent.substringAfterLast('/'))
        }
        val matches = DriveSidecarSubtitles.match(
            videoName = videoPath.substringAfterLast('/'),
            files = nearby,
            nameOf = { pathOf(it).substringAfterLast('/') },
            isVideo = { isVideoFileName(pathOf(it)) },
        )
        return DriveSidecarSubtitles.numbered(matches)
    }

    /**
     * 选中视频 [video] 后下它的外挂字幕 (每条建一个文件句柄, 与视频同样高优先级), 最多等 [timeout] 让它们下完.
     * 一条都没有、或是云端的视频时为 null. 拿到的 [Download] 在播放结束 (或打开失败) 时 [Download.close]; 等的途中被取消时自己放掉句柄.
     */
    suspend fun start(video: TorrentFileEntry, files: List<TorrentFileEntry>, timeout: Duration = AWAIT_TIMEOUT): Download? {
        if (video is CloudReadiness) return null
        val matches = match(video.pathInTorrent, files) { it.pathInTorrent }
        if (matches.isEmpty()) return null
        logger.info { "Sidecar subtitles of ${video.pathInTorrent}: ${matches.map { it.file.pathInTorrent }}" }
        val download = Download(
            matches.map { match ->
                match to match.file.createHandle().also { it.resume(FilePriority.HIGH) }
            },
        )
        try {
            download.await(timeout)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { download.close() }
            throw e
        }
        return download
    }

    /** 外挂字幕的下载 (见 [start]). */
    class Download internal constructor(
        private val handles: List<Pair<DriveSidecarSubtitles.Match<TorrentFileEntry>, TorrentFileHandle>>,
    ) {
        private var subtitles: List<Subtitle> = emptyList()

        /** [extraFiles] 之外再挂上下好的字幕 (等的时限内没下完的这次不挂). */
        fun attachTo(extraFiles: MediaExtraFiles): MediaExtraFiles {
            if (subtitles.isEmpty()) return extraFiles
            return MediaExtraFiles(subtitles = subtitles + extraFiles.subtitles)
        }

        internal suspend fun await(timeout: Duration) {
            withTimeoutOrNull(timeout) {
                for ((_, handle) in handles) handle.entry.fileStats.first { it.isDownloadFinished }
            }
            subtitles = handles.mapNotNull { (match, handle) -> finishedSubtitleOf(match) }
            if (subtitles.size < handles.size) {
                logger.info { "${handles.size - subtitles.size} sidecar subtitle(s) not downloaded within $timeout, attached ${subtitles.size}" }
            }
        }

        /** 放掉字幕的文件句柄 (下好的文件留在种子目录里). */
        suspend fun close() = release(handles.map { it.second })
    }

    /**
     * 缓存这一集时把它的外挂字幕也下下来, 不等: 在 [scope] 里等它们下完就放掉句柄, 文件留在种子目录里, 播缓存时
     * [attachFinished] 挂上. 缓存关掉时 [Prefetch.close] (句柄不放掉的话会话一直算有人在用, 删缓存时删不掉).
     * 没有字幕、或是云端的视频时为 null.
     */
    fun prefetch(scope: CoroutineScope, video: TorrentFileEntry, files: List<TorrentFileEntry>): Prefetch? {
        if (video is CloudReadiness) return null
        val matches = match(video.pathInTorrent, files) { it.pathInTorrent }
        if (matches.isEmpty()) return null
        logger.info { "Caching sidecar subtitles of ${video.pathInTorrent}: ${matches.map { it.file.pathInTorrent }}" }
        return Prefetch(scope, matches.map { it.file.createHandle().also { handle -> handle.resume(FilePriority.HIGH) } })
    }

    /** 缓存时在下的外挂字幕 (见 [prefetch]). */
    class Prefetch internal constructor(scope: CoroutineScope, private val handles: List<TorrentFileHandle>) {
        private val lock = Mutex()
        private var released = false

        private val job = scope.launch {
            try {
                for (handle in handles) handle.entry.fileStats.first { it.isDownloadFinished }
            } finally {
                withContext(NonCancellable) { releaseOnce() }
            }
        }

        /** 不再下, 放掉句柄. */
        suspend fun close() {
            job.cancel()
            releaseOnce()
        }

        private suspend fun releaseOnce() {
            lock.withLock {
                if (released) return
                released = true
            }
            release(handles)
        }
    }

    /**
     * 播下好的缓存时: [origin] 之外挂上本机视频文件 [video] 旁边配给它的字幕 (同一个文件夹与里面的字幕子文件夹; 缓存时一起下好的就在那里,
     * 见 [prefetch]). 只读目录、不下载 —— 取缓存媒体在每次搜数据源时都会走, 不能卡在这里. 没下完的字幕文件 (没下到的地方是一串 0) 不挂.
     */
    fun onDisk(video: SystemPath, origin: CachedMediaExtraFiles): CachedMediaExtraFiles {
        val subtitles = try {
            val dir = video.path.parent?.inSystem ?: return origin
            val files = buildList {
                for (child in dir.list()) {
                    if (!child.inSystem.isDirectory()) {
                        add(child.name to child)
                    } else if (DriveSidecarSubtitles.isSubtitleFolder(child.name)) {
                        for (inner in child.inSystem.list()) {
                            if (!inner.inSystem.isDirectory()) add("${child.name}/${inner.name}" to inner)
                        }
                    }
                }
            }
            match(video.name, files) { it.first }
                .filter { isComplete(it.file.second.inSystem) }
                .map { CachedMediaSubtitle(uri = fileUriOf(it.file.second.inSystem.absolutePath), mimeType = it.mimeType, language = it.language, label = it.label) }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to look for sidecar subtitles next to $video" }
            return origin
        }
        if (subtitles.isEmpty()) return origin
        return CachedMediaExtraFiles(subtitles = subtitles + origin.subtitles)
    }

    /** 字幕文件下全了: 文字字幕里不会有一长串 0 (UTF-16 的也只是隔一个字节一个 0). */
    private fun isComplete(file: SystemPath): Boolean {
        var zeros = 0
        for (byte in file.readBytes()) {
            zeros = if (byte.toInt() == 0) zeros + 1 else 0
            if (zeros >= HOLE_ZEROS) return false
        }
        return true
    }

    private const val HOLE_ZEROS = 16

    /** 下好了的字幕文件 ([match]) 作为外挂字幕; 没下好或取不到路径时为 null. */
    private suspend fun finishedSubtitleOf(match: DriveSidecarSubtitles.Match<TorrentFileEntry>): Subtitle? {
        val entry = match.file
        return try {
            if (!entry.isFinished()) return null
            Subtitle(uri = fileUriOf(entry.resolveFile().toString()), mimeType = match.mimeType, language = match.language, label = match.label)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to attach sidecar subtitle ${entry.pathInTorrent}" }
            null
        }
    }

    private suspend fun release(handles: List<TorrentFileHandle>) {
        for (handle in handles) {
            try {
                handle.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to release sidecar subtitle handle ${handle.entry.pathInTorrent}" }
            }
        }
    }

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    /** 本机路径的 `file://` 地址: 种子里的文件名什么字符都有 (`#`、`?`、空格), 按段转义, 播放器取路径时再解开. */
    internal fun fileUriOf(path: String): String = "file://" + path.split('/').joinToString("/") { segment ->
        buildString {
            for (byte in segment.encodeToByteArray()) {
                val c = byte.toInt() and 0xff
                if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~")) append(c.toChar())
                else append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
