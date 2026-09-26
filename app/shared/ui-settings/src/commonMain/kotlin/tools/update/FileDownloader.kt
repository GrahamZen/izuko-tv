/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tools.update

import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.coroutines.cancellableCoroutineScope
import me.him188.ani.utils.coroutines.withExceptionCollector
import me.him188.ani.utils.httpdownloader.SYNC_EVERY_BYTES_DOWNLOAD
import me.him188.ani.utils.httpdownloader.openPeriodicSyncSink
import me.him188.ani.utils.io.DEFAULT_BUFFER_SIZE
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.bufferedSink
import me.him188.ani.utils.io.bufferedSource
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.length
import me.him188.ani.utils.io.readAndDigest
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 要下载的一个文件.
 *
 * @param sources 同一份内容的几个来源 (如 GitHub 原地址与各个加速镜像), 下载器挑最快的用, 失败换下一个
 * @param sha256 可信的 SHA-256 (十六进制小写, 如 GitHub 接口给的资源摘要). 有它就按它校验, 不看来源旁边的 `.sha1`
 *   —— 那份是从同一个来源取的, 第三方镜像改了包也能把它一起改掉. `null` = 没有, 退回 `.sha1`
 */
class DownloadPackage(
    val fileName: String,
    val sources: List<String>,
    val sha256: String? = null,
)

/**
 * 文件下载器.
 *
 * - 按顺序尝试几个文件 (如本机架构的专包在前、universal 兜底), 每个文件在它的几个来源里挑最快的下
 * - 为下载好的文件做校验 (可信的 SHA-256, 或来源旁边的 `.sha1`)
 * - 提供下载进度 [progress] 与下载状态 [state]
 */
interface FileDownloader {
    /**
     * Range: `[0, 1]`.
     */
    val progress: Flow<Float>
    val state: StateFlow<FileDownloaderState>

    /**
     * 下载 [packages] 里第一个能下成并通过校验的文件到 [saveDir]:
     * - 目标文件已存在且校验通过则跳过下载
     * - 校验不通过就删掉, 换下一个来源
     *
     * @return 下好的文件; 已有别的下载在进行时返回 `null`. 全部失败时抛出最后一个错误.
     */
    suspend fun download(packages: List<DownloadPackage>, saveDir: SystemPath): SystemPath?

    /**
     * 按地址下载: [filenameProvider] 相同的地址当成同一个文件的几个来源, 文件按地址第一次出现的顺序尝试.
     * 见另一个 [download].
     */
    suspend fun download(
        alternativeUrls: List<String>,
        filenameProvider: (url: String) -> String = { it.substringAfterLast("/", "") },
        saveDir: SystemPath,
    ): SystemPath? = download(
        alternativeUrls.groupBy(filenameProvider).map { (name, urls) -> DownloadPackage(name, urls) },
        saveDir,
    )
}

sealed class FileDownloaderState {
    /**
     * [FileDownloader.download] 尚未被调用.
     */
    data object Idle : FileDownloaderState()

    /**
     * 正在挑来源或下载.
     */
    data object Downloading : FileDownloaderState()

    sealed class Completed : FileDownloaderState()

    /**
     * 下载成功并通过校验.
     *
     * @param url 下载的源地址
     * @param file 保存的文件
     */
    data class Succeed(
        val url: String,
        val file: SystemPath,
        val checked: Boolean,
    ) : Completed()

    data class Cancelled(val throwable: CancellationException) : Completed()

    /**
     * 所有文件的所有来源均下载失败, 或其他异常.
     */
    data class Failed(val throwable: Throwable) : Completed()
}

