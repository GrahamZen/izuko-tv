/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.platform.annotations.TestOnly

/**
 * **种子级**信息. 一个种子 ([mediaId]) 一行, 即使该种子被多个剧集共用也只存一份.
 *
 * 单文件相关的信息 (选中的文件、是否完成、已下载/上传大小) 见 [TorrentCacheFileEntity],
 * 因为同一个种子被多集共用时, 每集对应种子内不同的文件, 必须按集分别存储, 否则会互相覆盖.
 *
 * 种子文件的最终目录是 [MediaSaveDirProvider.saveDir] + [relativeDir] + [TorrentCacheFileEntity.pathInTorrent].
 */
@Entity(
    tableName = "torrent_cache",
    primaryKeys = ["mediaId"],
    indices = [Index(value = ["mediaId"], unique = true)],
)
data class TorrentCacheInfoEntity(
    /**
     * 媒体 ID, 对应 [MediaCacheSave.origin] 中的 [Media.mediaId]
     */
    val mediaId: String,
    /**
     * 种子信息
     */
    val torrentData: ByteArray,
    /**
     * 种子的缓存目录, 相对于 [MediaSaveDirProvider.saveDir] 的相对路径.
     */
    val relativeDir: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as TorrentCacheInfoEntity

        if (mediaId != other.mediaId) return false
        if (!torrentData.contentEquals(other.torrentData)) return false
        if (relativeDir != other.relativeDir) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mediaId.hashCode()
        result = 31 * result + torrentData.contentHashCode()
        result = 31 * result + relativeDir.hashCode()
        return result
    }
}

/**
 * **按集 (按文件)** 的缓存信息. 主键为 ([mediaId], [subjectId], [episodeId]).
 *
 * 同一个种子 ([mediaId]) 被多集共用时, 每集在种子内对应不同的视频文件 ([pathInTorrent]),
 * 完成状态 ([completed]) 与下载/上传量也各自独立, 因此必须按集分别一行.
 */
@Entity(
    tableName = "torrent_cache_file",
    primaryKeys = ["mediaId", "subjectId", "episodeId"],
)
data class TorrentCacheFileEntity(
    /**
     * 媒体 ID, 对应 [TorrentCacheInfoEntity.mediaId]
     */
    val mediaId: String,
    /**
     * 条目 ID, 对应 [me.him188.ani.datasources.api.MediaCacheMetadata.subjectId]
     */
    val subjectId: String,
    /**
     * 剧集 ID, 对应 [me.him188.ani.datasources.api.MediaCacheMetadata.episodeId]
     */
    val episodeId: String,
    /**
     * 该集在种子内对应的文件.
     * @see TorrentFileEntry.pathInTorrent
     */
    val pathInTorrent: String = "",
    /**
     * 该集对应的文件是否已经下载完并达到分享率.
     */
    val completed: Boolean = false,
    /**
     * 该集对应文件已下载的大小, 字节.
     */
    val downloadSize: Long = 0,
    /**
     * 已上传的大小, 字节.
     */
    val uploadSize: Long = 0,
)

/**
 * 用于跨表读取: 按集的完成状态 + 种子目录, 给 "是否所有缓存都已完成" 之类的判断使用.
 */
data class TorrentCacheFileWithDir(
    val mediaId: String,
    val pathInTorrent: String,
    val completed: Boolean,
    val relativeDir: String?,
)

@Dao
interface TorrentCacheInfoDao {
    // region 种子级 (torrent_cache)

    @Query("""SELECT * FROM torrent_cache""")
    fun getAll(): Flow<List<TorrentCacheInfoEntity>>

    @Query("""SELECT * FROM torrent_cache WHERE mediaId = :mediaId LIMIT 1""")
    suspend fun get(mediaId: String): TorrentCacheInfoEntity?

    @Query("""SELECT * FROM torrent_cache WHERE mediaId in (:mediaIds)""")
    suspend fun batchGet(mediaIds: List<String>): List<TorrentCacheInfoEntity>

    @Upsert
    suspend fun upsert(item: TorrentCacheInfoEntity)

    @Query("""DELETE FROM torrent_cache WHERE mediaId = :mediaId""")
    suspend fun deleteByMediaId(mediaId: String)

    // endregion

    // region 按集 (torrent_cache_file)

    @Query("""SELECT * FROM torrent_cache_file""")
    fun getAllFiles(): Flow<List<TorrentCacheFileEntity>>

