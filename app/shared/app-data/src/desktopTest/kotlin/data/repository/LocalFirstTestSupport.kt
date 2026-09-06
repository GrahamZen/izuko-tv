/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import java.util.Collections
import kotlin.test.assertFailsWith

// 先改本地、再发请求的写操作 (writeLocalFirst) 的测试工具

/** 每次 [send] 按顺序取一个行为 (没有了就直接成功); 发的内容记在 [calls], 已经开始几次记在 [started]. */
internal class FakeRemote<T> {
    val calls: MutableList<T> = Collections.synchronizedList(mutableListOf())
    val behaviors: MutableList<suspend () -> Unit> = Collections.synchronizedList(mutableListOf())
    val started = MutableStateFlow(0)

    suspend fun send(value: T) {
        calls += value
        val behavior = synchronized(behaviors) { behaviors.removeFirstOrNull() }
        started.update { it + 1 }
        behavior?.invoke()
    }
}

/** 真实调度器上跑: Room 在自己的线程上, 失败重试的退避也是真的等. */
internal fun realTimeTest(block: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

/** 不挂在测试协程上: 取消它不影响测试本身. */
internal fun launchDetached(block: suspend () -> Unit) = CoroutineScope(Dispatchers.Default).launch { block() }

/** 服务端的明确拒绝 (400), 与真实请求抛出的是同一种异常. */
internal suspend fun badRequest(): ClientRequestException {
    val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.BadRequest) }) { expectSuccess = true }
    return try {
        assertFailsWith<ClientRequestException> { client.get("https://api.bgm.tv/v0/users/-/collections/1") }
    } finally {
        client.close()
    }
}

/** 一条条目收藏 (选集表对它有外键, 插选集前要先有它). */
internal fun testSubjectCollectionEntity(
    subjectId: Int,
    collectionType: UnifiedCollectionType = UnifiedCollectionType.DOING,
    lastUpdated: Long = 0,
) = SubjectCollectionEntity(
    subjectId = subjectId, name = "", nameCn = "", summary = "", nsfw = false, imageLarge = "", totalEpisodes = 12,
    airDate = PackedDate.Invalid, aliases = emptyList(), tags = emptyList(), collectionStats = SubjectCollectionStats.Zero,
    ratingInfo = RatingInfo.Empty, completeDate = PackedDate.Invalid, selfRatingInfo = SelfRatingInfo.Empty,
    collectionType = collectionType, recurrence = null, lastUpdated = lastUpdated, lastFetched = 0,
    cachedStaffUpdated = 0, cachedCharactersUpdated = 0,
)
