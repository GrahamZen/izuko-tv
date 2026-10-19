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
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.NOT_COLLECTED
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.WISH
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 移出收藏 ([me.him188.ani.app.data.persistent.database.dao.SubjectCollectionDao.markLeftCollection]): 行与挂在它上面的分集留着.
 */
class MarkLeftCollectionTest {
    private val database = createTestAniDatabase()
    private val subjects = database.subjectCollection()
    private val episodes = database.episodeCollection()

    @AfterTest
    fun close() = database.close()

    @Test
    fun `left subjects keep their row and episodes but leave the list`() = realTimeTest {
        subjects.upsert(testSubjectCollectionEntity(1, WISH, lastUpdated = 100).copy(lastFetched = 500))
        subjects.upsert(testSubjectCollectionEntity(2, WISH, lastUpdated = 200).copy(lastFetched = 500))
        episodes.upsert(
            listOf(
                EpisodeCollectionEntity(
                    subjectId = 1, episodeId = 11, episodeType = null, name = "", nameCn = "", airDate = PackedDate.Invalid,
                    comment = 0, desc = "", sort = EpisodeSort(1), sortNumber = 1f, selfCollectionType = NOT_COLLECTED, lastFetched = 500,
                ),
            ),
        )

        subjects.markLeftCollection(listOf(1))

        val left = subjects.findById(1).first()!!
        assertEquals(NOT_COLLECTED, left.collectionType)
        assertEquals(0, left.lastFetched)
        assertEquals(listOf(11), episodes.filterBySubjectId(1).first().map { it.episodeId })
        assertEquals(listOf(2), subjects.subjectIdsByCollectionType(listOf(WISH)).first())
    }
}
