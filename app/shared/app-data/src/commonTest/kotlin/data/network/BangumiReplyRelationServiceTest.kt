/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.episode.EpisodeComment
import me.him188.ani.app.data.models.episode.EpisodeCommentSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class BangumiReplyRelationServiceTest {
    private var nowMillis = 0L
    private var fetchCount = 0
    private var relations: Map<String, String>? = mapOf("r2" to "r1")

    /** 每次取要花多久 (虚拟时间); 用来验证"等不到就先把评论发出去". */
    private var fetchDelayMillis = 0L

    private fun TestScope.createService() = BangumiReplyRelationService(
        nowMillis = { nowMillis },
        // 用 backgroundScope: 请求的宿主必须独立于调用方, 调用方超时不能把它杀掉
        scope = backgroundScope,
        fetchRelations = {
            fetchCount++
            if (fetchDelayMillis > 0) delay(fetchDelayMillis)
            relations
        },
    )

    @Test
    fun `补上关系`() = runTest {
        val result = createService().fillInReplyTargets(1, listOf(bangumiComment()))
        assertNull(result[0].replies[0].replyToCommentId)
        assertEquals("r1", result[0].replies[1].replyToCommentId)
    }

    @Test
    fun `不覆盖已经从正文引用认出来的`() = runTest {
        relations = mapOf("r2" to "r-别的")
        val comments = listOf(bangumiComment(secondReplyTarget = "r1"))
        val result = createService().fillInReplyTargets(1, comments)
        assertEquals("r1", result[0].replies[1].replyToCommentId)
    }

    @Test
    fun `没有需要补的就不发请求`() = runTest {
        val service = createService()
        // Ani 来源
        service.fillInReplyTargets(1, listOf(bangumiComment().copy(source = EpisodeCommentSource.ANI)))
        // 只有一条回复
        service.fillInReplyTargets(1, listOf(bangumiComment().let { it.copy(replies = it.replies.take(1)) }))
        // 全部已经认出来了
        service.fillInReplyTargets(1, listOf(bangumiComment(firstReplyTarget = "r0", secondReplyTarget = "r1")))
        assertEquals(0, fetchCount)
    }

    @Test
    fun `同一集只取一次`() = runTest {
        val service = createService()
        service.fillInReplyTargets(1, listOf(bangumiComment()))
        service.fillInReplyTargets(1, listOf(bangumiComment()))
        assertEquals(1, fetchCount)
    }

    @Test
    fun `取不到时退避, 到期后还会再试`() = runTest {
        relations = null
        val service = createService()
        service.fillInReplyTargets(1, listOf(bangumiComment()))
        assertEquals(1, fetchCount)

        // 退避期内不再打扰它 (否则每翻一页都要等一次超时)
        nowMillis += 1.minutes.inWholeMilliseconds
        service.fillInReplyTargets(1, listOf(bangumiComment()))
        assertEquals(1, fetchCount)

        // 但失败不能被记成"这一集没有关系" —— 到期后必须重来一次
        nowMillis += 10.minutes.inWholeMilliseconds
        relations = mapOf("r2" to "r1")
        val result = service.fillInReplyTargets(1, listOf(bangumiComment()))
        assertEquals(2, fetchCount)
        assertEquals("r1", result[0].replies[1].replyToCommentId)
    }

    @Test
    fun `换一集重新取`() = runTest {
        val service = createService()
        service.fillInReplyTargets(1, listOf(bangumiComment()))
        service.fillInReplyTargets(2, listOf(bangumiComment()))
        assertEquals(2, fetchCount)
    }

    @Test
    fun `来回切集命中缓存, 不重发`() = runTest {
        val service = createService()
        service.fillInReplyTargets(1, listOf(bangumiComment()))
        service.fillInReplyTargets(2, listOf(bangumiComment()))
        // 单格缓存时代第 1 集已被第 2 集顶掉, 这里会发第 3 次请求
        service.fillInReplyTargets(1, listOf(bangumiComment()))
        service.fillInReplyTargets(2, listOf(bangumiComment()))
        assertEquals(2, fetchCount)
    }

    @Test
    fun `超过容量淘汰最久未用的那集`() = runTest {
        val service = createService()
        // 容量 4: 取第 5 集时应淘汰最老的第 1 集
        (1L..5L).forEach { service.fillInReplyTargets(it, listOf(bangumiComment())) }
        assertEquals(5, fetchCount)
        // 第 5 集还在
        service.fillInReplyTargets(5, listOf(bangumiComment()))
        assertEquals(5, fetchCount)
        // 第 1 集已被淘汰, 要重取
        service.fillInReplyTargets(1, listOf(bangumiComment()))
        assertEquals(6, fetchCount)
    }

    @Test
    fun `取得慢就先把评论发出去, 不等满`() = runTest {
        fetchDelayMillis = 2_500 // > BLOCKING_BUDGET
        val service = createService()
        val result = service.fillInReplyTargets(1, listOf(bangumiComment()))
        // 没等到关系: 原样返回 (正文引用推断的那份还在, 只是少一行"回复 @某人")
        assertNull(result[0].replies[1].replyToCommentId)
        assertEquals(1, fetchCount)
    }

    @Test
    fun `没等满的那次请求继续在后台跑完, 下次直接命中`() = runTest {
        fetchDelayMillis = 2_500
        val service = createService()
        service.fillInReplyTargets(1, listOf(bangumiComment()))
        // 调用方已经放弃等待, 但请求挂在 service 自己的作用域上, 不该被一起取消
        // 用前台 delay 推进虚拟时间: advanceUntilIdle() 不跑 backgroundScope 里的任务
        delay(3_000)
        val result = service.fillInReplyTargets(1, listOf(bangumiComment()))
        assertEquals("r1", result[0].replies[1].replyToCommentId)
        assertEquals(1, fetchCount) // 后台那次的结果, 没有重发
    }

    @Test
    fun `同一集并发翻页只发一次`() = runTest {
        fetchDelayMillis = 100
        val service = createService()
        val pages = List(3) { async { service.fillInReplyTargets(1, listOf(bangumiComment())) } }
        pages.forEach { it.await() }
        assertEquals(1, fetchCount)
    }

    private fun bangumiComment(
        firstReplyTarget: String? = null,
        secondReplyTarget: String? = null,
    ) = EpisodeComment(
        stableId = "c1",
        source = EpisodeCommentSource.BANGUMI,
        sourceCommentId = "c1",
        commentId = "c1",
        episodeId = 1,
        createdAt = 0,
        content = "主楼",
        author = null,
        replies = listOf(
            reply("r1", firstReplyTarget),
            reply("r2", secondReplyTarget),
        ),
    )

    private fun reply(id: String, replyToCommentId: String?) = EpisodeComment(
        stableId = id,
        source = EpisodeCommentSource.BANGUMI,
        sourceCommentId = id,
        commentId = id,
        episodeId = 1,
        createdAt = 0,
        content = "回复",
        author = null,
        replyToCommentId = replyToCommentId,
    )
}
