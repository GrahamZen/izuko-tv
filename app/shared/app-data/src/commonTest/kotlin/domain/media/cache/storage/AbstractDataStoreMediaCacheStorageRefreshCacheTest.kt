/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.SystemTemporaryDirectory
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.media.cache.LocalFileMediaCache
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.domain.media.cache.mediaCacheKey
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.resolve
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回归测试: [AbstractDataStoreMediaCacheStorage.refreshCache] 不应把本轮恢复的
 * [LocalFileMediaCache] 在 [MediaCacheStorage.listFlow] 里加两遍.
 *
 * 加两遍会导致删除时 `minus` 只移除一份, 剩下的副本 state 恒为 COMPLETED,
 * 表现为"删了还显示有缓存/要按两次删除才生效".
 */
class AbstractDataStoreMediaCacheStorageRefreshCacheTest {

    @Test
    fun `refreshCache should not duplicate local file caches restored in the same round`() = runTest {
        val storage = createStorage(backgroundScope.coroutineContext)

        val recovered = storage.refreshCache()

        assertEquals(2, recovered.size)
        assertEquals(2, storage.listFlow.value.size)
        assertEquals(2, storage.listFlow.value.distinctBy { it.origin.mediaId }.size)
    }

    @Test
    fun `refreshCache should collapse duplicate persisted rows for one episode`() = runTest {
        val engine = LocalFileTestEngine()
        val save = mediaCacheSave("media-1", "1", engine.engineKey)
        val storage = LocalFileTestStorage(
            MemoryDataStore(listOf(save, save)),
            engine,
            backgroundScope.coroutineContext,
        )

        val recovered = storage.refreshCache()

        assertEquals(1, recovered.size)
        assertEquals(1, storage.listFlow.value.size)
    }

    @Test
    fun `refreshCache identity does not depend on cacheId hash`() = runTest {
        // "FB" 与 "Ea" 是已知 String hash 碰撞; subject/episode 相同会生成同一个旧 cacheId.
        assertEquals("FB".hashCode(), "Ea".hashCode())
        val engine = LocalFileTestEngine()
        val storage = LocalFileTestStorage(
            MemoryDataStore(
                listOf(
                    mediaCacheSave("FB", "1", engine.engineKey),
                    mediaCacheSave("Ea", "1", engine.engineKey),
                ),
            ),
            engine,
            backgroundScope.coroutineContext,
        )

        val recovered = storage.refreshCache()

        assertEquals(1, recovered.map { it.cacheId }.distinct().size)
        assertEquals(2, recovered.map { it.mediaCacheKey }.distinct().size)
        assertEquals(2, storage.listFlow.value.size)
    }

    @Test
    fun `refreshCache should restore all episodes of a collection sharing one mediaId`() = runTest {
        // 合集种子: 多集共享同一个 mediaId. 恢复去重必须按完整集键 —— 按 mediaId 记账时,
        // 第一集恢复完成后才轮到检查的其余各集会被当成"已恢复"跳过 (并发上限 8, 第 10 集起丢失).
        val engine = LocalFileTestEngine()
        val storage = LocalFileTestStorage(
            MemoryDataStore(
                (1..10).map { episode ->
                    mediaCacheSave("media-shared", episode.toString(), engine.engineKey)
                },
            ),
            engine,
            backgroundScope.coroutineContext,
        )

        val recovered = storage.refreshCache()

        assertEquals(10, recovered.size)
        assertEquals(10, storage.listFlow.value.size)
        assertEquals(10, storage.listFlow.value.distinctBy { it.mediaCacheKey }.size)

        // 第二轮全部走"已恢复"跳过, 也必须原样保留.
        storage.refreshCache()
        assertEquals(10, storage.listFlow.value.size)
    }

    @Test
    fun `refreshCache should preserve local file caches restored in previous rounds`() = runTest {
        val storage = createStorage(backgroundScope.coroutineContext)
        storage.refreshCache()
        val firstRound = storage.listFlow.value

        // 第二轮: 已恢复过的 LocalFileMediaCache 会被跳过, 但必须原样保留在 listFlow 里.
        storage.refreshCache()
        val secondRound = storage.listFlow.value

        assertEquals(2, secondRound.size)
        assertEquals(firstRound.toSet(), secondRound.toSet())
    }

