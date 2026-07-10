/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.cache.storage.TorrentMediaCacheStorage
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.torrent.anitorrent.session.AnitorrentDownloadSession
import me.him188.ani.app.torrent.api.TorrentDownloader
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.unwrapCached
import me.him188.ani.utils.io.SystemPath
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * @see TorrentMediaCacheStorage
 */
class TorrentMediaCacheStorageTest : AbstractTorrentMediaCacheEngineTest() {

    private val metadataStore: DataStore<List<MediaCacheSave>> = MemoryDataStore(emptyList())
    private val storages = mutableListOf<TorrentMediaCacheStorage>()

    private val metadataFlow = metadataStore.data
        .map { list ->
            list
                .filter { it.engine == CacheEngineKey }
                .sortedBy { it.origin.mediaId } // consistent stable order
        }


    private fun cleanup() {
        storages.forEach { it.close() }
        storages.clear()
    }

    private fun runTest(
        context: CoroutineContext = EmptyCoroutineContext,
        timeout: Duration = 5.seconds,
        testBody: suspend TestScope.() -> Unit
    ) = kotlinx.coroutines.test.runTest(context, timeout) {
        try {
            testBody()
        } finally {
            cleanup()
        }
    }

    /**
     * 建一个"会话一启动就报告校验完成"的 storage. 绝大多数用例都只需要这个 —— 少了
     * [AnitorrentDownloadSession.onTorrentChecked], `cache()` 会一直等文件信息直到超时.
     */
    private fun TestScope.createReadyStorage(): TorrentMediaCacheStorage = createStorage(
        createEngine(
            onDownloadStarted = {
                it.onTorrentChecked()
            },
        ),
    )

    private fun TestScope.createStorage(engine: TorrentMediaCacheEngine = createEngine()): TorrentMediaCacheStorage {
        return TorrentMediaCacheStorage(
            CACHE_MEDIA_SOURCE_ID,
            metadataStore,
            engine.also { cacheEngine = it },
            MutableStateFlow(1.2f),
            "本地",
            this.coroutineContext,
        ).also {
            storages.add(it)
        }
    }

    private fun mediaCacheMetadata() = MediaCacheMetadata(
        subjectId = "1",
        episodeId = "1",
        subjectNameCN = "1",
        subjectNames = emptyList(),
        episodeSort = EpisodeSort("02"),
        episodeEp = EpisodeSort("02"),
        episodeName = "测试剧集",
    )

    ///////////////////////////////////////////////////////////////////////////
    // simple create, restore, find
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `create cache then get from listFlow`() = runTest {
        val storage = createReadyStorage()

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        assertSame(cache, storage.listFlow.first().single())
        assertNotNull(torrentInfoDatabase.get("dmhy.2"))
    }

    private suspend fun TorrentMediaCacheStorage.cache(
        media: DefaultMedia,
        metadata: MediaCacheMetadata,
        resume: Boolean
    ) = cache(
        media,
        metadata,
        EpisodeMetadata("Test", null, EpisodeSort(1)), // doesn't matter, as we only test BT engine.
        resume,
    )

    @Test
    fun `create cache saves metadata`() = runTest {
        val storage = createReadyStorage()

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)

        metadataFlow.first().filter { it.origin.mediaId == cache.origin.mediaId }.run {
            assertEquals(1, size)
            assertEquals(cache.origin.mediaId, first().origin.mediaId)
        }

