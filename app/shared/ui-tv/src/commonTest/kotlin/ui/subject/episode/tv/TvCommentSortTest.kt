/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import me.him188.ani.app.data.models.preference.EpisodeCommentSort
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.comment.UICommentReaction
import me.him188.ani.app.ui.comment.UIRichText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 评论面板的四种排序: 只排主楼, 稳定 (计数相同照楼层先后), 时间倒序时同一秒的也倒过来.
 */
class TvCommentSortTest {
    // 楼层顺序 (接口给的): 1 楼与 2 楼同一秒发
    private val threads = listOf(
        comment("1", createdAt = 1000, reactions = listOf(2, 1), replies = 0),
        comment("2", createdAt = 1000, reactions = listOf(), replies = 4),
        comment("3", createdAt = 2000, reactions = listOf(5), replies = 1),
        comment("4", createdAt = 3000, reactions = listOf(3), replies = 4),
    )

    private fun sortedIds(sort: EpisodeCommentSort) = tvSortCommentThreads(threads, sort).map { it.stableId }

    @Test
    fun `time ascending keeps floor order`() {
        assertEquals(listOf("1", "2", "3", "4"), sortedIds(EpisodeCommentSort.TIME_ASCENDING))
    }

    @Test
    fun `time descending reverses floor order including same-second ties`() {
        assertEquals(listOf("4", "3", "2", "1"), sortedIds(EpisodeCommentSort.TIME_DESCENDING))
    }

    @Test
    fun `most reactions counts people across all reactions`() {
        // 3 楼 5 人, 1 楼 2+1 = 3 人与 4 楼 3 人同数照楼层, 2 楼没有
        assertEquals(listOf("3", "1", "4", "2"), sortedIds(EpisodeCommentSort.MOST_REACTIONS))
    }

    @Test
    fun `most replies keeps floor order for ties`() {
        assertEquals(listOf("2", "4", "3", "1"), sortedIds(EpisodeCommentSort.MOST_REPLIES))
    }

    private fun comment(id: String, createdAt: Long, reactions: List<Int>, replies: Int) = UIComment(
        id = id.toLong(),
        stableId = id,
        author = null,
        content = UIRichText(emptyList()),
        createdAt = createdAt,
        reactions = reactions.mapIndexed { i, count -> UICommentReaction(value = "bgm$i", count = count, selected = false) },
        briefReplies = emptyList(),
        replyCount = replies,
        rating = null,
    )
}
