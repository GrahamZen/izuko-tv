/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.datasources.api.MediaExtraFiles
import me.him188.ani.datasources.api.Subtitle
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.list
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeBytes
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.ktor.rawCookieHeader
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.coroutines.cancellation.CancellationException
import org.openani.mediamp.source.Subtitle as MediampSubtitle

/**
 * 缓存的外挂字幕 (网盘视频旁边的 .ass / .srt 等): 建缓存时一并下到本地, 播这份缓存时挂上。
 *
 * 网盘的字幕地址是起播时现取的下载链接 (要带视频那套请求头), 过一阵就失效, 所以必须在建缓存那一刻下 —— 缓存下载器只下视频本身。
 * 存在缓存目录旁边的 `izuko-subtitles/<下载任务 id>/` 里, 不进缓存目录: 那里不属于任何下载任务的文件会被
 * [HttpMediaCacheEngine.deleteUnusedCaches] 清掉。每个任务一个目录: 字幕文件 + 一份 [MANIFEST] (语言、标签、类型)。
 *
 * @param saveDir 缓存目录 ([HttpMediaCacheEngine] 的 saveDir); 字幕放在它的父目录下
 * @param fetch 下载一个字幕 (地址, 请求头) → 内容; 默认用应用的 HTTP 客户端
 */
class CacheSidecarSubtitles(
    saveDir: Path,
    private val fetch: suspend (url: String, headers: Map<String, String>) -> ByteArray = ::fetchWithAppClient,
) {
    private val root: Path = Path(saveDir.parent ?: saveDir, DIR_NAME)

    @Serializable
    private class Entry(val file: String, val mimeType: String? = null, val language: String? = null, val label: String? = null)

    /** 建缓存时调: 后台把 [subtitles] 下到这个任务的目录 (用视频的 [headers]); 失败只记日志, 不影响缓存本身 */
    fun saveInBackground(scope: CoroutineScope, id: String, subtitles: List<MediampSubtitle>, headers: Map<String, String>) {
        if (subtitles.isEmpty()) return
        scope.launch(Dispatchers.IO_) { save(id, subtitles, headers) }
    }

    suspend fun save(id: String, subtitles: List<MediampSubtitle>, headers: Map<String, String>) {
        val dir = dirOf(id)
        val entries = mutableListOf<Entry>()
        subtitles.forEachIndexed { index, sub ->
            val bytes = try {
                fetch(sub.uri, headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to download sidecar subtitle #$index for cache $id" }
                return@forEachIndexed
            }
            val file = "$index.${extensionOf(sub)}"
            withContext(Dispatchers.IO_) {
                dir.inSystem.createDirectories()
                dir.resolve(file).inSystem.writeBytes(bytes)
            }
            entries += Entry(file, sub.mimeType, sub.language, sub.label)
        }
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO_) {
            dir.resolve(MANIFEST).inSystem.writeText(json.encodeToString(ListSerializer(Entry.serializer()), entries))
        }
        logger.info { "Saved ${entries.size} sidecar subtitle(s) for cache $id" }
    }

    /** 播缓存时: [origin] 之外再挂上本地存的字幕 (file://); 没存过就原样返回 */
    fun extraFilesOf(id: String, origin: MediaExtraFiles): MediaExtraFiles {
        val dir = dirOf(id)
        val manifest = dir.resolve(MANIFEST).inSystem
        val entries = try {
            if (!manifest.exists()) return origin
            json.decodeFromString(ListSerializer(Entry.serializer()), manifest.readText())
        } catch (e: Exception) {
            logger.warn(e) { "Unreadable sidecar subtitle manifest of cache $id" }
            return origin
        }
        val local = entries.mapNotNull { e ->
            val file = dir.resolve(e.file).inSystem
            if (!file.exists()) return@mapNotNull null
            Subtitle(uri = "file://" + file.absolutePath, mimeType = e.mimeType, language = e.language, label = e.label)
        }
        if (local.isEmpty()) return origin
        return MediaExtraFiles(subtitles = local + origin.subtitles)
    }

    /** 删缓存时一并删 */
    fun delete(id: String) {
        runCatching { dirOf(id).inSystem.takeIf { it.exists() }?.deleteRecursively() }
            .onFailure { logger.warn(it) { "Failed to delete sidecar subtitles of cache $id" } }
    }

    /** 清掉不属于 [keepIds] 里任何缓存的字幕目录 (缓存被别的途径删掉时留下的) */
    fun prune(keepIds: Set<String>) {
        val rootPath = root.inSystem
        if (!rootPath.exists()) return
        for (child in rootPath.list()) {
            if (child.name in keepIds) continue
            runCatching { child.inSystem.deleteRecursively() }
            logger.info { "Removed sidecar subtitles of a cache that no longer exists: ${child.name}" }
        }
    }

    private fun dirOf(id: String): Path = Path(root, id)

    private companion object {
        const val DIR_NAME = "izuko-subtitles"
        const val MANIFEST = "subtitles.json"
        val logger = logger<CacheSidecarSubtitles>()
        val json = Json { ignoreUnknownKeys = true }

        fun extensionOf(sub: MediampSubtitle): String {
            val fromUri = sub.uri.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            if (fromUri in KNOWN_EXTENSIONS) return fromUri
            return when (sub.mimeType) {
                "text/x-ssa" -> "ass"
                "application/x-subrip" -> "srt"
                "text/vtt" -> "vtt"
                else -> "sub"
            }
        }

        val KNOWN_EXTENSIONS = setOf("ass", "ssa", "srt", "vtt")
    }
}

/**
 * 用应用的 HTTP 客户端下载 (走用户的代理设置), 带上视频的请求头。网盘靠 Cookie 鉴权, 这个头经 [rawCookieHeader] 原样发:
 * 应用的 client 装了 Cookie 插件, 手写的 Cookie 头交给它会被改坏 (网盘回「require login」)。
 */
private suspend fun fetchWithAppClient(url: String, requestHeaders: Map<String, String>): ByteArray =
    KoinPlatform.getKoin().get<HttpClientProvider>().get().use {
        val response = get(url) {
            for ((k, v) in requestHeaders) {
                if (k.equals(HttpHeaders.Cookie, ignoreCase = true)) rawCookieHeader(v) else header(k, v)
            }
        }
        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        response.bodyAsBytes()
    }
