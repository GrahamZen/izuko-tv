/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 片库在哪: 哪个位置 ([location], 见 MediaCacheLocations, `null` = 内部存储) 上的公开片库 ([public], 卸载保留) 还是应用目录里的片库.
 */
internal data class LibraryPlace(val location: String?, val public: Boolean)

/**
 * 片库里的一个视频: 哪一集 (与缓存元数据里的那条对应) 在哪个片库里、相对片库根目录的路径.
 *
 * @property location 片库所在的位置 (见 MediaCacheLocations), `null` = 内部存储
 * @property public 在公开的片库里; `false` = 应用目录里的片库
 */
@Serializable
internal data class MediaLibraryIndexEntry(
    val mediaId: String,
    val subjectId: String,
    val episodeId: String,
    val location: String?,
    val relativePath: String,
    val public: Boolean = true,
) {
    val place: LibraryPlace get() = LibraryPlace(location, public)
}

/**
 * 片库的本机索引: 片库里每个视频在哪个位置、哪个路径. 片库文件夹才是准的, 这里是扫出来的结果 (见 [scanMediaLibrary]), 随时能重扫重建;
 * 存下来是为了盘没接上 (或「所有文件访问」被关掉) 时还知道那些缓存在哪个位置, 给出「不可用」的占位而不是当成没了.
 *
 * @param file 存在哪 (应用私有目录); `null` = 只在内存里 (测试)
 */
internal class MediaLibraryIndex(private val file: SystemPath?) {
    private val logger = logger<MediaLibraryIndex>()
    private val entries = MutableStateFlow(load())

    val all: List<MediaLibraryIndexEntry> get() = entries.value

    fun find(mediaId: String, metadata: MediaCacheMetadata): MediaLibraryIndexEntry? =
        entries.value.firstOrNull { it.mediaId == mediaId && it.subjectId == metadata.subjectId && it.episodeId == metadata.episodeId }

    /** [places] 这几个片库重扫过了: 它们的条目换成 [scanned], 别的片库 (盘没接上、没列出) 的照留 */
    fun replacePlaces(places: Set<LibraryPlace>, scanned: List<MediaLibraryIndexEntry>) {
        entries.update { old -> old.filter { it.place !in places } + scanned }
        save()
    }

    fun add(entry: MediaLibraryIndexEntry) {
        entries.update { old -> old.filterNot { it.sameEpisodeAs(entry) } + entry }
        save()
    }

    fun remove(entry: MediaLibraryIndexEntry) {
        entries.update { old -> old.filterNot { it.sameEpisodeAs(entry) } }
        save()
    }

    fun removeAll(predicate: (MediaLibraryIndexEntry) -> Boolean) {
        entries.update { old -> old.filterNot(predicate) }
        save()
    }

    private fun MediaLibraryIndexEntry.sameEpisodeAs(other: MediaLibraryIndexEntry) =
        mediaId == other.mediaId && subjectId == other.subjectId && episodeId == other.episodeId

    private fun load(): List<MediaLibraryIndexEntry> {
        val file = file ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            DataStoreJson.decodeFromString(SERIALIZER, file.readText())
        } catch (e: Exception) {
            // 坏了也不要紧: 下次扫描重建, 只是这之前没接上的盘上的那些暂时不显示
            logger.warn(e) { "Media library index is unreadable, starting empty" }
            emptyList()
        }
    }

    private fun save() {
        val file = file ?: return
        try {
            val tmp = file.resolveSibling(file.name + ".tmp")
            tmp.writeText(DataStoreJson.encodeToString(SERIALIZER, entries.value))
            tmp.moveTo(file)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to save the media library index" }
        }
    }

    private companion object {
        val SERIALIZER = ListSerializer(MediaLibraryIndexEntry.serializer())
    }
}
