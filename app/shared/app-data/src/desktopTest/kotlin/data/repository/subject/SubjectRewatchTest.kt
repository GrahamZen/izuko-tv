/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionPendingOpEntity
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
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
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 重温: 开始时存快照并把本地改成在看、各集清空, 结束时照快照还原; Bangumi 回来的数据怎么落库; 没在重温的条目一律原样放行.
 */
class SubjectRewatchTest {
    private val database = createTestAniDatabase()
    private val subjects = database.subjectCollection()
    private val episodes = database.episodeCollection()
    private val dir = createTempDirectory("subject-rewatch").toFile()
    private val file: SystemPath = File(dir, SubjectRewatch.FILE_NAME).toKtPath().inSystem

    private fun rewatch() = SubjectRewatch(database, file, clock = { NOW })

    @AfterTest
    fun close() {
        database.close()
        dir.deleteRecursively()
    }

    private fun episode(subjectId: Int, episodeId: Int, type: UnifiedCollectionType) = EpisodeCollectionEntity(
        subjectId = subjectId, episodeId = episodeId, episodeType = null, name = "", nameCn = "",
        airDate = PackedDate.Invalid, comment = 0, desc = "", sort = EpisodeSort(episodeId % 100),
        sortNumber = (episodeId % 100).toFloat(), selfCollectionType = type, lastFetched = 1,
    )

    /** 看过的一部: 第 1 2 集看过, 第 3 集没标. */
    private suspend fun seedDone(subjectId: Int = SUBJECT) {
        subjects.upsert(testSubjectCollectionEntity(subjectId, DONE, lastUpdated = WATCHED_AT))
        episodes.upsert(
            listOf(
                episode(subjectId, subjectId * 100 + 1, DONE),
                episode(subjectId, subjectId * 100 + 2, DONE),
                episode(subjectId, subjectId * 100 + 3, NOT_COLLECTED),
            ),
        )
    }

    private suspend fun row(subjectId: Int = SUBJECT) = subjects.findById(subjectId).first()!!

    private suspend fun episodeTypes(subjectId: Int = SUBJECT) =
        episodes.filterBySubjectId(subjectId).first().map { it.selfCollectionType }

    @Test
    fun `start turns a watched subject into doing with no watched episodes`() = realTimeTest {
        seedDone()
        val rewatch = rewatch()

        assertEquals(RewatchStartResult.STARTED, rewatch.start(SUBJECT))

        assertEquals(DOING, row().collectionType)
        assertEquals(NOW, row().lastUpdated)
        assertEquals(listOf(NOT_COLLECTED, NOT_COLLECTED, NOT_COLLECTED), episodeTypes())
        assertTrue(rewatch.isActive(SUBJECT))
        assertTrue(rewatch.rewatchingFlow(SUBJECT).first())
        assertTrue(file.exists())
    }

    @Test
    fun `start refuses subjects that are not watched`() = realTimeTest {
        subjects.upsert(testSubjectCollectionEntity(SUBJECT, WISH, lastUpdated = WATCHED_AT))
        val rewatch = rewatch()

        assertEquals(RewatchStartResult.NOT_DONE, rewatch.start(SUBJECT))
        assertEquals(RewatchStartResult.NOT_DONE, rewatch.start(OTHER))

        assertEquals(WISH, row().collectionType)
        assertFalse(rewatch.isActive(SUBJECT))
        assertFalse(file.exists())
    }

    @Test
    fun `start refuses while watched episodes are waiting to sync`() = realTimeTest {
        seedDone()
        database.episodeCollectionPendingOpDao().replacePendingOps(
            listOf(EpisodeCollectionPendingOpEntity(subjectId = SUBJECT, episodeId = SUBJECT * 100 + 3, collectionType = DONE, updatedAtMillis = 1)),
        )
        val rewatch = rewatch()

        assertEquals(RewatchStartResult.PENDING_SYNC, rewatch.start(SUBJECT))

        assertEquals(DONE, row().collectionType)
        assertEquals(listOf(DONE, DONE, NOT_COLLECTED), episodeTypes())
        assertFalse(rewatch.isActive(SUBJECT))
    }

    @Test
    fun `end restores the watched record and drops this round`() = realTimeTest {
        seedDone()
        val rewatch = rewatch()
        rewatch.start(SUBJECT)
        // 这一轮看了第 3 集
        episodes.updateSelfCollectionType(SUBJECT, SUBJECT * 100 + 3, DONE)

        assertTrue(rewatch.end(SUBJECT))

        assertEquals(DONE, row().collectionType)
        assertEquals(WATCHED_AT, row().lastUpdated)
        assertEquals(listOf(DONE, DONE, NOT_COLLECTED), episodeTypes())
        assertFalse(rewatch.isActive(SUBJECT))
        assertFalse(rewatch.rewatchingFlow(SUBJECT).first())
        assertFalse(file.exists())
    }