        assertSame(cache, storage.listFlow.first().single())
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
    }

    @Test
    fun `create same cache twice`() = runTest {
        val storage = createReadyStorage()

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        assertSame(cache, storage.listFlow.first().single())
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
        assertSame(cache, storage.cache(testMedia, mediaCacheMetadata(), resume = false))
        assertSame(cache, storage.listFlow.first().single())
        assertEquals(1, torrentInfoDatabase.getAll().first().size)
    }

    @Test
    fun `create and delete`() = runTest {
        val storage = createReadyStorage()

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)

        metadataFlow.first().filter { it.origin.mediaId == cache.origin.mediaId }.run {
            assertEquals(1, size)
            assertEquals(cache.origin.mediaId, first().origin.mediaId)
        }
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))

        assertNotNull(cache.fileHandle.state.first()).run {
            assertNotNull(handle)
            assertNotNull(entry)
        }

        assertEquals(cache, storage.listFlow.first().single())
        val relativeDir = torrentInfoDatabase.get(testMedia.mediaId)!!.relativeDir
        val torrentDirectory = File(testRootDir, relativeDir.trimStart('/', '\\'))
        // 生产上该目录由 libtorrent 原生侧写数据时创建, 测试的 TestTorrentManagerSession 不落盘, 手动模拟.
        torrentDirectory.mkdirs()
        File(torrentDirectory, "test.mkv").writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue(torrentDirectory.exists())

        assertEquals(true, storage.delete(cache))

        // 逻辑删除 (list/datastore + 按集的文件行) 必须在 delete 返回前全部完成, 不等 torrent 服务:
        // 文件行若交给后台, 进程退出/scope 取消会留下 completed=true 的孤儿行,
        // 重缓存不覆盖已有行, 会被判"已完成"而交出不完整文件.
        assertNull(
            torrentInfoDatabase.getFile(testMedia.mediaId, mediaCacheMetadata().subjectId, mediaCacheMetadata().episodeId),
        )
        metadataFlow.first().filter { it.origin.mediaId == cache.origin.mediaId }.run {
            assertEquals(0, size)
        }
        assertEquals(null, storage.listFlow.first().firstOrNull())
        // 种子级父行按 mediaId 记账, 生命周期完全在阶段 1 的 dataLock 内, 同样在返回前删完:
        // 留给只持有旧目录锁的阶段 2 去删, 会与"同一 mediaId 换目录重新缓存"竞争.
        assertNull(torrentInfoDatabase.get(testMedia.mediaId))

        // 测试中直接等待同一个幂等清理入口, 避免用轮询猜测 storage 后台 Job 的完成时间.
        cache.closeAndDeleteFiles()

        assertNull(torrentInfoDatabase.get(testMedia.mediaId))
        assertFalse(torrentDirectory.exists())
    }

    @Test
    fun `delete keeps torrent directory while session is still in use`() = runTest {
        var session: AnitorrentDownloadSession? = null
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    session = it
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        assertNotNull(cache.fileHandle.state.first()).run {
            assertNotNull(handle)
        }

        val relativeDir = torrentInfoDatabase.get(testMedia.mediaId)!!.relativeDir
        val torrentDirectory = File(testRootDir, relativeDir.trimStart('/', '\\'))
        torrentDirectory.mkdirs()
        File(torrentDirectory, "test.mkv").writeBytes(byteArrayOf(1, 2, 3, 4))

        // 模拟同种子的磁力流播: 会话上还挂着别的句柄, 会话侧整目录删除会跳过.
        // 此时绝不能从活会话脚下强删目录: 活会话会重建文件/fastresume, 且随后的重缓存会
        // 复用它的内存 piece 状态秒判完成, 交出稀疏坏文件. 目录必须原样保留.
        val extraHandle = assertNotNull(session).getFiles().first().createHandle()

        assertEquals(true, storage.delete(cache))
        // 逻辑删除 (按集的文件行 + 种子级父行) 在 delete 返回前完成,
        // 即使物理清理被会话占用挡住也不残留假完成行.
        assertNull(
            torrentInfoDatabase.getFile(testMedia.mediaId, mediaCacheMetadata().subjectId, mediaCacheMetadata().episodeId),
        )
        assertNull(torrentInfoDatabase.get(testMedia.mediaId))
        cache.closeAndDeleteFiles()

        assertTrue(torrentDirectory.exists(), "会话仍被占用时必须保留目录, 不能从活会话脚下删除")

        // 期间重新缓存: 目录重新有主, 启动清扫必须放弃回收.
        val recreated = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        runCurrent()
        cacheEngine.deleteUnusedCaches(listOf(recreated))
        assertTrue(torrentDirectory.exists(), "目录重新被引用后启动清扫必须放弃回收")

        // 重缓存也删掉、最后一个句柄关闭后, 无主目录由下次启动清扫回收.
        assertEquals(true, storage.delete(recreated))
        recreated.closeAndDeleteFiles()
        extraHandle.close()
        cacheEngine.deleteUnusedCaches(emptyList())
        assertFalse(torrentDirectory.exists(), "无主目录由启动清扫回收")
    }

    @Test
    fun `stale physical cleanup must not touch rows of a cache re-created in another directory`() = runTest {
        val storage = createReadyStorage()

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        runCurrent()
        val oldDir = torrentInfoDatabase.get(testMedia.mediaId)!!.relativeDir

        assertEquals(true, storage.delete(cache))

        // 阶段 2 还没跑; 期间同一 mediaId 换了另一个种子 (因而是另一个目录) 重新缓存.
        val updatedMedia = createTestDefaultMedia(
            mediaId = testMedia.mediaId,
            mediaSourceId = testMedia.mediaSourceId,
            download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:2"),
            originalUrl = testMedia.originalUrl,
            originalTitle = testMedia.originalTitle,
            publishedTime = testMedia.publishedTime + 1,
            properties = testMedia.properties,
            episodeRange = EpisodeRange.single(EpisodeSort(2)),
            kind = MediaSourceKind.BitTorrent,
            location = MediaSourceLocation.Online,
        )
        val recreated = storage.cache(updatedMedia, mediaCacheMetadata(), resume = false)
        runCurrent()
        val newDir = torrentInfoDatabase.get(testMedia.mediaId)!!.relativeDir
        assertNotEquals(oldDir, newDir, "换种子后目录必须变化, 否则测不到本竞态")

        // 旧缓存的阶段 2 只负责回收旧目录, 绝不能动数据库行: 它只持有旧目录锁, 与新缓存的
        // 落库 (dataLock + 新目录锁) 不互斥. 误删父行会让新缓存的 dao.get 全线返回 null
        // (stats 停写、重启不再恢复), 且新目录从此不受引用计数保护.
        cache.closeAndDeleteFiles()

        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId), "新缓存的种子级父行必须保留")
        assertEquals(newDir, torrentInfoDatabase.get(testMedia.mediaId)!!.relativeDir)
        assertNotNull(
            torrentInfoDatabase.getFile(
                testMedia.mediaId,
                mediaCacheMetadata().subjectId,
                mediaCacheMetadata().episodeId,
            ),
            "新缓存的集行必须保留",
        )
        assertEquals(1, torrentInfoDatabase.countFilesByRelativeDir(newDir), "新目录必须仍受引用计数保护")
        assertSame(recreated, storage.listFlow.first().single())
    }

    @Test
    fun `deleting one cache keeps shared torrent files reusable`() = runTest {
        val storage = createReadyStorage()
        val firstMetadata = mediaCacheMetadata()
        val secondMetadata = firstMetadata.copy(
            episodeId = "2",
            episodeSort = EpisodeSort("03"),
            episodeEp = EpisodeSort("03"),
        )
        val secondMedia = createTestDefaultMedia(
            mediaId = "dmhy.shared-torrent-copy",
            mediaSourceId = testMedia.mediaSourceId,
            download = testMedia.download,
            originalUrl = "https://example.com/shared-copy",
            originalTitle = "夜晚的水母不会游泳 03 测试剧集",
            publishedTime = testMedia.publishedTime + 1,
            properties = testMedia.properties,
            episodeRange = EpisodeRange.single(EpisodeSort(3)),
            kind = MediaSourceKind.BitTorrent,
            location = MediaSourceLocation.Online,
        )

        val first = storage.cache(testMedia, firstMetadata, resume = false)
        val second = storage.cache(secondMedia, secondMetadata, resume = false)
        runCurrent()

        val firstInfo = torrentInfoDatabase.get(testMedia.mediaId)!!
        val secondInfo = torrentInfoDatabase.get(secondMedia.mediaId)!!
        assertEquals(firstInfo.relativeDir, secondInfo.relativeDir)
        assertEquals(2, torrentInfoDatabase.countFilesByRelativeDir(firstInfo.relativeDir))

        // 模拟合集种子中两集指向不同文件. 删除第一集时, 活会话仍持有第二集;
        // 单删 firstPath 会让 libtorrent 的内存 piece 状态与磁盘脱节, 随后重缓存得到稀疏坏文件.
        val firstPath = first.fileHandle.entry.first()!!.pathInTorrent
        val secondRow = torrentInfoDatabase.getFile(
            secondMedia.mediaId,
            secondMetadata.subjectId,
            secondMetadata.episodeId,
        )!!
        torrentInfoDatabase.upsertFile(secondRow.copy(pathInTorrent = "another-episode.mkv"))
        val firstFile = File(testRootDir, firstInfo.relativeDir.trimStart('/', '\\')).resolve(firstPath)
        firstFile.parentFile.mkdirs()
        firstFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertEquals(true, storage.delete(first))
        first.closeAndDeleteFiles()
        assertNull(
            torrentInfoDatabase.getFile(testMedia.mediaId, firstMetadata.subjectId, firstMetadata.episodeId),
        )

        val replacement = storage.cache(testMedia, firstMetadata, resume = false)

        assertNotNull(
            torrentInfoDatabase.getFile(testMedia.mediaId, firstMetadata.subjectId, firstMetadata.episodeId),
        )
        assertNotNull(
            torrentInfoDatabase.getFile(
                secondMedia.mediaId,
                secondMetadata.subjectId,
                secondMetadata.episodeId,
            ),
        )
        assertNotNull(torrentInfoDatabase.get(secondMedia.mediaId))
        assertEquals(setOf(second, replacement), storage.listFlow.first().toSet())
        assertTrue(firstFile.exists(), "共享种子仍有引用时必须保留被删集的物理文件")
    }

    ///////////////////////////////////////////////////////////////////////////
    // restore
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `restorePersistedCaches - nothing`() = runTest {
        val storage = createReadyStorage()
        storage.restorePersistedCaches()
        assertEquals(0, storage.listFlow.first().size)
    }

    @Test
    fun `a failing startup sweep must not kill the restore loop`() = runTest {
        // 清扫抛异常 (真实成因: actualSize 遍历目录时目录被并发删除、文件不可读) 绝不能逃出恢复
        // 循环: 协程一死, 之后服务重连也不再刷新缓存列表, 整个进程内缓存页都不会再更新;
        // startupRestored 也必须放行, 否则后续 refreshCache 被永久挡住, 连重试机会都没有.
        val storage = createStorage(
            createEngine(
                engine = FailingListSavesTorrentEngine(createTestAnitorrentEngine(coroutineContext)),
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        storage.restorePersistedCaches()
        runCurrent()

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        assertSame(cache, storage.listFlow.first().single())

        // 清扫失败后 storage 仍然可用: 还能刷新 (refreshCache 会重新恢复出新实例, 故按集键比较).
        storage.refreshCache()
        assertEquals(cache.origin.mediaId, storage.listFlow.first().single().origin.mediaId)
    }

    @Test
    fun `restorePersistedCaches restores cache when requested immediately after construction`() = runTest {
        val originalStorage = createReadyStorage()
        val metadata = mediaCacheMetadata()
        val originalCache = originalStorage.cache(testMedia, metadata, resume = false)
        originalStorage.close()

        val restoredStorage = createReadyStorage()

        restoredStorage.restorePersistedCaches()

        val restoredCache = restoredStorage.listFlow.first { it.isNotEmpty() }.single()
        assertEquals(originalCache.origin.mediaId, restoredCache.origin.mediaId)
        assertEquals(metadata, restoredCache.metadata)
    }

    ///////////////////////////////////////////////////////////////////////////
    // cacheMediaSource
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `query cacheMediaSource`() = runTest {
        val storage = createReadyStorage()

        val metadata = mediaCacheMetadata()
        val cache = storage.cache(testMedia, metadata, resume = false)

        assertEquals(
            cache.getCachedMedia().unwrapCached(),
            storage.cacheMediaSource.fetch(
                MediaFetchRequest(
                    subjectId = "1",
                    episodeId = "1",
                    subjectNames = metadata.subjectNames,
                    episodeSort = metadata.episodeSort,
                    episodeName = metadata.episodeName,
                ),
            ).results.toList().single().media.unwrapCached(),
        )
        assertNotNull(torrentInfoDatabase.get(cache.origin.mediaId))
    }

    ///////////////////////////////////////////////////////////////////////////
    // metadata
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `cached media id`() = runTest {
        val storage = createReadyStorage()

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)

        assertNotNull(cache.fileHandle.state.first()).run {
            assertNotNull(handle)
        }

        val cachedMedia = cache.getCachedMedia()
        assertEquals("$CACHE_MEDIA_SOURCE_ID:${testMedia.mediaId}", cachedMedia.mediaId)
        assertEquals(CACHE_MEDIA_SOURCE_ID, cachedMedia.mediaSourceId)
        assertEquals(testMedia, cachedMedia.origin)
    }

    @Test
    fun `create two caches with same episode id`() = runTest {
        val storage = createReadyStorage()

        val metadata = mediaCacheMetadata()
        val testMedia2 = createTestDefaultMedia(
            mediaId = "dmhy.3",
            mediaSourceId = "dmhy",
            originalTitle = "夜晚的水母不会游泳 02 测试剧集2",
            download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:2"),
            originalUrl = "https://example.com/2",
            publishedTime = 1724493292759,
            episodeRange = EpisodeRange.single(EpisodeSort(2)),
            properties = createTestMediaProperties(),
            kind = MediaSourceKind.BitTorrent,
            location = MediaSourceLocation.Online,
        )

        storage.cache(testMedia, metadata, resume = false)
        storage.cache(testMedia2, metadata, resume = false)

        assertEquals(2, storage.listFlow.first().size)
        assertEquals(2, torrentInfoDatabase.getAll().first().size)
    }
}

/**
 * 让启动清扫必定失败的引擎: 清扫要先列出所有种子保存目录.
 */
private class FailingListSavesTorrentEngine(
    private val delegate: TorrentEngine,
) : TorrentEngine by delegate {
    override suspend fun getDownloader(): TorrentDownloader {
        val real = delegate.getDownloader()
        return object : TorrentDownloader by real {
            override fun listSaves(): List<SystemPath> = throw IllegalStateException("listSaves failed")
        }
    }
}
