/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.network.SubjectCollectionUpdate
import me.him188.ani.app.data.persistent.database.DeviceAniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryRecordEntity
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import me.him188.ani.app.platform.AppRestarter
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DOING
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DONE
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.NOT_COLLECTED
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.WISH
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import me.him188.ani.utils.io.writeText
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本地用户改成 Bangumi 用户 (BangumiProfileConversion) 与留下的本地收藏 (PendingLocalCollections):
 * 改的时候收藏存进文件再清库; 登录后合并到账号 (只增不删, 没加上的留着) 或删掉; 没登录就改回本地用户的放回库里.
 */
class BangumiProfileConversionTest {
    private class CountingRestarter : AppRestarter {
        override val isSupported: Boolean get() = true
        var restarts = 0
        override fun restart() {
            restarts++
        }
    }

    private val dir = createTempDirectory("pending-local").toFile()
    private val file: SystemPath = File(dir, PendingLocalCollections.FILE_NAME).toKtPath().inSystem
    private val database = createTestAniDatabase()
    private val device = createTestAniDatabase()
    private val registry = UserProfileRegistry.inMemory(
        UserProfilesSave(listOf(UserProfile(UserProfile.PRIMARY_ID, name = "Lina", kind = UserProfileKind.LOCAL)), UserProfile.PRIMARY_ID, 2),
    )
    private val restarter = CountingRestarter()
    private val manager = UserProfileManager(registry, restarter, deleteFiles = {})
    private val records = SelfCollectionRecords(database)
    private val pending = PendingLocalCollections(file)

    /** 进程常量: 改了类型也要重启才变, 测试里手动换. */
    private var current = UserProfile(UserProfile.PRIMARY_ID, name = "Lina", kind = UserProfileKind.LOCAL)

    /** Bangumi 那边已经收藏的条目, 与写进去的请求. */
    private val bangumiCollected = mutableSetOf<Int>()
    private val added = mutableListOf<Pair<Int, SubjectCollectionUpdate>>()
    private var failAdd: (Int) -> Boolean = { false }

    private val importer = LocalProfileImporter(
        deviceDatabase = DeviceAniDatabase(device),
        openDatabase = { error("not used") },
        fetchBangumiCollectedIds = { bangumiCollected.toSet() },
        isCollectedOnBangumi = { it in bangumiCollected },
        addBangumiCollection = { id, update ->
            if (failAdd(id)) error("add failed")
            added += id to update
            bangumiCollected += id
        },
        markBangumiEpisodesWatched = { _, _ -> },
    )

    /** 放回库里的 (改回本地用户时), 与放不回去的. */
    private val restored = mutableListOf<ProfileArchive.Collection>()
    private var failRestore: (Int) -> Boolean = { false }

    private val archiver = ProfileArchiver(
        currentProfile = { current },
        currentDatabase = database,
        deviceDatabase = DeviceAniDatabase(device),
        openDatabase = { error("not used") },
        setLocalCollectionType = { _, _ -> },
        loadEpisodes = {},
    )

    private val conversion = BangumiProfileConversion(
        manager = manager,
        records = records,
        pending = pending,
        importer = importer,
        currentProfile = { current },
        exportCollections = { archiver.export(current).collections },
        restoreCollection = { item ->
            if (failRestore(item.subjectId)) error("restore failed")
            restored += item
        },
    )

    @AfterTest
    fun cleanup() {
        database.close()
        device.close()
        dir.deleteRecursively()
    }

    private fun episode(subjectId: Int, episodeId: Int, type: UnifiedCollectionType) = EpisodeCollectionEntity(
        subjectId = subjectId, episodeId = episodeId, episodeType = null, name = "", nameCn = "", airDate = PackedDate.Invalid,
        comment = 0, desc = "", sort = EpisodeSort(episodeId % 100), sortNumber = (episodeId % 100).toFloat(),
        selfCollectionType = type, lastFetched = 0,
    )

    /** 本地收藏: A 在看 (评过分, 看了两集), B 想看; C 只是浏览过; 还有一条播放进度. */
    private suspend fun collectLocally() {
        database.subjectCollection().upsert(
            testSubjectCollectionEntity(A, DOING, lastUpdated = 300).copy(nameCn = "甲", selfRatingInfo = SelfRatingInfo(8, "好看", listOf("神作"), false)),
        )
        database.subjectCollection().upsert(testSubjectCollectionEntity(B, WISH, lastUpdated = 200).copy(nameCn = "乙"))
        database.subjectCollection().upsert(testSubjectCollectionEntity(C, NOT_COLLECTED))
        database.episodeCollection().upsert(listOf(episode(A, 1001, DONE), episode(A, 1002, DONE)))
        database.playbackHistoryDao().upsertRecord(PlaybackHistoryRecordEntity(episodeId = 1001, positionMillis = 5000, updatedAtMillis = 1))
    }

    /** 改完重启进来: 当前用户换成登录 Bangumi 的. */
    private fun restartAsBangumi() {
        current = registry.find(UserProfile.PRIMARY_ID)!!
    }

    @Test
    fun `改成 Bangumi 用户 收藏存进文件 库里清掉 播放进度留着 然后重启进登录`() = realTimeTest {
        collectLocally()

        conversion.convert()

        val profile = registry.find(UserProfile.PRIMARY_ID)!!
        assertFalse(profile.isLocal)
        assertTrue(profile.pendingLogin)
        assertEquals("Lina", profile.name)
        assertEquals(1, restarter.restarts)
        assertTrue(records.counts().isEmpty)
        assertEquals(5000, database.playbackHistoryDao().getRecordByEpisodeId(1001)!!.positionMillis)
        assertTrue(file.exists())
        val kept = PendingLocalCollections(file).load()
        assertEquals(listOf(A, B), kept.map { it.subjectId })
        assertEquals(listOf(1001, 1002), kept.first().watchedEpisodes.sorted())
        assertEquals(8, kept.first().score)
    }

