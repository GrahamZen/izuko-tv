/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.DriveVideoInfo
import me.him188.ani.app.domain.media.probe.MediaStreamProbe
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 列目录不给视频信息的网盘 (WebDAV) 上, 读视频文件头 ([MediaStreamProbe], 几十 KB) 补上画面高度与时长,
 * 资源才标得对分辨率、显示得出码率 (见 [CloudDriveMediaSource.mediaFor]); 不然文件名里没写分辨率的一律标成 1080P.
 *
 * 当前这一集的文件马上读, 调用方等它最多 [WAIT]; 其余各集的在后台排队读, 同时最多 [PARALLEL] 个 (经 AList 这类转发的,
 * 每读一个它就要去网盘要一次下载地址), 不让调用方等. 读得慢的先按没有信息给出, 读完记下并通知 [onLateInfo]
 * (条目级查询切集不再查, 要靠它让数据源重查, 见 [DriveVideoInfoRefresh]); 读不出来 (网络错、不认识的格式) 也当没有.
 * 每个文件只读一次: 结果按文件记下 ([save], 存在账号里, 键见 [keyOf]), 文件换了 (大小变了) 才重读.
 *
 * @param stored 记下的信息 ([keyOf] → 信息)
 * @param probe 读一个文件的头; 读不到时为 null
 * @param onLateInfo 给出候选时还没读完的文件后来读到了
 */
internal class DriveVideoInfoProbe(
    private val stored: suspend () -> Map<String, DriveVideoInfo>,
    private val save: suspend (key: String, info: DriveVideoInfo) -> Unit,
    private val probe: suspend (DriveFile) -> MediaStreamProbe.Result?,
    private val onLateInfo: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val inFlight = HashMap<String, Deferred<DriveVideoInfo?>>()
    private val permits = Semaphore(PARALLEL)

    /**
     * [current] (当前这一集的) 与 [others] (其余各集的) 里有信息的那些: 文件 id → 补上画面高度与时长的文件.
     * 已经有这两项的、不是能读头的格式的不读. 只等 [current] 里没读过的, 最多 [WAIT]; 那时已经读完的 [others] 也一并给出.
     */
    suspend fun fill(current: List<DriveFile>, others: List<DriveFile> = emptyList()): Map<String, DriveFile> {
        val urgent = current.filter { it.needsProbe() }.distinctBy { it.fid }
        val urgentIds = urgent.mapTo(HashSet()) { it.fid }
        val rest = others.filter { it.needsProbe() && it.fid !in urgentIds }.distinctBy { it.fid }.take(MAX_FILES)
        if (urgent.isEmpty() && rest.isEmpty()) return emptyMap()
        val known = stored()
        val filled = HashMap<String, DriveFile>()
        val waited = ArrayList<Pair<DriveFile, Deferred<DriveVideoInfo?>>>()
        val queued = ArrayList<Pair<DriveFile, Deferred<DriveVideoInfo?>>>()
        for (file in urgent + rest) {
            val info = known[keyOf(file.fid)]?.takeIf { it.size == file.size }
            when {
                info != null -> filled[file.fid] = file.with(info)
                file.fid in urgentIds -> waited += file to start(file, queue = false)
                else -> queued += file to start(file, queue = true)
            }
        }
        if (waited.isNotEmpty()) withTimeoutOrNull(WAIT) { waited.forEach { it.second.await() } }
        for ((file, job) in waited + queued) {
            if (job.isCompleted) {
                job.await()?.let { filled[file.fid] = file.with(it) }
            } else {
                scope.launch { if (job.await() != null) onLateInfo() }
            }
        }
        return filled
    }

    /**
     * 读 [file] 的头; 同一个文件正在读时共用那一次. 读完 (不论调用方还等不等) 记下结果.
     * [queue] 时排队 (同时最多 [PARALLEL] 个), 否则马上读.
     */
    private fun start(file: DriveFile, queue: Boolean): Deferred<DriveVideoInfo?> = synchronized(lock) {
        inFlight[file.fid]?.let { return it }
        // 先登记再开始: 读得极快时 finally 里的移除也排在登记之后
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                val result = if (queue) permits.withPermit { read(file) } else read(file)
                result?.let { DriveVideoInfo(file.size, heightOf(it.width, it.height), it.durationSeconds ?: 0) }
                    ?.also { save(keyOf(file.fid), it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to probe ${file.fileName}" }
                null
            } finally {
                synchronized(lock) { inFlight.remove(file.fid) }
            }
        }
        inFlight[file.fid] = job
        job.start()
        job
    }

    private suspend fun read(file: DriveFile): MediaStreamProbe.Result? {
        val mark = TimeSource.Monotonic.markNow()
        return withTimeoutOrNull(PROBE_TIMEOUT) { probe(file) }.also { result ->
            logger.info {
                "Probed ${file.fileName} in ${mark.elapsedNow().inWholeMilliseconds} ms: " +
                        (result?.let { "${it.container} ${it.width}x${it.height} ${it.codec} ${it.durationSeconds} s" } ?: "nothing")
            }
        }
    }

    private fun DriveFile.needsProbe(): Boolean =
        isVideo && !dir && videoHeight <= 0 && durationSeconds <= 0 && fileName.substringAfterLast('.', "").lowercase() in PROBED_EXTENSIONS

    private fun DriveFile.with(info: DriveVideoInfo): DriveFile = DriveFile(
        fid = fid,
        fileName = fileName,
        parentFid = parentFid,
        dir = dir,
        size = size,
        updatedAt = updatedAt,
        videoHeight = info.height,
        isVideo = isVideo,
        shareToken = shareToken,
        durationSeconds = info.durationSeconds,
    )

    companion object {
        /** 等当前这一集的结果最多这么久, 再长就先按没有信息给出候选 (开播要等这些候选). */
        val WAIT = 1.seconds

        /** 后台读一个文件最多这么久. */
        private val PROBE_TIMEOUT = 20.seconds

        /** 一次最多读几个文件 (一季的各集). */
        private const val MAX_FILES = 50

        /** 后台排队读的同时读几个. */
        private const val PARALLEL = 3

        /** [MediaStreamProbe] 认得的容器. */
        private val PROBED_EXTENSIONS = setOf("mkv", "webm", "mp4", "m4v", "mov")

        private val logger = logger<DriveVideoInfoProbe>()

        /**
         * 记在账号里的键: 文件 id 的散列. WebDAV 的文件 id 是转义过的整条路径, 中文名一个字就占 9 个字符, 直接当键账号设置会大很多;
         * 散列撞上的机会极小, 撞上了大小也得一样才会用错.
         */
        fun keyOf(fileId: String): String = fileId.hashCode().toUInt().toString(36)

        /** 按 16:9 折算的画面高度: 宽银幕的片子 (3840×1608) 按宽度算, 才归得到它实际的档位 (4K). 读不出为 0. */
        fun heightOf(width: Int?, height: Int?): Int = maxOf(height ?: 0, (width ?: 0) * 9 / 16)
    }
}
