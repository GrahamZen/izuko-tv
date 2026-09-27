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
import me.him188.ani.app.data.network.SubjectCollectionUpdate
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 本地用户导入 Bangumi (LocalProfileImporter): 只加 Bangumi 上还没收藏的, 已有的一概不动; 最早收藏的先加;
 * 想看的不标看过的集; 动手前再核对一次; 一条失败不影响其余.
 */
class LocalProfileImporterTest {
    /** 本地用户的库 (他的进程没在跑, 导入时打开读). */
    private val local = createTestAniDatabase()

    /** 1 号用户的库, 这里用不上, 只是构造要. */
    private val device = createTestAniDatabase()

    private val source = UserProfile(id = 2, name = "Lina", kind = UserProfileKind.LOCAL)

    /** Bangumi 那边: 已经收藏的条目, 与写进去的请求. */
    private val bangumiCollected = mutableSetOf<Int>()

    /** 翻收藏列表时漏掉的 (逐条查时查得到). */
    private var listingMisses = emptySet<Int>()
    private var failCheck: (Int) -> Boolean = { false }
    private val added = mutableListOf<Pair<Int, SubjectCollectionUpdate>>()
    private val marked = mutableListOf<Pair<Int, List<Int>>>()
    private var afterImportCalls = 0
    private var failAdd: (Int) -> Boolean = { false }
    private var failMark: (Int) -> Boolean = { false }

    private val importer = LocalProfileImporter(
        deviceDatabase = DeviceAniDatabase(device),
        openDatabase = { local },
        fetchBangumiCollectedIds = { bangumiCollected - listingMisses },
        isCollectedOnBangumi = { id ->
            if (failCheck(id)) error("check failed")
            id in bangumiCollected
        },
        addBangumiCollection = { id, update ->
            if (failAdd(id)) error("add failed")
            added += id to update
            bangumiCollected += id
        },
        markBangumiEpisodesWatched = { id, episodes ->
            if (failMark(id)) error("mark failed")
            marked += id to episodes
        },
        afterImport = { afterImportCalls++ },
    )

    @AfterTest
    fun cleanup() {
        runCatching { local.close() }
        device.close()
    }

    private fun episode(subjectId: Int, episodeId: Int, type: UnifiedCollectionType) = EpisodeCollectionEntity(
        subjectId = subjectId, episodeId = episodeId, episodeType = null, name = "", nameCn = "", airDate = PackedDate.Invalid,
        comment = 0, desc = "", sort = EpisodeSort(episodeId % 100), sortNumber = (episodeId % 100).toFloat(),
        selfCollectionType = type, lastFetched = 0,
    )

    /** 在看 (评过分, 看了两集) / 想看 (有一集标了看过) / 看过 / 只是浏览过的; 收藏时间 A 最近, C 最早. */
    private suspend fun collectLocally() {
        local.subjectCollection().upsert(
            testSubjectCollectionEntity(A, DOING, lastUpdated = 300).copy(
                nameCn = "甲",
                selfRatingInfo = SelfRatingInfo(8, "好看", listOf("神作"), false),
            ),
        )
        local.subjectCollection().upsert(testSubjectCollectionEntity(B, WISH, lastUpdated = 200).copy(nameCn = "乙"))
        local.subjectCollection().upsert(testSubjectCollectionEntity(C, DONE, lastUpdated = 100).copy(nameCn = "丙"))
        local.subjectCollection().upsert(testSubjectCollectionEntity(D, NOT_COLLECTED).copy(nameCn = "丁"))
        local.episodeCollection().upsert(
            listOf(episode(A, 1001, DONE), episode(A, 1002, DONE), episode(A, 1003, NOT_COLLECTED), episode(B, 2001, DONE)),
        )
    }

    @Test
    fun `预览 分出要加的与已经收藏的 只是浏览过的不算`() = realTimeTest {
        collectLocally()
        bangumiCollected += C

        val preview = importer.preview(source)

        assertEquals(listOf(A, B), preview.toAdd.map { it.subjectId })
        assertEquals(listOf(C), preview.alreadyCollected.map { it.subjectId })
        val a = preview.toAdd.first()
        assertEquals("甲", a.name)
        assertEquals(listOf(1001, 1002), a.watchedEpisodeIds)
    }

    @Test
    fun `导入 最早收藏的先加 带上评分短评 想看的不标集`() = realTimeTest {
        collectLocally()
        val result = importer.import(importer.preview(source))

        // C 最早, A 最近
        assertEquals(listOf(C, B, A), added.map { it.first })
        val a = added.single { it.first == A }.second
        assertEquals(SubjectCollectionUpdate(collectionType = DOING, score = 8, comment = "好看", tags = listOf("神作")), a)
        // 没评分的不带评分 (0 = 删评分)
        assertEquals(SubjectCollectionUpdate(collectionType = WISH), added.single { it.first == B }.second)
        // 想看的 B 有一集标了看过也不标; C 本地没有看过的集
        assertEquals(listOf(A to listOf(1001, 1002)), marked)
        assertEquals(3, result.added)
        assertEquals(0, result.skipped)
        assertEquals(2, result.episodesMarked)
        assertTrue(result.failures.isEmpty())
        assertEquals(1, afterImportCalls)
    }

    @Test
    fun `预览之后才在别处加上的 导入时照样跳过`() = realTimeTest {
        collectLocally()
        bangumiCollected += C
        val preview = importer.preview(source)
        bangumiCollected += A

        val result = importer.import(preview)

        assertEquals(listOf(B), added.map { it.first })
        assertEquals(1, result.added)
        assertEquals(2, result.skipped)
        assertTrue(marked.isEmpty())
    }

    @Test
    fun `翻收藏列表漏掉的已收藏条目 写之前逐条核对 不会改写`() = realTimeTest {
        collectLocally()
        bangumiCollected += A
        listingMisses = setOf(A)
        val preview = importer.preview(source)
        assertEquals(listOf(A, B, C), preview.toAdd.map { it.subjectId })

        val result = importer.import(preview)

        assertEquals(listOf(C, B), added.map { it.first })
        assertEquals(1, result.skipped)
        // A 的看过的集也不动
        assertTrue(marked.isEmpty())
    }

    @Test
    fun `核对失败的不写`() = realTimeTest {
        collectLocally()
        failCheck = { it == B }

        val result = importer.import(importer.preview(source))

        assertEquals(listOf(C, A), added.map { it.first })
        assertEquals(listOf(B to false), result.failures.map { it.entry.subjectId to it.collectionAdded })
    }

    @Test
    fun `一条失败不影响其余 集没标上的算已加上`() = realTimeTest {
        collectLocally()
        failAdd = { it == B }
        failMark = { it == A }

        val result = importer.import(importer.preview(source))

        assertEquals(listOf(C, A), added.map { it.first })
        assertEquals(2, result.added)
        assertEquals(listOf(B to false, A to true), result.failures.map { it.entry.subjectId to it.collectionAdded })
    }

    @Test
    fun `什么都没加 不刷新缓存`() = realTimeTest {
        collectLocally()
        bangumiCollected += listOf(A, B, C)

        val result = importer.import(importer.preview(source))

        assertEquals(0, result.added)
        assertEquals(3, result.skipped)
        assertEquals(0, afterImportCalls)
    }

    @Test
    fun `只能导入本地用户`() = realTimeTest {
        assertFailsWith<IllegalArgumentException> { importer.preview(UserProfile(id = 3, kind = UserProfileKind.BANGUMI)) }
    }

    private companion object {
        private const val A = 10
        private const val B = 20
        private const val C = 30
        private const val D = 40
    }
}
