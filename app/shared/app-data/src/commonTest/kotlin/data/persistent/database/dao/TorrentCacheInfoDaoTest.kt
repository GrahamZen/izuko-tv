/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 回归测试: 全集种子被多集共用时, 每集对应种子内不同文件, 必须按 (mediaId, subjectId, episodeId) 分别存储,
 * 不能再像旧实现那样只用 mediaId 单键导致互相覆盖 (#播放/索引到错误集数).
 */
@OptIn(TestOnly::class)
class TorrentCacheInfoDaoTest {
    private val mediaId = "full-season-torrent"

    @Test
    fun `same torrent multiple episodes keep distinct files`() = runTest {
        val dao = createMemoryTorrentCacheInfoDao()

        // 同一种子, 不同集 -> 不同文件
        dao.upsertFile(
            TorrentCacheFileEntity(mediaId, subjectId = "100", episodeId = "7", pathInTorrent = "[07].mp4"),
        )
        dao.upsertFile(
            TorrentCacheFileEntity(mediaId, subjectId = "100", episodeId = "9", pathInTorrent = "[09].mp4"),
        )

        assertEquals("[07].mp4", dao.getFile(mediaId, "100", "7")?.pathInTorrent)
        assertEquals("[09].mp4", dao.getFile(mediaId, "100", "9")?.pathInTorrent)
        assertEquals(2, dao.getAllFiles().first().size)
        assertEquals(2, dao.countFilesByMediaId(mediaId))
    }

    @Test
    fun `completing one episode does not affect another`() = runTest {
        val dao = createMemoryTorrentCacheInfoDao()
        dao.upsertFile(TorrentCacheFileEntity(mediaId, "100", "7", pathInTorrent = "[07].mp4", completed = false))
        dao.upsertFile(TorrentCacheFileEntity(mediaId, "100", "9", pathInTorrent = "[09].mp4", completed = false))

        // 第 9 集完成不应把第 7 集也标记完成 / 改文件
        dao.upsertFile(dao.getFile(mediaId, "100", "9")!!.copy(completed = true))

        assertEquals(true, dao.getFile(mediaId, "100", "9")?.completed)
        assertEquals(false, dao.getFile(mediaId, "100", "7")?.completed)
        assertEquals("[07].mp4", dao.getFile(mediaId, "100", "7")?.pathInTorrent)
    }

    @Test
    fun `deleting one episode keeps the others`() = runTest {
        val dao = createMemoryTorrentCacheInfoDao()
        dao.upsertFile(TorrentCacheFileEntity(mediaId, "100", "7", pathInTorrent = "[07].mp4"))
        dao.upsertFile(TorrentCacheFileEntity(mediaId, "100", "9", pathInTorrent = "[09].mp4"))

        dao.deleteFile(mediaId, "100", "7")

        assertNull(dao.getFile(mediaId, "100", "7"))
        assertEquals("[09].mp4", dao.getFile(mediaId, "100", "9")?.pathInTorrent)
        assertEquals(1, dao.countFilesByMediaId(mediaId))
    }
}
