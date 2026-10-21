/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheEpisodeEntity
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.data.persistent.database.dao.createMemoryTorrentCacheInfoDao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TorrentFilesInUseTest {
    private val dir = "/anitorrent/pieces/1"
    private val packA = "pack-a"
    private val packB = "pack-b"

    /** 两次读之间、或删除的两步之间插一段别的操作 */
    private class HookedDao(private val delegate: TorrentCacheInfoDao) : TorrentCacheInfoDao by delegate {
        var beforeSecondRead: (suspend () -> Unit)? = null
        var afterFirstDeletionStep: (suspend () -> Unit)? = null
        private var reads = 0
        private var deletions = 0

        private suspend fun deleted() {
            if (++deletions == 1) afterFirstDeletionStep?.invoke()
        }

        private suspend fun read() {
            if (++reads == 2) beforeSecondRead?.invoke()
        }

        override fun getAll(): Flow<List<TorrentCacheInfoEntity>> = flow {
            read()
            emitAll(delegate.getAll())
        }

        override fun getAllEpisodes(): Flow<List<TorrentCacheEpisodeEntity>> = flow {
            read()
            emitAll(delegate.getAllEpisodes())
        }

        override suspend fun deleteByMediaId(mediaId: String) {
            delegate.deleteByMediaId(mediaId)
            deleted()
        }

        override suspend fun deleteEpisode(mediaId: String, episodeId: String) {
            delegate.deleteEpisode(mediaId, episodeId)
            deleted()
        }
    }

    private suspend fun TorrentCacheInfoDao.addRecord(mediaId: String, episodeId: String, path: String) {
        upsert(TorrentCacheInfoEntity(mediaId = mediaId, torrentData = byteArrayOf(), relativeDir = dir))
        upsertEpisode(TorrentCacheEpisodeEntity(mediaId = mediaId, episodeId = episodeId, pathInTorrent = path))
    }

    @Test
    fun `another resource deleted between the two reads does not look like a record without a file`() = runTest {
        val dao = HookedDao(createMemoryTorrentCacheInfoDao())
        dao.addRecord(packA, "5", "E05.mkv")
        dao.addRecord(packB, "8", "E08.mkv")
        // 第五集的行已删, 正在算还有谁在用; 两次读之间第八集 (另一个资源, 同一目录) 的记录被删掉
        dao.deleteEpisodeRecord(packA, "5")
        dao.beforeSecondRead = { dao.deleteEpisodeRecord(packB, "8") }

        assertEquals(emptySet(), dao.filesInUse(dir))
    }

    @Test
    fun `a read between the two deletion steps sees the resource as gone`() = runTest {
        val dao = createMemoryTorrentCacheInfoDao()
        dao.addRecord(packA, "5", "E05.mkv")
        dao.addRecord(packA, "8", "E08.mkv")
        dao.deleteEpisodeRecord(packA, "5")
        val hooked = HookedDao(dao)
        var seen: Set<String>? = null
        hooked.afterFirstDeletionStep = { seen = dao.filesInUse(dir) }

        hooked.deleteEpisodeRecord(packA, "8")

        assertEquals(emptySet(), seen)
        assertEquals(emptySet(), dao.filesInUse(dir))
    }

    @Test
    fun `the resource row stays while another episode of it remains`() = runTest {
        val dao = createMemoryTorrentCacheInfoDao()
        dao.addRecord(packA, "5", "E05.mkv")
        dao.addRecord(packA, "8", "E08.mkv")

        dao.deleteEpisodeRecord(packA, "5")
        assertNotNull(dao.get(packA))
        assertEquals(setOf("E08.mkv"), dao.filesInUse(dir))

        dao.deleteEpisodeRecord(packA, "8")
        assertNull(dao.get(packA))
        assertEquals(emptySet(), dao.filesInUse(dir))
    }

    @Test
    fun `a record that has not picked its file still keeps everything`() = runTest {
        val dao = createMemoryTorrentCacheInfoDao()
        dao.addRecord(packA, "5", "E05.mkv")
        dao.addRecord(packA, "8", "")
        assertNull(dao.filesInUse(dir))

        // 刚建、剧集行还没写进来的资源
        dao.upsert(TorrentCacheInfoEntity(mediaId = packB, torrentData = byteArrayOf(), relativeDir = dir))
        dao.upsertEpisode(TorrentCacheEpisodeEntity(mediaId = packA, episodeId = "8", pathInTorrent = "E08.mkv"))
        assertNull(dao.filesInUse(dir))
    }
}