    @Query(
        """SELECT * FROM torrent_cache_file
            WHERE mediaId = :mediaId AND subjectId = :subjectId AND episodeId = :episodeId LIMIT 1""",
    )
    suspend fun getFile(mediaId: String, subjectId: String, episodeId: String): TorrentCacheFileEntity?

    @Upsert
    suspend fun upsertFile(item: TorrentCacheFileEntity)

    @Query(
        """DELETE FROM torrent_cache_file
            WHERE mediaId = :mediaId AND subjectId = :subjectId AND episodeId = :episodeId""",
    )
    suspend fun deleteFile(mediaId: String, subjectId: String, episodeId: String)

    @Query("""SELECT COUNT(*) FROM torrent_cache_file WHERE mediaId = :mediaId""")
    suspend fun countFilesByMediaId(mediaId: String): Int

    /**
     * 按集的完成状态 + 对应种子的 relativeDir (LEFT JOIN). 供 "是否全部完成" 判断使用.
     */
    @Query(
        """SELECT f.mediaId AS mediaId, f.pathInTorrent AS pathInTorrent, f.completed AS completed,
                t.relativeDir AS relativeDir
            FROM torrent_cache_file f LEFT JOIN torrent_cache t ON f.mediaId = t.mediaId""",
    )
    fun getAllFilesWithDir(): Flow<List<TorrentCacheFileWithDir>>

    // endregion
}

@TestOnly
fun createMemoryTorrentCacheInfoDao(): TorrentCacheInfoDao {
    return object : TorrentCacheInfoDao {
        private val store = MemoryDataStore(listOf<TorrentCacheInfoEntity>())
        private val fileStore = MemoryDataStore(listOf<TorrentCacheFileEntity>())

        override fun getAll(): Flow<List<TorrentCacheInfoEntity>> {
            return store.data
        }

        override suspend fun get(mediaId: String): TorrentCacheInfoEntity? {
            return store.data.firstOrNull()?.find { it.mediaId == mediaId }
        }

        override suspend fun batchGet(mediaIds: List<String>): List<TorrentCacheInfoEntity> {
            return store.data.firstOrNull()?.filter { it.mediaId in mediaIds } ?: emptyList()
        }

        override suspend fun upsert(item: TorrentCacheInfoEntity) {
            store.updateData {
                val existing = it.indexOfFirst { e -> e.mediaId == item.mediaId }
                if (existing >= 0) {
                    it.toMutableList().apply { this[existing] = item }
                } else {
                    it + item
                }
            }
        }

        override suspend fun deleteByMediaId(mediaId: String) {
            store.updateData {
                it.filter { e -> e.mediaId != mediaId }
            }
        }

        override fun getAllFiles(): Flow<List<TorrentCacheFileEntity>> {
            return fileStore.data
        }

        override suspend fun getFile(
            mediaId: String,
            subjectId: String,
            episodeId: String
        ): TorrentCacheFileEntity? {
            return fileStore.data.firstOrNull()?.find {
                it.mediaId == mediaId && it.subjectId == subjectId && it.episodeId == episodeId
            }
        }

        override suspend fun upsertFile(item: TorrentCacheFileEntity) {
            fileStore.updateData {
                val existing = it.indexOfFirst { e ->
                    e.mediaId == item.mediaId && e.subjectId == item.subjectId && e.episodeId == item.episodeId
                }
                if (existing >= 0) {
                    it.toMutableList().apply { this[existing] = item }
                } else {
                    it + item
                }
            }
        }

        override suspend fun deleteFile(mediaId: String, subjectId: String, episodeId: String) {
            fileStore.updateData {
                it.filterNot { e ->
                    e.mediaId == mediaId && e.subjectId == subjectId && e.episodeId == episodeId
                }
            }
        }

        override suspend fun countFilesByMediaId(mediaId: String): Int {
            return fileStore.data.firstOrNull()?.count { it.mediaId == mediaId } ?: 0
        }

        override fun getAllFilesWithDir(): Flow<List<TorrentCacheFileWithDir>> {
            return kotlinx.coroutines.flow.combine(fileStore.data, store.data) { files, infos ->
                files.map { f ->
                    TorrentCacheFileWithDir(
                        mediaId = f.mediaId,
                        pathInTorrent = f.pathInTorrent,
                        completed = f.completed,
                        relativeDir = infos.find { it.mediaId == f.mediaId }?.relativeDir,
                    )
                }
            }
        }
    }
}
