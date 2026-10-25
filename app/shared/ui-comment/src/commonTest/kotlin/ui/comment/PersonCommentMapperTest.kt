/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.comment

import me.him188.ani.app.data.models.UserInfo
import me.him188.ani.app.data.models.person.PersonComment
import me.him188.ani.app.data.models.person.PersonCommentReaction
import me.him188.ani.app.data.models.person.PersonCommentSource
import me.him188.ani.app.ui.comment.CommentMapperContext.parseToUIComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 人物 / 角色吐槽箱的一楼转成 UIComment: 同单集评论, 带楼中回复 (回复谁按同楼里的昵称认)、楼层、删除标记与贴贴.
 */
class PersonCommentMapperTest {
    @Test
    fun `replies keep floor and reply target from the same thread`() {
        val ui = comment(
            "10",
            floor = "2",
            reactions = listOf(PersonCommentReaction("bgm1", 3, selected = false)),
            replies = listOf(
                comment("11", floor = "2-1", nickname = "甲"),
                comment("12", floor = "2-2", replyTo = "11"),
                comment("13", floor = "2-3", replyTo = "99"),
            ),
        ).parseToUIComment()
        assertEquals("2", ui.floor)
        assertEquals(1, ui.reactions.size)
        assertEquals(listOf("2-1", "2-2", "2-3"), ui.briefReplies.map { it.floor })
        assertNull(ui.briefReplies[0].replyTo)
        assertEquals("甲", ui.briefReplies[1].replyTo?.authorName)
        // 被回复的那条不在这一楼里: 不写回复谁
        assertNull(ui.briefReplies[2].replyTo)
        assertEquals(3, ui.replyCount)
    }

    @Test
    fun `deleted floors and replies stay marked`() {
        val ui = comment("10", deleted = true, replies = listOf(comment("11", deleted = true))).parseToUIComment()
        assertTrue(ui.deleted)
        assertTrue(ui.briefReplies.single().deleted)
    }

    private fun comment(
        id: String,
        floor: String? = null,
        nickname: String = "user$id",
        replyTo: String? = null,
        deleted: Boolean = false,
        reactions: List<PersonCommentReaction> = emptyList(),
        replies: List<PersonComment> = emptyList(),
    ) = PersonComment(
        stableId = id,
        source = PersonCommentSource.BANGUMI,
        sourceCommentId = id,
        createdAt = 0,
        content = if (deleted) "" else "text $id",
        author = UserInfo(id = id, username = null, nickname = nickname),
        reactions = reactions,
        replies = replies,
        replyToCommentId = replyTo,
        floor = floor,
        deleted = deleted,
    )
}