    @Test
    fun `end does nothing for subjects not rewatching`() = realTimeTest {
        seedDone()
        val rewatch = rewatch()

        assertFalse(rewatch.end(SUBJECT))

        assertEquals(DONE, row().collectionType)
        assertEquals(WATCHED_AT, row().lastUpdated)
        assertEquals(listOf(DONE, DONE, NOT_COLLECTED), episodeTypes())
    }

    @Test
    fun `refresh keeps local state while Bangumi still says watched`() = realTimeTest {
        seedDone()
        val rewatch = rewatch()
        rewatch.start(SUBJECT)

        assertTrue(rewatch.keepsSelfState(SUBJECT, DONE))
        // 没登录时取到的匿名结果
        assertTrue(rewatch.keepsSelfState(SUBJECT, NOT_COLLECTED))
        assertTrue(rewatch.isActive(SUBJECT))
    }

    @Test
    fun `refresh ends the rewatch when Bangumi changed the collection elsewhere`() = realTimeTest {
        seedDone()
        val rewatch = rewatch()
        rewatch.start(SUBJECT)

        assertFalse(rewatch.keepsSelfState(SUBJECT, DOING))
        assertFalse(rewatch.isActive(SUBJECT))
    }

    @Test
    fun `refresh hooks pass other subjects through unchanged`() = realTimeTest {
        seedDone()
        seedDone(OTHER)
        val rewatch = rewatch()

        assertFalse(rewatch.keepsSelfState(OTHER, DONE))
        val fetched = listOf(testSubjectCollectionEntity(OTHER, DONE, lastUpdated = 7))
        assertSame(fetched, rewatch.keepSelfStates(fetched, mapOf(OTHER to row(OTHER))))
        val ids = listOf(SUBJECT, OTHER)
        assertSame(ids, rewatch.idsAsOnBangumi(listOf(DONE), ids))

        rewatch.start(SUBJECT)
        assertFalse(rewatch.keepsSelfState(OTHER, DOING))
        val kept = rewatch.keepSelfStates(fetched, mapOf(OTHER to row(OTHER)))
        assertEquals(fetched, kept)
    }

    @Test
    fun `batch refresh keeps the rewatching subject as doing`() = realTimeTest {
        seedDone()
        seedDone(OTHER)
        val rewatch = rewatch()
        rewatch.start(SUBJECT)
        val fetched = listOf(
            testSubjectCollectionEntity(SUBJECT, DONE, lastUpdated = WATCHED_AT).copy(summary = "new"),
            testSubjectCollectionEntity(OTHER, DOING, lastUpdated = 9),
        )

        val kept = rewatch.keepSelfStates(fetched, mapOf(SUBJECT to row(), OTHER to row(OTHER)))

        assertEquals(DOING, kept[0].collectionType)
        assertEquals(NOW, kept[0].lastUpdated)
        assertEquals("new", kept[0].summary)
        assertEquals(fetched[1], kept[1])
    }

    @Test
    fun `collection list reconcile counts the rewatching subject as watched`() = realTimeTest {
        seedDone()
        val rewatch = rewatch()
        rewatch.start(SUBJECT)

        assertEquals(listOf(OTHER), rewatch.idsAsOnBangumi(listOf(DOING), listOf(SUBJECT, OTHER)).toList())
        assertEquals(setOf(OTHER, SUBJECT), rewatch.idsAsOnBangumi(listOf(DONE), listOf(OTHER)).toSet())
        assertEquals(setOf(SUBJECT, OTHER), rewatch.idsAsOnBangumi(UnifiedCollectionType.entries, listOf(SUBJECT, OTHER)).toSet())
    }

    @Test
    fun `rewatch survives a restart`() = realTimeTest {
        seedDone()
        rewatch().start(SUBJECT)

        val restarted = rewatch()
        assertTrue(restarted.isActive(SUBJECT))
        assertTrue(restarted.end(SUBJECT))
        assertEquals(listOf(DONE, DONE, NOT_COLLECTED), episodeTypes())
    }

    @Test
    fun `a record whose row is no longer doing is dropped on load`() = realTimeTest {
        seedDone()
        rewatch().start(SUBJECT)
        // 进程在还原库之后、删记录之前被杀; 或者别的路径把这一行写回了看过
        subjects.updateType(SUBJECT, DONE, WATCHED_AT)

        val restarted = rewatch()
        assertFalse(restarted.isActive(SUBJECT))
        assertFalse(file.exists())
    }

    @Test
    fun `new login drops all rewatches`() = realTimeTest {
        seedDone()
        val rewatch = rewatch()
        rewatch.start(SUBJECT)

        rewatch.dropAll()

        assertFalse(rewatch.isActive(SUBJECT))
        assertFalse(file.exists())
    }

    private companion object {
        const val SUBJECT = 7
        const val OTHER = 8
        const val WATCHED_AT = 500L
        const val NOW = 9_000L
    }
}
