/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import io.ktor.client.plugins.ResponseException
import io.ktor.http.Url
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import me.him188.ani.utils.coroutines.REJECTED_LINK_STATUSES
import me.him188.ani.utils.coroutines.RejectionRefresh
import me.him188.ani.utils.coroutines.RejectionRefresh.RefreshResult
import me.him188.ani.utils.coroutines.RejectionRefresh.Verdict
import me.him188.ani.utils.coroutines.update
import me.him188.ani.utils.httpdownloader.m3u.ResolvedMediaPlaylist
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.time.Clock

/** 换来的下载地址与请求时要带的请求头 (同 [DownloadState.url] 与 [DownloadState.requestHeaders]) */
data class DownloadSource(
    val url: String,
    val headers: Map<String, String>,
)

/**
 * 下载地址被服务器拒绝 (见 [REJECTED_LINK_STATUSES]) 时去哪换一个新地址. 带签名的地址几个小时就失效 (网盘直链约 6 小时),
 * 任务停一阵再接着下 (退出应用后再进、为播放让路、暂停) 时落库的地址早已过期.
 *
 * 换不了 (不认得这个任务、重新解析失败) 时返回 `null`, 下载照旧按退避重试.
 */
fun interface DownloadSourceRefresher {
    suspend fun refresh(state: DownloadState): DownloadSource?
}

/** 能在下载中途换地址的下载器, 见 [DownloadSourceRefresher] */
interface RefreshableHttpDownloader {
    var sourceRefresher: DownloadSourceRefresher?
}

/**
 * 换上的新播放列表与原来的不是同一份内容 (分片对不上), 已经下好的分片不能接着用: 这一轮停下, 按新列表从头下
 * (见 [ExpiredSourceRecovery.restarting]). 分段的重试遇到它当场抛出.
 */
internal class DownloadSourceReplacedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * m3u8 换地址要用到的几样下载器内部的东西 (见 [ExpiredSourceRecovery]).
 *
 * @property resolve 取这个地址的播放列表 (主列表会跟到媒体列表)
 * @property segmentsOf 按任务的分片目录把列表排成分片 (都还没下)
 * @property persist 把任务目录里存的那份列表换成这一份 (合并时按它生成本机列表)
 * @property downloadKeys 下任务要用的解密密钥 (已有的不重下)
 * @property keyFilesOf 任务的密钥文件, 同 [SegmentInfo.relativeTempFilePath] 是相对任务所在位置根目录的路径
 * @property deleteFile 删任务所在位置下的一个文件 (相对根目录的路径), 没有就算了
 */
internal class HlsPlaylistReload(
    val resolve: suspend (url: String, options: DownloadOptions) -> ResolvedMediaPlaylist,
    val segmentsOf: (state: DownloadState, playlist: ResolvedMediaPlaylist) -> List<SegmentInfo>,
    val persist: suspend (state: DownloadState, playlist: ResolvedMediaPlaylist) -> Unit,
    val downloadKeys: suspend (state: DownloadState, options: DownloadOptions) -> Unit,
    val keyFilesOf: (state: DownloadState) -> Collection<String>,
    val deleteFile: (state: DownloadState, relativePath: String) -> Unit,
)

/**
 * 分段下载被拒 (见 [REJECTED_LINK_STATUSES]) 时换新地址接着下 (见 [DownloadSourceRefresher]); 什么时候换、什么时候不再换按各处共用的
 * [RejectionRefresh].
 *
 * 换地址只做加法: 被拒不一定是过期 (站点临时限流, 过一会儿自己会好). 换不成、换了也没用时只是不再换, 分段照旧退避重试到上限,
 * 不比不换时更早失败, 也尽量不比不换时多丢已经下好的.
 *
 * - 换上之后马上再试一次, 不等退避; 新地址与请求头记进任务, 下次续传接着用.
 * - 按字节范围下的文件 (MP4 / MKV): 所有分段用同一个地址, 换了地址已经下好的分段照样能用, 所以第一次被拒就去换.
 *   新地址的文件大小 (探一次 Range) 要与原来一样才换上; 不一样当成没换成 (已经下好的分段对不上).
 * - m3u8: 每个分片各有地址 (签名常常是取列表时现发的), 重新取一遍播放列表. 新列表与原来的是同一份内容 (分片数、序号、时长都对得上)
 *   时按序号换上新地址, 已经下好的分片接着用; 对不上时这一轮停下, 按新列表从头下 ([restarting]). 密钥地址跟着列表换, 换上之前先下好.
 *   解析不出新的列表地址时原来的列表地址也重新取一遍. 换了之后原来的密钥文件、多出来的分片文件删掉 (合并完要删空分片目录).
 * - 有的站点每次取列表插播片段的位置都不一样, 换一次列表就是整集从头下. 所以 m3u8 的同一个分片连着被拒
 *   [HLS_REJECTIONS_BEFORE_REFRESH] 次才去换 (偶尔被拒照旧退避重试, 不丢已经下好的); 从头下过之后一片都还没下成就不再从头下;
 *   一次下载里最多从头下 [MAX_RESTARTS] 回.
 *
 * @param probeTotalSize 探新地址的文件多大; 探不出时为空
 */