class DefaultFileDownloader(
    private val client: ScopedHttpClient,
) : FileDownloader {
    private companion object {
        private val logger = logger<DefaultFileDownloader>()

        /**
         * 挑来源时每个来源先下这么多. 只比「谁先回应」量不出速度: 大陆常见小请求很快、大文件极慢的来源,
         * 取一段真实内容才看得出差别.
         */
        const val PROBE_BYTES = 256 * 1024

        /**
         * 第一个来源下完探测那段之后, 再等这么久收其他来源的结果, 按实测快慢排; 更慢的不等, 排在后面照样能用.
         */
        val PROBE_GRACE = 1500.milliseconds
    }

    override val state = MutableStateFlow<FileDownloaderState>(FileDownloaderState.Idle)

    private val _progress = MutableStateFlow(0f)
    override val progress: Flow<Float> get() = _progress

    override suspend fun download(packages: List<DownloadPackage>, saveDir: SystemPath): SystemPath? {
        require(packages.isNotEmpty() && packages.all { it.sources.isNotEmpty() }) { "No URLs provided." }

        // Transition to "Downloading" only if not already in a valid state
        state.update {
            if (it != FileDownloaderState.Idle && it !is FileDownloaderState.Completed) {
                return null
            }
            FileDownloaderState.Downloading
        }

        _progress.value = 0f
        withExceptionCollector {
            try {
                for (pkg in packages) {
                    val targetFile = saveDir.resolve(pkg.fileName)
                    if (pkg.sha256 != null && targetFile.exists()) {
                        if (computeLocalChecksum(targetFile, DigestAlgorithm.SHA256) == pkg.sha256) {
                            logger.info { "File ${pkg.fileName} already exists and SHA-256 matches. Skipping download." }
                            state.value = FileDownloaderState.Succeed(pkg.sources.first(), targetFile, checked = true)
                            return targetFile
                        }
                        logger.info { "File ${pkg.fileName} exists but SHA-256 mismatch. Deleting old file..." }
                        withContext(Dispatchers.IO_) { targetFile.delete() }
                    }

                    val ranking = rankSources(pkg.sources.distinct())
                    if (ranking.ordered.isEmpty()) {
                        ranking.failures.forEach { collect(it) }
                        continue
                    }
                    for (url in ranking.ordered) {
                        try {
                            val file = downloadFrom(url, pkg, targetFile)
                            return file
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // 记下错误, 接着试下一个来源. 这时不切到失败态: 界面按失败态显示「重试」(电视上焦点也送过去),
                            // 而后台还在试后面的来源, 用户一按就从头重来, 反而打断了回落
                            collect(e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                state.value = FileDownloaderState.Cancelled(e)
                throw e
            }
            // 所有来源都失败才算失败, 抛出最后一个错误
            state.value = FileDownloaderState.Failed(getLast()!!)
            throwLast()
        }
        // Unreachable in normal flow
        return null
    }

    /**
     * 从 [url] 下载 [pkg] 到 [targetFile] 并校验, 成功时置 [FileDownloaderState.Succeed]. 失败抛出.
     */
    private suspend fun downloadFrom(url: String, pkg: DownloadPackage, targetFile: SystemPath): SystemPath {
        // 没有可信的 SHA-256 才看来源旁边的 .sha1
        val remoteSha1 = if (pkg.sha256 == null) fetchRemoteChecksum(client, url) else null
        if (pkg.sha256 == null) {
            if (remoteSha1 == null) {
                logger.info { "No remote SHA-1 found for: $url" }
            } else if (targetFile.exists()) {
                logger.info { "File ${pkg.fileName} already exists, size=${targetFile.length().bytes}, verifying SHA-1..." }
                if (computeLocalChecksum(targetFile, DigestAlgorithm.SHA1) == remoteSha1) {
                    logger.info { "File ${pkg.fileName} already exists and SHA-1 matches. Skipping download." }
                    state.value = FileDownloaderState.Succeed(url, targetFile, checked = true)
                    return targetFile
                }
                logger.info { "File ${pkg.fileName} exists but SHA-1 mismatch. Deleting old file..." }
                withContext(Dispatchers.IO_) { targetFile.delete() }
            }
        }

        tryDownload(client, url, targetFile)

        val expected = pkg.sha256?.let { DigestAlgorithm.SHA256 to it } ?: remoteSha1?.let { DigestAlgorithm.SHA1 to it }
        if (expected != null) {
            val (algorithm, value) = expected
            if (computeLocalChecksum(targetFile, algorithm) != value) {
                logger.info { "File ${pkg.fileName} $algorithm mismatch after downloading from $url. Deleting file..." }
                withContext(Dispatchers.IO_) { targetFile.delete() }
                throw IllegalStateException("Downloaded file ${pkg.fileName} $algorithm mismatch (from $url).")
            }
        }
        state.value = FileDownloaderState.Succeed(url, targetFile, checked = expected != null)
        return targetFile
    }

    /**
     * @param ordered 按实测快慢排好的来源, 探测时还没回应的排在后面; 探测失败的不在里面
     * @param failures 探测失败的错误
     */
    private class Ranking(val ordered: List<String>, val failures: List<Throwable>)

    /**
     * 所有来源同时各下 [PROBE_BYTES], 按用时排序. 只有一个来源时不探测.
     */
    private suspend fun rankSources(sources: List<String>): Ranking {
        if (sources.size <= 1) return Ranking(sources, emptyList())
        return coroutineScope {
            val results = Channel<Pair<String, Result<Duration>>>(Channel.UNLIMITED)
            val probes = sources.map { url ->
                launch {
                    val result = try {
                        Result.success(probe(url))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                    results.send(url to result)
                }
            }
            val succeeded = mutableListOf<Pair<String, Duration>>()
            val failed = mutableMapOf<String, Throwable>()
            var received = 0
            suspend fun receiveOne() {
                val (url, result) = results.receive()
                received++
                result.onSuccess {
                    succeeded += url to it
                    logger.info { "Probe $url: ${PROBE_BYTES / 1024} KiB in ${it.inWholeMilliseconds} ms" }
                }.onFailure {
                    failed[url] = it
                    logger.info { "Probe $url failed: ${it::class.simpleName}: ${it.message}" }
                }
            }
            while (received < sources.size && succeeded.isEmpty()) receiveOne()
            withTimeoutOrNull(PROBE_GRACE) {
                while (received < sources.size) receiveOne()
            }
            probes.forEach { it.cancel() }

            val fastest = succeeded.sortedBy { it.second }.map { it.first }
            val pending = sources.filter { it !in fastest && it !in failed }
            Ranking(fastest + pending, failed.values.toList()).also {
                logger.info { "Download sources ranked: ${it.ordered}" }
            }
        }
    }

    /**
     * 从 [url] 下开头的 [PROBE_BYTES] (不支持 Range 的来源会发整个文件, 读够就断开), 返回用时. 失败抛出.
     */
    private suspend fun probe(url: String): Duration {
        val mark = TimeSource.Monotonic.markNow()
        client.use {
            prepareGet(url) {
                header(HttpHeaders.Range, "bytes=0-${PROBE_BYTES - 1}")
                timeout {
                    connectTimeoutMillis = 8_000
                    socketTimeoutMillis = 8_000
                    requestTimeoutMillis = 15_000
                }
            }.execute { resp ->
                val input = resp.bodyAsChannel()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read = 0L
                while (read < PROBE_BYTES) {
                    val n = input.readAvailable(buffer)
                    if (n == -1) break
                    read += n
                }
            }
        }
        return mark.elapsedNow()
    }

    /**
     * 获取远程 SHA-1 校验和: 对应的 URL 为 [url].sha1
     */
    private suspend fun fetchRemoteChecksum(client: ScopedHttpClient, url: String): String? {
        return try {
            // The server should serve the checksum as plain text
            // Checksum sidecars may have no line ending, LF, or CRLF; trim all surrounding whitespace.
            // 短超时: 默认的 30 秒读超时会让用户白等半分钟才轮到下一个来源
            client.use {
                get("$url.sha1") {
                    timeout {
                        connectTimeoutMillis = 8_000
                        socketTimeoutMillis = 8_000
                        requestTimeoutMillis = 10_000
                    }
                }.body<String>().trim()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) {
                logger.info { "No remote SHA-1 found for: $url" }
                null
            } else {
                throw e
            }
        }
    }

    /**
     * 下载单个文件并更新进度 [_progress]. 如果下载失败, 抛出异常.
     */
    private suspend fun tryDownload(client: ScopedHttpClient, url: String, file: SystemPath) {
        _progress.value = 0f
        cancellableCoroutineScope {
            logger.info { "Attempting download: $url" }
            try {
                client.use {
                    prepareRequest(url) {
                        timeout {
                            requestTimeoutMillis = 1_000_000
                        }
                    }.execute { resp ->
                        val length = resp.contentLength()
                        logger.info { "Downloading $url to ${file.absolutePath}, length=${(length ?: 0).bytes}" }

                        val downloaded = object {
                            val value = atomic(0L)
                        }

                        val input = resp.bodyAsChannel()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                        if (length != null) {
                            this@cancellableCoroutineScope.launch {
                                while (isActive) {
                                    delay(1.seconds)
                                    _progress.value = downloaded.value.value.toFloat() / length
                                }
                            }
                        }

                        // 边写边刷盘. 不刷的话几十 MB 都积在内存里, 拉起系统安装器时正好撞上内核往盘上刷: 整机读盘排队,
                        // 安装器要多等好几秒才出来, 这期间遥控器按键被系统扣住, 按一下返回就把安装器关了 (2026-09-23 实测)
                        val sink = openPeriodicSyncSink(file.absolutePath, SYNC_EVERY_BYTES_DOWNLOAD)?.buffered()
                            ?: file.bufferedSink()
                        sink.use { output ->
                            while (!input.isClosedForRead) {
                                val read = input.readAvailable(buffer)
                                if (read == -1) {
                                    break
                                }
                                downloaded.value.addAndGet(read.toLong())
                                withContext(Dispatchers.IO_) {
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        _progress.value = 1f

                        logger.info { "Successfully downloaded: $url" }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.info(e) { "Failed to download $url" }
                throw e
            } finally {
                // Cancel any extra coroutines in the same scope
                cancelScope()
            }
        }
    }

    /**
     * 计算 [file] 的 [algorithm] 校验和并返回 Hex 字符串.
     */
    @OptIn(ExperimentalStdlibApi::class)
    private fun computeLocalChecksum(file: SystemPath, algorithm: DigestAlgorithm): String {
        return file.bufferedSource().use {
            it.readAndDigest(algorithm).toHexString()
        }
    }
}
