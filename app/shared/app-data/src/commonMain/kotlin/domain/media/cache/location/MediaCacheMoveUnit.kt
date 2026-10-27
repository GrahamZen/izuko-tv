/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.location

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.utils.io.WriteBackCounter
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.syncFileContents

/**
 * 一起搬的一份缓存: BT 是一个种子目录 (合集的各集一起搬), 在线源是一个任务的文件.
 *
 * @property key 在 [MediaCacheLocations.moving] 里登记用的键, 引擎恢复缓存时按它认出正在搬的
 * @property location 现在所在的位置, `null` = 内部存储
 * @property matches 存储列表里哪些缓存属于它 (认在播的、搬前搬后重新恢复)
 * @property libraryPublic 片库里的视频才有: 在公开的片库 (`true`, 卸载保留) 还是应用目录里的片库 (`false`); 下载的缓存为 `null`
 */
class MediaCacheMoveUnit(
    val key: String,
    val location: String?,
    val bytes: Long,
    val label: String,
    val matches: (MediaCache) -> Boolean,
    val libraryPublic: Boolean? = null,
) {
    /** 已经在 [target] 了 (不用搬) */
    fun isAt(target: MediaCacheMoveTarget): Boolean =
        location == target.location && (libraryPublic == null || target.libraryPublic == null || libraryPublic == target.libraryPublic)
}

/**
 * 搬到哪: 位置 ([location], `null` = 内部存储), 以及片库里的视频放进公开的片库还是应用目录里的片库 ([libraryPublic];
 * `null` = 各自照旧). 下载的缓存只看位置.
 */
@Serializable
data class MediaCacheMoveTarget(val location: String?, val libraryPublic: Boolean? = null)

/**
 * 一种缓存引擎的搬法. 只管文件与记录: 搬之前让缓存停下、搬完重新恢复由 [MediaCacheMover] 做.
 */
interface MediaCacheEngineMover {
    /** 可以搬的全部缓存; 所在的盘没接上的、文件已经不在的不算 */
    suspend fun units(): List<MediaCacheMoveUnit>

    /**
     * 把 [unit] 的文件复制到 [target] 位置并核对, 再改记录, 返回原处要删的文件 (由调用方在缓存重新恢复后删).
     * 改记录之前出错或被取消时把目标处复制了的清掉再抛出, 记录与原文件都不动.
     */
    suspend fun move(unit: MediaCacheMoveUnit, target: String?, onCopied: (bytes: Long) -> Unit): List<Path>

    /** 同上, 搬到 [target]; 只看位置的引擎不用管片库那一半 */
    suspend fun move(unit: MediaCacheMoveUnit, target: MediaCacheMoveTarget, onCopied: (bytes: Long) -> Unit): List<Path> =
        move(unit, target.location, onCopied)

    /** 原处的文件 ([move] 返回的) 删掉之后收拾原处, 如片库删掉空了的条目文件夹 */
    suspend fun afterSourcesDeleted(sources: List<Path>) {}
}

/** 搬的时候缓存记录被删了 (用户在占位上按了删除): 不再搬, 目标处复制了的清掉 */
class MediaCacheMoveAbortedException(message: String) : Exception(message)

/** 复制到一半的文件或目录的名字: 目标处出现它 = 上次没搬完, 下次搬或启动清扫时删掉 */
internal fun movingName(path: Path): Path = Path(path.parent ?: Path(""), path.name + MOVING_SUFFIX)

internal const val MOVING_SUFFIX = ".izuko-moving"

/**
 * 把 [source] (文件或目录) 复制到 [target], 每个文件复制完核对大小. [skip] 选中的文件不复制. 每写一段报 [onCopied].
 * 边写边落盘 (见 [WriteBackCounter]): 一口气往外接盘写几 GB 时不让脏页积起来. 可取消, 已写的部分由调用方清理.
 */
internal suspend fun copyTree(
    source: Path,
    target: Path,
    skip: (Path) -> Boolean = { false },
    onCopied: (bytes: Long) -> Unit,
) {
    val fs = SystemFileSystem
    val metadata = fs.metadataOrNull(source) ?: throw IOException("$source does not exist")
    if (metadata.isDirectory) {
        fs.createDirectories(target)
        for (child in fs.list(source)) {
            if (skip(child)) continue
            copyTree(child, Path(target, child.name), skip, onCopied)
        }
        return
    }
    target.parent?.let { fs.createDirectories(it) }
    val counter = WriteBackCounter()
    fs.source(source).use { input ->
        fs.sink(target).use { output ->
            val buffer = Buffer()
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.readAtMostTo(buffer, CHUNK_BYTES)
                if (read == -1L) break
                output.write(buffer, read)
                onCopied(read)
                if (counter.wrote(read)) {
                    output.flush()
                    syncFileContents(target.inSystem)
                }
            }
            output.flush()
        }
    }
    syncFileContents(target.inSystem)
    val copied = fs.metadataOrNull(target)?.size
    if (copied != metadata.size) throw IOException("Copied $copied bytes of $source to $target, expected ${metadata.size}")
}

private const val CHUNK_BYTES = 256L * 1024
