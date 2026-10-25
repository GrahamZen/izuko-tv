/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.app.data.repository.subject.SubjectRewatchEntry
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DOING
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DONE
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.NOT_COLLECTED
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 本地用户的收藏带出去 (导出文件 / 改成 Bangumi 用户 / 导入 Bangumi) 时, 重温中的番按开始时的看过算, 不当成在看.
 */
class LocalCollectionsRewatchTest {
    private val database = createTestAniDatabase()

    @AfterTest
    fun close() = database.close()

    private fun episode(subjectId: Int, episodeId: Int, type: UnifiedCollectionType) = EpisodeCollectionEntity(
        subjectId = subjectId, episodeId = episodeId, episodeType = null, name = "", nameCn = "",
        airDate = PackedDate.Invalid, comment = 0, desc = "", sort = EpisodeSort(episodeId % 100),
        sortNumber = (episodeId % 100).toFloat(), selfCollectionType = type, lastFetched = 1,
    )

    private suspend fun seed() {
        // 7 在重温 (本地是在看, 这一轮看了第 3 集); 8 是普通的在看
        database.subjectCollection().upsert(testSubjectCollectionEntity(7, DOING, lastUpdated = 9_000))
        database.subjectCollection().upsert(testSubjectCollectionEntity(8, DOING, lastUpdated = 100))
        database.episodeCollection().upsert(
            listOf(
                episode(7, 701, NOT_COLLECTED), episode(7, 702, NOT_COLLECTED), episode(7, 703, DONE),
                episode(8, 801, DONE),
            ),
        )
    }

    private val rewatch = SubjectRewatchEntry(7, startedAt = 9_000, lastUpdated = 500, episodes = mapOf(701 to DONE, 702 to DONE))

    @Test
    fun `rewatching subjects are read as watched with the episodes watched before the rewatch`() = realTimeTest {
        seed()

        val entries = database.readLocalCollections(mapOf(7 to rewatch)).associateBy { it.subjectId }

        assertEquals(DONE, entries.getValue(7).type)
        assertEquals(setOf(701, 702), entries.getValue(7).watchedEpisodeIds.toSet())
        assertEquals(500, entries.getValue(7).updatedAt)
        assertEquals(DOING, entries.getValue(8).type)
        assertEquals(listOf(801), entries.getValue(8).watchedEpisodeIds)
    }

    @Test
    fun `without rewatches everything is read as stored`() = realTimeTest {
        seed()

        val entries = database.readLocalCollections().associateBy { it.subjectId }

        assertEquals(DOING, entries.getValue(7).type)
        assertEquals(listOf(703), entries.getValue(7).watchedEpisodeIds)
        assertEquals(9_000, entries.getValue(7).updatedAt)
    }

    @Test
    fun `a record whose row is no longer doing is ignored`() = realTimeTest {
        seed()
        database.subjectCollection().updateType(7, DONE, 9_500)

        val entries = database.readLocalCollections(mapOf(7 to rewatch)).associateBy { it.subjectId }

        assertEquals(DONE, entries.getValue(7).type)
        assertEquals(listOf(703), entries.getValue(7).watchedEpisodeIds)
        assertEquals(9_500, entries.getValue(7).updatedAt)
    }
}