    @Test
    fun `没有收藏时不写文件`() = realTimeTest {
        conversion.convert()

        assertFalse(file.exists())
        assertEquals(1, restarter.restarts)
    }

    @Test
    fun `已经是 Bangumi 用户的不能再改`() = realTimeTest {
        current = current.copy(kind = UserProfileKind.BANGUMI)

        assertFalse(conversion.isOffered)
        assertFailsWith<IllegalStateException> { conversion.convert() }
        assertEquals(0, restarter.restarts)
    }

    @Test
    fun `合并 账号里已有的不动 加上的处理完 没加上的留着下次再问`() = realTimeTest {
        collectLocally()
        conversion.convert()
        restartAsBangumi()
        // 再放一部进文件 (改的时候本地有三部), 账号里已经有 B
        pending.add(listOf(ProfileArchive.Collection(subjectId = D, name = "丁", type = "DONE")))
        bangumiCollected += B
        failAdd = { it == D }

        val preview = conversion.previewMerge()!!
        assertEquals(listOf(D, A), preview.toAdd.map { it.subjectId })
        assertEquals(listOf(B), preview.alreadyCollected.map { it.subjectId })

        val result = conversion.merge(preview)

        assertEquals(1, result.added)
        assertEquals(listOf(A), added.map { it.first })
        assertEquals(DOING, added.single().second.collectionType)
        assertEquals(8, added.single().second.score)
        assertEquals(listOf(D), pending.load().map { it.subjectId })
        assertEquals(listOf(D), PendingLocalCollections(file).load().map { it.subjectId })
    }

    @Test
    fun `合并完都加上了 文件删掉`() = realTimeTest {
        collectLocally()
        conversion.convert()
        restartAsBangumi()

        conversion.merge(conversion.previewMerge()!!)

        assertEquals(listOf(B, A), added.map { it.first })
        assertTrue(pending.load().isEmpty())
        assertFalse(file.exists())
        assertNull(conversion.previewMerge())
    }

    @Test
    fun `在别处删掉之后再合并 不写账号`() = realTimeTest {
        collectLocally()
        conversion.convert()
        restartAsBangumi()
        val preview = conversion.previewMerge()!!

        conversion.discard()

        assertFailsWith<IllegalStateException> { conversion.merge(preview) }
        assertTrue(added.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `本地用户不能合并`() = realTimeTest {
        collectLocally()
        pending.add(listOf(ProfileArchive.Collection(subjectId = A, type = "DOING")))

        assertFailsWith<IllegalStateException> { conversion.previewMerge() }
    }

    @Test
    fun `没登录又改回本地 放回库里 放不回去的留着`() = realTimeTest {
        collectLocally()
        conversion.convert()
        // 没登录就改回本地用户, 重启进来
        current = current.copy(kind = UserProfileKind.LOCAL)
        failRestore = { it == B }

        assertEquals(1, conversion.restoreIntoLocal())

        assertEquals(listOf(A), restored.map { it.subjectId })
        assertEquals(listOf(B), pending.load().map { it.subjectId })

        failRestore = { false }
        assertEquals(1, conversion.restoreIntoLocal())
        assertTrue(pending.load().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `登录 Bangumi 的用户启动时不放回`() = realTimeTest {
        collectLocally()
        conversion.convert()
        restartAsBangumi()

        assertEquals(0, conversion.restoreIntoLocal())
        assertTrue(restored.isEmpty())
        assertEquals(2, pending.load().size)
    }

    @Test
    fun `文件坏了当没有 同一部再加换成新的`() = realTimeTest {
        file.writeText("{not json")
        assertTrue(PendingLocalCollections(file).load().isEmpty())

        val store = PendingLocalCollections(file)
        store.add(listOf(ProfileArchive.Collection(subjectId = A, type = "WISH"), ProfileArchive.Collection(subjectId = B, type = "DONE")))
        store.add(listOf(ProfileArchive.Collection(subjectId = A, type = "DOING")))

        val read = PendingLocalCollections(file).load()
        assertEquals(listOf(A, B), read.map { it.subjectId })
        assertEquals("DOING", read.first().type)
        assertEquals(2, store.collections.first().size)
    }

    @Test
    fun `改回本地用户时 文件里的算在留下的记录里 选清除就一起删`() = realTimeTest {
        pending.add(listOf(ProfileArchive.Collection(subjectId = A, type = "DOING", watchedEpisodes = listOf(1001, 1002))))
        current = current.copy(kind = UserProfileKind.BANGUMI)
        registry.update(UserProfile.PRIMARY_ID) { it.copy(kind = UserProfileKind.BANGUMI) }
        val toLocal = LocalProfileConversion(
            manager,
            records,
            pending,
            currentProfile = { current },
            clearSession = {},
        )

        assertEquals(SelfCollectionRecords.Counts(collections = 1, watchedEpisodes = 2), toLocal.leftoverRecords())

        toLocal.convert(clearRecords = true, defaultName = "用户 1")

        assertTrue(pending.load().isEmpty())
        assertFalse(file.exists())
    }

    private companion object {
        private const val A = 10
        private const val B = 20
        private const val C = 30
        private const val D = 40
    }
}
