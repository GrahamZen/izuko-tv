/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.JsonFileCache
import me.him188.ani.app.data.recommendation.RecommendationCatalog
import me.him188.ani.app.data.recommendation.RecommendationCatalogManifest
import me.him188.ani.app.domain.foundation.GitHubFileSources
import me.him188.ani.app.domain.foundation.RemoteFetch
import me.him188.ani.app.domain.foundation.RemoteRefresh
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.list
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readAndDigest
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeBytes
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours

/**
 * 推荐目录 ([RecommendationCatalog]) 的下载与本地缓存, 来自 [GrahamZen/bangumi-sequel-seasons](https://github.com/GrahamZen/bangumi-sequel-seasons)
 * 的 `rec/` 目录: 一份清单 `manifest.json` 加几份按内容摘要命名的文件. 清单带 ETag 定期问一次 (多数时候是 304); 清单变了只下本地没有的文件
 * (主目录不常变的那几片与「看过的人还看了」很少换, 每周换的是统计那一份, 每天换的是近期分片), 下完核对 sha256, 齐了才换上.
 *
 * 文件存在 [dir] 下; 清单引用不到的旧文件换上新清单后删掉.
 */
class RecommendationCatalogRepository(
    private val dir: SystemPath,
    /** 惰性取, 构造期不碰 HttpClientProvider (同 [SeriesGraphTableRepository]). */
    private val client: () -> ScopedHttpClient,
    /** 主目录里名字空着的那些从系列关系图补 (见 [RecommendationCatalog.build]); 返回 (原名, 中文名). */
    private val seriesGraph: suspend () -> SeriesGraphTable?,
    scope: CoroutineScope,
) {
    private val logger = logger<RecommendationCatalogRepository>()
    private val catalog = MutableStateFlow<RecommendationCatalog?>(null)
    private val localLoaded = CompletableDeferred<Unit>()
    private val download = JsonFileCache(dir.resolve(DOWNLOAD_FILE), RecommendationCatalogDownload.serializer())

    /** 什么时候问一次清单 (多数时候是 304); 本地没有目录就不管上次什么时候问过. */
    private val refresh = RemoteRefresh(
        name = "recommendation catalog",
        scope = scope,
        staleAfter = REFRESH_INTERVAL,
        checkEvery = CHECK_INTERVAL,
        refreshOnNetworkCheck = false,
        lastFetchedAt = {
            localLoaded.await()
            download.read()?.checkedAt?.takeIf { catalog.value != null }
        },
        fetch = ::fetch,
    )

    init {
        scope.launch {
            catalog.value = runCatching { loadLocal() }
                .onFailure { if (it is CancellationException) throw it else logger.warn(it) { "recommendation catalog: local copy unreadable" } }
                .getOrNull()
            localLoaded.complete(Unit)
            catalog.value?.let { logger.info { "recommendation catalog loaded: ${it.size} entries (${it.manifest.generated})" } }
        }
    }

    /** 现在能用的目录; 本地还没有 (刚安装、还没下成) 时为 `null`, 推荐照旧问 bangumi. 不等下载. */
    suspend fun current(): RecommendationCatalog? {
        localLoaded.await()
        return catalog.value
    }

    private suspend fun loadLocal(): RecommendationCatalog? = withContext(Dispatchers.IO_) {
        val manifestFile = dir.resolve(MANIFEST_FILE)
        if (!manifestFile.exists()) return@withContext null
        val manifest = json.decodeFromString(RecommendationCatalogManifest.serializer(), manifestFile.readBytes().decodeToString())
        if (manifest.files.any { !dir.resolve(fileName(it)).exists() }) return@withContext null
        build(manifest)
    }

    private suspend fun build(manifest: RecommendationCatalogManifest): RecommendationCatalog {
        val graph = seriesGraph()
        return withContext(Dispatchers.IO_) {
            fun text(entry: RecommendationCatalogManifest.FileEntry) = dir.resolve(fileName(entry)).readBytes().decodeToString()
            RecommendationCatalog.build(
                manifest,
                staticFiles = manifest.static.map(::text),
                stats = text(manifest.stats),
                recent = text(manifest.recent),
                recs = manifest.recs.map(::text),
                names = { id -> graph?.nodeOf(id)?.let { it.name to it.nameCn } },
            )
        }
    }

    /** 问一次清单; 没变或换上了新的返回 true. */
    private suspend fun fetch(): Boolean {
        val saved = download.read() ?: RecommendationCatalogDownload()
        val current = catalog.value
        val fetched = RemoteFetch.firstChanged(
            "recommendation catalog",
            MANIFEST_URLS,
            client,
            previous = RemoteFetch.Revision(saved.source, saved.etag).takeIf { current != null },
        ) { source, bytes ->
            val manifest = runCatching { json.decodeFromString(RecommendationCatalogManifest.serializer(), bytes.decodeToString()) }.getOrNull()
            if (manifest == null || manifest.format != FORMAT) RemoteFetch.reject("not a manifest")
            // CDN 的边缘缓存可能还是更早的一版 (见 GitHubFileSources): 比本机这份旧就换下一个入口
            if (current != null && manifest.generated < current.manifest.generated) {
                RemoteFetch.reject("stale (${manifest.generated} < ${current.manifest.generated})")
            }
            if (!downloadFiles(manifest, preferred = source)) RemoteFetch.reject("files unavailable")
            manifest to bytes
        } ?: return false
        when (fetched) {
            is RemoteFetch.Conditional.NotModified -> download.write(saved.copy(checkedAt = currentTimeMillis()))
            is RemoteFetch.Conditional.Changed -> {
                val (manifest, bytes) = fetched.value
                withContext(Dispatchers.IO_) {
                    val temp = dir.resolve("$MANIFEST_FILE.tmp")
                    temp.writeBytes(bytes)
                    temp.moveTo(dir.resolve(MANIFEST_FILE))
                }
                download.write(RecommendationCatalogDownload(etag = fetched.etag, source = fetched.source, checkedAt = currentTimeMillis()))
                val built = build(manifest)
                catalog.value = built
                removeUnreferenced(manifest)
                logger.info { "recommendation catalog updated from ${fetched.source}: ${built.size} entries (${manifest.generated})" }
            }
        }
        return true
    }

    /** 清单引用的文件本地没有的都下下来 (先试清单所在的入口, 再试别的), 核对 sha256; 有一份下不全就算失败. */
    private suspend fun downloadFiles(manifest: RecommendationCatalogManifest, preferred: String): Boolean {
        withContext(Dispatchers.IO_) { dir.createDirectories() }
        for (entry in manifest.files) {
            val target = dir.resolve(fileName(entry))
            if (withContext(Dispatchers.IO_) { target.exists() }) continue
            val urls = GitHubFileSources.urls(REPOSITORY, entry.path).sortedBy { !sameSource(it, preferred) }
            val fetched = RemoteFetch.firstChanged("recommendation catalog ${entry.path}", urls, client, previous = null) { _, bytes ->
                if (sha256Hex(bytes) != entry.sha256) RemoteFetch.reject("sha256 mismatch")
                bytes
            }
            if (fetched !is RemoteFetch.Conditional.Changed) return false
            withContext(Dispatchers.IO_) {
                val temp = target.resolveSibling(target.name + ".tmp")
                temp.writeBytes(fetched.value)
                temp.moveTo(target)
            }
        }
        return true
    }

    private suspend fun removeUnreferenced(manifest: RecommendationCatalogManifest) = withContext(Dispatchers.IO_) {
        val keep = manifest.files.mapTo(HashSet()) { fileName(it) } + MANIFEST_FILE + DOWNLOAD_FILE
        dir.list().map { it.name }.filter { it !in keep }.forEach { runCatching { dir.resolve(it).delete() } }
    }

    private companion object {
        const val REPOSITORY = "GrahamZen/bangumi-sequel-seasons"
        const val MANIFEST_PATH = "rec/manifest.json"
        const val MANIFEST_FILE = "manifest.json"
        const val DOWNLOAD_FILE = "download.json"

        /** 认得的清单格式 (scripts/rec.py 的 FORMAT). */
        const val FORMAT = 1

        /** 清单的下载入口, 按顺序试; 顺序的理由见 [GitHubFileSources]. */
        val MANIFEST_URLS = GitHubFileSources.urls(REPOSITORY, MANIFEST_PATH)

        /** 多久问一次清单: 近期分片每天换, 半天看一眼 (多数时候是 304). */
        val REFRESH_INTERVAL = 12.hours
        val CHECK_INTERVAL = 3.hours

        val json = Json { ignoreUnknownKeys = true }

        /** 本地文件名: 清单里的路径去掉 `rec/` (路径里带内容摘要, 不会重名). */
        fun fileName(entry: RecommendationCatalogManifest.FileEntry): String = entry.path.substringAfterLast('/')

        /** 两个下载地址是不是同一个入口 (同一个 CDN 节点或同为 raw). */
        fun sameSource(a: String, b: String): Boolean = a.substringBefore("/gh/").substringBefore("/GrahamZen") ==
                b.substringBefore("/gh/").substringBefore("/GrahamZen")

        fun sha256Hex(bytes: ByteArray): String =
            Buffer().apply { write(bytes) }.readAndDigest(DigestAlgorithm.SHA256).joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }
}

/** 推荐目录的下载元数据: 清单的 ETag 与它从哪个入口来 (ETag 只对同一个入口有效)、上次检查的时间. */
@Serializable
data class RecommendationCatalogDownload(
    val etag: String? = null,
    val source: String? = null,
    val checkedAt: Long = 0,
)
