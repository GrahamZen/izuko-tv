/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecommendationFeedAppendTest {
    private fun runDatabaseTest(block: suspend (RecommendationFeedDao) -> Unit) = runBlocking {
        val database: AniDatabase = createTestAniDatabase()
        try {
            block(database.recommendationFeedDao())
        } finally {
            database.close()
        }
    }

    private fun row(groupKey: String, orderIndex: Int, subjectId: Int, computedAt: Long = COMPUTED_AT, titleArg: String? = "种子") =
        RecommendationFeedEntity(
            groupKey = groupKey,
            orderIndex = orderIndex,
            subjectId = subjectId,
            nameCn = "条目$subjectId",
            imageLarge = "",
            computedAt = computedAt,
            titleArg = titleArg,
        )

    @Test
    fun `接在一组末尾 - 序号排在全表最后且组内次序照传入的`() = runDatabaseTest { dao ->
        dao.replaceAll(listOf(row(SEED_GROUP, 0, 1), row(SEED_GROUP, 1, 2), row(OTHER_GROUP, 2, 3)))

        val appended = dao.appendToGroup(SEED_GROUP, COMPUTED_AT, listOf(row(SEED_GROUP, 0, 10), row(SEED_GROUP, 0, 11)))

        assertEquals(listOf(3, 4), appended?.map { it.orderIndex })
        val all = dao.allFlow().first()
        assertEquals(listOf(1, 2, 10, 11), all.filter { it.groupKey == SEED_GROUP }.map { it.subjectId })
        // 别的组不动
        assertEquals(listOf(3), all.filter { it.groupKey == OTHER_GROUP }.map { it.subjectId })
    }

    @Test
    fun `整表已换成新一批 - 什么都不写`() = runDatabaseTest { dao ->
        dao.replaceAll(listOf(row(SEED_GROUP, 0, 1, computedAt = COMPUTED_AT + 1)))

        val appended = dao.appendToGroup(SEED_GROUP, COMPUTED_AT, listOf(row(SEED_GROUP, 0, 10)))

        assertNull(appended)
        assertEquals(listOf(1), dao.allFlow().first().map { it.subjectId })
    }

    @Test
    fun `上一批的种子名 - 按行的先后, 换换口味记的标签不算`() = runDatabaseTest { dao ->
        dao.replaceAll(
            listOf(
                row(SEED_GROUP, 0, 1, titleArg = "种子甲"),
                row(SEED_GROUP, 1, 2, titleArg = "种子甲"),
                row("change_taste", 2, 3, titleArg = "悬疑"),
                row("also_watched#1@327", 3, 4, titleArg = "种子乙"),
                row(OTHER_GROUP, 4, 5, titleArg = null),
            ),
        )

        assertEquals(listOf("种子甲", "种子乙"), dao.seedTitleArgs())
    }

    private companion object {
        const val SEED_GROUP = "because_you_liked@326"
        const val OTHER_GROUP = "top_rated"
        const val COMPUTED_AT = 1_000L
    }
}
