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
import me.him188.ani.app.data.persistent.database.DeviceAniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryRecordEntity
import me.him188.ani.app.data.repository.RepositorySubjectNotAccessibleException
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DOING
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DONE
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.NOT_COLLECTED
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.WISH
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本地用户的导出文件 (ProfileArchiver): 导出收藏、看过的集与播放进度; 导入只进当前的本地用户, 只增不删 ——
 * 已经收藏的不动, 播放进度只在文件里的更新时覆盖.
 */
class ProfileArchiveTest {
    /** 当前用户 (Lina) 的库. */
    private val current = createTestAniDatabase()

    /** 另一个本地用户的库 (他的进程没在跑, 导出时打开读). */
    private val other = createTestAniDatabase()
    private val device = createTestAniDatabase()

    private val lina = UserProfile(id = 2, name = "Lina", kind = UserProfileKind.LOCAL)
    private var currentProfile = lina

    /** 条目信息「从 Bangumi 取」: 取过哪些, 哪些取不到. */
    private val fetched = mutableListOf<Int>()
    private val unreachable = mutableSetOf<Int>()

    /** 单独重取过分集的条目, 与重取时失败的条目. */
    private val episodesLoaded = mutableListOf<Int>()
    private val episodesUnreachable = mutableSetOf<Int>()

    private val archiver = ProfileArchiver(
        currentProfile = { currentProfile },
        currentDatabase = current,
        deviceDatabase = DeviceAniDatabase(device),
        openDatabase = { other },
        setLocalCollectionType = { subjectId, type ->
            // 同本地档的 SubjectCollectionRepository: 库里没有这个条目先取公开信息 (连分集), 再改类型
            if (current.subjectCollection().getById(subjectId) == null) {
                fetched += subjectId
                if (subjectId in unreachable) throw RepositorySubjectNotAccessibleException(subjectId)
                current.subjectCollection().upsert(testSubjectCollectionEntity(subjectId, NOT_COLLECTED).copy(nameCn = "取来的"))
                current.episodeCollection().upsert(listOf(episode(subjectId, subjectId * 100 + 1), episode(subjectId, subjectId * 100 + 2)))
            }
            current.subjectCollection().updateType(subjectId, type)
        },
        loadEpisodes = { subjectId ->
            episodesLoaded += subjectId
            if (subjectId in episodesUnreachable) throw RepositorySubjectNotAccessibleException(subjectId)
            current.episodeCollection().upsert(listOf(episode(subjectId, subjectId * 100 + 1), episode(subjectId, subjectId * 100 + 2)))
        },
        clock = { NOW },
    )

    @AfterTest
    fun cleanup() {
        current.close()
        runCatching { other.close() }
        device.close()
    }

    private fun episode(subjectId: Int, episodeId: Int, type: UnifiedCollectionType = NOT_COLLECTED) = EpisodeCollectionEntity(
        subjectId = subjectId, episodeId = episodeId, episodeType = null, name = "", nameCn = "", airDate = PackedDate.Invalid,
        comment = 0, desc = "", sort = EpisodeSort(episodeId % 100), sortNumber = (episodeId % 100).toFloat(),
        selfCollectionType = type, lastFetched = 0,
    )

    private fun record(episodeId: Int, updatedAt: Long, position: Long = 1000, deletedAt: Long? = null) = PlaybackHistoryRecordEntity(
        episodeId = episodeId, positionMillis = position, subjectId = A, subjectName = "甲", episodeName = "第 1 集",
        durationMillis = 1_440_000, updatedAtMillis = updatedAt, deletedAtMillis = deletedAt,
    )

    private fun item(subjectId: Int, type: String, updatedAt: Long = 300, episodes: List<Int> = emptyList()) = ProfileArchive.Collection(
        subjectId = subjectId, name = "甲", type = type, score = 8, comment = "好看", tags = listOf("神作"), private = true,
        updatedAt = updatedAt, watchedEpisodes = episodes,
    )

