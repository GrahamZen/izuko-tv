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
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UserProfileSeederTest {
    private val current = createTestAniDatabase()

    /** 对方: 用 1 号用户 (整机库), 垫完之后还开着, 能直接查. */
    private val target = createTestAniDatabase()

    @AfterTest
    fun cleanup() {
        current.close()
        target.close()
    }

    private fun seeder(ids: List<Int>, onOpen: () -> Unit = {}) = UserProfileSeeder(
        currentDatabase = current,
        deviceDatabase = DeviceAniDatabase(target),
        openDatabase = { onOpen(); error("1 号用户的库不该再开一份") },
        subjectIds = { ids },
    )

    private fun episode(subjectId: Int, episodeId: Int, type: UnifiedCollectionType) =
        EpisodeCollectionEntity(
            subjectId = subjectId, episodeId = episodeId, episodeType = null, name = "", nameCn = "",
            airDate = PackedDate.Invalid, comment = 0, desc = "", sort = EpisodeSort(1),
            sortNumber = 1f, selfCollectionType = type, lastFetched = 123,
        )

    @Test
    fun `只补对方没有的条目 - 个人状态不带过去`() = realTimeTest {
        current.subjectCollection().upsert(
            testSubjectCollectionEntity(1, DOING, lastUpdated = 456).copy(
                summary = "简介",
                selfRatingInfo = SelfRatingInfo(8, "好看", listOf("神作"), false),
                lastFetched = 123,
            ),
        )
        current.episodeCollection().upsert(listOf(episode(1, 11, DONE), episode(1, 12, DONE)))
        current.subjectCollection().upsert(testSubjectCollectionEntity(2, DOING))
        target.subjectCollection().upsert(testSubjectCollectionEntity(2, WISH))

        seeder(listOf(1, 2, 3)).seed(UserProfile(UserProfile.PRIMARY_ID))

        val seeded = target.subjectCollection().findById(1).first()!!
        assertEquals("简介", seeded.summary)
        assertEquals(NOT_COLLECTED, seeded.collectionType)
        assertEquals(SelfRatingInfo.Empty, seeded.selfRatingInfo)
        assertEquals(0, seeded.lastUpdated)
        assertEquals(0, seeded.lastFetched)
        val episodes = target.episodeCollection().filterBySubjectId(1).first()
        assertEquals(listOf(11, 12), episodes.map { it.episodeId })
        assertEquals(setOf(NOT_COLLECTED), episodes.map { it.selfCollectionType }.toSet())
        assertEquals(setOf(0L), episodes.map { it.lastFetched }.toSet())
        // 对方自己有的不动
        assertEquals(WISH, target.subjectCollection().findById(2).first()!!.collectionType)
        // 两边都没有的跳过
        assertNull(target.subjectCollection().findById(3).first())
    }

    @Test
    fun `没有要垫的 - 什么都不开`() = realTimeTest {
        var opened = false
        seeder(emptyList(), onOpen = { opened = true }).seed(UserProfile(2))
        assertFalse(opened)
    }
}
