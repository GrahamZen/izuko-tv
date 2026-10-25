/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import me.him188.ani.app.data.models.UserInfo
import me.him188.ani.app.tools.TimeFormatter
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.comment.UICommentReplyTarget
import me.him188.ani.app.ui.comment.UICommentSource
import me.him188.ani.app.ui.comment.UIRichText
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_NATIVE_STICKER_PLACEHOLDER
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeSticker
import me.him188.ani.app.ui.richtext.UIRichElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 电视上评论的统一转换: 列表卡只摆前两条回复、写一共多少条, Ani 源不列楼中回复; 原生文字里表情的占位与回落.
 */
class TvCommentModelsTest {
    private val formatter = TimeFormatter()

    @Test
    fun `list card shows the first two replies and the total`() {
        val comment = comment(
            "1",
            floor = "3",
            replies = listOf(
                comment("2", floor = "3-1"),
                comment("3", floor = "3-2", replyTo = UICommentReplyTarget("2", "二楼")),
                comment("4", floor = "3-3"),
            ),
        )
        val card = comment.toTvNativeComment(formatter, showRating = false)
        assertEquals(listOf("2", "3"), card.replies.map { it.key })
        assertEquals(3, card.replyCount)
        assertNull(card.replies[0].replyToName)
        assertEquals("二楼", card.replies[1].replyToName)
        assertEquals("#3", card.meta)
    }

    @Test
    fun `ani comments list no replies`() {
        val comment = comment("1", source = UICommentSource.ANI, replies = listOf(comment("2")))
        val card = comment.toTvNativeComment(formatter, showRating = false)
        assertEquals(emptyList(), card.replies)
        assertEquals(0, card.replyCount)
        assertEquals(emptyList(), comment.toTvThreadReplies(formatter))
    }

    @Test
    fun `rating shows only when asked`() {
        val comment = comment("1", rating = 8)
        assertEquals(8, comment.toTvNativeComment(formatter, showRating = true).rating)
        assertNull(comment.toTvNativeComment(formatter, showRating = false).rating)
        assertEquals(8, comment.toTvQuotedComment(formatter, showRating = true).rating)
    }

    @Test
    fun `thread keeps every reply and its target`() {
        val comment = comment(
            "1",
            replies = listOf(comment("2"), comment("3", replyTo = UICommentReplyTarget("2", "二楼")), comment("4")),
        )
        val replies = comment.toTvThreadReplies(formatter)
        assertEquals(3, replies.size)
        assertEquals(listOf(null, "二楼", null), replies.map { it.replyToName })
    }

    @Test
    fun `native text puts a placeholder where each sticker is`() {
        val text = UIRichText(
            listOf(
                UIRichElement.AnnotatedText(
                    listOf(
                        UIRichElement.Annotated.Text("  哈"),
                        UIRichElement.Annotated.Sticker("(bgm38)", resource = null, imageUrl = "https://a/38.gif"),
                        UIRichElement.Annotated.Text("好 "),
                    ),
                ),
            ),
        ).toTvNativeInlineText()
        assertEquals("哈${TV_NATIVE_STICKER_PLACEHOLDER}好", text.text)
        assertEquals(listOf(TvNativeSticker(1, "https://a/38.gif")), text.stickers)
    }

    @Test
    fun `stickers without an image address fall back to text`() {
        val text = UIRichText(
            listOf(
                UIRichElement.AnnotatedText(
                    listOf(UIRichElement.Annotated.Sticker("(x)", resource = null, imageUrl = null)),
                ),
            ),
        ).toTvNativeInlineText()
        assertEquals("[表情]", text.text)
        assertEquals(emptyList(), text.stickers)
    }

    private fun comment(
        id: String,
        floor: String? = null,
        replies: List<UIComment> = emptyList(),
        replyTo: UICommentReplyTarget? = null,
        source: UICommentSource = UICommentSource.BANGUMI,
        rating: Int? = null,
    ) = UIComment(
        id = id.toLong(),
        stableId = id,
        author = UserInfo(id = id, username = null, nickname = "user$id"),
        content = UIRichText(emptyList()),
        createdAt = 0,
        reactions = emptyList(),
        briefReplies = replies,
        replyCount = replies.size,
        rating = rating,
        source = source,
        replyTo = replyTo,
        floor = floor,
    )
}