    @Test
    fun `导出 收藏类型 评分 看过的集与播放进度 浏览过的与删掉的记录不算`() = realTimeTest {
        current.subjectCollection().upsert(
            testSubjectCollectionEntity(A, DOING, lastUpdated = 300).copy(
                nameCn = "甲",
                selfRatingInfo = SelfRatingInfo(8, "好看", listOf("神作"), true),
            ),
        )
        current.subjectCollection().upsert(testSubjectCollectionEntity(B, WISH, lastUpdated = 200).copy(name = "Otsu"))
        current.subjectCollection().upsert(testSubjectCollectionEntity(D, NOT_COLLECTED).copy(nameCn = "丁"))
        current.episodeCollection().upsert(listOf(episode(A, 1001, DONE), episode(A, 1002, DONE), episode(A, 1003)))
        current.playbackHistoryDao().upsertRecords(listOf(record(1001, updatedAt = 50), record(9, updatedAt = 60, deletedAt = 70)))

        val archive = archiver.export(lina)

        assertEquals("Lina", archive.profileName)
        assertEquals(NOW, archive.exportedAt)
        assertEquals(
            listOf(
                ProfileArchive.Collection(A, "甲", "DOING", 8, "好看", listOf("神作"), true, 300, listOf(1001, 1002)),
                // 没有中文名用原名
                ProfileArchive.Collection(B, "Otsu", "WISH", updatedAt = 200),
            ),
            archive.collections,
        )
        assertEquals(listOf(1001), archive.playback.map { it.episodeId })
        assertEquals("第 1 集", archive.playback.single().episodeName)
        // 当前用户的库导完还开着
        assertEquals(2, current.subjectCollection().subjectIdsByCollectionType(listOf(DOING, WISH)).first().size)
    }

    @Test
    fun `导出别的本地用户 读他的库`() = realTimeTest {
        other.subjectCollection().upsert(testSubjectCollectionEntity(C, DONE, lastUpdated = 100).copy(nameCn = "丙"))

        val archive = archiver.export(UserProfile(id = 3, name = "Mika", kind = UserProfileKind.LOCAL))

        assertEquals(listOf(C), archive.collections.map { it.subjectId })
        assertEquals("Mika", archive.profileName)
    }

    @Test
    fun `只能导出本地用户`() = realTimeTest {
        assertFailsWith<IllegalArgumentException> { archiver.export(UserProfile(id = 1)) }
    }

    @Test
    fun `文件往返 格式与版本写在里面 不认识的字段忽略`() {
        val archive = ProfileArchive(
            exportedAt = 1,
            profileName = "Lina",
            collections = listOf(item(A, "DOING", episodes = listOf(1001))),
            playback = listOf(ProfileArchive.Playback(episodeId = 1001, positionMillis = 5000, updatedAt = 50)),
        )
        val text = ProfileArchive.Json.encodeToString(ProfileArchive.serializer(), archive)
        assertTrue("\"format\":\"izuko-tv-profile\"" in text, text)
        assertTrue("\"version\":2" in text, text)
        // 没有的可空字段不写 null
        assertTrue("null" !in text, text)
        assertEquals(archive, ProfileArchive.Json.decodeFromString(ProfileArchive.serializer(), text))

        val newer = """{"format":"izuko-tv-profile","version":1,"future":true,"collections":[{"subjectId":10,"type":"WISH","mood":"?"}]}"""
        assertEquals(
            listOf(ProfileArchive.Collection(subjectId = 10, type = "WISH")),
            ProfileArchive.Json.decodeFromString(ProfileArchive.serializer(), newer).collections,
        )
    }

    @Test
    fun `导入 没收藏的加上 带评分与收藏时间 只标当前条目上有的集`() = realTimeTest {
        // 文件里的 A99 这一集条目上没有 (比如条目的分集表变过)
        val outcome = archiver.restoreCollection(item(A, "DOING", updatedAt = 300, episodes = listOf(A * 100 + 1, A * 100 + 2, A * 100 + 99)))

        assertEquals(ProfileArchiver.CollectionOutcome.Added(episodesMarked = 2), outcome)
        assertEquals(listOf(A), fetched)
        val saved = current.subjectCollection().getById(A)!!
        assertEquals(DOING, saved.collectionType)
        assertEquals(300, saved.lastUpdated)
        assertEquals(SelfRatingInfo(8, "好看", listOf("神作"), true), saved.selfRatingInfo)
        assertEquals(
            listOf(DONE, DONE),
            current.subjectCollection().episodeSelfStatesOf(A).map { it.selfCollectionType },
        )
    }

