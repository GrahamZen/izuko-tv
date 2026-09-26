/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.repository.FakeRemote
import me.him188.ani.app.data.repository.badRequest
import me.him188.ani.app.data.repository.launchDetached
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DOING
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DONE
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.WISH
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 改收藏类型 ([setCollectionTypeLocalFirst]): 本地先改 (界面上的收藏状态立刻变), 请求在后面发;
 * 成功后补写被旧数据盖回的, 最终失败才改回 (连同更新时间), 期间又改过的以后面那次为准.
 */
class SubjectCollectionTypeLocalFirstTest {
    private val database = createTestAniDatabase()
    private val dao = database.subjectCollection()
    private val remote = FakeRemote<UnifiedCollectionType>()

    @AfterTest
    fun close() = database.close()

    private suspend fun set(type: UnifiedCollectionType) = dao.setCollectionTypeLocalFirst(SUBJECT, type) { remote.send(type) }

    private suspend fun local() = dao.findById(SUBJECT).first()

    @Test
    fun `请求还没回来时本地已经改了`() = realTimeTest {
        dao.upsert(testSubjectCollectionEntity(SUBJECT, WISH))
        val gate = CompletableDeferred<Unit>()
        remote.behaviors += { gate.await() }

        val job = launchDetached { set(DOING) }
        remote.started.first { it == 1 }
        assertEquals(DOING, local()?.collectionType)
        gate.complete(Unit)
        job.join()
        assertEquals(DOING, local()?.collectionType)
    }

    @Test
    fun `服务端拒绝, 类型与更新时间都改回原来的`() = realTimeTest {
        dao.upsert(testSubjectCollectionEntity(SUBJECT, WISH, lastUpdated = 123))
        val rejected = badRequest()
        remote.behaviors += { throw rejected }

        assertFailsWith<ClientRequestException> { set(DOING) }
        assertEquals(WISH, local()?.collectionType)
        assertEquals(123L, local()?.lastUpdated)
    }

    @Test
    fun `连点时前一次失败, 后一次已经改过就不改回去`() = realTimeTest {
        dao.upsert(testSubjectCollectionEntity(SUBJECT, WISH))
        val firstGate = CompletableDeferred<Unit>()
        val rejected = badRequest()
        remote.behaviors += { firstGate.await(); throw rejected }

        val first = launchDetached { assertFailsWith<ClientRequestException> { set(DOING) } }
        remote.started.first { it == 1 }
        set(DONE)
        firstGate.complete(Unit)
        first.join()
        assertEquals(DONE, local()?.collectionType)
    }

    @Test
    fun `请求期间旧数据落库把本地盖回旧值, 成功后补写`() = realTimeTest {
        dao.upsert(testSubjectCollectionEntity(SUBJECT, WISH))
        val gate = CompletableDeferred<Unit>()
        remote.behaviors += { gate.await() }

        val job = launchDetached { set(DOING) }
        remote.started.first { it == 1 }
        dao.updateType(SUBJECT, WISH)
        gate.complete(Unit)
        job.join()
        assertEquals(DOING, local()?.collectionType)
    }

    private companion object {
        const val SUBJECT = 1
    }
}
