/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.app.torrent.api.pieces.PieceList
import me.him188.ani.app.torrent.api.peer.PeerInfo
import me.him188.ani.app.torrent.api.TorrentHandleState
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import org.openani.mediamp.io.SeekableInput
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheEpisodeEntity
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TorrentMediaCacheEngineTest : AbstractTorrentMediaCacheEngineTest() {
    private companion object {
        const val RELATIVE_DIR = "torrent-dir"
        const val PATH_IN_TORRENT = "a.mkv"
        const val EPISODE_ID = "1"
        const val FILE_SIZE = 1024L
    }

    /**
     * 记录说"这一集下完了"还不够, **文件本身也得对得上**才能当成已完成的本地文件。
     *
     * 走那条路就会被包成 [LocalFileMediaCache] —— 它的 `state` 是写死的 COMPLETED、`fileStats` 拿当前文件
     * 长度同时当已下载和总大小, 进度恒等于 1。所以标记一旦是错的, 界面上显示"已完成", 播放器却打开一个
     * 残缺文件, 表现为"已完成但播不了"; 而 `completed` 在上游是粘性的 (`finished = entity.completed || ...`),
     * 错了永远不会自我纠正。
     *
     * 2026-09-20 真机实证: 某一集 `completed=1`、`downloadSize` 与种子里该文件的长度一致 (323,630,457),
     * 磁盘上却只有 318,767,104 —— 少了不到一个 piece 的尾巴, 点开就播不了。
     */
    @Test
    fun `restores as a completed local file only when the file size matches the record`() = runTest {
        createEngine(createTestAnitorrentEngine(backgroundScope.coroutineContext))
        torrentInfoDatabase.upsert(
            TorrentCacheInfoEntity(
                mediaId = testMedia.mediaId,
                torrentData = byteArrayOf(0),
                relativeDir = RELATIVE_DIR,
            ),
        )
        torrentInfoDatabase.upsertEpisode(
            TorrentCacheEpisodeEntity(
                mediaId = testMedia.mediaId,
                episodeId = EPISODE_ID,
                completed = true,
                pathInTorrent = PATH_IN_TORRENT,
                downloadSize = FILE_SIZE,
            ),
        )
        val file = File(testRootDir, "$RELATIVE_DIR/$PATH_IN_TORRENT").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(FILE_SIZE.toInt()))
        }

        // 对得上: 照旧当本地文件恢复, 不必启动种子引擎
        assertIs<LocalFileMediaCache>(cacheEngine.restore(testMedia, metadata(), coroutineContext))

        // 少一个字节就不行了. 这里只钉"不能再当成已完成的本地文件" —— 之后回落到种子那条路
        // (由引擎重新校验分片、把缺的补上), 那条路在这个测试环境里成不成功不是本用例关心的,
        // 所以抛异常也算通过.
        file.writeBytes(ByteArray((FILE_SIZE - 1).toInt()))
        val restored = runCatching { cacheEngine.restore(testMedia, metadata(), coroutineContext) }.getOrNull()
        assertFalse(restored is LocalFileMediaCache)

        // 不可信的完成标记要清掉, 否则它是粘性的: subscribeStats 会以"早就完成了"跳过订阅,
        // 这条记录既不会被修正也再没机会重新标成完成, 每次恢复都白白回落 (2026-09-20 真机撞到)
        assertEquals(false, torrentInfoDatabase.getEpisode(testMedia.mediaId, EPISODE_ID)?.completed)
    }

    /**
     * 老记录可能没记下大小 (`downloadSize` 为 0), 那就无从校验 —— 照旧放行, 不制造新的回归。
     */
    @Test
    fun `records without a recorded size are still restored as local files`() = runTest {
        createEngine(createTestAnitorrentEngine(backgroundScope.coroutineContext))
        torrentInfoDatabase.upsert(
            TorrentCacheInfoEntity(
                mediaId = testMedia.mediaId,
                torrentData = byteArrayOf(0),
                relativeDir = RELATIVE_DIR,
            ),
        )
        torrentInfoDatabase.upsertEpisode(
            TorrentCacheEpisodeEntity(
                mediaId = testMedia.mediaId,
                episodeId = EPISODE_ID,
                completed = true,
                pathInTorrent = PATH_IN_TORRENT,
                downloadSize = 0,
            ),
        )
        File(testRootDir, "$RELATIVE_DIR/$PATH_IN_TORRENT").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(FILE_SIZE.toInt()))
        }

        assertIs<LocalFileMediaCache>(cacheEngine.restore(testMedia, metadata(), coroutineContext))
    }

    /**
     * 删掉一个**从没选中过文件**的缓存时, 按集记录和种子行也得清掉。
     *
     * 原先 [TorrentMediaCacheEngine.TorrentMediaCache.closeAndDeleteFiles] 在这条路上写的是
     * `fileHandle.handle.first() ?: kotlin.run { ...; return }` —— `withServiceRequest` 是 inline,
     * 那个 `return` 是**非局部返回**, 直接退出整个函数, 把末尾的记录清理跳过了。留下的
     * `completed = true` 孤儿行会让之后重新缓存同一集被判成"已完成"而交出不完整文件。
     */
    @Test
    fun `deleting a cache that never selected a file still clears its rows`() = runTest {
        createEngine(createTestAnitorrentEngine(backgroundScope.coroutineContext))
        torrentInfoDatabase.upsert(
            TorrentCacheInfoEntity(
                mediaId = testMedia.mediaId,
                torrentData = byteArrayOf(0),
                relativeDir = RELATIVE_DIR,
            ),
        )
        torrentInfoDatabase.upsertEpisode(
            TorrentCacheEpisodeEntity(
                mediaId = testMedia.mediaId,
                episodeId = EPISODE_ID,
                completed = true,
                pathInTorrent = PATH_IN_TORRENT,
                downloadSize = FILE_SIZE,
            ),
        )

        // handle 恒为 null = 从没选中过文件
        val cache = cacheEngine.TorrentMediaCache(
            origin = testMedia,
            metadata = metadata(),
            fileHandle = TorrentMediaCacheEngine.FileHandle(flowOf(null)),
        )
        cache.closeAndDeleteFiles()

        assertNull(torrentInfoDatabase.getEpisode(testMedia.mediaId, EPISODE_ID))
        // 这是这条资源的最后一集, 种子行也该一起走
        assertNull(torrentInfoDatabase.get(testMedia.mediaId))
    }

    /**
     * **删掉一集不能顺手把它在种子目录里的那个文件也删了** (2026-09-20 回归, 从 v6.0.6 退化)。
     *
     * 同一个种子的其他集还在时会话不会被移除, libtorrent 那份 piece 完成状态跟磁盘无关 ——
     * 绕过会话直接 delete() 文件, 它仍认为那些 piece 都在。于是重新缓存这一集时 isDownloadFinished
     * 立刻为 true (界面秒显"下载完成"), 它也就不去下载、不创建文件, 播放时永远卡在
     * `resolveDownloadingFile` 的 "Still waiting to get file..." 上。
     *
     * 真机复现: 2026-09-20 删掉第六集再重新缓存, 引擎报 `progress=1.0` 而磁盘上根本没那个文件。
     *
     * 正确做法是只关句柄, 把物理回收全权交给会话侧的 `deleteEntireTorrentIfNotInUse` ——
     * 整个种子没人引用了才整目录 reclaim (先失效 fastresume 再删数据)。代价是"删了其中几集
     * 空间不立即回收", 这是 v6.0.6 就做过的取舍。
     */
    @Test
    fun `deleting a cache closes the handle without deleting the file itself`() = runTest {
        createEngine(createTestAnitorrentEngine(backgroundScope.coroutineContext))
        torrentInfoDatabase.upsert(
            TorrentCacheInfoEntity(
                mediaId = testMedia.mediaId,
                torrentData = byteArrayOf(0),
                relativeDir = RELATIVE_DIR,
            ),
        )
        torrentInfoDatabase.upsertEpisode(
            TorrentCacheEpisodeEntity(
                mediaId = testMedia.mediaId,
                episodeId = EPISODE_ID,
                completed = true,
                pathInTorrent = PATH_IN_TORRENT,
                downloadSize = FILE_SIZE,
            ),
        )
        val file = File(testRootDir, "$RELATIVE_DIR/$PATH_IN_TORRENT").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(FILE_SIZE.toInt()))
        }

        var closeAndDeleteCalled = false
        val entry = FakeTorrentFileEntry(file.toKtPath().inSystem)
        val handle = FakeTorrentFileHandle(entry) { closeAndDeleteCalled = true }

        val cache = cacheEngine.TorrentMediaCache(
            origin = testMedia,
            metadata = metadata(),
            fileHandle = TorrentMediaCacheEngine.FileHandle(
                flowOf(TorrentMediaCacheEngine.FileHandle.State(FakeTorrentSession, entry, handle)),
            ),
        )
        cache.closeAndDeleteFiles()

        // 句柄要关: 整目录回收由会话侧按引用计数自己决定
        assertTrue(closeAndDeleteCalled, "closeAndDelete() 没被调用, 整目录回收就没人做了")
        // 文件必须还在 —— 引擎层绝不能自己删单集文件
        assertTrue(file.exists(), "单集文件被删了: 会话的 piece 状态会跟磁盘脱节, 重新缓存秒判完成但永远下不出文件")
        // 记录照样要清掉
        assertNull(torrentInfoDatabase.getEpisode(testMedia.mediaId, EPISODE_ID))
    }

    /**
     * 只为上面那条用例存在: 删除路径只用得到 [TorrentFileHandle.closeAndDelete],
     * 其余成员被访问到就是行为变了, 故意让它们炸。
     */
    private class FakeTorrentFileHandle(
        override val entry: TorrentFileEntry,
        private val onCloseAndDelete: () -> Unit,
    ) : TorrentFileHandle {
        override fun resume(priority: FilePriority) = error("unexpected resume()")
        override fun pause() = error("unexpected pause()")
        override suspend fun close() {}
        override suspend fun closeAndDelete() = onCloseAndDelete()
    }

    /**
     * [resolveFileMaybeEmptyOrNull] 故意返回真实存在的文件: 回归代码正是顺这条路拿到文件
     * 再 `delete()` 的, 这样上面那条用例在回归时会真的看到文件消失而不是报个无关的异常。
     */
    private class FakeTorrentFileEntry(private val file: SystemPath) : TorrentFileEntry {
        override val fileName: String get() = PATH_IN_TORRENT
        override val pathInTorrent: String get() = PATH_IN_TORRENT
        override val length: Long get() = FILE_SIZE
        override fun resolveFileMaybeEmptyOrNull(): SystemPath = file
        override suspend fun resolveFile(): SystemPath = file

        override val fileStats: Flow<TorrentFileEntry.Stats> get() = error("unexpected fileStats")
        override val pieces: PieceList get() = error("unexpected pieces")
        override val supportsStreaming: Boolean get() = error("unexpected supportsStreaming")
        override fun createHandle(): TorrentFileHandle = error("unexpected createHandle()")
        override suspend fun createInput(awaitCoroutineContext: CoroutineContext): SeekableInput =
            error("unexpected createInput()")
    }

    /** 删除路径根本不碰 session, 全部炸掉就行. */
    private object FakeTorrentSession : TorrentSession {
        override val sessionStats: Flow<TorrentSession.Stats?> get() = error("unexpected sessionStats")
        override suspend fun getName(): String = error("unexpected getName()")
        override suspend fun getFiles(): List<TorrentFileEntry> = error("unexpected getFiles()")
        override fun getPeers(): List<PeerInfo> = error("unexpected getPeers()")
        override fun getState(): TorrentHandleState? = error("unexpected getState()")
        override suspend fun close() = error("unexpected close()")
        override suspend fun closeIfNotInUse() = error("unexpected closeIfNotInUse()")
    }

    /**
     * 缓存引擎会起长活协程, 测试体跑完得收掉 —— 不然 runTest 会等满一分钟再报
     * UncompletedCoroutinesError (同 TorrentMediaCacheStorageTest 的做法).
     */
    private fun runTest(
        context: CoroutineContext = EmptyCoroutineContext,
        timeout: Duration = 20.seconds,
        testBody: suspend TestScope.() -> Unit,
    ) = kotlinx.coroutines.test.runTest(context, timeout) {
        try {
            testBody()
        } finally {
            // cacheEngine 是基类的 lateinit, 这里拿不到 isInitialized; 没建过引擎时让它安静地失败,
            // 免得在 finally 里抛出去盖掉测试体真正的错
            runCatching { cacheEngine.close() }
        }
    }

    private fun metadata() = MediaCacheMetadata(
        subjectId = "1",
        episodeId = EPISODE_ID,
        subjectNameCN = "1",
        subjectNames = emptyList(),
        episodeSort = EpisodeSort("02"),
        episodeEp = EpisodeSort("02"),
        episodeName = "测试剧集",
    )
}
