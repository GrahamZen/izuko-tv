/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.content.Context
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 在线播放时下过的数据存在本机磁盘上 (设置里的「边下边播」): 往回拖、拖动预览、再看同一个文件时直接从盘上读, 不再走网络.
 * 网盘直链按解析器给的内容标识 ([PlaybackRequestHints.CACHE_KEY_HEADER]) 存取 (直链本身每次都不一样), 其余在线源按地址.
 *
 * 目录在应用的缓存目录下 (存储紧张时系统先清这里). 容量是这次启动第一次用到时剩余空间的四分之一, 最多 [MAX_BYTES], 满了先删最久没读过的;
 * 算出来不到 [MIN_BYTES] (剩余空间不够) 时不存, 以前存的也删掉.
 *
 * 每存满一段 ([FRAGMENT_BYTES]) 在后台线程把它 fsync 掉: /data 是 ext4 data=ordered, 脏页积多了别的进程的 fsync 要排队等它们落盘,
 * 蓝牙遥控器的按键会因此卡住好几秒 (缓存下载时出过).
 */
@AndroidxOptIn(UnstableApi::class)
object PlaybackDiskCache {
    private var initialized = false
    private var cache: Cache? = null

    /** 这个进程里唯一的实例 (同一个目录只能开一个); 剩余空间不够时为 null. */
    @Synchronized
    fun get(context: Context): Cache? {
        if (!initialized) {
            initialized = true
            cache = open(context.applicationContext)
        }
        return cache
    }

    /** [data] 的内容标识, 解析器没给时为 null. */
    fun keyOf(data: UriMediaData): String? = data.headers[PlaybackRequestHints.CACHE_KEY_HEADER]?.takeIf { it.isNotBlank() }

    /** [cache] 里记着的 [key] 的文件总长 (播放器第一次读到时记下), 没记时为 -1. 会等缓存初始化完, 别在主线程上调. */
    internal fun contentLength(cache: Cache, key: String): Long = ContentMetadata.getContentLength(cache.getContentMetadata(key))

    /**
     * 先读 [cache] 再读 [upstream] 的数据源, 从 [upstream] 读到的同时存进 [cache]. 按请求的内容标识 (`DataSpec.key`) 存取, 没有时按地址.
     */
    internal fun cacheDataSourceFactory(cache: Cache, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(FRAGMENT_BYTES))
            // 读盘出错 (比如系统清了缓存目录) 时这次请求不再读缓存, 照常下载
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(cacheEventLogger)

    /** 在 [directory] 开一个最多 [maxBytes] 的缓存, 存满一段就在后台 fsync. */
    internal fun create(directory: File, maxBytes: Long, databaseProvider: DatabaseProvider): SimpleCache =
        SimpleCache(directory, SyncingEvictor(LeastRecentlyUsedCacheEvictor(maxBytes)), databaseProvider)

    private fun open(context: Context): Cache? {
        val directory = File(context.cacheDir, DIRECTORY)
        val freeBytes = context.cacheDir.usableSpace
        val maxBytes = (freeBytes / 4).coerceAtMost(MAX_BYTES)
        val databaseProvider = StandaloneDatabaseProvider(context)
        if (maxBytes < MIN_BYTES) {
            logger.warn { "Only ${freeBytes / MIB} MiB free, playback disk cache disabled" }
            // 没开实例才能删; 慢, 不在调用方线程上做
            thread(name = "PlaybackDiskCache-delete", isDaemon = true) {
                runCatching { SimpleCache.delete(directory, databaseProvider) }
                    .onFailure { logger.warn { "Failed to delete the playback disk cache: $it" } }
            }
            return null
        }
        return try {
            create(directory, maxBytes, databaseProvider).also {
                logger.info { "Playback disk cache at $directory, up to ${maxBytes / MIB} MiB (${freeBytes / MIB} MiB free)" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to open the playback disk cache" }
            null
        }
    }

    /** 存满一段 (提交一个文件) 就在后台把它落盘, 见 [PlaybackDiskCache]. */
    private class SyncingEvictor(private val delegate: CacheEvictor) : CacheEvictor by delegate {
        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            delegate.onSpanAdded(cache, span)
            val file = span.file ?: return
            syncExecutor.execute { syncFile(file) }
        }
    }

    /**
     * 把 [file] 已写的内容落盘, 返回是否成功. fsync 按文件生效, 不必是写它的那个文件描述符, 所以只读打开:
     * 文件已经被挤出缓存 (删了) 时打开失败, 什么也不做, 也不会把它重新建出来.
     */
    internal fun syncFile(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { it.fd.sync() }
        true
    } catch (e: IOException) {
        false
    }

    private val syncExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PlaybackDiskCache-sync").apply { isDaemon = true }
    }

    private val cacheEventLogger = object : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
            logger.info { "Read ${cachedBytesRead / 1024} KiB from the playback disk cache (${cacheSizeBytes / MIB} MiB stored)" }
        }

        override fun onCacheIgnored(reason: Int) {
            logger.warn { "Playback disk cache ignored for a request (reason $reason)" }
        }
    }

    private const val DIRECTORY = "playback-cache"
    private const val MIB = 1024L * 1024

    /** 一段多大: 存满一段才能读到, 也才落一次盘. */
    internal const val FRAGMENT_BYTES = 4 * MIB

    private const val MIN_BYTES = 256 * MIB
    private const val MAX_BYTES = 4096 * MIB

    private val logger = logger<PlaybackDiskCache>()
}
