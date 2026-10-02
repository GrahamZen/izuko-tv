/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryRecordEntity
import me.him188.ani.client.models.AniCollectionType
import me.him188.ani.client.models.AniEpisodeCollection
import me.him188.ani.client.models.AniEpisodeCollectionType
import me.him188.ani.client.models.AniEpisodeType
import me.him188.ani.client.models.AniFavourite
import me.him188.ani.client.models.AniSelfRatingInfo
import me.him188.ani.client.models.AniSubjectCollection
import me.him188.ani.client.models.AniSubjectRelations
import me.him188.ani.client.models.AniSubjectType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountArchiveExporterTest {
    private val database: AniDatabase = createTestAniDatabase()

    @AfterTest
    fun close() {
        database.close()
    }

    @Test
    fun `翻页取完所有收藏 - 字段照抄`() = runTest {
        val all = (1..AccountArchiveExporter.PAGE_SIZE + 3).map { id ->
            collection(id, if (id % 2 == 0) AniCollectionType.DONE else AniCollectionType.WISH)
        }
        val requests = mutableListOf<Pair<Int, Int>>()
        val exporter = AccountArchiveExporter(
            fetchCollections = { offset, limit -> requests += offset to limit; all.drop(offset).take(limit) },
            database = database,
            clock = { 42 },
        )
        val progress = mutableListOf<Int>()
        val archive = exporter.export("小明") { progress += it }

        assertEquals(listOf(0 to AccountArchiveExporter.PAGE_SIZE, AccountArchiveExporter.PAGE_SIZE to AccountArchiveExporter.PAGE_SIZE), requests)
        assertEquals(listOf(AccountArchiveExporter.PAGE_SIZE, AccountArchiveExporter.PAGE_SIZE + 3), progress)
        assertEquals(all.map { it.id.toInt() }, archive.collections.map { it.subjectId })
        assertEquals("小明", archive.profileName)
        assertEquals(42, archive.exportedAt)
        assertEquals("WISH", archive.collections[0].type)
        assertEquals("DONE", archive.collections[1].type)
    }

    @Test
    fun `评分短评标签与看过的集`() = runTest {
        val item = collection(
            7, AniCollectionType.DOING,
            rating = AniSelfRatingInfo(score = 8, tags = listOf("百合"), isPrivate = true, comment = "好看"),
            episodes = listOf(70 to true, 71 to false, 72 to true),
        )
        val archive = AccountArchiveExporter({ offset, _ -> if (offset == 0) listOf(item) else emptyList() }, database).export("")
        val c = archive.collections.single()
        assertEquals("条目 7", c.name)
        assertEquals("DOING", c.type)
        assertEquals(8, c.score)
        assertEquals("好看", c.comment)
        assertEquals(listOf("百合"), c.tags)
        assertTrue(c.private)
        assertEquals(1704153600000, c.updatedAt) // 2024-01-02T00:00:00Z
        assertEquals(listOf(70, 72), c.watchedEpisodes)
    }

    @Test
    fun `没收藏的跳过 - 空短评不写`() = runTest {
        val items = listOf(
            collection(1, null),
            collection(2, AniCollectionType.ON_HOLD, rating = AniSelfRatingInfo(score = 0, tags = emptyList(), isPrivate = false, comment = " ")),
        )
        val archive = AccountArchiveExporter({ offset, _ -> if (offset == 0) items else emptyList() }, database).export("")
        val c = archive.collections.single()
        assertEquals(2, c.subjectId)
        assertEquals(0, c.score)
        assertEquals(null, c.comment)
    }

    @Test
    fun `服务器不按 offset 翻页时不会一直取`() = runTest {
        val page = (1..AccountArchiveExporter.PAGE_SIZE).map { collection(it, AniCollectionType.DONE) }
        var calls = 0
        val archive = AccountArchiveExporter({ _, _ -> calls++; page }, database).export("")
        assertEquals(2, calls)
        assertEquals(AccountArchiveExporter.PAGE_SIZE, archive.collections.size)
    }

    @Test
    fun `播放进度只取没删的 - 新的在前`() = runTest {
        val dao = database.playbackHistoryDao()
        dao.upsertRecord(PlaybackHistoryRecordEntity(episodeId = 1, positionMillis = 1000, subjectId = 9, updatedAtMillis = 10))
        dao.upsertRecord(PlaybackHistoryRecordEntity(episodeId = 2, positionMillis = 2000, subjectId = 9, updatedAtMillis = 20))
        dao.upsertRecord(PlaybackHistoryRecordEntity(episodeId = 3, positionMillis = 3000, updatedAtMillis = 30, deletedAtMillis = 31))
        val archive = AccountArchiveExporter({ _, _ -> emptyList() }, database).export("")
        assertEquals(listOf(2, 1), archive.playback.map { it.episodeId })
        assertEquals(2000, archive.playback[0].positionMillis)
        assertEquals(20, archive.playback[0].updatedAt)
    }

    @Test
    fun `文件带格式与版本 - 不写 null`() = runTest {
        val item = collection(5, AniCollectionType.DONE)
        val archive = AccountArchiveExporter({ offset, _ -> if (offset == 0) listOf(item) else emptyList() }, database).export("")
        val json = ProfileArchive.Json.encodeToJsonElement(ProfileArchive.serializer(), archive).jsonObject
        assertEquals("izuko-tv-profile", json["format"]!!.jsonPrimitive.content)
        assertEquals(1, json["version"]!!.jsonPrimitive.int)
        val c = json["collections"]!!.jsonArray.single() as JsonObject
        assertEquals(5, c["subjectId"]!!.jsonPrimitive.int)
        assertEquals("DONE", c["type"]!!.jsonPrimitive.content)
        assertFalse("comment" in c)
    }

    private fun collection(
        subjectId: Int,
        type: AniCollectionType?,
        rating: AniSelfRatingInfo = AniSelfRatingInfo(score = 0, tags = emptyList(), isPrivate = false, comment = null),
        episodes: List<Pair<Int, Boolean>> = emptyList(),
    ) = AniSubjectCollection(
        id = subjectId.toLong(),
        type = AniSubjectType.ANIME,
        name = "subject-$subjectId",
        nameCn = "条目 $subjectId",
        summary = "",
        nsfw = false,
        airDate = "2024-01-01",
        aliases = emptyList(),
        favorite = AniFavourite(wish = 0, done = 0, doing = 0, onHold = 0, dropped = 0),
        tags = emptyList(),
        metaTags = emptyList(),
        scoreDetails = emptyMap(),
        selfRating = rating,
        episodes = episodes.mapIndexed { index, (episodeId, watched) ->
            AniEpisodeCollection(
                episodeId = episodeId.toLong(),
                subjectId = subjectId.toLong(),
                sort = (index + 1).toString(),
                type = AniEpisodeType.MAIN,
                name = "ep",
                nameCn = "第 ${index + 1} 集",
                description = "",
                collectionType = if (watched) AniEpisodeCollectionType.DONE else null,
            )
        },
        relations = AniSubjectRelations(subjectId.toLong(), emptyList(), emptyList(), emptyList(), emptyList()),
        collectionType = type,
        updatedAt = "2024-01-02T00:00:00Z",
    )
}
