/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import me.him188.ani.app.tools.TimeFormatter
import me.him188.ani.app.ui.comment.BangumiStickers
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.comment.UICommentReaction
import me.him188.ani.app.ui.comment.UICommentSource
import me.him188.ani.app.ui.comment.UIRichText
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeComment
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentReply
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_NATIVE_STICKER_PLACEHOLDER
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeInlineText
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeSticker
import me.him188.ani.app.ui.richtext.UIRichElement

/*
 * 电视上的评论一律按这里转: 列表卡 (原生, [TvNativeComment]) 与整楼视图 ([TvQuotedComment] / [TvThreadReply]) 用的是同一份 UIComment,
 * 详情页评价、人物 / 角色吐槽箱、本集评论都一样 —— 有回复就带回复, 有评分就带评分.
 */

/** 列表卡里一楼最多摆几条回复, 再多的写「共 N 条回复」, 点开整楼看. */
internal const val TV_COMMENT_PREVIEW_REPLIES = 2

/** 楼下的回复 (Ani 源不返回楼中回复的关系, 不列, 同 bangumi 网页以外的来源). */
internal val UIComment.threadReplies: List<UIComment>
    get() = if (source == UICommentSource.ANI) emptyList() else briefReplies

/** 列表里的一楼: 主楼 + 前 [TV_COMMENT_PREVIEW_REPLIES] 条回复. [showRating] = 有评分就画星 (条目评价). */
internal fun UIComment.toTvNativeComment(formatter: TimeFormatter, showRating: Boolean): TvNativeComment {
    val replies = threadReplies
    return TvNativeComment(
        key = stableId,
        avatarUrl = author?.avatarUrl,
        name = displayAuthorName(),
        sign = author?.sign,
        meta = metaText(formatter),
        rating = rating?.takeIf { showRating && it > 0 },
        body = content.toTvNativeInlineText(),
        deleted = deleted,
        reactions = reactions.toTvNativeReactions(),
        replies = replies.take(TV_COMMENT_PREVIEW_REPLIES).map { reply ->
            TvNativeCommentReply(
                key = reply.stableId,
                name = reply.displayAuthorName(),
                meta = reply.metaText(formatter),
                replyToName = reply.replyTo?.authorName,
                body = reply.content.toTvNativeInlineText(),
                deleted = reply.deleted,
            )
        },
        replyCount = replies.size,
    )
}

/** 整楼视图里的主楼. [showRating] = 有评分就画星 (条目评价). */
internal fun UIComment.toTvQuotedComment(timeFormatter: TimeFormatter, showRating: Boolean = false): TvQuotedComment = TvQuotedComment(
    authorName = displayAuthorName(),
    timeText = timeFormatter.formatCommentTime(createdAt),
    blocks = content.toCommentBlocks(),
    reactions = reactions.toTvReactions(),
    authorSign = author?.sign,
    floorText = floorText(),
    deleted = deleted,
    avatarUrl = author?.avatarUrl,
    rating = rating?.takeIf { showRating && it > 0 },
)

/** 整楼视图里这一楼的全部回复, 由上往下. */
internal fun UIComment.toTvThreadReplies(timeFormatter: TimeFormatter): List<TvThreadReply> =
    threadReplies.map { TvThreadReply(it.toTvQuotedComment(timeFormatter), it.replyTo?.authorName) }

/** 楼层号的展示: `#3` / `#3-2`. */
internal fun UIComment.floorText(): String? = floor?.let { "#$it" }

/** 楼层号在时间前面, 同 bangumi 网页的「#3 - 时间」. */
private fun UIComment.metaText(formatter: TimeFormatter): String {
    val time = formatter.formatCommentTime(createdAt)
    return floorText()?.let { if (time.isEmpty()) it else "$it  $time" } ?: time
}

/** 昵称缺失时退到用户 id (匿名 / 注销用户). */
internal fun UIComment.displayAuthorName(): String = author?.nickname ?: author?.id.orEmpty()

