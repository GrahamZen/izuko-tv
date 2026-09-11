/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.AniDatabaseConstructor
import me.him188.ani.app.data.persistent.database.ProtoConverters
import me.him188.ani.app.data.persistent.database.SelfRatingTagsRepair
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 自己的评分标签 (`self_rating_tags`): [SubjectCollectionDao.updateRating] 收编码好的字节 (以前收列表, 被 Room 展开成
 * `COALESCE(?, ?, …)` 把第一个标签的原文写进了这一列); 以前写坏的行由 [SelfRatingTagsRepair] 在打开数据库时修.
 */
class SubjectCollectionRatingTagsTest {
    private fun encode(tags: List<String>): ByteArray = ProtoConverters.StringList().fromList(tags)

    private fun subject(subjectId: Int, lastFetched: Long, tags: List<String> = emptyList()) = SubjectCollectionEntity(
        subjectId = subjectId,
        name = "subject-$subjectId",
        nameCn = "条目 $subjectId",
        summary = "",
        nsfw = false,
        imageLarge = "",
        totalEpisodes = 12,
        airDate = PackedDate.Invalid,
        aliases = emptyList(),
        tags = emptyList(),
        collectionStats = SubjectCollectionStats.Zero,
        ratingInfo = RatingInfo.Empty,
        completeDate = PackedDate.Invalid,
        selfRatingInfo = SelfRatingInfo(score = 0, comment = null, tags = tags, isPrivate = false),
        collectionType = UnifiedCollectionType.DONE,
        recurrence = null,
        lastUpdated = 1,
        lastFetched = lastFetched,
        cachedStaffUpdated = 0,
        cachedCharactersUpdated = 0,
    )

    @Test
    fun `updateRating 写进去的标签能原样读回来`() = runBlocking {
        val db = createTestAniDatabase()
        try {
            val dao = db.subjectCollection()
            dao.upsert(subject(1, lastFetched = 100))

            dao.updateRating(1, 9, "好看", encode(listOf("卡谬", "富野由悠季")), false)
            val rated = assertNotNull(dao.findById(1).first())
            assertEquals(listOf("卡谬", "富野由悠季"), rated.selfRatingInfo.tags)
            assertEquals(9, rated.selfRatingInfo.score)
            assertEquals("好看", rated.selfRatingInfo.comment)

            // 空列表: 以前展开成 COALESCE(, …), 直接 SQL 语法错误
            dao.updateRating(1, null, null, encode(emptyList()), null)
            assertEquals(emptyList(), assertNotNull(dao.findById(1).first()).selfRatingInfo.tags)

            // null = 不改
            dao.updateRating(1, null, null, encode(listOf("机战")), null)
            dao.updateRating(1, 7, null, null, null)
            val kept = assertNotNull(dao.findById(1).first())
            assertEquals(listOf("机战"), kept.selfRatingInfo.tags)
            assertEquals(7, kept.selfRatingInfo.score)
        } finally {
            db.close()
        }
    }

    @Test
    fun `打开数据库时修复被写成纯文本的标签, 别的行不动`() = runBlocking {
        val path = Files.createTempDirectory("ani-rating-tags").resolve("test.db").toString()
        fun open(repair: Boolean): AniDatabase =
            Room.databaseBuilder<AniDatabase>(name = path) { AniDatabaseConstructor.initialize() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .apply { if (repair) addCallback(SelfRatingTagsRepair) }
                .build()

        open(repair = false).let { db ->
            db.subjectCollection().upsert(
                listOf(
                    subject(1, lastFetched = 100, tags = listOf("卡谬", "富野由悠季")),
                    subject(2, lastFetched = 100, tags = listOf("高达")),
                ),
            )
            db.close()
        }
        // 旧实现写坏的样子 (2026-09-11 电视上的那一行): 第一个标签的原文进了这一列
        BundledSQLiteDriver().open(path).use { connection ->
            connection.prepare("UPDATE subject_collection SET self_rating_tags = ? WHERE subjectId = 1").use {
                it.bindText(1, "卡谬")
                it.step()
            }
        }
        open(repair = false).let { db ->
            val failure = runCatching { db.subjectCollection().findById(1).first() }.exceptionOrNull()
            assertNotNull(failure, "a text value in self_rating_tags should fail to decode")
            db.close()
        }

        val db = open(repair = true)
        try {
            val repaired = assertNotNull(db.subjectCollection().findById(1).first())
            assertEquals(emptyList(), repaired.selfRatingInfo.tags)
            // 置 0 = 过期, 下次用到时从服务端取回整行 (含标签)
            assertEquals(0, repaired.lastFetched)
            val untouched = assertNotNull(db.subjectCollection().findById(2).first())
            assertEquals(listOf("高达"), untouched.selfRatingInfo.tags)
            assertEquals(100, untouched.lastFetched)
        } finally {
            db.close()
        }
    }
}
