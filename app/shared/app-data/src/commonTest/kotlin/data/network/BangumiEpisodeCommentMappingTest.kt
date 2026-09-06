/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import me.him188.ani.app.data.models.episode.EpisodeCommentSource
import me.him188.ani.datasources.bangumi.next.models.BangumiNextAvatar
import me.him188.ani.datasources.bangumi.next.models.BangumiNextComment
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCommentBase
import me.him188.ani.datasources.bangumi.next.models.BangumiNextReaction
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSimpleUser
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSlimUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 剧集评论从 p1 的形状映射到 [me.him188.ani.app.data.models.episode.EpisodeComment].
 *
 * 这里钉住的三件事都是换数据源时会悄悄变掉的:
 * - **回复关系**: 直连之后 `relatedID` 是真值 (原先经 Ani 合并时拿不到, 只能从正文开头那条
 *   `[quote][b]昵称[/b] 说:` 反推). 但它指向主楼时只是普通楼内回复, 不能当成"回复某条回复".
 * - **表情回应的编号**: 传输值是 `"bgm" + 回应编号`, 那个数字**不是**表情代码 (两者差 16),
 *   见 `BangumiStickers.reactionStickerToken`.
 * - **时间戳单位**: p1 给秒, 领域模型要毫秒.
 */
class BangumiEpisodeCommentMappingTest {
    private fun user(id: Int, nickname: String) = BangumiNextSlimUser(
        id = id,
        username = "u$id",
        nickname = nickname,
        avatar = BangumiNextAvatar(small = "s", medium = "m", large = "l"),
        group = 0,
        sign = "",
        joinedAt = 0,
        isFriend = false,
    )

    private fun reply(
        id: Int,
        mainID: Int,
        relatedID: Int,
        content: String = "reply$id",
    ) = BangumiNextCommentBase(
        id = id,
        mainID = mainID,
        creatorID = 1,
        relatedID = relatedID,
        createdAt = 1_700_000_000,
        content = content,
        state = 0,
        user = user(1, "someone"),
    )

    private fun comment(
        id: Int = 100,
        replies: List<BangumiNextCommentBase> = emptyList(),
        reactions: List<BangumiNextReaction>? = null,
    ) = BangumiNextComment(
        id = id,
        mainID = 0,
        creatorID = 1,
        relatedID = 0,
        createdAt = 1_700_000_000,
        content = "main",
        state = 0,
        replies = replies,
        user = user(1, "someone"),
        reactions = reactions,
    )

    @Test
    fun `回复指向同层的另一条回复`() {
        val mapped = comment(
            id = 100,
            replies = listOf(reply(id = 201, mainID = 100, relatedID = 0), reply(id = 202, mainID = 100, relatedID = 201)),
        ).toEpisodeComment(episodeId = 1L, selfUserId = null)

        assertEquals(2, mapped.replies.size)
        // 第一条直接回复主楼
        assertNull(mapped.replies[0].replyToCommentId)
        // 第二条回复的是第一条
        assertEquals("201", mapped.replies[1].replyToCommentId)
    }

    @Test
    fun `relatedID 指向主楼时不算回复某条回复`() {
        val mapped = comment(
            id = 100,
            replies = listOf(reply(id = 201, mainID = 100, relatedID = 100)),
        ).toEpisodeComment(episodeId = 1L, selfUserId = null)

        assertNull(mapped.replies.single().replyToCommentId)
    }

    @Test
    fun `表情回应带 bgm 前缀_选中与否看自己在不在里面`() {
        val reactions = listOf(
            BangumiNextReaction(
                users = listOf(
                    BangumiNextSimpleUser(id = 7, username = "a", nickname = "A"),
                    BangumiNextSimpleUser(id = 8, username = "b", nickname = "B"),
                ),
                value = 54,
            ),
        )

        val mine = comment(reactions = reactions).toEpisodeComment(episodeId = 1L, selfUserId = 8)
        assertEquals("bgm54", mine.reactions.single().value)
        assertEquals(2, mine.reactions.single().count)
        assertTrue(mine.reactions.single().selected)

        val others = comment(reactions = reactions).toEpisodeComment(episodeId = 1L, selfUserId = 9)
        assertEquals(false, others.reactions.single().selected)

        // 未登录时一律未选中
        val anonymous = comment(reactions = reactions).toEpisodeComment(episodeId = 1L, selfUserId = null)
        assertEquals(false, anonymous.reactions.single().selected)
    }

    @Test
    fun `时间戳从秒换成毫秒_来源恒为 bangumi`() {
        val mapped = comment().toEpisodeComment(episodeId = 42L, selfUserId = null)
        assertEquals(1_700_000_000_000L, mapped.createdAt)
        assertEquals(EpisodeCommentSource.BANGUMI, mapped.source)
        assertEquals(42L, mapped.episodeId)
        assertEquals("100", mapped.sourceCommentId)
    }
}
