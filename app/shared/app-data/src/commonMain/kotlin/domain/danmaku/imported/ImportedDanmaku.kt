/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku.imported

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.danmaku.server.DanmakuServerRegistry
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchInfo
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.Imported
import me.him188.ani.danmaku.api.provider.SimpleDanmakuProvider
import me.him188.ani.danmaku.dandanplay.server.DanmakuFileParser
import me.him188.ani.danmaku.dandanplay.server.DanmakuServerException
import me.him188.ani.danmaku.dandanplay.server.DanmakuServerRouteMissingException
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.Uuid
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * 用户给某一集导入的一批弹幕.
 *
 * @param label 给用户看的来源: 文件名或视频链接
 * @param count 有多少条
 */
@Serializable
class ImportedDanmakuBatch(
    val id: String,
    val label: String,
    val count: Int,
)

@Serializable
private class ImportedDanmakuIndex(val batches: List<ImportedDanmakuBatch> = emptyList())

/**
 * 导入的弹幕按集存成文件. 不放弹幕缓存表: 那张表跟着缓存策略清理, 而且远程有结果时不显示.
 *
 * 每集一个索引文件, 每批弹幕一个内容文件: 一批可能有几万条, 列出有哪几批时不用读它们.
 */
class ImportedDanmakuStore(private val dir: SystemPath) {
    private val lock = Mutex()

    suspend fun list(subjectId: Int, episodeId: Int): List<ImportedDanmakuBatch> = lock.withLock {
        read(subjectId, episodeId)
    }

    suspend fun add(subjectId: Int, episodeId: Int, label: String, contents: List<DanmakuContent>): ImportedDanmakuBatch {
        val batch = ImportedDanmakuBatch(Uuid.randomString(), label, contents.size)
        lock.withLock {
            withContext(Dispatchers.IO_) {
                dir.createDirectories()
                writeAtomically(contentFile(subjectId, episodeId, batch.id), DanmakuFileParser.encode(contents))
            }
            write(subjectId, episodeId, read(subjectId, episodeId) + batch)
        }
        return batch
    }

    /** 这一批的弹幕. 文件丢了或读不出来时为空. */
    suspend fun contents(subjectId: Int, episodeId: Int, batchId: String): List<DanmakuContent> =
        withContext(Dispatchers.IO_) {
            val file = contentFile(subjectId, episodeId, batchId)
            try {
                if (file.exists()) DanmakuFileParser.parse(file.readBytes()).orEmpty() else emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to read ${file.name}" }
                emptyList()
            }
        }

    /** @return 有没有删掉 */
    suspend fun remove(subjectId: Int, episodeId: Int, id: String): Boolean = lock.withLock {
        val current = read(subjectId, episodeId)
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return@withLock false
        write(subjectId, episodeId, next)
        withContext(Dispatchers.IO_) {
            val file = contentFile(subjectId, episodeId, id)
            if (file.exists()) file.delete()
        }
        true
    }

    private fun fileOf(subjectId: Int, episodeId: Int) = dir.resolve("$subjectId-$episodeId.json")

    private fun contentFile(subjectId: Int, episodeId: Int, batchId: String) =
        dir.resolve("$subjectId-$episodeId-$batchId.comments.json")

    private fun writeAtomically(file: SystemPath, text: String) {
        val temp = file.resolveSibling(file.name + ".tmp")
        temp.writeText(text)
        temp.moveTo(file)
    }