internal class ExpiredSourceRecovery(
    private val refresher: () -> DownloadSourceRefresher?,
    private val clock: Clock,
    private val getState: suspend (DownloadId) -> DownloadState?,
    private val updateState: suspend (DownloadId, (DownloadState) -> DownloadState) -> Unit,
    private val probeTotalSize: suspend (url: String, options: DownloadOptions) -> Long?,
    private val hls: HlsPlaylistReload? = null,
) {
    /** 正在跑的各次下载 */
    private val jobs = MutableStateFlow(persistentMapOf<DownloadId, Job>())

    /**
     * 跑一次下载 [download] (里面调 [forRun]); 中途换上了内容不同的播放列表 ([DownloadSourceReplacedException]) 就按新列表从头再跑.
     */
    suspend fun restarting(downloadId: DownloadId, download: suspend () -> Unit) {
        val job = Job(downloadId)
        jobs.update { put(downloadId, job) }
        try {
            while (true) {
                try {
                    return download()
                } catch (e: DownloadSourceReplacedException) {
                    logger.info { "Download source of $downloadId was replaced by different content, downloading it again from the start" }
                    job.beforeRestart()
                }
            }
        } finally {
            jobs.update { if (this[downloadId] === job) remove(downloadId) else this }
        }
    }

    /** 删掉 [old] 用过而 [new] 不用的密钥与分片文件 */
    private fun deleteUnusedFiles(old: DownloadState, new: DownloadState) {
        val hls = hls ?: return
        val used = (new.segments.map { it.relativeTempFilePath } + hls.keyFilesOf(new)).toSet()
        for (path in old.segments.map { it.relativeTempFilePath } + hls.keyFilesOf(old)) {
            if (path in used) continue
            try {
                hls.deleteFile(old, path)
            } catch (e: Exception) {
                logger.warn { "Cannot delete $path of ${old.downloadId} left by its old playlist: $e" }
            }
        }
    }

    /**
     * 一轮下载 (一次 [KtorHttpDownloader.downloadSegments]) 用一个.
     *
     * @param requestOptions 这一轮开始时的请求选项 (按 [snapshot] 的请求头)
     * @param optionsOf 按换过地址的任务状态重新算请求选项 (请求头换了)
     */
    fun forRun(
        snapshot: DownloadState,
        requestOptions: DownloadOptions,
        optionsOf: (DownloadState) -> DownloadOptions,
    ): Run {
        val job = jobs.value[snapshot.downloadId] ?: Job(snapshot.downloadId)
        return Run(job, snapshot.requestHeaders, requestOptions, optionsOf).also { job.run = it }
    }

    /**
     * 一次下载 (从开始或继续, 到下完或停下): 换过几次、换来的下成过没有在这一次里算, 重新开始下载时再给新地址机会.
     * 中途按新列表从头下的各轮 ([Run]) 共用一个, 上一轮换过地址之后的冷却接着算.
     */
    inner class Job internal constructor(val downloadId: DownloadId) {
        /** 现在这一轮 */
        @Volatile
        var run: Run? = null

        val policy = RejectionRefresh(nowMillis = { clock.now().toEpochMilliseconds() }) {
            run?.refreshSource() ?: RefreshResult.Unavailable
        }

        /** 按新列表从头下过几回 */
        @Volatile
        var restarts = 0

        /** 上一回从头下之后有没有分片下成过 (没从头下过时为 true) */
        @Volatile
        var progressedSinceRestart = true

        /** 换上内容不同的列表之前的状态: 这一轮停下后按它清旧文件 */
        @Volatile
        var replaced: DownloadState? = null

        /** 各分片连着被拒了几次 (m3u8 用) */
        val rejections = MutableStateFlow(persistentMapOf<Int, Int>())

        /**
         * 这一轮的分片都停下了, 从头下之前收拾: 原来多出来的分片这时才不会还在写; 换上新列表之后才下完的那几片是按旧列表下的,
         * 却记在了新列表同一个序号上, 一并清掉.
         */
        suspend fun beforeRestart() {
            replaced?.let { old ->
                replaced = null
                getState(downloadId)?.let { deleteUnusedFiles(old, it) }
            }
            updateState(downloadId) { state ->
                state.copy(segments = state.segments.map { it.copy(isDownloaded = false) }, downloadedBytes = 0)
            }
            rejections.value = persistentMapOf()
        }
    }

    inner class Run internal constructor(
        private val job: Job,
        private val initialHeaders: Map<String, String>,
        private val initialOptions: DownloadOptions,
        private val optionsOf: (DownloadState) -> DownloadOptions,
    ) {
        private val downloadId get() = job.downloadId

        /**
         * 用 [segment] 现在的地址下一次 ([block] 拿到换过地址的分段与请求选项). 被拒时换地址再试, 别的错原样抛出 (交给退避重试).
         */
        suspend fun <R> attempt(segment: SegmentInfo, block: suspend (SegmentInfo, DownloadOptions) -> R): R {
            var tries = 0
            while (true) {
                val (generation, current, options) = job.policy.read { Triple(it, currentOf(segment), currentOptions()) }
                try {
                    return block(current, options).also {
                        job.policy.confirm(generation, segment.index)
                        job.progressedSinceRestart = true
                        if (segment.index in job.rejections.value) job.rejections.update { remove(segment.index) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val status = e.rejectedStatus() ?: throw e
                    if (!rejectedLongEnough(segment)) throw e
                    when (job.policy.rejected(generation, segment.index)) {
                        Verdict.Retry -> if (++tries >= MAX_IMMEDIATE_RETRIES) throw e
                        Verdict.Backoff -> throw e
                        Verdict.Restart -> throw DownloadSourceReplacedException(
                            "Download source of $downloadId was rejected (HTTP $status) and its fresh playlist is different content", e,
                        )
                    }
                }
            }
        }

        /** 这个分片被拒得够久、该去换地址了: 按范围下的文件第一次被拒就是 (换地址不丢进度), m3u8 要连着被拒几次 */
        private suspend fun rejectedLongEnough(segment: SegmentInfo): Boolean {
            if (getState(downloadId)?.mediaType != MediaType.M3U8) return true
            var times = 0
            job.rejections.update {
                times = (this[segment.index] ?: 0) + 1
                put(segment.index, times)
            }
            return times >= HLS_REJECTIONS_BEFORE_REFRESH
        }

        private suspend fun currentOf(segment: SegmentInfo): SegmentInfo {
            val url = getState(downloadId)?.segments?.firstOrNull { it.index == segment.index }?.url ?: return segment
            return if (url == segment.url) segment else segment.copy(url = url)
        }

        private suspend fun currentOptions(): DownloadOptions {
            val state = getState(downloadId) ?: return initialOptions
            return if (state.requestHeaders == initialHeaders) initialOptions else optionsOf(state)
        }

        internal suspend fun refreshSource(): RefreshResult {
            val state = getState(downloadId) ?: return RefreshResult.Unavailable
            val fresh = try {
                refresher()?.refresh(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to refresh download source of $downloadId" }
                null
            }
            if (state.mediaType == MediaType.M3U8) return refreshPlaylist(state, fresh)
            if (fresh == null || (fresh.url == state.url && fresh.headers == state.requestHeaders)) {
                logger.info { "Download source of $downloadId was rejected and no fresh one is available" }
                return RefreshResult.Unavailable
            }
            state.rangeTotalSize()?.let { expected ->
                val actual = probeTotalSize(fresh.url, optionsOf(state.copy(requestHeaders = fresh.headers)))
                if (actual == null) {
                    logger.info { "Cannot probe the size of the fresh download source of $downloadId, keeping the old one for now" }
                    return RefreshResult.Unavailable
                }
                if (actual != expected) {
                    logger.info { "Fresh download source of $downloadId has $actual bytes, $expected expected: not taking it" }
                    return RefreshResult.Unavailable
                }
            }
            updateState(downloadId) { it.withSource(fresh) }
            logger.info { "Download source of $downloadId was rejected, switched to a fresh one on ${hostOf(fresh.url)}" }
            return RefreshResult.Refreshed()
        }

        /** m3u8: 重新取播放列表 ([fresh] 解析不出时取原来那个地址), 见类注释 */
        private suspend fun refreshPlaylist(state: DownloadState, fresh: DownloadSource?): RefreshResult {
            val hls = hls ?: return RefreshResult.Unavailable
            val source = fresh ?: DownloadSource(state.url, state.requestHeaders)
            val options = optionsOf(state.copy(requestHeaders = source.headers))
            val playlist = try {
                hls.resolve(source.url, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.info { "Download source of $downloadId was rejected and its playlist cannot be fetched again: $e" }
                return RefreshResult.Unavailable
            }
            val reloaded = hls.segmentsOf(state, playlist)
            if (reloaded.isEmpty() || reloaded.map { it.url } == state.segments.map { it.url }) {
                logger.info { "Download source of $downloadId was rejected and its playlist gives the same segment links" }
                return RefreshResult.Unavailable
            }
            val sameContent = reloaded.continuing(state.segments) != null
            if (!sameContent && (job.restarts >= MAX_RESTARTS || !job.progressedSinceRestart)) {
                // 从头下过之后一片都没下成, 被拒就不是过期; 再从头下只会白丢进度
                logger.info {
                    "Fresh playlist of $downloadId is different content, not downloading it again from the start " +
                            "(restarts=${job.restarts}, progressed=${job.progressedSinceRestart})"
                }
                return RefreshResult.Unavailable
            }
            // 密钥地址跟着列表换了: 先下好再换上 (合并时要用), 下不了这次就不换
            try {
                hls.downloadKeys(state.copy(segments = reloaded), options.copy(maxRetriesPerSegment = KEY_RETRIES))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.info { "Cannot download the keys of the fresh playlist of $downloadId, keeping the old one for now: $e" }
                return RefreshResult.Unavailable
            }
            hls.persist(state, playlist)
            updateState(downloadId) { current ->
                // 取列表这会儿别的分片可能下完了: 进度按现在的算
                val continued = if (sameContent) reloaded.continuing(current.segments) else null
                current.copy(
                    url = source.url,
                    requestHeaders = source.headers,
                    segments = continued ?: reloaded,
                    totalSegments = reloaded.size,
                    downloadedBytes = if (continued == null) 0 else current.downloadedBytes,
                )
            }
            if (sameContent) {
                getState(downloadId)?.let { deleteUnusedFiles(state, it) }
            } else {
                job.replaced = state
                job.restarts++
                job.progressedSinceRestart = false
            }
            logger.info {
                "Download source of $downloadId was rejected, switched to a fresh playlist on ${hostOf(source.url)}" +
                        if (sameContent) "" else " with different content (${state.segments.size} -> ${reloaded.size} segments)"
            }
            return RefreshResult.Refreshed(restart = !sameContent)
        }
    }

    private companion object {
        private val logger = logger<ExpiredSourceRecovery>()

        /** 一次重试里换地址后接着再试几次 (防着换来换去不停) */
        const val MAX_IMMEDIATE_RETRIES = 3

        /** 一次下载里最多按新列表从头再下几回 */
        const val MAX_RESTARTS = 2

        /** 换列表时下新密钥最多试几次 (这时别的分片都在等) */
        const val KEY_RETRIES = 3

        /** m3u8 的一个分片连着被拒这么多次才去换列表 (按默认的退避大约一分半钟) */
        const val HLS_REJECTIONS_BEFORE_REFRESH = 8
    }
}

/** 服务器拒绝了这个地址 (见 [REJECTED_LINK_STATUSES]) 时的状态码, 别的错为 `null` */
internal fun Throwable.rejectedStatus(): Int? {
    var e: Throwable? = this
    while (e != null) {
        if (e is ResponseException) return e.response.status.value.takeIf { it in REJECTED_LINK_STATUSES }
        e = e.cause
    }
    return null
}

/**
 * 新播放列表的分片 (本身) 与旧的 [old] 是同一份内容 (分片数、序号、时长都对得上) 时, 给新分片带上旧的下载进度; 对不上时为 `null`.
 */
internal fun List<SegmentInfo>.continuing(old: List<SegmentInfo>): List<SegmentInfo>? {
    if (size != old.size) return null
    return zip(old) { fresh, done ->
        if (fresh.index != done.index || !sameDuration(fresh.durationSeconds, done.durationSeconds)) return null
        fresh.copy(isDownloaded = done.isDownloaded, byteSize = done.byteSize)
    }
}

private fun sameDuration(a: Float?, b: Float?): Boolean =
    if (a == null || b == null) a == b else abs(a - b) < 0.05f

/** 按字节范围分段时整个文件多大; 有不带范围的分段 (整条下) 时为 `null` */
internal fun DownloadState.rangeTotalSize(): Long? {
    if (segments.isEmpty() || segments.any { it.rangeEnd == null }) return null
    return segments.maxOf { it.rangeEnd!! } + 1
}

/** 换成 [source]: 用旧地址的分段一起换 (按范围分段时就是全部) */
internal fun DownloadState.withSource(source: DownloadSource): DownloadState = copy(
    url = source.url,
    requestHeaders = source.headers,
    segments = segments.map { if (it.url == url) it.copy(url = source.url) else it },
)

private fun hostOf(url: String): String = try {
    Url(url).host
} catch (_: Exception) {
    "?"
}