/**
 * 回应 -> 整楼视图用的展示模型, 按人数降序 (与 bgm 网页端一致).
 *
 * 认不出的编号整条丢掉: 回应只有"表情 + 人数"两样, 表情画不出来就只剩一个数字, 没有意义.
 * 注意编号要过 [BangumiStickers.reactionStickerTokenOf] 换算, 不能直接当表情代码用.
 */
internal fun List<UICommentReaction>.toTvReactions(): List<TvCommentReaction> =
    sortedByDescending { it.count }
        .mapNotNull { reaction ->
            val token = BangumiStickers.reactionStickerTokenOf(reaction.value) ?: return@mapNotNull null
            val imageUrl = BangumiStickers.imageUrlOf(token) ?: return@mapNotNull null
            TvCommentReaction(
                sticker = UIRichElement.Annotated.Sticker(id = token, resource = null, imageUrl = imageUrl),
                count = reaction.count,
            )
        }

/** 列表卡里的回应: 表情 + 人数排成一行 (同 [toTvReactions] 的顺序与取舍). */
private fun List<UICommentReaction>.toTvNativeReactions(): TvNativeInlineText {
    val reactions = toTvReactions()
    if (reactions.isEmpty()) return TvNativeInlineText.Empty
    val text = StringBuilder()
    val stickers = mutableListOf<TvNativeSticker>()
    for (reaction in reactions) {
        val url = reaction.sticker.imageUrl ?: continue
        if (text.isNotEmpty()) text.append("   ")
        stickers += TvNativeSticker(text.length, url)
        text.append(TV_NATIVE_STICKER_PLACEHOLDER).append(' ').append(reaction.count)
    }
    return TvNativeInlineText(text.toString(), stickers)
}

/** 时间戳 -> 展示文本, 0 = 未知, 显示空. 非组合环境 (点击回调、翻楼) 里也要算, 所以不用 @Composable 的 formatDateTime. */
internal fun TimeFormatter.formatCommentTime(millis: Long): String =
    if (millis == 0L) "" else format(millis)

/**
 * 富文本压成一段文字 + 行内表情 (列表卡紧凑形态), 见 [TvInlineText]. 图片只留 `[图片]` 占位, 点开整楼才是真图 (见 [toCommentBlocks]).
 * 引用块跳过 —— 拍平进正文会把别人被引用的话混成本人说的.
 */
internal fun UIRichText.toInlineText(): TvInlineText = inlineTextBuilder().build()

/** 同 [toInlineText], 给原生列表 (表情是占位符 + 地址, 见 [TvNativeInlineText]). */
internal fun UIRichText.toTvNativeInlineText(): TvNativeInlineText = inlineTextBuilder().buildNative()

private fun UIRichText.inlineTextBuilder(): TvInlineTextBuilder {
    val builder = TvInlineTextBuilder()
    elements.forEach { element ->
        when (element) {
            is UIRichElement.AnnotatedText -> builder.append(element.slice)
            is UIRichElement.Quote -> {}
            is UIRichElement.Image -> builder.append(TV_IMAGE_FALLBACK_TEXT)
        }
    }
    return builder
}

/**
 * 富文本拆成整楼视图用的正文块: 文本段与图片按原顺序排列, 相邻文本并成一段.
 *
 * 引用块与 [toInlineText] 一样跳过 —— 拍平进正文会把别人被引用的话混成本人说的,
 * 而在这个"整块单焦点"的引用区里没有地方摆引用的边框和出处.
 */
internal fun UIRichText.toCommentBlocks(): List<TvCommentBlock> {
    val blocks = mutableListOf<TvCommentBlock>()
    val pending = TvInlineTextBuilder()

    fun flushText() {
        val text = pending.build()
        if (!text.isEmpty) blocks += TvCommentBlock.Text(text)
        pending.clear()
    }

    elements.forEach { element ->
        when (element) {
            is UIRichElement.AnnotatedText -> pending.append(element.slice)

            is UIRichElement.Image -> {
                flushText()
                blocks += TvCommentBlock.Image(element.imageUrl)
            }

            is UIRichElement.Quote -> {}
        }
    }
    flushText()
    return blocks
}