    @Test
    fun `local file deletion is awaited and runs only once`() = runTest {
        val save = mediaCacheSave("media-1", "1", MediaCacheEngineKey("test"))
        val deletionStarted = CompletableDeferred<Unit>()
        val allowDeletion = CompletableDeferred<Unit>()
        var deletionCount = 0
        val cache = LocalFileMediaCache(
            save.origin,
            save.metadata,
            SystemTemporaryDirectory.resolve("ani-local-delete-test.tmp").inSystem,
        ) {
            deletionCount++
            deletionStarted.complete(Unit)
            allowDeletion.await()
        }

        val first = launch { cache.closeAndDeleteFiles() }
        deletionStarted.await()
        val second = launch { cache.closeAndDeleteFiles() }
        runCurrent()

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        allowDeletion.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, deletionCount)
    }

    @Test
    fun `failed row deletion can be retried and does not mark the cache deleted`() = runTest {
        val save = mediaCacheSave("media-1", "1", MediaCacheEngineKey("test"))
        var rowDeletionAttempts = 0
        var fileDeletions = 0
        val cache = LocalFileMediaCache(
            save.origin,
            save.metadata,
            SystemTemporaryDirectory.resolve("ani-local-retry-test.tmp").inSystem,
            onDeletePersistedRows = {
                rowDeletionAttempts++
                // 第一次失败: 模拟 DAO 临时异常. 不能因此把缓存标记成已删除,
                // 否则重试会被短路, 而物理清理仍以为逻辑删除已完成.
                if (rowDeletionAttempts == 1) throw IllegalStateException("dao failure")
            },
            onCloseAndDeleteFiles = { fileDeletions++ },
        )

        assertFailsWith<IllegalStateException> { cache.deletePersistedRows() }
        assertFalse(cache.isDeleted.value)
        assertEquals(0, fileDeletions)

        // 重试: 逻辑删除必须真的再跑一次, 成功后才允许进入物理清理.
        cache.closeAndDeleteFiles()

        assertEquals(2, rowDeletionAttempts)
        assertEquals(1, fileDeletions)
        assertTrue(cache.isDeleted.value)

        // 已成功的阶段 1 不再重复执行.
        cache.closeAndDeleteFiles()
        assertEquals(2, rowDeletionAttempts)
        assertEquals(1, fileDeletions)
    }

    private fun createStorage(context: CoroutineContext): LocalFileTestStorage {
        val engine = LocalFileTestEngine()
        return LocalFileTestStorage(
            MemoryDataStore(
                listOf(
                    mediaCacheSave("media-1", "1", engine.engineKey),
                    mediaCacheSave("media-2", "2", engine.engineKey),
                ),
            ),
            engine,
            context,
        )
    }
}

/**
 * 总是以 [LocalFileMediaCache] 恢复缓存的引擎, 模拟"已完成缓存走本地文件快路径"的场景.
 */
private class LocalFileTestEngine : MediaCacheEngine {
    override val engineKey: MediaCacheEngineKey = MediaCacheEngineKey("test-local-file")
    override val stats: Flow<MediaStats> = flowOf(MediaStats.Unspecified)

    override fun supports(media: Media): Boolean = true

    override suspend fun restore(
        origin: Media,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext,
    ): MediaCache = LocalFileMediaCache(
        origin, metadata,
        // 文件不存在也没关系, length() 返回 0, 测试不读文件内容.
        SystemTemporaryDirectory.resolve("ani-test-${origin.mediaId}.tmp").inSystem,
    )

    override suspend fun createCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        parentContext: CoroutineContext,
    ): MediaCache = throw UnsupportedOperationException("Not used in this test")

    override suspend fun deleteUnusedCaches(all: List<MediaCache>) = Unit
}

private class LocalFileTestStorage(
    datastore: DataStore<List<MediaCacheSave>>,
    engine: MediaCacheEngine,
    parentCoroutineContext: CoroutineContext,
) : AbstractDataStoreMediaCacheStorage(
    mediaSourceId = "test-storage",
    datastore = datastore,
    engine = engine,
    displayName = "Test Storage",
    parentCoroutineContext = parentCoroutineContext,
) {
    override suspend fun restorePersistedCaches() = Unit
}

private fun mediaCacheSave(
    mediaId: String,
    episodeId: String,
    engineKey: MediaCacheEngineKey,
): MediaCacheSave {
    val episodeSort = EpisodeSort(episodeId.toInt())
    val origin = createTestDefaultMedia(
        mediaId = mediaId,
        mediaSourceId = "test-source",
        originalUrl = "https://example.com/$mediaId",
        download = ResourceLocation.HttpStreamingFile("https://example.com/$mediaId.m3u8"),
        originalTitle = "Episode $episodeId",
        publishedTime = 1L,
        properties = createTestMediaProperties(
            subjectName = "Test Subject",
            episodeName = "Episode $episodeId",
        ),
        episodeRange = EpisodeRange.single(episodeSort),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.WEB,
    )
    return MediaCacheSave(
        origin,
        MediaCacheMetadata(
            subjectId = "1",
            episodeId = episodeId,
            subjectNameCN = "Test Subject",
            subjectNames = listOf("Test Subject"),
            episodeSort = episodeSort,
            episodeEp = episodeSort,
            episodeName = "Episode $episodeId",
        ),
        engineKey,
    )
}
