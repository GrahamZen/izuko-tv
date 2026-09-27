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
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.repository.realTimeTest
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
import kotlin.test.assertNull

/**
 * 本地档的落库 (upsertSubjectKeepingSelfState / upsertKeepingSelfState): 取到的是匿名结果, 收藏类型、自己的评分、
 * 收藏更新时间与每集看过状态保留库里的, 其余照新的写.
 */
class LocalProfileSelfStateTest {
    private val database = createTestAniDatabase()
    private val subjects = database.subjectCollection()
    private val episodes = database.episodeCollection()

    @AfterTest
    fun close() = database.close()

    private fun episode(episodeId: Int, type: UnifiedCollectionType, name: String = "", lastFetched: Long = 1) =
        EpisodeCollectionEntity(
            subjectId = SUBJECT, episodeId = episodeId, episodeType = null, name = name, nameCn = "",
            airDate = PackedDate.Invalid, comment = 0, desc = "", sort = EpisodeSort(episodeId - 10),
            sortNumber = (episodeId - 10).toFloat(), selfCollectionType = type, lastFetched = lastFetched,
        )

    private val rating = SelfRatingInfo(8, "好看", listOf("神作"), false)

    private suspend fun collectLocally() {
        subjects.upsert(
            testSubjectCollectionEntity(SUBJECT, DOING, lastUpdated = 500).copy(
                summary = "旧简介",
                selfRatingInfo = rating,
                lastFetched = 1,
            ),
        )
        episodes.upsert(listOf(episode(11, DONE), episode(12, NOT_COLLECTED), episode(13, DONE)))
    }

    /** 服务端给的样子: 匿名, 收藏与看过一律空. */
    private val fetched = testSubjectCollectionEntity(SUBJECT, NOT_COLLECTED, lastUpdated = 0).copy(summary = "新简介", lastFetched = 999)

    @Test
    fun `条目重取 保留本地的收藏 评分与收藏时间 其余照新的写`() = realTimeTest {
        collectLocally()
        subjects.upsertSubjectKeepingSelfState(fetched, listOf(episode(11, NOT_COLLECTED), episode(12, NOT_COLLECTED)))

        val saved = subjects.findById(SUBJECT).first()!!
        assertEquals(DOING, saved.collectionType)
        assertEquals(rating, saved.selfRatingInfo)
        assertEquals(500, saved.lastUpdated)
        assertEquals("新简介", saved.summary)
        assertEquals(999, saved.lastFetched)
    }

    @Test
    fun `分集按集保留看过 服务端删掉的集照删 新集照加`() = realTimeTest {
        collectLocally()
        subjects.upsertSubjectKeepingSelfState(
            fetched,
            listOf(
                episode(11, NOT_COLLECTED, name = "第一集", lastFetched = 999),
                episode(12, NOT_COLLECTED, lastFetched = 999),
                episode(14, NOT_COLLECTED, lastFetched = 999),
            ),
        )

        val saved = episodes.filterBySubjectId(SUBJECT).first().associateBy { it.episodeId }
        assertEquals(setOf(11, 12, 14), saved.keys)
        assertEquals(DONE, saved.getValue(11).selfCollectionType)
        assertEquals("第一集", saved.getValue(11).name)
        assertEquals(999, saved.getValue(11).lastFetched)
        assertEquals(NOT_COLLECTED, saved.getValue(12).selfCollectionType)
        assertEquals(NOT_COLLECTED, saved.getValue(14).selfCollectionType)
    }

    @Test
    fun `分集这次没取 只写条目`() = realTimeTest {
        collectLocally()
        subjects.upsertSubjectKeepingSelfState(fetched, null)

        assertEquals("新简介", subjects.findById(SUBJECT).first()!!.summary)
        val saved = episodes.filterBySubjectId(SUBJECT).first()
        assertEquals(listOf(11, 12, 13), saved.map { it.episodeId })
        assertEquals(listOf(DONE, NOT_COLLECTED, DONE), saved.map { it.selfCollectionType })
    }

    @Test
    fun `库里还没有的条目照取到的写`() = realTimeTest {
        assertNull(subjects.findById(SUBJECT).first())
        subjects.upsertSubjectKeepingSelfState(fetched, listOf(episode(11, NOT_COLLECTED)))

        assertEquals(NOT_COLLECTED, subjects.findById(SUBJECT).first()!!.collectionType)
        assertEquals(listOf(11), episodes.filterBySubjectId(SUBJECT).first().map { it.episodeId })
    }

    @Test
    fun `单集重取 保留看过`() = realTimeTest {
        collectLocally()
        val kept = episodes.upsertKeepingSelfState(episode(11, NOT_COLLECTED, name = "第一集", lastFetched = 999))

        assertEquals(DONE, kept.selfCollectionType)
        val saved = episodes.findByEpisodeId(11).first()!!
        assertEquals(DONE, saved.selfCollectionType)
        assertEquals("第一集", saved.name)
        assertEquals(999, saved.lastFetched)
    }

    @Test
    fun `单集库里没有照取到的写`() = realTimeTest {
        subjects.upsert(testSubjectCollectionEntity(SUBJECT, NOT_COLLECTED))
        val kept = episodes.upsertKeepingSelfState(episode(15, NOT_COLLECTED))

        assertEquals(NOT_COLLECTED, kept.selfCollectionType)
        assertEquals(NOT_COLLECTED, episodes.findByEpisodeId(15).first()!!.selfCollectionType)
    }

    private companion object {
        private const val SUBJECT = 1
    }
}
