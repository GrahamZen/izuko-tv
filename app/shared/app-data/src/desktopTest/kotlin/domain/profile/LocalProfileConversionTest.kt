/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import me.him188.ani.app.data.models.subject.SelfRatingInfo
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
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bangumi 用户改成本地用户 (LocalProfileConversion) 与自己的收藏记录 (SelfCollectionRecords):
 * 之前登录留下的记录由人决定留不留, 播放进度都留着; 登录着的、已经是本地的、不是 1 号的不改.
 */
class LocalProfileConversionTest {
    private class CountingRestarter(override val isSupported: Boolean = true) : AppRestarter {
        var restarts = 0
        override fun restart() {
            restarts++
        }
    }

    private val database = createTestAniDatabase()
    private val registry = UserProfileRegistry.inMemory(
        UserProfilesSave(listOf(UserProfile(UserProfile.PRIMARY_ID, avatarUrl = "https://lain.bgm.tv/a.jpg", pendingLogin = true)), UserProfile.PRIMARY_ID, 2),
    )
    private val restarter = CountingRestarter()
    private val manager = UserProfileManager(registry, restarter, deleteFiles = {})
    private val records = SelfCollectionRecords(database)
    private val dir = createTempDirectory("local-conversion").toFile()
    private val pending = PendingLocalCollections(File(dir, PendingLocalCollections.FILE_NAME).toKtPath().inSystem)

    private var current = { registry.find(UserProfile.PRIMARY_ID)!! }
    private var sessionCleared = 0
    private var loggedIn = false

    /** 取账号里的收藏: 依次记下发生了什么 (取收藏 / 退出登录), 看先后. */
    private val events = mutableListOf<String>()
    private var downloadFails = false

    private val conversion
        get() = LocalProfileConversion(
            manager,
            records,
            pending,
            currentProfile = current,
            isLoggedIn = { loggedIn },
            downloadAccountCollections = { onProgress ->
                events += "download"
                if (downloadFails) error("network")
                onProgress(AccountCollectionsDownload.Progress(1, 1))
            },
            clearSession = {
                events += "logout"
                sessionCleared++
            },
        )

    @AfterTest
    fun cleanup() {
        database.close()
        dir.deleteRecursively()
    }

    private fun episode(subjectId: Int, episodeId: Int, type: UnifiedCollectionType) = EpisodeCollectionEntity(
        subjectId = subjectId, episodeId = episodeId, episodeType = null, name = "", nameCn = "", airDate = PackedDate.Invalid,
        comment = 0, desc = "", sort = EpisodeSort(episodeId % 100), sortNumber = (episodeId % 100).toFloat(),
        selfCollectionType = type, lastFetched = 0,
    )

    /** 之前登录留下的: A 在看 (评过分, 看了两集), B 想看; C 只是浏览过; 还有一条播放进度. */
    private suspend fun leaveRecords() {
        database.subjectCollection().upsert(
            testSubjectCollectionEntity(A, DOING).copy(selfRatingInfo = SelfRatingInfo(8, "好看", listOf("神作"), true)),
        )
        database.subjectCollection().upsert(testSubjectCollectionEntity(B, WISH))
        database.subjectCollection().upsert(testSubjectCollectionEntity(C, NOT_COLLECTED))
        database.episodeCollection().upsert(listOf(episode(A, 1001, DONE), episode(A, 1002, DONE), episode(C, 3001, NOT_COLLECTED)))
        database.playbackHistoryDao().upsertRecord(PlaybackHistoryRecordEntity(episodeId = 1001, positionMillis = 5000, updatedAtMillis = 1))
    }

    @Test
    fun `记录计数 只算收藏了的条目与看过的集`() = realTimeTest {
        assertTrue(records.counts().isEmpty)
        leaveRecords()
        assertEquals(SelfCollectionRecords.Counts(collections = 2, watchedEpisodes = 2), records.counts())
    }

    @Test
    fun `清除记录 收藏类型评分看过都清掉 条目与播放进度留着`() = realTimeTest {
        leaveRecords()

        records.clear()

        assertTrue(records.counts().isEmpty)
        val a = database.subjectCollection().getById(A)!!
        assertEquals(NOT_COLLECTED, a.collectionType)
        assertEquals(SelfRatingInfo.Empty, a.selfRatingInfo)
        assertNotNull(database.subjectCollection().getById(C))
        assertEquals(listOf(NOT_COLLECTED, NOT_COLLECTED), database.subjectCollection().episodeSelfStatesOf(A).map { it.selfCollectionType })
        assertEquals(5000, database.playbackHistoryDao().getRecordByEpisodeId(1001)!!.positionMillis)
    }

