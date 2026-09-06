/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.io.IOException
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.repository.FakeRemote
import me.him188.ani.app.data.repository.badRequest
import me.him188.ani.app.data.repository.launchDetached
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DONE
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DROPPED
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.NOT_COLLECTED
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.WISH
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 标看过 / 取消看过 ([setSelfCollectionTypeLocalFirst]) 与全部标为看过 ([setAllEpisodesWatchedLocalFirst]):
 * 本地先改 (进度条立刻变), 请求在后面发; 成功后补写被旧数据盖回的, 最终失败或被取消才改回去, 而且只动自己写的那一次.
 * 2026-09-26 Shield 实测: 请求被首页推荐重算的几十个搜索压在队尾, 进度条要等 4~8 秒.
 *
 * 用真的 (内存) 数据库: 补写与改回用的是条件 UPDATE, 要连 SQL 一起测.
 */
class EpisodeCollectionOptimisticUpdateTest {
    private val database = createTestAniDatabase()
    private val dao = database.episodeCollection()
    private val remote = FakeRemote<UnifiedCollectionType>()

    @AfterTest
    fun close() = database.close()

    private suspend fun set(type: UnifiedCollectionType, episodeId: Int = EPISODE) =
        dao.setSelfCollectionTypeLocalFirst(SUBJECT, episodeId, type) { remote.send(type) }

    private suspend fun setAllWatched() = dao.setAllEpisodesWatchedLocalFirst(SUBJECT) { remote.send(DONE) }

    private suspend fun local(episodeId: Int = EPISODE): UnifiedCollectionType? =
        dao.findByEpisodeId(episodeId).first()?.selfCollectionType

    private suspend fun insertEpisodes(vararg episodes: Pair<Int, UnifiedCollectionType>) {
        database.subjectCollection().upsert(testSubjectCollectionEntity(SUBJECT))
        @Suppress("DEPRECATION")
        dao.upsert(
            episodes.mapIndexed { index, (episodeId, type) ->
                EpisodeCollectionEntity(
                    subjectId = SUBJECT, episodeId = episodeId, episodeType = null, name = "", nameCn = "",
                    airDate = PackedDate.Invalid, comment = 0, desc = "", sort = EpisodeSort(index + 1),
                    sortNumber = index + 1f, selfCollectionType = type, lastFetched = 0,
                )
            },
        )
    }

    @Test
    fun `请求还没回来时本地已经改了`() = realTimeTest {
        insertEpisodes(EPISODE to WISH)
        val gate = CompletableDeferred<Unit>()
        remote.behaviors += { gate.await() }

        val job = launchDetached { set(DONE) }
        remote.started.first { it == 1 }
        assertEquals(DONE, local())
        gate.complete(Unit)
        job.join()
        assertEquals(DONE, local())
        assertEquals(listOf(DONE), remote.calls)
    }

    @Test
    fun `服务端明确拒绝 (4xx) 不重试, 改回原来的`() = realTimeTest {
        insertEpisodes(EPISODE to WISH)
        val rejected = badRequest()
        remote.behaviors += { throw rejected }

        assertFailsWith<ClientRequestException> { set(DONE) }
        assertEquals(WISH, local())
        assertEquals(listOf(DONE), remote.calls)
    }

    @Test
    fun `临时失败重试成功, 本地保持新值`() = realTimeTest {
        insertEpisodes(EPISODE to WISH)
        remote.behaviors += { throw IOException("reset") }

        set(DONE)
        assertEquals(DONE, local())
        assertEquals(listOf(DONE, DONE), remote.calls)
    }

    @Test
    fun `连点时前一次失败, 后一次已经改过就不改回去`() = realTimeTest {
        insertEpisodes(EPISODE to WISH)
        val firstGate = CompletableDeferred<Unit>()
        val rejected = badRequest()
        remote.behaviors += { firstGate.await(); throw rejected }

        val first = launchDetached { assertFailsWith<ClientRequestException> { set(DONE) } }
        remote.started.first { it == 1 }
        set(DROPPED)
        assertEquals(DROPPED, local())

        firstGate.complete(Unit)
        first.join()
        assertEquals(DROPPED, local())
    }

    @Test
    fun `请求期间旧数据落库把本地盖回旧值, 成功后补写`() = realTimeTest {
        insertEpisodes(EPISODE to WISH)
        val gate = CompletableDeferred<Unit>()
        remote.behaviors += { gate.await() }

        val job = launchDetached { set(DONE) }
        remote.started.first { it == 1 }
        // 进页时就发出的刷新这时才落库, 带的是改之前的状态
        dao.updateSelfCollectionType(SUBJECT, EPISODE, WISH)
        gate.complete(Unit)
        job.join()
        assertEquals(DONE, local())
    }

    @Test
    fun `被取消也改回去`() = realTimeTest {
        insertEpisodes(EPISODE to WISH)
        remote.behaviors += { CompletableDeferred<Unit>().await() }

        val job = launchDetached { set(DONE) }
        remote.started.first { it == 1 }
        assertEquals(DONE, local())
        job.cancel()
        job.join()
        assertEquals(WISH, local())
    }

    @Test
    fun `全部标为看过 - 本地先全改, 失败逐集改回, 期间又改过的那集不动`() = realTimeTest {
        insertEpisodes(10 to WISH, 11 to DONE, 12 to NOT_COLLECTED, 13 to WISH)
        val gate = CompletableDeferred<Unit>()
        val rejected = badRequest()
        remote.behaviors += { gate.await(); throw rejected }

        val job = launchDetached { assertFailsWith<ClientRequestException> { setAllWatched() } }
        remote.started.first { it == 1 }
        assertEquals(listOf(DONE, DONE, DONE, DONE), listOf(local(10), local(11), local(12), local(13)))
        dao.updateSelfCollectionType(SUBJECT, 13, DROPPED) // 请求期间用户单独改了一集
        gate.complete(Unit)
        job.join()
        assertEquals(listOf(WISH, DONE, NOT_COLLECTED, DROPPED), listOf(local(10), local(11), local(12), local(13)))
    }

    @Test
    fun `全部标为看过 - 请求期间旧数据落库, 成功后补写`() = realTimeTest {
        insertEpisodes(10 to WISH, 11 to NOT_COLLECTED)
        val gate = CompletableDeferred<Unit>()
        remote.behaviors += { gate.await() }

        val job = launchDetached { setAllWatched() }
        remote.started.first { it == 1 }
        dao.updateSelfCollectionType(SUBJECT, 10, WISH)
        gate.complete(Unit)
        job.join()
        assertEquals(listOf(DONE, DONE), listOf(local(10), local(11)))
    }

    private companion object {
        const val SUBJECT = 1
        const val EPISODE = 10
    }
}