    @Test
    fun `已经收藏的不动`() = realTimeTest {
        current.subjectCollection().upsert(
            testSubjectCollectionEntity(A, WISH, lastUpdated = 100).copy(selfRatingInfo = SelfRatingInfo(5, null, emptyList(), false)),
        )

        val outcome = archiver.restoreCollection(item(A, "DOING"))

        assertEquals(ProfileArchiver.CollectionOutcome.Skipped, outcome)
        val saved = current.subjectCollection().getById(A)!!
        assertEquals(WISH, saved.collectionType)
        assertEquals(100, saved.lastUpdated)
        assertEquals(5, saved.selfRatingInfo.score)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `浏览过没收藏的 照样收藏上 不用再取`() = realTimeTest {
        current.subjectCollection().upsert(testSubjectCollectionEntity(A, NOT_COLLECTED))
        current.episodeCollection().upsert(listOf(episode(A, 1001)))

        val outcome = archiver.restoreCollection(item(A, "DONE", episodes = listOf(1001)))

        assertEquals(ProfileArchiver.CollectionOutcome.Added(episodesMarked = 1), outcome)
        assertEquals(DONE, current.subjectCollection().getById(A)!!.collectionType)
        assertTrue(fetched.isEmpty())
        assertTrue(episodesLoaded.isEmpty())
    }

    @Test
    fun `条目在而分集没取过 先取分集再标看过`() = realTimeTest {
        // 只浏览过 / 清除过收藏记录: 条目行留着, 分集是进详情页才取的
        current.subjectCollection().upsert(testSubjectCollectionEntity(A, NOT_COLLECTED))

        val outcome = archiver.restoreCollection(item(A, "DONE", updatedAt = 300, episodes = listOf(A * 100 + 1, A * 100 + 2)))

        assertEquals(ProfileArchiver.CollectionOutcome.Added(episodesMarked = 2), outcome)
        assertEquals(listOf(A), episodesLoaded)
        val saved = current.subjectCollection().getById(A)!!
        assertEquals(DONE, saved.collectionType)
        assertEquals(300, saved.lastUpdated)
        assertEquals(listOf(DONE, DONE), current.subjectCollection().episodeSelfStatesOf(A).map { it.selfCollectionType })
    }

    @Test
    fun `分集取不到时抛 收藏不写 再导会重试`() = realTimeTest {
        current.subjectCollection().upsert(testSubjectCollectionEntity(A, NOT_COLLECTED))
        episodesUnreachable += A

        assertFailsWith<RepositorySubjectNotAccessibleException> {
            archiver.restoreCollection(item(A, "DONE", episodes = listOf(A * 100 + 1)))
        }
        assertEquals(NOT_COLLECTED, current.subjectCollection().getById(A)!!.collectionType)
    }

    @Test
    fun `条目信息取不到时抛 什么都不留`() = realTimeTest {
        unreachable += A

        assertFailsWith<RepositorySubjectNotAccessibleException> { archiver.restoreCollection(item(A, "DOING")) }
        assertNull(current.subjectCollection().getById(A))
    }

    @Test
    fun `类型不认识或是没收藏的 拒绝`() = realTimeTest {
        assertFailsWith<IllegalArgumentException> { archiver.restoreCollection(item(A, "FAVORITE")) }
        assertFailsWith<IllegalArgumentException> { archiver.restoreCollection(item(A, "NOT_COLLECTED")) }
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `已经收藏了哪些`() = realTimeTest {
        current.subjectCollection().upsert(testSubjectCollectionEntity(A, DOING))
        current.subjectCollection().upsert(testSubjectCollectionEntity(B, NOT_COLLECTED))

        assertEquals(setOf(A), archiver.collectedAmong(listOf(A, B, C)))
    }

    @Test
    fun `播放进度 没有的加上 文件里更新的才覆盖`() = realTimeTest {
        val dao = current.playbackHistoryDao()
        dao.upsertRecords(
            listOf(
                record(1, updatedAt = 100, position = 1),
                record(2, updatedAt = 500, position = 2),
                // 在电视上删掉了, 删的时刻比文件里的新
                record(3, updatedAt = 100, position = 3, deletedAt = 400),
            ),
        )
        fun playback(episodeId: Int, updatedAt: Long) =
            ProfileArchive.Playback(episodeId = episodeId, positionMillis = episodeId * 1000L, updatedAt = updatedAt)

        val restored = archiver.restorePlayback(listOf(playback(1, 200), playback(2, 300), playback(3, 300), playback(4, 50)))

        assertEquals(2, restored)
        assertEquals(1000, dao.getRecordByEpisodeId(1)!!.positionMillis)
        assertEquals(2, dao.getRecordByEpisodeId(2)!!.positionMillis)
        assertEquals(400, dao.getRecordByEpisodeId(3)!!.deletedAtMillis)
        assertEquals(4000, dao.getRecordByEpisodeId(4)!!.positionMillis)
    }

    @Test
    fun `当前用户不是本地用户时 不导入`() = realTimeTest {
        currentProfile = UserProfile(id = 1)

        assertFailsWith<IllegalStateException> { archiver.restoreCollection(item(A, "DOING")) }
        assertFailsWith<IllegalStateException> { archiver.restorePlayback(emptyList()) }
        assertFailsWith<IllegalStateException> { archiver.collectedAmong(listOf(A)) }
        assertTrue(fetched.isEmpty())
    }

    private companion object {
        private const val NOW = 1_000_000L
        private const val A = 10
        private const val B = 20
        private const val C = 30
        private const val D = 40
    }
}