    @Test
    fun `改成本地用户 留下记录 清掉登录与头像 没起名的存默认名 然后重启`() = realTimeTest {
        leaveRecords()

        conversion.convert(clearRecords = false, defaultName = "用户 1")

        val profile = registry.find(UserProfile.PRIMARY_ID)!!
        assertTrue(profile.isLocal)
        assertEquals("用户 1", profile.name)
        assertFalse(profile.pendingLogin)
        assertNull(profile.avatarUrl)
        assertEquals(1, sessionCleared)
        assertEquals(1, restarter.restarts)
        assertEquals(SelfCollectionRecords.Counts(2, 2), records.counts())
    }

    @Test
    fun `改成本地用户 选了清除就先清记录`() = realTimeTest {
        leaveRecords()

        registry.rename(UserProfile.PRIMARY_ID, "Lina")

        conversion.convert(clearRecords = true, defaultName = "用户 1")

        // 起过名字的不动
        assertEquals("Lina", registry.find(UserProfile.PRIMARY_ID)!!.name)
        assertTrue(registry.find(UserProfile.PRIMARY_ID)!!.isLocal)
        assertTrue(records.counts().isEmpty)
        assertEquals(1, restarter.restarts)
    }

    @Test
    fun `别的 Bangumi 用户也能改 已经是本地用户的不能`() = realTimeTest {
        // 改的是进程的当前用户 (UserProfileManager 按 UserProfiles.currentId), 测试里只能是 1 号; 这里只看给不给
        current = { UserProfile(id = 2, name = "Bob") }
        assertTrue(conversion.isOffered)

        current = { UserProfile(UserProfile.PRIMARY_ID, kind = UserProfileKind.LOCAL) }
        assertFalse(conversion.isOffered)
        assertFailsWith<IllegalStateException> { conversion.convert(clearRecords = false, defaultName = "用户 1") }
        assertEquals(0, restarter.restarts)
    }

    @Test
    fun `登录着留下记录 先取账号里的收藏再退出登录`() = realTimeTest {
        leaveRecords()
        loggedIn = true
        val progress = mutableListOf<AccountCollectionsDownload.Progress>()

        conversion.convert(clearRecords = false, defaultName = "用户 1") { progress += it }

        assertEquals(listOf("download", "logout"), events)
        assertEquals(listOf(AccountCollectionsDownload.Progress(1, 1)), progress)
        assertTrue(registry.find(UserProfile.PRIMARY_ID)!!.isLocal)
        assertEquals(1, restarter.restarts)
    }

    @Test
    fun `账号里的收藏没取全 什么都不改 也不退出登录`() = realTimeTest {
        leaveRecords()
        loggedIn = true
        downloadFails = true

        assertFailsWith<IllegalStateException> { conversion.convert(clearRecords = false, defaultName = "用户 1") }

        assertEquals(listOf("download"), events)
        assertFalse(registry.find(UserProfile.PRIMARY_ID)!!.isLocal)
        assertEquals(SelfCollectionRecords.Counts(2, 2), records.counts())
        assertEquals(0, restarter.restarts)
    }

    @Test
    fun `选了清除或者没登录 不取账号里的收藏`() = realTimeTest {
        loggedIn = true
        conversion.convert(clearRecords = true, defaultName = "用户 1")
        assertEquals(listOf("logout"), events)

        events.clear()
        registry.update(UserProfile.PRIMARY_ID) { it.copy(kind = UserProfileKind.BANGUMI) }
        loggedIn = false
        conversion.convert(clearRecords = false, defaultName = "用户 1")
        assertEquals(listOf("logout"), events)
    }

    @Test
    fun `不能重启应用的平台上不给`() {
        val unsupported = UserProfileManager(registry, CountingRestarter(isSupported = false), deleteFiles = {})
        assertFalse(LocalProfileConversion(unsupported, records, pending, current, clearSession = {}).isOffered)
    }

    private companion object {
        private const val A = 10
        private const val B = 20
        private const val C = 30
    }
}