    private suspend fun read(subjectId: Int, episodeId: Int): List<ImportedDanmakuBatch> = withContext(Dispatchers.IO_) {
        val file = fileOf(subjectId, episodeId)
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString(ImportedDanmakuIndex.serializer(), file.readText()).batches
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read ${file.name}" }
            emptyList()
        }
    }

    private suspend fun write(subjectId: Int, episodeId: Int, batches: List<ImportedDanmakuBatch>) {
        withContext(Dispatchers.IO_) {
            val file = fileOf(subjectId, episodeId)
            if (batches.isEmpty()) {
                if (file.exists()) file.delete()
                return@withContext
            }
            dir.createDirectories()
            writeAtomically(file, json.encodeToString(ImportedDanmakuIndex.serializer(), ImportedDanmakuIndex(batches)))
        }
    }

    private companion object {
        private val logger = logger<ImportedDanmakuStore>()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * 导入的弹幕当弹幕源: 播放器里统一一项. 这一集没导入过时不出现.
 */
class ImportedDanmakuProvider(
    private val store: ImportedDanmakuStore,
) : SimpleDanmakuProvider {
    override val providerId: DanmakuProviderId get() = DanmakuProviderId.Imported
    override val mainServiceId: DanmakuServiceId get() = DanmakuServiceId.Imported

    override suspend fun fetchAutomatic(request: DanmakuFetchRequest): List<DanmakuFetchResult> =
        results(request.subjectId, request.episodeId)

    /** 这一集导入过的全部弹幕; 没有时为空列表. */
    suspend fun results(subjectId: Int, episodeId: Int): List<DanmakuFetchResult> {
        val batches = store.list(subjectId, episodeId)
        if (batches.isEmpty()) return emptyList()
        val list = batches.flatMap { batch ->
            store.contents(subjectId, episodeId, batch.id).mapIndexed { index, content ->
                DanmakuInfo(
                    id = "imported:${batch.id}:$index",
                    serviceId = mainServiceId,
                    senderId = "",
                    content = content,
                )
            }
        }
        return listOf(
            DanmakuFetchResult(
                providerId = providerId,
                matchInfo = DanmakuMatchInfo(mainServiceId, list.size, DanmakuMatchMethod.ExactId(subjectId, episodeId)),
                list = list,
            ),
        )
    }
}

/** 按链接导入的结果. */
sealed class DanmakuLinkImportResult {
    data class Success(val serverName: String, val batch: ImportedDanmakuBatch) : DanmakuLinkImportResult()

    /** 没有启用中的、能按链接取弹幕的服务器 */
    data object NoServer : DanmakuLinkImportResult()

    /** 这不是链接或视频编号 */
    data object InvalidLink : DanmakuLinkImportResult()

    /** 服务器都回了, 但没有弹幕 */
    data object Empty : DanmakuLinkImportResult()

    data object Timeout : DanmakuLinkImportResult()

    /** @param message 服务器给的说明, 可能没有 */
    data class Failed(val message: String?) : DanmakuLinkImportResult()
}

/**
 * 给某一集导入弹幕: 用户上传的文件, 或一个视频链接 (几台服务器同时问, 用最先拿到弹幕的那个).
 *
 * 应用自己不访问视频网站, 只把链接转给用户选的服务器.
 */
class DanmakuImporter(
    private val store: ImportedDanmakuStore,
    private val servers: DanmakuServerRegistry,
) {
    /** @return 新的一批, 里面一条弹幕也没有时不会保存; 文件不是弹幕文件时为 `null` */
    suspend fun importFile(subjectId: Int, episodeId: Int, fileName: String, bytes: ByteArray): ImportedDanmakuBatch? {
        val contents = withContext(Dispatchers.Default) { DanmakuFileParser.parse(bytes) } ?: return null
        if (contents.isEmpty()) return ImportedDanmakuBatch("", fileName, 0)
        return store.add(subjectId, episodeId, fileName, contents)
    }

    suspend fun importLink(subjectId: Int, episodeId: Int, input: String): DanmakuLinkImportResult {
        val url = normalizeVideoLink(input) ?: return DanmakuLinkImportResult.InvalidLink
        val providers = servers.providers().filter { it.supportsLinkImport }
        if (providers.isEmpty()) return DanmakuLinkImportResult.NoServer

        class Hit(val name: String, val contents: List<DanmakuContent>)

        val winner = CompletableDeferred<Hit?>()
        var failure: String? = null
        var routeMissing = 0
        val lock = Mutex()
        var finished = 0
        val hit = withTimeoutOrNull(LINK_TIMEOUT) {
            coroutineScope {
                val jobs = providers.map { provider ->
                    launch {
                        try {
                            val contents = provider.fetchByLink(url)
                            if (contents.isNotEmpty()) winner.complete(Hit(provider.endpoint.name, contents))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: DanmakuServerRouteMissingException) {
                            lock.withLock { routeMissing++ }
                        } catch (e: DanmakuServerException) {
                            logger.warn { "Link import failed: ${e.message}" }
                            lock.withLock { failure = e.serverMessage ?: failure }
                        } catch (e: Exception) {
                            logger.warn { "Link import failed: ${e::class.simpleName}" }
                        }
                        lock.withLock {
                            if (++finished == providers.size) winner.complete(null)
                        }
                    }
                }
                winner.await().also { jobs.forEach { it.cancel() } }
            }
        }
        return when {
            hit != null -> DanmakuLinkImportResult.Success(hit.name, store.add(subjectId, episodeId, url, hit.contents))
            !winner.isCompleted -> DanmakuLinkImportResult.Timeout
            routeMissing == providers.size -> DanmakuLinkImportResult.NoServer
            failure != null -> DanmakuLinkImportResult.Failed(failure)
            else -> DanmakuLinkImportResult.Empty
        }
    }

    private companion object {
        private val logger = logger<DanmakuImporter>()
        private val LINK_TIMEOUT = 40.seconds
    }
}

private val BV_ID = Regex("""^BV[0-9A-Za-z]{10}$""")
private val AV_ID = Regex("""^av\d+$""", RegexOption.IGNORE_CASE)
private val HTTP_URL = Regex("""https?://[^\s<>"'，。！？）】]+""", RegexOption.IGNORE_CASE)

/**
 * 用户粘贴的内容变成视频链接: 视频编号拼成完整地址 (只是拼字符串); 手机分享出来的「标题 + 链接」取其中的链接.
 * 短链接原样转给服务器, 应用不去访问视频网站.
 */
fun normalizeVideoLink(input: String): String? {
    val text = input.trim()
    if (BV_ID.matches(text) || AV_ID.matches(text)) return "https://www.bilibili.com/video/$text"
    return HTTP_URL.find(text)?.value
}
