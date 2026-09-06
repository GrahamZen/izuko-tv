/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.cache.DiskCache
import com.github.panpf.sketch.cache.internal.checkDiskCacheDirectory
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.utils.coroutines.IO_
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path

/**
 * 图片下载缓存. 磁盘上与 Sketch 自带的 `LruDiskCache` 是同一套格式 (同目录、同 journal 版本、同文件命名), 两者可以互相接着用.
 *
 * 用它而不用自带那个, 是因为自带的打开太慢: 每个条目在读 journal 时就拼好 4 个文件路径, 几千个条目在没编译优化的包上要好几秒,
 * 而打开之前所有图片请求都排队等着 —— 冷启动时封面全灰的那段时间. 这里的路径用到时才拼 (见 [AniDiskLruCache]),
 * 并把打开进度给出来 ([openProgress]).
 */
internal class AniImageDiskCache(
    context: PlatformContext,
    override val fileSystem: FileSystem,
    override val maxSize: Long,
    directory: Path,
) : DiskCache {
    override val appVersion: Int get() = DiskCache.Builder.DEFAULT_APP_VERSION
    override val internalVersion: Int get() = DiskCache.DownloadBuilder.INTERNAL_VERSION

    override val directory: Path by lazy { checkDiskCacheDirectory(context, directory) }

    private val _openProgress = MutableStateFlow(0f)

    /** 打开 (读 journal) 的进度, 0..1; 打开完成后恒为 1. 第一次读写缓存或读 [size] 时才开始打开. */
    val openProgress: StateFlow<Float> = _openProgress.asStateFlow()

    private val cache: AniDiskLruCache by lazy {
        AniDiskLruCache(
            fileSystem = fileSystem,
            directory = this.directory,
            cleanupDispatcher = Dispatchers.IO_,
            maxSize = maxSize,
            // 与 LruDiskCache 写进 journal 头的版本号一致: 高 16 位 appVersion, 低 16 位 internalVersion
            appVersion = (appVersion shl 16) or internalVersion,
            valueCount = 2, // 数据 + 元数据
            onOpenProgress = { _openProgress.value = it },
        )
    }

    // 同一个键的下载串行: 两个请求同时要同一张图时, 后一个等前一个写完直接读缓存
    private val keyMutexes = LinkedHashMap<String, Mutex>()
    private val keyMutexesLock = SynchronizedObject()

    override val size: Long get() = cache.size()

    override fun openSnapshot(key: String): DiskCache.Snapshot? =
        cache[key.toEntryName()]?.let(::SnapshotImpl)

    override fun openEditor(key: String): DiskCache.Editor? =
        cache.edit(key.toEntryName())?.let(::EditorImpl)

    override fun remove(key: String): Boolean = cache.remove(key.toEntryName())

    override fun clear() {
        runCatching { cache.evictAll() }
    }

    override suspend fun <R> withLock(key: String, action: suspend DiskCache.() -> R): R {
        val name = key.toEntryName()
        val mutex = synchronized(keyMutexesLock) {
            // 取出再放回 = 挪到最新; 超出上限时丢最久没用的那把
            val existing = keyMutexes.remove(name)
            (existing ?: Mutex()).also {
                keyMutexes[name] = it
                if (keyMutexes.size > MAX_KEY_MUTEXES) keyMutexes.remove(keyMutexes.keys.first())
            }
        }
        return mutex.withLock { action(this) }
    }

    override fun close() {
        runCatching { cache.close() }
    }

    override fun toString(): String = "AniImageDiskCache(maxSize=$maxSize, directory='$directory')"

    private class SnapshotImpl(private val snapshot: AniDiskLruCache.Snapshot) : DiskCache.Snapshot {
        override val data: Path = snapshot.file(ENTRY_DATA)
        override val metadata: Path = snapshot.file(ENTRY_METADATA)
        override fun close() = snapshot.close()
        override fun closeAndOpenEditor(): DiskCache.Editor? = snapshot.closeAndEdit()?.let(::EditorImpl)
    }

    private class EditorImpl(private val editor: AniDiskLruCache.Editor) : DiskCache.Editor {
        override val data: Path = editor.file(ENTRY_DATA)
        override val metadata: Path = editor.file(ENTRY_METADATA)
        override fun commit() = editor.commit()
        override fun commitAndOpenSnapshot(): DiskCache.Snapshot? = editor.commitAndGet()?.let(::SnapshotImpl)
        override fun abort() = editor.abort()
    }

    private companion object {
        const val ENTRY_DATA = 0
        const val ENTRY_METADATA = 1

        /** 解码并发只有几路, 同时在下的键远到不了这个数. */
        const val MAX_KEY_MUTEXES = 200

        /** 缓存键 (URL 等) → 磁盘上的条目名, 与 LruDiskCache 相同: 键的 MD5 十六进制. */
        fun String.toEntryName(): String = encodeUtf8().md5().hex()
    }
}
